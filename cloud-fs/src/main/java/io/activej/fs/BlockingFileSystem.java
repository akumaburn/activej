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

package io.activej.fs;

import io.activej.common.ApplicationSettings;
import io.activej.common.builder.AbstractBuilder;
import io.activej.common.service.BlockingService;
import io.activej.common.time.CurrentTimeProvider;
import io.activej.fs.exception.ForbiddenPathException;
import io.activej.fs.util.ForwardingOutputStream;
import io.activej.fs.util.LimitedInputStream;
import io.activej.fs.util.UploadOutputStream;
import io.activej.jmx.api.ConcurrentJmxBean;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collector;

import static io.activej.common.Checks.checkArgument;
import static io.activej.common.Checks.checkState;
import static io.activej.common.Utils.isBijection;
import static io.activej.common.Utils.noMergeFunction;
import static io.activej.common.function.BiConsumerEx.uncheckedOf;
import static io.activej.fs.LocalFileUtils.*;
import static java.nio.file.StandardOpenOption.*;

public final class BlockingFileSystem implements IBlockingFileSystem, BlockingService, ConcurrentJmxBean {
	private static final Logger logger = LoggerFactory.getLogger(BlockingFileSystem.class);

	public static final String DEFAULT_TEMP_DIR = ".upload";
	public static final boolean DEFAULT_FSYNC_UPLOADS = ApplicationSettings.getBoolean(BlockingFileSystem.class, "fsyncUploads", false);
	public static final boolean DEFAULT_FSYNC_DIRECTORIES = ApplicationSettings.getBoolean(BlockingFileSystem.class, "fsyncDirectories", false);
	public static final boolean DEFAULT_FSYNC_APPENDS = ApplicationSettings.getBoolean(BlockingFileSystem.class, "fsyncAppends", false);

	private static final Set<StandardOpenOption> DEFAULT_APPEND_OPTIONS = Set.of(WRITE);
	private static final Set<StandardOpenOption> DEFAULT_APPEND_NEW_OPTIONS = Set.of(WRITE, CREATE);

	private final Path storage;

	private final Set<OpenOption> appendOptions = new HashSet<>(DEFAULT_APPEND_OPTIONS);
	private final Set<OpenOption> appendNewOptions = new HashSet<>(DEFAULT_APPEND_NEW_OPTIONS);

	private boolean hardLinkOnCopy = false;
	private Path tempDir;
	private boolean fsyncUploads = DEFAULT_FSYNC_UPLOADS;
	private boolean fsyncDirectories = DEFAULT_FSYNC_DIRECTORIES;

	private boolean started;

	CurrentTimeProvider now = CurrentTimeProvider.ofSystem();

	private BlockingFileSystem(Path storage) {
		this.storage = storage;
		this.tempDir = storage.resolve(DEFAULT_TEMP_DIR);

		if (DEFAULT_FSYNC_APPENDS) {
			appendOptions.add(SYNC);
			appendNewOptions.add(SYNC);
		}
	}

	public static BlockingFileSystem create(Path storageDir) {
		return builder(storageDir).build();
	}

	public static Builder builder(Path storageDir) {
		return new BlockingFileSystem(storageDir.normalize()).new Builder();
	}

	public final class Builder extends AbstractBuilder<Builder, BlockingFileSystem> {
		private Builder() {}

		/**
		 * If set to {@code true}, an attempt to create a hard link will be made when copying files
		 */
		public Builder withHardLinkOnCopy(boolean hardLinkOnCopy) {
			checkNotBuilt(this);
			BlockingFileSystem.this.hardLinkOnCopy = hardLinkOnCopy;
			return this;
		}

		/**
		 * Sets a temporary directory for files to be stored while uploading.
		 */
		public Builder withTempDir(Path tempDir) {
			checkNotBuilt(this);
			BlockingFileSystem.this.tempDir = tempDir;
			return this;
		}

		/**
		 * If set to {@code true}, all uploaded files will be synchronously persisted to the storage device.
		 * <p>
		 * <b>Note: may be slow when there are a lot of new files uploaded</b>
		 */
		public Builder withFSyncUploads(boolean fsync) {
			checkNotBuilt(this);
			BlockingFileSystem.this.fsyncUploads = fsync;
			return this;
		}

		/**
		 * If set to {@code true}, all newly created directories as well all changes to the directories
		 * (e.g. adding new files, updating existing, etc.)
		 * will be synchronously persisted to the storage device.
		 * <p>
		 * <b>Note: may be slow when there are a lot of new directories created or or changed</b>
		 */
		public Builder withFSyncDirectories(boolean fsync) {
			checkNotBuilt(this);
			BlockingFileSystem.this.fsyncDirectories = fsync;
			return this;
		}

