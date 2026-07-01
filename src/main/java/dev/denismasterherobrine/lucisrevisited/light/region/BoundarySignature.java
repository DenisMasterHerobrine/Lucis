package dev.denismasterherobrine.lucisrevisited.light.region;

import java.util.Arrays;

public record BoundarySignature(long regionKey, RegionSide side, int blockHash, int skyHash) {
    public static BoundarySignature from(long regionKey, RegionSide side, byte[] blockLevels, byte[] skyLevels) {
        return new BoundarySignature(regionKey, side, Arrays.hashCode(blockLevels), Arrays.hashCode(skyLevels));
    }
}
