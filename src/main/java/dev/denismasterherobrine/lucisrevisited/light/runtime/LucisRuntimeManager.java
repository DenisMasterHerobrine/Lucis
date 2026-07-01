package dev.denismasterherobrine.lucisrevisited.light.runtime;

import dev.denismasterherobrine.lucisrevisited.config.LucisConfig;
import dev.denismasterherobrine.lucisrevisited.light.engine.LucisRelighter;
import dev.denismasterherobrine.lucisrevisited.light.region.BoundaryDelta;
import dev.denismasterherobrine.lucisrevisited.light.region.BoundarySignature;
import dev.denismasterherobrine.lucisrevisited.light.region.OwnedRegionCache;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionBounds;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionOwnerTable;
import dev.denismasterherobrine.lucisrevisited.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucisrevisited.test.LucisBenchmarkSupport;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

public final class LucisRuntimeManager implements AutoCloseable {
    private final RuntimeUpdateQueue updateQueue = new RuntimeUpdateQueue();
    private final RegionOwnerTable ownerTable = new RegionOwnerTable();
    private final OwnedRegionCache regionCache = new OwnedRegionCache();
    private final LucisScheduler scheduler = new LucisScheduler(Math.max(1, Runtime.getRuntime().availableProcessors() - 1));
    private final ConcurrentLinkedQueue<LucisRelightResult> commitQueue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<BoundaryDelta> boundaryQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<Long, BoundarySignature> boundarySignatures = new ConcurrentHashMap<>();
    private final AtomicLong ownerIds = new AtomicLong();

    public void enqueue(BlockChangeRecord record) {
        updateQueue.enqueue(record);
    }

    public void tick(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                     int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        scheduleQueuedRegions(getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
        scheduleBoundaryRegions(getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
        flushCommits(lightEngine);
        regionCache.trimToSize(LucisConfig.maxCachedRegions);
    }

    public boolean hasPendingWork() {
        return !updateQueue.isEmpty()
                || !boundaryQueue.isEmpty()
                || !commitQueue.isEmpty()
                || !scheduledRegions.isEmpty()
                || scheduler.hasPendingWork();
    }

    private void scheduleQueuedRegions(LightChunkGetter getter, LucisRelighter relighter,
                                       int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        if (updateQueue.isEmpty()) {
            return;
        }

        Map<Long, List<BlockChangeRecord>> byRegion = new HashMap<>();
        Collection<BlockChangeRecord> drained = updateQueue.drain();
        LucisBenchmarkSupport.count("lucis.runtime.drain.records", drained.size());
        for (BlockChangeRecord record : drained) {
            long regionKey = regionKey(record.pos().getX() >> 4, record.pos().getZ() >> 4, regionChunks);
            byRegion.computeIfAbsent(regionKey, ignored -> new ArrayList<>()).add(record);
        }
        LucisBenchmarkSupport.count("lucis.runtime.drain.regions", byRegion.size());

        int scheduled = 0;
        for (Map.Entry<Long, List<BlockChangeRecord>> entry : byRegion.entrySet()) {
            if (scheduled >= LucisConfig.maxBatchChunks) {
                LucisBenchmarkSupport.count("lucis.runtime.requeued.batchLimit");
                updateQueue.enqueueAll(entry.getValue());
                continue;
            }
            scheduleRegion(false, entry.getKey(), entry.getValue(), getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
            scheduled++;
        }
    }

    private void scheduleBoundaryRegions(LightChunkGetter getter, LucisRelighter relighter,
                                         int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
        BoundaryDelta delta;
        int scheduled = 0;
        while ((delta = boundaryQueue.poll()) != null) {
            if (scheduled >= LucisConfig.maxBatchChunks) {
                LucisBenchmarkSupport.count("lucis.runtime.boundary.requeued.batchLimit");
                boundaryQueue.add(delta);
                break;
            }
            RuntimeRegionState targetState = regionCache.getInitialized(delta.targetRegionKey());
            if (targetState == null) {
                LucisBenchmarkSupport.count("lucis.runtime.boundary.skipped.uninitialized");
                continue;
            }
            scheduleRegion(true, delta.targetRegionKey(), List.of(), getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
            scheduled++;
        }
    }

    private void scheduleRegion(boolean boundaryOnly, long regionKey, List<BlockChangeRecord> changes, LightChunkGetter getter,
                                LucisRelighter relighter, int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
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

        ChunkPos anchor = new ChunkPos(ChunkPos.getX(regionKey), ChunkPos.getZ(regionKey));
        RegionBounds bounds = RegionBounds.around(anchor, getter.getLevel(), regionChunks, haloChunks);
        RuntimeRegionState ownedState = regionCache.getOrCreate(bounds);
        ownedState.touch();
        LucisBenchmarkSupport.count(boundaryOnly ? "lucis.runtime.jobs.boundary.submit" : "lucis.runtime.jobs.runtime.submit");
        LucisBenchmarkSupport.count("lucis.runtime.jobs.changeRecords", changes == null ? 0 : changes.size());
        boolean submitted = scheduler.submit(new LucisJob(boundaryOnly ? LucisJobPriority.BOUNDARY : LucisJobPriority.RUNTIME, () -> {
            long jobStartedAt = System.nanoTime();
            try {
                List<LucisRelightResult> results = boundaryOnly
                        ? relighter.refreshRuntimeRegion(getter, ownedState, enableSky, enableBlock)
                        : relighter.relightRuntimeRegion(getter, ownedState, changes == null ? List.of() : changes, enableSky, enableBlock);
                LucisBenchmarkSupport.count("lucis.runtime.jobs.results", results.size());
                for (LucisRelightResult result : results) {
                    LucisBenchmarkSupport.count("lucis.runtime.jobs.sections", result.sections().size());
                    commitQueue.add(result);
                }
                // Boundary refresh currently recomputes neighbor regions and is slower than the stale-boundary risk.
                // Keep runtime fast until delta application is wired into refreshRuntimeRegion.
            } finally {
                LucisBenchmarkSupport.record(boundaryOnly ? "lucis.stage.runtime.job.boundary" : "lucis.stage.runtime.job.runtime", System.nanoTime() - jobStartedAt);
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

    private boolean shouldEnqueue(BoundaryDelta delta) {
        BoundarySignature next = BoundarySignature.from(delta.sourceRegionKey(), delta.side(), delta.blockLevels(), delta.skyLevels());
        long key = signatureKey(delta.sourceRegionKey(), delta.side());
        BoundarySignature previous = boundarySignatures.put(key, next);
        return previous == null || previous.blockHash() != next.blockHash() || previous.skyHash() != next.skyHash();
    }

    private long signatureKey(long regionKey, dev.denismasterherobrine.lucisrevisited.light.region.RegionSide side) {
        return regionKey ^ ((long) side.ordinal() << 60);
    }

    private void flushCommits(ThreadedLevelLightEngine lightEngine) {
        boolean any = false;
        LucisRelightResult result;
        while ((result = commitQueue.poll()) != null) {
            any = true;
            LucisBenchmarkSupport.count("lucis.runtime.commit.results");
            for (LucisSectionData section : result.sections()) {
                LucisBenchmarkSupport.count("lucis.runtime.commit.sections");
                lightEngine.queueSectionData(section.layer(), section.sectionPos(), section.dataLayer());
                lightEngine.updateSectionStatus(section.sectionPos(), false);
            }
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

    @Override
    public void close() {
        scheduler.close();
        commitQueue.clear();
        boundaryQueue.clear();
        scheduledRegions.clear();
        ownerTable.clear();
        boundarySignatures.clear();
    }
}
