package dev.denismasterherobrine.lucis.light.runtime;

import net.minecraft.world.level.chunk.LightChunk;

public interface LucisLightPublisher {
    void lucis$publish(LucisRelightResult result, LightChunk expectedChunk);
}
