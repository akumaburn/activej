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

package io.activej.cube.linear;

import io.activej.aggregation.AggregationChunkStorage;
import io.activej.common.annotation.ComponentInterface;
import io.activej.common.builder.AbstractBuilder;
import io.activej.cube.exception.CubeException;
import io.activej.jmx.api.ConcurrentJmxBean;
import io.activej.jmx.api.attribute.JmxAttribute;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static io.activej.cube.linear.Utils.executeSqlScript;
import static io.activej.cube.linear.Utils.loadResource;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.sql.Connection.TRANSACTION_READ_COMMITTED;
import static java.util.stream.Collectors.joining;

public final class CubeBackupController implements ConcurrentJmxBean {
	private static final Logger logger = LoggerFactory.getLogger(CubeBackupController.class);

	private static final String SQL_BACKUP_SCRIPT = "sql/backup.sql";

	private final DataSource dataSource;
	private final IChunksBackupService chunksBackupService;
	private CubeSqlNaming sqlNaming = CubeSqlNaming.DEFAULT_SQL_NAMING;

	// region JMX
	private long backupLastStartTimestamp;
	private long backupLastCompleteTimestamp;
	private long backupDurationMillis;
	private @Nullable Exception backupException;

	private long backupDbLastStartTimestamp;
	private long backupDbLastCompleteTimestamp;
	private long backupDbDurationMillis;
	private @Nullable Exception backupDbException;

	private long getChunksToBackupLastStartTimestamp;
	private long getChunksToBackupLastCompleteTimestamp;
	private long getChunksToBackupDurationMillis;
	private @Nullable Exception getChunksToBackupException;

	private long backupChunksLastStartTimestamp;
	private long backupChunksLastCompleteTimestamp;
	private long backupChunksDurationMillis;
	private @Nullable Exception backupChunksException;
	// endregion

	private CubeBackupController(DataSource dataSource, IChunksBackupService chunksBackupService) {
		this.dataSource = dataSource;
		this.chunksBackupService = chunksBackupService;
	}

	public static CubeBackupController create(DataSource dataSource, IChunksBackupService chunksBackupService) {
		return builder(dataSource, chunksBackupService).build();
	}

	public static Builder builder(DataSource dataSource, IChunksBackupService chunksBackupService) {
		return new CubeBackupController(dataSource, chunksBackupService).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, CubeBackupController> {
		private Builder() {}

		public Builder withSqlNaming(CubeSqlNaming sqlScheme) {
			checkNotBuilt(this);
			CubeBackupController.this.sqlNaming = sqlScheme;
			return this;
		}

		@Override
		protected CubeBackupController doBuild() {
			return CubeBackupController.this;
		}
	}

	public void backup() throws CubeException {
		doBackup(null);
	}

	public void backup(long revisionId) throws CubeException {
		doBackup(revisionId);
	}

	private void doBackup(@Nullable Long revisionId) throws CubeException {
		backupLastStartTimestamp = System.currentTimeMillis();

		try {
			Set<Long> chunkIds;
			try (Connection connection = dataSource.getConnection()) {
				if (revisionId == null) {
					revisionId = getMaxRevisionId(connection);
				}
				chunkIds = getChunksToBackup(connection, revisionId);
			} catch (SQLException e) {
				throw new CubeException("Failed to connect to the database", e);
			}
			backupChunks(chunkIds, revisionId);
			backupDb(chunkIds, revisionId);
		} catch (CubeException e) {
			backupException = e;
			throw e;
		} finally {
			backupLastCompleteTimestamp = System.currentTimeMillis();
			backupDurationMillis = backupLastCompleteTimestamp - backupLastStartTimestamp;
		}

		backupException = null;
	}

	private long getMaxRevisionId(Connection connection) throws CubeException {
		try (Statement statement = connection.createStatement()) {
			ResultSet resultSet = statement.executeQuery(sql("""
				SELECT MAX(`revision`)
				FROM {revision}
				"""));

			if (!resultSet.next()) {
				throw new CubeException("Cube is not initialized");
			}
			return resultSet.getLong(1);
		} catch (SQLException e) {
			throw new CubeException("Failed to retrieve maximum revision ID", e);
		}
	}

	private void backupDb(Set<Long> chunkIds, long revisionId) throws CubeException {
		logger.trace("Backing up database on revision {}", revisionId);

		backupDbLastStartTimestamp = 0;

		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			connection.setTransactionIsolation(TRANSACTION_READ_COMMITTED);

			try (Statement statement = connection.createStatement()) {
				String backupScript = sql(new String(loadResource(SQL_BACKUP_SCRIPT), UTF_8))
					.replace("{backup_revision}", String.valueOf(revisionId))
					.replace("{backup_chunk_ids}", chunkIds.isEmpty() ?
						"null" :
						chunkIds.stream()
							.map(Object::toString)
							.collect(joining(",")));
				statement.execute(backupScript);
				connection.commit();
			}
		} catch (SQLException | IOException e) {
			CubeException exception = new CubeException("Failed to back up database", e);
			backupDbException = exception;
			throw exception;
		} finally {
			backupDbLastCompleteTimestamp = System.currentTimeMillis();
			backupDbDurationMillis = backupDbLastCompleteTimestamp - backupDbLastStartTimestamp;
		}

		backupDbException = null;

		logger.trace("Database is backed up on revision {} ", revisionId);
	}

