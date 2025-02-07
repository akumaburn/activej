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
import io.activej.common.annotation.ExposedInternals;
import io.activej.serializer.CompatibilityLevel;
import io.activej.serializer.def.SerializerDef;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import static io.activej.codegen.expression.Expressions.*;
import static io.activej.serializer.util.Utils.hashInitialSize;

@ExposedInternals
public final class SetSerializerDef extends RegularCollectionSerializerDef {
	public SetSerializerDef(SerializerDef valueSerializer, boolean nullable) {
		super(valueSerializer, Set.class, Set.class, Object.class, nullable);
	}

	@Override
	protected SerializerDef doEnsureNullable(CompatibilityLevel compatibilityLevel) {
		return new SetSerializerDef(valueSerializer, true);
	}

	@Override
	protected Expression doDecode(StaticDecoders staticDecoders, Expression in, int version, CompatibilityLevel compatibilityLevel, Expression length) {
		Decoder decoder = valueSerializer.defineDecoder(staticDecoders, version, compatibilityLevel);
		return ifEq(length, value(0),
			staticCall(Collections.class, "emptySet"),
			ifEq(length, value(1),
				staticCall(Collections.class, "singleton", decoder.decode(in)),
				super.doDecode(staticDecoders, in, version, compatibilityLevel, length)));
	}

	@Override
	protected Expression createBuilder(Expression length) {
		if (valueSerializer.getDecodeType().isEnum()) {
			return staticCall(EnumSet.class, "noneOf", value(valueSerializer.getEncodeType()));
		}
		return constructor(HashSet.class, hashInitialSize(length));
	}
}
