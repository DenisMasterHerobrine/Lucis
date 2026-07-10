package dev.denismasterherobrine.lucis.light;

public final class LucisConstants {
    public static final int SECTION_SIZE = 16;
    public static final int SECTION_AREA = SECTION_SIZE * SECTION_SIZE;
    public static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    public static final int MAX_LIGHT = 15;
    public static final byte MAX_LIGHT_BYTE = (byte) MAX_LIGHT;
    public static final int REGION_UNLOADED_LIGHT = -1;

    private LucisConstants() {
    }
}
