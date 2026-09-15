package com.createnestedfactory.create_nested_factory;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

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

    public static void register(IEventBus modEventBus) {
        if (isLoaded("mekanism")) {
            invoke("com.createnestedfactory.create_nested_factory_logistics.MekanismLogisticsCompat",
                    "initialize", IEventBus.class, modEventBus);
        }

        // The bundled Simulated integration uses both APIs. Do not load any of it
        // unless both providers are available. The Mixin plugin applies the same rule.
        if (isLoaded("simulated") && isLoaded("sable")) {
            invoke("com.createnestedfactory.create_nested_factory_simulated.CreateNestedFactorySimulated",
                    "initialize");
        }
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
