package dev.denismasterherobrine.lucisrevisited.mixin;

import dev.denismasterherobrine.lucisrevisited.light.engine.LucisServices;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisRelightResult;
import dev.denismasterherobrine.lucisrevisited.light.runtime.LucisSectionData;
import dev.denismasterherobrine.lucisrevisited.test.LucisBenchmarkSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

@Mixin(ThreadedLevelLightEngine.class)
public abstract class ThreadedLevelLightEngineMixin extends LevelLightEngine {
    @Unique
    private LightChunkGetter lucis$chunkSource;
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
    }

    @Inject(method = "lightChunk", at = @At("HEAD"), cancellable = true)
    private void lucis$lightChunk(ChunkAccess chunk, boolean trustEdges, CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!LucisServices.controller().shouldHandleWorldgen()) {
            return;
        }

        long startedAt = System.nanoTime();
        LucisRelightResult result = LucisServices.controller().relightChunk(lucis$chunkSource, chunk, trustEdges);
        lucis$publish(result);
        chunk.setLightCorrect(true);
        LucisBenchmarkSupport.record("lucis.light_chunk", System.nanoTime() - startedAt);
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
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

        setLightEnabled(chunk.getPos(), bl);
        retainData(chunk.getPos(), false);
        cir.setReturnValue(CompletableFuture.completedFuture(chunk));
    }

    @Unique
    private void lucis$publish(LucisRelightResult result) {
        for (LucisSectionData section : result.sections()) {
            queueSectionData(section.layer(), section.sectionPos(), section.dataLayer());
            updateSectionStatus(section.sectionPos(), false);
        }
        ((ThreadedLevelLightEngine) (Object) this).tryScheduleUpdate();
    }
}
