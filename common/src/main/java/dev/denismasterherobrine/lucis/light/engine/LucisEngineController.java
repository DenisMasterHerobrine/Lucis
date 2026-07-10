package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.compat.LucisCompat;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.runtime.BlockChangeRecord;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.LucisRuntimeManager;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class LucisEngineController {
    private static final int RUNTIME_REGION_CHUNKS = 1;
    private static final int RUNTIME_HALO_CHUNKS = 0;

    private final LightMaterialCache materialCache = new LightMaterialCache();
    private final LucisRelighter relighter = new LucisRelighter(materialCache, new LucisRegionExtractor(materialCache));
    private final LucisRuntimeManager runtimeManager = new LucisRuntimeManager();
    private final ExecutorService worldgenWorkers = Executors.newFixedThreadPool(worldgenWorkerCount(), new LucisWorldgenThreadFactory());
    private final ThreadLocal<Integer> worldgenWriteDepth = ThreadLocal.withInitial(() -> 0);
    private final AtomicLong runtimeBackpressureUntilNanos = new AtomicLong();

    public boolean enabled() {
        return LucisConfig.enabled;
    }

    public boolean shouldHandleWorldgen(LightChunkGetter getter, ChunkAccess chunk) {
        return enabled() && LucisConfig.enableWorldgen && chunk != null && !isSablePlotChunk(getter, chunk.getPos());
    }

    public boolean shouldHandleLightInitialization(LightChunkGetter getter, ChunkAccess chunk) {
        return enabled() && chunk != null && !isSablePlotChunk(getter, chunk.getPos());
    }

    public LucisRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        return relighter.relightChunk(getter, chunk, LucisConfig.enableSky, LucisConfig.enableBlock, 1, 1);
    }

    public CompletableFuture<LucisRelightResult> relightChunkAsync(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        long submittedAt = LucisBenchmarkSupport.start();
        return CompletableFuture.supplyAsync(() -> {
            long startedAt = LucisBenchmarkSupport.start();
            if (startedAt != 0L && submittedAt != 0L) {
                LucisBenchmarkSupport.record("lucis.light_chunk.worker_wait", startedAt - submittedAt);
            }
            LucisRelightResult result = relightChunk(getter, chunk, trustEdges);
            LucisBenchmarkSupport.recordSince("lucis.light_chunk.worker_compute", startedAt);
            return result;
        }, worldgenWorkers);
    }

    public LucisRelightResult relightAtBlock(LightChunkGetter getter, BlockPos pos) {
        if (!shouldHandleBlockChange(getter, pos)) {
            return new LucisRelightResult(ChunkPos.containing(pos), java.util.List.of());
        }

        LightChunk chunk = getter.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk instanceof ChunkAccess chunkAccess) {
            return relightChunk(getter, chunkAccess, true);
        }
        return new LucisRelightResult(ChunkPos.containing(pos), java.util.List.of());
    }

    public boolean shouldHandleBlockChange(BlockPos pos) {
        return enabled()
                && LucisConfig.enableRuntime
                && pos != null
                && !isWorldgenWriteSuppressed()
                && !runtimeBackpressureActive()
                && runtimeManager.canAcceptMoreWork();
    }

    public boolean shouldHandleBlockChange(LightChunkGetter getter, BlockPos pos) {
        return shouldHandleBlockChange(pos) && !LucisCompat.isSablePlotChunk(getter,
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public boolean shouldHandleBlockChange(Level level, BlockPos pos) {
        return shouldHandleBlockChange(pos) && !LucisCompat.isSablePlotChunk(level,
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ()));
    }

    public void enqueueBlockChange(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!shouldHandleBlockChange(pos)) {
            return;
        }
        long startedAt = LucisBenchmarkSupport.start();
        if (!runtimeManager.enqueue(new BlockChangeRecord(pos.getX(), pos.getY(), pos.getZ(), oldState, newState))) {
            activateRuntimeBackpressure();
        }
        LucisBenchmarkSupport.recordSince("lucis.enqueue_block_change", startedAt);
    }

    public boolean enqueueBlockChange(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!shouldHandleBlockChange(level, pos)) {
            return false;
        }
        long startedAt = LucisBenchmarkSupport.start();
        if (!runtimeManager.enqueue(new BlockChangeRecord(pos.getX(), pos.getY(), pos.getZ(), oldState, newState))) {
            activateRuntimeBackpressure();
            LucisBenchmarkSupport.recordSince("lucis.enqueue_block_change", startedAt);
            return false;
        }
        LucisBenchmarkSupport.recordSince("lucis.enqueue_block_change", startedAt);
        return true;
    }

    public void tickRuntime(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter) {
        if (!enabled() || !LucisConfig.enableRuntime) {
            return;
        }
        runtimeManager.tick(lightEngine, getter, relighter, RUNTIME_REGION_CHUNKS, RUNTIME_HALO_CHUNKS,
                LucisConfig.enableSky, LucisConfig.enableBlock);
    }

    public void kickRuntime(ThreadedLevelLightEngine lightEngine, LightChunkGetter getter) {
        if (!enabled() || !LucisConfig.enableRuntime) {
            return;
        }
        runtimeManager.kick(lightEngine, getter, relighter, RUNTIME_REGION_CHUNKS, RUNTIME_HALO_CHUNKS,
                LucisConfig.enableSky, LucisConfig.enableBlock);
    }

    public List<LucisRelightResult> relightRegion(LightChunkGetter getter, ChunkPos anchorChunk) {
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

    private boolean runtimeBackpressureActive() {
        return System.nanoTime() < runtimeBackpressureUntilNanos.get();
    }

    private void activateRuntimeBackpressure() {
        LucisBenchmarkSupport.count("lucis.runtime.backpressure");
        runtimeBackpressureUntilNanos.set(System.nanoTime() + 250_000_000L);
    }

    private boolean isSablePlotChunk(LightChunkGetter getter, ChunkPos chunkPos) {
        return LucisCompat.isSablePlotChunk(getter, chunkPos);
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
