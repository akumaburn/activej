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

package io.activej.crdt.storage.cluster;

import io.activej.async.function.AsyncFunction;
import io.activej.async.function.AsyncSupplier;
import io.activej.async.process.AsyncCloseable;
import io.activej.async.service.ReactiveService;
import io.activej.common.ApplicationSettings;
import io.activej.common.Checks;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.collection.Try;
import io.activej.crdt.CrdtData;
import io.activej.crdt.CrdtException;
import io.activej.crdt.CrdtTombstone;
import io.activej.crdt.RemoteCrdtStorage;
import io.activej.crdt.function.CrdtFunction;
import io.activej.crdt.storage.ICrdtStorage;
import io.activej.crdt.storage.cluster.IDiscoveryService.PartitionScheme;
import io.activej.datastream.consumer.StreamConsumer;
import io.activej.datastream.processor.StreamSplitter;
import io.activej.datastream.processor.reducer.BinaryAccumulatorReducer;
import io.activej.datastream.processor.reducer.StreamReducer;
import io.activej.datastream.stats.BasicStreamStats;
import io.activej.datastream.stats.DetailedStreamStats;
import io.activej.datastream.stats.StreamStats;
import io.activej.datastream.supplier.StreamSupplier;
import io.activej.jmx.api.attribute.JmxAttribute;
import io.activej.jmx.api.attribute.JmxOperation;
import io.activej.jmx.stats.EventStats;
import io.activej.promise.Promise;
import io.activej.promise.Promises;
import io.activej.reactor.AbstractReactive;
import io.activej.reactor.Reactor;
import io.activej.reactor.jmx.ReactiveJmxBeanWithStats;
import org.jetbrains.annotations.VisibleForTesting;

import java.time.Duration;
import java.util.*;
import java.util.function.Function;

import static io.activej.crdt.util.Utils.onItem;
import static io.activej.reactor.Reactive.checkInReactorThread;
import static java.util.stream.Collectors.toMap;

