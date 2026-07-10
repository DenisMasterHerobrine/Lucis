package dev.denismasterherobrine.lucis.test;

import com.mojang.logging.LogUtils;
import dev.denismasterherobrine.lucis.Lucis;
import dev.denismasterherobrine.lucis.config.LucisConfig;
import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import dev.denismasterherobrine.lucis.platform.LucisPlatform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

public final class LucisServerBenchmark {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int FULL_CHUNK_TICKET_RADIUS = 1;
    private static final int PREPARE_TICKET_BUDGET_PER_TICK = 2048;
    private static final int PREPARE_POLL_BUDGET_PER_TICK = 2048;
    private static final TicketType BENCHMARK_TICKET_TYPE = TicketType.FORCED;

    private static BenchmarkRun activeRun;

    private LucisServerBenchmark() {
    }

    public static void onServerStarted(MinecraftServer server) {
        if (!Boolean.getBoolean("lucis.benchmark") || !LucisConfig.debug) {
            return;
        }
        BenchmarkConfig config = BenchmarkConfig.fromProperties();
        String expectedMod = config.expectedMod();
        if (!expectedMod.isBlank() && !LucisPlatform.isModLoaded(expectedMod)) {
            throw new IllegalStateException("Lucis benchmark expected mod is not loaded: " + expectedMod);
        }
        LOGGER.info("Lucis light benchmark starting: mode={} workload={} radiusChunks={} chunkSpan={} origin=({}, {}, {})",
                config.mode(), config.workload(), config.radiusChunks(), config.chunkSpan(), config.originX(), config.y(), config.originZ());
        activeRun = new BenchmarkRun(server, config);
    }

    public static void onServerTick(MinecraftServer server) {
        BenchmarkRun run = activeRun;
        if (run != null && run.server == server) {
            run.tick();
        }
    }

    public static void onServerStopping(MinecraftServer server) {
        if (activeRun != null && activeRun.server == server) {
            activeRun.releaseTickets();
            activeRun = null;
        }
    }

    private static int applyPattern(ServerLevel level, BenchmarkConfig config, BlockState state) {
        return switch (config.workload()) {
            case "block_toggle_sparse" -> applySparsePattern(level, config, state, FLAGS);
            case "block_toggle_border" -> applyBorderPattern(level, config, state, FLAGS);
            case "block_toggle_column" -> applyColumnPattern(level, config, state, FLAGS);
            case "roof_toggle" -> applyRoofPattern(level, config, state, FLAGS);
            case "edge_toggle" -> applyEdgePattern(level, config, state, FLAGS);
            case "sky_hole" -> applySkyHolePattern(level, config, state, FLAGS);
            case "dense_chunk_patch" -> applyDenseChunkPatch(level, config, state, FLAGS);
            default -> applyPreparedPositions(level, config, state, FLAGS);
        };
    }

    private static int applyPreparationPattern(ServerLevel level, BenchmarkConfig config) {
        return switch (config.workload()) {
            case "sky_hole" -> applySkyHolePreparationPattern(level, config, Blocks.STONE.defaultBlockState(), FLAGS);
            default -> applyPattern(level, config, Blocks.STONE.defaultBlockState());
        };
    }

