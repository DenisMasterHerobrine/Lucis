package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LucisSkyLightEngineTest {
    private static final int WIDTH = 48;
    private static final int HEIGHT = 32;
    private static final int ROOF_Y = 24;
    private static final int ROOF_MIN = 8;
    private static final int ROOF_MAX = 39;
    private static final int CENTER = 24;
    private static final int DEEP_Y = 10;
    private static final int LEAF_Y = 12;

    @Test
    void runtimeSolidRoofRepairClearsStaleSkylightBelowShallowBand() {
        RegionLightData data = newRegionData();
        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);
        data.clearDirty();

        RuntimeLightChangeBuffer changes = applyRoofOpacity(data, LucisConstants.MAX_LIGHT);

        engine.applyRuntimeChanges(data, changes);

        assertEquals(0, sky(data, CENTER, ROOF_Y - 1, CENTER));
        assertEquals(0, sky(data, CENTER, DEEP_Y, CENTER));
        assertTrue(data.dirtySkySections.get(data.sectionLinearIndexLocal(CENTER, DEEP_Y, CENTER)));
    }

    @Test
    void runtimePartialOpacityRepairPreservesFilteredSkylight() {
        RegionLightData data = newRegionData();
        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);

        RuntimeLightChangeBuffer changes = applyRoofOpacity(data, 1);

        engine.applyRuntimeChanges(data, changes);

        assertEquals(14, sky(data, CENTER, ROOF_Y, CENTER));
        assertEquals(13, sky(data, CENTER, ROOF_Y - 1, CENTER));
        assertEquals(0, sky(data, CENTER, DEEP_Y, CENTER));
    }

    @Test
    void computeStopsDirectSkySourceAtLeavesLikeVanilla() {
        RegionLightData data = newRegionData();
        for (int z = CENTER - 2; z <= CENTER + 2; z++) {
            for (int x = CENTER - 2; x <= CENTER + 2; x++) {
                data.opacity[data.localIndex(x, LEAF_Y, z)] = 1;
            }
        }

        new LucisSkyLightEngine().compute(data);

        assertEquals(15, sky(data, CENTER, LEAF_Y + 1, CENTER));
        assertEquals(14, sky(data, CENTER, LEAF_Y, CENTER));
        assertEquals(13, sky(data, CENTER, LEAF_Y - 1, CENTER));
        assertEquals(12, sky(data, CENTER, LEAF_Y - 2, CENTER));
    }

    @Test
    void runtimeGlassMaterialChangePublishesSkySectionWhenLevelIsUnchanged() {
        RegionLightData data = newRegionData();
        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);
        data.clearDirty();

        int index = data.localIndex(CENTER, LEAF_Y, CENTER);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(1);
        changes.addMaterial(index,
                LightMaterial.pack(0, 0, LightMaterial.FLAG_AIR | LightMaterial.FLAG_SKYLIGHT_DOWN),
                LightMaterial.pack(0, 0, LightMaterial.FLAG_GLASS | LightMaterial.FLAG_SKYLIGHT_DOWN));

        engine.applyRuntimeChanges(data, changes);

        assertEquals(15, sky(data, CENTER, LEAF_Y, CENTER));
        assertTrue(data.dirtySkySections.get(data.sectionLinearIndexLocal(CENTER, LEAF_Y, CENTER)));
    }

    @Test
    void runtimeSolidRoofRepairMatchesFullRecomputeWithLateralSkyLeakage() {
        RegionLightData data = newRegionData();
        for (int z = ROOF_MIN; z <= ROOF_MAX; z++) {
            for (int x = ROOF_MIN; x <= ROOF_MAX; x++) {
                data.opacity[data.localIndex(x, LEAF_Y, z)] = 1;
            }
        }

        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);

        for (int z = ROOF_MIN; z <= ROOF_MAX; z++) {
            for (int x = ROOF_MIN; x <= ROOF_MAX; x++) {
                data.opacity[data.localIndex(x, ROOF_Y, z)] = LucisConstants.MAX_LIGHT_BYTE;
            }
        }

        RuntimeLightChangeBuffer changes = applyRoofOpacity(data, 20, 21, 20, 21, LucisConstants.MAX_LIGHT);

        engine.applyRuntimeChanges(data, changes);

        RegionLightData full = cloneMaterials(data);
        engine.compute(full);
        assertSameSky(full, data, -1);
    }

    @Test
    void runtimeLargeRoofSliceClearsDeepLeafSections() {
        int width = 16;
        int height = 128;
        int roofY = 112;
        int leafY = 24;
        int deepY = 10;
        RegionLightData data = new RegionLightData(new RegionBounds(0, 0, 1, 0, width, width,
                0, height, 0, height / 16, height, width * width, width * width * height));
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                data.opacity[data.localIndex(x, leafY, z)] = 1;
            }
        }

        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(width * width);
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                int index = data.localIndex(x, roofY, z);
                data.opacity[index] = LucisConstants.MAX_LIGHT_BYTE;
                changes.addLight(index, LightMaterial.packLight(0, 0),
                        LightMaterial.packLight(LucisConstants.MAX_LIGHT, 0));
            }
        }

        engine.applyRuntimeChanges(data, changes);

        assertEquals(0, sky(data, 8, leafY, 8));
        assertEquals(0, sky(data, 8, deepY, 8));
    }

    @Test
    void runtimeFullDepthRepairMatchesFullRecomputeBeyondSeedRadius() {
        int width = 64;
        int height = 64;
        int roofY = 48;
        int leafY = 18;
        int deepY = 8;
        int roofMin = 8;
        int roofMax = 55;
        RegionLightData data = new RegionLightData(new RegionBounds(0, 0, 4, 0, width, width,
                0, height, 0, height / 16, height, width * width, width * width * height));
        for (int z = roofMin; z <= roofMax; z++) {
            for (int x = roofMin; x <= roofMax; x++) {
                data.opacity[data.localIndex(x, leafY, z)] = 1;
            }
        }

        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);
        for (int z = roofMin; z <= roofMax; z++) {
            for (int x = roofMin; x <= roofMax; x++) {
                data.opacity[data.localIndex(x, roofY, z)] = LucisConstants.MAX_LIGHT_BYTE;
            }
        }

        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(4);
        for (int z = 31; z <= 32; z++) {
            for (int x = 31; x <= 32; x++) {
                int index = data.localIndex(x, roofY, z);
                changes.addLight(index, LightMaterial.packLight(0, 0),
                        LightMaterial.packLight(LucisConstants.MAX_LIGHT, 0));
            }
        }

        engine.applyRuntimeChanges(data, changes);

        RegionLightData full = cloneMaterials(data);
        engine.compute(full);
        assertSameSky(full, data, -1);
    }

    @Test
    void runtimeOpacityChangesMatchFullSkyRecomputeForRandomRegions() {
        Random random = new Random(26012115L);
        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        for (int iteration = 0; iteration < 64; iteration++) {
            RegionLightData base = randomSkyRegion(random);
            engine.compute(base);

            RegionLightData incremental = cloneMaterials(base);
            System.arraycopy(base.skyLight, 0, incremental.skyLight, 0, base.skyLight.length);
            RuntimeLightChangeBuffer changes = randomOpacityChanges(random, incremental, 1 + random.nextInt(8));

            engine.applyRuntimeChanges(incremental, changes);

            RegionLightData full = cloneMaterials(incremental);
            engine.compute(full);
            assertSameSky(full, incremental, iteration);
        }
    }

    private static RegionLightData newRegionData() {
        RegionBounds bounds = new RegionBounds(0, 0, 3, 0, WIDTH, WIDTH,
                0, HEIGHT, 0, HEIGHT / 16, HEIGHT, WIDTH * WIDTH, WIDTH * WIDTH * HEIGHT);
        return new RegionLightData(bounds);
    }

    private static RuntimeLightChangeBuffer applyRoofOpacity(RegionLightData data, int opacity) {
        return applyRoofOpacity(data, ROOF_MIN, ROOF_MAX, ROOF_MIN, ROOF_MAX, opacity);
    }

    private static RuntimeLightChangeBuffer applyRoofOpacity(RegionLightData data, int minX, int maxX, int minZ, int maxZ,
                                                             int opacity) {
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer((maxX - minX + 1) * (maxZ - minZ + 1));
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                int index = data.localIndex(x, ROOF_Y, z);
                data.opacity[index] = (byte) opacity;
                changes.addLight(index, LightMaterial.packLight(0, 0), LightMaterial.packLight(opacity, 0));
            }
        }
        return changes;
    }

    private static int sky(RegionLightData data, int x, int y, int z) {
        return data.skyLight[data.localIndex(x, y, z)] & 0xF;
    }

    private static RegionLightData randomSkyRegion(Random random) {
        int width = 32;
        int height = 64;
        RegionLightData data = new RegionLightData(new RegionBounds(0, 0, 2, 0, width, width,
                0, height, 0, height / 16, height, width * width, width * width * height));
        for (int index = 0; index < data.bounds.volume(); index++) {
            data.opacity[index] = (byte) randomOpacity(random);
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

    private static RuntimeLightChangeBuffer randomOpacityChanges(Random random, RegionLightData data, int targetChanges) {
        RuntimeLightChangeBuffer changes = new RuntimeLightChangeBuffer(targetChanges);
        HashSet<Integer> used = new HashSet<>();
        int attempts = 0;
        while (changes.size() < targetChanges && attempts++ < 1024) {
            int index = random.nextInt(data.bounds.volume());
            if (!used.add(index)) {
                continue;
            }
            int oldOpacity = data.opacity[index] & 0xF;
            int newOpacity = randomOpacity(random);
            if (newOpacity == oldOpacity) {
                continue;
            }
            data.opacity[index] = (byte) newOpacity;
            changes.addLight(index, LightMaterial.packLight(oldOpacity, 0), LightMaterial.packLight(newOpacity, 0));
        }
        return changes;
    }

    private static int randomOpacity(Random random) {
        int roll = random.nextInt(100);
        if (roll < 70) {
            return 0;
        }
        if (roll < 86) {
            return 1;
        }
        if (roll < 95) {
            return 2 + random.nextInt(4);
        }
        return LucisConstants.MAX_LIGHT;
    }

    private static void assertSameSky(RegionLightData expected, RegionLightData actual, int iteration) {
        int width = expected.bounds.widthBlocks();
        int area = expected.bounds.area();
        for (int index = 0; index < expected.skyLight.length; index++) {
            int expectedLight = expected.skyLight[index] & 0xF;
            int actualLight = actual.skyLight[index] & 0xF;
            if (expectedLight != actualLight) {
                int y = index / area;
                int rem = index - y * area;
                int z = rem / width;
                int x = rem - z * width;
                assertEquals(expectedLight, actualLight,
                        "sky mismatch in iteration " + iteration + " at " + x + "," + y + "," + z);
            }
        }
    }
}
