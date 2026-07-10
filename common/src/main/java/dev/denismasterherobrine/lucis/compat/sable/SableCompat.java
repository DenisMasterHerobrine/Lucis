package dev.denismasterherobrine.lucis.compat.sable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public final class SableCompat {
    private static volatile Method containerAccessor;
    private static volatile Method plotLookup;
    private static volatile boolean unavailable;

    private SableCompat() {
    }

    public static boolean isSablePlotChunk(Level level, int chunkX, int chunkZ) {
        if (!(level instanceof ServerLevel serverLevel) || unavailable) {
            return false;
        }

        try {
            Method accessor = containerAccessor;
            if (accessor == null) {
                accessor = serverLevel.getClass().getMethod("sable$getPlotContainer");
                containerAccessor = accessor;
            }
            Object container = accessor.invoke(serverLevel);
            if (container == null) {
                return false;
            }

            Method lookup = plotLookup;
            if (lookup == null || lookup.getDeclaringClass() != container.getClass()) {
                lookup = container.getClass().getMethod("getPlot", int.class, int.class);
                plotLookup = lookup;
            }
            return lookup.invoke(container, chunkX, chunkZ) != null;
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException | LinkageError exception) {
            unavailable = true;
            return false;
        }
    }
}