		/**
		 * If set to {@code true}, each write to {@link #append} consumer will be synchronously written to the storage device.
		 * <p>
		 * <b>Note: significantly slows down appends</b>
		 */
		public Builder withFSyncAppends(boolean fsync) {
			checkNotBuilt(this);
			if (fsync) {
				appendOptions.add(SYNC);
				appendNewOptions.add(SYNC);
			} else {
				appendOptions.remove(SYNC);
				appendNewOptions.remove(SYNC);
			}
			return this;
		}

		@Override
		protected BlockingFileSystem doBuild() {
			return BlockingFileSystem.this;
		}
	}

	@Override
	public OutputStream upload(String name) throws IOException {
		checkStarted();
		Path tempPath = LocalFileUtils.createTempUploadFile(tempDir);
		return new UploadOutputStream(tempPath, resolve(name), fsyncUploads, fsyncDirectories, this::doMove);
	}

	@Override
	public OutputStream upload(String name, long size) throws IOException {
		checkStarted();
		Path tempPath = LocalFileUtils.createTempUploadFile(tempDir);
		return new UploadOutputStream(tempPath, resolve(name), fsyncUploads, fsyncDirectories, this::doMove) {
			long totalSize;

			@Override
			protected void onBytes(int len) throws IOException {
				if ((totalSize += len) > size) throw new IOException("Size mismatch");
			}

			@Override
			protected void onClose() throws IOException {
				if (totalSize != size) throw new IOException("Size mismatch");
			}
		};
	}

	@Override
	public OutputStream append(String name, long offset) throws IOException {
		checkStarted();
		checkArgument(offset >= 0, "Offset cannot be less than 0");

		Path path = resolve(name);
		FileChannel channel;
		if (offset == 0) {
			channel = ensureTarget(path, () -> FileChannel.open(path, appendNewOptions));
			if (fsyncDirectories) {
				tryFsync(path.getParent());
			}
		} else {
			channel = FileChannel.open(path, appendOptions);
		}
		if (channel.size() < offset) {
			throw new IOException("Offset exceeds file size");
		}
		channel.position(offset);
		return new ForwardingOutputStream(Channels.newOutputStream(channel)) {
			boolean closed;

			@Override
			public void close() throws IOException {
				if (closed) return;
				closed = true;
				peer.close();
				if (fsyncUploads && !appendOptions.contains(SYNC)) {
					tryFsync(path);
				}
			}
		};
	}

	@Override
	public InputStream download(String name, long offset, long limit) throws IOException {
		checkStarted();
		Path path = resolve(name);
		if (!Files.exists(path)) {
			throw new FileNotFoundException(name);
		}
		if (offset > Files.size(path)) {
			throw new IOException("Offset exceeds file size");
		}
		FileInputStream fileInputStream = new FileInputStream(path.toFile());

		//noinspection ResultOfMethodCallIgnored
		fileInputStream.skip(offset);
		return new LimitedInputStream(fileInputStream, limit);
	}

	@Override
	public void delete(String name) throws IOException {
		checkStarted();
		Path path = resolve(name);
		// cannot delete storage
		if (path.equals(storage)) return;

		Files.deleteIfExists(path);
	}

	@Override
	public void copy(String name, String target) throws IOException {
		checkStarted();
		copyImpl(Map.of(name, target));
	}

	@Override
	public void copyAll(Map<String, String> sourceToTarget) throws IOException {
		checkStarted();
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		copyImpl(sourceToTarget);
	}

	@Override
	public void move(String name, String target) throws IOException {
		checkStarted();
		moveImpl(Map.of(name, target));
	}

	@Override
	public void moveAll(Map<String, String> sourceToTarget) throws IOException {
		checkStarted();
		checkArgument(isBijection(sourceToTarget), "Targets must be unique");
		moveImpl(sourceToTarget);
	}

	@Override
	public Map<String, FileMetadata> list(String glob) throws IOException {
		checkStarted();
		if (glob.isEmpty()) return Map.of();

		String subdir = extractSubDir(glob);
		Path subdirectory = resolve(subdir);
		String subglob = glob.substring(subdir.length());

		return findMatching(tempDir, subglob, subdirectory).stream()
			.collect(Collector.of(
				(Supplier<Map<String, FileMetadata>>) HashMap::new,
				uncheckedOf((map, path) -> {
					FileMetadata metadata = toFileMetadata(path);
					if (metadata != null) {
						String filename = TO_REMOTE_NAME.apply(storage.relativize(path).toString());
						map.put(filename, metadata);
					}
				}),
				noMergeFunction())
			);
	}

