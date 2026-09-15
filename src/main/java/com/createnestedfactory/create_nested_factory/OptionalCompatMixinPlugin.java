package com.createnestedfactory.create_nested_factory;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import net.neoforged.fml.loading.LoadingModList;
import java.util.Set;

/** Filters bundled compatibility Mixins according to the mods available at runtime. */
public final class OptionalCompatMixinPlugin implements IMixinConfigPlugin {
    private static final String SIMULATED_MIXIN_PACKAGE =
            "com.createnestedfactory.create_nested_factory_simulated.mixin.";
    private static final String LOGISTICS_MIXIN_PACKAGE =
            "com.createnestedfactory.create_nested_factory_logistics.mixin.";

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        try {
            if (mixinClassName.startsWith(SIMULATED_MIXIN_PACKAGE)) {
                // Mixin configuration is prepared before ModList has finished constructing
                // runtime containers. LoadingModList is already populated during this phase.
                return isModAvailable("simulated") && isModAvailable("sable");
            }
            if (mixinClassName.startsWith(LOGISTICS_MIXIN_PACKAGE)) {
                return isModAvailable("pipez");
            }
        } catch (RuntimeException ignored) {
            // Failing closed preserves the base mod when the loader is not ready.
            return false;
        }
        return true;
    }

    private static boolean isModAvailable(String modId) {
        try {
            return LoadingModList.get().getModFileById(modId) != null;
        } catch (LinkageError | RuntimeException ignored) {
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
