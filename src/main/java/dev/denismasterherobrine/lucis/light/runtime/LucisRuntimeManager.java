package dev.denismasterherobrine.lucis.light.runtime;

import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.engine.LucisRelighter;
import dev.denismasterherobrine.lucis.light.region.OwnedRegionCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionOwnerTable;
import dev.denismasterherobrine.lucis.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

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
    private final ConcurrentLinkedQueue<RuntimeCommit> commitQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ownerIds = new AtomicLong();
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
        return !closed && updateQueue.hasCapacity();
    }

    public void tick(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                     int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            return;
        }
        scheduleQueuedRegions(getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
        flushCommits(lightEngine);
        regionCache.trimToSize(LucisConfig.maxCachedRegions);
    }

    public boolean hasPendingWork() {
        return !updateQueue.isEmpty()
                || !commitQueue.isEmpty()
                || !scheduledRegions.isEmpty()
                || scheduler.hasPendingWork();
    }

    private void scheduleQueuedRegions(LightChunkGetter getter, LucisRelighter relighter,
                                       int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (updateQueue.isEmpty()) {
            return;
        }

        drainedBatches.clear();
        int drained = updateQueue.drainTo(drainedBatches);
        LucisBenchmarkSupport.count("lucis.runtime.drain.records", drained);
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
                LucisBenchmarkSupport.count("lucis.runtime.requeued.batchLimit");
                break;
            }
            if (scheduledRegions.contains(entry.getKey())) {
                continue;
            }
            RuntimeRegionBatch batch = entry.getValue();
            iterator.remove();
            scheduleRegion(entry.getKey(), batch, getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
            scheduled++;
        }
    }

    private void scheduleRegion(long regionKey, RuntimeRegionBatch batch, LightChunkGetter getter,
                                LucisRelighter relighter, int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            requeue(regionKey, batch);
            return;
        }

        ChunkPos anchor = new ChunkPos(ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
        LightChunk coreChunk = getter.getChunkForLighting(anchor.x, anchor.z);
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
        HashMap<Long, LightChunk> expectedChunks = captureExpectedChunks(getter, bounds);
        RuntimeRegionState ownedState = regionCache.getOrCreate(bounds);
        ownedState.touch();
        LucisBenchmarkSupport.count("lucis.runtime.jobs.runtime.submit");
        LucisBenchmarkSupport.count(batch.fullRelight() ? "lucis.runtime.jobs.fullRelight" : "lucis.runtime.jobs.incremental");
        LucisBenchmarkSupport.count("lucis.runtime.jobs.changeRecords", batch.queuedChangeCount());
        boolean submitted = scheduler.submit(new LucisJob(() -> {
            long jobStartedAt = LucisBenchmarkSupport.start();
            try {
                List<LucisRelightResult> results = relighter.relightRuntimeRegion(getter, ownedState, coreChunk,
                        batch, enableSky, enableBlock);
                if (closed) {
                    return;
                }
                LucisBenchmarkSupport.count("lucis.runtime.jobs.results", results.size());
                for (LucisRelightResult result : results) {
                    LucisBenchmarkSupport.count("lucis.runtime.jobs.sections", result.sections().size());
                    LightChunk expectedChunk = expectedChunks.get(ChunkPos.asLong(result.chunkPos().x, result.chunkPos().z));
                    if (expectedChunk == null) {
                        LucisBenchmarkSupport.count("lucis.runtime.commit.skippedMissingExpectedChunk");
                        continue;
                    }
                    commitQueue.add(new RuntimeCommit(result, expectedChunk));
                }
            } finally {
                LucisBenchmarkSupport.recordSince("lucis.stage.runtime.job.runtime", jobStartedAt);
                ownerTable.release(regionKey, ownerId);
                scheduledRegions.remove(regionKey);
            }
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
            updateQueue.enqueueAll(batch.changes());
        }
    }

    private HashMap<Long, LightChunk> captureExpectedChunks(LightChunkGetter getter, RegionBounds bounds) {
        int regionChunks = bounds.regionChunks();
        HashMap<Long, LightChunk> expectedChunks = new HashMap<>(regionChunks * regionChunks);
        int maxChunkX = bounds.originChunkX() + regionChunks;
        int maxChunkZ = bounds.originChunkZ() + regionChunks;
        for (int chunkZ = bounds.originChunkZ(); chunkZ < maxChunkZ; chunkZ++) {
            for (int chunkX = bounds.originChunkX(); chunkX < maxChunkX; chunkX++) {
                LightChunk chunk = getter.getChunkForLighting(chunkX, chunkZ);
                if (chunk != null) {
                    expectedChunks.put(ChunkPos.asLong(chunkX, chunkZ), chunk);
                }
            }
        }
        return expectedChunks;
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

    private void flushCommits(ThreadedLevelLightEngine lightEngine) {
        LucisLightPublisher publisher = (LucisLightPublisher) lightEngine;
        boolean any = false;
        RuntimeCommit commit;
        while ((commit = commitQueue.poll()) != null) {
            LucisRelightResult result = commit.result();
            any = true;
            LucisBenchmarkSupport.count("lucis.runtime.commit.results");
            LucisBenchmarkSupport.count("lucis.runtime.commit.sections", result.sections().size());
            publisher.lucis$publish(result, commit.expectedChunk());
        }
        if (any) {
            lightEngine.tryScheduleUpdate();
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
        updateQueue.clear();
        commitQueue.clear();
        pendingBatchesByRegion.clear();
        scheduledRegions.clear();
        ownerTable.clear();
        regionCache.clear();
        fullRelightCoalesceUntil.clear();
    }

    private record RuntimeCommit(LucisRelightResult result, LightChunk expectedChunk) {
    }
}
