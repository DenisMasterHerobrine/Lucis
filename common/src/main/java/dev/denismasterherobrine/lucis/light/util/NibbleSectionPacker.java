package dev.denismasterherobrine.lucis.light.util;

import dev.denismasterherobrine.lucis.light.region.RegionLightData;

public final class NibbleSectionPacker {
    private NibbleSectionPacker() {
    }

    public static byte[] packSectionBytes(RegionLightData data, byte[] source, int sectionWorldX, int sectionY, int sectionWorldZ) {
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
                byte b0 = (byte) (source[row     ] | (source[row +  1] << 4));
                byte b1 = (byte) (source[row +  2] | (source[row +  3] << 4));
                byte b2 = (byte) (source[row +  4] | (source[row +  5] << 4));
                byte b3 = (byte) (source[row +  6] | (source[row +  7] << 4));
                byte b4 = (byte) (source[row +  8] | (source[row +  9] << 4));
                byte b5 = (byte) (source[row + 10] | (source[row + 11] << 4));
                byte b6 = (byte) (source[row + 12] | (source[row + 13] << 4));
                byte b7 = (byte) (source[row + 14] | (source[row + 15] << 4));
                if ((b0 | b1 | b2 | b3 | b4 | b5 | b6 | b7) == 0) {
                    row += width;
                    write += 8;
                    continue;
                }

                byte[] packed = new byte[2048];
                packed[write    ] = b0;
                packed[write + 1] = b1;
                packed[write + 2] = b2;
                packed[write + 3] = b3;
                packed[write + 4] = b4;
                packed[write + 5] = b5;
                packed[write + 6] = b6;
                packed[write + 7] = b7;
                row += width;
                write += 8;

                for (z++; z < 16; z++) {
                    b0 = (byte) (source[row     ] | (source[row +  1] << 4));
                    b1 = (byte) (source[row +  2] | (source[row +  3] << 4));
                    b2 = (byte) (source[row +  4] | (source[row +  5] << 4));
                    b3 = (byte) (source[row +  6] | (source[row +  7] << 4));
                    b4 = (byte) (source[row +  8] | (source[row +  9] << 4));
                    b5 = (byte) (source[row + 10] | (source[row + 11] << 4));
                    b6 = (byte) (source[row + 12] | (source[row + 13] << 4));
                    b7 = (byte) (source[row + 14] | (source[row + 15] << 4));
                    packed[write    ] = b0;
                    packed[write + 1] = b1;
                    packed[write + 2] = b2;
                    packed[write + 3] = b3;
                    packed[write + 4] = b4;
                    packed[write + 5] = b5;
                    packed[write + 6] = b6;
                    packed[write + 7] = b7;
                    row += width;
                    write += 8;
                }

                for (y++, layer += area; y < 16; y++, layer += area) {
                    row = layer;
                    for (z = 0; z < 16; z++) {
                        b0 = (byte) (source[row     ] | (source[row +  1] << 4));
                        b1 = (byte) (source[row +  2] | (source[row +  3] << 4));
                        b2 = (byte) (source[row +  4] | (source[row +  5] << 4));
                        b3 = (byte) (source[row +  6] | (source[row +  7] << 4));
                        b4 = (byte) (source[row +  8] | (source[row +  9] << 4));
                        b5 = (byte) (source[row + 10] | (source[row + 11] << 4));
                        b6 = (byte) (source[row + 12] | (source[row + 13] << 4));
                        b7 = (byte) (source[row + 14] | (source[row + 15] << 4));
                        packed[write    ] = b0;
                        packed[write + 1] = b1;
                        packed[write + 2] = b2;
                        packed[write + 3] = b3;
                        packed[write + 4] = b4;
                        packed[write + 5] = b5;
                        packed[write + 6] = b6;
                        packed[write + 7] = b7;
                        row += width;
                        write += 8;
                    }
                }

                return packed;
            }

            layer += area;
        }

        return null;
    }
}
