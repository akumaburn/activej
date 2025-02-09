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

package io.activej.aggregation.predicate.impl;

import io.activej.aggregation.fieldtype.FieldType;
import io.activej.aggregation.predicate.AggregationPredicate;
import io.activej.aggregation.predicate.AggregationPredicates;
import io.activej.aggregation.predicate.AggregationPredicates.PredicateSimplifier;
import io.activej.aggregation.predicate.AggregationPredicates.PredicateSimplifierKey;
import io.activej.codegen.expression.Expression;
import io.activej.codegen.expression.Expressions;
import io.activej.common.annotation.ExposedInternals;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Function;

import static io.activej.aggregation.predicate.AggregationPredicates.alwaysTrue;
import static io.activej.aggregation.predicate.AggregationPredicates.and;
import static io.activej.common.Utils.first;

@ExposedInternals
public final class And implements AggregationPredicate {
	private static final class E extends Expressions {}

	public final List<AggregationPredicate> predicates;

	public And(List<AggregationPredicate> predicates) {
		this.predicates = predicates;
	}

	@Override
	public AggregationPredicate simplify() {
		Set<AggregationPredicate> simplifiedPredicates = new LinkedHashSet<>();
		for (AggregationPredicate predicate : predicates) {
			AggregationPredicate simplified = predicate.simplify();
			if (simplified instanceof And and) {
				simplifiedPredicates.addAll(and.predicates);
			} else {
				simplifiedPredicates.add(simplified);
			}
		}
		boolean simplified;
		do {
			simplified = false;
			Set<AggregationPredicate> newPredicates = new HashSet<>();
			L:
			for (AggregationPredicate newPredicate : simplifiedPredicates) {
				for (AggregationPredicate simplifiedPredicate : newPredicates) {
					AggregationPredicate maybeSimplified = simplifyAnd(newPredicate, simplifiedPredicate);
					if (maybeSimplified != null) {
						newPredicates.remove(simplifiedPredicate);
						newPredicates.add(maybeSimplified);
						simplified = true;
						continue L;
					}
				}
				newPredicates.add(newPredicate);
			}
			simplifiedPredicates = newPredicates;
		} while (simplified);

		return simplifiedPredicates.isEmpty() ?
			alwaysTrue() :
			simplifiedPredicates.size() == 1 ?
				first(simplifiedPredicates) :
				and(new ArrayList<>(simplifiedPredicates));
	}

	@SuppressWarnings("unchecked")
	private static @Nullable AggregationPredicate simplifyAnd(AggregationPredicate left, AggregationPredicate right) {
		if (left.equals(right))
			return left;
		PredicateSimplifierKey<?, ?> key = new PredicateSimplifierKey<>(left.getClass(), right.getClass());
		PredicateSimplifier<AggregationPredicate, AggregationPredicate> simplifier = (PredicateSimplifier<AggregationPredicate, AggregationPredicate>) AggregationPredicates.simplifiers.get(key);
		if (simplifier == null)
			return null;
		return simplifier.simplifyAnd(left, right);
	}

	@Override
	public Set<String> getDimensions() {
		Set<String> result = new HashSet<>();
		for (AggregationPredicate predicate : predicates) {
			result.addAll(predicate.getDimensions());
		}
		return result;
	}

	@Override
	public Map<String, Object> getFullySpecifiedDimensions() {
		Map<String, Object> result = new HashMap<>();
		for (AggregationPredicate predicate : predicates) {
			result.putAll(predicate.getFullySpecifiedDimensions());
		}
		return result;
	}

	@SuppressWarnings("rawtypes")
	@Override
	public Expression createPredicate(
		Expression record, Map<String, FieldType> fields, Function<String, AggregationPredicate> predicateFactory
	) {
		List<Expression> predicateDefs = new ArrayList<>();
		for (AggregationPredicate predicate : predicates) {
			predicateDefs.add(predicate.createPredicate(record, fields, predicateFactory));
		}
		return E.and(predicateDefs);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;

		And that = (And) o;

		return new HashSet<>(predicates).equals(new HashSet<>(that.predicates));

	}

	@Override
	public int hashCode() {
		return new HashSet<>(predicates).hashCode();
	}

	@Override
	public String toString() {
		StringJoiner joiner = new StringJoiner(" AND ");
		for (AggregationPredicate predicate : predicates)
			joiner.add(predicate != null ? predicate.toString() : null);

		return "(" + joiner + ")";
	}
}
