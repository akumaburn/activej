package io.activej.aggregation;

import io.activej.aggregation.ot.AggregationDiff;
import io.activej.aggregation.ot.AggregationStructure;
import io.activej.async.function.AsyncSupplier;
import io.activej.codegen.DefiningClassLoader;
import io.activej.common.ref.RefLong;
import io.activej.csp.process.frame.FrameFormat;
import io.activej.csp.process.frame.FrameFormats;
import io.activej.datastream.supplier.StreamSupplier;
import io.activej.datastream.supplier.StreamSuppliers;
import io.activej.fs.FileSystem;
import io.activej.reactor.Reactor;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.ClassBuilderConstantsRule;
import io.activej.test.rules.EventloopRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.aggregation.fieldtype.FieldTypes.ofInt;
import static io.activej.aggregation.fieldtype.FieldTypes.ofString;
import static io.activej.aggregation.measure.Measures.union;
import static io.activej.promise.TestUtils.await;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;

public class InvertedIndexTest {

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Rule
	public final ClassBuilderConstantsRule classBuilderConstantsRule = new ClassBuilderConstantsRule();

	public static class InvertedIndexQueryResult {
		public String word;
		public Set<Integer> documents;

		@SuppressWarnings("unused")
		public InvertedIndexQueryResult() {
		}

		public InvertedIndexQueryResult(String word, Set<Integer> documents) {
			this.word = word;
			this.documents = documents;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			InvertedIndexQueryResult that = (InvertedIndexQueryResult) o;

			if (!Objects.equals(word, that.word)) return false;
			return Objects.equals(documents, that.documents);

		}

		@Override
		public int hashCode() {
			int result = word != null ? word.hashCode() : 0;
			result = 31 * result + (documents != null ? documents.hashCode() : 0);
			return result;
		}

		@Override
		public String toString() {
			return
				"InvertedIndexQueryResult{" +
				"word='" + word + '\'' +
				", documents=" + documents +
				'}';
		}
	}

	@Test
	public void testInvertedIndex() throws Exception {
		Executor executor = Executors.newCachedThreadPool();
		Reactor reactor = Reactor.getCurrentReactor();
		DefiningClassLoader classLoader = DefiningClassLoader.create();
		Path path = temporaryFolder.newFolder().toPath();
		FileSystem fs = FileSystem.create(reactor, executor, path);
		await(fs.start());
		FrameFormat frameFormat = FrameFormats.lz4();
		IAggregationChunkStorage<Long> aggregationChunkStorage = AggregationChunkStorage.create(reactor, ChunkIdJsonCodec.ofLong(), AsyncSupplier.of(new RefLong(0)::inc), frameFormat, fs);

		Aggregation aggregation = Aggregation.builder(reactor, executor, classLoader, aggregationChunkStorage, frameFormat)
			.withStructure(AggregationStructure.builder(ChunkIdJsonCodec.ofLong())
				.withKey("word", ofString())
				.withMeasure("documents", union(ofInt()))
				.build())
			.withTemporarySortDir(temporaryFolder.newFolder().toPath())
			.build();

		StreamSupplier<InvertedIndexRecord> supplier = StreamSuppliers.ofValues(
			new InvertedIndexRecord("fox", 1),
			new InvertedIndexRecord("brown", 2),
			new InvertedIndexRecord("fox", 3));

		doProcess(aggregationChunkStorage, aggregation, supplier);

		supplier = StreamSuppliers.ofValues(
			new InvertedIndexRecord("brown", 3),
			new InvertedIndexRecord("lazy", 4),
			new InvertedIndexRecord("dog", 1));

		doProcess(aggregationChunkStorage, aggregation, supplier);

		supplier = StreamSuppliers.ofValues(
			new InvertedIndexRecord("quick", 1),
			new InvertedIndexRecord("fox", 4),
			new InvertedIndexRecord("brown", 10));

		doProcess(aggregationChunkStorage, aggregation, supplier);

		AggregationQuery query = AggregationQuery.builder()
			.withKeys("word")
			.withMeasures("documents")
			.build();

		List<InvertedIndexQueryResult> list = await(aggregation.query(query, InvertedIndexQueryResult.class, DefiningClassLoader.create(classLoader))
			.toList());

		List<InvertedIndexQueryResult> expectedResult = List.of(
			new InvertedIndexQueryResult("brown", Set.of(2, 3, 10)),
			new InvertedIndexQueryResult("dog", Set.of(1)),
			new InvertedIndexQueryResult("fox", Set.of(1, 3, 4)),
			new InvertedIndexQueryResult("lazy", Set.of(4)),
			new InvertedIndexQueryResult("quick", Set.of(1)));

		assertEquals(expectedResult, list);
	}

	public void doProcess(IAggregationChunkStorage<Long> aggregationChunkStorage, Aggregation aggregation, StreamSupplier<InvertedIndexRecord> supplier) {
		AggregationDiff diff = await(supplier.streamTo(aggregation.consume(InvertedIndexRecord.class)));
		aggregation.getState().apply(diff);
		await(aggregationChunkStorage.finish(getAddedChunks(diff)));
	}

	private Set<Long> getAddedChunks(AggregationDiff aggregationDiff) {
		return aggregationDiff.getAddedChunks().stream().map(AggregationChunk::getChunkId).map(id -> (long) id).collect(toSet());
	}

}
