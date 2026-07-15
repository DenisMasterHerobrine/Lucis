package dev.denismasterherobrine.lucis.light;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class LightMaterialCache {
    private static final VarHandle INT_ARRAY = MethodHandles.arrayElementVarHandle(int[].class);
    private static final int UNCACHED = 0;
    private static final int DYNAMIC_OPACITY = 1 << 31;
    private static final int ENCODED_DYNAMIC_MARKER = 1;
    private static final int DYNAMIC_EMISSION_SHIFT = 1;
    private static final int DYNAMIC_FLAGS_SHIFT = 5;

    // each slot publishes one self-contained int
    // a stale/uncached read can only cause a duplicate recompute, not wrong light data
    private final int[] lightCache = new int[Block.BLOCK_STATE_REGISTRY.size()];

    public int lookupLight(BlockGetter level, BlockState state, BlockPos pos) {
        int id = Block.getId(state);
        int cached = (int) INT_ARRAY.getOpaque(lightCache, id);
        if (cached != UNCACHED) {
            return unpackCachedMaterial(level, state, pos, cached);
        }

        int emission = clampLight(state.getLightEmission());
        int staticFlags = staticFlags(state);
        if (state.useShapeForLightOcclusion()) {
            INT_ARRAY.setOpaque(lightCache, id, encodeDynamic(emission, staticFlags));
            return material(level, state, pos, emission, staticFlags);
        }

        int packed = material(level, state, pos, emission, staticFlags);
        INT_ARRAY.setOpaque(lightCache, id, encodeStatic(packed));
        return packed;
    }

    private int unpackCachedMaterial(BlockGetter level, BlockState state, BlockPos pos, int cached) {
        if ((cached & DYNAMIC_OPACITY) != 0) {
            return material(level, state, pos, dynamicEmission(cached), dynamicFlags(cached));
        }
        return decodeStatic(cached);
    }

    private static int material(BlockGetter level, BlockState state, BlockPos pos, int emission, int staticFlags) {
        boolean foliage = (staticFlags & LightMaterial.FLAG_FOLIAGE) != 0;
        boolean glass = (staticFlags & LightMaterial.FLAG_GLASS) != 0;
        int opacity = glass ? 0 : clampLight(state.getLightDampening());
        if (foliage && opacity == 0) {
            opacity = 1;
        }

        int flags = staticFlags;
        if (glass || state.propagatesSkylightDown()) {
            flags |= LightMaterial.FLAG_SKYLIGHT_DOWN;
        }
        return LightMaterial.pack(opacity, emission, flags);
    }

    private static int staticFlags(BlockState state) {
        int flags = 0;
        if (state.isAir()) {
            flags |= LightMaterial.FLAG_AIR;
        }
        if (state.canOcclude()) {
            flags |= LightMaterial.FLAG_OCCLUDES;
        }
        if (isTransparentGlass(state)) {
            flags |= LightMaterial.FLAG_GLASS | LightMaterial.FLAG_SKYLIGHT_DOWN;
        }
        if (state.is(BlockTags.LEAVES)) {
            flags |= LightMaterial.FLAG_FOLIAGE;
        }
        return flags;
    }

    private static boolean isTransparentGlass(BlockState state) {
        return state.is(BlockTags.IMPERMEABLE) && !state.is(Blocks.TINTED_GLASS);
    }

    private static int encodeStatic(int packed) {
        return (packed & LightMaterial.MATERIAL_MASK) + 1;
    }

    private static int decodeStatic(int cached) {
        return (cached - 1) & LightMaterial.MATERIAL_MASK;
    }

    private static int encodeDynamic(int emission, int flags) {
        return DYNAMIC_OPACITY | ENCODED_DYNAMIC_MARKER
                | ((emission & 0xF) << DYNAMIC_EMISSION_SHIFT)
                | ((flags & 0xFF) << DYNAMIC_FLAGS_SHIFT);
    }

    private static int dynamicEmission(int cached) {
        return (cached >>> DYNAMIC_EMISSION_SHIFT) & 0xF;
    }

    private static int dynamicFlags(int cached) {
        return (cached >>> DYNAMIC_FLAGS_SHIFT) & 0xFF;
    }

    private static int clampLight(int light) {
        if (light <= 0) {
            return 0;
        }
        return Math.min(light, LucisConstants.MAX_LIGHT);
    }
}
