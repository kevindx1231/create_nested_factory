package com.createnestedfactory.create_nested_factory.network;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.client.PlayerMessageOverlay;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.chat.Style;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server-authoritative player feedback rendered with the mod's consistent overlay style. */
public record PlayerMessagePayload(Component message, boolean hideGoggle) implements CustomPacketPayload {
    public static final Type<PlayerMessagePayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "player_message"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlayerMessagePayload> STREAM_CODEC =
            StreamCodec.composite(
                    ComponentSerialization.TRUSTED_STREAM_CODEC, PlayerMessagePayload::message,
                    ByteBufCodecs.BOOL, PlayerMessagePayload::hideGoggle,
                    PlayerMessagePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void sendTo(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PlayerMessagePayload(message.copy().setStyle(Style.EMPTY), true));
        }
    }

    /** Compatibility overload for existing action-bar call sites; messages keep the old goggle behavior. */
    public static void sendTo(Player player, Component message, boolean ignoredActionBar) {
        sendTo(player, message);
    }

    /** Sends feedback without temporarily replacing Create's goggle tooltip layer. */
    public static void sendToWithoutGoggleHiding(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            PacketDistributor.sendToPlayer(serverPlayer,
                    new PlayerMessagePayload(message.copy().setStyle(Style.EMPTY), false));
        }
    }

    public static void handle(PlayerMessagePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> PlayerMessageOverlay.show(payload.message(), payload.hideGoggle()));
    }
}