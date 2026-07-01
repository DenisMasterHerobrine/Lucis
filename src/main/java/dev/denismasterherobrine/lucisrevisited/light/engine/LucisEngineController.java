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

import java.util.List;

public final class LucisEngineController {
    private final LightMaterialCache materialCache = new LightMaterialCache();
    private final LucisRelighter relighter = new LucisRelighter(materialCache, new LucisRegionExtractor(materialCache));
    private final LucisRuntimeManager runtimeManager = new LucisRuntimeManager();

    public boolean enabled() {
        return LucisConfig.enabled;
    }

    public boolean shouldHandleWorldgen() {
        return LucisConfig.enabled && LucisConfig.enableWorldgen;
    }

    public LucisRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean trustEdges) {
        return relighter.relightChunk(getter, chunk, LucisConfig.enableSky, LucisConfig.enableBlock,
                LucisConfig.regionChunks, LucisConfig.haloChunks);
    }

    public LucisRelightResult relightAtBlock(LightChunkGetter getter, BlockPos pos) {
        LightChunk chunk = getter.getChunkForLighting(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk instanceof ChunkAccess chunkAccess) {
            return relightChunk(getter, chunkAccess, true);
        }
        return new LucisRelightResult(new net.minecraft.world.level.ChunkPos(pos), java.util.List.of());
    }

    public boolean shouldHandleBlockChange(BlockPos pos) {
        return LucisConfig.enabled && LucisConfig.enableRuntime && pos != null;
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
        runtimeManager.tick(lightEngine, getter, relighter, LucisConfig.regionChunks, LucisConfig.haloChunks,
                LucisConfig.enableSky, LucisConfig.enableBlock);
    }

    public List<LucisRelightResult> relightRegion(LightChunkGetter getter, net.minecraft.world.level.ChunkPos anchorChunk) {
        return relighter.relightRegion(getter, anchorChunk, LucisConfig.enableSky, LucisConfig.enableBlock,
                LucisConfig.regionChunks, LucisConfig.haloChunks);
    }

    public void shutdown() {
        runtimeManager.close();
    }

    public boolean hasPendingRuntimeWork() {
        return runtimeManager.hasPendingWork();
    }
}
