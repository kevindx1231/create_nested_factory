package com.createnestedfactory.create_nested_factory_simulated.mixin;

import com.createnestedfactory.create_nested_factory_simulated.SableFactoryPassageAdapter;
import com.createnestedfactory.create_nested_factory_simulated.SableFactoryReturnAnchorAdapter;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps pending carrier teleports in the carrier's frame without issuing new teleport IDs. */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PassageArrivalConnectionMixin {
    @Shadow public ServerPlayer player;
    @Shadow private Vec3 awaitingPositionFromClient;

    @Inject(method = "tick", at = @At("HEAD"))
    private void followPassageArrival(CallbackInfo ci) {
        awaitingPositionFromClient = SableFactoryReturnAnchorAdapter.hasPendingArrival(player)
                ? SableFactoryReturnAnchorAdapter.followArrival(player, awaitingPositionFromClient)
                : SableFactoryPassageAdapter.followArrival(player, awaitingPositionFromClient);
    }
}
