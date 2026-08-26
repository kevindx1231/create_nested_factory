package com.createnestedfactory.create_nested_factory.network;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record RenameFactoryPayload(BlockPos pos, String name) implements CustomPacketPayload {
    public static final Type<RenameFactoryPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "rename_factory"));

    public static final StreamCodec<ByteBuf, RenameFactoryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RenameFactoryPayload::pos,
                    ByteBufCodecs.STRING_UTF8, RenameFactoryPayload::name,
                    RenameFactoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RenameFactoryPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player
                    && player.level().getBlockEntity(payload.pos()) instanceof NestedFactoryBlockEntity be) {
                be.setCustomName(payload.name());
            }
        });
    }
}
