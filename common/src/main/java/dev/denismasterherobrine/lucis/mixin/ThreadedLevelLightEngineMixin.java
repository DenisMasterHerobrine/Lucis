package dev.denismasterherobrine.lucis.mixin;

import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import dev.denismasterherobrine.lucis.light.runtime.LucisLightPublisher;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.LucisSectionData;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskDispatcher;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ConsecutiveExecutor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin extends LevelLightEngine implements LucisLightPublisher {
    @Unique
    private static final long LUCIS_RUNTIME_QUIET_NANOS = Long.getLong("lucis.runtimeQuietNanos", 2_000_000L);
    @Unique
    private static final long LUCIS_RUNTIME_BULK_QUIET_NANOS = Long.getLong("lucis.runtimeBulkQuietNanos", 10_000_000L);
    @Unique
    private static final int LUCIS_RUNTIME_BULK_THRESHOLD = Integer.getInteger("lucis.runtimeBulkThreshold", 64);
    @Unique
    private static final ScheduledExecutorService LUCIS_RUNTIME_KICK_TIMER = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().daemon(true).name("lucis-runtime-kick").factory());

    @Unique
    private LightChunkGetter lucis$chunkSource;
    @Unique
    private ConsecutiveExecutor lucis$consecutiveExecutor;
    @Unique
    private final Object lucis$publishLock = new Object();
    @Unique
    private final Deque<LucisQueuedLightTask> lucis$pendingLightTasks = new ArrayDeque<>();
    @Unique
    private final ArrayDeque<LucisQueuedLightTask> lucis$publishBatch = new ArrayDeque<>(1000);
    @Unique
    private final AtomicBoolean lucis$publishScheduled = new AtomicBoolean();
    @Unique
    private final AtomicBoolean lucis$runtimeKickScheduled = new AtomicBoolean();
    @Unique
    private final AtomicLong lucis$lastRuntimeChangeNanos = new AtomicLong();
    @Unique
    private final AtomicInteger lucis$runtimeBurstChanges = new AtomicInteger();
    @Unique
    private final ThreadLocal<Long> lucis$checkBlockStartedAt = new ThreadLocal<>();

    protected ThreadedLevelLightEngineMixin(LightChunkGetter chunkSource, boolean hasBlockLight, boolean hasSkyLight) {
        super(chunkSource, hasBlockLight, hasSkyLight);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lucis$init(LightChunkGetter chunkSource, net.minecraft.server.level.ChunkMap chunkMap, boolean hasSkyLight,
                            ConsecutiveExecutor consecutiveExecutor,
                            ChunkTaskDispatcher taskDispatcher,
                            CallbackInfo ci) {
        this.lucis$chunkSource = chunkSource;
        this.lucis$consecutiveExecutor = consecutiveExecutor;
    }

    @Inject(method = "lightChunk", at = @At("HEAD"), cancellable = true)
    private void lucis$lightChunk(ChunkAccess chunk, boolean trustEdges, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!LucisServices.controller().shouldHandleWorldgen(lucis$chunkSource, chunk)) {
            return;
        }

        long startedAt = LucisBenchmarkSupport.start();
        ChunkPos chunkPos = chunk.getPos();
        chunk.setLightCorrect(false);
        CompletableFuture<ChunkAccess> future = LucisServices.controller()
                .relightChunkAsync(lucis$chunkSource, chunk, trustEdges)
                .thenCompose(result -> {
                    LucisBenchmarkSupport.recordSince("lucis.light_chunk.compute", startedAt);
                    long publishStartedAt = LucisBenchmarkSupport.start();
                    CompletableFuture<Void> published = lucis$publish(result);
                    ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
                    return published.thenApply(ignored -> {
                        LucisBenchmarkSupport.recordSince("lucis.light_chunk.publish_wait", publishStartedAt);
                        chunk.setLightCorrect(true);
                        LucisBenchmarkSupport.recordSince("lucis.light_chunk", startedAt);
                        return chunk;
                    });
                });
        cir.setReturnValue(future);
    }

    @Inject(method = "tryScheduleUpdate", at = @At("HEAD"))
    private void lucis$kickRuntimeProcessing(CallbackInfo ci) {
        lucis$flushRuntimeKick();
    }

    @Inject(method = "checkBlock", at = @At("HEAD"))
    private void lucis$startVanillaCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (!LucisServices.controller().shouldHandleBlockChange(lucis$chunkSource, pos) && LucisBenchmarkSupport.enabled()) {
            lucis$checkBlockStartedAt.set(LucisBenchmarkSupport.start());
        }
    }

    @Inject(method = "checkBlock", at = @At("RETURN"))
    private void lucis$finishVanillaCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (!LucisServices.controller().shouldHandleBlockChange(lucis$chunkSource, pos)) {
            Long startedAt = lucis$checkBlockStartedAt.get();
            if (startedAt != null) {
                LucisBenchmarkSupport.recordSince("engine.check_block", startedAt);
            }
            lucis$checkBlockStartedAt.remove();
        }
    }

    @Inject(method = "initializeLight", at = @At("HEAD"), cancellable = true)
    private void lucis$initializeLight(ChunkAccess chunk, boolean bl, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!LucisServices.controller().shouldHandleLightInitialization(lucis$chunkSource, chunk)) {
            return;
        }

        ChunkPos chunkPos = chunk.getPos();
        CompletableFuture<Void> initialized = lucis$addPostTask(chunkPos.x(), chunkPos.z(), () -> {
            long startedAt = LucisBenchmarkSupport.start();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                if (!sections[i].hasOnlyAir()) {
                    int sectionY = this.levelHeightAccessor.getSectionYFromSectionIndex(i);
                    super.updateSectionStatus(SectionPos.of(chunkPos, sectionY), false);
                }
            }
            super.setLightEnabled(chunkPos, bl);
            super.retainData(chunkPos, false);
            LucisBenchmarkSupport.recordSince("lucis.initialize_light.publish", startedAt);
        });
        ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
        cir.setReturnValue(initialized.thenApply(ignored -> chunk));
    }

    @Override
    public CompletableFuture<Void> lucis$publish(LucisRelightResult result) {
        return lucis$addPostTask(result.chunkPos().x(), result.chunkPos().z(), () -> lucis$publishDirect(result));
    }

    @Override
    public void lucis$onRuntimeChange() {
        lucis$requestRuntimeKick();
    }

    @Override
    public void lucis$requestRuntimeDrain() {
        lucis$ensureRuntimeKickScheduled();
    }

    @Unique
    private void lucis$requestRuntimeKick() {
        this.lucis$lastRuntimeChangeNanos.set(System.nanoTime());
        this.lucis$runtimeBurstChanges.incrementAndGet();
        lucis$ensureRuntimeKickScheduled();
    }

    @Unique
    private void lucis$ensureRuntimeKickScheduled() {
        if (!this.lucis$runtimeKickScheduled.compareAndSet(false, true)) {
            return;
        }
        long remaining = Math.max(0L, lucis$currentRuntimeQuietNanos()
                - (System.nanoTime() - this.lucis$lastRuntimeChangeNanos.get()));
        LUCIS_RUNTIME_KICK_TIMER.schedule(this::lucis$runDebouncedRuntimeKick, remaining, TimeUnit.NANOSECONDS);
    }

    @Unique
    private void lucis$runDebouncedRuntimeKick() {
        if (!this.lucis$runtimeKickScheduled.get()) {
            return;
        }
        long remaining = lucis$currentRuntimeQuietNanos()
                - (System.nanoTime() - this.lucis$lastRuntimeChangeNanos.get());
        if (remaining > 0L) {
            LUCIS_RUNTIME_KICK_TIMER.schedule(this::lucis$runDebouncedRuntimeKick, remaining, TimeUnit.NANOSECONDS);
            return;
        }
        if (!this.lucis$runtimeKickScheduled.compareAndSet(true, false)) {
            return;
        }
        lucis$scheduleRuntimeKick();
    }

    @Unique
    private void lucis$flushRuntimeKick() {
        this.lucis$runtimeKickScheduled.set(false);
        this.lucis$runtimeBurstChanges.set(0);
        LucisServices.controller().kickRuntime((ThreadedLevelLightEngine) (Object) this, this.lucis$chunkSource);
    }

    @Unique
    private long lucis$currentRuntimeQuietNanos() {
        return this.lucis$runtimeBurstChanges.get() >= LUCIS_RUNTIME_BULK_THRESHOLD
                ? LUCIS_RUNTIME_BULK_QUIET_NANOS
                : LUCIS_RUNTIME_QUIET_NANOS;
    }

    @Unique
    private void lucis$scheduleRuntimeKick() {
        ConsecutiveExecutor executor = this.lucis$consecutiveExecutor;
        if (executor == null) {
            LucisServices.controller().kickRuntime((ThreadedLevelLightEngine) (Object) this, this.lucis$chunkSource);
            return;
        }
        executor.schedule(() -> LucisServices.controller().kickRuntime(
                (ThreadedLevelLightEngine) (Object) this, this.lucis$chunkSource));
    }

    @Redirect(
            method = "addTask(IILjava/util/function/IntSupplier;Lnet/minecraft/server/level/ThreadedLevelLightEngine$TaskType;Ljava/lang/Runnable;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ChunkTaskDispatcher;submit(Ljava/lang/Runnable;JLjava/util/function/IntSupplier;)V"
            )
    )
    private void lucis$wakeAfterTaskInsert(ChunkTaskDispatcher dispatcher, Runnable task, long chunkPos,
                                           IntSupplier queueLevel) {
        dispatcher.submit(() -> {
            task.run();
            ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
        }, chunkPos, queueLevel);
    }

    @Unique
    private void lucis$publishDirect(LucisRelightResult result) {
        long startedAt = LucisBenchmarkSupport.start();
        for (LucisSectionData section : result.sections()) {
            super.queueSectionData(section.layer(), section.sectionPos(), section.dataLayer());
            super.updateSectionStatus(section.sectionPos(), false);
        }
        LucisBenchmarkSupport.recordSince("lucis.publish_direct", startedAt);
        LucisBenchmarkSupport.count("lucis.publish_direct.sections", result.sections().size());
    }

    @Unique
    private CompletableFuture<Void> lucis$addPostTask(int chunkX, int chunkZ, Runnable preTask) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        lucis$enqueueLightTask(preTask, future);
        return future;
    }

    @Unique
    private void lucis$enqueueLightTask(Runnable preTask, CompletableFuture<Void> completion) {
        synchronized (this.lucis$publishLock) {
            this.lucis$pendingLightTasks.addLast(new LucisQueuedLightTask(preTask, completion));
            LucisBenchmarkSupport.count("lucis.publish_batch.queued");
        }
        lucis$schedulePublishDrain();
    }

    @Unique
    private void lucis$schedulePublishDrain() {
        if (this.lucis$consecutiveExecutor == null || !this.lucis$publishScheduled.compareAndSet(false, true)) {
            return;
        }
        this.lucis$consecutiveExecutor.schedule(this::lucis$drainQueuedLightTasks);
    }

    @Unique
    private void lucis$drainQueuedLightTasks() {
        long startedAt = LucisBenchmarkSupport.start();
        ArrayDeque<LucisQueuedLightTask> batch = this.lucis$publishBatch;
        batch.clear();
        synchronized (this.lucis$publishLock) {
            int count = Math.min(1000, this.lucis$pendingLightTasks.size());
            for (int index = 0; index < count; index++) {
                batch.add(this.lucis$pendingLightTasks.removeFirst());
            }
        }

        try {
            for (LucisQueuedLightTask task : batch) {
                task.runnable().run();
            }
            if (!batch.isEmpty()) {
                super.runLightUpdates();
            }
            for (LucisQueuedLightTask task : batch) {
                if (task.completion() != null) {
                    task.completion().complete(null);
                }
            }
            LucisBenchmarkSupport.recordSince("lucis.publish_batch.drain", startedAt);
            LucisBenchmarkSupport.count("lucis.publish_batch.tasks", batch.size());
        } catch (Throwable throwable) {
            for (LucisQueuedLightTask task : batch) {
                if (task.completion() != null) {
                    task.completion().completeExceptionally(throwable);
                }
            }
            throw throwable;
        } finally {
            batch.clear();
            this.lucis$publishScheduled.set(false);
            boolean hasMore;
            synchronized (this.lucis$publishLock) {
                hasMore = !this.lucis$pendingLightTasks.isEmpty();
            }
            if (hasMore) {
                lucis$schedulePublishDrain();
            }
        }
    }

    @Unique
    private record LucisQueuedLightTask(Runnable runnable, CompletableFuture<Void> completion) {
    }
}
