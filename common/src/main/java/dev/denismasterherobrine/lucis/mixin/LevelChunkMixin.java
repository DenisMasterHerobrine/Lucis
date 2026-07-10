package dev.denismasterherobrine.lucis.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import dev.denismasterherobrine.lucis.light.runtime.LucisLightPublisher;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Shadow
    @Final
    private Level level;

    @Redirect(
            method = "setBlockState",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/lighting/LevelLightEngine;checkBlock(Lnet/minecraft/core/BlockPos;)V"
            )
    )
    private void lucis$enqueueRuntimeLight(LevelLightEngine lightEngine, BlockPos checkedPos,
                                           BlockPos pos, BlockState state, int flags,
                                           @Local(index = 10) BlockState oldState) {
        if (this.level instanceof ServerLevel serverLevel) {
            LevelChunk chunk = (LevelChunk) (Object) this;
            if (chunk.getFullStatus() != null
                    && chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING)
                    && lightEngine instanceof LucisLightPublisher publisher
                    && LucisServices.controller().enqueueBlockChange(serverLevel, pos, oldState, state)) {
                publisher.lucis$onRuntimeChange();
                return;
            }
        }
        lightEngine.checkBlock(checkedPos);
    }

    @Redirect(method = "postProcessGeneration", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean lucis$suppressRuntimeLightDuringPostProcess(ServerLevel level, BlockPos pos, BlockState state, int flags) {
        LucisServices.controller().beginWorldgenWrite();
        try {
            return level.setBlock(pos, state, flags);
        } finally {
            LucisServices.controller().endWorldgenWrite();
        }
    }
}
