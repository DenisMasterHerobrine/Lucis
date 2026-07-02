package dev.denismasterherobrine.lucis.mixin.compat.sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Dynamic;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ServerLevel.class, priority = 500)
public interface SableServerLevelAccessor {
    @Dynamic("Added by Sable's ServerLevel mixin")
    @Invoker(value = "sable$getPlotContainer", remap = false)
    SubLevelContainer lucis$getSablePlotContainer();
}
