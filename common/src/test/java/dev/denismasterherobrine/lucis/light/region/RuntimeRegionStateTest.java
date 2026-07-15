package dev.denismasterherobrine.lucis.light.region;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeRegionStateTest {
    @Test
    void invalidatesInitializationWhenChunkIdentityChanges() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
        RuntimeRegionState state = new RuntimeRegionState(new RegionLightData(bounds));
        Object firstChunk = new Object();
        Object reloadedChunk = new Object();

        assertFalse(state.initializedFor(firstChunk));

        state.markInitialized(firstChunk);
        assertTrue(state.initializedFor(firstChunk));
        assertFalse(state.initializedFor(reloadedChunk));

        state.markInitialized(reloadedChunk);
        assertTrue(state.initializedFor(reloadedChunk));
        assertFalse(state.initializedFor(firstChunk));
    }
}
