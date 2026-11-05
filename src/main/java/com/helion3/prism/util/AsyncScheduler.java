/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2015 Helion3 http://helion3.com/
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package com.helion3.prism.util;

import org.apache.logging.log4j.Logger;

import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** The asynchronous task lifecycle previously supplied by Sponge. Never accesses game state. */
public final class AsyncScheduler {
    private final ScheduledThreadPoolExecutor executor;
    private final Logger logger;

    public AsyncScheduler(Logger logger) {
        this.logger = logger;
        AtomicInteger threadNumber = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(2, task -> {
            Thread thread = new Thread(task, "PrismAsync-" + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setRemoveOnCancelPolicy(true);
    }

    public void execute(String name, Runnable task) {
        executor.execute(guard(name, task));
    }

    public void repeat(String name, Runnable task, long interval, TimeUnit unit) {
        executor.scheduleAtFixedRate(guard(name, task), 0, interval, unit);
    }

    private Runnable guard(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Exception exception) {
                // A failure must be reported without silently disabling a periodic queue flush.
                logger.error("Encountered an error running " + name, exception);
            }
        };
    }

    /** Cancel future runs and finish active work before the lifecycle closes its database. */
    public void shutdownAndWait() {
        executor.shutdown();
        boolean interrupted = false;
        while (!executor.isTerminated()) {
            try {
                executor.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
