package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Create_nested_factory.MODID);

    public static final Supplier<AttachmentType<ReturnData>> RETURN_DATA = ATTACHMENT_TYPES.register("return_data",
            () -> AttachmentType.builder(ReturnData::defaults)
                    .serialize(ReturnData.CODEC)
                    .build());

    public record ReturnData(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot, boolean mayfly, boolean flying) {
        public static ReturnData defaults() {
            return new ReturnData(Level.OVERWORLD, Vec3.ZERO, 0.0F, 0.0F, false, false);
        }

        public static final Codec<ReturnData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ReturnData::dimension),
                Vec3.CODEC.fieldOf("pos").forGetter(ReturnData::pos),
                Codec.FLOAT.fieldOf("yRot").forGetter(ReturnData::yRot),
                Codec.FLOAT.fieldOf("xRot").forGetter(ReturnData::xRot),
                Codec.BOOL.fieldOf("mayfly").forGetter(ReturnData::mayfly),
                Codec.BOOL.fieldOf("flying").forGetter(ReturnData::flying)
        ).apply(instance, ReturnData::new));
    }
}
