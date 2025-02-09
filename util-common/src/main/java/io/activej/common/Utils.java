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

package io.activej.common;

import org.jetbrains.annotations.ApiStatus.Internal;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static java.lang.Math.max;
import static java.util.function.Function.identity;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toSet;

/**
 * Utility helper methods for internal use
 */
@Internal
public class Utils {
	public static final int TO_STRING_LIMIT = ApplicationSettings.getInt(Utils.class, "toStringLimit", 10);

	private static final BinaryOperator<?> NO_MERGE_FUNCTION = (v, v2) -> {
		throw new AssertionError();
	};

	private static final Iterator<Object> EMPTY_ITERATOR = new Iterator<>() {
		@Override
		public boolean hasNext() {
			return false;
		}

		@Override
		public Object next() {
			throw new NoSuchElementException();
		}
	};

	public static <T> T get(Supplier<T> supplier) {
		return supplier.get();
	}

	public static <T> T nonNullElse(@Nullable T value1, T defaultValue) {
		return value1 != null ? value1 : defaultValue;
	}

	public static <T> T nonNullElseGet(@Nullable T value, Supplier<? extends T> defaultValue) {
		return value != null ? value : defaultValue.get();
	}

	public static String nonNullElseEmpty(@Nullable String value) {
		return nonNullElse(value, "");
	}

	public static <T> Set<T> nonNullElseEmpty(@Nullable Set<T> set) {
		return nonNullElse(set, Set.of());
	}

	public static <T> List<T> nonNullElseEmpty(@Nullable List<T> list) {
		return nonNullElse(list, List.of());
	}

	public static <K, V> Map<K, V> nonNullElseEmpty(@Nullable Map<K, V> map) {
		return nonNullElse(map, Map.of());
	}

	public static <T, E extends Throwable> T nonNullOrException(@Nullable T value, Supplier<E> exceptionSupplier) throws E {
		if (value != null) {
			return value;
		}
		throw exceptionSupplier.get();
	}

	@SuppressWarnings("unchecked")
	public static <T> Predicate<T> not(Predicate<? super T> target) {
		return (Predicate<T>) target.negate();
	}

	@Contract("_, _ -> null")
	public static <V> @Nullable V nullify(@Nullable V value, Consumer<? super V> action) {
		if (value != null) {
			action.accept(value);
		}
		return null;
	}

	@Contract("_, _, _ -> null")
	public static <V, A> @Nullable V nullify(@Nullable V value, BiConsumer<? super V, A> action, A actionArg) {
		if (value != null) {
			action.accept(value, actionArg);
		}
		return null;
	}

	public static <V> @Nullable V replace(@Nullable V value, @Nullable V newValue, Consumer<? super V> action) {
		if (value != null && value != newValue) {
			action.accept(value);
		}
		return newValue;
	}

	public static <V, A> @Nullable V replace(@Nullable V value, @Nullable V newValue, BiConsumer<? super V, A> action, A actionArg) {
		if (value != null && value != newValue) {
			action.accept(value, actionArg);
		}
		return newValue;
	}

