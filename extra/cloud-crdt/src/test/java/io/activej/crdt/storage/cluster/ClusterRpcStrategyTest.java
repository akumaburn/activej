package io.activej.crdt.storage.cluster;

import io.activej.async.callback.Callback;
import io.activej.common.ref.RefBoolean;
import io.activej.crdt.storage.cluster.DiscoveryService.Partitionings;
import io.activej.rpc.client.RpcClientConnectionPool;
import io.activej.rpc.client.sender.RpcSender;
import io.activej.rpc.client.sender.RpcStrategy;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.function.Function;

import static io.activej.common.Utils.*;
import static io.activej.rpc.client.sender.RpcStrategies.server;
import static java.util.Collections.singleton;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.*;

public class ClusterRpcStrategyTest {

	private static final Map<String, InetSocketAddress> PARTITION_ADDRESS_MAP_1 = mapOf(
			"one", new InetSocketAddress(9001),
			"two", new InetSocketAddress(9002),
			"three", new InetSocketAddress(9003),
			"four", new InetSocketAddress(9004)
	);

	private static final Map<String, InetSocketAddress> PARTITION_ADDRESS_MAP_2 = mapOf(
			"five", new InetSocketAddress(9005),
			"six", new InetSocketAddress(9006),
			"seven", new InetSocketAddress(9007),
			"eight", new InetSocketAddress(9008),
			"nine", new InetSocketAddress(9009)
	);

	private static final Function<Object, Integer> KEY_GETTER = obj -> {
		if (obj instanceof Integer) return (Integer) obj;
		throw new IllegalArgumentException();
	};

	@Test
	public void testRpcStrategyNoActive() {
		Set<String> crdtStorages = PARTITION_ADDRESS_MAP_1.keySet();

		Partitionings<String> partitionings = RendezvousPartitionings.<String>create()
				.withPartitioning(RendezvousPartitioning.create(crdtStorages, 2, true, false));

		List<String> alivePartitions = new ArrayList<>(difference(crdtStorages, singleton("two")));

		RpcStrategy rpcStrategy = partitionings.createRpcStrategy(partition -> server(PARTITION_ADDRESS_MAP_1.get(partition)), KEY_GETTER);

		RpcClientConnectionPoolStub poolStub = new RpcClientConnectionPoolStub();
		for (String alivePartition : alivePartitions) {
			poolStub.put(PARTITION_ADDRESS_MAP_1.get(alivePartition), new RpcSenderStub());
		}

		RpcSender sender = rpcStrategy.createSender(poolStub);
		assertNull(sender);
	}

	@Test
	public void testRpcStrategyActive() {
		Set<String> partitionIds = PARTITION_ADDRESS_MAP_1.keySet();

		Partitionings<String> partitionings = RendezvousPartitionings.<String>create()
				.withPartitioning(RendezvousPartitioning.create(partitionIds, 2, true, true));

		List<String> alivePartitions = new ArrayList<>(difference(partitionIds, singleton("two")));

		RpcClientConnectionPoolStub poolStub = new RpcClientConnectionPoolStub();
		for (String alivePartition : alivePartitions) {
			poolStub.put(PARTITION_ADDRESS_MAP_1.get(alivePartition), new RpcSenderStub());
		}

		RpcStrategy rpcStrategy = partitionings.createRpcStrategy(partition -> server(PARTITION_ADDRESS_MAP_1.get(partition)), KEY_GETTER);

		RpcSender sender = rpcStrategy.createSender(poolStub);
		assertNotNull(sender);

		Sharder<Integer> sharder = partitionings.createSharder(alivePartitions);
		assertNotNull(sharder);

		Map<InetSocketAddress, String> address2Partition = PARTITION_ADDRESS_MAP_1.entrySet()
				.stream()
				.collect(toMap(Map.Entry::getValue, Map.Entry::getKey));

		for (int i = 0; i < 1000; i++) {
			int[] partitionsIndexes = sharder.shard(i);
			Set<String> partitions = Arrays.stream(partitionsIndexes)
					.mapToObj(alivePartitions::get)
					.collect(toSet());

			sendRequest(sender, i);

			boolean asserted = false;
			for (Map.Entry<InetSocketAddress, RpcSender> entry : poolStub.connections.entrySet()) {
				RpcSenderStub rpcSender = (RpcSenderStub) entry.getValue();
				Integer count = rpcSender.counters.get(i);
				if (count == null) continue;

				assertEquals(1, count.intValue());
				String partition = address2Partition.get(entry.getKey());
				assertTrue(partitions.contains(partition));
				assertFalse(asserted);
				asserted = true;
			}

			assertTrue(asserted);
		}
	}

