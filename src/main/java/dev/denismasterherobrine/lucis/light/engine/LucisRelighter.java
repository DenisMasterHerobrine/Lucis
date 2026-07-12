package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.BlockChangeRecord;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeRegionBatch;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.List;

public final class LucisRelighter {
    private final LucisRegionExtractor extractor;
    private final LightMaterialCache materialCache;
    private final LucisSkyLightEngine skyLightEngine = new LucisSkyLightEngine();
    private final LucisBlockLightEngine blockLightEngine = new LucisBlockLightEngine();
    private final LucisPublishEngine publishEngine = new LucisPublishEngine();
    private final ThreadLocal<ArrayList<RuntimeLightChange>> runtimeChangeBuffers = ThreadLocal.withInitial(() -> new ArrayList<>(256));
    private final ThreadLocal<BlockPos.MutableBlockPos> runtimeMaterialPos = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    public LucisRelighter(LightMaterialCache materialCache, LucisRegionExtractor extractor) {
        this.materialCache = materialCache;
        this.extractor = extractor;
    }

    public LucisRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean enableSky, boolean enableBlock,
                                           int regionChunks, int haloChunks) {
        ChunkPos chunkPos = chunk.getPos();
        long startedAt = LucisBenchmarkSupport.start();
        RegionLightData data = extractChunkData(getter, chunk, regionChunks, haloChunks);
        LucisBenchmarkSupport.recordSince("lucis.stage.worldgen.extract", startedAt);
        return relightPreparedChunk(chunkPos, data, enableSky, enableBlock);
    }

    public RegionLightData extractChunkData(LightChunkGetter getter, ChunkAccess chunk, int regionChunks, int haloChunks) {
        RegionBounds bounds = RegionBounds.around(chunk.getPos(), chunk.getHeightAccessorForGeneration(), regionChunks, haloChunks);
        return extractor.extract(getter, bounds, chunk);
    }

    public LucisRelightResult relightPreparedChunk(ChunkPos chunkPos, RegionLightData data, boolean enableSky, boolean enableBlock) {
        long startedAt = LucisBenchmarkSupport.start();
        if (enableSky) {
            skyLightEngine.compute(data);
        }
        LucisBenchmarkSupport.recordSince("lucis.stage.worldgen.sky", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        if (enableBlock) {
            blockLightEngine.compute(data);
        }
        LucisBenchmarkSupport.recordSince("lucis.stage.worldgen.block", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        LucisRelightResult result = publishEngine.publishChunk(chunkPos, data);
        LucisBenchmarkSupport.recordSince("lucis.stage.worldgen.publish", startedAt);
        LucisBenchmarkSupport.count("lucis.worldgen.sections", result.sections().size());
        return result;
    }

    public List<LucisRelightResult> relightRegion(LightChunkGetter getter, ChunkPos anchorChunk, boolean enableSky, boolean enableBlock,
                                                  int regionChunks, int haloChunks) {
        RegionBounds bounds = RegionBounds.around(anchorChunk, getter.getLevel(), regionChunks, haloChunks);
        long startedAt = LucisBenchmarkSupport.start();
        RegionLightData data = extractor.extract(getter, bounds);
        LucisBenchmarkSupport.recordSince("lucis.stage.region.extract", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        if (enableSky) {
            skyLightEngine.compute(data);
        }
        LucisBenchmarkSupport.recordSince("lucis.stage.region.sky", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        if (enableBlock) {
            blockLightEngine.compute(data);
        }
        LucisBenchmarkSupport.recordSince("lucis.stage.region.block", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        List<LucisRelightResult> results = publishEngine.publishRegion(data);
        LucisBenchmarkSupport.recordSince("lucis.stage.region.publish", startedAt);
        countPublished("lucis.region", results);
        return results;
    }

    public List<LucisRelightResult> relightRuntimeRegion(LightChunkGetter getter, RuntimeRegionState state, LightChunk coreChunk,
                                                        RuntimeRegionBatch batch, boolean enableSky, boolean enableBlock) {
        RegionLightData data = state.data();
        if (!state.initializedFor(coreChunk)) {
            LucisBenchmarkSupport.count(state.initialized()
                    ? "lucis.runtime.region.reload"
                    : "lucis.runtime.region.init");
            long startedAt = LucisBenchmarkSupport.start();
            extractor.populate(getter, data, coreChunk);
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.init.extract", startedAt);
            startedAt = LucisBenchmarkSupport.start();
            if (enableSky) {
                skyLightEngine.compute(data);
            }
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.init.sky", startedAt);
            startedAt = LucisBenchmarkSupport.start();
            if (enableBlock) {
                blockLightEngine.compute(data);
            }
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.init.block", startedAt);
            state.markInitialized(coreChunk);
            startedAt = LucisBenchmarkSupport.start();
            List<LucisRelightResult> results = publishEngine.publishRegion(data);
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.init.publish", startedAt);
            countPublished("lucis.runtime.init", results);
            data.clearDirty();
            return results;
        }

        if (batch.fullRelight()) {
            LucisBenchmarkSupport.count("lucis.runtime.region.fullRelight");
            long startedAt = LucisBenchmarkSupport.start();
            extractor.populate(getter, data, coreChunk);
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.full.extract", startedAt);
            startedAt = LucisBenchmarkSupport.start();
            if (enableSky) {
                skyLightEngine.compute(data);
            }
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.full.sky", startedAt);
            startedAt = LucisBenchmarkSupport.start();
            if (enableBlock) {
                blockLightEngine.compute(data);
            }
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.full.block", startedAt);
            startedAt = LucisBenchmarkSupport.start();
            List<LucisRelightResult> results = publishEngine.publishRegion(data);
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.full.publish", startedAt);
            countPublished("lucis.runtime.full", results);
            data.clearDirty();
            return results;
        }

        List<BlockChangeRecord> changes = batch.changes();

        if (changes.isEmpty()) {
            return List.of();
        }

        LucisBenchmarkSupport.count("lucis.runtime.region.incremental");
        LucisBenchmarkSupport.count("lucis.runtime.change.records", changes.size());
        data.clearDirty();
        long startedAt = LucisBenchmarkSupport.start();
        ArrayList<RuntimeLightChange> runtimeChanges = runtimeChangeBuffers.get();
        runtimeChanges.clear();
        materializeChanges(getter.getLevel(), changes, runtimeChanges);
        LucisBenchmarkSupport.recordSince("lucis.stage.runtime.incremental.materialize", startedAt);
        if (runtimeChanges.isEmpty()) {
            LucisBenchmarkSupport.count("lucis.runtime.change.light_noop", changes.size());
            runtimeChanges.clear();
            return List.of();
        }
        startedAt = LucisBenchmarkSupport.start();
        applyMaterialChanges(data, runtimeChanges);
        LucisBenchmarkSupport.recordSince("lucis.stage.runtime.incremental.materials", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        boolean opacityChanged = hasOpacityChange(runtimeChanges);
        LucisBenchmarkSupport.count(opacityChanged ? "lucis.runtime.opacity.changed" : "lucis.runtime.opacity.unchanged");
        if (enableSky && opacityChanged) {
            skyLightEngine.applyRuntimeChanges(data, runtimeChanges);
        }
        LucisBenchmarkSupport.recordSince("lucis.stage.runtime.incremental.sky", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        boolean emissionChanged = hasEmissionChange(runtimeChanges);
        LucisBenchmarkSupport.count(emissionChanged ? "lucis.runtime.emission.changed" : "lucis.runtime.emission.unchanged");
        if (enableBlock && emissionChanged) {
            if (runtimeChanges.size() > 1) {
                LucisBenchmarkSupport.count("lucis.runtime.block.fastBatch");
                blockLightEngine.applyRuntimeChangesFast(data, runtimeChanges);
            } else {
                blockLightEngine.applyRuntimeChanges(data, runtimeChanges);
            }
        } else if (enableBlock) {
            LucisBenchmarkSupport.count("lucis.runtime.block.skipped.noEmissionChange");
        }
        LucisBenchmarkSupport.recordSince("lucis.stage.runtime.incremental.block", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        List<LucisRelightResult> results = publishEngine.publishRegion(data);
        LucisBenchmarkSupport.recordSince("lucis.stage.runtime.incremental.publish", startedAt);
        countPublished("lucis.runtime.incremental", results);
        data.clearDirty();
        runtimeChanges.clear();
        return results;
    }

    private void materializeChanges(BlockGetter level, List<BlockChangeRecord> changes, List<RuntimeLightChange> runtimeChanges) {
        BlockPos.MutableBlockPos pos = runtimeMaterialPos.get();
        for (BlockChangeRecord change : changes) {
            pos.set(change.x(), change.y(), change.z());
            int oldMaterial = materialCache.lookupLight(level, change.oldState(), pos);
            int newMaterial = materialCache.lookupLight(level, change.newState(), pos);
            if (LightMaterial.hasSameLight(oldMaterial, newMaterial)) {
                continue;
            }
            runtimeChanges.add(new RuntimeLightChange(
                    change.x(), change.y(), change.z(),
                    LightMaterial.opacity(oldMaterial), LightMaterial.opacity(newMaterial),
                    LightMaterial.emission(oldMaterial), LightMaterial.emission(newMaterial)
            ));
        }
    }

    private void applyMaterialChanges(RegionLightData data, List<RuntimeLightChange> changes) {
        RegionBounds bounds = data.bounds;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int minBuildY = bounds.minBuildY();
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();
        for (RuntimeLightChange change : changes) {
            int localX = change.worldX() - minBlockX;
            int localY = change.worldY() - minBuildY;
            int localZ = change.worldZ() - minBlockZ;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth || localY < 0 || localY >= height) {
                continue;
            }
            int index = data.localIndex(localX, localY, localZ);
            data.opacity[index] = change.newOpacity();
            data.emission[index] = change.newEmission();
        }
    }

    private boolean hasOpacityChange(List<RuntimeLightChange> changes) {
        for (RuntimeLightChange change : changes) {
            if ((change.oldOpacity() & 0xF) != (change.newOpacity() & 0xF)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEmissionChange(List<RuntimeLightChange> changes) {
        for (RuntimeLightChange change : changes) {
            if ((change.oldEmission() & 0xF) != (change.newEmission() & 0xF)) {
                return true;
            }
        }
        return false;
    }

    private void countPublished(String prefix, List<LucisRelightResult> results) {
        int sections = 0;
        for (LucisRelightResult result : results) {
            sections += result.sections().size();
        }
        LucisBenchmarkSupport.count(prefix + ".results", results.size());
        LucisBenchmarkSupport.count(prefix + ".sections", sections);
    }
}
