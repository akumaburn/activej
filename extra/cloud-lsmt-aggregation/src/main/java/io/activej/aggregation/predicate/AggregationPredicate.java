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

package io.activej.aggregation.predicate;

import io.activej.aggregation.fieldtype.FieldType;
import io.activej.codegen.expression.Expression;

import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public interface AggregationPredicate {

	AggregationPredicate simplify();

	Set<String> getDimensions();

	Map<String, Object> getFullySpecifiedDimensions();

	Expression createPredicate(
		Expression record, @SuppressWarnings("rawtypes") Map<String, FieldType> fields,
		Function<String, AggregationPredicate> predicateFactory);

	@Override
	boolean equals(Object o);

	@Override
	int hashCode();
}
