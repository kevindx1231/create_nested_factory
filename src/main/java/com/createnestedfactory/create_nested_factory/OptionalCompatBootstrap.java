package com.createnestedfactory.create_nested_factory;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import java.util.EnumSet;

/**
 * Loads bundled compatibility code only when its external mod APIs are present.
 *
 * <p>This class deliberately has no compile-time references to optional mods.
 * That keeps the main mod safe to load when any optional integration is absent.</p>
 */
public final class OptionalCompatBootstrap {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(OptionalCompatBootstrap.class);

    private OptionalCompatBootstrap() {
    }

    enum CompatInitializer {
        MEKANISM,
        PIPEZ,
        SIMULATED
    }

    public static void register(IEventBus modEventBus) {
        for (CompatInitializer initializer : initializersFor(
                isLoaded("mekanism"), isLoaded("pipez"), isLoaded("simulated"), isLoaded("sable"))) {
            switch (initializer) {
                case MEKANISM -> invoke(
                        "com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat",
                        "initialize", IEventBus.class, modEventBus);
                case PIPEZ -> invoke(
                        "com.createnestedfactory.create_nested_factory_logistics.PipezLogisticsCompat",
                        "initialize");
                case SIMULATED -> invoke(
                        "com.createnestedfactory.create_nested_factory_simulated.CreateNestedFactorySimulated",
                        "initialize");
            }
        }
    }

    static EnumSet<CompatInitializer> initializersFor(boolean mekanismLoaded, boolean pipezLoaded,
                                                        boolean simulatedLoaded, boolean sableLoaded) {
        EnumSet<CompatInitializer> initializers = EnumSet.noneOf(CompatInitializer.class);
        if (mekanismLoaded) initializers.add(CompatInitializer.MEKANISM);
        if (pipezLoaded) initializers.add(CompatInitializer.PIPEZ);
        if (simulatedLoaded && sableLoaded) initializers.add(CompatInitializer.SIMULATED);
        return initializers;
    }

    private static boolean isLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    private static void invoke(String className, String methodName, Class<?> parameterType, Object argument) {
        try {
            Class<?> compatClass = Class.forName(className, true, OptionalCompatBootstrap.class.getClassLoader());
            Method method = compatClass.getMethod(methodName, parameterType);
            method.invoke(null, argument);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            LOGGER.warn("Unable to initialize optional compatibility {}", className, exception);
        } catch (InvocationTargetException exception) {
            LOGGER.warn("Optional compatibility {} failed during initialization", className, exception.getCause());
        } catch (LinkageError | RuntimeException exception) {
            LOGGER.warn("Optional compatibility {} is unavailable", className, exception);
        }
    }

    private static void invoke(String className, String methodName) {
        try {
            Class<?> compatClass = Class.forName(className, true, OptionalCompatBootstrap.class.getClassLoader());
            Method method = compatClass.getMethod(methodName);
            method.invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException exception) {
            LOGGER.warn("Unable to initialize optional compatibility {}", className, exception);
        } catch (InvocationTargetException exception) {
            LOGGER.warn("Optional compatibility {} failed during initialization", className, exception.getCause());
        } catch (LinkageError | RuntimeException exception) {
            LOGGER.warn("Optional compatibility {} is unavailable", className, exception);
        }
    }
}
