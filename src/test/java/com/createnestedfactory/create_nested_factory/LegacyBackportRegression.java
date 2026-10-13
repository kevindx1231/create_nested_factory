package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.entity.ItemVariant;
import com.createnestedfactory.create_nested_factory.block.entity.FluidVariant;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory.compat.LegacyCapabilities;
import com.createnestedfactory.create_nested_factory.compat.LegacyMath;
import com.createnestedfactory.create_nested_factory.compat.NbtIdentity;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import com.createnestedfactory.create_nested_factory.network.RenameFactoryPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.items.ItemStackHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.common.util.LazyOptional;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.mockito.Mockito.*;

/** Checks the cross-version contracts most likely to compile while silently changing behavior. */
public final class LegacyBackportRegression {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        variants();
        playerState();
        capabilities();
        packetAndCounts();
        System.out.println("1.20.1 backport NBT, sessions, capabilities and packet regressions passed.");
    }

    private static void variants() {
        ItemStack item = new ItemStack(Items.DIAMOND_PICKAXE);
        item.setDamageValue(37);
        item.setHoverName(Component.literal("Named tool"));
        item.getOrCreateTag().putString("ModSpecificIdentity", "first");
        ItemVariant identity = ItemVariant.of(item);
        require(identity.equals(ItemVariant.read(null, identity.write(null))), "item NBT round trip lost identity");
        ItemStack other = item.copy();
        other.getOrCreateTag().putString("ModSpecificIdentity", "second");
        require(!identity.matches(other), "different mod-specific NBT was merged");
        other = item.copy();
        other.setDamageValue(100);
        require(ItemVariant.toolSignature(item).equals(ItemVariant.toolSignature(other)), "tool wear changed the tool identity");
        require(!identity.matches(other), "ordinary stack identity ignored wear");

        FluidStack fluid = new FluidStack(Fluids.WATER, 50);
        fluid.getOrCreateTag().putString("ResourceVariant", "first");
        FluidVariant fluidIdentity = FluidVariant.of(fluid);
        require(fluidIdentity.matches(NbtIdentity.withAmount(fluid, 500)), "fluid identity included amount");
        require(fluidIdentity.equals(FluidVariant.read(null, fluidIdentity.write(null))), "fluid NBT round trip lost identity");
        FluidStack different = fluid.copy();
        different.getOrCreateTag().putString("ResourceVariant", "second");
        require(!fluidIdentity.matches(different), "different fluid NBT was merged");

        CompoundTag a = new CompoundTag(), b = new CompoundTag();
        a.putString("Name", "test"); a.putInt("Count", 1);
        b.putInt("Count", 1); b.putString("Name", "test");
        require(NbtIdentity.canonical(a).equals(NbtIdentity.canonical(b)), "resource keys depended on NBT insertion order");
    }

    private static void playerState() {
        Player first = mock(Player.class);
        CompoundTag data = new CompoundTag();
        when(first.getPersistentData()).thenReturn(data);
        var reference = new ModAttachments.FactoryReference(Level.OVERWORLD, BlockPos.ZERO, "factory", "root");
        var frame = ModAttachments.ReturnFrame.external(Level.OVERWORLD,
                new net.minecraft.world.phys.Vec3(1, 64, 2), 90, 0, "factory");
        var session = new ModAttachments.FactorySession("root", "factory", reference,
                true, false, false, true, false, List.of(frame));
        ModAttachments.set(first, ModAttachments.FACTORY_SESSION, session);
        ModAttachments.set(first, ModAttachments.PASSAGE_SELECTION, new ModAttachments.PassageSelection(reference));
        ModAttachments.set(first, ModAttachments.PASSAGE_TRAVEL_GUARD,
                ModAttachments.PassageTravelGuard.arrival("passage", Level.OVERWORLD, BlockPos.ZERO));
        Player reloaded = mock(Player.class);
        when(reloaded.getPersistentData()).thenReturn(data.copy());
        require(ModAttachments.get(reloaded, ModAttachments.FACTORY_SESSION).equals(session), "return stack did not survive player reload");
        require(!ModAttachments.get(reloaded, ModAttachments.PASSAGE_SELECTION).isPresent(), "transient selection persisted across reload");
        require(!ModAttachments.get(reloaded, ModAttachments.PASSAGE_TRAVEL_GUARD).isActive(), "transient arrival guard persisted across reload");
    }

    private static void capabilities() {
        // CapabilityToken.getType() is supplied by FML's transformer in a game.
        // Standalone tests supply identities while exercising the actual cache lifecycle.
        try (var manager = mockStatic(net.minecraftforge.common.capabilities.CapabilityManager.class)) {
            manager.when(() -> net.minecraftforge.common.capabilities.CapabilityManager.get(
                    any(net.minecraftforge.common.capabilities.CapabilityToken.class)))
                    .thenAnswer(ignored -> mock(net.minecraftforge.common.capabilities.Capability.class));
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        when(factory.getItemHandler(Direction.NORTH)).thenReturn(new ItemStackHandler(1));
        var cache = new LegacyCapabilities.Cache();
        var endpoint = cache.get(factory, ForgeCapabilities.ITEM_HANDLER, Direction.NORTH);
        require(endpoint.isPresent(), "configured item endpoint was absent");
        require(endpoint == cache.get(factory, ForgeCapabilities.ITEM_HANDLER, Direction.NORTH), "capability identity was unstable");
        boolean[] invalidated = {false};
        endpoint.addListener(ignored -> invalidated[0] = true);
        cache.invalidate();
        require(invalidated[0] && !endpoint.isPresent(), "capability invalidation did not notify consumers");
        require(!cache.get(factory, ForgeCapabilities.ITEM_HANDLER, Direction.SOUTH).isPresent(), "unconfigured face exposed an endpoint");
        reentrantCapabilities();
        }
    }

    private static void reentrantCapabilities() {
        NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        NestedPortBlockEntity port = mock(NestedPortBlockEntity.class);
        NestedExtensionInterfaceBlockEntity extension = mock(NestedExtensionInterfaceBlockEntity.class);
        for (Direction side : List.of(Direction.NORTH, Direction.EAST, Direction.SOUTH)) {
            when(factory.getItemHandler(side)).thenReturn(new ItemStackHandler(1));
            when(port.getItemHandler(side)).thenReturn(new ItemStackHandler(1));
            when(extension.getItemHandler(side)).thenReturn(new ItemStackHandler(1));
        }
        for (BlockEntity owner : List.of(factory, port, extension)) {
            var cache = new LegacyCapabilities.Cache();
            var north = cache.get(owner, ForgeCapabilities.ITEM_HANDLER, Direction.NORTH);
            var east = cache.get(owner, ForgeCapabilities.ITEM_HANDLER, Direction.EAST);
            var refreshed = new AtomicReference<LazyOptional<IItemHandler>>();
            var added = new AtomicReference<LazyOptional<IItemHandler>>();
            boolean[] eastNotified = {false};
            north.addListener(ignored -> {
                // Mekanism synchronously refreshes connections from its invalidation listener.
                refreshed.set(cache.get(owner, ForgeCapabilities.ITEM_HANDLER, Direction.NORTH));
                added.set(cache.get(owner, ForgeCapabilities.ITEM_HANDLER, Direction.SOUTH));
            });
            east.addListener(ignored -> eastNotified[0] = true);
            cache.invalidate();
            require(!north.isPresent() && !east.isPresent() && eastNotified[0], "old capability generation was not fully invalidated");
            require(refreshed.get() != north && refreshed.get().isPresent(), "callback re-query received the stale invalid capability");
            require(refreshed.get() == cache.get(owner, ForgeCapabilities.ITEM_HANDLER, Direction.NORTH), "callback-created capability was cleared");
            require(added.get().isPresent() && added.get() == cache.get(owner, ForgeCapabilities.ITEM_HANDLER, Direction.SOUTH),
                    "callback-created side did not survive invalidation");
            cache.invalidate();
            require(!refreshed.get().isPresent() && !added.get().isPresent(), "replacement generation could not be invalidated");
        }
    }

    private static void packetAndCounts() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            var packet = new RenameFactoryPayload(42, "嵌套工厂");
            RenameFactoryPayload.encode(packet, buffer);
            require(packet.equals(RenameFactoryPayload.decode(buffer)), "rename packet lost menu scope or Unicode text");
        } finally { buffer.release(); }
        long large = (1L << 54) + 3;
        require(LegacyMath.clamp(large, 0L, Long.MAX_VALUE) == large, "Java 17 clamp rounded long resource amounts");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
