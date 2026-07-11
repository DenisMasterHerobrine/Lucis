package dev.denismasterherobrine.lucis.light.runtime;

import dev.denismasterherobrine.lucis.Lucis;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.engine.LucisRelighter;
import dev.denismasterherobrine.lucis.light.region.OwnedRegionCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionOwnerTable;
import dev.denismasterherobrine.lucis.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import net.minecraft.world.level.block.state.BlockState;

public final class LucisRuntimeManager implements AutoCloseable {
    private static final int MAX_RUNTIME_REGION_SUBMITS_PER_TICK = Integer.getInteger("lucis.runtime.maxSubmitsPerTick", 0);
    private static final int MAX_RUNTIME_PENDING_RECORDS = Integer.getInteger("lucis.runtime.maxPendingRecords", 131_072);
    private static final int FULL_RELIGHT_CHANGE_THRESHOLD = Integer.getInteger("lucis.runtime.fullRelightChangeThreshold", 2048);
    private static final long FULL_RELIGHT_COALESCE_NANOS = Long.getLong("lucis.runtime.fullRelightCoalesceNanos", 5_000_000_000L);
    private static final int RUNTIME_REGION_CHUNKS = Math.max(1, Math.min(Integer.getInteger("lucis.runtimeRegionChunks", 1), 16));

    private final RuntimeUpdateQueue updateQueue = new RuntimeUpdateQueue(MAX_RUNTIME_PENDING_RECORDS, FULL_RELIGHT_CHANGE_THRESHOLD);
    private final RegionOwnerTable ownerTable = new RegionOwnerTable();
    private final OwnedRegionCache regionCache = new OwnedRegionCache();
    private final LucisScheduler scheduler = new LucisScheduler(runtimeWorkerCount());
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ownerIds = new AtomicLong();
    private final AtomicInteger pendingPublishes = new AtomicInteger();
    private final AtomicInteger deferredRecords = new AtomicInteger();
    private final ReentrantLock coordinatorLock = new ReentrantLock();
    private final HashMap<Long, RuntimeRegionBatch> drainedBatches = new HashMap<>();
    private final HashMap<Long, RuntimeRegionBatch> pendingBatchesByRegion = new HashMap<>();
    private final ConcurrentHashMap<Long, Long> fullRelightCoalesceUntil = new ConcurrentHashMap<>();
    private volatile boolean closed;

    public boolean enqueue(BlockChangeRecord record) {
        return record != null && enqueue(record.x(), record.y(), record.z(), record.oldState(), record.newState());
    }

    public boolean enqueue(int x, int y, int z, BlockState oldState, BlockState newState) {
        if (closed) {
            return false;
        }
        long regionKey = regionKey(x >> 4, z >> 4, RUNTIME_REGION_CHUNKS);
        if (isFullRelightCoalescing(regionKey)) {
            updateQueue.enqueueFullRelight(regionKey, 1);
            markFullRelightCoalescing(regionKey);
            return true;
        }
        boolean accepted = updateQueue.enqueue(regionKey, x, y, z, oldState, newState);
        if (accepted && updateQueue.hasFullRelight(regionKey)) {
            markFullRelightCoalescing(regionKey);
        }
        return accepted;
    }

    public boolean enqueueFullRelight(long regionKey, long originalChangeCount) {
        if (closed) {
            return false;
        }
        updateQueue.enqueueFullRelight(regionKey, Math.max(1, originalChangeCount));
        markFullRelightCoalescing(regionKey);
        return true;
    }

    public boolean canAcceptMoreWork() {
        return !closed && deferredRecords.get() < MAX_RUNTIME_PENDING_RECORDS && updateQueue.hasCapacity();
    }

    public void tick(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                     int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        processQueuedUpdates(lightEngine, getter, relighter, regionChunks, haloChunks, enableSky, enableBlock, true);
    }

    public void kick(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                     int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        processQueuedUpdates(lightEngine, getter, relighter, regionChunks, haloChunks, enableSky, enableBlock, false);
    }

    public boolean hasPendingWork() {
        return !closed && (!updateQueue.isEmpty()
                || deferredRecords.get() > 0
                || !scheduledRegions.isEmpty()
                || scheduler.hasPendingWork()
                || pendingPublishes.get() > 0);
    }

