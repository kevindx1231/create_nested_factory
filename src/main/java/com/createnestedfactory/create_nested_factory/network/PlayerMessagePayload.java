package com.createnestedfactory.create_nested_factory.network;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import java.util.function.Supplier;
public record PlayerMessagePayload(Component message, boolean hideGoggle) {
    public static void encode(PlayerMessagePayload packet, FriendlyByteBuf buffer) {
        buffer.writeComponent(packet.message);
        buffer.writeBoolean(packet.hideGoggle);
    }
    public static PlayerMessagePayload decode(FriendlyByteBuf buffer) {
        return new PlayerMessagePayload(buffer.readComponent(), buffer.readBoolean());
    }
    public static void sendTo(Player player, Component message) { send(player, message, true); }
    public static void sendTo(Player player, Component message, boolean ignoredActionBar) { sendTo(player, message); }
    public static void sendToWithoutGoggleHiding(Player player, Component message) { send(player, message, false); }
    private static void send(Player player, Component message, boolean hideGoggle) {
        if (player instanceof ServerPlayer serverPlayer)
            ModNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> serverPlayer),
                    new PlayerMessagePayload(message.copy().setStyle(Style.EMPTY), hideGoggle));
    }
    public static void handle(PlayerMessagePayload packet, Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> ClientHandler.show(packet));
        context.get().setPacketHandled(true);
    }
    private static final class ClientHandler {
        static void show(PlayerMessagePayload packet) {
            com.createnestedfactory.create_nested_factory.client.PlayerMessageOverlay.show(packet.message, packet.hideGoggle);
        }
    }
}
