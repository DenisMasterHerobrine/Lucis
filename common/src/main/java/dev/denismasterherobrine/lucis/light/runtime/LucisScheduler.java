package dev.denismasterherobrine.lucis.light.runtime;

import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class LucisScheduler implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final AtomicInteger activeJobs = new AtomicInteger();
    private volatile boolean running = true;

    public LucisScheduler(int workerCount) {
        int workerCountResolved = Math.max(1, workerCount);
        this.workers = new ThreadPoolExecutor(
                workerCountResolved,
                workerCountResolved,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedTransferQueue<>(),
                new LucisThreadFactory()) {
            @Override
            protected void beforeExecute(Thread thread, Runnable task) {
                super.beforeExecute(thread, task);
                activeJobs.incrementAndGet();
            }

            @Override
            protected void afterExecute(Runnable task, Throwable throwable) {
                try {
                    if (throwable != null) {
                        dev.denismasterherobrine.lucis.Lucis.LOGGER.error("Lucis worker job failed", throwable);
                    }
                } finally {
                    activeJobs.decrementAndGet();
                    super.afterExecute(task, throwable);
                }
            }
        };
        this.workers.prestartAllCoreThreads();
    }

    public boolean submit(LucisJob job) {
        if (!running) {
            return false;
        }
        try {
            workers.execute(job.task());
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    public boolean hasPendingWork() {
        return !workers.getQueue().isEmpty() || activeJobs.get() > 0;
    }

    @Override
    public void close() {
        running = false;
        workers.shutdownNow();
        workers.getQueue().clear();
        try {
            workers.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class LucisThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lucis-worker-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
