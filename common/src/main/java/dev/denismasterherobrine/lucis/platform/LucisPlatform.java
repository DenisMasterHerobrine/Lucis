package dev.denismasterherobrine.lucis.platform;

import java.util.Objects;
import java.util.function.Predicate;

public final class LucisPlatform {
    private static volatile Predicate<String> modLookup = ignored -> false;

    private LucisPlatform() {
    }

    public static void installModLookup(Predicate<String> lookup) {
        modLookup = Objects.requireNonNull(lookup, "lookup");
    }

    public static boolean isModLoaded(String modId) {
        return modLookup.test(modId);
    }
}
