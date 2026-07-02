package dev.denismasterherobrine.lucis.light.runtime;

public record RuntimeLightChange(
        int worldX,
        int worldY,
        int worldZ,
        byte oldOpacity,
        byte newOpacity,
        byte oldEmission,
        byte newEmission
) {
}
