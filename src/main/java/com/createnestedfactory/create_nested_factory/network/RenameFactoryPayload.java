package com.createnestedfactory.create_nested_factory.network;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.menu.FactoryMenu;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Menu-scoped rename request. The server resolves the factory from the active FactoryMenu. */
public record RenameFactoryPayload(int containerId, String name) implements CustomPacketPayload {
    public static final Type<RenameFactoryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "rename_factory"));

    public static final StreamCodec<ByteBuf, RenameFactoryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RenameFactoryPayload::containerId,
                    ByteBufCodecs.STRING_UTF8, RenameFactoryPayload::name,
                    RenameFactoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RenameFactoryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.containerMenu instanceof FactoryMenu menu
                    && menu.containerId == payload.containerId()) {
                menu.renameIfValid(player, payload.name());
            }
        });
    }
}
