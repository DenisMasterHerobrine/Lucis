package dev.denismasterherobrine.lucis.mixin;

import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import dev.denismasterherobrine.lucis.light.runtime.LucisLightPublisher;
import dev.denismasterherobrine.lucis.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucis.light.runtime.LucisSectionData;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunk;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin extends LevelLightEngine implements LucisLightPublisher {
    @Unique
    private static final long LUCIS_PUBLISH_COALESCE_NANOS = 250_000L;

    @Unique
    private LightChunkGetter lucis$chunkSource;
    @Unique
    private ChunkMap lucis$chunkMap;
    @Unique
    private ProcessorMailbox<Runnable> lucis$taskMailbox;
    @Unique
    private ProcessorHandle<ChunkTaskPriorityQueueSorter.Message<Runnable>> lucis$sorterMailbox;
    @Unique
    private final Object lucis$publishLock = new Object();
    @Unique
    private final Deque<LucisQueuedLightTask> lucis$pendingLightTasks = new ArrayDeque<>();
    @Unique
    private final ArrayDeque<LucisQueuedLightTask> lucis$publishBatch = new ArrayDeque<>(1000);
    @Unique
    private final ArrayDeque<LucisSectionData> lucis$pendingLightNotifications = new ArrayDeque<>(1000);
    @Unique
    private final AtomicBoolean lucis$publishScheduled = new AtomicBoolean();
    @Unique
    private final ThreadLocal<Long> lucis$checkBlockStartedAt = new ThreadLocal<>();

    protected ThreadedLevelLightEngineMixin(LightChunkGetter chunkSource, boolean hasBlockLight, boolean hasSkyLight) {
        super(chunkSource, hasBlockLight, hasSkyLight);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lucis$init(LightChunkGetter chunkSource, net.minecraft.server.level.ChunkMap chunkMap, boolean hasSkyLight,
                            net.minecraft.util.thread.ProcessorMailbox<Runnable> taskMailbox,
                            net.minecraft.util.thread.ProcessorHandle<net.minecraft.server.level.ChunkTaskPriorityQueueSorter.Message<Runnable>> sorterMailbox,
                            CallbackInfo ci) {
        this.lucis$chunkSource = chunkSource;
        this.lucis$chunkMap = chunkMap;
        this.lucis$taskMailbox = taskMailbox;
        this.lucis$sorterMailbox = sorterMailbox;
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
                    CompletableFuture<Void> published = lucis$publishAsync(result, chunkPos.x, chunkPos.z);
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

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void lucis$checkBlock(BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!LucisServices.controller().shouldHandleBlockChange(lucis$chunkSource, pos)) {
            return;
        }

        long startedAt = LucisBenchmarkSupport.start();
        LucisBenchmarkSupport.recordSince("lucis.check_block", startedAt);
        ci.cancel();
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
        CompletableFuture<Void> initialized = lucis$addPostTask(chunkPos.x, chunkPos.z, () -> {
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
    public void lucis$publish(LucisRelightResult result, LightChunk expectedChunk) {
        lucis$addPreTask(result.chunkPos().x, result.chunkPos().z, () -> lucis$publishDirect(result, expectedChunk));
    }

    @Unique
    private CompletableFuture<Void> lucis$publishAsync(LucisRelightResult result, int chunkX, int chunkZ) {
        return lucis$addPostTask(chunkX, chunkZ, () -> lucis$publishDirect(result, null));
    }

    @Unique
    private void lucis$publishDirect(LucisRelightResult result, LightChunk expectedChunk) {
        if (expectedChunk != null) {
            ChunkPos chunkPos = result.chunkPos();
            if (this.lucis$chunkSource.getChunkForLighting(chunkPos.x, chunkPos.z) != expectedChunk) {
                LucisBenchmarkSupport.count("lucis.runtime.commit.staleChunk");
                return;
            }
        }
        long startedAt = LucisBenchmarkSupport.start();
        for (LucisSectionData section : result.sections()) {
            super.queueSectionData(section.layer(), section.sectionPos(), section.dataLayer());
            super.updateSectionStatus(section.sectionPos(), false);
            this.lucis$pendingLightNotifications.addLast(section);
        }
        LucisBenchmarkSupport.recordSince("lucis.publish_direct", startedAt);
        LucisBenchmarkSupport.count("lucis.publish_direct.sections", result.sections().size());
    }

    @Unique
    private void lucis$notifyPublishedLightSections() {
        ArrayDeque<LucisSectionData> notifications = this.lucis$pendingLightNotifications;
        while (!notifications.isEmpty()) {
            LucisSectionData section = notifications.removeFirst();
            this.lucis$chunkSource.onLightUpdate(section.layer(), section.sectionPos());
        }
    }

    @Unique
    private void lucis$addPreTask(int chunkX, int chunkZ, Runnable task) {
        lucis$enqueueLightTask(chunkX, chunkZ, lucis$queueLevel(chunkX, chunkZ), task, null);
    }

    @Unique
    private CompletableFuture<Void> lucis$addPostTask(int chunkX, int chunkZ, Runnable preTask) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        lucis$enqueueLightTask(chunkX, chunkZ, lucis$queueLevel(chunkX, chunkZ), preTask, future);
        return future;
    }

    @Unique
    private IntSupplier lucis$queueLevel(int chunkX, int chunkZ) {
        return ((ChunkMapAccessor) this.lucis$chunkMap).lucis$getChunkQueueLevel(ChunkPos.asLong(chunkX, chunkZ));
    }

    @Unique
    private void lucis$enqueueLightTask(int chunkX, int chunkZ, IntSupplier queueLevel, Runnable preTask, CompletableFuture<Void> completion) {
        this.lucis$sorterMailbox.tell(ChunkTaskPriorityQueueSorter.message(() -> {
            synchronized (this.lucis$publishLock) {
                this.lucis$pendingLightTasks.addLast(new LucisQueuedLightTask(preTask, completion));
                LucisBenchmarkSupport.count("lucis.publish_batch.queued");
            }
            lucis$schedulePublishDrain();
        }, ChunkPos.asLong(chunkX, chunkZ), queueLevel));
    }

    @Unique
    private void lucis$schedulePublishDrain() {
        if (this.lucis$taskMailbox == null || !this.lucis$publishScheduled.compareAndSet(false, true)) {
            return;
        }
        CompletableFuture.delayedExecutor(LUCIS_PUBLISH_COALESCE_NANOS, TimeUnit.NANOSECONDS)
                .execute(() -> this.lucis$taskMailbox.tell(this::lucis$drainQueuedLightTasks));
    }

    @Unique
    private void lucis$drainQueuedLightTasks() {
        long startedAt = LucisBenchmarkSupport.start();
        ArrayDeque<LucisQueuedLightTask> batch = this.lucis$publishBatch;
        batch.clear();
        this.lucis$pendingLightNotifications.clear();
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
                lucis$notifyPublishedLightSections();
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
            this.lucis$pendingLightNotifications.clear();
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
