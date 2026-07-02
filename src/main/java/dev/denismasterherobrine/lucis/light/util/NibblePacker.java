package dev.denismasterherobrine.lucis.light.util;

import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import net.minecraft.world.level.chunk.DataLayer;

public final class NibblePacker {
    private NibblePacker() {
    }

    public static DataLayer packSection(RegionLightData data, byte[] source, int sectionWorldX, int sectionY, int sectionWorldZ) {
        byte[] packed = new byte[2048];
        int worldY = sectionY << 4;
        int write = 0;
        for (int localY = 0; localY < 16; localY++) {
            int blockY = worldY + localY;
            for (int localZ = 0; localZ < 16; localZ++) {
                int blockZ = sectionWorldZ + localZ;
                for (int localX = 0; localX < 16; localX += 2) {
                    int lo = source[data.index(sectionWorldX + localX, blockY, blockZ)] & 0xF;
                    int hi = source[data.index(sectionWorldX + localX + 1, blockY, blockZ)] & 0xF;
                    packed[write++] = (byte) (lo | (hi << 4));
                }
            }
        }
        return new DataLayer(packed);
    }
}
