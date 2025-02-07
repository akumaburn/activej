package io.activej.cube.linear;

import io.activej.aggregation.AggregationChunk;
import io.activej.aggregation.PrimaryKey;
import io.activej.aggregation.ot.AggregationDiff;
import io.activej.async.function.AsyncSupplier;
import io.activej.common.ref.RefLong;
import io.activej.cube.Cube;
import io.activej.cube.ot.CubeDiff;
import io.activej.cube.ot.CubeDiffJsonCodec;
import io.activej.cube.ot.CubeOT;
import io.activej.etl.LogDiff;
import io.activej.etl.LogDiffCodec;
import io.activej.etl.LogOT;
import io.activej.etl.LogPositionDiff;
import io.activej.multilog.LogFile;
import io.activej.multilog.LogPosition;
import io.activej.ot.OTCommit;
import io.activej.ot.repository.MySqlOTRepository;
import io.activej.ot.system.OTSystem;
import io.activej.ot.uplink.AsyncOTUplink.FetchData;
import io.activej.reactor.Reactor;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static io.activej.aggregation.fieldtype.FieldTypes.*;
import static io.activej.aggregation.measure.Measures.sum;
import static io.activej.common.Utils.concat;
import static io.activej.common.Utils.first;
import static io.activej.cube.Cube.AggregationConfig.id;
import static io.activej.cube.TestUtils.initializeRepository;
import static io.activej.cube.linear.CubeUplinkMigrationService.builderOfEmptyCube;
import static io.activej.promise.TestUtils.await;
import static io.activej.test.TestUtils.dataSource;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class CubeUplinkMigrationServiceTest {

	public static final OTSystem<LogDiff<CubeDiff>> OT_SYSTEM = LogOT.createLogOT(CubeOT.createCubeOT());

	@ClassRule
	public static EventloopRule eventloopRule = new EventloopRule();

	private DataSource dataSource;
	private Cube cube;

	private MySqlOTRepository<LogDiff<CubeDiff>> repo;
	private CubeMySqlOTUplink uplink;

	@Before
	public void setUp() throws Exception {
		dataSource = dataSource("test.properties");

		Reactor reactor = Reactor.getCurrentReactor();
		Executor executor = Executors.newCachedThreadPool();

		cube = builderOfEmptyCube(reactor, executor)
			.withDimension("campaign", ofInt())
			.withDimension("advertiser", ofInt())
			.withMeasure("impressions", sum(ofLong()))
			.withMeasure("clicks", sum(ofLong()))
			.withMeasure("conversions", sum(ofLong()))
			.withMeasure("revenue", sum(ofDouble()))
			.withAggregation(id("campaign")
				.withDimensions("campaign")
				.withMeasures("impressions", "clicks", "conversions", "revenue"))
			.withAggregation(id("advertiser-campaign")
				.withDimensions("advertiser", "campaign")
				.withMeasures("impressions", "clicks", "conversions", "revenue"))
			.build();

		LogDiffCodec<CubeDiff> diffCodec = LogDiffCodec.create(CubeDiffJsonCodec.create(cube));

		repo = MySqlOTRepository.create(reactor, executor, dataSource, AsyncSupplier.of(new RefLong(0)::inc), OT_SYSTEM, diffCodec);
		initializeRepository(repo);

		PrimaryKeyCodecs codecs = PrimaryKeyCodecs.ofCube(cube);
		uplink = CubeMySqlOTUplink.builder(reactor, executor, dataSource, codecs)
			.withMeasuresValidator(MeasuresValidator.ofCube(cube))
			.build();

		uplink.initialize();
		uplink.truncateTables();
	}

	@Test
	public void migration() throws ExecutionException, InterruptedException {
		FetchData<Long, LogDiff<CubeDiff>> checkoutData = await(uplink.checkout());
		assertEquals(0, (long) checkoutData.commitId());
		assertEquals(0, checkoutData.level());
		assertTrue(checkoutData.diffs().isEmpty());

		CubeUplinkMigrationService service = new CubeUplinkMigrationService();
		service.cube = cube;

		List<LogDiff<CubeDiff>> diffs1 = List.of(
			LogDiff.of(Map.of(
					"a", new LogPositionDiff(LogPosition.initial(), LogPosition.create(new LogFile("a", 12), 13)), "b", new LogPositionDiff(LogPosition.initial(), LogPosition.create(new LogFile("b", 23), 34))),
				List.of(
					CubeDiff.of(Map.of(
						"campaign", AggregationDiff.of(Set.of(AggregationChunk.create(1L, List.of("clicks", "impressions"), PrimaryKey.ofArray(12), PrimaryKey.ofArray(34), 10), AggregationChunk.create(2L, List.of("impressions"), PrimaryKey.ofArray(123), PrimaryKey.ofArray(345), 20))),
						"advertiser-campaign", AggregationDiff.of(Set.of(AggregationChunk.create(3L, List.of("clicks", "impressions", "revenue"), PrimaryKey.ofArray(15, 654), PrimaryKey.ofArray(35, 76763), 1234), AggregationChunk.create(4L, List.of("conversions"), PrimaryKey.ofArray(12, 23), PrimaryKey.ofArray(124, 543), 22))))))
			));

		List<LogDiff<CubeDiff>> diffs2 = List.of(
			LogDiff.of(
				Map.of(
					"a", new LogPositionDiff(
						LogPosition.create(new LogFile("a", 12), 13),
						LogPosition.create(new LogFile("a2", 53), 1381)), "b", new LogPositionDiff(
						LogPosition.create(new LogFile("b", 23), 34),
						LogPosition.create(new LogFile("b4", 231), 3124))),
				List.of(
					CubeDiff.of(Map.of(
						"campaign", AggregationDiff.of(
							Set.of(AggregationChunk.create(5L, List.of("clicks"), PrimaryKey.ofArray(12453), PrimaryKey.ofArray(12453121), 23523), AggregationChunk.create(6L, List.of("impressions", "clicks", "conversions", "revenue"), PrimaryKey.ofArray(1113), PrimaryKey.ofArray(34512412), 52350)),
							Set.of(AggregationChunk.create(1L, List.of("clicks", "impressions"), PrimaryKey.ofArray(12), PrimaryKey.ofArray(34), 10))),
						"advertiser-campaign", AggregationDiff.of(
							Set.of(AggregationChunk.create(7L, List.of("clicks", "revenue"), PrimaryKey.ofArray(1125, 53), PrimaryKey.ofArray(1422142, 653), 122134), AggregationChunk.create(8L, List.of("conversions", "impressions"), PrimaryKey.ofArray(44, 52), PrimaryKey.ofArray(124124, 122), 65472))))
					)
				)
			));

		push(diffs1);
		push(diffs2);

		service.migrate(dataSource, dataSource);

		checkoutData = await(uplink.checkout());
		assertEquals(1, (long) checkoutData.commitId());
		assertEquals(1, checkoutData.level());

		List<LogDiff<CubeDiff>> expected = OT_SYSTEM.squash(concat(diffs1, diffs2));

		assertEquals(expected, checkoutData.diffs());
	}

	private void push(List<LogDiff<CubeDiff>> diffs) {
		OTCommit<Long, LogDiff<CubeDiff>> parent = await(repo.loadCommit(first(await(repo.getHeads()))));
		Long commitId = await(repo.createCommitId());
		await(repo.pushAndUpdateHead(OTCommit.ofCommit(0, commitId, parent.getId(), diffs, parent.getLevel())));
	}
}
