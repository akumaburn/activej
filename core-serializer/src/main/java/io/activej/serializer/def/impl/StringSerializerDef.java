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

package io.activej.serializer.def.impl;

import io.activej.codegen.expression.Expression;
import io.activej.codegen.expression.Variable;
import io.activej.common.annotation.ExposedInternals;
import io.activej.serializer.CompatibilityLevel;
import io.activej.serializer.StringFormat;
import io.activej.serializer.def.AbstractSerializerDef;
import io.activej.serializer.def.SerializerDefWithNullable;
import io.activej.serializer.util.BinaryOutputUtils;

import static io.activej.codegen.expression.Expressions.*;
import static io.activej.serializer.CompatibilityLevel.LEVEL_1;
import static io.activej.serializer.StringFormat.ISO_8859_1;
import static io.activej.serializer.StringFormat.UTF8;
import static io.activej.serializer.util.Utils.get;

@ExposedInternals
public final class StringSerializerDef extends AbstractSerializerDef implements SerializerDefWithNullable {
	public final StringFormat format;
	public final boolean nullable;

	@SuppressWarnings("unused") // used via reflection
	public StringSerializerDef() {
		this(UTF8, false);
	}

	public StringSerializerDef(StringFormat format, boolean nullable) {
		this.format = format;
		this.nullable = nullable;
	}

	@Override
	public StringSerializerDef ensureNullable(CompatibilityLevel compatibilityLevel) {
		return new StringSerializerDef(format, true);
	}

	public StringSerializerDef encoding(StringFormat format) {
		return new StringSerializerDef(format, nullable);
	}

	@Override
	public Class<?> getEncodeType() {
		return String.class;
	}

	@Override
	public Expression encode(StaticEncoders staticEncoders, Expression buf, Variable pos, Expression value, int version, CompatibilityLevel compatibilityLevel) {
		return set(pos, get(() -> {
			Expression string = cast(value, String.class);
			if (compatibilityLevel == LEVEL_1 &&
				(format == ISO_8859_1 || format == UTF8)
			) {
				// UTF-MB3
				return nullable ?
					staticCall(BinaryOutputUtils.class, "writeUTF8mb3Nullable", buf, pos, string) :
					staticCall(BinaryOutputUtils.class, "writeUTF8mb3", buf, pos, string);
			}
			return switch (format) {
				case ISO_8859_1 -> nullable ?
					staticCall(BinaryOutputUtils.class, "writeIso88591Nullable", buf, pos, string) :
					staticCall(BinaryOutputUtils.class, "writeIso88591", buf, pos, string);
				case UTF8 -> nullable ?
					staticCall(BinaryOutputUtils.class, "writeUTF8Nullable", buf, pos, string) :
					staticCall(BinaryOutputUtils.class, "writeUTF8", buf, pos, string);
				case UTF16 -> {
					String LE = compatibilityLevel.isLittleEndian() ? "LE" : "";
					yield nullable ?
						staticCall(BinaryOutputUtils.class, "writeUTF16Nullable" + LE, buf, pos, string) :
						staticCall(BinaryOutputUtils.class, "writeUTF16" + LE, buf, pos, string);
				}
				//noinspection deprecation
				case UTF8_MB3 -> nullable ?
					staticCall(BinaryOutputUtils.class, "writeUTF8mb3Nullable", buf, pos, string) :
					staticCall(BinaryOutputUtils.class, "writeUTF8mb3", buf, pos, string);
			};
		}));
	}

	@Override
	public Expression decode(StaticDecoders staticDecoders, Expression in, int version, CompatibilityLevel compatibilityLevel) {
		if (compatibilityLevel == LEVEL_1 && (format == ISO_8859_1 || format == UTF8)) {
			// UTF-MB3
			return nullable ?
				call(in, "readUTF8mb3Nullable") :
				call(in, "readUTF8mb3");
		}
		return switch (format) {
			case ISO_8859_1 -> nullable ?
				call(in, "readIso88591Nullable") :
				call(in, "readIso88591");
			case UTF8 -> nullable ?
				call(in, "readUTF8Nullable") :
				call(in, "readUTF8");
			case UTF16 -> {
				String LE = compatibilityLevel.isLittleEndian() ? "LE" : "";
				yield nullable ?
					call(in, "readUTF16Nullable" + LE) :
					call(in, "readUTF16" + LE);
			}
			case UTF8_MB3 -> nullable ?
				call(in, "readUTF8mb3Nullable") :
				call(in, "readUTF8mb3");
		};
	}
}
