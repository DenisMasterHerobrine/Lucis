package dev.denismasterherobrine.lucisrevisited.test;

import dev.denismasterherobrine.lucisrevisited.LucisRevisited;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;

@GameTestHolder(LucisRevisited.MODID)
public final class LucisGameTests {
    private LucisGameTests() {
    }

    public static void register(RegisterGameTestsEvent event) {
        event.register(LucisGameTests.class);
    }

    @GameTest(template = "empty", batch = "lucis_runtime", timeoutTicks = 200)
    public static void blockLightPropagatesAfterPlacement(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 2, 1);
        BlockPos target = new BlockPos(2, 2, 1);
        helper.setBlock(source, Blocks.GLOWSTONE);
        helper.startSequence()
                .thenExecuteAfter(5, () -> assertBlockLightAtLeast(helper, target, 13, "expected runtime block light propagation"))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "lucis_runtime", timeoutTicks = 200)
    public static void blockLightClearsAfterRemoval(GameTestHelper helper) {
        BlockPos source = new BlockPos(1, 2, 1);
        BlockPos target = new BlockPos(2, 2, 1);
        int[] baseline = new int[1];
        helper.startSequence()
                .thenExecute(() -> baseline[0] = blockLight(helper, target))
                .thenExecute(() -> helper.setBlock(source, Blocks.GLOWSTONE))
                .thenExecuteAfter(5, () -> assertBlockLightAtLeast(helper, target, Math.max(13, baseline[0] + 8), "expected initial light"))
                .thenExecute(() -> helper.setBlock(source, Blocks.AIR))
                .thenExecuteAfter(5, () -> assertBlockLightAtMost(helper, target, baseline[0] + 1, "expected runtime block light removal"))
                .thenSucceed();
    }

    @GameTest(template = "empty", batch = "lucis_runtime", timeoutTicks = 300)
    public static void skylightRespondsToRoofPlacement(GameTestHelper helper) {
        BlockPos below = new BlockPos(2, 2, 2);
        BlockPos roof = new BlockPos(2, 5, 2);
        helper.startSequence()
                .thenExecute(() -> buildSkylightShaft(helper, below, roof.getY()))
                .thenExecuteAfter(5, () -> assertSkyLightAtLeast(helper, below, 14, "expected open sky light before roof"))
                .thenExecute(() -> helper.setBlock(roof, Blocks.STONE))
                .thenExecuteAfter(5, () -> assertSkyLightAtMost(helper, below, 1, "expected skylight cut by roof"))
                .thenSucceed();
    }

    private static void buildSkylightShaft(GameTestHelper helper, BlockPos below, int roofY) {
        for (int y = below.getY(); y <= roofY; y++) {
            helper.setBlock(new BlockPos(below.getX() - 1, y, below.getZ()), Blocks.STONE);
            helper.setBlock(new BlockPos(below.getX() + 1, y, below.getZ()), Blocks.STONE);
            helper.setBlock(new BlockPos(below.getX(), y, below.getZ() - 1), Blocks.STONE);
            helper.setBlock(new BlockPos(below.getX(), y, below.getZ() + 1), Blocks.STONE);
        }
    }

    private static int blockLight(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getBrightness(LightLayer.BLOCK, helper.absolutePos(pos));
    }

    private static void assertBlockLightAtLeast(GameTestHelper helper, BlockPos pos, int expected, String message) {
        int light = blockLight(helper, pos);
        helper.assertTrue(light >= expected, message + ": got " + light);
    }

    private static void assertBlockLightAtMost(GameTestHelper helper, BlockPos pos, int expected, String message) {
        int light = blockLight(helper, pos);
        helper.assertTrue(light <= expected, message + ": got " + light);
    }

    private static void assertSkyLightAtLeast(GameTestHelper helper, BlockPos pos, int expected, String message) {
        int light = helper.getLevel().getBrightness(LightLayer.SKY, helper.absolutePos(pos));
        helper.assertTrue(light >= expected, message + ": got " + light);
    }

    private static void assertSkyLightAtMost(GameTestHelper helper, BlockPos pos, int expected, String message) {
        int light = helper.getLevel().getBrightness(LightLayer.SKY, helper.absolutePos(pos));
        helper.assertTrue(light <= expected, message + ": got " + light);
    }
}
