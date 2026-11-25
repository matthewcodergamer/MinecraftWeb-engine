/*
 *  Copyright 2017 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.classlib.java.util.stream.impl;

import java.util.Spliterator;
import java.util.function.Consumer;

/**
 * Compat override of TeaVM 0.16.0's {@code TSimpleStreamSpliterator}.
 *
 * <p>TeaVM 0.16.0 returns {@code true} unconditionally from
 * {@link #tryAdvance(Consumer)} when the spliterator was not already done,
 * even when the underlying stream is exhausted and the action was never
 * invoked. {@code Spliterator} adapters such as
 * {@code java.util.Spliterators.iterator} therefore report one extra element
 * and hand out {@code null}. Guava's {@code Streams.mapWithIndex} uses that
 * adapter whenever the source spliterator is not {@code SUBSIZED}, which is
 * how a trailing {@code null} reached
 * {@code TagValueInput.CompoundListWrapper.stream()} and crashed chunk
 * post-loading with a {@code CompoundTag.isEmpty()} null dereference.</p>
 *
 * <p>Return {@code true} only when an element was actually delivered. The
 * loop already distinguishes the two cases via {@code foundItems}; a stream
 * may return {@code true} without invoking the consumer while it finishes an
 * inner stream, so the loop must continue until either an item is found or
 * the stream reports completion.</p>
 */
public class TSimpleStreamSpliterator<T> implements Spliterator<T> {
    private TSimpleStreamImpl<T> stream;
    private boolean foundItems;
    private boolean done;

    public TSimpleStreamSpliterator(TSimpleStreamImpl<T> stream) {
        this.stream = stream;
    }

    @Override
    public void forEachRemaining(Consumer<? super T> action) {
        while (true) {
            boolean hasMore = stream.next(x -> {
                action.accept(x);
                return true;
            });
            if (!hasMore) {
                break;
            }
        }
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> action) {
        if (done) {
            return false;
        }
        foundItems = false;
        while (!foundItems && !done) {
            done = !stream.next(x -> {
                action.accept(x);
                foundItems = true;
                return false;
            });
        }
        return foundItems;
    }

    @Override
    public Spliterator<T> trySplit() {
        return null;
    }

    @Override
    public long estimateSize() {
        return stream.estimateSize();
    }

    @Override
    public int characteristics() {
        return 0;
    }
}
