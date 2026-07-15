package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LucisBlockLightEngineTest {
    @Test
    void fastBatchPropagatesTorchLightBeyondImmediateNeighbors() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
        RegionLightData data = new RegionLightData(bounds);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(2);
        changes.addLight(data.localIndex(8, 8, 8), LightMaterial.packLight(0, 0), LightMaterial.packLight(0, 14));
        changes.addLight(data.localIndex(2, 2, 2), LightMaterial.packLight(0, 0), LightMaterial.packLight(0, 14));

        new LucisBlockLightEngine().applyRuntimeChangesFast(data, changes);

        assertEquals(14, data.blockLight[data.localIndex(8, 8, 8)] & 0xF);
        assertEquals(13, data.blockLight[data.localIndex(9, 8, 8)] & 0xF);
        assertEquals(9, data.blockLight[data.localIndex(13, 8, 8)] & 0xF);
    }

    @Test
    void runtimeNonFastChangesMatchFullBlockRecomputeForRandomRegions() {
        Random random = new Random(26012116L);
        LucisBlockLightEngine engine = new LucisBlockLightEngine();
        for (int iteration = 0; iteration < 128; iteration++) {
            RegionLightData base = randomBlockRegion(random);
            engine.compute(base);

            RegionLightData incremental = cloneMaterials(base);
            System.arraycopy(base.blockLight, 0, incremental.blockLight, 0, base.blockLight.length);
            RuntimeLightChangeBuffer changes = randomNonFastChanges(random, incremental, 1 + random.nextInt(8));

            engine.applyRuntimeChanges(incremental, changes);

            RegionLightData full = cloneMaterials(incremental);
            engine.compute(full);
            assertSameBlockLight(full, incremental, iteration);
        }
    }

    private static RegionLightData randomBlockRegion(Random random) {
        int width = 16;
        int height = 32;
        RegionLightData data = new RegionLightData(new RegionBounds(0, 0, 1, 0, width, width,
                0, height, 0, height / 16, height, width * width, width * width * height));
        for (int index = 0; index < data.bounds.volume(); index++) {
            data.opacity[index] = (byte) randomOpacity(random);
            data.emission[index] = (byte) (random.nextInt(100) < 4 ? 1 + random.nextInt(15) : 0);
        }
        return data;
    }

    private static RegionLightData cloneMaterials(RegionLightData source) {
        int width = source.bounds.widthBlocks();
        int height = source.bounds.heightBlocks();
        RegionLightData copy = new RegionLightData(new RegionBounds(source.bounds.originChunkX(), source.bounds.originChunkZ(),
                source.bounds.regionChunks(), source.bounds.haloChunks(), width, source.bounds.depthBlocks(),
                source.bounds.minBuildY(), source.bounds.maxBuildY(), source.bounds.minSectionY(), source.bounds.sectionCount(),
                height, source.bounds.area(), source.bounds.volume()));
        System.arraycopy(source.opacity, 0, copy.opacity, 0, source.opacity.length);
        System.arraycopy(source.emission, 0, copy.emission, 0, source.emission.length);
        return copy;
    }

    private static RuntimeLightChangeBuffer randomNonFastChanges(Random random, RegionLightData data, int targetChanges) {
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(targetChanges);
        HashSet<Integer> used = new HashSet<>();
        int attempts = 0;
        while (changes.size() < targetChanges && attempts++ < 1024) {
            int index = random.nextInt(data.bounds.volume());
            if (!used.add(index)) {
                continue;
            }
            int oldOpacity = data.opacity[index] & 0xF;
            int oldEmission = data.emission[index] & 0xF;
            int newOpacity = randomOpacity(random);
            int newEmission = random.nextInt(100) < 10 ? 1 + random.nextInt(15) : 0;
            if (newOpacity == oldOpacity && newEmission == oldEmission) {
                continue;
            }
            if (newOpacity == oldOpacity && newEmission >= oldEmission) {
                newOpacity = oldOpacity == 0 ? 1 : 0;
            }
            data.opacity[index] = (byte) newOpacity;
            data.emission[index] = (byte) newEmission;
            changes.addLight(index, LightMaterial.packLight(oldOpacity, oldEmission),
                    LightMaterial.packLight(newOpacity, newEmission));
        }
        return changes;
    }

    private static int randomOpacity(Random random) {
        int roll = random.nextInt(100);
        if (roll < 72) {
            return 0;
        }
        if (roll < 85) {
            return 1;
        }
        return random.nextInt(16);
    }

    private static void assertSameBlockLight(RegionLightData expected, RegionLightData actual, int iteration) {
        int width = expected.bounds.widthBlocks();
        int area = expected.bounds.area();
        for (int index = 0; index < expected.blockLight.length; index++) {
            int expectedLight = expected.blockLight[index] & 0xF;
            int actualLight = actual.blockLight[index] & 0xF;
            if (expectedLight != actualLight) {
                int y = index / area;
                int rem = index - y * area;
                int z = rem / width;
                int x = rem - z * width;
                assertEquals(expectedLight, actualLight,
                        "block light mismatch in iteration " + iteration + " at " + x + "," + y + "," + z);
            }
        }
    }
}