@SuppressWarnings("rawtypes") // JMX
public final class ClusterCrdtStorage<K extends Comparable<K>, S, P> extends AbstractReactive
	implements ICrdtStorage<K, S>, ReactiveService, ReactiveJmxBeanWithStats {

	private static final boolean CHECKS = Checks.isEnabled(ClusterCrdtStorage.class);

	public static final Duration DEFAULT_SMOOTHING_WINDOW = ApplicationSettings.getDuration(ClusterCrdtStorage.class, "smoothingWindow", Duration.ofMinutes(1));

	private final IDiscoveryService<P> discoveryService;
	private final CrdtFunction<S> crdtFunction;
	private final Map<P, ICrdtStorage<K, S>> crdtStorages = new LinkedHashMap<>();

	private PartitionScheme<P> currentPartitionScheme;
	private boolean forceStart;
	private boolean stopped;

	// region JMX
	private boolean detailedStats;

	private final BasicStreamStats<CrdtData<K, S>> uploadStats = StreamStats.basic();
	private final DetailedStreamStats<CrdtData<K, S>> uploadStatsDetailed = StreamStats.detailed();
	private final BasicStreamStats<CrdtData<K, S>> downloadStats = StreamStats.basic();
	private final DetailedStreamStats<CrdtData<K, S>> downloadStatsDetailed = StreamStats.detailed();
	private final BasicStreamStats<CrdtData<K, S>> takeStats = StreamStats.basic();
	private final DetailedStreamStats<CrdtData<K, S>> takeStatsDetailed = StreamStats.detailed();
	private final BasicStreamStats<CrdtTombstone<K>> removeStats = StreamStats.basic();
	private final DetailedStreamStats<CrdtTombstone<K>> removeStatsDetailed = StreamStats.detailed();

	private final BasicStreamStats<CrdtData<K, S>> repartitionUploadStats = StreamStats.basic();
	private final DetailedStreamStats<CrdtData<K, S>> repartitionUploadStatsDetailed = StreamStats.detailed();

	private final EventStats uploadedItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final EventStats downloadedItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final EventStats takenItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final EventStats removedItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	private final EventStats repartitionedItems = EventStats.create(DEFAULT_SMOOTHING_WINDOW);
	// endregion

	private ClusterCrdtStorage(Reactor reactor, IDiscoveryService<P> discoveryService, CrdtFunction<S> crdtFunction) {
		super(reactor);
		this.discoveryService = discoveryService;
		this.crdtFunction = crdtFunction;
	}

	public static <K extends Comparable<K>, S, P> ClusterCrdtStorage<K, S, P> create(
		Reactor reactor, IDiscoveryService<P> discoveryService, CrdtFunction<S> crdtFunction
	) {
		return ClusterCrdtStorage.<K, S, P>builder(reactor, discoveryService, crdtFunction).build();
	}

	public static <K extends Comparable<K>, S, P> ClusterCrdtStorage<K, S, P>.Builder builder(
		Reactor reactor, IDiscoveryService<P> discoveryService, CrdtFunction<S> crdtFunction
	) {
		return new ClusterCrdtStorage<K, S, P>(reactor, discoveryService, crdtFunction).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, ClusterCrdtStorage<K, S, P>> {
		private Builder() {}

		public Builder withForceStart(boolean forceStart) {
			checkNotBuilt(this);
			ClusterCrdtStorage.this.forceStart = forceStart;
			return this;
		}

		@Override
		protected ClusterCrdtStorage<K, S, P> doBuild() {
			return ClusterCrdtStorage.this;
		}
	}

/*
	public CrdtStorageCluster<K, S, P> withReplicationCount(int replicationCount) {
		checkArgument(1 <= replicationCount, "Replication count cannot be less than one");
		this.deadPartitionsThreshold = replicationCount - 1;
		this.replicationCount = replicationCount;
		this.partitions.setTopShards(replicationCount);
		return this;
	}
*/

	@Override
	public Promise<?> start() {
		checkInReactorThread(this);
		AsyncSupplier<PartitionScheme<P>> discoverySupplier = discoveryService.discover();
		return discoverySupplier.get()
			.then(result -> {
				updatePartitionScheme(result);
				return ping()
					.thenCallback((v, e, cb) -> {
						if (e instanceof CrdtException && forceStart) {
							cb.set(null);
							return;
						}
						cb.set(v, e);
					});
			})
			.whenResult(() -> Promises.repeat(() ->
				discoverySupplier.get()
					.map((result, e) -> {
						if (stopped) return false;
						if (e == null) {
							updatePartitionScheme(result);
						}
						return true;
					})
			));
	}

	@Override
	public Promise<?> stop() {
		checkInReactorThread(this);
		this.stopped = true;
		return Promise.complete();
	}

	@Override
	public Promise<StreamConsumer<CrdtData<K, S>>> upload() {
		if (CHECKS) checkInReactorThread(this);
		PartitionScheme<P> partitionScheme = this.currentPartitionScheme;
		return execute(partitionScheme, ICrdtStorage::upload)
			.then(map -> {
				List<P> alive = new ArrayList<>(map.keySet());
				Sharder<K> sharder = partitionScheme.createSharder(alive);
				if (sharder == null) {
					throw new CrdtException("Incomplete cluster");
				}
				StreamSplitter<CrdtData<K, S>, CrdtData<K, S>> splitter = StreamSplitter.create(
					(item, acceptors) -> {
						int[] selected = sharder.shard(item.getKey());
						//noinspection ForLoopReplaceableByForEach
						for (int i = 0; i < selected.length; i++) {
							acceptors[selected[i]].accept(item);
						}
					});
				for (P partitionId : alive) {
					splitter.newOutput().streamTo(map.get(partitionId));
				}
				return Promise.of(splitter.getInput()
					.transformWith(detailedStats ? uploadStatsDetailed : uploadStats)
					.transformWith(onItem(uploadedItems::recordEvent)));
			});
	}

	@Override
	public Promise<StreamSupplier<CrdtData<K, S>>> download(long timestamp) {
		if (CHECKS) checkInReactorThread(this);
		return getData(storage -> storage.download(timestamp))
			.map(supplier -> supplier
				.transformWith(detailedStats ? downloadStatsDetailed : downloadStats)
				.transformWith(onItem(downloadedItems::recordEvent)));
	}

	@Override
	public Promise<StreamSupplier<CrdtData<K, S>>> take() {
		if (CHECKS) checkInReactorThread(this);
		return getData(ICrdtStorage::take)
			.map(supplier -> supplier
				.transformWith(detailedStats ? takeStatsDetailed : takeStats)
				.transformWith(onItem(takenItems::recordEvent)));
	}

	@Override
	public Promise<StreamConsumer<CrdtTombstone<K>>> remove() {
		if (CHECKS) checkInReactorThread(this);
		PartitionScheme<P> partitionScheme = currentPartitionScheme;
		return execute(partitionScheme, ICrdtStorage::remove)
			.map(map -> {
				List<P> alive = new ArrayList<>(map.keySet());
				Sharder<K> sharder = partitionScheme.createSharder(alive);
				if (sharder == null) {
					throw new CrdtException("Incomplete cluster");
				}
				StreamSplitter<CrdtTombstone<K>, CrdtTombstone<K>> splitter = StreamSplitter.create(
					(item, acceptors) -> {
						int[] selected = sharder.shard(item.getKey());
						//noinspection ForLoopReplaceableByForEach
						for (int i = 0; i < selected.length; i++) {
							acceptors[selected[i]].accept(item);
						}
					});
				for (P partitionId : alive) {
					splitter.newOutput().streamTo(map.get(partitionId));
				}
				return splitter.getInput()
					.transformWith(detailedStats ? removeStatsDetailed : removeStats)
					.transformWith(onItem(removedItems::recordEvent));
			});
	}

	public Promise<Void> repartition(P sourcePartitionId) {
		if (CHECKS) checkInReactorThread(this);
		PartitionScheme<P> partitionScheme = this.currentPartitionScheme;
		ICrdtStorage<K, S> source = crdtStorages.get(sourcePartitionId);

		class Tuple {
			private final Try<StreamSupplier<CrdtData<K, S>>> downloader;
			private final Map<P, StreamConsumer<CrdtData<K, S>>> uploaders;

			public Tuple(Try<StreamSupplier<CrdtData<K, S>>> downloader, Map<P, StreamConsumer<CrdtData<K, S>>> uploaders) {
				this.downloader = downloader;
				this.uploaders = uploaders;
			}

			private void close() {
				downloader.ifSuccess(AsyncCloseable::close);
				uploaders.values().forEach(AsyncCloseable::close);
			}
		}

		return Promises.toTuple(Tuple::new,
				source.take().toTry(),
				execute(partitionScheme, ICrdtStorage::upload))
			.whenResult(tuple -> {
				if (!tuple.uploaders.containsKey(sourcePartitionId)) {
					tuple.close();
					throw new CrdtException("Could not upload to local storage");
				}
				if (tuple.uploaders.size() == 1) {
					tuple.close();
					throw new CrdtException("Nowhere to upload");
				}
				if (tuple.downloader.isException()) {
					tuple.close();
					Exception e = tuple.downloader.getException();
					throw new CrdtException("Could not download local data", e);
				}
			})
			.then(tuple -> {
				List<P> alive = new ArrayList<>(tuple.uploaders.keySet());
				Sharder<K> sharder = partitionScheme.createSharder(alive);
				if (sharder == null) {
					tuple.close();
					return Promise.ofException(new CrdtException("Incomplete cluster"));
				}

				StreamSplitter<CrdtData<K, S>, ?> splitter = StreamSplitter.create(
					(item, acceptors) -> {
						for (int idx : sharder.shard(item.getKey())) {
							acceptors[idx].accept(item);
						}
					});

				StreamConsumer<CrdtData<K, S>> uploader = splitter.getInput();
				StreamSupplier<CrdtData<K, S>> downloader = tuple.downloader.get();

				for (P partitionId : alive) {
					//noinspection unchecked
					((StreamSupplier<CrdtData<K, S>>) splitter.newOutput())
						.transformWith(detailedStats ? repartitionUploadStatsDetailed : repartitionUploadStats)
						.transformWith(onItem(repartitionedItems::recordEvent))
						.streamTo(tuple.uploaders.get(partitionId));
				}

				return downloader.streamTo(uploader);
			});
	}

	@Override
	public Promise<Void> ping() {
		if (CHECKS) checkInReactorThread(this);
		PartitionScheme<P> partitionScheme = this.currentPartitionScheme;
		return execute(partitionScheme, ICrdtStorage::ping)
			.whenResult(map -> {
				Sharder<K> sharder = partitionScheme.createSharder(new ArrayList<>(map.keySet()));
				if (sharder == null) {
					throw new CrdtException("Incomplete cluster");
				}
			})
			.toVoid();
	}

	private <T> Promise<Map<P, T>> execute(PartitionScheme<P> partitionScheme, AsyncFunction<ICrdtStorage<K, S>, T> method) {
		Set<P> partitions = partitionScheme.getPartitions();
		Map<P, T> map = new HashMap<>();
		return Promises.all(
				partitions.stream()
					.map(partitionId -> method.apply(crdtStorages.get(partitionId))
						.map((t, e) -> e == null ? map.put(partitionId, t) : null)
					))
			.map($ -> map);
	}

	private Promise<StreamSupplier<CrdtData<K, S>>> getData(AsyncFunction<ICrdtStorage<K, S>, StreamSupplier<CrdtData<K, S>>> method) {
		PartitionScheme<P> partitionScheme = currentPartitionScheme;
		return execute(partitionScheme, method)
			.map(map -> {
				if (!partitionScheme.isReadValid(map.keySet())) {
					throw new CrdtException("Incomplete cluster");
				}
				StreamReducer<K, CrdtData<K, S>, CrdtData<K, S>> streamReducer = StreamReducer.create();
				for (P partitionId : map.keySet()) {
					map.get(partitionId).streamTo(streamReducer.newInput(
						CrdtData::getKey,
						new BinaryAccumulatorReducer<>() {
							@Override
							protected CrdtData<K, S> combine(K key, CrdtData<K, S> nextValue, CrdtData<K, S> accumulator) {
								long timestamp = Math.max(nextValue.getTimestamp(), accumulator.getTimestamp());
								S merged = crdtFunction.merge(accumulator.getState(), accumulator.getTimestamp(), nextValue.getState(), nextValue.getTimestamp());
								return new CrdtData<>(key, timestamp, merged);
							}
						})
					);
				}
				return streamReducer.getOutput();
			});
	}

	private void updatePartitionScheme(PartitionScheme<P> partitionScheme) {
		this.currentPartitionScheme = partitionScheme;
		crdtStorages.keySet().retainAll(partitionScheme.getPartitions());
		for (P partition : partitionScheme.getPartitions()) {
			//noinspection unchecked
			crdtStorages.computeIfAbsent(partition,
				(Function) (Function<P, ICrdtStorage<?, ?>>) partitionScheme::provideCrdtConnection);
		}
	}

	@VisibleForTesting
	Map<P, ICrdtStorage<K, S>> getCrdtStorages() {
		return crdtStorages;
	}

	// region JMX
/*
	@JmxAttribute
	public int getDeadPartitionsThreshold() {
		return deadPartitionsThreshold;
	}

	@JmxAttribute
	public int getReplicationCount() {
		return replicationCount;
	}

	@JmxOperation
	public void setPersistenceOptions(int deadPartitionsThreshold, int uploadTargets) {
		withPersistenceOptions(deadPartitionsThreshold, uploadTargets);
	}

	@JmxAttribute
	public void setReplicationCount(int replicationCount) {
		withReplicationCount(replicationCount);
	}

	@JmxAttribute(name = "")
	public CrdtPartitions getPartitionsJmx() {
		return partitions;
	}
*/

	@JmxAttribute
	public boolean isDetailedStats() {
		return detailedStats;
	}

	@JmxOperation
	public void startDetailedMonitoring() {
		detailedStats = true;
		for (ICrdtStorage<K, S> storage : crdtStorages.values()) {
			if (storage instanceof RemoteCrdtStorage<K, S> client) {
				client.startDetailedMonitoring();
			}
		}
	}

	@JmxOperation
	public void stopDetailedMonitoring() {
		detailedStats = false;
		for (ICrdtStorage<K, S> storage : crdtStorages.values()) {
			if (storage instanceof RemoteCrdtStorage<K, S> client) {
				client.stopDetailedMonitoring();
			}
		}
	}

	@JmxAttribute
	public BasicStreamStats getUploadStats() {
		return uploadStats;
	}

	@JmxAttribute
	public DetailedStreamStats getUploadStatsDetailed() {
		return uploadStatsDetailed;
	}

	@JmxAttribute
	public BasicStreamStats getDownloadStats() {
		return downloadStats;
	}

	@JmxAttribute
	public DetailedStreamStats getDownloadStatsDetailed() {
		return downloadStatsDetailed;
	}

	@JmxAttribute
	public BasicStreamStats getTakeStats() {
		return takeStats;
	}

	@JmxAttribute
	public DetailedStreamStats getTakeStatsDetailed() {
		return takeStatsDetailed;
	}

	@JmxAttribute
	public BasicStreamStats getRemoveStats() {
		return removeStats;
	}

	@JmxAttribute
	public DetailedStreamStats getRemoveStatsDetailed() {
		return removeStatsDetailed;
	}

	@JmxAttribute
	public BasicStreamStats getRepartitionUploadStats() {
		return repartitionUploadStats;
	}

	@JmxAttribute
	public DetailedStreamStats getRepartitionUploadStatsDetailed() {
		return repartitionUploadStatsDetailed;
	}

	@JmxAttribute
	public Map<P, RemoteCrdtStorage> getCrdtStorageClients() {
		return crdtStorages.entrySet().stream()
			.filter(entry -> entry.getValue() instanceof RemoteCrdtStorage)
			.collect(toMap(Map.Entry::getKey, e -> (RemoteCrdtStorage) e.getValue()));
	}

	@JmxAttribute
	public EventStats getUploadedItems() {
		return uploadedItems;
	}

	@JmxAttribute
	public EventStats getDownloadedItems() {
		return downloadedItems;
	}

	@JmxAttribute
	public EventStats getTakenItems() {
		return takenItems;
	}

	@JmxAttribute
	public EventStats getRemovedItems() {
		return removedItems;
	}

	@JmxAttribute
	public EventStats getRepartitionedItems() {
		return repartitionedItems;
	}
	// endregion
}