    private void processQueuedUpdates(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                                      int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock,
                                      boolean trimCache) {
        if (closed || !coordinatorLock.tryLock()) {
            return;
        }
        try {
            scheduleQueuedRegions(lightEngine, getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
            if (trimCache) {
                regionCache.trimToSize(LucisConfig.maxCachedRegions);
            }
        } finally {
            coordinatorLock.unlock();
        }
    }

    private void scheduleQueuedRegions(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                                       int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (updateQueue.isEmpty() && deferredRecords.get() == 0) {
            return;
        }

        drainedBatches.clear();
        int drained = updateQueue.drainTo(drainedBatches);
        LucisBenchmarkSupport.count("lucis.runtime.drain.records", drained);
        deferredRecords.addAndGet(drained);
        for (Map.Entry<Long, RuntimeRegionBatch> entry : drainedBatches.entrySet()) {
            pendingBatchesByRegion.merge(entry.getKey(), entry.getValue(), LucisRuntimeManager::mergeBatches);
        }
        drainedBatches.clear();
        LucisBenchmarkSupport.count("lucis.runtime.drain.regions", pendingBatchesByRegion.size());

        int scheduled = 0;
        int maxSubmits = runtimeSubmitBudget();
        Iterator<Map.Entry<Long, RuntimeRegionBatch>> iterator = pendingBatchesByRegion.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, RuntimeRegionBatch> entry = iterator.next();
            if (scheduled >= maxSubmits) {
                break;
            }
            if (scheduledRegions.contains(entry.getKey())) {
                continue;
            }
            RuntimeRegionBatch batch = entry.getValue();
            iterator.remove();
            deferredRecords.addAndGet(-batch.queuedWorkCount());
            scheduleRegion(entry.getKey(), batch, lightEngine, getter, relighter,
                    regionChunks, haloChunks, enableSky, enableBlock);
            scheduled++;
        }
    }

    private void scheduleRegion(long regionKey, RuntimeRegionBatch batch, ThreadedLevelLightEngine lightEngine,
                                LightChunkGetter getter,
                                LucisRelighter relighter, int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            requeue(regionKey, batch);
            return;
        }

