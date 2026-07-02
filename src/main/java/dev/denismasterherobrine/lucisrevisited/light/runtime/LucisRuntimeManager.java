package dev.denismasterherobrine.lucisrevisited.light.runtime;

import dev.denismasterherobrine.lucisrevisited.config.LucisConfig;
import dev.denismasterherobrine.lucisrevisited.light.engine.LucisRelighter;
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
    private static final int MAX_RUNTIME_REGION_SUBMITS_PER_TICK = Integer.getInteger("lucis.runtime.maxSubmitsPerTick", 0);

    private final RuntimeUpdateQueue updateQueue = new RuntimeUpdateQueue();
    private final RegionOwnerTable ownerTable = new RegionOwnerTable();
    private final OwnedRegionCache regionCache = new OwnedRegionCache();
    private final LucisScheduler scheduler = new LucisScheduler(runtimeWorkerCount());
    private final ConcurrentLinkedQueue<LucisRelightResult> commitQueue = new ConcurrentLinkedQueue<>();
    private final Set<Long> scheduledRegions = ConcurrentHashMap.newKeySet();
    private final AtomicLong ownerIds = new AtomicLong();

    public void enqueue(BlockChangeRecord record) {
        updateQueue.enqueue(record);
    }

    public void tick(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter, LucisRelighter relighter,
                     int regionChunks, int haloChunks, boolean enableSky, boolean enableBlock) {
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

        Map<Long, List<BlockChangeRecord>> byRegion = new HashMap<>();
        Collection<BlockChangeRecord> drained = updateQueue.drain();
        LucisBenchmarkSupport.count("lucis.runtime.drain.records", drained.size());
        for (BlockChangeRecord record : drained) {
            long regionKey = regionKey(record.pos().getX() >> 4, record.pos().getZ() >> 4, regionChunks);
            byRegion.computeIfAbsent(regionKey, ignored -> new ArrayList<>()).add(record);
        }
        LucisBenchmarkSupport.count("lucis.runtime.drain.regions", byRegion.size());

        int scheduled = 0;
        int maxSubmits = runtimeSubmitBudget();
        for (Map.Entry<Long, List<BlockChangeRecord>> entry : byRegion.entrySet()) {
            if (scheduled >= maxSubmits) {
                LucisBenchmarkSupport.count("lucis.runtime.requeued.batchLimit");
                updateQueue.enqueueAll(entry.getValue());
                continue;
            }
            scheduleRegion(entry.getKey(), entry.getValue(), getter, relighter, regionChunks, haloChunks, enableSky, enableBlock);
            scheduled++;
        }
    }

    private void scheduleRegion(long regionKey, List<BlockChangeRecord> changes, LightChunkGetter getter,
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
        LucisBenchmarkSupport.count("lucis.runtime.jobs.runtime.submit");
        LucisBenchmarkSupport.count("lucis.runtime.jobs.changeRecords", changes == null ? 0 : changes.size());
        boolean submitted = scheduler.submit(new LucisJob(() -> {
            long jobStartedAt = LucisBenchmarkSupport.start();
            try {
                List<LucisRelightResult> results = relighter.relightRuntimeRegion(getter, ownedState,
                        changes == null ? List.of() : changes, enableSky, enableBlock);
                LucisBenchmarkSupport.count("lucis.runtime.jobs.results", results.size());
                for (LucisRelightResult result : results) {
                    LucisBenchmarkSupport.count("lucis.runtime.jobs.sections", result.sections().size());
                    commitQueue.add(result);
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
        LucisRelightResult result;
        while ((result = commitQueue.poll()) != null) {
            any = true;
            LucisBenchmarkSupport.count("lucis.runtime.commit.results");
            LucisBenchmarkSupport.count("lucis.runtime.commit.sections", result.sections().size());
            publisher.lucisrevisited$publish(result);
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
        scheduler.close();
        commitQueue.clear();
        scheduledRegions.clear();
        ownerTable.clear();
    }
}
