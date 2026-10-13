package com.createnestedfactory.create_nested_factory.network;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import java.util.function.Supplier;
public record RenameFactoryPayload(int containerId, String name) {
    public static void encode(RenameFactoryPayload packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.containerId);
        buffer.writeUtf(packet.name, 256);
    }
    public static RenameFactoryPayload decode(FriendlyByteBuf buffer) {
        return new RenameFactoryPayload(buffer.readVarInt(), buffer.readUtf(256));
    }
    public static void handle(RenameFactoryPayload packet, Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null && player.containerMenu instanceof FactoryMenu menu
                    && menu.containerId == packet.containerId) menu.renameIfValid(player, packet.name);
        });
        ctx.setPacketHandled(true);
    }
}
