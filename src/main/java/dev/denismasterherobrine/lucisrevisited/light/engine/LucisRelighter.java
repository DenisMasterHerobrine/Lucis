package dev.denismasterherobrine.lucisrevisited.light.engine;

import dev.denismasterherobrine.lucisrevisited.light.LightMaterial;
import dev.denismasterherobrine.lucisrevisited.light.LightMaterialCache;
import dev.denismasterherobrine.lucisrevisited.light.region.BoundaryDelta;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionBounds;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionLightData;
import dev.denismasterherobrine.lucisrevisited.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucisrevisited.light.runtime.BoundaryCellChange;
import dev.denismasterherobrine.lucisrevisited.light.runtime.BlockChangeRecord;
import dev.denismasterherobrine.lucisrevisited.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucisrevisited.test.LucisBenchmarkSupport;
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
    private final LucisBoundaryEngine boundaryEngine = new LucisBoundaryEngine();
    private final LucisPublishEngine publishEngine = new LucisPublishEngine();

    public LucisRelighter(LightMaterialCache materialCache, LucisRegionExtractor extractor) {
        this.materialCache = materialCache;
        this.extractor = extractor;
    }

    public LucisRelightResult relightChunk(LightChunkGetter getter, ChunkAccess chunk, boolean enableSky, boolean enableBlock,
                                           int regionChunks, int haloChunks) {
        ChunkPos chunkPos = chunk.getPos();
        RegionBounds bounds = RegionBounds.around(chunkPos, chunk.getHeightAccessorForGeneration(), regionChunks, haloChunks);
        long startedAt = System.nanoTime();
        RegionLightData data = extractor.extract(getter, bounds);
        LucisBenchmarkSupport.record("lucis.stage.worldgen.extract", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        removeEngine.applyChanges(data, List.of());
        if (enableSky) {
            skyLightEngine.compute(data);
        }
        LucisBenchmarkSupport.record("lucis.stage.worldgen.sky", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        if (enableBlock) {
            blockLightEngine.compute(data);
        }
        LucisBenchmarkSupport.record("lucis.stage.worldgen.block", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        LucisRelightResult result = publishEngine.publishChunk(chunkPos, data);
        LucisBenchmarkSupport.record("lucis.stage.worldgen.publish", System.nanoTime() - startedAt);
        LucisBenchmarkSupport.count("lucis.worldgen.sections", result.sections().size());
        return result;
    }

    public List<LucisRelightResult> relightRegion(LightChunkGetter getter, ChunkPos anchorChunk, boolean enableSky, boolean enableBlock,
                                                  int regionChunks, int haloChunks) {
        RegionBounds bounds = RegionBounds.around(anchorChunk, getter.getLevel(), regionChunks, haloChunks);
        long startedAt = System.nanoTime();
        RegionLightData data = extractor.extract(getter, bounds);
        LucisBenchmarkSupport.record("lucis.stage.region.extract", System.nanoTime() - startedAt);
        removeEngine.applyChanges(data, List.of());
        startedAt = System.nanoTime();
        if (enableSky) {
            skyLightEngine.compute(data);
        }
        LucisBenchmarkSupport.record("lucis.stage.region.sky", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        if (enableBlock) {
            blockLightEngine.compute(data);
        }
        LucisBenchmarkSupport.record("lucis.stage.region.block", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        List<LucisRelightResult> results = publishEngine.publishRegion(data);
        LucisBenchmarkSupport.record("lucis.stage.region.publish", System.nanoTime() - startedAt);
        countPublished("lucis.region", results);
        return results;
    }

    public List<LucisRelightResult> relightRuntimeRegion(LightChunkGetter getter, RuntimeRegionState state, List<BlockChangeRecord> changes,
                                                      boolean enableSky, boolean enableBlock) {
        RegionLightData data = state.data();
        if (!state.initialized()) {
            LucisBenchmarkSupport.count("lucis.runtime.region.init");
            long startedAt = System.nanoTime();
            extractor.populate(getter, data);
            LucisBenchmarkSupport.record("lucis.stage.runtime.init.extract", System.nanoTime() - startedAt);
            removeEngine.applyChanges(data, List.of());
            startedAt = System.nanoTime();
            if (enableSky) {
                skyLightEngine.compute(data);
            }
            LucisBenchmarkSupport.record("lucis.stage.runtime.init.sky", System.nanoTime() - startedAt);
            startedAt = System.nanoTime();
            if (enableBlock) {
                blockLightEngine.compute(data);
            }
            LucisBenchmarkSupport.record("lucis.stage.runtime.init.block", System.nanoTime() - startedAt);
            startedAt = System.nanoTime();
            applyPendingBoundary(state, enableSky, enableBlock);
            LucisBenchmarkSupport.record("lucis.stage.runtime.init.boundary", System.nanoTime() - startedAt);
            state.markInitialized();
            startedAt = System.nanoTime();
            List<LucisRelightResult> results = publishEngine.publishRegion(data);
            LucisBenchmarkSupport.record("lucis.stage.runtime.init.publish", System.nanoTime() - startedAt);
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
        long startedAt = System.nanoTime();
        List<RuntimeLightChange> runtimeChanges = materializeChanges(getter.getLevel(), changes);
        LucisBenchmarkSupport.record("lucis.stage.runtime.incremental.materialize", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        applyMaterialChanges(data, runtimeChanges);
        LucisBenchmarkSupport.record("lucis.stage.runtime.incremental.materials", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        boolean opacityChanged = hasOpacityChange(runtimeChanges);
        LucisBenchmarkSupport.count(opacityChanged ? "lucis.runtime.opacity.changed" : "lucis.runtime.opacity.unchanged");
        if (enableSky && opacityChanged) {
            skyLightEngine.applyRuntimeChanges(data, runtimeChanges);
        }
        LucisBenchmarkSupport.record("lucis.stage.runtime.incremental.sky", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
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
        LucisBenchmarkSupport.record("lucis.stage.runtime.incremental.block", System.nanoTime() - startedAt);
        startedAt = System.nanoTime();
        List<LucisRelightResult> results = publishEngine.publishRegion(data);
        LucisBenchmarkSupport.record("lucis.stage.runtime.incremental.publish", System.nanoTime() - startedAt);
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

    public List<BoundaryDelta> captureBoundaryDeltas(RuntimeRegionState state) {
        return boundaryEngine.capture(state.data());
    }

    public void applyBoundaryDelta(RuntimeRegionState state, BoundaryDelta delta) {
        state.putBoundarySnapshot(boundaryEngine.toSnapshot(delta));
    }

    public List<LucisRelightResult> applyBoundaryUpdates(RuntimeRegionState state, boolean enableSky, boolean enableBlock) {
        if (!state.initialized()) {
            return List.of();
        }
        return List.of();
    }

    public List<LucisRelightResult> refreshRuntimeRegion(LightChunkGetter getter, RuntimeRegionState state,
                                                         boolean enableSky, boolean enableBlock) {
        if (!state.initialized()) {
            return List.of();
        }

        RegionLightData data = state.data();
        extractor.populate(getter, data);
        removeEngine.applyChanges(data, List.of());
        if (enableSky) {
            skyLightEngine.compute(data);
        }
        if (enableBlock) {
            blockLightEngine.compute(data);
        }
        return publishEngine.publishRegion(data);
    }

    private void applyPendingBoundary(RuntimeRegionState state, boolean enableSky, boolean enableBlock) {
        List<BoundaryCellChange> changes = boundaryEngine.apply(state);
        if (changes.isEmpty()) {
            return;
        }
        if (enableBlock) {
            blockLightEngine.applyBoundaryChanges(state.data(), changes);
        }
        if (enableSky) {
            skyLightEngine.applyBoundaryChanges(state.data(), changes);
        }
    }
}
