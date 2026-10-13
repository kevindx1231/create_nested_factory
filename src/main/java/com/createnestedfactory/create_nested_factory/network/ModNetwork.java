package com.createnestedfactory.create_nested_factory.network;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class ModNetwork {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Create_nested_factory.MODID, "main"), () -> "1", "1"::equals, "1"::equals);
    private ModNetwork() {}
    public static void register() {
        CHANNEL.messageBuilder(RenameFactoryPayload.class, 0, NetworkDirection.PLAY_TO_SERVER)
                .encoder(RenameFactoryPayload::encode).decoder(RenameFactoryPayload::decode)
                .consumerNetworkThread(RenameFactoryPayload::handle).add();
        CHANNEL.messageBuilder(PlayerMessagePayload.class, 1, NetworkDirection.PLAY_TO_CLIENT)
                .encoder(PlayerMessagePayload::encode).decoder(PlayerMessagePayload::decode)
                .consumerNetworkThread(PlayerMessagePayload::handle).add();
    }
}
