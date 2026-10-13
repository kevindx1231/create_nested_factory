package com.createnestedfactory.create_nested_factory.compat;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/** Maps sided endpoints to Forge 47 capability lifetimes. */
public final class LegacyCapabilities {
    private record Provider<T>(BiFunction<BlockEntity, Direction, T> create, Predicate<T> available) {}
    private static final Map<Capability<?>, Provider<?>> PROVIDERS = new HashMap<>();
    static {
        register(ForgeCapabilities.ITEM_HANDLER, (be, side) -> {
            if (be instanceof NestedFactoryBlockEntity factory) return factory.getItemHandler(side);
            if (be instanceof NestedPortBlockEntity port) return port.getItemHandler(side);
            if (be instanceof NestedExtensionInterfaceBlockEntity extension) return extension.getItemHandler(side);
            return null;
        });
        register(ForgeCapabilities.FLUID_HANDLER, (be, side) -> {
            if (be instanceof NestedFactoryBlockEntity factory) return factory.getFluidHandler(side);
            if (be instanceof NestedPortBlockEntity port) return port.getFluidHandler(side);
            if (be instanceof NestedExtensionInterfaceBlockEntity extension) return extension.getFluidHandler(side);
            return null;
        });
    }
    private LegacyCapabilities() {}
    public static <T> void register(Capability<T> capability, BiFunction<BlockEntity, Direction, T> provider) {
        register(capability, provider, null);
    }
    public static <T> void register(Capability<T> capability, BiFunction<BlockEntity, Direction, T> provider,
                                    Predicate<T> available) {
        PROVIDERS.put(capability, new Provider<>(provider, available));
    }
    public static <T> T get(Level level, Capability<T> capability, BlockPos pos, Direction side) {
        if (level == null || !level.hasChunkAt(pos)) return null;
        BlockEntity be = level.getBlockEntity(pos);
        return be == null || be.isRemoved() ? null : be.getCapability(capability, side).orElse(null);
    }
    public static void invalidate(Level level, BlockPos pos) {
        if (level == null || !level.hasChunkAt(pos)) return;
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof NestedFactoryBlockEntity factory) factory.invalidatePortCapabilities();
        else if (be instanceof NestedPortBlockEntity port) port.invalidatePortCapabilities();
        else if (be instanceof NestedExtensionInterfaceBlockEntity extension) extension.invalidatePortCapabilities();
    }
    public static final class Cache {
        private record Key(Capability<?> capability, Direction side) {}
        private final Map<Key, LazyOptional<?>> values = new HashMap<>();
        public <T> LazyOptional<T> get(BlockEntity owner, Capability<T> capability, Direction side) {
            @SuppressWarnings("unchecked") Provider<T> provider = (Provider<T>) PROVIDERS.get(capability);
            if (provider == null || owner.isRemoved()) return LazyOptional.empty();
            Key key = new Key(capability, side);
            LazyOptional<?> cached = values.get(key);
            if (cached != null && provider.available() != null) {
                T previous = cached.<T>cast().orElse(null);
                if (previous != null && provider.available().test(previous)) return cached.cast();
                values.remove(key);
                cached.invalidate();
            }
            T handler = provider.create().apply(owner, side);
            if (handler == null) {
                LazyOptional<?> previous = values.remove(key);
                if (previous != null) previous.invalidate();
                return LazyOptional.empty();
            }
            return values.computeIfAbsent(key, ignored -> LazyOptional.of(() -> handler)).cast();
        }
        public void invalidate() {
            // Consumers may synchronously query capabilities from their invalidation listeners.
            // Detach the old generation first and preserve any replacements those callbacks create.
            var previous = List.copyOf(values.values());
            values.clear();
            previous.forEach(LazyOptional::invalidate);
        }
    }
}