    private static int applySkyHolePreparationPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int roofY = config.y() + 24;
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                pos.set(config.originX() + x, roofY, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int applyPreparedPositions(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 2) {
            for (int x = min; x < max; x += 2) {
                int y = config.y() + ((x * 31 + z * 17) & 15);
                pos.set(config.originX() + x, y, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static BlockState stateForPass(BenchmarkConfig config, int pass) {
        if (isSkyWorkload(config.workload())) {
            return (pass & 1) == 0 ? Blocks.AIR.defaultBlockState() : Blocks.STONE.defaultBlockState();
        }
        return (pass & 1) == 0 ? Blocks.GLOWSTONE.defaultBlockState() : Blocks.STONE.defaultBlockState();
    }

    private static boolean isSkyWorkload(String workload) {
        return switch (workload) {
            case "roof_toggle", "edge_toggle", "sky_hole" -> true;
            default -> false;
        };
    }

    private static int applySparsePattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 8) {
            for (int x = min; x < max; x += 8) {
                int y = config.y() + ((x * 13 + z * 7) & 15);
                pos.set(config.originX() + x, y, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int applyBorderPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 2) {
            int y = config.y() + (z & 15);
            pos.set(config.originX(), y, config.originZ() + z);
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
        }
        for (int x = min; x < max; x += 2) {
            int y = config.y() + ((x * 3) & 15);
            pos.set(config.originX() + x, y, config.originZ());
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
        }
        return changes;
    }

    private static int applyColumnPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z += 8) {
            for (int x = min; x < max; x += 8) {
                for (int dy = 0; dy < 24; dy++) {
                    pos.set(config.originX() + x, config.y() + dy, config.originZ() + z);
                    if (level.setBlock(pos, state, flags)) {
                        changes++;
                    }
                }
            }
        }
        return changes;
    }

    private static int applyRoofPattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        int roofY = config.y() + 20;
        for (int z = min; z < max; z++) {
            for (int x = min; x < max; x++) {
                pos.set(config.originX() + x, roofY, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int applyEdgePattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int min = config.minOffsetBlocks();
        int max = config.maxOffsetBlocksExclusive();
        for (int z = min; z < max; z++) {
            pos.set(config.originX() + 15, config.y() + (z & 7), config.originZ() + z);
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
            pos.set(config.originX() - 16, config.y() + (z & 7), config.originZ() + z);
            if (level.setBlock(pos, state, flags)) {
                changes++;
            }
        }
        return changes;
    }

    private static int applySkyHolePattern(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int roofY = config.y() + 24;
        for (int z = -2; z <= 2; z++) {
            for (int x = -2; x <= 2; x++) {
                pos.set(config.originX() + x, roofY, config.originZ() + z);
                if (level.setBlock(pos, state, flags)) {
                    changes++;
                }
            }
        }
        return changes;
    }

    private static int applyDenseChunkPatch(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                for (int dy = 0; dy < 8; dy++) {
                    pos.set(config.originX() + x, config.y() + dy, config.originZ() + z);
                    if (level.setBlock(pos, state, flags)) {
                        changes++;
                    }
                }
            }
        }
        return changes;
    }

    private static void writeResult(MinecraftServer server, BenchmarkConfig config, BenchmarkStats stats) throws IOException {
        Path output = Path.of(config.output());
        if (!output.isAbsolute()) {
            output = server.getServerDirectory().resolve(output);
        }
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = "{"
                + "\"mode\":\"" + escape(config.mode()) + "\","
                + "\"workload\":\"" + escape(config.workload()) + "\","
                + "\"lucisEnabled\":" + LucisConfig.enabled + ","
                + "\"enableWorldgen\":" + LucisConfig.enableWorldgen + ","
                + "\"enableRuntime\":" + LucisConfig.enableRuntime + ","
                + "\"regionChunks\":" + LucisConfig.regionChunks + ","
                + "\"haloChunks\":" + LucisConfig.haloChunks + ","
                + "\"radiusChunks\":" + config.radiusChunks() + ","
                + "\"chunkSpan\":" + config.chunkSpan() + ","
                + "\"passes\":" + config.passes() + ","
                + "\"warmupPasses\":" + config.warmupPasses() + ","
                + "\"changes\":" + stats.measuredChanges() + ","
                + "\"nanos\":" + stats.measuredNanos() + ","
                + "\"millis\":" + stats.measuredNanos() / 1_000_000.0D + ","
                + "\"nsPerChange\":" + (stats.measuredChanges() == 0 ? 0.0D : (double) stats.measuredNanos() / stats.measuredChanges())
                + "}";
        Files.writeString(output, json + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private enum Phase {
        PREPARE,
        WAIT_PREPARE_LIGHT,
        SETTLE,
        START_PASS,
        WAIT_LIGHT,
        COMPLETE
    }

    private static final class BenchmarkRun {
        private final MinecraftServer server;
        private final ServerLevel level;
        private final BenchmarkConfig config;
        private final int totalPasses;
        private final int minChunkX;
        private final int maxChunkX;
        private final int minChunkZ;
        private final int maxChunkZ;
        private final ChunkPos[] trackedChunks;
        private final boolean[] preparedChunkLoaded;
        private final CompletableFuture<?>[] waitFutures;
        private final long prepareStartNanos;
        private long prepareChunksLoadedNanos;
        private int prepareChanges;
        private int remainingPrepareChunks;
        private int prepareTicketIndex;
        private int preparePollIndex;
        private boolean prepareChunksLoaded;
        private Phase phase = Phase.PREPARE;
        private int pass;
        private int waitTicks;
        private int currentChanges;
        private long currentStartNanos;
        private long waitStartNanos;
        private volatile long waitCompleteNanos;
        private volatile Throwable waitFailure;
        private CompletableFuture<Void> waitFuture;
        private long measuredNanos;
        private int measuredChanges;

        private BenchmarkRun(MinecraftServer server, BenchmarkConfig config) {
            this.server = server;
            this.level = server.overworld();
            int originX = config.originX();
            int originZ = config.originZ();
            if (originX == 0 && originZ == 0) {
                BlockPos spawn = this.level.getRespawnData().pos();
                originX = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(spawn.getX()));
                originZ = SectionPos.sectionToBlockCoord(SectionPos.blockToSectionCoord(spawn.getZ()));
            }
            this.config = config.withOrigin(originX, originZ);
            this.totalPasses = config.warmupPasses() + config.passes();
            int minX = this.config.originX() + this.config.minOffsetBlocks();
            int maxX = this.config.originX() + this.config.maxOffsetBlocksExclusive() - 1;
            int minZ = this.config.originZ() + this.config.minOffsetBlocks();
            int maxZ = this.config.originZ() + this.config.maxOffsetBlocksExclusive() - 1;
            this.minChunkX = minX >> 4;
            this.maxChunkX = maxX >> 4;
            this.minChunkZ = minZ >> 4;
            this.maxChunkZ = maxZ >> 4;
            int chunkCount = (this.maxChunkX - this.minChunkX + 1) * (this.maxChunkZ - this.minChunkZ + 1);
            this.trackedChunks = new ChunkPos[chunkCount];
            this.preparedChunkLoaded = new boolean[chunkCount];
            this.waitFutures = new CompletableFuture<?>[chunkCount];
            this.remainingPrepareChunks = chunkCount;
            this.prepareStartNanos = System.nanoTime();
            LucisBenchmarkSupport.reset();
            int index = 0;
            for (int chunkZ = this.minChunkZ; chunkZ <= this.maxChunkZ; chunkZ++) {
                for (int chunkX = this.minChunkX; chunkX <= this.maxChunkX; chunkX++) {
                    this.trackedChunks[index++] = new ChunkPos(chunkX, chunkZ);
                }
            }
            LOGGER.info("Lucis light benchmark created: mode={} trackedChunks={} chunkRange=[{}, {}]x[{}, {}]",
                    this.config.mode(), this.trackedChunks.length, this.minChunkX, this.maxChunkX, this.minChunkZ, this.maxChunkZ);
        }

        private void tick() {
            try {
                switch (this.phase) {
                    case PREPARE -> this.prepare();
                    case WAIT_PREPARE_LIGHT -> this.waitPrepareLight();
                    case SETTLE -> this.settle();
                    case START_PASS -> this.startPass();
                    case WAIT_LIGHT -> this.waitLight();
                    case COMPLETE -> {
                    }
                }
            } catch (Throwable throwable) {
                LOGGER.error("Lucis light benchmark failed", throwable);
                this.finish();
            }
        }

        private void prepare() {
            if (!this.prepareChunksLoaded) {
                ChunkSource chunkSource = this.level.getChunkSource();
                int ticketBudget = Math.min(PREPARE_TICKET_BUDGET_PER_TICK, this.trackedChunks.length - this.prepareTicketIndex);
                while (ticketBudget > 0 && this.prepareTicketIndex < this.trackedChunks.length) {
                    ChunkPos chunkPos = this.trackedChunks[this.prepareTicketIndex++];
                    this.level.getChunkSource().addTicketWithRadius(BENCHMARK_TICKET_TYPE, chunkPos, FULL_CHUNK_TICKET_RADIUS);
                    ticketBudget--;
                }

                int pollBudget = Math.min(PREPARE_POLL_BUDGET_PER_TICK, this.remainingPrepareChunks);
                int firstPendingIndex = -1;
                while (pollBudget > 0 && this.remainingPrepareChunks > 0) {
                    int pollIndex = this.preparePollIndex++;
                    if (this.preparePollIndex >= this.trackedChunks.length) {
                        this.preparePollIndex = 0;
                    }
                    if (this.preparedChunkLoaded[pollIndex]) {
                        continue;
                    }
                    ChunkPos chunkPos = this.trackedChunks[pollIndex];
                    if (chunkSource.getChunkNow(chunkPos.x(), chunkPos.z()) != null) {
                        this.preparedChunkLoaded[pollIndex] = true;
                        this.remainingPrepareChunks--;
                    } else if (firstPendingIndex < 0) {
                        firstPendingIndex = pollIndex;
                    }
                    pollBudget--;
                }

                if (this.remainingPrepareChunks > 0) {
                    if ((this.waitTicks++ & 31) == 0) {
                        int pendingIndex = firstPendingIndex >= 0 ? firstPendingIndex : this.firstPendingPrepareChunkIndex();
                        ChunkPos chunkPos = pendingIndex >= 0 ? this.trackedChunks[pendingIndex] : this.trackedChunks[Math.min(this.preparePollIndex, this.trackedChunks.length - 1)];
                        LOGGER.info("Lucis light benchmark prepare waiting: mode={} ticketed={}/{} loaded={}/{} pending={} at ({}, {})",
                                this.config.mode(), this.prepareTicketIndex, this.trackedChunks.length,
                                this.trackedChunks.length - this.remainingPrepareChunks, this.trackedChunks.length,
                                this.remainingPrepareChunks, chunkPos.x(), chunkPos.z());
                    }
                    return;
                }
                this.prepareChunksLoaded = true;
                long prepareEndNanos = System.nanoTime();
                this.prepareChunksLoadedNanos = prepareEndNanos;
                LOGGER.info("Lucis light benchmark prepare chunks loaded: mode={} trackedChunks={} elapsedMs={}",
                        this.config.mode(), this.trackedChunks.length, (prepareEndNanos - this.prepareStartNanos) / 1_000_000L);
                LucisBenchmarkSupport.logResult("server_chunk_load_" + this.config.workload(), this.trackedChunks.length,
                        this.prepareStartNanos, prepareEndNanos);
                LucisBenchmarkSupport.reset();
                this.waitTicks = 0;
            }
            this.prepareChanges = applyPreparationPattern(this.level, this.config);
            this.beginLightWait();
            this.phase = Phase.WAIT_PREPARE_LIGHT;
        }

        private void waitPrepareLight() {
            if (!this.waitForLight(true)) {
                return;
            }
            if (this.waitFailure != null) {
                LOGGER.error("Lucis light benchmark prepare wait failed mode={}", this.config.mode(), this.waitFailure);
                this.finish();
                return;
            }
            long completedNanos = this.waitCompleteNanos == 0L ? System.nanoTime() : this.waitCompleteNanos;
            LOGGER.info("Lucis light benchmark prepare complete: mode={} waitTicks={} changes={} loadMs={} lightWaitMs={} totalMs={}",
                    this.config.mode(), this.waitTicks, this.prepareChanges,
                    (this.prepareChunksLoadedNanos - this.prepareStartNanos) / 1_000_000L,
                    Math.max(0L, completedNanos - this.waitStartNanos) / 1_000_000L,
                    (completedNanos - this.prepareStartNanos) / 1_000_000L);
            LucisBenchmarkSupport.record("bench.prepare_light_wait", Math.max(0L, completedNanos - this.waitStartNanos));
            LucisBenchmarkSupport.logResult("server_chunk_prepare_" + this.config.workload(), this.trackedChunks.length,
                    this.prepareStartNanos, completedNanos);
            this.waitTicks = 0;
            this.phase = Phase.SETTLE;
        }

        private void settle() {
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            if (LucisServices.controller().hasPendingRuntimeWork()) {
                this.waitTicks = 0;
                return;
            }
            if (this.waitTicks++ < this.config.settleTicks()) {
                return;
            }
            LucisBenchmarkSupport.reset();
            this.phase = Phase.START_PASS;
        }

        private void beginLightWait() {
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            int index = 0;
            for (int chunkZ = this.minChunkZ; chunkZ <= this.maxChunkZ; chunkZ++) {
                for (int chunkX = this.minChunkX; chunkX <= this.maxChunkX; chunkX++) {
                    this.waitFutures[index++] = this.level.getChunkSource().getLightEngine().waitForPendingTasks(chunkX, chunkZ);
                }
            }
            this.waitTicks = 0;
            this.waitFailure = null;
            this.waitCompleteNanos = 0L;
            this.waitStartNanos = System.nanoTime();
            this.waitFuture = CompletableFuture.allOf(this.waitFutures).whenComplete((ignored, throwable) -> {
                this.waitCompleteNanos = System.nanoTime();
                this.waitFailure = throwable;
            });
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            LOGGER.info("Lucis light benchmark light wait started: mode={} pass={} phase={} futures={}",
                    this.config.mode(), this.pass, this.phase, this.waitFutures.length);
        }

        private boolean waitForLight(boolean prepare) {
            CompletableFuture<Void> future = this.waitFuture;
            if ((future == null || future.isDone()) && !LucisServices.controller().hasPendingRuntimeWork()) {
                return true;
            }
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            if (System.nanoTime() - this.waitStartNanos > this.config.maxWaitNanos()) {
                LOGGER.error("Lucis light benchmark {}timed out after {} ms mode={} pass={}/{}",
                        prepare ? "prepare " : "", this.config.maxWaitNanos() / 1_000_000L,
                        this.config.mode(), this.pass + 1, this.totalPasses);
                this.finish();
                return false;
            }
            this.waitTicks++;
            if ((this.waitTicks & 31) == 0) {
                int pending = 0;
                String pendingChunk = "";
                for (int index = 0; index < this.waitFutures.length; index++) {
                    CompletableFuture<?> wait = this.waitFutures[index];
                    if (wait == null || wait.isDone()) {
                        continue;
                    }
                    pending++;
                    if (pendingChunk.isEmpty()) {
                        ChunkPos chunkPos = this.trackedChunks[index];
                        pendingChunk = chunkPos.x() + "," + chunkPos.z();
                    }
                }
                boolean runtimePending = LucisServices.controller().hasPendingRuntimeWork();
                LOGGER.info("Lucis light benchmark still waiting: mode={} phase={} pass={}/{} waitTicks={} pendingFutures={}{}",
                        this.config.mode(), this.phase, this.pass + 1, this.totalPasses, this.waitTicks, pending,
                        (pendingChunk.isEmpty() ? "" : " firstPending=" + pendingChunk)
                                + (runtimePending ? " runtimePending=true" : ""));
            }
            return false;
        }

        private void startPass() {
            if (this.pass >= this.totalPasses) {
                try {
                    BenchmarkStats stats = new BenchmarkStats(this.measuredChanges, this.measuredNanos);
                    writeResult(this.server, this.config, stats);
                    LOGGER.info("Lucis light benchmark complete: mode={} changes={} measuredMs={} nsPerChange={}",
                            this.config.mode(), stats.measuredChanges(), stats.measuredNanos() / 1_000_000L,
                            stats.measuredChanges() == 0 ? 0L : stats.measuredNanos() / stats.measuredChanges());
                    LucisBenchmarkSupport.logResult("server_light_" + this.config.workload(), stats.measuredChanges(), 0L, stats.measuredNanos());
                } catch (IOException exception) {
                    LOGGER.error("Failed to write Lucis light benchmark result", exception);
                }
                this.finish();
                return;
            }
            BlockState state = stateForPass(this.config, this.pass);
            this.currentStartNanos = System.nanoTime();
            this.currentChanges = applyPattern(this.level, this.config, state);
            LucisBenchmarkSupport.record("bench.apply_pattern", System.nanoTime() - this.currentStartNanos);
            LOGGER.info("Lucis light benchmark pass start {}/{} state={} changes={}",
                    this.pass + 1, this.totalPasses, state.getBlock().builtInRegistryHolder().key().identifier(), this.currentChanges);
            this.beginLightWait();
            this.phase = Phase.WAIT_LIGHT;
        }

        private void waitLight() {
            if (!this.waitForLight(false)) {
                return;
            }
            if (this.waitFailure != null) {
                LOGGER.error("Lucis light benchmark wait failed on pass {}/{} mode={}",
                        this.pass + 1, this.totalPasses, this.config.mode(), this.waitFailure);
                this.finish();
                return;
            }
            long completedNanos = this.waitCompleteNanos;
            long elapsed = (completedNanos == 0L ? System.nanoTime() : completedNanos) - this.currentStartNanos;
            LucisBenchmarkSupport.record("bench.wait_after_apply", Math.max(0L, (completedNanos == 0L ? System.nanoTime() : completedNanos) - this.waitStartNanos));
            if (this.pass >= this.config.warmupPasses()) {
                this.measuredNanos += elapsed;
                this.measuredChanges += this.currentChanges;
            }
            LOGGER.info("Lucis light benchmark pass {}/{} state={} changes={} elapsedMs={} waitTicks={}",
                    this.pass + 1, this.totalPasses,
                    stateForPass(this.config, this.pass).getBlock().builtInRegistryHolder().key().identifier(),
                    this.currentChanges, elapsed / 1_000_000L, this.waitTicks);
            this.pass++;
            if (this.pass == this.config.warmupPasses()) {
                LucisBenchmarkSupport.reset();
            }
            this.phase = Phase.START_PASS;
        }

        private void finish() {
            this.phase = Phase.COMPLETE;
            this.releaseTickets();
            activeRun = null;
            this.server.halt(false);
        }

        private void releaseTickets() {
            for (ChunkPos chunkPos : this.trackedChunks) {
                this.level.getChunkSource().removeTicketWithRadius(BENCHMARK_TICKET_TYPE, chunkPos, FULL_CHUNK_TICKET_RADIUS);
            }
        }

        private int firstPendingPrepareChunkIndex() {
            for (int index = 0; index < this.preparedChunkLoaded.length; index++) {
                if (!this.preparedChunkLoaded[index]) {
                    return index;
                }
            }
            return -1;
        }
    }

    private record BenchmarkConfig(String mode, String workload, String expectedMod, String output, int radiusChunks, int chunkSpan,
                                   int passes, int warmupPasses, int settleTicks, int originX, int originZ, int y, long maxWaitNanos) {
        private static BenchmarkConfig fromProperties() {
            return new BenchmarkConfig(
                    stringProperty("lucis.benchmark.mode", "lucis"),
                    stringProperty("lucis.benchmark.workload", "block_toggle_dense"),
                    stringProperty("lucis.benchmark.expectedMod", ""),
                    stringProperty("lucis.benchmark.output", "lucis-light-benchmark.jsonl"),
                    intProperty("lucis.benchmark.radiusChunks", 3),
                    intProperty("lucis.benchmark.chunkSpan", 0),
                    intProperty("lucis.benchmark.passes", 6),
                    intProperty("lucis.benchmark.warmupPasses", 2),
                    intProperty("lucis.benchmark.settleTicks", 20),
                    intProperty("lucis.benchmark.originX", 0),
                    intProperty("lucis.benchmark.originZ", 0),
                    intProperty("lucis.benchmark.y", 80),
                    longProperty("lucis.benchmark.maxWaitNanos", 120_000_000_000L)
            );
        }

        private BenchmarkConfig withOrigin(int originX, int originZ) {
            return new BenchmarkConfig(mode, workload, expectedMod, output, radiusChunks, chunkSpan, passes, warmupPasses, settleTicks,
                    originX, originZ, y, maxWaitNanos);
        }

        private int spanChunks() {
            return chunkSpan > 0 ? chunkSpan : radiusChunks * 2;
        }

        private int minOffsetBlocks() {
            return -(spanChunks() / 2) * 16;
        }

        private int maxOffsetBlocksExclusive() {
            return minOffsetBlocks() + spanChunks() * 16;
        }

        private static String stringProperty(String key, String fallback) {
            String value = System.getProperty(key);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static int intProperty(String key, int fallback) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Integer.parseInt(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }

        private static long longProperty(String key, long fallback) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
    }

    private record BenchmarkStats(int measuredChanges, long measuredNanos) {
    }
}
