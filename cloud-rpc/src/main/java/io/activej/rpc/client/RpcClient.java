/*
 * Copyright (C) 2020 ActiveJ LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.activej.rpc.client;

import io.activej.async.callback.Callback;
import io.activej.async.exception.AsyncTimeoutException;
import io.activej.async.service.ReactiveService;
import io.activej.common.ApplicationSettings;
import io.activej.common.Checks;
import io.activej.common.MemSize;
import io.activej.common.builder.AbstractBuilder;
import io.activej.csp.process.frame.FrameFormat;
import io.activej.datastream.csp.ChannelSerializer;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.jmx.api.attribute.JmxReducers.JmxReducerSum;
import io.activej.jmx.stats.ExceptionStats;
import io.activej.net.socket.tcp.ITcpSocket;
import io.activej.net.socket.tcp.TcpSocket;
import io.activej.net.socket.tcp.TcpSocket.JmxInspector;
import io.activej.promise.Promise;
import io.activej.promise.SettablePromise;
import io.activej.reactor.AbstractNioReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;
import io.activej.reactor.net.SocketSettings;
import io.activej.reactor.nio.NioReactor;
import io.activej.rpc.client.jmx.RpcConnectStats;
import io.activej.rpc.client.jmx.RpcRequestStats;
import io.activej.rpc.client.sender.RpcSender;
import io.activej.rpc.client.sender.strategy.RpcStrategies;
import io.activej.rpc.client.sender.strategy.RpcStrategy;
import io.activej.rpc.protocol.RpcException;
import io.activej.rpc.protocol.RpcMessage;
import io.activej.rpc.protocol.RpcStream;
import io.activej.rpc.server.RpcServer;
import io.activej.serializer.BinarySerializer;
import io.activej.serializer.SerializerFactory;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.VisibleForTesting;
import org.slf4j.Logger;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executor;

import static io.activej.common.Checks.checkState;
import static io.activej.common.Utils.nonNullElseGet;
import static io.activej.common.Utils.not;
import static io.activej.net.socket.tcp.SslTcpSocket.wrapClientSocket;
import static io.activej.reactor.Reactive.checkInReactorThread;
import static io.activej.reactor.Reactor.checkInReactorThread;
import static java.util.stream.Collectors.toList;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Sends requests to the specified servers according to defined
 * {@link RpcStrategy} strategy.
 * <p>
 * Example. Consider a client which sends a {@code Request} and receives a
 * {@code Response} from some {@link RpcServer}. To implement such kind of
 * client it's necessary to proceed with following steps:
 * <ul>
 * <li>Create request-response classes for the client</li>
 * <li>Create a request handler for specified types</li>
 * <li>Create {@code RpcClient} and adjust it</li>
 * </ul>
 *
 * @see RpcStrategy
 * @see RpcServer
 */
