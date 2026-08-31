package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES = DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Create_nested_factory.MODID);

    public static final Supplier<AttachmentType<FactorySession>> FACTORY_SESSION = ATTACHMENT_TYPES.register("factory_session",
            () -> AttachmentType.builder(FactorySession::defaults)
                    .serialize(FactorySession.CODEC)
                    .build());

    /** A persistent, directly loadable reference to one specific factory block entity. */
    public record FactoryReference(ResourceKey<Level> dimension, BlockPos pos, String factoryId, String rootFactoryId) {
        public boolean isComplete() {
            return !factoryId.isBlank() && !rootFactoryId.isBlank();
        }

        public static final Codec<FactoryReference> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(FactoryReference::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(FactoryReference::pos),
                Codec.STRING.fieldOf("factoryId").forGetter(FactoryReference::factoryId),
                Codec.STRING.fieldOf("rootFactoryId").forGetter(FactoryReference::rootFactoryId)
        ).apply(instance, FactoryReference::new));
    }

    public record ReturnFrame(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                              String sourceFactoryId, String targetFactoryId,
                              FactoryReference sourceFactory) {
        public static ReturnFrame external(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                                           String targetFactoryId) {
            return new ReturnFrame(dimension, pos, yRot, xRot, "", targetFactoryId, null);
        }

        public static final Codec<ReturnFrame> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ReturnFrame::dimension),
                Vec3.CODEC.fieldOf("pos").forGetter(ReturnFrame::pos),
                Codec.FLOAT.fieldOf("yRot").forGetter(ReturnFrame::yRot),
                Codec.FLOAT.fieldOf("xRot").forGetter(ReturnFrame::xRot),
                Codec.STRING.optionalFieldOf("sourceFactoryId", "").forGetter(ReturnFrame::sourceFactoryId),
                Codec.STRING.optionalFieldOf("targetFactoryId", "").forGetter(ReturnFrame::targetFactoryId),
                FactoryReference.CODEC.optionalFieldOf("sourceFactory")
                        .forGetter(frame -> Optional.ofNullable(frame.sourceFactory))
        ).apply(instance, (dimension, pos, yRot, xRot, sourceFactoryId, targetFactoryId, sourceFactory) ->
                new ReturnFrame(dimension, pos, yRot, xRot, sourceFactoryId, targetFactoryId,
                        sourceFactory.orElse(null))));
    }

    public record FactorySession(String rootFactoryId, String currentFactoryId,
                                 FactoryReference currentFactory,
                                 boolean grantedFlight, boolean originalMayFly, boolean originalFlying,
                                 boolean nightVisionGranted, boolean originalNightVision,
                                 List<ReturnFrame> stack) {
        public static FactorySession defaults() {
            return new FactorySession("", "", null, false, false, false, false, false, new ArrayList<>());
        }

        public boolean isActive() {
            return !currentFactoryId.isEmpty();
        }

        public boolean hasCurrentFactoryReference() {
            return currentFactory != null && currentFactory.isComplete();
        }

        public static final Codec<FactorySession> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("rootFactoryId", "").forGetter(FactorySession::rootFactoryId),
                Codec.STRING.optionalFieldOf("currentFactoryId", "").forGetter(FactorySession::currentFactoryId),
                FactoryReference.CODEC.optionalFieldOf("currentFactory")
                        .forGetter(session -> Optional.ofNullable(session.currentFactory)),
                Codec.BOOL.fieldOf("grantedFlight").forGetter(FactorySession::grantedFlight),
                Codec.BOOL.fieldOf("originalMayFly").forGetter(FactorySession::originalMayFly),
                Codec.BOOL.fieldOf("originalFlying").forGetter(FactorySession::originalFlying),
                Codec.BOOL.fieldOf("nightVisionGranted").forGetter(FactorySession::nightVisionGranted),
                Codec.BOOL.fieldOf("originalNightVision").forGetter(FactorySession::originalNightVision),
                Codec.list(ReturnFrame.CODEC).fieldOf("stack").forGetter(FactorySession::stack)
        ).apply(instance, (rootFactoryId, currentFactoryId, currentFactory, grantedFlight, originalMayFly,
                            originalFlying, nightVisionGranted, originalNightVision, stack) ->
                new FactorySession(rootFactoryId, currentFactoryId, currentFactory.orElse(null), grantedFlight,
                        originalMayFly, originalFlying, nightVisionGranted, originalNightVision, stack)));
    }
}
