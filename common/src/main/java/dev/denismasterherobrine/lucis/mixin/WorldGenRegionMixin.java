package dev.denismasterherobrine.lucis.mixin;

import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(net.minecraft.server.level.WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Redirect(
            method = "setBlock",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Lnet/minecraft/world/level/block/state/BlockState;"
            )
    )
    private BlockState lucis$markWorldgenWrite(ChunkAccess chunk, BlockPos pos, BlockState state, int flags) {
        LucisServices.controller().beginWorldgenWrite();
        try {
            return chunk.setBlockState(pos, state, flags);
        } finally {
            LucisServices.controller().endWorldgenWrite();
        }
    }
}
