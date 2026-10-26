package com.createnestedfactory.create_nested_factory.ponder;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;

/** Converts embedded 1.21.1 Ponder item stacks before the 1.20.1 readers see them. */
public final class PonderNbt {
    private PonderNbt() {
    }

    public static CompoundTag parseTag(String snbt) throws CommandSyntaxException {
        CompoundTag tag = TagParser.parseTag(snbt);
        normalize(tag);
        return tag;
    }

    private static void normalize(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            // Scope this to serialized item stacks; unrelated count fields keep their type/name.
            if (compound.contains("id", Tag.TAG_STRING) && compound.contains("count", Tag.TAG_ANY_NUMERIC)) {
                int count = compound.getInt("count");
                if (count < Byte.MIN_VALUE || count > Byte.MAX_VALUE || compound.contains("components")) {
                    throw new IllegalArgumentException("Unsupported exported Ponder item stack: " + compound);
                }
                compound.remove("count");
                compound.putByte("Count", (byte) count);
            }
            for (String key : compound.getAllKeys()) {
                normalize(compound.get(key));
            }
        } else if (tag instanceof ListTag list) {
            for (Tag child : list) {
                normalize(child);
            }
        }
    }
}
