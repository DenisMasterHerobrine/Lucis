package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.LightMaterial;
import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChangeBuffer;
import org.junit.jupiter.api.Test;

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
}
