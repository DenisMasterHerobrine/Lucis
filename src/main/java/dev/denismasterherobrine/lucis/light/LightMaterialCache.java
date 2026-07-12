package dev.denismasterherobrine.lucis.light;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class LightMaterialCache {
    private static final VarHandle INT_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);
    private static final int UNCACHED = 0;
    private static final int DYNAMIC_OPACITY = 1 << 31;
    private static final int ENCODED_DYNAMIC_MARKER = 1;
    private static final int DYNAMIC_EMISSION_SHIFT = 1;

    // each slot publishes one self-contained int
    // a stale/uncached read can only cause a duplicate recompute, not wrong light data
    private final int[] lightCache = new int[Block.BLOCK_STATE_REGISTRY.size()];

    public int lookupLight(BlockGetter level, BlockState state, BlockPos pos) {
        int id = Block.getId(state);
        int cached = (int) INT_ARRAY.getOpaque(lightCache, id);
        if (cached != UNCACHED) {
            return unpackCachedLight(level, state, pos, cached);
        }

        int emission = clampLight(state.getLightEmission());
        if (state.useShapeForLightOcclusion()) {
            INT_ARRAY.setOpaque(lightCache, id, encodeDynamic(emission));
            return LightMaterial.packLight(clampLight(state.getLightBlock(level, pos)), emission);
        }

        int packed = LightMaterial.packLight(clampLight(state.getLightBlock(level, pos)), emission);
        INT_ARRAY.setOpaque(lightCache, id, encodeStatic(packed));
        return packed;
    }

    private int unpackCachedLight(BlockGetter level, BlockState state, BlockPos pos, int cached) {
        if ((cached & DYNAMIC_OPACITY) != 0) {
            return LightMaterial.packLight(clampLight(state.getLightBlock(level, pos)), dynamicEmission(cached));
        }
        return decodeStatic(cached);
    }

    private static int encodeStatic(int packed) {
        return (packed & LightMaterial.LIGHT_MASK) + 1;
    }

    private static int decodeStatic(int cached) {
        return (cached - 1) & LightMaterial.LIGHT_MASK;
    }

    private static int encodeDynamic(int emission) {
        return DYNAMIC_OPACITY | ENCODED_DYNAMIC_MARKER | ((emission & 0xF) << DYNAMIC_EMISSION_SHIFT);
    }

    private static int dynamicEmission(int cached) {
        return (cached >>> DYNAMIC_EMISSION_SHIFT) & 0xF;
    }

    private static int clampLight(int light) {
        if (light <= 0) {
            return 0;
        }
        return Math.min(light, LucisConstants.MAX_LIGHT);
    }
}
