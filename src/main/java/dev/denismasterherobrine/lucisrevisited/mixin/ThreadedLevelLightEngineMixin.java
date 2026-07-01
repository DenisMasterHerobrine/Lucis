package dev.denismasterherobrine.lucisrevisited.mixin;

import dev.denismasterherobrine.lucisrevisited.light.engine.LucisServices;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisLightPublisher;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisSectionData;
import dev.denismasterherobrine.lucisrevisited.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.util.thread.ProcessorMailbox;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.chunk.ChunkAccess;
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
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
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
        if (!LucisServices.controller().shouldHandleWorldgen()) {
            return;
        }

        long startedAt = System.nanoTime();
        ChunkPos chunkPos = chunk.getPos();
        if (trustEdges) {
            LucisBenchmarkSupport.count("lucis.light_chunk.trust_edges.skip");
            chunk.setLightCorrect(true);
            LucisBenchmarkSupport.record("lucis.light_chunk", System.nanoTime() - startedAt);
            cir.setReturnValue(CompletableFuture.completedFuture(chunk));
            return;
        }

        chunk.setLightCorrect(false);
        CompletableFuture<ChunkAccess> future = LucisServices.controller()
                .relightChunkAsync(lucis$chunkSource, chunk, trustEdges)
                .thenCompose(result -> {
                    LucisBenchmarkSupport.record("lucis.light_chunk.compute", System.nanoTime() - startedAt);
                    long publishStartedAt = System.nanoTime();
                    CompletableFuture<Void> published = lucis$publishAsync(result, chunkPos.x, chunkPos.z);
                    ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
                    return published.thenApply(ignored -> {
                        LucisBenchmarkSupport.record("lucis.light_chunk.publish_wait", System.nanoTime() - publishStartedAt);
                        chunk.setLightCorrect(true);
                        LucisBenchmarkSupport.record("lucis.light_chunk", System.nanoTime() - startedAt);
                        return chunk;
                    });
                });
        cir.setReturnValue(future);
    }

    @Inject(method = "checkBlock", at = @At("HEAD"), cancellable = true)
    private void lucis$checkBlock(BlockPos pos, org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci) {
        if (!LucisServices.controller().shouldHandleBlockChange(pos)) {
            return;
        }

        long startedAt = System.nanoTime();
        LucisBenchmarkSupport.record("lucis.check_block", System.nanoTime() - startedAt);
        ci.cancel();
    }

    @Inject(method = "checkBlock", at = @At("HEAD"))
    private void lucis$startVanillaCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (!LucisServices.controller().enabled()) {
            lucis$checkBlockStartedAt.set(System.nanoTime());
        }
    }

    @Inject(method = "checkBlock", at = @At("RETURN"))
    private void lucis$finishVanillaCheckBlock(BlockPos pos, CallbackInfo ci) {
        if (!LucisServices.controller().enabled()) {
            Long startedAt = lucis$checkBlockStartedAt.get();
            if (startedAt != null) {
                LucisBenchmarkSupport.record("engine.check_block", System.nanoTime() - startedAt);
            }
            lucis$checkBlockStartedAt.remove();
        }
    }

    @Inject(method = "initializeLight", at = @At("HEAD"), cancellable = true)
    private void lucis$initializeLight(ChunkAccess chunk, boolean bl, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!LucisServices.controller().enabled()) {
            return;
        }

        ChunkPos chunkPos = chunk.getPos();
        CompletableFuture<Void> initialized = lucis$addPostTask(chunkPos.x, chunkPos.z, () -> {
            long startedAt = System.nanoTime();
            LevelChunkSection[] sections = chunk.getSections();
            for (int i = 0; i < chunk.getSectionsCount(); i++) {
                if (!sections[i].hasOnlyAir()) {
                    int sectionY = this.levelHeightAccessor.getSectionYFromSectionIndex(i);
                    super.updateSectionStatus(SectionPos.of(chunkPos, sectionY), false);
                }
            }
            super.setLightEnabled(chunkPos, bl);
            super.retainData(chunkPos, false);
            LucisBenchmarkSupport.record("lucis.initialize_light.publish", System.nanoTime() - startedAt);
        });
        ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
        cir.setReturnValue(initialized.thenApply(ignored -> chunk));
    }

    @Override
    public void lucisrevisited$publish(LucisRelightResult result) {
        lucis$addPreTask(result.chunkPos().x, result.chunkPos().z, () -> lucis$publishDirect(result));
    }

    @Unique
    private CompletableFuture<Void> lucis$publishAsync(LucisRelightResult result, int chunkX, int chunkZ) {
        return lucis$addPostTask(chunkX, chunkZ, () -> lucis$publishDirect(result));
    }

    @Unique
    private void lucis$publishDirect(LucisRelightResult result) {
        long startedAt = System.nanoTime();
        for (LucisSectionData section : result.sections()) {
            super.queueSectionData(section.layer(), section.sectionPos(), section.dataLayer());
            super.updateSectionStatus(section.sectionPos(), false);
        }
        LucisBenchmarkSupport.record("lucis.publish_direct", System.nanoTime() - startedAt);
        LucisBenchmarkSupport.count("lucis.publish_direct.sections", result.sections().size());
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
        return ((ChunkMapAccessor) this.lucis$chunkMap).lucisrevisited$getChunkQueueLevel(ChunkPos.asLong(chunkX, chunkZ));
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
        long startedAt = System.nanoTime();
        List<LucisQueuedLightTask> batch = new ArrayList<>();
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
            LucisBenchmarkSupport.record("lucis.publish_batch.drain", System.nanoTime() - startedAt);
            LucisBenchmarkSupport.count("lucis.publish_batch.tasks", batch.size());
        } catch (Throwable throwable) {
            for (LucisQueuedLightTask task : batch) {
                if (task.completion() != null) {
                    task.completion().completeExceptionally(throwable);
                }
            }
            throw throwable;
        } finally {
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
