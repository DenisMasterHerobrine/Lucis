package dev.denismasterherobrine.lucis.light.util;

import java.util.NoSuchElementException;

public final class IntBucketQueue {
    private static final int MIN_CAPACITY = 4;
    private static final int MAX_CAPACITY = 1 << 30;

    private final int[][] buckets;
    private final int[] heads;
    private final int[] tails;
    private final int[] masks;

    private int nonEmptyMask;
    private int touchedMask;

    public IntBucketQueue(int levels, int initialCapacity) {
        if (levels <= 0 || levels > Integer.SIZE) {
            throw new IllegalArgumentException("levels must be in range 1..32");
        }

        int capacity = tableSizeFor(initialCapacity);
        this.buckets = new int[levels][];
        this.heads = new int[levels];
        this.tails = new int[levels];
        this.masks = new int[levels];

        for (int i = 0; i < levels; i++) {
            buckets[i] = new int[capacity];
            masks[i] = capacity - 1;
        }
    }

    public void clear() {
        int mask = touchedMask;
        while (mask != 0) {
            int level = Integer.numberOfTrailingZeros(mask);
            heads[level] = 0;
            tails[level] = 0;
            mask &= mask - 1;
        }

        nonEmptyMask = 0;
        touchedMask = 0;
    }

    public boolean isEmpty() {
        return nonEmptyMask == 0;
    }

    public void enqueue(int level, int value) {
        if (level < 0 || level >= buckets.length) {
            return;
        }

        int[] bucket = buckets[level];
        int head = heads[level];
        int tail = tails[level];

        if (tail - head == bucket.length) {
            grow(level);
            bucket = buckets[level];
            tail = tails[level];
        }

        int m = masks[level];
        bucket[tail & m] = value;
        tails[level] = tail + 1;

        int bit = 1 << level;
        nonEmptyMask |= bit;
        touchedMask |= bit;
    }

    public int dequeueLevel() {
        int mask = nonEmptyMask;
        return mask == 0 ? -1 : 31 - Integer.numberOfLeadingZeros(mask);
    }

    public int dequeue() {
        if (nonEmptyMask == 0) {
            throw new NoSuchElementException("IntBucketQueue is empty");
        }

        return pollUnchecked();
    }

    public int poll() {
        return nonEmptyMask == 0 ? -1 : pollUnchecked();
    }

    private int pollUnchecked() {
        int mask = nonEmptyMask;
        int level = 31 - Integer.numberOfLeadingZeros(mask);

        int[] heads = this.heads;
        int[] tails = this.tails;
        int head = heads[level];
        int tail = tails[level];
        int[] bucket = buckets[level];
        int m = masks[level];

        int value = bucket[head & m];
        head++;
        heads[level] = head;

        if (head == tail) {
            nonEmptyMask = mask & ~(1 << level);
        }

        return value;
    }

    private void grow(int level) {
        int[] old = buckets[level];
        int oldLength = old.length;
        if (oldLength == MAX_CAPACITY) {
            throw new IllegalStateException("IntBucketQueue bucket capacity overflow");
        }

        int head = heads[level];
        int tail = tails[level];
        int size = tail - head;

        int[] next = new int[oldLength << 1];

        int start = head & masks[level];
        int first = Math.min(size, oldLength - start);
        System.arraycopy(old, start, next, 0, first);
        if (first < size) {
            System.arraycopy(old, 0, next, first, size - first);
        }

        buckets[level] = next;
        masks[level] = next.length - 1;
        heads[level] = 0;
        tails[level] = size;
    }

    private static int tableSizeFor(int capacity) {
        int value = Math.max(MIN_CAPACITY, capacity);
        if (value >= MAX_CAPACITY) {
            return MAX_CAPACITY;
        }

        return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(value - 1));
    }
}
