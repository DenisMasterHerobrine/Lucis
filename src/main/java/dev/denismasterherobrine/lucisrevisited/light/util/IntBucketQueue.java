package dev.denismasterherobrine.lucisrevisited.light.util;

import java.util.NoSuchElementException;

public final class IntBucketQueue {
    private final IntRingQueue[] buckets;
    private int highest;

    public IntBucketQueue(int levels, int initialCapacity) {
        this.buckets = new IntRingQueue[levels];
        for (int i = 0; i < levels; i++) {
            buckets[i] = new IntRingQueue(initialCapacity);
        }
        highest = 0;
    }

    public void clear() {
        for (IntRingQueue queue : buckets) {
            queue.clear();
        }
        highest = 0;
    }

    public boolean isEmpty() {
        while (highest > 0 && buckets[highest].isEmpty()) {
            highest--;
        }
        return highest <= 0 && buckets[0].isEmpty();
    }

    public void enqueue(int level, int value) {
        if (level < 0 || level >= buckets.length) {
            return;
        }
        buckets[level].enqueue(value);
        if (level > highest) {
            highest = level;
        }
    }

    public int dequeueLevel() {
        while (highest > 0 && buckets[highest].isEmpty()) {
            highest--;
        }
        if (buckets[highest].isEmpty()) {
            return -1;
        }
        return highest;
    }

    public int dequeue() {
        int level = dequeueLevel();
        if (level < 0) {
            throw new NoSuchElementException("IntBucketQueue is empty");
        }
        return buckets[level].dequeue();
    }
}