	private Set<Long> getChunksToBackup(Connection connection, long revisionId) throws CubeException {
		getChunksToBackupLastStartTimestamp = 0;

		Set<Long> chunkIds = new HashSet<>();
		try (PreparedStatement stmt = connection.prepareStatement(sql("""
			SELECT `id`
			FROM {chunk}
			WHERE `added_revision`<=?
			  AND (`removed_revision` IS NULL OR `removed_revision`>?)
			"""))
		) {
			stmt.setLong(1, revisionId);
			stmt.setLong(2, revisionId);

			ResultSet resultSet = stmt.executeQuery();

			while (resultSet.next()) {
				chunkIds.add(resultSet.getLong(1));
			}
		} catch (SQLException e) {
			CubeException exception = new CubeException("Failed to retrieve chunks to back up", e);
			getChunksToBackupException = exception;
			throw exception;
		} finally {
			getChunksToBackupLastCompleteTimestamp = System.currentTimeMillis();
			getChunksToBackupDurationMillis = getChunksToBackupLastCompleteTimestamp - getChunksToBackupLastStartTimestamp;
		}

		getChunksToBackupException = null;

		return chunkIds;
	}

	private void backupChunks(Set<Long> chunkIds, long revisionId) throws CubeException {
		logger.trace("Backing up chunks {} on revision {}", chunkIds, revisionId);

		backupChunksLastStartTimestamp = 0;

		try {
			chunksBackupService.backup(revisionId, chunkIds);
		} catch (IOException e) {
			CubeException exception = new CubeException("Failed to backup chunks", e);
			backupChunksException = exception;
			throw exception;
		} finally {
			backupChunksLastCompleteTimestamp = System.currentTimeMillis();
			backupChunksDurationMillis = backupChunksLastCompleteTimestamp - backupChunksLastStartTimestamp;
		}

		backupChunksException = null;

		logger.trace("Chunks {} are backed up on revision {}", chunkIds, revisionId);
	}

	private String sql(String sql) {
		return sqlNaming.sql(sql);
	}

	public void initialize() throws IOException, SQLException {
		logger.trace("Initializing tables");
		executeSqlScript(dataSource, sql(new String(loadResource("sql/ddl/uplink_revision.sql"), UTF_8)));
		executeSqlScript(dataSource, sql(new String(loadResource("sql/ddl/uplink_chunk.sql"), UTF_8)));
		executeSqlScript(dataSource, sql(new String(loadResource("sql/ddl/uplink_position.sql"), UTF_8)));
		executeSqlScript(dataSource, sql(new String(loadResource("sql/ddl/uplink_backup.sql"), UTF_8)));
	}

	public void truncateTables() throws SQLException {
		logger.trace("Truncate tables");
		try (
			Connection connection = dataSource.getConnection();
			Statement statement = connection.createStatement()
		) {
			statement.execute(sql("TRUNCATE TABLE {chunk}"));
			statement.execute(sql("TRUNCATE TABLE {position}"));
			statement.execute(sql("""
				DELETE
				FROM {revision}
				WHERE `revision`!=0
				"""));

			statement.execute(sql("TRUNCATE TABLE {backup}"));
			statement.execute(sql("TRUNCATE TABLE {backup_chunk}"));
			statement.execute(sql("TRUNCATE TABLE {backup_position}"));
		}
	}

