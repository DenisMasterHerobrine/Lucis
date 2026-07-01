package dev.denismasterherobrine.lucisrevisited.mixin;

import dev.denismasterherobrine.lucisrevisited.light.engine.LucisServices;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {
    @Inject(method = "onBlockStateChange", at = @At("HEAD"))
    private void lucis$onBlockStateChange(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
        LucisServices.controller().enqueueBlockChange(pos, oldState, newState);
    }
}
