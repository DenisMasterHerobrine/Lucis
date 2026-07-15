package dev.denismasterherobrine.lucis.mixin;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.function.IntSupplier;

@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    @Invoker("getChunkQueueLevel")
    IntSupplier lucis$getChunkQueueLevel(long chunkPos);

    @Invoker("releaseLightTicket")
    void lucis$releaseLightTicket(ChunkPos chunkPos);
}
