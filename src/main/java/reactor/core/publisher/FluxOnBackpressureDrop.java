/*
 * Copyright (c) 2011-2017 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core.publisher;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.Consumer;

import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/**
 * Drops values if the subscriber doesn't request fast enough.
 *
 * @param <T> the value type
 * @see <a href="https://github.com/reactor/reactive-streams-commons">Reactive-Streams-Commons</a>
 */
final class FluxOnBackpressureDrop<T> extends FluxSource<T, T> {

	static final Consumer<Object> NOOP = t -> {

	};

	final Consumer<? super T> onDrop;

	FluxOnBackpressureDrop(Flux<? extends T> source) {
		super(source);
		this.onDrop = NOOP;
	}

	FluxOnBackpressureDrop(Flux<? extends T> source,
			Consumer<? super T> onDrop) {
		super(source);
		this.onDrop = Objects.requireNonNull(onDrop, "onDrop");
	}

	@Override
	public long getPrefetch() {
		return Integer.MAX_VALUE;
	}

	@Override
	public void subscribe(Subscriber<? super T> s) {
		source.subscribe(new DropSubscriber<>(s, onDrop));
	}

	static final class DropSubscriber<T>
			implements InnerOperator<T, T> {

		final Subscriber<? super T> actual;
		final Consumer<? super T>   onDrop;

		Subscription s;

		volatile long requested;
		@SuppressWarnings("rawtypes")
		static final AtomicLongFieldUpdater<DropSubscriber> REQUESTED =
				AtomicLongFieldUpdater.newUpdater(DropSubscriber.class, "requested");

		boolean done;

		DropSubscriber(Subscriber<? super T> actual, Consumer<? super T> onDrop) {
			this.actual = actual;
			this.onDrop = onDrop;
		}

		@Override
		public void request(long n) {
			if (Operators.validate(n)) {
				Operators.getAndAddCap(REQUESTED, this, n);
			}
		}

		@Override
		public void cancel() {
			s.cancel();
		}

		@Override
		public void onSubscribe(Subscription s) {
			if (Operators.validate(this.s, s)) {
				this.s = s;

				actual.onSubscribe(this);

				s.request(Long.MAX_VALUE);
			}
		}

		@Override
		public void onNext(T t) {

			if (done) {
				try {
					onDrop.accept(t);
				}
				catch (Throwable e) {
					Operators.onErrorDropped(e);
				}
				return;
			}

			long r = requested;
			if (r != 0L) {
				actual.onNext(t);
				if (r != Long.MAX_VALUE) {
					REQUESTED.decrementAndGet(this);
				}

			}
			else {
				try {
					onDrop.accept(t);
				}
				catch (Throwable e) {
					onError(Operators.onOperatorError(s, e, t));
				}
			}
		}

		@Override
		public void onError(Throwable t) {
			if (done) {
				Operators.onErrorDropped(t);
				return;
			}
			done = true;

			actual.onError(t);
		}

		@Override
		public void onComplete() {
			if (done) {
				return;
			}
			done = true;

			actual.onComplete();
		}

		@Override
		public Subscriber<? super T> actual() {
			return actual;
		}

		@Override
		public Object scan(Attr key) {
			switch (key) {
				case PARENT:
					return s;
				case REQUESTED_FROM_DOWNSTREAM:
					return requested;
				case TERMINATED:
					return done;
				case PREFETCH:
					return Integer.MAX_VALUE;
			}
			return InnerOperator.super.scan(key);
		}


	}
}
