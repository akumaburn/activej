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

package io.activej.codegen.expression;

import io.activej.codegen.Context;
import org.objectweb.asm.Type;

import java.util.List;

import static org.objectweb.asm.Type.getType;

public final class Expression_StaticCall implements Expression {
	private final Class<?> owner;
	private final String name;
	private final List<Expression> arguments;

	public Expression_StaticCall(Class<?> owner, String name, List<Expression> arguments) {
		this.owner = owner;
		this.name = name;
		this.arguments = arguments;
	}

	public Class<?> getOwner() {
		return owner;
	}

	public String getName() {
		return name;
	}

	public List<Expression> getArguments() {
		return arguments;
	}

	@Override
	public Type load(Context ctx) {
		return ctx.invokeStatic(getType(owner), name, arguments);
	}
}
