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

package io.activej.codegen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Optional;

public abstract class AbstractIOBytecodeStorage implements BytecodeStorage {
	private static final int DEFAULT_BUFFER_SIZE = 8192;

	protected abstract Optional<InputStream> getInputStream(String className) throws IOException;

	protected abstract OutputStream getOutputStream(String className) throws IOException;

	@Override
	public final Optional<byte[]> loadBytecode(String className) throws IOException {
		Optional<InputStream> maybeInputStream = getInputStream(className);
		if (!maybeInputStream.isPresent()) return Optional.empty();

		try (InputStream stream = maybeInputStream.get()) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
			int size;
			while ((size = stream.read(buffer)) != -1) {
				baos.write(buffer, 0, size);
			}
			return Optional.of(baos.toByteArray());
		}
	}

	@Override
	public final void saveBytecode(String className, byte[] bytecode) throws IOException {
		try (OutputStream outputStream = getOutputStream(className)) {
			outputStream.write(bytecode);
		}
	}
}
