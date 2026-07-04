package com.angel.aibuilder.ai;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class AiExecutors {
    /**
     * Pool for blocking AI/HTTP work. Bridge requests can block for up to ~90 minutes,
     * so they must never run on the common ForkJoinPool, which they would starve.
     */
    public static final ExecutorService BACKGROUND = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger count = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "minedit-worker-" + count.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    });

    private AiExecutors() {
    }
}
