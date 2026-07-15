package dev.denismasterherobrine.lucis.mixin;

import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(LevelChunk.class)
public abstract class LevelChunkMixin {
    @Redirect(method = "postProcessGeneration", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"))
    private boolean lucis$suppressRuntimeLightDuringPostProcess(Level level, BlockPos pos, BlockState state, int flags) {
        LucisServices.controller().beginWorldgenWrite();
        try {
            return level.setBlock(pos, state, flags);
        } finally {
            LucisServices.controller().endWorldgenWrite();
        }
    }
}
