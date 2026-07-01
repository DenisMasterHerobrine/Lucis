package dev.denismasterherobrine.lucisrevisited.light.engine;

import dev.denismasterherobrine.lucisrevisited.config.LucisConfig;
import dev.denismasterherobrine.lucisrevisited.light.LightMaterialCache;
import dev.denismasterherobrine.lucisrevisited.light.runtime.BlockChangeRecord;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisRuntimeManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import dev.denismasterherobrine.lucisrevisited.test.LucisBenchmarkSupport;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;

public final class LucisEngineController {
    private static final int RUNTIME_REGION_CHUNKS = 1;
    private static final int RUNTIME_HALO_CHUNKS = 0;

    private final LightMaterialCache materialCache = new LightMaterialCache();
    private final LucisRelighter relighter = new LucisRelighter(materialCache, new LucisRegionExtractor(materialCache));
    private final LucisRuntimeManager runtimeManager = new LucisRuntimeManager();
    private final ExecutorService worldgenWorkers = Executors.newFixedThreadPool(worldgenWorkerCount(), new LucisWorldgenThreadFactory());
    private final ThreadLocal<Integer> worldgenWriteDepth = ThreadLocal.withInitial(() -> 0);

    public boolean enabled() {
        return LucisConfig.enabled;
    }

    public boolean shouldHandleWorldgen() {
        return LucisConfig.enabled && LucisConfig.enableWorldgen;
    }

    public LucisRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        return relighter.relightChunk(getter, chunk, LucisConfig.enableSky, LucisConfig.enableBlock, 1, 0);
    }

    public CompletableFuture<LucisRelightResult> relightChunkAsync(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        long submittedAt = System.nanoTime();
        return CompletableFuture.supplyAsync(() -> {
            long startedAt = System.nanoTime();
            LucisBenchmarkSupport.record("lucis.light_chunk.worker_wait", startedAt - submittedAt);
            LucisRelightResult result = relightChunk(getter, chunk, trustEdges);
            LucisBenchmarkSupport.record("lucis.light_chunk.worker_compute", System.nanoTime() - startedAt);
            return result;
        }, worldgenWorkers);
    }

    public LucisRelightResult relightAtBlock(LightChunkGetter getter, BlockPos pos) {
        LightChunk chunk = getter.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk instanceof ChunkAccess chunkAccess) {
            return relightChunk(getter, chunkAccess, true);
        }
        return new LucisRelightResult(new net.minecraft.world.level.ChunkPos(pos), java.util.List.of());
    }

    public boolean shouldHandleBlockChange(BlockPos pos) {
        return LucisConfig.enabled && LucisConfig.enableRuntime && pos != null && !isWorldgenWriteSuppressed();
    }

    public void enqueueBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!shouldHandleBlockChange(pos)) {
            return;
        }
        long startedAt = System.nanoTime();
        runtimeManager.enqueue(new BlockChangeRecord(pos.immutable(), oldState, newState));
        LucisBenchmarkSupport.record("lucis.enqueue_block_change", System.nanoTime() - startedAt);
    }

    public void tickRuntime(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter) {
        if (!LucisConfig.enabled || !LucisConfig.enableRuntime) {
            return;
        }
        runtimeManager.tick(lightEngine, getter, relighter, RUNTIME_REGION_CHUNKS, RUNTIME_HALO_CHUNKS,
                LucisConfig.enableSky, LucisConfig.enableBlock);
    }

    public List<LucisRelightResult> relightRegion(LightChunkGetter getter, net.minecraft.world.level.ChunkPos anchorChunk) {
        return relighter.relightRegion(getter, anchorChunk, LucisConfig.enableSky, LucisConfig.enableBlock,
                LucisConfig.regionChunks, LucisConfig.haloChunks);
    }

    public void shutdown() {
        runtimeManager.close();
        worldgenWorkers.shutdownNow();
    }

    public boolean hasPendingRuntimeWork() {
        return runtimeManager.hasPendingWork();
    }

    public void beginWorldgenWrite() {
        worldgenWriteDepth.set(worldgenWriteDepth.get() + 1);
    }

    public void endWorldgenWrite() {
        int depth = worldgenWriteDepth.get() - 1;
        if (depth <= 0) {
            worldgenWriteDepth.remove();
        } else {
            worldgenWriteDepth.set(depth);
        }
    }

    private boolean isWorldgenWriteSuppressed() {
        return worldgenWriteDepth.get() > 0;
    }

    private static int worldgenWorkerCount() {
        int available = Runtime.getRuntime().availableProcessors();
        int configured = Integer.getInteger("lucis.worldgenWorkers", 0);
        if (configured > 0) {
            return configured;
        }
        return Math.max(1, Math.min(available - 1, 6));
    }

    private static final class LucisWorldgenThreadFactory implements ThreadFactory {
        private final AtomicInteger index = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "lucis-worldgen-light-" + index.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
