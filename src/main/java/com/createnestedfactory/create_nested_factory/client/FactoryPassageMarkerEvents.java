package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.FactoryPassageAvailability;
import com.createnestedfactory.create_nested_factory.block.FactoryPassageBlock;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassageBlockEntity;
import com.createnestedfactory.create_nested_factory.registry.ModBlocks;
import com.simibubi.create.AllSpecialTextures;
import com.simibubi.create.CreateClient;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBox;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBox.ItemValueBox;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.outliner.Outliner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/** Shows the passage marker slot using Create's standard filter value-box presentation. */
@EventBusSubscriber(modid = Create_nested_factory.MODID, value = Dist.CLIENT)
public final class FactoryPassageMarkerEvents {
    private FactoryPassageMarkerEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Level level = minecraft.level;
        if (player == null || level == null || player.isShiftKeyDown()
                || !(minecraft.hitResult instanceof BlockHitResult hitResult)) {
            return;
        }

        BlockPos upperPos = hitResult.getBlockPos();
        BlockState upperState = level.getBlockState(upperPos);
        if (!upperState.is(ModBlocks.FACTORY_PASSAGE.get())
                || !FactoryPassageBlock.isMarkerSlotHit(upperState, upperPos, hitResult)) {
            return;
        }

        BlockPos basePos = FactoryPassageBlock.getBasePos(upperPos, upperState);
        if (!(level.getBlockEntity(basePos) instanceof FactoryPassageBlockEntity passage)) {
            return;
        }
        if (FactoryPassageAvailability.isPhysicalized(passage)) return;

        ItemStack marker = passage.getMarker();
        MutableComponent label = Component.translatable("tooltip.create_nested_factory.passage_marker");
        AABB emptyBox = new AABB(Vec3.ZERO, Vec3.ZERO);
        ValueBox box = new ItemValueBox(label, emptyBox.inflate(.25), basePos, marker, null);

        Outliner.getInstance()
                .showOutline(Pair.of("factory_passage_marker", basePos),
                        box.transform(FactoryPassageMarkerSlot.INSTANCE))
                .lineWidth(1 / 64F)
                .withFaceTexture(AllSpecialTextures.THIN_CHECKERED)
                .highlightFace(hitResult.getDirection());

        List<MutableComponent> tip = new ArrayList<>();
        tip.add(label);
        tip.add(Component.translatable("create.logistics.filter.click_to_set"));
        CreateClient.VALUE_SETTINGS_HANDLER.showHoverTip(tip);
    }
}