	@Test
	public void testRpcStrategyMultipleActive() {
		Set<String> partitionIds = union(PARTITION_ADDRESS_MAP_1.keySet(), PARTITION_ADDRESS_MAP_2.keySet());

		Partitionings<String> partitionings = RendezvousPartitionings.<String>create()
				.withPartitioning(RendezvousPartitioning.create(PARTITION_ADDRESS_MAP_1.keySet(), 2, true, true))
				.withPartitioning(RendezvousPartitioning.create(PARTITION_ADDRESS_MAP_2.keySet(), 2, true, true));

		List<String> alivePartitions = new ArrayList<>(difference(partitionIds, setOf("two", "seven", "nine")));

		Map<String, InetSocketAddress> partition2Address = new HashMap<>();
		partition2Address.putAll(PARTITION_ADDRESS_MAP_1);
		partition2Address.putAll(PARTITION_ADDRESS_MAP_2);

		Map<InetSocketAddress, String> address2Partitions = partition2Address.entrySet()
				.stream()
				.collect(toMap(Map.Entry::getValue, Map.Entry::getKey));

		RpcClientConnectionPoolStub poolStub = new RpcClientConnectionPoolStub();
		for (String alivePartition : alivePartitions) {
			poolStub.put(partition2Address.get(alivePartition), new RpcSenderStub());
		}

		RpcStrategy rpcStrategy = partitionings.createRpcStrategy(partition -> server(partition2Address.get(partition)), KEY_GETTER);

		RpcSender sender = rpcStrategy.createSender(poolStub);
		assertNotNull(sender);

		Sharder<Integer> sharder = partitionings.createSharder(alivePartitions);
		assertNotNull(sharder);

		for (int i = 0; i < 1000; i++) {
			int[] partitionsIndexes = sharder.shard(i);
			Set<String> partitions = Arrays.stream(partitionsIndexes)
					.mapToObj(alivePartitions::get)
					.collect(toSet());

			sendRequest(sender, i);

			boolean asserted = false;
			for (Map.Entry<InetSocketAddress, RpcSender> entry : poolStub.connections.entrySet()) {
				RpcSenderStub rpcSender = (RpcSenderStub) entry.getValue();
				Integer count = rpcSender.counters.get(i);
				if (count == null) continue;

				assertEquals(1, count.intValue());
				String partition = address2Partitions.get(entry.getKey());
				assertTrue(partitions.contains(partition));
				assertFalse(asserted);
				asserted = true;
			}

			assertTrue(asserted);
		}
	}

	private static void sendRequest(RpcSender sender, int request) {
		RefBoolean sent = new RefBoolean(false);
		sender.sendRequest(request, (result, e) -> {
			assertNull(result);
			assertNull(e);

			sent.set(true);
		});
		assertTrue(sent.get());
	}

	private static class RpcClientConnectionPoolStub implements RpcClientConnectionPool {
		private final Map<InetSocketAddress, RpcSender> connections = new HashMap<>();

		public void put(InetSocketAddress address, RpcSender connection) {
			connections.put(address, connection);
		}

		@Override
		public RpcSender get(@NotNull InetSocketAddress address) {
			return connections.get(address);
		}
	}


	private static final class RpcSenderStub implements RpcSender {
		private final Map<Integer, Integer> counters = new HashMap<>();

		@Override
		public <I, O> void sendRequest(I request, int timeout, @NotNull Callback<O> cb) {
			//noinspection SuspiciousMethodCalls
			Integer count = counters.getOrDefault(request, 0);
			counters.put((Integer) request, ++count);
			cb.accept(null, null);
		}
	}

}
