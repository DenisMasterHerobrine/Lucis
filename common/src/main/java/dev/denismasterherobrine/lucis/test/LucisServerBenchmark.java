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
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;

public final class LucisServerBenchmark {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
    private static final int FULL_CHUNK_TICKET_RADIUS = 1;
    private static final int PREPARE_TICKET_BUDGET_PER_TICK = 2048;
    private static final int PREPARE_POLL_BUDGET_PER_TICK = 2048;
    private static final TicketType<ChunkPos> BENCHMARK_TICKET_TYPE = TicketType.create("lucis_revisited_benchmark", Comparator.comparingLong(ChunkPos::toLong));

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
            case "structure_cube" -> applyStructureCube(level, config, state, FLAGS);
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
            case "structure_cube" -> applyStructureCube(level, config, Blocks.AIR.defaultBlockState(), FLAGS);
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
        if ("structure_cube".equals(config.workload())) {
            return (pass & 1) == 0 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
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

    private static int applyStructureCube(ServerLevel level, BenchmarkConfig config, BlockState state, int flags) {
        int changes = 0;
        int width = config.structureWidth();
        int height = config.structureHeight();
        int depth = config.structureDepth();
        long maxAttempts = config.maxApplyBlocks() <= 0L
                ? config.plannedChanges()
                : Math.min(config.plannedChanges(), config.maxApplyBlocks());
        long attempts = 0L;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LucisServices.controller().beginRuntimeBulkWrite();
        LucisServices.controller().recordRuntimeBulkBounds(level,
                config.originX(), config.originZ(), config.originX() + width, config.originZ() + depth,
                config.plannedChanges());
        if (config.lightOnly()) {
            LucisServices.controller().endRuntimeBulkWrite();
            return (int) Math.min(Integer.MAX_VALUE, config.plannedChanges());
        }
        try {
            outer:
            for (int y = 0; y < height; y++) {
                for (int z = 0; z < depth; z++) {
                    for (int x = 0; x < width; x++) {
                        if (attempts++ >= maxAttempts) {
                            break outer;
                        }
                        pos.set(config.originX() + x, config.y() + y, config.originZ() + z);
                        if (level.setBlock(pos, state, flags)) {
                            changes++;
                        }
                    }
                }
            }
        } finally {
            LucisServices.controller().endRuntimeBulkWrite();
        }
        return changes;
    }

    private static void writeResult(MinecraftServer server, BenchmarkConfig config, BenchmarkStats stats) throws IOException {
        Path output = Path.of(config.output());
        if (!output.isAbsolute()) {
            output = server.getServerDirectory().toPath().resolve(output);
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
                + "\"structureSize\":" + config.structureSize() + ","
                + "\"structureWidth\":" + config.structureWidth() + ","
                + "\"structureHeight\":" + config.structureHeight() + ","
                + "\"structureDepth\":" + config.structureDepth() + ","
                + "\"plannedChanges\":" + config.plannedChanges() + ","
                + "\"maxApplyBlocks\":" + config.maxApplyBlocks() + ","
                + "\"skipPreparation\":" + config.skipPreparation() + ","
                + "\"lightOnly\":" + config.lightOnly() + ","
                + "\"trackRegionAnchorsOnly\":" + config.trackRegionAnchorsOnly() + ","
                + "\"prepareMaxTicks\":" + config.prepareMaxTicks() + ","
                + "\"strictDrainBeforeMeasure\":" + config.strictDrainBeforeMeasure() + ","
                + "\"waitWorldgenDuringMeasured\":" + config.waitWorldgenDuringMeasured() + ","
                + "\"capped\":" + config.capped() + ","
                + "\"passes\":" + config.passes() + ","
                + "\"warmupPasses\":" + config.warmupPasses() + ","
                + "\"changes\":" + stats.measuredChanges() + ","
                + "\"nanos\":" + stats.measuredNanos() + ","
                + "\"millis\":" + stats.measuredNanos() / 1_000_000.0D + ","
                + "\"applyNanos\":" + stats.measuredApplyNanos() + ","
                + "\"applyMillis\":" + stats.measuredApplyNanos() / 1_000_000.0D + ","
                + "\"waitNanos\":" + stats.measuredWaitNanos() + ","
                + "\"waitMillis\":" + stats.measuredWaitNanos() / 1_000_000.0D + ","
                + "\"prepareLoadNanos\":" + stats.prepareLoadNanos() + ","
                + "\"prepareWaitNanos\":" + stats.prepareWaitNanos() + ","
                + "\"preMeasureQuiesceNanos\":" + stats.preMeasureQuiesceNanos() + ","
                + "\"measuredBarrierNanos\":" + stats.measuredBarrierNanos() + ","
                + "\"measuredBarrierCount\":" + stats.measuredBarrierCount() + ","
                + "\"measuredPasses\":" + stats.measuredPasses() + ","
                + "\"measuredChangesPerPass\":" + (stats.measuredPasses() == 0 ? 0.0D : (double) stats.measuredChanges() / stats.measuredPasses()) + ","
                + "\"minPassNanos\":" + stats.minPassNanosOrZero() + ","
                + "\"maxPassNanos\":" + stats.maxPassNanos() + ","
                + "\"projectedMillis\":" + stats.projectedMillis(config) + ","
                + "\"status\":\"" + escape(stats.status()) + "\","
                + "\"nsPerChangeApplyOnly\":" + (stats.measuredChanges() == 0 ? 0.0D : (double) stats.measuredApplyNanos() / stats.measuredChanges()) + ","
                + "\"nsPerChangeWaitOnly\":" + (stats.measuredChanges() == 0 ? 0.0D : (double) stats.measuredWaitNanos() / stats.measuredChanges()) + ","
                + "\"msPerMeasuredPass\":" + (stats.measuredPasses() == 0 ? 0.0D : stats.measuredNanos() / 1_000_000.0D / stats.measuredPasses()) + ","
                + "\"worldgenPendingBeforeMeasuredPass\":" + stats.worldgenPendingBeforeMeasuredPass() + ","
                + "\"runtimePendingBeforeMeasuredPass\":" + stats.runtimePendingBeforeMeasuredPass() + ","
                + "\"worldgenPendingAfterApply\":" + stats.worldgenPendingAfterApply() + ","
                + "\"runtimePendingAfterApply\":" + stats.runtimePendingAfterApply() + ","
                + "\"measuredWorldgenWaitEnabled\":" + config.waitWorldgenDuringMeasured() + ","
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
        QUIESCE_BEFORE_MEASURE,
        START_PASS,
        WAIT_LIGHT,
        COMPLETE
    }

    private enum WaitMode {
        FULL_DRAIN(true, true),
        RUNTIME_ONLY(true, false);

        private final boolean waitRuntime;
        private final boolean waitWorldgen;

        WaitMode(boolean waitRuntime, boolean waitWorldgen) {
            this.waitRuntime = waitRuntime;
            this.waitWorldgen = waitWorldgen;
        }
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
        private final ChunkPos[] prepareChunks;
        private final ChunkPos[] waitChunks;
        private final boolean[] preparedChunkLoaded;
        private CompletableFuture<?>[] waitFutures;
        private ChunkPos[] activeWaitChunks;
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
        private long currentApplyNanos;
        private long waitStartNanos;
        private volatile long waitCompleteNanos;
        private volatile Throwable waitFailure;
        private CompletableFuture<Void> waitFuture;
        private WaitMode waitMode = WaitMode.FULL_DRAIN;
        private boolean preMeasureQuiesced;
        private long prepareLoadNanos;
        private long prepareWaitNanos;
        private long preMeasureQuiesceStartNanos;
        private long preMeasureQuiesceNanos;
        private long measuredBarrierStartNanos;
        private long measuredBarrierNanos;
        private long measuredNanos;
        private long measuredApplyNanos;
        private long measuredWaitNanos;
        private long minPassNanos = Long.MAX_VALUE;
        private long maxPassNanos;
        private int measuredChanges;
        private int measuredPasses;
        private int measuredBarrierCount;
        private int worldgenPendingBeforeMeasuredPass;
        private int runtimePendingBeforeMeasuredPass;
        private int worldgenPendingAfterApply;
        private int runtimePendingAfterApply;

        private BenchmarkRun(MinecraftServer server, BenchmarkConfig config) {
            this.server = server;
            this.level = server.overworld();
            int originX = config.originX();
            int originZ = config.originZ();
            if (originX == 0 && originZ == 0) {
                BlockPos spawn = this.level.getSharedSpawnPos();
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
            this.prepareChunks = createChunkGrid(this.minChunkX - 1, this.maxChunkX + 1, this.minChunkZ - 1, this.maxChunkZ + 1, 1);
            int runtimeRegionChunks = runtimeRegionChunks();
            int waitMinChunkX = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.minChunkX, runtimeRegionChunks) * runtimeRegionChunks
                    : this.minChunkX;
            int waitMaxChunkX = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.maxChunkX, runtimeRegionChunks) * runtimeRegionChunks
                    : this.maxChunkX;
            int waitMinChunkZ = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.minChunkZ, runtimeRegionChunks) * runtimeRegionChunks
                    : this.minChunkZ;
            int waitMaxChunkZ = this.config.trackRegionAnchorsOnly()
                    ? Math.floorDiv(this.maxChunkZ, runtimeRegionChunks) * runtimeRegionChunks
                    : this.maxChunkZ;
            int waitStepChunks = this.config.trackRegionAnchorsOnly() ? runtimeRegionChunks : 1;
            this.waitChunks = createChunkGrid(waitMinChunkX, waitMaxChunkX, waitMinChunkZ, waitMaxChunkZ, waitStepChunks);
            this.preparedChunkLoaded = new boolean[this.prepareChunks.length];
            this.waitFutures = new CompletableFuture<?>[0];
            this.remainingPrepareChunks = this.prepareChunks.length;
            this.prepareStartNanos = System.nanoTime();
            LucisBenchmarkSupport.reset();
            LOGGER.info("Lucis light benchmark created: mode={} prepareChunks={} waitChunks={} chunkRange=[{}, {}]x[{}, {}]",
                    this.config.mode(), this.prepareChunks.length, this.waitChunks.length,
                    this.minChunkX, this.maxChunkX, this.minChunkZ, this.maxChunkZ);
        }

        private void tick() {
            try {
                switch (this.phase) {
                    case PREPARE -> this.prepare();
                    case WAIT_PREPARE_LIGHT -> this.waitPrepareLight();
                    case QUIESCE_BEFORE_MEASURE -> this.waitPreMeasureQuiesce();
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
                int ticketBudget = Math.min(PREPARE_TICKET_BUDGET_PER_TICK, this.prepareChunks.length - this.prepareTicketIndex);
                while (ticketBudget > 0 && this.prepareTicketIndex < this.prepareChunks.length) {
                    ChunkPos chunkPos = this.prepareChunks[this.prepareTicketIndex++];
                    this.level.getChunkSource().addRegionTicket(BENCHMARK_TICKET_TYPE, chunkPos, FULL_CHUNK_TICKET_RADIUS, chunkPos);
                    ticketBudget--;
                }

                int pollBudget = Math.min(PREPARE_POLL_BUDGET_PER_TICK, this.remainingPrepareChunks);
                int firstPendingIndex = -1;
                while (pollBudget > 0 && this.remainingPrepareChunks > 0) {
                    int pollIndex = this.preparePollIndex++;
                    if (this.preparePollIndex >= this.prepareChunks.length) {
                        this.preparePollIndex = 0;
                    }
                    if (this.preparedChunkLoaded[pollIndex]) {
                        continue;
                    }
                    ChunkPos chunkPos = this.prepareChunks[pollIndex];
                    if (chunkSource.getChunkNow(chunkPos.x, chunkPos.z) != null) {
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
                        ChunkPos chunkPos = pendingIndex >= 0 ? this.prepareChunks[pendingIndex] : this.prepareChunks[Math.min(this.preparePollIndex, this.prepareChunks.length - 1)];
                        LOGGER.info("Lucis light benchmark prepare waiting: mode={} ticketed={}/{} loaded={}/{} pending={} at ({}, {})",
                                this.config.mode(), this.prepareTicketIndex, this.prepareChunks.length,
                                this.prepareChunks.length - this.remainingPrepareChunks, this.prepareChunks.length,
                                this.remainingPrepareChunks, chunkPos.x, chunkPos.z);
                    }
                    if (this.config.prepareMaxTicks() > 0 && this.waitTicks > this.config.prepareMaxTicks()) {
                        LOGGER.error("Lucis light benchmark prepare timed out: mode={} workload={} pending={} ticks={}",
                                this.config.mode(), this.config.workload(), this.remainingPrepareChunks, this.waitTicks);
                        this.writeAndFinish("prepare_timeout", 0, System.nanoTime() - this.prepareStartNanos);
                    }
                    return;
                }
                this.prepareChunksLoaded = true;
                long prepareEndNanos = System.nanoTime();
                this.prepareChunksLoadedNanos = prepareEndNanos;
                LOGGER.info("Lucis light benchmark prepare chunks loaded: mode={} prepareChunks={} waitChunks={} elapsedMs={}",
                        this.config.mode(), this.prepareChunks.length, this.waitChunks.length,
                        (prepareEndNanos - this.prepareStartNanos) / 1_000_000L);
                LucisBenchmarkSupport.logResult("server_chunk_load_" + this.config.workload(), this.prepareChunks.length,
                        this.prepareStartNanos, prepareEndNanos);
                LucisBenchmarkSupport.reset();
                this.waitTicks = 0;
            }
            if (this.config.skipPreparation()) {
                this.prepareChanges = 0;
            } else {
                this.prepareChanges = applyPreparationPattern(this.level, this.config);
            }
            this.beginLightWait(WaitMode.FULL_DRAIN, this.prepareChunks);
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
            this.prepareLoadNanos = Math.max(0L, this.prepareChunksLoadedNanos - this.prepareStartNanos);
            this.prepareWaitNanos = Math.max(0L, completedNanos - this.waitStartNanos);
            LOGGER.info("Lucis light benchmark prepare complete: mode={} waitTicks={} changes={} loadMs={} lightWaitMs={} totalMs={}",
                    this.config.mode(), this.waitTicks, this.prepareChanges,
                    this.prepareLoadNanos / 1_000_000L,
                    this.prepareWaitNanos / 1_000_000L,
                    (completedNanos - this.prepareStartNanos) / 1_000_000L);
            LucisBenchmarkSupport.record("bench.prepare_light_wait", this.prepareWaitNanos);
            LucisBenchmarkSupport.logResult("server_chunk_prepare_" + this.config.workload(), this.prepareChunks.length,
                    this.prepareStartNanos, completedNanos);
            this.waitTicks = 0;
            LucisBenchmarkSupport.reset();
            if (this.config.strictDrainBeforeMeasure() && this.config.warmupPasses() == 0) {
                this.beginPreMeasureQuiesce();
            } else {
                this.phase = Phase.START_PASS;
            }
        }

        private void beginLightWait(WaitMode mode, ChunkPos[] chunks) {
            if (this.waitFutures.length != chunks.length) {
                this.waitFutures = new CompletableFuture<?>[chunks.length];
            }
            this.waitMode = mode;
            this.activeWaitChunks = chunks;
            this.level.getChunkSource().getLightEngine().tryScheduleUpdate();
            for (int index = 0; index < chunks.length; index++) {
                ChunkPos chunkPos = chunks[index];
                this.waitFutures[index] = this.waitForPendingLightTasks(chunkPos);
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
            LOGGER.info("Lucis light benchmark light wait started: mode={} pass={} phase={} waitMode={} futures={}",
                    this.config.mode(), this.pass, this.phase, this.waitMode, this.waitFutures.length);
        }

        private CompletableFuture<?> waitForPendingLightTasks(ChunkPos chunkPos) {
            Object lightEngine = this.level.getChunkSource().getLightEngine();
            try {
                Object result = lightEngine.getClass()
                        .getMethod("waitForPendingTasks", int.class, int.class)
                        .invoke(lightEngine, chunkPos.x, chunkPos.z);
                if (result instanceof CompletableFuture<?> future) {
                    return future;
                }
            } catch (ReflectiveOperationException | LinkageError ignored) {
            }
            return CompletableFuture.completedFuture(null);
        }

        private boolean waitForLight(boolean prepare) {
            CompletableFuture<Void> future = this.waitFuture;
            boolean runtimePending = this.waitMode.waitRuntime && LucisServices.controller().hasPendingRuntimeWork();
            boolean worldgenPending = this.shouldWaitWorldgen() && LucisServices.controller().hasPendingWorldgenWork();
            if ((future == null || future.isDone()) && !runtimePending && !worldgenPending) {
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
                        ChunkPos[] chunks = this.activeWaitChunks == null ? this.waitChunks : this.activeWaitChunks;
                        ChunkPos chunkPos = chunks[index];
                        pendingChunk = chunkPos.x + "," + chunkPos.z;
                    }
                }
                LOGGER.info("Lucis light benchmark still waiting: mode={} phase={} pass={}/{} waitMode={} waitTicks={} pendingFutures={}{}",
                        this.config.mode(), this.phase, this.pass + 1, this.totalPasses, this.waitMode, this.waitTicks, pending,
                        (pendingChunk.isEmpty() ? "" : " firstPending=" + pendingChunk)
                                + (runtimePending ? " runtimePending=true" : "")
                                + (worldgenPending ? " worldgenPending=true" : ""));
            }
            return false;
        }

        private boolean shouldWaitWorldgen() {
            return this.waitMode.waitWorldgen || (this.phase == Phase.WAIT_LIGHT && this.config.waitWorldgenDuringMeasured());
        }

        private void beginPreMeasureQuiesce() {
            this.preMeasureQuiesceStartNanos = System.nanoTime();
            this.beginLightWait(WaitMode.FULL_DRAIN, this.prepareChunks);
            this.phase = Phase.QUIESCE_BEFORE_MEASURE;
        }

        private void beginMeasuredBarrierDrain() {
            this.measuredBarrierStartNanos = System.nanoTime();
            this.measuredBarrierCount++;
            this.beginLightWait(WaitMode.FULL_DRAIN, this.prepareChunks);
            this.phase = Phase.QUIESCE_BEFORE_MEASURE;
        }

        private void waitPreMeasureQuiesce() {
            if (!this.waitForLight(false)) {
                return;
            }
            if (this.waitFailure != null) {
                LOGGER.error("Lucis light benchmark pre-measure quiesce failed mode={}", this.config.mode(), this.waitFailure);
                this.finish();
                return;
            }
            long completedNanos = this.waitCompleteNanos == 0L ? System.nanoTime() : this.waitCompleteNanos;
            boolean measuredBarrier = this.measuredBarrierStartNanos != 0L;
            long drainNanos;
            if (this.measuredBarrierStartNanos != 0L) {
                drainNanos = Math.max(0L, completedNanos - this.measuredBarrierStartNanos);
                this.measuredBarrierNanos += drainNanos;
                this.measuredBarrierStartNanos = 0L;
            } else {
                drainNanos = Math.max(0L, completedNanos - this.preMeasureQuiesceStartNanos);
                this.preMeasureQuiesceNanos += drainNanos;
            }
            this.preMeasureQuiesced = true;
            this.waitTicks = 0;
            LucisBenchmarkSupport.reset();
            LOGGER.info("Lucis light benchmark {} complete: mode={} elapsedMs={}",
                    measuredBarrier ? "measured barrier drain" : "pre-measure quiesce",
                    this.config.mode(), drainNanos / 1_000_000L);
            this.phase = Phase.START_PASS;
        }

        private void startPass() {
            if (this.pass >= this.totalPasses) {
                try {
                    BenchmarkStats stats = this.stats("ok", this.measuredChanges, this.measuredNanos);
                    writeResult(this.server, this.config, stats);
                    LOGGER.info("Lucis light benchmark complete: mode={} changes={} measuredMs={} nsPerChange={}",
                            this.config.mode(), stats.measuredChanges(), stats.measuredNanos() / 1_000_000L,
                            stats.measuredChanges() == 0 ? 0L : stats.measuredNanos() / stats.measuredChanges());
                    LucisBenchmarkSupport.logResult("server_light_" + this.config.workload(),
                            (int) Math.min(Integer.MAX_VALUE, stats.measuredChanges()), 0L, stats.measuredNanos());
                } catch (IOException exception) {
                    LOGGER.error("Failed to write Lucis light benchmark result", exception);
                }
                this.finish();
                return;
            }
            if (this.config.strictDrainBeforeMeasure()
                    && !this.preMeasureQuiesced
                    && this.pass >= this.config.warmupPasses()) {
                this.beginPreMeasureQuiesce();
                return;
            }
            if (this.config.strictDrainBeforeMeasure()
                    && this.pass >= this.config.warmupPasses()
                    && (LucisServices.controller().hasPendingWorldgenWork()
                    || LucisServices.controller().hasPendingRuntimeWork())) {
                this.beginMeasuredBarrierDrain();
                return;
            }
            if (this.pass >= this.config.warmupPasses()) {
                if (LucisServices.controller().hasPendingWorldgenWork()) {
                    this.worldgenPendingBeforeMeasuredPass++;
                }
                if (LucisServices.controller().hasPendingRuntimeWork()) {
                    this.runtimePendingBeforeMeasuredPass++;
                }
            }
            BlockState state = stateForPass(this.config, this.pass);
            this.currentStartNanos = System.nanoTime();
            this.currentChanges = applyPattern(this.level, this.config, state);
            this.currentApplyNanos = System.nanoTime() - this.currentStartNanos;
            LucisBenchmarkSupport.record("bench.apply_pattern", this.currentApplyNanos);
            LOGGER.info("Lucis light benchmark pass start {}/{} state={} changes={}",
                    this.pass + 1, this.totalPasses, state.getBlock().builtInRegistryHolder().key().location(), this.currentChanges);
            if (this.pass >= this.config.warmupPasses()) {
                if (LucisServices.controller().hasPendingWorldgenWork()) {
                    this.worldgenPendingAfterApply++;
                }
                if (LucisServices.controller().hasPendingRuntimeWork()) {
                    this.runtimePendingAfterApply++;
                }
            }
            this.beginLightWait(this.pass >= this.config.warmupPasses() ? WaitMode.RUNTIME_ONLY : WaitMode.FULL_DRAIN, this.waitChunks);
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
            long endNanos = completedNanos == 0L ? System.nanoTime() : completedNanos;
            long elapsed = endNanos - this.currentStartNanos;
            long waitNanos = Math.max(0L, endNanos - this.waitStartNanos);
            LucisBenchmarkSupport.record("bench.wait_after_apply", waitNanos);
            if (this.pass >= this.config.warmupPasses()) {
                this.measuredNanos += elapsed;
                this.measuredApplyNanos += this.currentApplyNanos;
                this.measuredWaitNanos += waitNanos;
                this.measuredChanges += this.currentChanges;
                this.measuredPasses++;
                this.minPassNanos = Math.min(this.minPassNanos, elapsed);
                this.maxPassNanos = Math.max(this.maxPassNanos, elapsed);
            }
            LOGGER.info("Lucis light benchmark pass {}/{} state={} changes={} elapsedMs={} waitTicks={}",
                    this.pass + 1, this.totalPasses,
                    stateForPass(this.config, this.pass).getBlock().builtInRegistryHolder().key().location(),
                    this.currentChanges, elapsed / 1_000_000L, this.waitTicks);
            this.pass++;
            if (this.pass == this.config.warmupPasses()) {
                LucisBenchmarkSupport.reset();
                if (this.config.strictDrainBeforeMeasure()) {
                    this.beginPreMeasureQuiesce();
                    return;
                }
            }
            this.phase = Phase.START_PASS;
        }

        private BenchmarkStats stats(String status, long changes, long nanos) {
            return new BenchmarkStats(changes, nanos, this.measuredApplyNanos, this.measuredWaitNanos,
                    this.prepareLoadNanos, this.prepareWaitNanos, this.preMeasureQuiesceNanos,
                    this.measuredBarrierNanos, this.measuredBarrierCount,
                    this.measuredPasses, this.minPassNanos, this.maxPassNanos,
                    this.worldgenPendingBeforeMeasuredPass, this.runtimePendingBeforeMeasuredPass,
                    this.worldgenPendingAfterApply, this.runtimePendingAfterApply, status);
        }

        private void finish() {
            this.phase = Phase.COMPLETE;
            this.releaseTickets();
            activeRun = null;
            this.server.halt(false);
        }

        private void writeAndFinish(String status, long changes, long nanos) {
            try {
                writeResult(this.server, this.config, this.stats(status, changes, nanos));
            } catch (IOException exception) {
                LOGGER.error("Failed to write Lucis light benchmark result", exception);
            }
            this.finish();
        }

        private void releaseTickets() {
            for (ChunkPos chunkPos : this.prepareChunks) {
                this.level.getChunkSource().removeRegionTicket(BENCHMARK_TICKET_TYPE, chunkPos, FULL_CHUNK_TICKET_RADIUS, chunkPos);
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

        private static ChunkPos[] createChunkGrid(int minChunkX, int maxChunkX, int minChunkZ, int maxChunkZ, int stepChunks) {
            int step = Math.max(1, stepChunks);
            int chunkCount = ((maxChunkX - minChunkX) / step + 1) * ((maxChunkZ - minChunkZ) / step + 1);
            ChunkPos[] chunks = new ChunkPos[chunkCount];
            int index = 0;
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ += step) {
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX += step) {
                    chunks[index++] = new ChunkPos(chunkX, chunkZ);
                }
            }
            return chunks;
        }
    }

