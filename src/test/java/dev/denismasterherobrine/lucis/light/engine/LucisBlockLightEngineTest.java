package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.RuntimeLightChange;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LucisBlockLightEngineTest {
    @Test
    void fastBatchPropagatesTorchLightBeyondImmediateNeighbors() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
        RegionLightData data = new RegionLightData(bounds);
        RuntimeLightChange firstTorch = new RuntimeLightChange(8, 8, 8,
                (byte) 0, (byte) 0, (byte) 0, (byte) 14);
        RuntimeLightChange secondTorch = new RuntimeLightChange(2, 2, 2,
                (byte) 0, (byte) 0, (byte) 0, (byte) 14);

        new LucisBlockLightEngine().applyRuntimeChangesFast(data, List.of(firstTorch, secondTorch));

        assertEquals(14, data.blockLight[data.localIndex(8, 8, 8)] & 0xF);
        assertEquals(13, data.blockLight[data.localIndex(9, 8, 8)] & 0xF);
        assertEquals(9, data.blockLight[data.localIndex(13, 8, 8)] & 0xF);
    }
}
