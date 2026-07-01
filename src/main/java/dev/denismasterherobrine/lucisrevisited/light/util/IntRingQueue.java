package dev.denismasterherobrine.lucisrevisited.light.util;

import java.util.Arrays;
import java.util.NoSuchElementException;

public final class IntRingQueue {
    private int[] data;
    private int head;
    private int tail;
    private int size;

    public IntRingQueue(int capacity) {
        this.data = new int[Math.max(4, capacity)];
    }

    public void clear() {
        head = 0;
        tail = 0;
        size = 0;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public int size() {
        return size;
    }

    public void enqueue(int value) {
        ensureCapacity(size + 1);
        data[tail] = value;
        tail = (tail + 1) % data.length;
        size++;
    }

    public int dequeue() {
        if (size == 0) {
            throw new NoSuchElementException("IntRingQueue is empty");
        }
        int value = data[head];
        head = (head + 1) % data.length;
        size--;
        return value;
    }

    private void ensureCapacity(int wanted) {
        if (wanted <= data.length) {
            return;
        }

        int[] next = new int[data.length << 1];
        for (int i = 0; i < size; i++) {
            next[i] = data[(head + i) % data.length];
        }
        data = next;
        head = 0;
        tail = size;
    }

    @Override
    public String toString() {
        return "IntRingQueue{" +
                "size=" + size +
                ", capacity=" + data.length +
                ", data=" + Arrays.toString(data) +
                '}';
    }
}
