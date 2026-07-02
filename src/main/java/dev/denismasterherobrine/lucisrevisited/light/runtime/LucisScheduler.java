package dev.denismasterherobrine.lucisrevisited.light.runtime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class LucisScheduler implements AutoCloseable {
    private final LinkedBlockingQueue<LucisJob> queue = new LinkedBlockingQueue<>();
    private final ExecutorService workers;
    private final AtomicInteger activeJobs = new AtomicInteger();
    private volatile boolean running = true;

    public LucisScheduler(int workerCount) {
        int workerCountResolved = Math.max(1, workerCount);
        this.workers = Executors.newFixedThreadPool(workerCountResolved, new LucisThreadFactory());
        for (int i = 0; i < workerCountResolved; i++) {
            this.workers.execute(this::runLoop);
        }
    }

    public boolean submit(LucisJob job) {
        return running && queue.offer(job);
    }

    private void runLoop() {
        while (running) {
            try {
                LucisJob job = queue.take();
                activeJobs.incrementAndGet();
                try {
                    job.task().run();
                } catch (Throwable throwable) {
                    dev.denismasterherobrine.lucisrevisited.LucisRevisited.LOGGER.error("Lucis worker job failed", throwable);
                } finally {
                    activeJobs.decrementAndGet();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean hasPendingWork() {
        return !queue.isEmpty() || activeJobs.get() > 0;
    }

    @Override
    public void close() {
        running = false;
        queue.clear();
        workers.shutdownNow();
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