	// region JMX getters
	@JmxAttribute
	public @Nullable Instant getBackupLastStartTime() {
		return backupLastStartTimestamp != 0L ? Instant.ofEpochMilli(backupLastStartTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Instant getBackupLastCompleteTime() {
		return backupLastCompleteTimestamp != 0L ? Instant.ofEpochMilli(backupLastCompleteTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Duration getBackupCurrentDuration() {
		return backupLastStartTimestamp - backupLastCompleteTimestamp > 0 ?
			Duration.ofMillis(System.currentTimeMillis() - backupLastStartTimestamp) :
			null;
	}

	@JmxAttribute
	public Duration getBackupLastDuration() {
		return Duration.ofMillis(backupDurationMillis);
	}

	@JmxAttribute(optional = true)
	public @Nullable Exception getBackupLastException() {
		return backupException;
	}

	@JmxAttribute
	public @Nullable Instant getBackupDbLastStartTime() {
		return backupDbLastStartTimestamp != 0L ? Instant.ofEpochMilli(backupDbLastStartTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Instant getBackupDbLastCompleteTime() {
		return backupDbLastCompleteTimestamp != 0L ? Instant.ofEpochMilli(backupDbLastCompleteTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Duration getBackupDbCurrentDuration() {
		return backupDbLastStartTimestamp - backupDbLastCompleteTimestamp > 0 ?
			Duration.ofMillis(System.currentTimeMillis() - backupDbLastStartTimestamp) :
			null;
	}

	@JmxAttribute
	public Duration getBackupDbLastDuration() {
		return Duration.ofMillis(backupDbDurationMillis);
	}

	@JmxAttribute(optional = true)
	public @Nullable Exception getBackupDbLastException() {
		return backupDbException;
	}

	@JmxAttribute
	public @Nullable Instant getGetChunksToBackupLastStartTime() {
		return getChunksToBackupLastStartTimestamp != 0L ? Instant.ofEpochMilli(getChunksToBackupLastStartTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Instant getGetChunksToBackupLastCompleteTime() {
		return getChunksToBackupLastCompleteTimestamp != 0L ? Instant.ofEpochMilli(getChunksToBackupLastCompleteTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Duration getGetChunksToBackupCurrentDuration() {
		return getChunksToBackupLastStartTimestamp - getChunksToBackupLastCompleteTimestamp > 0 ?
			Duration.ofMillis(System.currentTimeMillis() - getChunksToBackupLastStartTimestamp) :
			null;
	}

	@JmxAttribute
	public Duration getGetChunksToBackupLastDuration() {
		return Duration.ofMillis(getChunksToBackupDurationMillis);
	}

	@JmxAttribute(optional = true)
	public @Nullable Exception getGetChunksToBackupLastException() {
		return getChunksToBackupException;
	}

	@JmxAttribute
	public @Nullable Instant getBackupChunksLastStartTime() {
		return backupChunksLastStartTimestamp != 0L ? Instant.ofEpochMilli(backupChunksLastStartTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Instant getBackupChunksLastCompleteTime() {
		return backupChunksLastCompleteTimestamp != 0L ? Instant.ofEpochMilli(backupChunksLastCompleteTimestamp) : null;
	}

	@JmxAttribute
	public @Nullable Duration getBackupChunksCurrentDuration() {
		return backupChunksLastStartTimestamp - backupChunksLastCompleteTimestamp > 0 ?
			Duration.ofMillis(System.currentTimeMillis() - backupChunksLastStartTimestamp) :
			null;
	}

	@JmxAttribute
	public Duration getBackupChunksLastDuration() {
		return Duration.ofMillis(backupChunksDurationMillis);
	}

	@JmxAttribute(optional = true)
	public @Nullable Exception getBackupChunksLastException() {
		return backupChunksException;
	}
	// endregion

	@ComponentInterface
	public interface IChunksBackupService {
		void backup(long revisionId, Set<Long> chunkIds) throws IOException;

		static IChunksBackupService ofReactiveAggregationChunkStorage(AggregationChunkStorage<Long> storage) {
			return Utils.backupServiceOfStorage(storage);
		}
	}
}

