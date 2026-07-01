package dev.denismasterherobrine.lucisrevisited.light.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public record BlockChangeRecord(BlockPos pos, BlockState oldState, BlockState newState) {
}
