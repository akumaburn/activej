package io.activej.cube;

import io.activej.aggregation.AggregationChunkStorage;
import io.activej.aggregation.ChunkIdJsonCodec;
import io.activej.aggregation.IAggregationChunkStorage;
import io.activej.async.function.AsyncSupplier;
import io.activej.codegen.DefiningClassLoader;
import io.activej.common.ref.RefLong;
import io.activej.csp.process.frame.FrameFormats;
import io.activej.cube.bean.DataItemResultString;
import io.activej.cube.bean.DataItemString1;
import io.activej.cube.bean.DataItemString2;
import io.activej.cube.ot.CubeDiff;
import io.activej.datastream.consumer.ToListStreamConsumer;
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
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.aggregation.fieldtype.FieldTypes.*;
import static io.activej.aggregation.measure.Measures.sum;
import static io.activej.aggregation.predicate.AggregationPredicates.*;
import static io.activej.cube.Cube.AggregationConfig.id;
import static io.activej.promise.TestUtils.await;
import static java.util.stream.Collectors.toSet;
import static org.junit.Assert.assertEquals;

public class StringDimensionTest {
	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final TemporaryFolder temporaryFolder = new TemporaryFolder();

	@Rule
	public final ClassBuilderConstantsRule classBuilderConstantsRule = new ClassBuilderConstantsRule();

	@Test
	public void testQuery() throws Exception {
		Path aggregationsDir = temporaryFolder.newFolder().toPath();
		Reactor reactor = Reactor.getCurrentReactor();
		Executor executor = Executors.newCachedThreadPool();
		DefiningClassLoader classLoader = DefiningClassLoader.create();

		FileSystem fs = FileSystem.create(reactor, executor, aggregationsDir);
		await(fs.start());
		IAggregationChunkStorage<Long> aggregationChunkStorage = AggregationChunkStorage.create(reactor, ChunkIdJsonCodec.ofLong(),
			AsyncSupplier.of(new RefLong(0)::inc), FrameFormats.lz4(), fs);
		Cube cube = Cube.builder(reactor, executor, classLoader, aggregationChunkStorage)
			.withDimension("key1", ofString())
			.withDimension("key2", ofInt())
			.withMeasure("metric1", sum(ofLong()))
			.withMeasure("metric2", sum(ofLong()))
			.withMeasure("metric3", sum(ofLong()))
			.withAggregation(id("detailedAggregation")
				.withDimensions("key1", "key2")
				.withMeasures("metric1", "metric2", "metric3"))
			.build();

		CubeDiff consumer1Result = await(StreamSuppliers.ofValues(
				new DataItemString1("str1", 2, 10, 20),
				new DataItemString1("str2", 3, 10, 20))
			.streamTo(cube.consume(DataItemString1.class)));

		CubeDiff consumer2Result = await(StreamSuppliers.ofValues(
				new DataItemString2("str2", 3, 10, 20),
				new DataItemString2("str1", 4, 10, 20))
			.streamTo(cube.consume(DataItemString2.class)));

		await(aggregationChunkStorage.finish(consumer1Result.addedChunks().map(id -> (long) id).collect(toSet())));
		await(aggregationChunkStorage.finish(consumer2Result.addedChunks().map(id -> (long) id).collect(toSet())));

		cube.apply(consumer1Result);
		cube.apply(consumer2Result);

		ToListStreamConsumer<DataItemResultString> consumerToList = ToListStreamConsumer.create();
		await(cube.queryRawStream(List.of("key1", "key2"), List.of("metric1", "metric2", "metric3"),
				and(eq("key1", "str2"), eq("key2", 3)),
				DataItemResultString.class, DefiningClassLoader.create(classLoader))
			.streamTo(consumerToList));

		List<DataItemResultString> actual = consumerToList.getList();
		List<DataItemResultString> expected = List.of(new DataItemResultString("str2", 3, 10, 30, 20));

		assertEquals(expected, actual);
	}
}
