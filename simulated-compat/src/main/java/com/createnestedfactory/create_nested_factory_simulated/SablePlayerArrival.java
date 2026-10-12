package com.createnestedfactory.create_nested_factory_simulated;

import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.LivingEntityMovementExtension;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundFreezePlayerPacket;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

/** Shared player movement initialization; callers own arrival state and loading tickets. */
final class SablePlayerArrival {
    private SablePlayerArrival() {}

    static void initialize(ServerPlayer player, ServerSubLevel carrier, Vec3 localPosition) {
        ((EntityMovementExtension) player).sable$setTrackingSubLevel(carrier);
        // Sable supplies inherited motion; adding it as walking velocity would move the player twice.
        player.setDeltaMovement(Vec3.ZERO);
        if (player instanceof LivingEntityMovementExtension movement) movement.sable$getInheritedVelocity().zero();
        player.connection.send(new ClientboundCustomPayloadPacket(new ClientboundFreezePlayerPacket(carrier.getUniqueId(),
                new Vector3d(localPosition.x, localPosition.y, localPosition.z))));
    }
}
