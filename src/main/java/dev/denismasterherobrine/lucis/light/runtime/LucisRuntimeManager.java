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
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class LucisRuntimeManager implements AutoCloseable {
    private static final int MAX_RUNTIME_REGION_SUBMITS_PER_TICK = Integer.getInteger("lucis.runtime.maxSubmitsPerTick", 0);
    private static final int MAX_RUNTIME_PENDING_RECORDS = Integer.getInteger("lucis.runtime.maxPendingRecords", 131_072);

    private final RuntimeUpdateQueue updateQueue = new RuntimeUpdateQueue(MAX_RUNTIME_PENDING_RECORDS);
    private final RegionOwnerTable ownerTable = new RegionOwnerTable();
    private final OwnedRegionCache regionCache = new OwnedRegionCache();
    private final LucisScheduler scheduler = new LucisScheduler(runtimeWorkerCount());
    private final ConcurrentLinkedQueue<RuntimeCommit> commitQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ownerIds = new AtomicLong();
    private final ArrayList<BlockChangeRecord> drainedChanges = new ArrayList<>(256);
    private final HashMap<Long, List<BlockChangeRecord>> changesByRegion = new HashMap<>();
    private volatile boolean closed;

    public boolean enqueue(BlockChangeRecord record) {
        if (closed) {
            return false;
        }
        return updateQueue.enqueue(record);
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

        drainedChanges.clear();
        changesByRegion.clear();
        int drained = updateQueue.drainTo(drainedChanges);
        LucisBenchmarkSupport.count("lucis.runtime.drain.records", drained);
        for (BlockChangeRecord record : drainedChanges) {
            long regionKey = regionKey(record.x() >> 4, record.z() >> 4, regionChunks);
            changesByRegion.computeIfAbsent(regionKey, ignored -> new ArrayList<>()).add(record);
        }
        drainedChanges.clear();
        LucisBenchmarkSupport.count("lucis.runtime.drain.regions", changesByRegion.size());

        int scheduled = 0;
        int maxSubmits = runtimeSubmitBudget();
        for (Map.Entry<Long, List<BlockChangeRecord>> entry : changesByRegion.entrySet()) {
            if (scheduled >= maxSubmits) {
                LucisBenchmarkSupport.count("lucis.runtime.requeued.batchLimit");
                updateQueue.enqueueAll(entry.getValue());
                continue;
            }
            scheduleRegion(entry.getKey(), entry.getValue(), getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
            scheduled++;
        }
        changesByRegion.clear();
    }

    private void scheduleRegion(long regionKey, List<BlockChangeRecord> changes, LightChunkGetter getter,
                                LucisRelighter relighter, int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (closed) {
            if (changes != null && !changes.isEmpty()) {
                updateQueue.enqueueAll(changes);
            }
            return;
        }

        ChunkPos anchor = new ChunkPos(ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
        LightChunk coreChunk = getter.getChunkForLighting(anchor.x, anchor.z);
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
            try {
                List<LucisRelightResult> results = relighter.relightRuntimeRegion(getter, ownedState, coreChunk,
                        changes == null ? List.of() : changes, enableSky, enableBlock);
                if (closed) {
                    return;
                }
                LucisBenchmarkSupport.count("lucis.runtime.jobs.results", results.size());
                for (LucisRelightResult result : results) {
                    LucisBenchmarkSupport.count("lucis.runtime.jobs.sections", result.sections().size());
                    commitQueue.add(new RuntimeCommit(result, coreChunk));
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
            if (changes != null && !changes.isEmpty()) {
                updateQueue.enqueueAll(changes);
            }
        }
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
        scheduledRegions.clear();
        ownerTable.clear();
        regionCache.clear();
    }

    private record RuntimeCommit(LucisRelightResult result, LightChunk expectedChunk) {
    }
}