	public static boolean arraysEquals(
		byte[] array1, int pos1, int len1,
		byte[] array2, int pos2, int len2
	) {
		if (len1 != len2) return false;
		for (int i = 0; i < len1; i++) {
			if (array1[pos1 + i] != array2[pos2 + i]) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Concatenates two lists into one
	 * <p>
	 * No guarantee on a mutability of a resulting list is made,
	 * so list should be considered unmodifiable
	 * <p>
	 * If any of concatenated lists is modified, the behaviour of a concatenated list is undefined
	 */
	@SuppressWarnings("unchecked")
	public static <D> List<D> concat(List<? extends D> list1, List<? extends D> list2) {
		if (list1.isEmpty()) return (List<D>) list2;
		if (list2.isEmpty()) return (List<D>) list1;
		Object[] objects = new Object[list1.size() + list2.size()];
		System.arraycopy(list1.toArray(), 0, objects, 0, list1.size());
		System.arraycopy(list2.toArray(), 0, objects, list1.size(), list2.size());
		return (List<D>) List.of(objects);
	}

	/**
	 * Returns a difference of two sets
	 * <p>
	 * No guarantee on a mutability of a resulting set is made,
	 * so set should be considered unmodifiable
	 * <p>
	 * If any of provided sets is modified, the behaviour of a resulting set is undefined
	 */
	@SuppressWarnings("unchecked")
	public static <T> Set<T> difference(Set<? extends T> a, Set<? extends T> b) {
		if (b.isEmpty()) return (Set<T>) a;
		return a.stream().filter(not(b::contains)).collect(toSet());
	}

	/**
	 * Returns an intersection of two sets
	 * <p>
	 * No guarantee on a mutability of a resulting set is made,
	 * so set should be considered unmodifiable
	 * <p>
	 * If any of provided sets is modified, the behaviour of a resulting set is undefined
	 */
	public static <T> Set<T> intersection(Set<? extends T> a, Set<? extends T> b) {
		return a.size() < b.size() ?
			a.stream().filter(b::contains).collect(toSet()) :
			b.stream().filter(a::contains).collect(toSet());
	}

	public static <T> boolean hasIntersection(Set<? extends T> a, Set<? extends T> b) {
		return a.size() < b.size() ?
			a.stream().anyMatch(b::contains) :
			b.stream().anyMatch(a::contains);
	}

	/**
	 * Returns a union of two sets
	 * <p>
	 * No guarantee on a mutability of a resulting set is made,
	 * so set should be considered unmodifiable
	 * <p>
	 * If any of provided sets is modified, the behaviour of a resulting set is undefined
	 */
	@SuppressWarnings("unchecked")
	public static <T> Set<T> union(Set<? extends T> a, Set<? extends T> b) {
		if (a.isEmpty()) return (Set<T>) b;
		if (b.isEmpty()) return (Set<T>) a;
		Set<T> result = new HashSet<>(max(a.size(), b.size()));
		result.addAll(a);
		result.addAll(b);
		return result;
	}

	public static <T> T first(List<? extends T> list) {
		return list.get(0);
	}

	public static <T> T first(Iterable<? extends T> iterable) {
		return iterable.iterator().next();
	}

	public static <T> T last(List<? extends T> list) {
		return list.get(list.size() - 1);
	}

	public static <T> T last(Iterable<? extends T> iterable) {
		Iterator<? extends T> iterator = iterable.iterator();
		while (true) {
			T value = iterator.next();
			if (!iterator.hasNext()) return value;
		}
	}

	@SuppressWarnings("unchecked")
	public static <T> Iterator<T> iteratorOf() {
		return (Iterator<T>) EMPTY_ITERATOR;
	}

	public static <T> Iterator<T> iteratorOf(T item) {
		return new Iterator<>() {
			boolean hasNext = true;

			@Override
			public boolean hasNext() {
				return hasNext;
			}

			@Override
			public T next() {
				if (!hasNext()) throw new NoSuchElementException();
				hasNext = false;
				return item;
			}
		};
	}

	public static <T> Iterator<T> iteratorOf(T item1, T item2) {
		return new Iterator<>() {
			int i = 0;

			@Override
			public boolean hasNext() {
				return i < 2;
			}

			@Override
			public T next() {
				if (!hasNext()) throw new NoSuchElementException();
				return i++ == 0 ? item1 : item2;
			}
		};
	}

	@SafeVarargs
	public static <T> Iterator<T> iteratorOf(T... items) {
		return new Iterator<>() {
			int i = 0;

			@Override
			public boolean hasNext() {
				return i < items.length;
			}

			@Override
			public T next() {
				if (!hasNext()) throw new NoSuchElementException();
				return items[i++];
			}
		};
	}

	public static <T, R> Iterator<R> transformIterator(Iterator<? extends T> iterator, Function<? super T, ? extends R> fn) {
		return new Iterator<>() {
			@Override
			public boolean hasNext() {
				return iterator.hasNext();
			}

			@Override
			public R next() {
				return fn.apply(iterator.next());
			}
		};
	}

	public static <T> Iterator<T> concat(Iterator<? extends T> iterator1, Iterator<? extends T> iterator2) {
		return concatImpl(iteratorOf(iterator1, iterator2));
	}

	public static <T> Iterator<T> append(Iterator<? extends T> iterator, T value) {
		return concatImpl(iteratorOf(iterator, iteratorOf(value)));
	}

	public static <T> Iterator<T> prepend(T value, Iterator<? extends T> iterator) {
		return concatImpl(iteratorOf(iteratorOf(value), iterator));
	}

	private static <T> Iterator<T> concatImpl(Iterator<? extends Iterator<? extends T>> iterators) {
		return new Iterator<>() {
			@Nullable Iterator<? extends T> it = iterators.hasNext() ? iterators.next() : null;

			@Override
			public boolean hasNext() {
				return it != null;
			}

			@Override
			public T next() {
				if (it == null) throw new NoSuchElementException();
				T next = it.next();
				if (!it.hasNext()) {
					it = iterators.hasNext() ? iterators.next() : null;
				}
				return next;
			}
		};
	}

	public static boolean isBijection(Map<?, ?> map) {
		return new HashSet<>(map.values()).size() == map.size();
	}

	public static <T> Stream<T> iterate(Supplier<? extends T> supplier, Predicate<? super T> hasNext) {
		return iterate(supplier.get(), hasNext, $ -> supplier.get());
	}

	public static <T> Stream<T> iterate(T seed, Predicate<? super T> hasNext, UnaryOperator<T> f) {
		return StreamSupport.stream(Spliterators.spliteratorUnknownSize(
			new Iterator<>() {
				T item = seed;

				@Override
				public boolean hasNext() {
					return hasNext.test(item);
				}

				@Override
				public T next() {
					T next = item;
					item = f.apply(item);
					return next;
				}
			},
			Spliterator.ORDERED | Spliterator.IMMUTABLE), false);
	}

	public static <T> BinaryOperator<T> noMergeFunction() {
		//noinspection unchecked
		return (BinaryOperator<T>) NO_MERGE_FUNCTION;
	}

	public static <K, V, M extends Map<K, V>>
	Collector<Map.Entry<? extends K, ? extends V>, ?, M> entriesToMap(Supplier<M> mapFactory) {

		return toMap(Map.Entry::getKey, Map.Entry::getValue, mapFactory);
	}

	public static <K, V>
	Collector<Map.Entry<? extends K, ? extends V>, ?, LinkedHashMap<K, V>> entriesToLinkedHashMap() {
		return toMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new);
	}

	public static <K, V>
	Collector<Map.Entry<? extends K, ? extends V>, ?, HashMap<K, V>> entriesToHashMap() {
		return toMap(Map.Entry::getKey, Map.Entry::getValue, HashMap::new);
	}

	public static <K, V, K1, V1, M extends Map<K1, V1>>
	Collector<Map.Entry<? extends K, ? extends V>, ?, M> entriesToMap(
		Function<? super K, ? extends K1> keyMapper,
		Function<? super V, ? extends V1> valueMapper,
		Supplier<M> mapFactory
	) {
		return toMap(entry -> keyMapper.apply(entry.getKey()), entry -> valueMapper.apply(entry.getValue()), mapFactory);
	}

	public static <K, V, K1, V1>
	Collector<Map.Entry<? extends K, ? extends V>, ?, LinkedHashMap<K1, V1>> entriesToLinkedHashMap(
		Function<? super K, ? extends K1> keyMapper,
		Function<? super V, ? extends V1> valueMapper
	) {
		return entriesToMap(keyMapper, valueMapper, LinkedHashMap::new);
	}

	public static <K, V, K1, V1>
	Collector<Map.Entry<? extends K, ? extends V>, ?, HashMap<K1, V1>> entriesToHashMap(
		Function<? super K, ? extends K1> keyMapper,
		Function<? super V, ? extends V1> valueMapper
	) {
		return entriesToMap(keyMapper, valueMapper, HashMap::new);
	}

	public static <K, V, V1, M extends Map<K, V1>>
	Collector<Map.Entry<? extends K, ? extends V>, ?, M> entriesToMap(
		Function<? super V, ? extends V1> valueMapper,
		Supplier<M> mapFactory
	) {
		return toMap(Map.Entry::getKey, entry -> valueMapper.apply(entry.getValue()), mapFactory);
	}

	public static <K, V, V1>
	Collector<Map.Entry<? extends K, ? extends V>, ?, LinkedHashMap<K, V1>> entriesToLinkedHashMap(
		Function<? super V, ? extends V1> valueMapper
	) {
		return entriesToMap(valueMapper, LinkedHashMap::new);
	}

	public static <K, V, V1>
	Collector<Map.Entry<? extends K, ? extends V>, ?, HashMap<K, V1>> entriesToHashMap(
		Function<? super V, ? extends V1> valueMapper
	) {
		return entriesToMap(valueMapper, HashMap::new);
	}

	public static <T, K, V, M extends Map<K, V>>
	Collector<T, ?, M> toMap(
		Function<? super T, ? extends K> keyMapper,
		Function<? super T, ? extends V> valueMapper,
		Supplier<M> mapFactory
	) {
		return Collectors.toMap(keyMapper, valueMapper, noMergeFunction(), mapFactory);
	}

	public static <T, K, V>
	Collector<T, ?, LinkedHashMap<K, V>> toLinkedHashMap(
		Function<? super T, ? extends K> keyMapper,
		Function<? super T, ? extends V> valueMapper
	) {
		return toMap(keyMapper, valueMapper, LinkedHashMap::new);
	}

	public static <T, K, V>
	Collector<T, ?, HashMap<K, V>> toHashMap(
		Function<? super T, ? extends K> keyMapper,
		Function<? super T, ? extends V> valueMapper
	) {
		return toMap(keyMapper, valueMapper, HashMap::new);
	}

	public static <K, V, M extends Map<K, V>>
	Collector<K, ?, M> toMap(
		Function<? super K, ? extends V> valueMapper,
		Supplier<M> mapFactory
	) {
		return toMap(identity(), valueMapper, mapFactory);
	}

	public static <K, V>
	Collector<K, ?, LinkedHashMap<K, V>> toLinkedHashMap(
		Function<? super K, ? extends V> valueMapper
	) {
		return toLinkedHashMap(identity(), valueMapper);
	}

	public static <K, V>
	Collector<K, ?, HashMap<K, V>> toHashMap(
		Function<? super K, ? extends V> valueMapper
	) {
		return toHashMap(identity(), valueMapper);
	}

	public static <T> String toString(Collection<? extends T> collection) {
		return toString(collection, TO_STRING_LIMIT);
	}

	public static <K, V> String toString(Map<? extends K, ? extends V> map) {
		return toString(map, TO_STRING_LIMIT);
	}

	public static <T> String toString(Collection<? extends T> collection, int limit) {
		return collection.stream()
			.limit(limit)
			.map(Object::toString)
			.collect(joining(",", "[", collection.size() <= limit ? "]" : ", ..and " + (collection.size() - limit) + " more]"));
	}

	public static <K, V> String toString(Map<? extends K, ? extends V> map, int limit) {
		return map.entrySet().stream()
			.limit(limit)
			.map(element -> element.getKey() + "=" + element.getValue())
			.collect(joining(",", "{",
				map.size() <= limit ?
					"}" :
					", ..and " + (map.size() - limit) + " more}"));
	}
}
