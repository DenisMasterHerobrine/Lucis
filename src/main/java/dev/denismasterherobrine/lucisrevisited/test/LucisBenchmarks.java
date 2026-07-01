package dev.denismasterherobrine.lucisrevisited.test;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder("lucisbench")
public final class LucisBenchmarks {
    private LucisBenchmarks() {
    }

    public static void register(RegisterGameTestsEvent event) {
        event.register(LucisBenchmarks.class);
    }

    @GameTest(templateNamespace = "lucisrevisited", template = "empty", batch = "lucis_benchmark", timeoutTicks = 1400)
    public static void benchmarkRuntimeLightWallTime(GameTestHelper helper) {
        int iterations = 128;
        int delayTicks = 3;
        long[] startedAt = new long[1];
        BlockPos[] sources = new BlockPos[] {
                new BlockPos(1, 2, 1),
                new BlockPos(3, 2, 1),
                new BlockPos(1, 2, 3),
                new BlockPos(3, 2, 3)
        };

        var sequence = helper.startSequence()
                .thenExecute(() -> {
                    LucisBenchmarkSupport.reset();
                    startedAt[0] = System.nanoTime();
                });

        for (int i = 0; i < iterations; i++) {
            BlockPos pos = sources[i & 3];
            sequence.thenExecute(() -> helper.setBlock(pos, Blocks.GLOWSTONE));
            sequence.thenIdle(delayTicks);
            sequence.thenExecute(() -> helper.setBlock(pos, Blocks.AIR));
            sequence.thenIdle(delayTicks);
        }

        sequence.thenExecute(() -> LucisBenchmarkSupport.logResult("runtime_light_wall_time", iterations, startedAt[0], System.nanoTime()))
                .thenSucceed();
    }
}
