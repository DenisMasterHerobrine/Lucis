package dev.denismasterherobrine.lucis.light;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.AtomicReferenceArray;

public final class LightMaterialCache {
    private static final BlockPos ZERO = BlockPos.ZERO;
    private final AtomicReferenceArray<LightMaterial> cache = new AtomicReferenceArray<>(Block.BLOCK_STATE_REGISTRY.size());

    public LightMaterial lookup(BlockGetter level, BlockState state, BlockPos pos) {
        int id = Block.getId(state);
        LightMaterial cached = cache.get(id);
        if (cached != null) {
            return cached;
        }

        byte opacity = (byte) Math.max(0, Math.min(LucisConstants.MAX_LIGHT, state.getLightBlock(level, pos)));
        byte emission = (byte) Math.max(0, Math.min(LucisConstants.MAX_LIGHT, state.getLightEmission()));
        byte flags = 0;
        if (state.isAir()) {
            flags |= LightMaterial.FLAG_AIR;
        }
        if (state.propagatesSkylightDown(level, pos)) {
            flags |= LightMaterial.FLAG_SKYLIGHT_DOWN;
        }
        if (state.canOcclude()) {
            flags |= LightMaterial.FLAG_OCCLUDES;
        }

        LightMaterial material = new LightMaterial(opacity, emission, flags);
        if (!state.useShapeForLightOcclusion()) {
            cache.compareAndSet(id, null, material);
            return cache.get(id);
        }

        if (pos == ZERO) {
            return material;
        }

        return new LightMaterial(
                (byte) Math.max(0, Math.min(LucisConstants.MAX_LIGHT, state.getLightBlock(level, pos))),
                emission,
                flags
        );
    }
}
