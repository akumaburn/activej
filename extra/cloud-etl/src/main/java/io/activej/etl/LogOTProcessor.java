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

package io.activej.etl;

import io.activej.async.AsyncAccumulator;
import io.activej.async.function.AsyncSupplier;
import io.activej.async.service.ReactiveService;
import io.activej.datastream.consumer.StreamConsumerWithResult;
import io.activej.datastream.processor.StreamUnion;
import io.activej.datastream.stats.BasicStreamStats;
import io.activej.datastream.stats.DetailedStreamStats;
import io.activej.datastream.stats.StreamStats;
import io.activej.datastream.supplier.StreamSupplierWithResult;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.multilog.IMultilog;
import io.activej.multilog.LogPosition;
import io.activej.promise.Promise;
import io.activej.promise.jmx.PromiseStats;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.activej.async.function.AsyncSuppliers.reuse;
import static io.activej.reactor.Reactive.checkInReactorThread;

/**
 * Processes logs. Creates new aggregation logs and persists to {@link ILogDataConsumer} .
 */
@SuppressWarnings("rawtypes") // JMX doesn't work with generic types
public final class LogOTProcessor<T, D> extends AbstractReactive
	implements ReactiveService, ReactiveJmxBeanWithStats {
	private static final Logger logger = LoggerFactory.getLogger(LogOTProcessor.class);

	private final IMultilog<T> multilog;
	private final ILogDataConsumer<T, D> logStreamConsumer;

	private final String log;
	private final List<String> partitions;

	private final LogOTState<D> state;

	// JMX
	private boolean enabled = true;
	private boolean detailed;
	private final BasicStreamStats<T> streamStatsBasic = StreamStats.basic();
	private final DetailedStreamStats<T> streamStatsDetailed = StreamStats.detailed();
	private final PromiseStats promiseProcessLog = PromiseStats.create(Duration.ofMinutes(5));

	private LogOTProcessor(
		Reactor reactor, IMultilog<T> multilog, ILogDataConsumer<T, D> logStreamConsumer, String log,
		List<String> partitions, LogOTState<D> state
	) {
		super(reactor);
		this.multilog = multilog;
		this.logStreamConsumer = logStreamConsumer;
		this.log = log;
		this.partitions = partitions;
		this.state = state;
	}

	public static <T, D> LogOTProcessor<T, D> create(
		Reactor reactor, IMultilog<T> multilog, ILogDataConsumer<T, D> logStreamConsumer, String log,
		List<String> partitions, LogOTState<D> state
	) {
		return new LogOTProcessor<>(reactor, multilog, logStreamConsumer, log, partitions, state);
	}

	@Override
	public Promise<?> start() {
		checkInReactorThread(this);
		return Promise.complete();
	}

	@Override
	public Promise<?> stop() {
		checkInReactorThread(this);
		return Promise.complete();
	}

	private final AsyncSupplier<LogDiff<D>> processLog = reuse(this::doProcessLog);

	public Promise<LogDiff<D>> processLog() {
		checkInReactorThread(this);
		return processLog.get();
	}

	private Promise<LogDiff<D>> doProcessLog() {
		if (!enabled) return Promise.of(LogDiff.of(Map.of(), List.of()));
		logger.trace("processLog_gotPositions called. Positions: {}", state.getPositions());

		StreamSupplierWithResult<T, Map<String, LogPositionDiff>> supplier = getSupplier();
		StreamConsumerWithResult<T, List<D>> consumer = logStreamConsumer.consume();
		return supplier.streamTo(consumer)
			.whenComplete(promiseProcessLog.recordStats())
			.map(result -> LogDiff.of(result.value1(), result.value2()))
			.whenResult(logDiff ->
				logger.info("Log '{}' processing complete. Positions: {}", log, logDiff.getPositions()));
	}

	private StreamSupplierWithResult<T, Map<String, LogPositionDiff>> getSupplier() {
		AsyncAccumulator<Map<String, LogPositionDiff>> logPositionsAccumulator = AsyncAccumulator.create(new HashMap<>());
		StreamUnion<T> streamUnion = StreamUnion.create();
		for (String partition : partitions) {
			String logName = logName(partition);
			LogPosition logPosition = state.getPositions().get(logName);
			if (logPosition == null) {
				logPosition = LogPosition.initial();
			}
			logger.info("Starting reading '{}' from position {}", logName, logPosition);

			LogPosition logPositionFrom = logPosition;
			logPositionsAccumulator.addPromise(
				StreamSupplierWithResult.ofPromise(
						multilog.read(partition, logPosition.getLogFile(), logPosition.getPosition(), null))
					.streamTo(streamUnion.newInput()),
				(logPositions, logPositionTo) -> {
					if (!logPositionTo.equals(logPositionFrom)) {
						logPositions.put(logName, new LogPositionDiff(logPositionFrom, logPositionTo));
					}
				});
		}
		return StreamSupplierWithResult.of(
			streamUnion.getOutput()
				.transformWith(detailed ? streamStatsDetailed : streamStatsBasic),
			logPositionsAccumulator.run());
	}

	private String logName(String partition) {
		return log != null && !log.isEmpty() ? log + "." + partition : partition;
	}

	@JmxAttribute
	public boolean isEnabled() {
		return enabled;
	}

	@JmxAttribute
	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	@JmxAttribute
	public PromiseStats getPromiseProcessLog() {
		return promiseProcessLog;
	}

	@JmxAttribute
	public BasicStreamStats getStreamStatsBasic() {
		return streamStatsBasic;
	}

	@JmxAttribute
	public DetailedStreamStats getStreamStatsDetailed() {
		return streamStatsDetailed;
	}

	@JmxOperation
	public void startDetailedMonitoring() {
		detailed = true;
	}

	@JmxOperation
	public void stopDetailedMonitoring() {
		detailed = false;
	}
}
