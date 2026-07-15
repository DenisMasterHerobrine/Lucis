package dev.denismasterherobrine.lucis.light.util;

import dev.denismasterherobrine.lucis.light.region.RegionBounds;
import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class NibblePackerTest {
    @Test
    void returnsNullBytesForAllZeroSection() {
        RegionLightData data = new RegionLightData(singleSectionBounds());

        byte[] packed = NibbleSectionPacker.packSectionBytes(data, data.blockLight, 0, 0, 0);

        assertNull(packed);
    }

    @Test
    void packsSparseAndLateValuesIntoExpectedNibbles() {
        RegionLightData data = new RegionLightData(singleSectionBounds());
        data.blockLight[data.localIndex(0, 0, 0)] = 1;
        data.blockLight[data.localIndex(1, 0, 0)] = 2;
        data.blockLight[data.localIndex(14, 15, 15)] = 3;
        data.blockLight[data.localIndex(15, 15, 15)] = 4;

        byte[] packed = NibbleSectionPacker.packSectionBytes(data, data.blockLight, 0, 0, 0);

        assertNotNull(packed);
        assertEquals((byte) 0x21, packed[0]);
        assertEquals((byte) 0x43, packed[2047]);
        byte[] expected = new byte[2048];
        expected[0] = 0x21;
        expected[2047] = 0x43;
        assertArrayEquals(expected, packed);
    }

    private static RegionBounds singleSectionBounds() {
        return new RegionBounds(0, 0, 1, 0, 16, 16,
                0, 16, 0, 1, 16, 256, 4096);
    }
}