public final class RpcClient extends AbstractNioReactive
	implements IRpcClient, ReactiveService, ReactiveJmxBeanWithStats {
	private static final boolean CHECKS = Checks.isEnabled(RpcClient.class);

	public static final Duration DEFAULT_CONNECT_TIMEOUT = ApplicationSettings.getDuration(RpcClient.class, "connectTimeout", Duration.ZERO);
	public static final Duration DEFAULT_RECONNECT_INTERVAL = ApplicationSettings.getDuration(RpcClient.class, "reconnectInterval", Duration.ZERO);
	public static final MemSize DEFAULT_PACKET_SIZE = ApplicationSettings.getMemSize(RpcClient.class, "packetSize", ChannelSerializer.DEFAULT_INITIAL_BUFFER_SIZE);

	private static final RpcException CONNECTION_EXCEPTION = new RpcException("Could not establish connection");
	private static final RpcException SET_STRATEGY_EXCEPTION = new RpcException("Could not change strategy");
	private static final RpcException NO_SENDER_AVAILABLE_EXCEPTION = new RpcException("No senders available");
	private static final RpcException CLIENT_IS_STOPPED = new RpcException("Client is stopped");

	private Logger logger = getLogger(getClass());

	private SocketSettings socketSettings = SocketSettings.defaultInstance();

	// SSL
	private SSLContext sslContext;
	private Executor sslExecutor;

	private RpcStrategy strategy = new NoServersRpcStrategy();
	private final Set<InetSocketAddress> pendingConnections = new HashSet<>();
	private final Map<InetSocketAddress, RpcClientConnection> connections = new HashMap<>();

	private RpcStrategy newStrategy = strategy;
	private boolean newStrategyRetry;
	private SettablePromise<Void> newStrategyPromise;
	private final Set<InetSocketAddress> newConnections = new HashSet<>();

	private MemSize defaultPacketSize = DEFAULT_PACKET_SIZE;
	private @Nullable FrameFormat frameFormat;
	private Duration autoFlushInterval = Duration.ZERO;
	private Duration keepAliveInterval = Duration.ZERO;

	private long connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT.toMillis();
	private long reconnectIntervalMillis = DEFAULT_RECONNECT_INTERVAL.toMillis();

	private BinarySerializer<RpcMessage> requestSerializer;
	private BinarySerializer<RpcMessage> responseSerializer;

	private boolean forcedShutdown;

	private RpcSender requestSender = new NoSenderAvailable();

	private @Nullable SettablePromise<Void> stopPromise;

	private final RpcClientConnectionPool pool = connections::get;

	// jmx
	static final Duration SMOOTHING_WINDOW = Duration.ofMinutes(1);
	private boolean monitoring = false;
	private final RpcRequestStats generalRequestsStats = RpcRequestStats.create(SMOOTHING_WINDOW);
	private final Map<Class<?>, RpcRequestStats> requestStatsPerClass = new HashMap<>();
	private final Map<InetSocketAddress, RpcConnectStats> connectsStatsPerAddress = new HashMap<>();
	private final ExceptionStats lastProtocolError = ExceptionStats.create();

	private final JmxInspector statsSocket = new JmxInspector();

	private RpcClient(NioReactor reactor) {
		super(reactor);
	}

	public static Builder builder(NioReactor reactor) {
		return new RpcClient(reactor).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, RpcClient> {
		private Builder() {}

		/**
		 * Sets a default serializer for {@link RpcMessage} capable of serializing specified
		 * message types.
		 * <p>
		 * <b>
		 * All message types should be serializable by a default {@link  SerializerFactory}.
		 * If some additional configuration should be made to {@link  SerializerFactory}, use
		 * {@link #withSerializer(BinarySerializer)} method and pass manually constructed
		 * {@link BinarySerializer<RpcMessage>}. You can use implementation of this method as a reference.
		 * </b>
		 * <p>
		 * <b>Order of message types matters. It should match the order of message types set on {@link RpcServer}.
		 * To keep serializers compatible, the order of message types should not change</b>
		 *
		 * @param messageTypes serializer for RPC message
		 * @return the builder for RPC client with serializer for RPC message capable of serializing
		 * specified message types
		 */
		public Builder withMessageTypes(List<Class<?>> messageTypes) {
			return withSerializer(SerializerFactory.builder()
				.withSubclasses(RpcMessage.SUBCLASSES_ID, messageTypes)
				.build()
				.create(RpcMessage.class));
		}

		/**
		 * @see #withMessageTypes(List)
		 */
		public Builder withMessageTypes(Class<?>... messageTypes) {
			return withMessageTypes(List.of(messageTypes));
		}

		/**
		 * Sets serializer for {@link RpcMessage} of this RPC client.
		 *
		 * @param serializer serializer for RPC message
		 * @return the builder for RPC client with specified serializer for RPC message
		 */
		public Builder withSerializer(BinarySerializer<RpcMessage> serializer) {
			return withSerializer(serializer, serializer);
		}

		/**
		 * Sets serializers for request {@link RpcMessage} and response {@link RpcMessage} of this RPC client.
		 *
		 * @param requestSerializer  serializer for request RPC message
		 * @param responseSerializer serializer for response RPC message
		 * @return the builder for RPC client with specified serializers for RPC request and response {@link RpcMessage}s
		 */
		public Builder withSerializer(BinarySerializer<RpcMessage> requestSerializer, BinarySerializer<RpcMessage> responseSerializer) {
			checkNotBuilt(this);
			RpcClient.this.requestSerializer = requestSerializer;
			RpcClient.this.responseSerializer = responseSerializer;
			return this;
		}

		/**
		 * Sets serializers for request {@link RpcMessage} of this RPC client.
		 *
		 * @param serializer serializer for request RPC message
		 * @return the builder for RPC client with specified serializer for RPC request {@link RpcMessage}s
		 */
		public Builder withRequestsSerializer(BinarySerializer<RpcMessage> serializer) {
			checkNotBuilt(this);
			RpcClient.this.requestSerializer = serializer;
			return this;
		}

		/**
		 * Sets serializers for response {@link RpcMessage} of this RPC client.
		 *
		 * @param serializer serializer for response RPC message
		 * @return the builder for RPC client with specified serializer for RPC response {@link RpcMessage}s
		 */
		public Builder withResponsesSerializer(BinarySerializer<RpcMessage> serializer) {
			checkNotBuilt(this);
			RpcClient.this.responseSerializer = serializer;
			return this;
		}

		/**
		 * Sets socket settings for this RPC client.
		 *
		 * @param socketSettings settings for socket
		 * @return the builder for RPC client with specified socket settings
		 */
		public Builder withSocketSettings(SocketSettings socketSettings) {
			checkNotBuilt(this);
			RpcClient.this.socketSettings = socketSettings;
			return this;
		}

		/**
		 * Sets some request sending strategy. Consider some ready-to-use
		 * strategies from {@link RpcStrategies} or other {@link RpcStrategy} implementations.
		 *
		 * @param requestSendingStrategy strategy for sending requests
		 * @return the builder for RPC client, which sends requests according to given strategy
		 */
		public Builder withStrategy(RpcStrategy requestSendingStrategy) {
			checkNotBuilt(this);
			RpcClient.this.newStrategy = requestSendingStrategy;
			return this;
		}

		/**
		 * Sets a default RPC message packet size.
		 *
		 * @param defaultPacketSize default size of the message packet
		 * @return the builder for RPC client with specified size of the message packet
		 */
		public Builder withStreamProtocol(MemSize defaultPacketSize) {
			checkNotBuilt(this);
			RpcClient.this.defaultPacketSize = defaultPacketSize;
			return this;
		}

		/**
		 * Sets a default RPC message packet size as well as an optional {@link FrameFormat} to be used.
		 *
		 * @param defaultPacketSize default size of the message packet
		 * @param frameFormat       optional message frame format
		 * @return the builder for RPC client with specified size of the message packet and frame format
		 */
		public Builder withStreamProtocol(MemSize defaultPacketSize, @Nullable FrameFormat frameFormat) {
			checkNotBuilt(this);
			RpcClient.this.defaultPacketSize = defaultPacketSize;
			RpcClient.this.frameFormat = frameFormat;
			return this;
		}

		/**
		 * Sets an interval for an automatic message flush to the network.
		 *
		 * @param autoFlushInterval interval for an automatic message flush
		 * @return the builder for RPC client with specified interval for an automatic message flush
		 */
		public Builder withAutoFlush(Duration autoFlushInterval) {
			checkNotBuilt(this);
			RpcClient.this.autoFlushInterval = autoFlushInterval;
			return this;
		}

		/**
		 * Sets an interval for sending keep-alive messages to the RPC server.
		 * An interval of {@link Duration#ZERO} means no keep-alive message will be sent.
		 *
		 * @param keepAliveInterval interval for sending keep-alive messages
		 * @return the builder for RPC client with specified interval for sending keep-alive messages
		 */
		public Builder withKeepAlive(Duration keepAliveInterval) {
			checkNotBuilt(this);
			RpcClient.this.keepAliveInterval = keepAliveInterval;
			return this;
		}

		/**
		 * Sets a duration for which a client will wait on connection to the RPC server before failing with connection
		 * timeout error.
		 *
		 * @param connectTimeout timeout duration
		 * @return the builder for RPC client with specified connect timeout
		 */
		public Builder withConnectTimeout(Duration connectTimeout) {
			checkNotBuilt(this);
			RpcClient.this.connectTimeoutMillis = connectTimeout.toMillis();
			return this;
		}

		/**
		 * Sets a duration for which a client will wait before trying to re-establish a connection to the RPC server.
		 *
		 * @param reconnectInterval reconnection interval
		 * @return the builder for RPC client with specified reconnection interval
		 */
		public Builder withReconnectInterval(Duration reconnectInterval) {
			checkNotBuilt(this);
			RpcClient.this.reconnectIntervalMillis = reconnectInterval.toMillis();
			return this;
		}

		/**
		 * Adds ability to communicate with RPC server using secure connection.
		 *
		 * @param sslContext  SSL context to be used for encrypting/decrypting RPC messages
		 * @param sslExecutor executor of SSL tasks
		 * @return the builder for RPC client with secure network communication capabilities
		 */
		public Builder withSslEnabled(SSLContext sslContext, Executor sslExecutor) {
			checkNotBuilt(this);
			RpcClient.this.sslContext = sslContext;
			RpcClient.this.sslExecutor = sslExecutor;
			return this;
		}

		/**
		 * Sets a logger to be used on this RPC client.
		 *
		 * @param logger logger used for logging messages in this RPC client
		 * @return the builder for RPC client with specified logger
		 */
		public Builder withLogger(Logger logger) {
			checkNotBuilt(this);
			RpcClient.this.logger = logger;
			return this;
		}

		/**
		 * Makes RPC client forcefully shutdown even if there are active connections
		 *
		 * @return the builder for RPC client, which shutdowns regardless of active connections
		 */
		public Builder withForcedShutdown() {
			RpcClient.this.forcedShutdown = true;
			return this;
		}

		@Override
		protected RpcClient doBuild() {
			checkState(requestSerializer != null && responseSerializer != null);
			return RpcClient.this;
		}
	}

	public SocketSettings getSocketSettings() {
		return socketSettings;
	}

	@Override
	public Promise<Void> start() {
		checkInReactorThread(this);
		checkState(stopPromise == null);

		return changeStrategy(newStrategy, false);
	}

	public Promise<Void> changeStrategy(RpcStrategy newStrategy, boolean retry) {
		checkInReactorThread(this);
		if (stopPromise != null) {
			return Promise.ofException(CLIENT_IS_STOPPED);
		}

		if (newStrategyPromise != null) {
			SettablePromise<Void> promise = newStrategyPromise;
			newStrategyPromise = null;
			promise.setException(SET_STRATEGY_EXCEPTION);
			if (newStrategyPromise != null) {
				return Promise.ofException(SET_STRATEGY_EXCEPTION);
			}
		}

		SettablePromise<Void> newStrategyPromise = new SettablePromise<>();
		this.newStrategy = newStrategy;
		this.newStrategyPromise = newStrategyPromise;
		this.newStrategyRetry = retry;

		for (InetSocketAddress address : newStrategy.getAddresses()) {
			if (connections.containsKey(address)) continue;
			if (pendingConnections.contains(address)) continue;
			pendingConnections.add(address);
			newConnections.add(address);
			logger.info("Connecting: {}", address);
			connect(address);
		}

		updateStrategy();

		return newStrategyPromise;
	}

	@Override
	public Promise<Void> stop() {
		checkInReactorThread(this);
		if (stopPromise != null) return stopPromise;

		stopPromise = new SettablePromise<>();
		if (connections.size() == 0) {
			onClientStop();
			return stopPromise;
		}

		pendingConnections.clear();
		for (RpcClientConnection connection : new ArrayList<>(connections.values())) {
			if (forcedShutdown) {
				connection.forceShutdown();
			} else {
				connection.shutdown();
			}
		}

		return stopPromise;
	}

	private void connect(InetSocketAddress address) {
		TcpSocket.connect(reactor, address, connectTimeoutMillis, socketSettings)
			.whenResult(tcpSocket -> {
				newConnections.remove(address);
				if (!pendingConnections.contains(address) || stopPromise != null) {
					tcpSocket.close();
					return;
				}
				statsSocket.onConnect(tcpSocket);
				tcpSocket.setInspector(statsSocket);
				ITcpSocket socket = sslContext == null ?
					tcpSocket :
					wrapClientSocket(reactor, tcpSocket, sslContext, sslExecutor);
				RpcStream stream = new RpcStream(socket, responseSerializer, requestSerializer, defaultPacketSize,
					autoFlushInterval, frameFormat, false); // , statsSerializer, statsDeserializer, statsCompressor, statsDecompressor);
				RpcClientConnection connection = new RpcClientConnection(reactor, this, address, stream, keepAliveInterval.toMillis());
				stream.setListener(connection);

				// jmx
				if (isMonitoring()) {
					connection.startMonitoring();
				}

				// jmx
				connectsStatsPerAddress.computeIfAbsent(address, $ -> new RpcConnectStats(reactor)).recordSuccessfulConnect();
				logger.info("Connection to {} established", address);

				pendingConnections.remove(address);
				connections.put(address, connection);
				updateStrategy();
			})
			.whenException(e -> {
				newConnections.remove(address);
				logger.warn("Connection {} failed: {}", address, e);
				if (!pendingConnections.contains(address) || stopPromise != null) {
					return;
				}
				reactor.delayBackground(reconnectIntervalMillis, () -> {
					if (!pendingConnections.contains(address) || stopPromise != null) {
						return;
					}
					logger.info("Reconnecting: {}", address);
					connect(address);
				});
				updateStrategy();
			});
	}

	void onClosedConnection(InetSocketAddress address) {
		if (connections.remove(address) == null) {
			return;
		}
		logger.info("Connection closed: {}", address);
		if (stopPromise == null) {
			pendingConnections.add(address);
			reactor.delayBackground(reconnectIntervalMillis, () -> {
				if (!pendingConnections.contains(address) || stopPromise != null) {
					return;
				}
				logger.info("Reconnecting: {}", address);
				connect(address);
			});
		} else {
			if (connections.size() == 0) {
				onClientStop();
			}
		}
		updateStrategy();
	}

	private void onClientStop() {
		assert stopPromise != null;

		if (newStrategyPromise != null) {
			SettablePromise<Void> promise = this.newStrategyPromise;
			this.newStrategyPromise = null;
			promise.setException(CLIENT_IS_STOPPED);
			assert this.newStrategyPromise == null;
		}

		stopPromise.set(null);
	}

	private void updateStrategy() {
		if (stopPromise != null) {
			return;
		}
		if (newStrategy != null && newConnections.isEmpty()) {
			RpcStrategy newStrategy = this.newStrategy;
			RpcSender newRequestSender = newStrategy.createSender(pool);
			SettablePromise<Void> newStrategyPromise = this.newStrategyPromise;

			if (newRequestSender == null && newStrategyRetry) {
				return;
			}

			this.newStrategy = null;
			this.newStrategyPromise = null;

			if (newRequestSender != null) {
				this.strategy = newStrategy;
				this.requestSender = newRequestSender;
			}

			Set<InetSocketAddress> strategyAddresses = this.strategy.getAddresses();
			pendingConnections.retainAll(strategyAddresses);
			new ArrayList<>(connections.keySet()).stream()
				.filter(not(strategyAddresses::contains))
				.map(connections::remove).toList()
				.forEach(RpcClientConnection::shutdown);

			if (newRequestSender != null) {
				newStrategyPromise.set(null);
			} else {
				newStrategyPromise.setException(CONNECTION_EXCEPTION);
			}

		} else {
			requestSender = nonNullElseGet(strategy.createSender(pool), NoSenderAvailable::new);
		}
	}

	/**
	 * Sends the request to server, waits the result timeout and handles result with callback
	 *
	 * @param <I>     request class
	 * @param <O>     response class
	 * @param request request to a server
	 * @param timeout timeout in milliseconds. If there is no answer from the server after the timeout passes,
	 *                the request will end exceptionally with {@link AsyncTimeoutException}.
	 *                Note, that setting a timeout schedules a task to the {@link Reactor}, so a lot of
	 *                requests with large timeouts may degrade performance. In the most common scenarios
	 *                timeouts for RPC requests should not be very big (a few seconds should be enough)
	 * @param cb      a callback that will be completed after receiving a response or encountering some error
	 */
	@Override
	public <I, O> void sendRequest(I request, int timeout, Callback<O> cb) {
		if (CHECKS) checkInReactorThread(this);
		if (timeout > 0) {
			requestSender.sendRequest(request, timeout, cb);
		} else {
			cb.accept(null, new AsyncTimeoutException("RPC request has timed out"));
		}
	}

	@Override
	public <I, O> void sendRequest(I request, Callback<O> cb) {
		if (CHECKS) checkInReactorThread(this);
		requestSender.sendRequest(request, cb);
	}

	public IRpcClient adaptToAnotherReactor(NioReactor anotherReactor) {
		if (anotherReactor == this.reactor) {
			return this;
		}

		return new IRpcClient() {
			@Override
			public <I, O> void sendRequest(I request, int timeout, Callback<O> cb) {
				if (CHECKS) checkInReactorThread(anotherReactor);
				if (timeout > 0) {
					anotherReactor.startExternalTask();
					reactor.execute(() ->
						requestSender.sendRequest(request, timeout, (Callback<O>) (result, e) -> {
							anotherReactor.execute(() -> cb.accept(result, e));
							anotherReactor.completeExternalTask();
						}));
				} else {
					cb.accept(null, new AsyncTimeoutException("RPC request has timed out"));
				}
			}

		};
	}

	@VisibleForTesting
	public RpcSender getRequestSender() {
		return requestSender;
	}

	@VisibleForTesting
	public BinarySerializer<RpcMessage> getRequestSerializer() {
		return requestSerializer;
	}

	@VisibleForTesting
	public BinarySerializer<RpcMessage> getResponseSerializer() {
		return responseSerializer;
	}

	@Override
	public String toString() {
		return "RpcClient{" + connections + '}';
	}

	public static final class NoSenderAvailable implements RpcSender {
		@Override
		public <I, O> void sendRequest(I request, int timeout, Callback<O> cb) {
			cb.accept(null, NO_SENDER_AVAILABLE_EXCEPTION);
		}
	}

	public static final class NoServersRpcStrategy implements RpcStrategy {
		@Override
		public Set<InetSocketAddress> getAddresses() {
			return Set.of();
		}

		@Override
		public RpcSender createSender(RpcClientConnectionPool pool) {
			return null;
		}
	}

	// jmx
	@JmxOperation(description =
		"enable monitoring " +
		"[ when monitoring is enabled more stats are collected, but it causes more overhead " +
		"(for example, responseTime and requestsStatsPerClass are collected only when monitoring is enabled) ]")
	public void startMonitoring() {
		monitoring = true;
		for (RpcClientConnection connection : connections.values()) {
			connection.startMonitoring();
		}
	}

	@JmxOperation(description =
		"disable monitoring " +
		"[ when monitoring is enabled more stats are collected, but it causes more overhead " +
		"(for example, responseTime and requestsStatsPerClass are collected only when monitoring is enabled) ]")
	public void stopMonitoring() {
		monitoring = false;
		for (RpcClientConnection connection : connections.values()) {
			connection.stopMonitoring();
		}
	}

	@JmxAttribute(description =
		"when monitoring is enabled more stats are collected, but it causes more overhead " +
		"(for example, responseTime and requestsStatsPerClass are collected only when monitoring is enabled)")
	public boolean isMonitoring() {
		return monitoring;
	}

	@JmxAttribute(name = "requests", extraSubAttributes = "totalRequests")
	public RpcRequestStats getGeneralRequestsStats() {
		return generalRequestsStats;
	}

	@JmxAttribute(reducer = JmxReducerSum.class)
	public long getTotalSuccessfulConnects() {
		return connectsStatsPerAddress.values().stream()
			.mapToLong(RpcConnectStats::getSuccessfulConnects)
			.sum();
	}

	@JmxAttribute(reducer = JmxReducerSum.class)
	public long getTotalFailedConnects() {
		return connectsStatsPerAddress.values().stream()
			.mapToLong(RpcConnectStats::getFailedConnects)
			.sum();
	}

	@JmxAttribute(description = "request stats distributed by request class")
	public Map<Class<?>, RpcRequestStats> getRequestsStatsPerClass() {
		return requestStatsPerClass;
	}

	@JmxAttribute
	public Map<InetSocketAddress, RpcConnectStats> getConnectsStatsPerAddress() {
		return connectsStatsPerAddress;
	}

	@JmxAttribute(description = "request stats for current connections (when connection is closed stats are removed)")
	public Map<InetSocketAddress, RpcClientConnection> getRequestStatsPerConnection() {
		return connections;
	}

	@JmxAttribute(reducer = JmxReducerSum.class)
	public int getActiveConnections() {
		return connections.size();
	}

	@JmxAttribute(reducer = JmxReducerSum.class)
	public int getActiveRequests() {
		int count = 0;
		for (RpcClientConnection connection : connections.values()) {
			count += connection.getActiveRequests();
		}
		return count;
	}

	@JmxAttribute(description =
		"exception that occurred because of protocol error " +
		"(serialization, deserialization, compression, decompression, etc)")
	public ExceptionStats getLastProtocolError() {
		return lastProtocolError;
	}

	@JmxAttribute
	public JmxInspector getStatsSocket() {
		return statsSocket;
	}

	@JmxAttribute
	public List<String> getUnresponsiveServers() {
		if (stopPromise != null) return List.of();

		return connectsStatsPerAddress.entrySet().stream()
			.filter(entry -> !entry.getValue().isConnected())
			.map(entry -> entry.getKey().toString())
			.collect(toList());
	}

	RpcRequestStats ensureRequestStatsPerClass(Class<?> requestClass) {
		return requestStatsPerClass.computeIfAbsent(requestClass, $ -> RpcRequestStats.create(SMOOTHING_WINDOW));
	}
}
