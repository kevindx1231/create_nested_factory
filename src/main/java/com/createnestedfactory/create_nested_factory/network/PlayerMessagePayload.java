package com.createnestedfactory.create_nested_factory.network;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.client.PlayerMessageOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-authoritative player feedback rendered with the mod's consistent overlay style. */
public record PlayerMessagePayload(Component message) implements CustomPacketPayload {
    public static final Type<PlayerMessagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "player_message"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerMessagePayload> STREAM_CODEC =
            ComponentSerialization.TRUSTED_STREAM_CODEC.map(PlayerMessagePayload::new, PlayerMessagePayload::message);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void sendTo(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PlayerMessagePayload(message.copy().setStyle(Style.EMPTY)));
        }
    }

    /** Compatibility overload for existing action-bar call sites; the boolean is no longer used. */
    public static void sendTo(Player player, Component message, boolean ignoredActionBar) {
        sendTo(player, message);
    }

    public static void handle(PlayerMessagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PlayerMessageOverlay.show(payload.message()));
    }
}