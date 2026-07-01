package dev.denismasterherobrine.lucisrevisited.light.engine;

import dev.denismasterherobrine.lucisrevisited.light.region.OwnedRegionCache;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionBounds;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionSide;
import dev.denismasterherobrine.lucisrevisited.light.region.RegionLightData;
import dev.denismasterherobrine.lucisrevisited.light.region.RuntimeRegionState;
import dev.denismasterherobrine.lucisrevisited.light.region.BoundaryDelta;
import dev.denismasterherobrine.lucisrevisited.light.runtime.RuntimeLightChange;
import dev.denismasterherobrine.lucisrevisited.light.util.IntBucketQueue;
import dev.denismasterherobrine.lucisrevisited.light.util.IntRingQueue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LucisLightingKernelTest {
    @Test
    void skylightColumnFallsThroughAir() {
        RegionLightData data = region();
        LucisSkyLightEngine engine = new LucisSkyLightEngine();

        engine.compute(data);

        int top = data.index(0, 31, 0);
        int mid = data.index(0, 16, 0);
        int bottom = data.index(0, 0, 0);
        assertEquals(15, data.skyLight[top]);
        assertEquals(15, data.skyLight[mid]);
        assertEquals(15, data.skyLight[bottom]);
    }

    @Test
    void skylightStopsUnderOpaqueRoofAndBleedsSideways() {
        RegionLightData data = region();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                data.opacity[data.index(x, 20, z)] = 15;
            }
        }
        data.opacity[data.index(0, 20, 0)] = 0;

        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);

        assertEquals(0, data.skyLight[data.index(8, 19, 8)]);
        assertTrue((data.skyLight[data.index(1, 19, 0)] & 0xF) > 0);
    }

    @Test
    void blockLightPropagatesWithOpacityLoss() {
        RegionLightData data = region();
        int source = data.index(4, 4, 4);
        int east = data.index(5, 4, 4);
        int twoEast = data.index(6, 4, 4);
        data.emission[source] = 15;
        data.opacity[east] = 0;
        data.opacity[twoEast] = 1;

        LucisBlockLightEngine engine = new LucisBlockLightEngine();
        engine.compute(data);

        assertEquals(15, data.blockLight[source]);
        assertEquals(14, data.blockLight[east]);
        assertEquals(12, data.blockLight[twoEast]);
    }

    @Test
    void runtimeBlockLightRemovalAndReaddWorks() {
        RegionLightData data = region();
        LucisBlockLightEngine engine = new LucisBlockLightEngine();
        int source = data.index(4, 4, 4);
        int east = data.index(5, 4, 4);
        data.emission[source] = 15;
        engine.compute(data);
        assertEquals(14, data.blockLight[east]);

        data.emission[source] = 0;
        engine.applyRuntimeChanges(data, List.of(new RuntimeLightChange(4, 4, 4, (byte) 0, (byte) 0, (byte) 15, (byte) 0)));
        assertEquals(0, data.blockLight[source]);
        assertEquals(0, data.blockLight[east]);

        data.emission[source] = 15;
        engine.applyRuntimeChanges(data, List.of(new RuntimeLightChange(4, 4, 4, (byte) 0, (byte) 0, (byte) 0, (byte) 15)));
        assertEquals(15, data.blockLight[source]);
        assertEquals(14, data.blockLight[east]);
    }

    @Test
    void runtimeSkylightColumnRecomputeAttenuatesOpaquePlacement() {
        RegionLightData data = region();
        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        engine.compute(data);
        assertEquals(15, data.skyLight[data.index(4, 3, 4)]);

        data.opacity[data.index(4, 4, 4)] = 15;
        engine.applyRuntimeChanges(data, List.of(new RuntimeLightChange(4, 4, 4, (byte) 0, (byte) 15, (byte) 0, (byte) 0)));
        assertEquals(0, data.skyLight[data.index(4, 4, 4)]);
        assertEquals(14, data.skyLight[data.index(4, 3, 4)]);
    }

    @Test
    void boundaryDeltaAppliesToOppositeHaloFace() {
        RegionLightData data = regionWithHalo();
        RuntimeRegionState state = new RuntimeRegionState(data);
        LucisBoundaryEngine engine = new LucisBoundaryEngine();
        BoundaryDelta delta = new BoundaryDelta(1L, 2L, RegionSide.WEST, 0, 2, 16, 16, filled((byte) 7, 512), filled((byte) 9, 512));

        state.putBoundarySnapshot(engine.toSnapshot(delta));
        var changes = engine.apply(state);

        int index = data.index(16, 0, 0);
        assertEquals(7, data.blockLight[index]);
        assertEquals(9, data.skyLight[index]);
        assertTrue(!changes.isEmpty());
    }

    @Test
    void regionSideOppositeIsCorrect() {
        assertEquals(RegionSide.EAST, RegionSide.WEST.opposite());
        assertEquals(RegionSide.WEST, RegionSide.EAST.opposite());
        assertEquals(RegionSide.SOUTH, RegionSide.NORTH.opposite());
        assertEquals(RegionSide.NORTH, RegionSide.SOUTH.opposite());
    }

    @Test
    void bucketQueueRejectsEmptyDequeue() {
        IntBucketQueue queue = new IntBucketQueue(16, 4);
        assertTrue(queue.isEmpty());
        assertEquals(-1, queue.dequeueLevel());
        assertThrows(NoSuchElementException.class, queue::dequeue);
    }

    @Test
    void ringQueueRejectsEmptyDequeue() {
        IntRingQueue queue = new IntRingQueue(4);
        assertThrows(NoSuchElementException.class, queue::dequeue);
    }

    @Test
    void skylightEngineSupportsConcurrentReuse() throws InterruptedException {
        LucisSkyLightEngine engine = new LucisSkyLightEngine();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Runnable task = () -> {
            ready.countDown();
            try {
                start.await();
                for (int i = 0; i < 64; i++) {
                    RegionLightData data = region();
                    data.opacity[data.index(i & 15, 20, (i * 3) & 15)] = 15;
                    engine.compute(data);
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
            }
        };

        Thread first = new Thread(task, "lucis-sky-test-1");
        Thread second = new Thread(task, "lucis-sky-test-2");
        first.start();
        second.start();
        ready.await();
        start.countDown();
        first.join();
        second.join();

        Throwable throwable = failure.get();
        if (throwable instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (throwable instanceof Error error) {
            throw error;
        }
        assertNull(throwable);
    }

    @Test
    void ownedRegionCacheOnlyExposesInitializedStatesForBoundaryWork() {
        OwnedRegionCache cache = new OwnedRegionCache();
        RegionBounds bounds = region().bounds;
        long key = bounds.coreRegionKey();

        assertNull(cache.getInitialized(key));

        RuntimeRegionState state = cache.getOrCreate(bounds);
        assertNull(cache.getInitialized(key));

        state.markInitialized();
        assertSame(state, cache.getInitialized(key));
    }

    private static RegionLightData region() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 0, 16, 16, 0, 32, 0, 2, 32, 256, 8192);
        return new RegionLightData(bounds);
    }

    private static RegionLightData regionWithHalo() {
        RegionBounds bounds = new RegionBounds(0, 0, 1, 1, 48, 48, 0, 32, 0, 2, 32, 2304, 73728);
        return new RegionLightData(bounds);
    }

    private static byte[] filled(byte value, int size) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, value);
        return data;
    }
}
