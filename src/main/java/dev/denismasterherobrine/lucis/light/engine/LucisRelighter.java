package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.BlockChangeRecord;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.ArrayList;
import java.util.List;

public final class LucisRelighter {
    private final LucisRegionExtractor extractor;
    private final LightMaterialCache materialCache;
    private final LucisSkyLightEngine skyLightEngine = new LucisSkyLightEngine();
    private final LucisBlockLightEngine blockLightEngine = new LucisBlockLightEngine();
    private final LucisRemoveEngine removeEngine = new LucisRemoveEngine();
    private final LucisPublishEngine publishEngine = new LucisPublishEngine();

    public LucisRelighter(LightMaterialCache materialCache, LucisRegionExtractor extractor) {
        this.materialCache = materialCache;
        this.extractor = extractor;
    }

    public LucisRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean enableSky, boolean enableBlock,
                                           int regionChunks, int haloChunks) {
        ChunkPos chunkPos = chunk.getPos();
        RegionBounds bounds = RegionBounds.around(chunkPos, chunk.getHeightAccessorForGeneration(), regionChunks, haloChunks);
        long startedAt = LucisBenchmarkSupport.start();
        RegionLightData data = extractor.extract(getter, bounds);
        LucisBenchmarkSupport.recordSince("lucis.stage.worldgen.extract", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        removeEngine.applyChanges(data, List.of());
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
        removeEngine.applyChanges(data, List.of());
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

    public List<LucisRelightResult> relightRuntimeRegion(LightChunkGetter getter, RuntimeRegionState state, List<BlockChangeRecord> changes,
                                                      boolean enableSky, boolean enableBlock) {
        RegionLightData data = state.data();
        if (!state.initialized()) {
            LucisBenchmarkSupport.count("lucis.runtime.region.init");
            long startedAt = LucisBenchmarkSupport.start();
            extractor.populate(getter, data);
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.init.extract", startedAt);
            removeEngine.applyChanges(data, List.of());
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
            state.markInitialized();
            startedAt = LucisBenchmarkSupport.start();
            List<LucisRelightResult> results = publishEngine.publishRegion(data);
            LucisBenchmarkSupport.recordSince("lucis.stage.runtime.init.publish", startedAt);
            countPublished("lucis.runtime.init", results);
            data.clearDirty();
            return results;
        }

        if (changes.isEmpty()) {
            return List.of();
        }

        LucisBenchmarkSupport.count("lucis.runtime.region.incremental");
        LucisBenchmarkSupport.count("lucis.runtime.change.records", changes.size());
        data.clearDirty();
        long startedAt = LucisBenchmarkSupport.start();
        List<RuntimeLightChange> runtimeChanges = materializeChanges(getter.getLevel(), changes);
        LucisBenchmarkSupport.recordSince("lucis.stage.runtime.incremental.materialize", startedAt);
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
        return results;
    }

    private List<RuntimeLightChange> materializeChanges(BlockGetter level, List<BlockChangeRecord> changes) {
        List<RuntimeLightChange> runtimeChanges = new ArrayList<>(changes.size());
        for (BlockChangeRecord change : changes) {
            BlockPos pos = change.pos();
            LightMaterial oldMaterial = materialCache.lookup(level, change.oldState(), pos);
            LightMaterial newMaterial = materialCache.lookup(level, change.newState(), pos);
            runtimeChanges.add(new RuntimeLightChange(
                    pos.getX(), pos.getY(), pos.getZ(),
                    oldMaterial.opacity(), newMaterial.opacity(),
                    oldMaterial.emission(), newMaterial.emission()
            ));
        }
        return runtimeChanges;
    }

    private void applyMaterialChanges(RegionLightData data, List<RuntimeLightChange> changes) {
        for (RuntimeLightChange change : changes) {
            if (!data.isInside(change.worldX(), change.worldY(), change.worldZ())) {
                continue;
            }
            int index = data.index(change.worldX(), change.worldY(), change.worldZ());
            data.opacity[index] = change.newOpacity();
            data.emission[index] = change.newEmission();
            if (change.newOpacity() != 0 || change.newEmission() != 0) {
                data.markNonEmpty(change.worldX(), change.worldY() >> 4, change.worldZ());
            }
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