        ChunkPos anchor = new ChunkPos(ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
        LightChunk coreChunk = getter.getChunkForLighting(anchor.x(), anchor.z());
        if (coreChunk == null) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.missingChunk");
            requeue(regionKey, batch);
            return;
        }
        if (!scheduledRegions.add(regionKey)) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.alreadyScheduled");
            requeue(regionKey, batch);
            return;
        }

        long ownerId = ownerIds.incrementAndGet();
        if (!ownerTable.tryAcquire(regionKey, ownerId)) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.ownerBusy");
            scheduledRegions.remove(regionKey);
            requeue(regionKey, batch);
            return;
        }

        RegionBounds bounds = RegionBounds.around(anchor, getter.getLevel(), regionChunks, haloChunks);
        RuntimeRegionState ownedState = regionCache.getOrCreate(bounds);
        ownedState.touch();
        LucisBenchmarkSupport.count("lucis.runtime.jobs.runtime.submit");
        LucisBenchmarkSupport.count("lucis.runtime.jobs.changeRecords", batch == null ? 0 : batch.originalChangeCount());
        if (batch != null && batch.fullRelight()) {
            LucisBenchmarkSupport.count("lucis.runtime.jobs.fullRelight");
            markFullRelightCoalescing(regionKey);
        }
        boolean submitted = scheduler.submit(new LucisJob(() -> {
            long jobStartedAt = LucisBenchmarkSupport.start();
            List<LucisRelightResult> results;
            try {
                results = relighter.relightRuntimeRegion(getter, ownedState, coreChunk,
                        batch == null ? RuntimeRegionBatch.incremental(List.of()) : batch, enableSky, enableBlock);
            } finally {
                LucisBenchmarkSupport.recordSince("lucis.stage.runtime.job.runtime", jobStartedAt);
                ownerTable.release(regionKey, ownerId);
                scheduledRegions.remove(regionKey);
            }
            if (closed) {
                return;
            }
            LucisBenchmarkSupport.count("lucis.runtime.jobs.results", results.size());
            LucisLightPublisher publisher = (LucisLightPublisher) lightEngine;
            for (LucisRelightResult result : results) {
                LucisBenchmarkSupport.count("lucis.runtime.jobs.sections", result.sections().size());
                pendingPublishes.incrementAndGet();
                CompletableFuture<Void> published;
                try {
                    published = publisher.lucis$publish(result, null);
                } catch (Throwable throwable) {
                    pendingPublishes.decrementAndGet();
                    throw throwable;
                }
                published.whenComplete((ignored, throwable) -> {
                    pendingPublishes.decrementAndGet();
                    if (throwable != null && !closed) {
                        Lucis.LOGGER.error("Lucis runtime light publish failed", throwable);
                    }
                });
                LucisBenchmarkSupport.count("lucis.runtime.commit.results");
                LucisBenchmarkSupport.count("lucis.runtime.commit.sections", result.sections().size());
            }
            publisher.lucis$requestRuntimeDrain();
        }));
        if (!submitted) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.submitClosed");
            ownerTable.release(regionKey, ownerId);
            scheduledRegions.remove(regionKey);
            requeue(regionKey, batch);
        }
    }

    private void requeue(long regionKey, RuntimeRegionBatch batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        if (batch.fullRelight()) {
            updateQueue.enqueueFullRelight(regionKey, batch.originalChangeCount());
            markFullRelightCoalescing(regionKey);
        } else {
            updateQueue.enqueueAll(regionKey, batch.changes());
        }
    }

    private boolean isFullRelightCoalescing(long regionKey) {
        Long until = fullRelightCoalesceUntil.get(regionKey);
        if (until == null) {
            return false;
        }
        if (System.nanoTime() <= until) {
            return true;
        }
        fullRelightCoalesceUntil.remove(regionKey, until);
        return false;
    }

    private void markFullRelightCoalescing(long regionKey) {
        fullRelightCoalesceUntil.put(regionKey, System.nanoTime() + FULL_RELIGHT_COALESCE_NANOS);
    }

    private static RuntimeRegionBatch mergeBatches(RuntimeRegionBatch existing, RuntimeRegionBatch incoming) {
        if (existing.fullRelight() || incoming.fullRelight()) {
            return RuntimeRegionBatch.fullRelight(existing.originalChangeCount() + incoming.originalChangeCount());
        }
        ArrayList<BlockChangeRecord> merged = new ArrayList<>(existing.queuedChangeCount() + incoming.queuedChangeCount());
        merged.addAll(existing.changes());
        merged.addAll(incoming.changes());
        return new RuntimeRegionBatch(merged, false, existing.originalChangeCount() + incoming.originalChangeCount());
    }

    private long regionKey(int chunkX, int chunkZ, int regionChunks) {
        int originChunkX = Math.floorDiv(chunkX, regionChunks) * regionChunks;
        int originChunkZ = Math.floorDiv(chunkZ, regionChunks) * regionChunks;
        return RegionBounds.regionKey(originChunkX, originChunkZ);
    }

    private static int runtimeWorkerCount() {
        int configured = Integer.getInteger("lucis.runtimeWorkers", 0);
        if (configured > 0) {
            return configured;
        }
        int available = Runtime.getRuntime().availableProcessors();
        return Math.max(1, Math.min(available / 2, 4));
    }

    private static int runtimeSubmitBudget() {
        int configured = MAX_RUNTIME_REGION_SUBMITS_PER_TICK;
        if (configured > 0) {
            return Math.max(1, Math.min(LucisConfig.maxBatchChunks, configured));
        }
        return Math.max(1, LucisConfig.maxBatchChunks);
    }

    @Override
    public void close() {
        closed = true;
        scheduler.close();
        coordinatorLock.lock();
        try {
            updateQueue.clear();
            pendingBatchesByRegion.clear();
            deferredRecords.set(0);
            scheduledRegions.clear();
            ownerTable.clear();
            regionCache.clear();
            fullRelightCoalesceUntil.clear();
        } finally {
            coordinatorLock.unlock();
        }
    }
}
