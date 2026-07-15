package dev.denismasterherobrine.lucis.light.util;

import java.util.Arrays;
import java.util.NoSuchElementException;

public final class IntRingQueue {
    private static final int MIN_CAPACITY = 4;
    private static final int MAX_CAPACITY = 1 << 30;

    private int[] data;
    private int mask;
    private int head;
    private int tail;

    public IntRingQueue(int capacity) {
        int actualCapacity = tableSizeFor(capacity);
        this.data = new int[actualCapacity];
        this.mask = actualCapacity - 1;
    }

    public void clear() {
        head = 0;
        tail = 0;
    }

    public boolean isEmpty() {
        return head == tail;
    }

    public int size() {
        return tail - head;
    }

    public void enqueue(int value) {
        int[] buffer = data;
        int head = this.head;
        int tail = this.tail;

        if (tail - head == buffer.length) {
            grow();
            buffer = data;
            tail = this.tail;
        }

        int m = mask;
        buffer[tail & m] = value;
        this.tail = tail + 1;
    }

    public int dequeue() {
        int head = this.head;
        if (head == tail) {
            throw new NoSuchElementException("IntRingQueue is empty");
        }

        return pollUnchecked(head);
    }

    public int poll() {
        int head = this.head;
        return head == tail ? Integer.MIN_VALUE : pollUnchecked(head);
    }

    private int pollUnchecked(int head) {
        int[] buffer = data;
        int m = mask;
        int value = buffer[head & m];
        this.head = head + 1;
        return value;
    }

    private void grow() {
        int[] old = data;
        int oldLength = old.length;
        if (oldLength == MAX_CAPACITY) {
            throw new IllegalStateException("IntRingQueue capacity overflow");
        }

        int size = tail - head;
        int[] next = new int[oldLength << 1];

        int start = head & mask;
        int first = Math.min(size, oldLength - start);
        System.arraycopy(old, start, next, 0, first);
        if (first < size) {
            System.arraycopy(old, 0, next, first, size - first);
        }

        data = next;
        mask = next.length - 1;
        head = 0;
        tail = size;
    }

    private static int tableSizeFor(int capacity) {
        int value = Math.max(MIN_CAPACITY, capacity);
        if (value >= MAX_CAPACITY) {
            return MAX_CAPACITY;
        }

        return 1 << (Integer.SIZE - Integer.numberOfLeadingZeros(value - 1));
    }

    @Override
    public String toString() {
        return "IntRingQueue{" +
                "size=" + size() +
                ", capacity=" + data.length +
                ", data=" + Arrays.toString(data) +
                '}';
    }
}
