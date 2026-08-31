package com.createnestedfactory.create_nested_factory.client;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Renders all mod player messages with one consistent, undimmed presentation. */
@EventBusSubscriber(modid = Create_nested_factory.MODID, value = Dist.CLIENT)
public final class PlayerMessageOverlay {
    private static final int Y_OFFSET_FROM_BOTTOM = 95;
    private static final int MESSAGE_COLOR = 0xF8D97C;
    private static final int SHADOW_COLOR = 0x3D361F;
    /** Sub-pixel down-right shadow offset; GuiGraphics text coordinates themselves are integral. */
    private static final float SHADOW_OFFSET = 0.5F;
    private static final long MESSAGE_DURATION_MS = 2_000L;

    private static Component message;
    private static long expiresAt;

    private PlayerMessageOverlay() {
    }

    public static void show(Component message) {
        // Color is owned by this overlay; preserve the payload text without adding a bold style.
        PlayerMessageOverlay.message = message.copy();
        expiresAt = System.currentTimeMillis() + MESSAGE_DURATION_MS;
    }

    private static final ResourceLocation CREATE_GOGGLE_LAYER = ResourceLocation.fromNamespaceAndPath("create", "goggle_info");

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void hideGoggleWhileMessageVisible(RenderGuiLayerEvent.Pre event) {
        if (CREATE_GOGGLE_LAYER.equals(event.getName()) && isVisible()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void render(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.screen != null) {
            return;
        }
        renderMessage(event.getGuiGraphics(), minecraft.font, minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight());
    }

    @SubscribeEvent
    public static void renderAfterScreen(ScreenEvent.Render.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        renderMessage(event.getGuiGraphics(), minecraft.font, minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight());
    }

    private static boolean isVisible() {
        if (message == null) {
            return false;
        }
        if (System.currentTimeMillis() >= expiresAt) {
            message = null;
            return false;
        }
        return true;
    }

    private static void renderMessage(GuiGraphics graphics, Font font, int width, int height) {
        if (!isVisible()) {
            return;
        }
        int textX = (width - font.width(message)) / 2;
        int textY = height - Y_OFFSET_FROM_BOTTOM;
        graphics.pose().pushPose();
        graphics.pose().translate(SHADOW_OFFSET, SHADOW_OFFSET, 0.0F);
        graphics.drawString(font, message, textX, textY, SHADOW_COLOR, false);
        graphics.pose().popPose();
        graphics.drawString(font, message, textX, textY, MESSAGE_COLOR, false);
    }
}
