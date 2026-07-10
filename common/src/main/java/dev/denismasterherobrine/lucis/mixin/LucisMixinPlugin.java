package dev.denismasterherobrine.lucis.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class LucisMixinPlugin implements IMixinConfigPlugin {
    private static final String SABLE_MIXIN_PACKAGE = "dev.denismasterherobrine.lucis.mixin.compat.sable.";
    private static final String SABLE_MARKER_CLASS = "dev.ryanhcode.sable.Sable";

    private Boolean sablePresent;

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return !mixinClassName.startsWith(SABLE_MIXIN_PACKAGE) || isSablePresentEarly();
    }

    private boolean isSablePresentEarly() {
        Boolean cached = sablePresent;
        if (cached == null) {
            cached = hasClass(SABLE_MARKER_CLASS);
            sablePresent = cached;
        }
        return cached;
    }

    private static boolean hasClass(String className) {
        try {
            Class.forName(className, false, LucisMixinPlugin.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
