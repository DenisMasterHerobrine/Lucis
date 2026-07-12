package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LucisConstants;
import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

        RuntimeLightChangeBuffer changes = applyRoofOpacity(data, LucisConstants.MAX_LIGHT);

        engine.applyRuntimeChanges(data, changes);

        assertEquals(0, sky(data, CENTER, ROOF_Y - 1, CENTER));
        assertEquals(0, sky(data, CENTER, DEEP_Y, CENTER));
    }

    @Test
    void runtimePartialOpacityRepairPreservesFilteredSkylight() {
        RegionLightData data = newRegionData();
        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);

        RuntimeLightChangeBuffer changes = applyRoofOpacity(data, 1);

        engine.applyRuntimeChanges(data, changes);

        assertEquals(14, sky(data, CENTER, ROOF_Y, CENTER));
        assertEquals(14, sky(data, CENTER, DEEP_Y, CENTER));
    }

    @Test
    void runtimeSolidRoofRepairDoesNotReseedFromStaleCoveredLeavesOutsideRepairBox() {
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

        assertEquals(0, sky(data, 21, LEAF_Y, 21));
        assertEquals(0, sky(data, 21, DEEP_Y, 21));
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
}
