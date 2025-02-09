package io.activej.cube.service;

import io.activej.aggregation.AggregationChunkStorage;
import io.activej.aggregation.ChunkIdJsonCodec;
import io.activej.aggregation.IAggregationChunkStorage;
import io.activej.async.function.AsyncSupplier;
import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.exception.UnknownFormatException;
import io.activej.common.ref.RefLong;
import io.activej.csp.process.frame.ChannelFrameDecoder;
import io.activej.csp.process.frame.ChannelFrameEncoder;
import io.activej.csp.process.frame.FrameFormats;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.cube.Cube;
import io.activej.cube.CubeTestBase;
import io.activej.cube.LogItem;
import io.activej.cube.exception.CubeException;
import io.activej.cube.ot.CubeDiff;
import io.activej.datastream.consumer.StreamConsumers;
import io.activej.datastream.supplier.StreamSuppliers;
import io.activej.etl.LogDiff;
import io.activej.etl.LogOTProcessor;
import io.activej.etl.LogOTState;
import io.activej.fs.FileMetadata;
import io.activej.fs.FileSystem;
import io.activej.multilog.IMultilog;
import io.activej.multilog.Multilog;
import io.activej.ot.OTStateManager;
import io.activej.ot.uplink.AsyncOTUplink;
import io.activej.serializer.BinarySerializer;
import io.activej.serializer.SerializerFactory;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.activej.aggregation.fieldtype.FieldTypes.*;
import static io.activej.aggregation.measure.Measures.sum;
import static io.activej.common.Utils.first;
import static io.activej.cube.Cube.AggregationConfig.id;
import static io.activej.multilog.LogNamingScheme.NAME_PARTITION_REMAINDER_SEQ;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;

public final class CubeLogProcessorControllerTest extends CubeTestBase {
	private IMultilog<LogItem> multilog;
	private FileSystem logsFileSystem;
	private CubeLogProcessorController<Long, Long> controller;

	@Before
	public void setUp() throws Exception {
		super.setUp();
		Path aggregationsDir = temporaryFolder.newFolder().toPath();
		Path logsDir = temporaryFolder.newFolder().toPath();

		FileSystem aggregationFS = FileSystem.create(reactor, EXECUTOR, aggregationsDir);
		await(aggregationFS.start());
		IAggregationChunkStorage<Long> aggregationChunkStorage = AggregationChunkStorage.create(reactor, ChunkIdJsonCodec.ofLong(), AsyncSupplier.of(new RefLong(0)::inc),
			FrameFormats.lz4(), aggregationFS);
		Cube cube = Cube.builder(reactor, EXECUTOR, CLASS_LOADER, aggregationChunkStorage)
			.withDimension("date", ofLocalDate())
			.withDimension("advertiser", ofInt())
			.withDimension("campaign", ofInt())
			.withDimension("banner", ofInt())
			.withRelation("campaign", "advertiser")
			.withRelation("banner", "campaign")
			.withMeasure("impressions", sum(ofLong()))
			.withMeasure("clicks", sum(ofLong()))
			.withMeasure("conversions", sum(ofLong()))
			.withMeasure("revenue", sum(ofDouble()))
			.withAggregation(id("detailed")
				.withDimensions("date", "advertiser", "campaign", "banner")
				.withMeasures("impressions", "clicks", "conversions", "revenue"))
			.withAggregation(id("date")
				.withDimensions("date")
				.withMeasures("impressions", "clicks", "conversions", "revenue"))
			.withAggregation(id("advertiser")
				.withDimensions("advertiser")
				.withMeasures("impressions", "clicks", "conversions", "revenue"))
			.build();

		AsyncOTUplink<Long, LogDiff<CubeDiff>, ?> uplink = uplinkFactory.create(cube);

		LogOTState<CubeDiff> logState = LogOTState.create(cube);
		OTStateManager<Long, LogDiff<CubeDiff>> stateManager = OTStateManager.create(reactor, LOG_OT, uplink, logState);

		logsFileSystem = FileSystem.create(reactor, EXECUTOR, logsDir);
		await(logsFileSystem.start());
		BinarySerializer<LogItem> serializer = SerializerFactory.defaultInstance()
			.create(CLASS_LOADER, LogItem.class);
		multilog = Multilog.create(reactor, logsFileSystem, FrameFormats.lz4(), serializer, NAME_PARTITION_REMAINDER_SEQ);

		LogOTProcessor<LogItem, CubeDiff> logProcessor = LogOTProcessor.create(
			reactor,
			multilog,
			cube.logStreamConsumer(LogItem.class),
			"test",
			List.of("partitionA"),
			logState);

		controller = CubeLogProcessorController.create(
			reactor,
			logState,
			stateManager,
			aggregationChunkStorage,
			List.of(logProcessor));

		await(stateManager.checkout());
	}

	@Test
	public void testMalformedLogs() {
		await(StreamSuppliers.ofValue(new LogItem("test")).streamTo(
			StreamConsumers.ofPromise(multilog.write("partitionA"))));

		Map<String, FileMetadata> files = await(logsFileSystem.list("**"));
		assertEquals(1, files.size());

		String logFile = first(files.keySet());
		ByteBuf serializedData = await(logsFileSystem.download(logFile).then(supplier -> supplier
			.transformWith(ChannelFrameDecoder.builder(FrameFormats.lz4())
				.withDecoderResets()
				.build())
			.toCollector(ByteBufs.collector())));

		// offset right before string
		int bufSize = serializedData.readRemaining();
		serializedData.tail(serializedData.head() + 49);

		byte[] malformed = new byte[bufSize - 49];
		malformed[0] = 127; // exceeds message size
		await(ChannelSuppliers.ofValues(serializedData, ByteBuf.wrapForReading(malformed))
			.transformWith(ChannelFrameEncoder.builder(FrameFormats.lz4())
				.withEncoderResets()
				.build())
			.streamTo(logsFileSystem.upload(logFile)));

		CubeException exception = awaitException(controller.process());
		Throwable firstCause = exception.getCause();
		assertThat(firstCause, instanceOf(UnknownFormatException.class));
		assertThat(firstCause.getCause(), instanceOf(StringIndexOutOfBoundsException.class));
	}
}
