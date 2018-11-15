/**
 * Copyright 2017 Pivotal Software, Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micrometer.statsd.internal;

import reactor.core.publisher.DirectProcessor;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class BufferingFlux {

    private BufferingFlux() {
    }

    /**
     * Creates a Flux that implements Nagle's algorithm to buffer messages -- joined by a delimiter string -- to up a
     * maximum number of bytes, or a maximum duration of time. This avoids sending many small packets in favor of fewer
     * larger ones.
     *
     * @param source                      The input flux.
     * @param delimiter                   The delimiter to use to join messages
     * @param maxByteArraySize            The buffered payload will contain no more than this number of bytes
     * @param maxMillisecondsBetweenEmits Buffered payloads will be emitted no less frequently than this.
     * @return A flux implementing Nagle's algorithm.
     * @see <a href="https://en.wikipedia.org/wiki/Nagle%27s_algorithm">Nagle's algorithm</a>
     */
    public static Flux<String> create(final Flux<String> source, final String delimiter, final int maxByteArraySize, final long maxMillisecondsBetweenEmits) {
        return Flux.defer(() -> {
            final int delimiterSize = delimiter.getBytes().length;
            final AtomicInteger byteSize = new AtomicInteger(0);
            final AtomicLong lastTime = new AtomicLong(0);

            final DirectProcessor<Void> intervalEnd = DirectProcessor.create();

            final Flux<String> hearbeat = Flux.interval(Duration.ofMillis(maxMillisecondsBetweenEmits))
                    .map(l -> "")
                    .takeUntilOther(intervalEnd);

            // Create a stream that emits at least once every $maxMillisecondsBetweenEmits, to avoid long pauses between
            // buffer flushes when the source doesn't emit for a while.
            final Flux<String> sourceWithEmptyStringKeepAlive = source
                    .doOnTerminate(intervalEnd::onComplete)
                    .mergeWith(hearbeat);

            return sourceWithEmptyStringKeepAlive
                    .bufferUntil(line -> {
                        final int bytesLength = line.getBytes().length;
                        final long now = System.currentTimeMillis();
                        final long last = lastTime.getAndSet(now);
                        long diff;
                        if (last != 0L) {
                            diff = now - last;
                            if (diff > maxMillisecondsBetweenEmits && byteSize.get() > 0) {
                                // This creates a buffer, reset size
                                byteSize.set(bytesLength);
                                return true;
                            }
                        }

                        int additionalBytes = bytesLength;
                        if (additionalBytes > 0 && byteSize.get() > 0) {
                            additionalBytes += delimiterSize;  // Make up for the delimiter that's added when joining the strings
                        }

                        final int projectedBytes = byteSize.addAndGet(additionalBytes);

                        if (projectedBytes > maxByteArraySize) {
                            // This creates a buffer, reset size
                            byteSize.set(bytesLength);
                            return true;
                        }

                        return false;
                    }, true)
                    .map(lines -> {
                        lines.removeIf(String::isEmpty); // Ignore empty messages
                        return String.join(delimiter, lines);
                    });
        });
    }
}