package dev.denismasterherobrine.lucis.light.util;

import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import net.minecraft.world.level.chunk.DataLayer;

public final class NibblePacker {
    private NibblePacker() {
    }

    public static DataLayer packSection(RegionLightData data, byte[] source, int sectionWorldX, int sectionY, int sectionWorldZ) {
        byte[] packed = new byte[2048];
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();

        int baseX = sectionWorldX - data.bounds.minBlockX();
        int baseY = (sectionY << 4) - data.bounds.minBuildY();
        int baseZ = sectionWorldZ - data.bounds.minBlockZ();

        int layer = baseY * area + baseZ * width + baseX;
        int write = 0;

        for (int y = 0; y < 16; y++) {
            int row = layer;
            for (int z = 0; z < 16; z++) {
                packed[write    ] = (byte) (source[row     ] | (source[row +  1] << 4));
                packed[write + 1] = (byte) (source[row +  2] | (source[row +  3] << 4));
                packed[write + 2] = (byte) (source[row +  4] | (source[row +  5] << 4));
                packed[write + 3] = (byte) (source[row +  6] | (source[row +  7] << 4));
                packed[write + 4] = (byte) (source[row +  8] | (source[row +  9] << 4));
                packed[write + 5] = (byte) (source[row + 10] | (source[row + 11] << 4));
                packed[write + 6] = (byte) (source[row + 12] | (source[row + 13] << 4));
                packed[write + 7] = (byte) (source[row + 14] | (source[row + 15] << 4));

                row += width;
                write += 8;
            }

            layer += area;
        }

        return new DataLayer(packed);
    }
}
