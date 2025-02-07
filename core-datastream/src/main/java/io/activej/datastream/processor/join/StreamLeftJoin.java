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

package io.activej.datastream.processor.join;

import io.activej.datastream.consumer.AbstractStreamConsumer;
import io.activej.datastream.consumer.StreamConsumer;
import io.activej.datastream.dsl.HasStreamInputs;
import io.activej.datastream.dsl.HasStreamOutput;
import io.activej.datastream.supplier.AbstractStreamSupplier;
import io.activej.datastream.supplier.StreamDataAcceptor;
import io.activej.datastream.supplier.StreamSupplier;
import io.activej.reactor.ImplicitlyReactive;

import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Represents an object which has left and right consumers and one supplier. After receiving data
 * it can join it, or either does a left-inner join or a left-outer join. It works similar to joins from SQL,
 * but it requires primary keys at the 'right' input and foreign keys at the 'left' input.
 * It is a {@link StreamLeftJoin} which receives specified type and streams
 * set of join's result to the destination.
 */
public final class StreamLeftJoin<K, L, R, V> extends ImplicitlyReactive implements HasStreamInputs, HasStreamOutput<V> {
	private final Comparator<K> keyComparator;
	private final Input<L> left;
	private final Input<R> right;
	private final Output output;

	private final ArrayDeque<L> leftDeque = new ArrayDeque<>();
	private final ArrayDeque<R> rightDeque = new ArrayDeque<>();

	private final Function<L, K> leftKeyFunction;
	private final Function<R, K> rightKeyFunction;

	private final LeftJoiner<K, L, R, V> leftJoiner;

	private StreamLeftJoin(
		Comparator<K> keyComparator, Function<L, K> leftKeyFunction, Function<R, K> rightKeyFunction,
		LeftJoiner<K, L, R, V> leftJoiner
	) {
		this.keyComparator = keyComparator;
		this.leftJoiner = leftJoiner;
		this.left = new Input<>(leftDeque);
		this.right = new Input<>(rightDeque);
		this.leftKeyFunction = leftKeyFunction;
		this.rightKeyFunction = rightKeyFunction;
		this.output = new Output();
	}

	/**
	 * Creates a new instance of StreamJoin
	 *
	 * @param keyComparator    comparator for compare keys
	 * @param leftKeyFunction  function for counting keys of left stream
	 * @param rightKeyFunction function for counting keys of right stream
	 * @param leftJoiner       joiner which will join streams
	 */
	public static <K, L, R, V> StreamLeftJoin<K, L, R, V> create(
		Comparator<K> keyComparator, Function<L, K> leftKeyFunction, Function<R, K> rightKeyFunction,
		LeftJoiner<K, L, R, V> leftJoiner
	) {
		return new StreamLeftJoin<>(keyComparator, leftKeyFunction, rightKeyFunction, leftJoiner);
	}

	public final class Input<I> extends AbstractStreamConsumer<I> implements StreamDataAcceptor<I> {
		private final Deque<I> deque;

		public Input(Deque<I> deque) {
			this.deque = deque;
		}

		@Override
		public void accept(I item) {
			boolean wasEmpty = deque.isEmpty();
			deque.addLast(item);
			if (wasEmpty) {
				output.join();
			}
		}

		@Override
		protected void onStarted() {
			output.join();
		}

		@Override
		protected void onEndOfStream() {
			output.join();
			output.getAcknowledgement()
				.whenResult(this::acknowledge)
				.whenException(this::closeEx);
		}

		@Override
		protected void onError(Exception e) {
			output.closeEx(e);
		}
	}

	public final class Output extends AbstractStreamSupplier<V> {

		void join() {
			resume();
		}

		@Override
		protected void onResumed() {
			StreamDataAcceptor<V> acceptor = this::send;
			if (isReady() && !leftDeque.isEmpty() && !rightDeque.isEmpty()) {
				L leftValue = leftDeque.peek();
				K leftKey = leftKeyFunction.apply(leftValue);
				R rightValue = rightDeque.peek();
				K rightKey = rightKeyFunction.apply(rightValue);
				while (true) {
					int compare = keyComparator.compare(leftKey, rightKey);
					if (compare < 0) {
						leftJoiner.onOuterJoin(leftKey, leftValue, acceptor);
						leftDeque.poll();
						if (leftDeque.isEmpty())
							break;
						leftValue = leftDeque.peek();
						leftKey = leftKeyFunction.apply(leftValue);
					} else if (compare > 0) {
						rightDeque.poll();
						if (rightDeque.isEmpty())
							break;
						rightValue = rightDeque.peek();
						rightKey = rightKeyFunction.apply(rightValue);
					} else {
						leftJoiner.onInnerJoin(leftKey, leftValue, rightValue, acceptor);
						leftDeque.poll();
						if (leftDeque.isEmpty())
							break;
						if (!isReady())
							break;
						leftValue = leftDeque.peek();
						leftKey = leftKeyFunction.apply(leftValue);
					}
				}
			}
			if (isReady()) {
				if (left.isEndOfStream() && right.isEndOfStream()) {
					while (!leftDeque.isEmpty()) {
						L leftValue = leftDeque.poll();
						K leftKey = leftKeyFunction.apply(leftValue);
						leftJoiner.onOuterJoin(leftKey, leftValue, acceptor);
					}
					sendEndOfStream();
				} else {
					left.resume(left);
					right.resume(right);
				}
			} else {
				left.suspend();
				right.suspend();
			}
		}

		@Override
		protected void onError(Exception e) {
			left.closeEx(e);
			right.closeEx(e);
		}

		@Override
		protected void onCleanup() {
			leftDeque.clear();
			rightDeque.clear();
		}
	}

	/**
	 * Returns left stream
	 */
	public StreamConsumer<L> getLeft() {
		return left;
	}

	/**
	 * Returns right stream
	 */
	public StreamConsumer<R> getRight() {
		return right;
	}

	@Override
	public List<? extends StreamConsumer<?>> getInputs() {
		return List.of(left, right);
	}

	@Override
	public StreamSupplier<V> getOutput() {
		return output;
	}
}
