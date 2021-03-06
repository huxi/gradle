/*
 * Copyright 2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.test.fixtures.server.http;

import com.sun.net.httpserver.HttpExchange;
import org.gradle.internal.time.TimeProvider;
import org.gradle.internal.time.TrueTimeProvider;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

class CyclicBarrierRequestHandler extends TrackingHttpHandler {
    private final TimeProvider timeProvider = new TrueTimeProvider();
    private final Lock lock;
    private final Condition condition;
    private final List<String> received = new ArrayList<String>();
    private final Map<String, ResourceHandler> pending;
    private final int timeoutMs;
    private long mostRecentEvent;
    private AssertionError failure;

    CyclicBarrierRequestHandler(Lock lock, int timeoutMs, Collection<? extends ResourceHandler> expectedCalls) {
        this.lock = lock;
        condition = lock.newCondition();
        this.timeoutMs = timeoutMs;
        pending = new TreeMap<String, ResourceHandler>();
        for (ResourceHandler call : expectedCalls) {
            pending.put(call.getPath(), call);
        }
    }

    @Override
    public ResourceHandler handle(int id, HttpExchange httpExchange) throws Exception {
        ResourceHandler handler;
        lock.lock();
        try {
            if (pending.isEmpty()) {
                // barrier open, let it travel on
                return null;
            }
            if (failure != null) {
                // Busted
                throw failure;
            }

            long now = timeProvider.getCurrentTimeForDuration();
            if (mostRecentEvent < now) {
                mostRecentEvent = now;
            }

            String path = httpExchange.getRequestURI().getPath().substring(1);
            handler = pending.remove(path);
            if (handler == null) {
                failure = new AssertionError(String.format("Unexpected request to '%s' received. Waiting for %s, already received %s.", path, pending.keySet(), received));
                condition.signalAll();
                throw failure;
            }

            received.add(path);
            if (pending.isEmpty()) {
                condition.signalAll();
            }

            while (!pending.isEmpty() && failure == null) {
                long waitMs = mostRecentEvent + timeoutMs - timeProvider.getCurrentTimeForDuration();
                if (waitMs < 0) {
                    System.out.println(String.format("[%d] timeout waiting for other requests", id));
                    failure = new AssertionError(String.format("Timeout waiting for expected requests to be received. Still waiting for %s, received %s.", pending.keySet(), received));
                    condition.signalAll();
                    throw failure;
                }
                System.out.println(String.format("[%d] waiting for other requests. Still waiting for %s", id, pending.keySet()));
                condition.await(waitMs, TimeUnit.MILLISECONDS);
            }

            if (failure != null) {
                // Failed in another thread
                System.out.println(String.format("[%d] failure in another thread", id));
                throw failure;
            }
        } finally {
            lock.unlock();
        }

        // All requests completed, write response
        return handler;
    }

    public void assertComplete() {
        lock.lock();
        try {
            if (failure != null) {
                throw failure;
            }
            if (!pending.isEmpty()) {
                throw new AssertionError(String.format("Did not receive expected requests. Waiting for %s, received %s", pending.keySet(), received));
            }
        } finally {
            lock.unlock();
        }
    }
}
