package dev.denismasterherobrine.lucis.light.runtime;

import dev.denismasterherobrine.lucis.light.LightMaterial;

import java.util.Arrays;

public final class RuntimeLightChangeBuffer {
    private static final int DEFAULT_CAPACITY = 256;
    private static final long INDEX_MASK = 0xFFFF_FFFFL;

    private long[] changes;
    private int size;
    private boolean hasOpacityChange;
    private boolean hasEmissionChange;
    private boolean blockFastEligible;

    public RuntimeLightChangeBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public RuntimeLightChangeBuffer(int capacity) {
        this.changes = new long[Math.max(1, capacity)];
        this.blockFastEligible = true;
    }

    public void clear() {
        size = 0;
        hasOpacityChange = false;
        hasEmissionChange = false;
        blockFastEligible = true;
    }

    public void add(int index, int oldMaterial, int newMaterial) {
        addLight(index, oldMaterial & LightMaterial.LIGHT_MASK, newMaterial & LightMaterial.LIGHT_MASK);
    }

    public void addLight(int index, int oldLight, int newLight) {
        ensureCapacity(size + 1);
        oldLight &= LightMaterial.LIGHT_MASK;
        newLight &= LightMaterial.LIGHT_MASK;
        changes[size++] = pack(index, oldLight, newLight);

        int delta = oldLight ^ newLight;
        hasOpacityChange |= (delta & 0x0F) != 0;
        hasEmissionChange |= (delta & 0xF0) != 0;
        blockFastEligible &= (delta & 0x0F) == 0 && (newLight & 0xF0) >= (oldLight & 0xF0);
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public long get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException(index);
        }
        return changes[index];
    }

    public boolean hasOpacityChange() {
        return hasOpacityChange;
    }

    public boolean hasEmissionChange() {
        return hasEmissionChange;
    }

    public boolean blockFastEligible() {
        return blockFastEligible;
    }

    private void ensureCapacity(int capacity) {
        if (changes.length >= capacity) {
            return;
        }
        changes = Arrays.copyOf(changes, Math.max(capacity, changes.length << 1));
    }

    public static long pack(int index, int oldLight, int newLight) {
        return ((long) index & INDEX_MASK)
                | ((long) oldLight & 0xFFL) << 32
                | ((long) newLight & 0xFFL) << 40;
    }

    public static int localIndex(long change) {
        return (int) (change & INDEX_MASK);
    }

    public static int oldLight(long change) {
        return (int) ((change >>> 32) & 0xFF);
    }

    public static int newLight(long change) {
        return (int) ((change >>> 40) & 0xFF);
    }

    public static int oldOpacity(long change) {
        return oldLight(change) & 0xF;
    }

    public static int newOpacity(long change) {
        return newLight(change) & 0xF;
    }

    public static int oldEmission(long change) {
        return (oldLight(change) >>> 4) & 0xF;
    }

    public static int newEmission(long change) {
        return (newLight(change) >>> 4) & 0xF;
    }
}
