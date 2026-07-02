package dev.denismasterherobrine.lucis.light;

public record LightMaterial(byte opacity, byte emission, byte flags) {
    public static final byte FLAG_AIR = 1;
    public static final byte FLAG_SKYLIGHT_DOWN = 1 << 1;
    public static final byte FLAG_OCCLUDES = 1 << 2;

    public boolean isAir() {
        return (flags & FLAG_AIR) != 0;
    }

    public boolean propagatesSkylightDown() {
        return (flags & FLAG_SKYLIGHT_DOWN) != 0;
    }

    public boolean occludes() {
        return (flags & FLAG_OCCLUDES) != 0;
    }
}
