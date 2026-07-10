package dev.denismasterherobrine.lucis.light.util;

import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import net.minecraft.world.level.chunk.DataLayer;

public final class NibblePacker {
    private NibblePacker() {
    }

    public static DataLayer packSection(RegionLightData data, byte[] source, int sectionWorldX, int sectionY, int sectionWorldZ) {
        byte[] packed = new byte[2048];
        int baseX = sectionWorldX - data.bounds.minBlockX();
        int baseY = (sectionY << 4) - data.bounds.minBuildY();
        int baseZ = sectionWorldZ - data.bounds.minBlockZ();
        int width = data.bounds.widthBlocks();
        int area = data.bounds.area();
        int write = 0;
        for (int localY = 0; localY < 16; localY++) {
            int layerBase = (baseY + localY) * area;
            for (int localZ = 0; localZ < 16; localZ++) {
                int rowBase = layerBase + (baseZ + localZ) * width + baseX;
                for (int localX = 0; localX < 16; localX += 2) {
                    int lo = source[rowBase + localX] & 0xF;
                    int hi = source[rowBase + localX + 1] & 0xF;
                    packed[write++] = (byte) (lo | (hi << 4));
                }
            }
        }
        return new DataLayer(packed);
    }
}
