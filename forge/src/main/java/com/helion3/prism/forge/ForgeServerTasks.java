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

package com.helion3.prism.forge;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Native main-thread delivery for command callbacks; does not observe or sample world changes. */
public final class ForgeServerTasks {
    private static final ForgeServerTasks INSTANCE = new ForgeServerTasks();
    private static final Queue<Job<?>> PENDING = new ConcurrentLinkedQueue<>();
    private static volatile Thread serverThread;
    private static volatile boolean running;
    private static boolean registered;

    private ForgeServerTasks() {}

    public static synchronized void start() {
        if (!registered) {
            FMLCommonHandler.instance().bus().register(INSTANCE);
            registered = true;
        }
        serverThread = Thread.currentThread();
        running = true;
    }

    public static void run(Runnable action) {
        submit(() -> {
            action.run();
            return null;
        }).whenComplete((ignored, failure) -> {
            if (failure != null && running) {
                LogManager.getLogger("Prism").error("Native command callback failed", failure);
            }
        });
    }

    public static <T> CompletableFuture<T> submit(Callable<T> action) {
        Job<T> job = new Job<>(action);
        if (!running) {
            job.reject();
        } else if (Thread.currentThread() == serverThread) {
            job.run();
        } else {
            PENDING.add(job);
            if (!running && PENDING.remove(job)) job.reject();
        }
        return job.future;
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Job<?> job;
            while ((job = PENDING.poll()) != null) {
                if (running) job.run(); else job.reject();
            }
        }
    }

    public static void stop() {
        running = false;
        Job<?> job;
        while ((job = PENDING.poll()) != null) job.reject();
        serverThread = null;
    }

    private static final class Job<T> {
        private final Callable<T> action;
        private final CompletableFuture<T> future = new CompletableFuture<>();

        private Job(Callable<T> action) {
            this.action = action;
        }

        private void run() {
            try {
                future.complete(action.call());
            } catch (Throwable failure) {
                future.completeExceptionally(failure);
            }
        }

        private void reject() {
            future.completeExceptionally(new IllegalStateException("Prism server command scheduler is stopped"));
        }
    }
}
