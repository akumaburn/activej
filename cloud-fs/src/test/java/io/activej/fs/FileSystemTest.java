package io.activej.fs;

import io.activej.bytebuf.ByteBuf;
import io.activej.bytebuf.ByteBufs;
import io.activej.common.MemSize;
import io.activej.common.exception.TruncatedDataException;
import io.activej.common.exception.UnexpectedDataException;
import io.activej.csp.consumer.ChannelConsumer;
import io.activej.csp.file.ChannelFileReader;
import io.activej.csp.file.ChannelFileWriter;
import io.activej.csp.supplier.ChannelSupplier;
import io.activej.csp.supplier.ChannelSuppliers;
import io.activej.fs.exception.*;
import io.activej.promise.Promises;
import io.activej.reactor.Reactor;
import io.activej.test.ExpectedException;
import io.activej.test.rules.ByteBufRule;
import io.activej.test.rules.EventloopRule;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static io.activej.bytebuf.ByteBufStrings.wrapUtf8;
import static io.activej.common.Utils.last;
import static io.activej.fs.FileSystem.DEFAULT_TEMP_DIR;
import static io.activej.fs.Utils.createEmptyDirectories;
import static io.activej.promise.TestUtils.await;
import static io.activej.promise.TestUtils.awaitException;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.stream.Collectors.toSet;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public final class FileSystemTest {

	@ClassRule
	public static final EventloopRule eventloopRule = new EventloopRule();

	@ClassRule
	public static final ByteBufRule byteBufRule = new ByteBufRule();

	@Rule
	public final TemporaryFolder tmpFolder = new TemporaryFolder();

	private Path storagePath;
	private Path clientPath;

	private FileSystem client;

	@Before
	public void setup() throws IOException {
		storagePath = tmpFolder.newFolder("storage").toPath();
		clientPath = tmpFolder.newFolder("client").toPath();

		Files.createDirectories(storagePath);
		Files.createDirectories(clientPath);

		Path f = clientPath.resolve("f.txt");
		Files.writeString(f, "some text1\n\nmore text1\t\n\n\r", CREATE, TRUNCATE_EXISTING);

		Path c = clientPath.resolve("c.txt");
		Files.writeString(c, "some text2\n\nmore text2\t\n\n\r", CREATE, TRUNCATE_EXISTING);

		Files.createDirectories(storagePath.resolve("1"));
		Files.createDirectories(storagePath.resolve("2/3"));
		Files.createDirectories(storagePath.resolve("2/b"));

		Path a1 = storagePath.resolve("1/a.txt");
		Files.writeString(a1, "1\n2\n3\n4\n5\n6\n", CREATE, TRUNCATE_EXISTING);

		Path b = storagePath.resolve("1/b.txt");
		Files.writeString(b, "7\n8\n9\n10\n11\n12\n", CREATE, TRUNCATE_EXISTING);

		Path a2 = storagePath.resolve("2/3/a.txt");
		Files.writeString(a2, "6\n5\n4\n3\n2\n1\n", CREATE, TRUNCATE_EXISTING);

		Path d = storagePath.resolve("2/b/d.txt");
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < 1_000_000; i++) {
			sb.append(i + "\n");
		}
		Files.writeString(d, sb.toString());

		Path e = storagePath.resolve("2/b/e.txt");
		try {
			Files.createFile(e);
		} catch (IOException ignored) {
		}

		client = FileSystem.create(Reactor.getCurrentReactor(), newCachedThreadPool(), storagePath);
		await(client.start());
	}

	@Test
	public void testDoUpload() throws IOException {
		Path path = clientPath.resolve("c.txt");

		await(client.upload("1/c.txt")
			.then(consumer -> ChannelFileReader.builderOpen(newCachedThreadPool(), path)
				.then(file -> file.withBufferSize(MemSize.of(2))
					.build()
					.streamTo(consumer))));

		assertEquals(-1, Files.mismatch(path, storagePath.resolve("1/c.txt")));
	}

	@Test
	public void testUploadToDirectory() {
		ByteBuf value = wrapUtf8("data");
		Exception exception = awaitException(ChannelSuppliers.ofValue(value).streamTo(client.upload("1")));
		assertThat(exception, instanceOf(IsADirectoryException.class));
	}

	@Test
	public void testAppendToDirectory() {
		ByteBuf value = wrapUtf8("data");
		Exception exception = awaitException(ChannelSuppliers.ofValue(value).streamTo(client.append("1", 0)));
		assertThat(exception, instanceOf(IsADirectoryException.class));
	}

	@Test
	public void testAppendToEmptyDirectory() throws IOException {
		Path empty = last(createEmptyDirectories(storagePath));
		assertTrue(Files.isDirectory(empty));
		try (Stream<Path> list = Files.list(empty)) {
			assertEquals(0, list.count());
		}

		ByteBuf value = wrapUtf8("data");
		await(ChannelSuppliers.ofValue(value).streamTo(client.append(storagePath.relativize(empty).toString(), 0)));

		assertTrue(Files.isRegularFile(empty));
		assertArrayEquals("data".getBytes(), Files.readAllBytes(empty));
	}

	@Test
	public void testAppendOffsetExceedsSize() throws IOException {
		String path = "1/a.txt";
		long size = Files.size(storagePath.resolve(path));
		assertTrue(size > 0);

		ByteBuf value = wrapUtf8("appended");
		Exception exception = awaitException(ChannelSuppliers.ofValue(value)
			.streamTo(client.append(path, size * 2)));

		assertThat(exception, instanceOf(IllegalOffsetException.class));
	}

	@Test
	public void uploadIncompleteFile() {
		String filename = "incomplete.txt";
		Path path = storagePath.resolve(filename);
		assertFalse(Files.exists(path));

		ExpectedException expectedException = new ExpectedException();
		ChannelConsumer<ByteBuf> consumer = await(client.upload(filename));

		Exception exception = awaitException(ChannelSuppliers.concat(
				ChannelSuppliers.ofValues(wrapUtf8("some"), wrapUtf8("test"), wrapUtf8("data")),
				ChannelSuppliers.ofException(expectedException))
			.streamTo(consumer));

		assertSame(expectedException, exception);

		assertFalse(Files.exists(path));
	}

	@Test
	public void uploadLessThanSpecified() {
		String filename = "incomplete.txt";
		Path path = storagePath.resolve(filename);
		assertFalse(Files.exists(path));

		ChannelConsumer<ByteBuf> consumer = await(client.upload(filename, 10));

		ByteBuf value = wrapUtf8("data");
		Exception exception = awaitException(ChannelSuppliers.ofValue(value).streamTo(consumer));

		assertThat(exception, instanceOf(TruncatedDataException.class));

		assertFalse(Files.exists(path));
	}

	@Test
	public void uploadMoreThanSpecified() {
		String filename = "incomplete.txt";
		Path path = storagePath.resolve(filename);
		assertFalse(Files.exists(path));

		ChannelConsumer<ByteBuf> consumer = await(client.upload(filename, 10));

		ByteBuf value = wrapUtf8("data data data data");
		Exception exception = awaitException(ChannelSuppliers.ofValue(value).streamTo(consumer));

		assertThat(exception, instanceOf(UnexpectedDataException.class));

		assertFalse(Files.exists(path));
	}

	@Test
	public void testDownload() throws IOException {
		Path outputFile = clientPath.resolve("d.txt");

		ChannelSupplier<ByteBuf> supplier = await(client.download("2/b/d.txt"));
		await(supplier.streamTo(ChannelFileWriter.open(newCachedThreadPool(), outputFile)));

		assertEquals(-1, Files.mismatch(storagePath.resolve("2/b/d.txt"), outputFile));
	}

	@Test
	public void testDownloadWithOffset() {
		String filename = "filename";
		ByteBuf value = wrapUtf8("abcdefgh");
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload(filename)));

		String result = await(await(client.download(filename, 3, Long.MAX_VALUE))
			.toCollector(ByteBufs.collector())).asString(UTF_8);
		assertEquals("defgh", result);
	}

	@Test
	public void testDownloadWithOffsetExceedingFileSize() {
		String filename = "filename";
		ByteBuf value = wrapUtf8("abcdefgh");
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload(filename)));

		Exception exception = awaitException(client.download(filename, 100, Long.MAX_VALUE));
		assertThat(exception, instanceOf(IllegalOffsetException.class));
	}

	@Test
	public void testDownloadWithLimit() {
		String filename = "filename";
		ByteBuf value = wrapUtf8("abcdefgh");
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload(filename)));

		String result = await(await(client.download(filename, 3, 2))
			.toCollector(ByteBufs.collector())).asString(UTF_8);
		assertEquals("de", result);
	}

	@Test
	public void testDownloadNonExistingFile() {
		Exception e = awaitException(client.download("no_file.txt"));

		assertThat(e, instanceOf(FileNotFoundException.class));
	}

	@Test
	public void testDeleteFile() {
		assertTrue(Files.exists(storagePath.resolve("2/3/a.txt")));

		await(client.delete("2/3/a.txt"));

		assertFalse(Files.exists(storagePath.resolve("2/3/a.txt")));
	}

	@Test
	public void testDeleteNonExistingFile() {
		await(client.delete("no_file.txt"));
	}

	@Test
	public void testListFiles() {
		Set<String> expected = Set.of("1/a.txt", "1/b.txt", "2/3/a.txt", "2/b/d.txt", "2/b/e.txt");

		Map<String, FileMetadata> actual = await(client.list("**"));

		assertEquals(expected, actual.keySet());
	}

	@Test
	public void testGlobListFiles() {
		Set<String> expected = Set.of("2/3/a.txt", "2/b/d.txt", "2/b/e.txt");

		Map<String, FileMetadata> actual = await(client.list("2/*/*.txt"));

		assertEquals(expected, actual.keySet());
	}

	@Test
	public void testMove() throws IOException {
		byte[] expected = Files.readAllBytes(storagePath.resolve("1/a.txt"));
		await(client.move("1/a.txt", "3/new_folder/z.txt"));

		assertArrayEquals(expected, Files.readAllBytes(storagePath.resolve("3/new_folder/z.txt")));
		assertFalse(Files.exists(storagePath.resolve("1/a.txt")));
	}

	@Test
	public void testMoveIntoExisting() throws IOException {
		byte[] expected = Files.readAllBytes(storagePath.resolve("1/b.txt"));
		await(client.move("1/b.txt", "1/a.txt"));

		assertArrayEquals(expected, Files.readAllBytes(storagePath.resolve("1/a.txt")));
		assertFalse(Files.exists(storagePath.resolve("1/b.txt")));
	}

	@Test
	public void testMoveNothingIntoNothing() {
		Exception exception = awaitException(client.move("i_do_not_exist.txt", "neither_am_i.txt"));

		assertThat(exception, instanceOf(FileNotFoundException.class));
	}

	@Test
	public void testOverwritingDirAsFile() {
		ByteBuf value1 = wrapUtf8("test");
		await(ChannelSuppliers.ofValue(value1).streamTo(client.upload("newdir/a.txt")));
		await(client.delete("newdir/a.txt"));

		assertTrue(await(client.list("**")).keySet().stream().noneMatch(name -> name.contains("newdir")));
		ByteBuf value = wrapUtf8("test");
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload("newdir")));
		assertNotNull(await(client.info("newdir")));
	}

	@Test
	public void testDeleteEmpty() {
		await(client.delete(""));
	}

	@Test
	public void testListMalformedGlob() {
		Exception exception = awaitException(client.list("["));
		assertThat(exception, instanceOf(MalformedGlobException.class));
	}

	@Test
	public void tempFilesAreNotListed() throws IOException {
		Map<String, FileMetadata> before = await(client.list("**"));

		Path tempDir = storagePath.resolve(DEFAULT_TEMP_DIR);
		Files.write(tempDir.resolve("systemFile.txt"), "test data".getBytes());
		Path folder = tempDir.resolve("folder");
		Files.createDirectories(folder);
		Files.write(folder.resolve("systemFile2.txt"), "test data".getBytes());

		Map<String, FileMetadata> after = await(client.list("**"));

		assertEquals(before, after);
	}

	@Test
	public void copyCreatesNewFile() {
		ByteBuf value2 = wrapUtf8("test");
		await(ChannelSuppliers.ofValue(value2).streamTo(client.upload("first")));
		await(client.copy("first", "second"));

		ByteBuf value1 = wrapUtf8("first");
		await(ChannelSuppliers.ofValue(value1).streamTo(client.append("first", 4)));

		assertEquals("testfirst", await(await(client.download("first")).toCollector(ByteBufs.collector())).asString(UTF_8));
		assertEquals("test", await(await(client.download("second")).toCollector(ByteBufs.collector())).asString(UTF_8));

		ByteBuf value = wrapUtf8("second");
		await(ChannelSuppliers.ofValue(value).streamTo(client.append("second", 4)));

		assertEquals("testfirst", await(await(client.download("first")).toCollector(ByteBufs.collector())).asString(UTF_8));
		assertEquals("testsecond", await(await(client.download("second")).toCollector(ByteBufs.collector())).asString(UTF_8));
	}

	@Test
	public void copyWithHardLinksDoesNotCreateNewFile() {
		client = FileSystem.builder(client.getReactor(), newCachedThreadPool(), storagePath)
			.withHardLinkOnCopy(true)
			.build();
		await(client.start());

		ByteBuf value2 = wrapUtf8("test");
		await(ChannelSuppliers.ofValue(value2).streamTo(client.upload("first")));
		await(client.copy("first", "second"));

		ByteBuf value1 = wrapUtf8("first");
		await(ChannelSuppliers.ofValue(value1).streamTo(client.append("first", 4)));

		assertEquals("testfirst", await(await(client.download("first")).toCollector(ByteBufs.collector())).asString(UTF_8));
		assertEquals("testfirst", await(await(client.download("second")).toCollector(ByteBufs.collector())).asString(UTF_8));

		ByteBuf value = wrapUtf8("second");
		await(ChannelSuppliers.ofValue(value).streamTo(client.append("second", 9)));

		assertEquals("testfirstsecond", await(await(client.download("first")).toCollector(ByteBufs.collector())).asString(UTF_8));
		assertEquals("testfirstsecond", await(await(client.download("second")).toCollector(ByteBufs.collector())).asString(UTF_8));
	}

	@Test
	public void testAppendInTheMiddle() {
		String filename = "test";

		// Creating file
		ByteBuf value1 = wrapUtf8("data");
		await(ChannelSuppliers.ofValue(value1).streamTo(client.upload(filename)));
		ByteBuf value = wrapUtf8("d");
		await(ChannelSuppliers.ofValue(value).streamTo(client.append(filename, 2)));

		String result = await(await(client.download(filename)).toCollector(ByteBufs.collector())).asString(UTF_8);
		assertEquals("dada", result);
	}

	@Test
	public void testConcurrentAppends() {
		String filename = "test";

		// Creating file
		await(client.upload(filename).then(ChannelConsumer::acceptEndOfStream));

		ChannelConsumer<ByteBuf> firstAppender = await(client.append(filename, 0));
		ChannelConsumer<ByteBuf> secondAppender = await(client.append(filename, 0));

		for (int i = 0; i < 100; i++) {
			await(firstAppender.accept(wrapUtf8("first\n")));
			await(secondAppender.accept(wrapUtf8("second\n")));
		}

		String fileContents = await(client.download(filename)
			.then(supplier -> supplier.toCollector(ByteBufs.collector()))).asString(UTF_8);

		assertTrue(fileContents.contains("first"));
		assertTrue(fileContents.contains("second"));
	}

	@Test
	public void testEmptyDirectoryCleanupOnUpload() {
		List<Path> emptyDirs = createEmptyDirectories(storagePath);
		String data = "test";
		ByteBuf value = wrapUtf8(data);
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload("empty")));

		String result = await(client.download("empty").then(supplier -> supplier.toCollector(ByteBufs.collector()))).asString(UTF_8);
		assertEquals(data, result);
		for (Path emptyDir : emptyDirs) {
			assertFalse(Files.isDirectory(emptyDir));
		}
	}

	@Test
	public void testEmptyDirectoryCleanupOnAppend() {
		List<Path> emptyDirs = createEmptyDirectories(storagePath);
		String data = "test";
		ByteBuf value = wrapUtf8(data);
		await(ChannelSuppliers.ofValue(value).streamTo(client.append("empty", 0)));

		String result = await(client.download("empty").then(supplier -> supplier.toCollector(ByteBufs.collector()))).asString(UTF_8);
		assertEquals(data, result);
		for (Path emptyDir : emptyDirs) {
			assertFalse(Files.isDirectory(emptyDir));
		}
	}

	@Test
	public void testEmptyDirectoryCleanupOnMove() {
		List<Path> emptyDirs = createEmptyDirectories(storagePath);
		String data = "test";
		ByteBuf value = wrapUtf8(data);
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload("source")));
		await(client.move("source", "empty"));

		String result = await(client.download("empty").then(supplier -> supplier.toCollector(ByteBufs.collector()))).asString(UTF_8);
		assertEquals(data, result);
		for (Path emptyDir : emptyDirs) {
			assertFalse(Files.isDirectory(emptyDir));
		}
	}

	@Test
	public void testEmptyDirectoryCleanupOnCopy() {
		List<Path> emptyDirs = createEmptyDirectories(storagePath);
		String data = "test";
		ByteBuf value = wrapUtf8(data);
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload("source")));
		await(client.copy("source", "empty"));

		String result = await(client.download("empty").then(supplier -> supplier.toCollector(ByteBufs.collector()))).asString(UTF_8);
		assertEquals(data, result);
		for (Path emptyDir : emptyDirs) {
			assertFalse(Files.isDirectory(emptyDir));
		}
	}

	@Test
	public void testEmptyDirectoryCleanupWithOneFile() throws IOException {
		List<Path> emptyDirs = createEmptyDirectories(storagePath);
		Path randomPath = emptyDirs.get(ThreadLocalRandom.current().nextInt(emptyDirs.size()));
		Files.createFile(randomPath.resolve("file"));
		String data = "test";
		ByteBuf value = wrapUtf8(data);
		Exception exception = awaitException(ChannelSuppliers.ofValue(value).streamTo(client.upload("empty")));
		assertThat(exception, instanceOf(IsADirectoryException.class));
	}

	@Test
	public void testUploadToSameNewDir() {
		String dir = "newDir";
		Set<String> filenames = IntStream.range(0, 5)
			.mapToObj(i -> dir + IFileSystem.SEPARATOR + i + ".txt")
			.collect(toSet());

		await(Promises.all(filenames.stream()
			.map(filename -> client.upload(filename)
				.then(ChannelConsumer::acceptEndOfStream))));

		Map<String, FileMetadata> files = await(client.list(dir + IFileSystem.SEPARATOR + '*'));
		assertEquals(filenames, files.keySet());
		for (FileMetadata meta : files.values()) {
			assertEquals(0, meta.getSize());
		}
	}

	@Test
	public void testCopyWithDeletedTempDir() throws IOException {
		ByteBuf value = wrapUtf8("Test content");
		await(ChannelSuppliers.ofValue(value).streamTo(client.upload("test.txt")));

		Path tempDir = storagePath.resolve(DEFAULT_TEMP_DIR);
		Files.delete(tempDir);

		Exception e = awaitException(client.copy("test.txt", "test.txt.copy"));

		assertThat(e, instanceOf(FileSystemIOException.class));
		assertEquals(e.getMessage(), "Temporary directory " + tempDir + " not found");
	}

	@Test
	public void testUploadWithDeletedTempDir() throws IOException {
		Path tempDir = storagePath.resolve(DEFAULT_TEMP_DIR);
		Files.delete(tempDir);

		ByteBuf value = wrapUtf8("Test content");
		Exception e = awaitException(ChannelSuppliers.ofValue(value)
			.streamTo(client.upload("test.txt")));

		assertThat(e, instanceOf(FileSystemIOException.class));
		assertEquals(e.getMessage(), "Temporary directory " + tempDir + " not found");
	}

	@Test
	public void testRelativePaths() {
		Path current = Paths.get(".").toAbsolutePath();
		assumeTrue("This test is located on a different drive than temporary directory", current.getRoot().equals(storagePath.getRoot()));

		Set<String> expected = Set.of(
			"1/a.txt",
			"1/b.txt",
			"2/3/a.txt",
			"2/b/d.txt",
			"2/b/e.txt"
		);

		Path relativePath = current.relativize(storagePath);
		relativePath = relativePath.getParent().resolve(".").resolve(relativePath.getFileName());

		assertFalse(relativePath.isAbsolute());

		client = FileSystem.create(Reactor.getCurrentReactor(), newCachedThreadPool(), relativePath);
		await(client.start());

		Map<String, FileMetadata> actual = await(client.list("**"));

		assertEquals(expected, actual.keySet());
	}
}
