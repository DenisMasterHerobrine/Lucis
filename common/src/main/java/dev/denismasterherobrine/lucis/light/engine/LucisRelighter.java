package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.LightMaterialCache;
import dev.denismasterherobrine.lucis.light.region.RegionChunkSnapshot;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.BlockChangeRecord;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeRegionBatch;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;

import java.util.List;

public final class LucisRelighter {
    private final LucisRegionExtractor extractor;
    private final LightMaterialCache materialCache;
    private final LucisSkyLightEngine skyLightEngine = new LucisSkyLightEngine();
    private final LucisBlockLightEngine blockLightEngine = new LucisBlockLightEngine();
    private final LucisPublishEngine publishEngine = new LucisPublishEngine();
    private final ThreadLocal<RuntimeLightChangeBuffer> runtimeChangeBuffers = ThreadLocal.withInitial(() -> new RuntimeLightChangeBuffer(256));
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

    public LucisRelightResult relightChunkSnapshot(ChunkPos chunkPos, RegionChunkSnapshot snapshot,
                                                  boolean enableSky, boolean enableBlock) {
        long startedAt = LucisBenchmarkSupport.start();
        RegionLightData data = new RegionLightData(snapshot.bounds());
        extractor.populateFromSnapshot(snapshot, data, snapshot);
        LucisBenchmarkSupport.recordSince("lucis.stage.worldgen.extract_snapshot", startedAt);
        startedAt = LucisBenchmarkSupport.start();
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
        RuntimeLightChangeBuffer runtimeChanges = runtimeChangeBuffers.get();
        runtimeChanges.clear();
        materializeChanges(getter.getLevel(), data, changes, runtimeChanges);
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
        boolean opacityChanged = runtimeChanges.hasOpacityChange();
        boolean skyChanged = runtimeChanges.hasSkyChange();
        LucisBenchmarkSupport.count(opacityChanged ? "lucis.runtime.opacity.changed" : "lucis.runtime.opacity.unchanged");
        LucisBenchmarkSupport.count(skyChanged ? "lucis.runtime.sky.changed" : "lucis.runtime.sky.unchanged");
        if (enableSky && skyChanged) {
            skyLightEngine.applyRuntimeChanges(data, runtimeChanges);
        }
        LucisBenchmarkSupport.recordSince("lucis.stage.runtime.incremental.sky", startedAt);
        startedAt = LucisBenchmarkSupport.start();
        boolean emissionChanged = runtimeChanges.hasEmissionChange();
        LucisBenchmarkSupport.count(emissionChanged ? "lucis.runtime.emission.changed" : "lucis.runtime.emission.unchanged");
        boolean blockAffected = emissionChanged || (opacityChanged && hasNearbyBlockLight(data, runtimeChanges));
        if (enableBlock && blockAffected) {
            if (runtimeChanges.size() > 1 && runtimeChanges.blockFastEligible()) {
                LucisBenchmarkSupport.count("lucis.runtime.block.fastBatch");
                blockLightEngine.applyRuntimeChangesFastEligible(data, runtimeChanges);
            } else {
                blockLightEngine.applyRuntimeChanges(data, runtimeChanges);
            }
        } else if (enableBlock) {
            LucisBenchmarkSupport.count(blockAffected
                    ? "lucis.runtime.block.skipped.disabled"
                    : "lucis.runtime.block.skipped.noAffectedLight");
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

    private void materializeChanges(BlockGetter level, RegionLightData data, List<BlockChangeRecord> changes, RuntimeLightChangeBuffer runtimeChanges) {
        BlockPos.MutableBlockPos pos = runtimeMaterialPos.get();
        RegionBounds bounds = data.bounds;
        int minBlockX = bounds.minBlockX();
        int minBlockZ = bounds.minBlockZ();
        int minBuildY = bounds.minBuildY();
        int width = bounds.widthBlocks();
        int depth = bounds.depthBlocks();
        int height = bounds.heightBlocks();
        for (BlockChangeRecord change : changes) {
            int localX = change.x() - minBlockX;
            int localY = change.y() - minBuildY;
            int localZ = change.z() - minBlockZ;
            if (localX < 0 || localX >= width || localZ < 0 || localZ >= depth || localY < 0 || localY >= height) {
                continue;
            }
            pos.set(change.x(), change.y(), change.z());
            int oldMaterial = materialCache.lookupLight(level, change.oldState(), pos);
            int newMaterial = materialCache.lookupLight(level, change.newState(), pos);
            if (LightMaterial.hasSameRuntimeProperties(oldMaterial, newMaterial)) {
                continue;
            }
            runtimeChanges.add(data.localIndex(localX, localY, localZ), oldMaterial, newMaterial);
        }
    }

    private void applyMaterialChanges(RegionLightData data, RuntimeLightChangeBuffer changes) {
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            int index = RuntimeLightChangeBuffer.localIndex(change);
            int newLight = RuntimeLightChangeBuffer.newLight(change);
            data.opacity[index] = (byte) (newLight & 0xF);
            data.emission[index] = (byte) ((newLight >>> 4) & 0xF);
        }
    }

    private boolean hasNearbyBlockLight(RegionLightData data, RuntimeLightChangeBuffer changes) {
        int width = data.bounds.widthBlocks();
        int depth = data.bounds.depthBlocks();
        int height = data.bounds.heightBlocks();
        int area = data.bounds.area();
        for (int i = 0; i < changes.size(); i++) {
            long change = changes.get(i);
            if (RuntimeLightChangeBuffer.oldOpacity(change) == RuntimeLightChangeBuffer.newOpacity(change)) {
                continue;
            }
            int index = RuntimeLightChangeBuffer.localIndex(change);
            if ((data.blockLight[index] & 0xF) != 0) {
                return true;
            }
            int y = index / area;
            int rem = index - y * area;
            int z = rem / width;
            int x = rem - z * width;
            if (x + 1 < width && (data.blockLight[index + data.offsetPosX] & 0xF) != 0) return true;
            if (x > 0 && (data.blockLight[index + data.offsetNegX] & 0xF) != 0) return true;
            if (z + 1 < depth && (data.blockLight[index + data.offsetPosZ] & 0xF) != 0) return true;
            if (z > 0 && (data.blockLight[index + data.offsetNegZ] & 0xF) != 0) return true;
            if (y + 1 < height && (data.blockLight[index + data.offsetPosY] & 0xF) != 0) return true;
            if (y > 0 && (data.blockLight[index + data.offsetNegY] & 0xF) != 0) return true;
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
