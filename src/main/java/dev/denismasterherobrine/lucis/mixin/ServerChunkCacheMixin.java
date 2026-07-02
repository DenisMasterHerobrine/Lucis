package dev.denismasterherobrine.lucis.mixin;

import dev.denismasterherobrine.lucis.light.engine.LucisServices;
import dev.denismasterherobrine.lucis.test.LucisBenchmarkSupport;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.LightChunkGetter;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Shadow
    @Final
    net.minecraft.server.level.ThreadedLevelLightEngine lightEngine;

    @Inject(method = "tick", at = @At("TAIL"))
    private void lucis$tick(java.util.function.BooleanSupplier hasTimeLeft, boolean tickChunks, CallbackInfo ci) {
        long startedAt = LucisBenchmarkSupport.start();
        LucisServices.controller().tickRuntime(lightEngine, (LightChunkGetter) (Object) this);
        LucisBenchmarkSupport.recordSince("lucis.runtime_tick", startedAt);
    }
}
