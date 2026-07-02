package dev.denismasterherobrine.lucis.light.engine;

import dev.denismasterherobrine.lucis.light.region.RegionLightData;
import dev.denismasterherobrine.lucis.light.runtime.BlockChangeRecord;

import java.util.List;

public final class LucisRemoveEngine {
    public void applyChanges(RegionLightData data, List<BlockChangeRecord> changes) {
        if (changes.isEmpty()) {
            data.clearLight();
            return;
        }

        // Runtime still rebuilds region lighting, but removals mark local neighborhoods explicitly.
        // This keeps change application closer to future remove/add propagation without mutating visible storage.
        data.clearLight();
        for (BlockChangeRecord change : changes) {
            int worldX = change.pos().getX();
            int worldY = change.pos().getY();
            int worldZ = change.pos().getZ();
            if (!data.isInside(worldX, worldY, worldZ)) {
                continue;
            }

            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int x = worldX + dx;
                    int z = worldZ + dz;
                    if (!data.isInside(x, worldY, z)) {
                        continue;
                    }
                    data.markDirtyBlock(x, worldY >> 4, z);
                    data.markDirtySky(x, worldY >> 4, z);
                }
            }
        }
    }
}
