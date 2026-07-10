package dev.denismasterherobrine.lucis.light.runtime;

import net.minecraft.world.level.chunk.LightChunk;

import java.util.concurrent.CompletableFuture;

public interface LucisLightPublisher {
    CompletableFuture<Void> lucis$publish(LucisRelightResult result, LightChunk expectedChunk);

    void lucis$onRuntimeChange();

    void lucis$requestRuntimeDrain();
}
