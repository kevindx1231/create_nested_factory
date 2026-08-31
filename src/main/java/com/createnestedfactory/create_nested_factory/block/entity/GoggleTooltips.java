package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

public final class GoggleTooltips {
    // 4 spaces (~16px) shift text past the 16px goggles icon, which overlaps the first 14px of text
    private static final String INDENT = "    ";

    private GoggleTooltips() {
    }

    public static MutableComponent title(Component name) {
        return Component.literal(INDENT)
                .append(name.copy().withStyle(ChatFormatting.WHITE))
                .append(Component.literal(":"));
    }

    public static MutableComponent section(String key) {
        return Component.literal(INDENT)
                .append(Component.translatable(key).withStyle(ChatFormatting.WHITE))
                .append(Component.literal(":").withStyle(ChatFormatting.WHITE));
    }

    public static MutableComponent message(String key, int color) {
        return Component.literal(INDENT)
                .append(Component.translatable(key).withStyle(style -> style.withColor(color)));
    }

    public static MutableComponent stat(String key, String text, ChatFormatting valueColor) {
        return Component.literal(INDENT + " ")
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(text).withStyle(valueColor));
    }

    public static MutableComponent stat(String key, Component value) {
        return Component.literal(INDENT + " ")
                .append(Component.translatable(key).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                .append(value);
    }
}