	@Override
	public @Nullable FileMetadata info(String name) throws IOException {
		checkStarted();
		return toFileMetadata(resolve(name));
	}

	@Override
	public void ping() {
		// local fs is always available
		checkStarted();
	}

	@Override
	public void start() throws IOException {
		LocalFileUtils.init(storage, tempDir, fsyncDirectories);
		started = true;
	}

	@Override
	public void stop() {
	}

	@Override
	public String toString() {
		return "BlockingFileSystem{storage=" + storage + '}';
	}

	private static FileMetadata toFileMetadata(Path path) throws IOException {
		try {
			return LocalFileUtils.toFileMetadata(path);
		} catch (IOException e) {
			logger.warn("Failed to retrieve metadata for {}", path, e);
			throw e;
		}
	}

	private Path resolve(String name) throws IOException {
		try {
			return LocalFileUtils.resolve(storage, tempDir, TO_LOCAL_NAME.apply(name));
		} catch (ForbiddenPathException e) {
			throw new FileSystemException(name, null, e.getMessage());
		}
	}

	private void moveImpl(Map<String, String> sourceToTargetMap) throws IOException {
		Set<Path> toFSync = new HashSet<>();
		try {
			for (Map.Entry<String, String> entry : sourceToTargetMap.entrySet()) {
				Path path = resolve(entry.getKey());
				if (!Files.isRegularFile(path)) {
					throw new FileNotFoundException("File '" + entry.getKey() + "' not found");
				}
				Path targetPath = resolve(entry.getValue());
				if (path.equals(targetPath)) {
					touch(path, now);
					if (fsyncDirectories) {
						toFSync.add(path);
					}
					continue;
				}
				doMove(path, targetPath);
				if (fsyncDirectories) {
					toFSync.add(targetPath.getParent());
				}
			}
		} finally {
			for (Path path : toFSync) {
				tryFsync(path);
			}
		}
	}

	private void copyImpl(Map<String, String> sourceToTargetMap) throws IOException {
		Set<Path> toFSync = new HashSet<>();
		try {
			for (Map.Entry<String, String> entry : sourceToTargetMap.entrySet()) {
				Path path = resolve(entry.getKey());
				if (!Files.isRegularFile(path)) {
					throw new FileNotFoundException("File '" + entry.getKey() + "' not found");
				}
				Path targetPath = resolve(entry.getValue());

				if (path.equals(targetPath)) {
					touch(path, now);
					if (fsyncDirectories) {
						toFSync.add(path);
					}
					continue;
				}

				if (hardLinkOnCopy) {
					try {
						ensureTarget(path, targetPath, () -> copyViaHardlink(path, targetPath, now));
					} catch (IOException e) {
						logger.warn("Could not copy via hard link, trying to copy via temporary directory", e);
						try {
							ensureTarget(path, targetPath, () -> copyViaTempDir(path, targetPath, now, tempDir));
						} catch (IOException e2) {
							e.addSuppressed(e2);
							throw e;
						}
					}
				} else {
					ensureTarget(path, targetPath, () -> copyViaTempDir(path, targetPath, now, tempDir));
				}
				if (fsyncDirectories) {
					toFSync.add(targetPath.getParent());
				}
			}
		} finally {
			for (Path path : toFSync) {
				tryFsync(path);
			}
		}
	}

	private void doMove(Path path, Path targetPath) throws IOException {
		ensureTarget(path, targetPath, () -> LocalFileUtils.moveViaHardlink(path, targetPath, now));
	}

	private <V> V ensureTarget(Path target, IOCallable<V> afterCreation) throws IOException {
		if (tempDir.startsWith(target)) throw new DirectoryNotEmptyException(storage.relativize(target).toString());

		return LocalFileUtils.ensureTarget(null, target, fsyncDirectories, afterCreation);
	}

	private void ensureTarget(@Nullable Path source, Path target, IORunnable afterCreation) throws IOException {
		if (tempDir.startsWith(target)) throw new DirectoryNotEmptyException(storage.relativize(target).toString());

		LocalFileUtils.ensureTarget(source, target, fsyncDirectories, () -> {
			afterCreation.run();
			return null;
		});
	}

	private void checkStarted() {
		checkState(started, "LocalBlockingFileSystem has not been started, call LocalBlockingFileSystems#start first");
	}
}
