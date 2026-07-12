package dev.denismasterherobrine.lucis.light;

public final class LightMaterial {
    public static final byte FLAG_AIR = 1;
    public static final byte FLAG_SKYLIGHT_DOWN = 1 << 1;
    public static final byte FLAG_OCCLUDES = 1 << 2;

    public static final int LIGHT_MASK = 0xFF;

    private static final int OPACITY_MASK = 0xF;
    private static final int EMISSION_SHIFT = 4;
    private static final int FLAGS_SHIFT = 8;

    private LightMaterial() {
    }

    public static int packLight(int opacity, int emission) {
        return (opacity & OPACITY_MASK) | ((emission & OPACITY_MASK) << EMISSION_SHIFT);
    }

    public static int pack(int opacity, int emission, int flags) {
        return packLight(opacity, emission) | ((flags & 0xFF) << FLAGS_SHIFT);
    }

    public static byte opacity(int packed) {
        return (byte) (packed & OPACITY_MASK);
    }

    public static byte emission(int packed) {
        return (byte) ((packed >>> EMISSION_SHIFT) & OPACITY_MASK);
    }

    public static byte flags(int packed) {
        return (byte) ((packed >>> FLAGS_SHIFT) & 0xFF);
    }

    public static int opacityInt(int packed) {
        return packed & OPACITY_MASK;
    }

    public static int emissionInt(int packed) {
        return (packed >>> EMISSION_SHIFT) & OPACITY_MASK;
    }

    public static boolean hasSameLight(int first, int second) {
        return ((first ^ second) & LIGHT_MASK) == 0;
    }

    public static boolean isAir(int packed) {
        return (flags(packed) & FLAG_AIR) != 0;
    }

    public static boolean propagatesSkylightDown(int packed) {
        return (flags(packed) & FLAG_SKYLIGHT_DOWN) != 0;
    }

    public static boolean occludes(int packed) {
        return (flags(packed) & FLAG_OCCLUDES) != 0;
    }
}
