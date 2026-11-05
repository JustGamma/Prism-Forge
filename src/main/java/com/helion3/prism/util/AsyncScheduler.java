/*
 * This file is part of Prism, licensed under the MIT License (MIT).
 * See LICENSE.txt for the full license.
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
