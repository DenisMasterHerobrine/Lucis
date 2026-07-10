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

public final class LucisRuntimeManager implements AutoCloseable {
    private static final int MAX_RUNTIME_REGION_SUBMITS_PER_TICK = Integer.getInteger("lucis.runtime.maxSubmitsPerTick", 0);
    private static final int MAX_RUNTIME_PENDING_RECORDS = Integer.getInteger("lucis.runtime.maxPendingRecords", 131_072);

    private final RuntimeUpdateQueue updateQueue = new RuntimeUpdateQueue(MAX_RUNTIME_PENDING_RECORDS);
    private final RegionOwnerTable ownerTable = new RegionOwnerTable();
    private final OwnedRegionCache regionCache = new OwnedRegionCache();
    private final LucisScheduler scheduler = new LucisScheduler(runtimeWorkerCount());
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ownerIds = new AtomicLong();
    private final AtomicInteger pendingPublishes = new AtomicInteger();
    private final AtomicInteger deferredRecords = new AtomicInteger();
    private final ReentrantLock coordinatorLock = new ReentrantLock();
    private final ArrayList<BlockChangeRecord> drainedChanges = new ArrayList<>(256);
    private final HashMap<Long, List<BlockChangeRecord>> pendingChangesByRegion = new HashMap<>();
    private volatile boolean closed;

    public boolean enqueue(BlockChangeRecord record) {
        if (closed) {
            return false;
        }
        return updateQueue.enqueue(record);
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

        drainedChanges.clear();
        int drained = updateQueue.drainTo(drainedChanges);
        LucisBenchmarkSupport.count("lucis.runtime.drain.records", drained);
        for (BlockChangeRecord record : drainedChanges) {
            long regionKey = regionKey(record.x() >> 4, record.z() >> 4, regionChunks);
            pendingChangesByRegion.computeIfAbsent(regionKey, ignored -> new ArrayList<>()).add(record);
        }
        deferredRecords.addAndGet(drained);
        drainedChanges.clear();
        LucisBenchmarkSupport.count("lucis.runtime.drain.regions", pendingChangesByRegion.size());

        int scheduled = 0;
        int maxSubmits = runtimeSubmitBudget();
        Iterator<Map.Entry<Long, List<BlockChangeRecord>>> iterator = pendingChangesByRegion.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, List<BlockChangeRecord>> entry = iterator.next();
            if (scheduled >= maxSubmits) {
                break;
            }
            if (scheduledRegions.contains(entry.getKey())) {
                continue;
            }
            List<BlockChangeRecord> changes = entry.getValue();
            iterator.remove();
            deferredRecords.addAndGet(-changes.size());
            scheduleRegion(entry.getKey(), changes, lightEngine, getter, relighter,
                    regionChunks, haloChunks, enableSky, enableBlock);
            scheduled++;
        }
    }

    private void scheduleRegion(long regionKey, List<BlockChangeRecord> changes, ThreadedLevelLightEngine lightEngine,
                                LightChunkGetter getter,
                                LucisRelighter relighter, int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            if (changes != null && !changes.isEmpty()) {
                updateQueue.enqueueAll(changes);
            }
            return;
        }

        ChunkPos anchor = new ChunkPos(ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
        LightChunk coreChunk = getter.getChunkForLighting(anchor.x(), anchor.z());
        if (coreChunk == null) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.missingChunk");
            if (changes != null && !changes.isEmpty()) {
                updateQueue.enqueueAll(changes);
            }
            return;
        }
        if (!scheduledRegions.add(regionKey)) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.alreadyScheduled");
            if (changes != null && !changes.isEmpty()) {
                updateQueue.enqueueAll(changes);
            }
            return;
        }

        long ownerId = ownerIds.incrementAndGet();
        if (!ownerTable.tryAcquire(regionKey, ownerId)) {
            LucisBenchmarkSupport.count("lucis.runtime.requeued.ownerBusy");
            scheduledRegions.remove(regionKey);
            if (changes != null && !changes.isEmpty()) {
                updateQueue.enqueueAll(changes);
            }
            return;
        }

        RegionBounds bounds = RegionBounds.around(anchor, getter.getLevel(), regionChunks, haloChunks);
        RuntimeRegionState ownedState = regionCache.getOrCreate(bounds);
        ownedState.touch();
        LucisBenchmarkSupport.count("lucis.runtime.jobs.runtime.submit");
        LucisBenchmarkSupport.count("lucis.runtime.jobs.changeRecords", changes == null ? 0 : changes.size());
        boolean submitted = scheduler.submit(new LucisJob(() -> {
            long jobStartedAt = LucisBenchmarkSupport.start();
            List<LucisRelightResult> results;
            try {
                results = relighter.relightRuntimeRegion(getter, ownedState, coreChunk,
                        changes == null ? List.of() : changes, enableSky, enableBlock);
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
                    published = publisher.lucis$publish(result, coreChunk);
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
            if (changes != null && !changes.isEmpty()) {
                updateQueue.enqueueAll(changes);
            }
        }
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
            pendingChangesByRegion.clear();
            deferredRecords.set(0);
            scheduledRegions.clear();
            ownerTable.clear();
            regionCache.clear();
        } finally {
            coordinatorLock.unlock();
        }
    }
}