    private record BenchmarkConfig(String mode, String workload, String expectedMod, String output, int radiusChunks, int chunkSpan,
                                   int structureSize, int structureWidth, int structureHeight, int structureDepth,
                                   long maxApplyBlocks, boolean skipPreparation, boolean lightOnly,
                                   boolean trackRegionAnchorsOnly, int prepareMaxTicks,
                                   boolean strictDrainBeforeMeasure, boolean waitWorldgenDuringMeasured,
                                   int passes, int warmupPasses, int originX, int originZ, int y, long maxWaitNanos) {
        private static BenchmarkConfig fromProperties() {
            int structureSize = intProperty("lucis.benchmark.structureSize", 16);
            return new BenchmarkConfig(
                    stringProperty("lucis.benchmark.mode", "lucis"),
                    stringProperty("lucis.benchmark.workload", "block_toggle_dense"),
                    stringProperty("lucis.benchmark.expectedMod", ""),
                    stringProperty("lucis.benchmark.output", "lucis-light-benchmark.jsonl"),
                    intProperty("lucis.benchmark.radiusChunks", 3),
                    intProperty("lucis.benchmark.chunkSpan", 0),
                    structureSize,
                    intProperty("lucis.benchmark.structureWidth", structureSize),
                    intProperty("lucis.benchmark.structureHeight", structureSize),
                    intProperty("lucis.benchmark.structureDepth", structureSize),
                    longProperty("lucis.benchmark.maxApplyBlocks", 0L),
                    booleanProperty("lucis.benchmark.skipPreparation", false),
                    booleanProperty("lucis.benchmark.lightOnly", false),
                    booleanProperty("lucis.benchmark.trackRegionAnchorsOnly", false),
                    intProperty("lucis.benchmark.prepareMaxTicks", 0),
                    booleanProperty("lucis.benchmark.strictDrainBeforeMeasure", true),
                    booleanProperty("lucis.benchmark.waitWorldgenDuringMeasured", false),
                    intProperty("lucis.benchmark.passes", 6),
                    intProperty("lucis.benchmark.warmupPasses", 2),
                    intProperty("lucis.benchmark.originX", 0),
                    intProperty("lucis.benchmark.originZ", 0),
                    intProperty("lucis.benchmark.y", 80),
                    longProperty("lucis.benchmark.maxWaitNanos", 120_000_000_000L)
            );
        }

        private BenchmarkConfig withOrigin(int originX, int originZ) {
            return new BenchmarkConfig(mode, workload, expectedMod, output, radiusChunks, chunkSpan,
                    structureSize, structureWidth, structureHeight, structureDepth,
                    maxApplyBlocks, skipPreparation, lightOnly, trackRegionAnchorsOnly, prepareMaxTicks,
                    strictDrainBeforeMeasure, waitWorldgenDuringMeasured,
                    passes, warmupPasses,
                    originX, originZ, y, maxWaitNanos);
        }

        private int spanChunks() {
            return chunkSpan > 0 ? chunkSpan : radiusChunks * 2;
        }

        private int minOffsetBlocks() {
            if ("structure_cube".equals(workload)) {
                return 0;
            }
            return -(spanChunks() / 2) * 16;
        }

        private int maxOffsetBlocksExclusive() {
            if ("structure_cube".equals(workload)) {
                return Math.max(1, Math.max(structureWidth, structureDepth));
            }
            return minOffsetBlocks() + spanChunks() * 16;
        }

        private long plannedChanges() {
            if ("structure_cube".equals(workload)) {
                return (long) Math.max(1, structureWidth)
                        * Math.max(1, structureHeight)
                        * Math.max(1, structureDepth);
            }
            return 0L;
        }

        private boolean capped() {
            return maxApplyBlocks > 0L && plannedChanges() > maxApplyBlocks;
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

        private static boolean booleanProperty(String key, boolean fallback) {
            String value = System.getProperty(key);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return Boolean.parseBoolean(value.trim());
        }
    }

    private record BenchmarkStats(long measuredChanges, long measuredNanos, long measuredApplyNanos,
                                  long measuredWaitNanos, long prepareLoadNanos, long prepareWaitNanos,
                                  long preMeasureQuiesceNanos, long measuredBarrierNanos,
                                  int measuredBarrierCount, int measuredPasses, long minPassNanos,
                                  long maxPassNanos, int worldgenPendingBeforeMeasuredPass,
                                  int runtimePendingBeforeMeasuredPass, int worldgenPendingAfterApply,
                                  int runtimePendingAfterApply, String status) {
        private double projectedMillis(BenchmarkConfig config) {
            if (measuredChanges <= 0L || config.plannedChanges() <= 0L || !config.capped()) {
                return 0.0D;
            }
            return measuredNanos / 1_000_000.0D * ((double) config.plannedChanges() / measuredChanges);
        }

        private long minPassNanosOrZero() {
            return minPassNanos == Long.MAX_VALUE ? 0L : minPassNanos;
        }
    }

    private static int runtimeRegionChunks() {
        return Math.max(1, Math.min(Integer.getInteger("lucis.runtimeRegionChunks", 1), 16));
    }
}

