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

    /** The passage item selection is deliberately transient and never follows an ItemStack. */
    public static final Supplier<AttachmentType<PassageSelection>> PASSAGE_SELECTION = ATTACHMENT_TYPES.register("passage_selection",
            () -> AttachmentType.builder(PassageSelection::empty).build());

    /** Prevents an arrival inside an open passage from immediately sending the player back. */
    public static final Supplier<AttachmentType<PassageTravelGuard>> PASSAGE_TRAVEL_GUARD = ATTACHMENT_TYPES.register("passage_travel_guard",
            () -> AttachmentType.builder(PassageTravelGuard::empty).build());

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

    public record PassageSelection(FactoryReference target) {
        public static PassageSelection empty() {
            return new PassageSelection(null);
        }

        public boolean isPresent() {
            return target != null && target.isComplete();
        }
    }

    public record PassageTravelGuard(String passageId, ResourceKey<Level> dimension, BlockPos pos,
                                     int settlingTicks, int outsideTicks, long lastContactGameTime,
                                     boolean contactObserved) {
        public static final int ARRIVAL_SETTLING_TICKS = 40;
        public static final int EXIT_CONFIRM_TICKS = 2;

        public PassageTravelGuard {
            passageId = passageId == null ? "" : passageId;
            pos = pos == null ? null : pos.immutable();
        }

        public static PassageTravelGuard empty() {
            return new PassageTravelGuard("", null, null, 0, 0, Long.MIN_VALUE, false);
        }

        public static PassageTravelGuard arrival(String passageId, ResourceKey<Level> dimension, BlockPos pos) {
            if (passageId == null || passageId.isBlank() || dimension == null || pos == null) {
                return empty();
            }
            return new PassageTravelGuard(passageId, dimension, pos, ARRIVAL_SETTLING_TICKS,
                    0, Long.MIN_VALUE, false);
        }

        public boolean matches(String candidatePassageId, ResourceKey<Level> candidateDimension,
                               BlockPos candidatePos) {
            if (!isActive() || candidatePassageId == null || candidatePassageId.isBlank()) {
                return false;
            }
            return passageId.equals(candidatePassageId);
        }

        public boolean isActive() {
            return !passageId.isBlank() && dimension != null && pos != null;
        }

        /** Whether a passage collision must be ignored while this arrival guard is active. */
        public boolean blocks(String candidatePassageId, ResourceKey<Level> candidateDimension,
                              BlockPos candidatePos) {
            return matches(candidatePassageId, candidateDimension, candidatePos);
        }

        /** Records broad collision contact before the narrower passage trigger test runs. */
        public PassageTravelGuard onCollision(String candidatePassageId, ResourceKey<Level> candidateDimension,
                                              BlockPos candidatePos, long gameTime) {
            if (!matches(candidatePassageId, candidateDimension, candidatePos)) {
                return this;
            }
            return new PassageTravelGuard(passageId, candidateDimension, candidatePos, settlingTicks,
                    0, gameTime, true);
        }

        /** Keeps the guard until the destination passage has been observed and then actually left. */
        public PassageTravelGuard afterTick(ResourceKey<Level> playerDimension, long gameTime) {
            if (!isActive() || lastContactGameTime == gameTime) {
                return this;
            }
            if (contactObserved) {
                int nextOutsideTicks = outsideTicks + 1;
                return nextOutsideTicks >= EXIT_CONFIRM_TICKS ? empty()
                        : new PassageTravelGuard(passageId, dimension, pos, settlingTicks,
                        nextOutsideTicks, lastContactGameTime, true);
            }
            if (!dimension.equals(playerDimension)) {
                return this;
            }
            if (settlingTicks > 1) {
                return new PassageTravelGuard(passageId, dimension, pos, settlingTicks - 1,
                        0, lastContactGameTime, false);
            }
            return empty();
        }
    }

    /** Optional data interpreted by a compatibility adapter to follow a moving physical carrier. */
    public record DynamicReturnAnchor(String resolverId, int version, String factoryId, String carrierId,
                                      Vec3 localOffset, Vec3 localLookDirection,
                                      // Retained for decoding legacy dynamic-passage sessions; new anchors leave it null.
                                      BlockPos returnGuardPos) {
        public static final Codec<DynamicReturnAnchor> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("resolverId").forGetter(DynamicReturnAnchor::resolverId),
                Codec.INT.fieldOf("version").forGetter(DynamicReturnAnchor::version),
                Codec.STRING.fieldOf("factoryId").forGetter(DynamicReturnAnchor::factoryId),
                Codec.STRING.fieldOf("carrierId").forGetter(DynamicReturnAnchor::carrierId),
                Vec3.CODEC.fieldOf("localOffset").forGetter(DynamicReturnAnchor::localOffset),
                Vec3.CODEC.fieldOf("localLookDirection").forGetter(DynamicReturnAnchor::localLookDirection),
                BlockPos.CODEC.optionalFieldOf("returnGuardPos")
                        .forGetter(anchor -> Optional.ofNullable(anchor.returnGuardPos))
        ).apply(instance, (resolverId, version, factoryId, carrierId, localOffset, localLookDirection,
                           returnGuardPos) -> new DynamicReturnAnchor(resolverId, version, factoryId, carrierId,
                localOffset, localLookDirection, returnGuardPos.orElse(null))));
    }

    public record ReturnFrame(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                              String sourceFactoryId, String targetFactoryId,
                              FactoryReference sourceFactory, boolean passage, String returnPassageId,
                              DynamicReturnAnchor dynamicAnchor) {
        public static ReturnFrame external(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                                           String targetFactoryId) {
            return external(dimension, pos, yRot, xRot, targetFactoryId, null);
        }

        public static ReturnFrame external(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                                           String targetFactoryId, DynamicReturnAnchor dynamicAnchor) {
            return new ReturnFrame(dimension, pos, yRot, xRot, "", targetFactoryId, null, false, "",
                    dynamicAnchor);
        }

        public static ReturnFrame externalPassage(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                                                   String targetFactoryId, String returnPassageId) {
            return new ReturnFrame(dimension, pos, yRot, xRot, "", targetFactoryId, null, true,
                    returnPassageId, null);
        }

        public static final Codec<ReturnFrame> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(ReturnFrame::dimension),
                Vec3.CODEC.fieldOf("pos").forGetter(ReturnFrame::pos),
                Codec.FLOAT.fieldOf("yRot").forGetter(ReturnFrame::yRot),
                Codec.FLOAT.fieldOf("xRot").forGetter(ReturnFrame::xRot),
                Codec.STRING.optionalFieldOf("sourceFactoryId", "").forGetter(ReturnFrame::sourceFactoryId),
                Codec.STRING.optionalFieldOf("targetFactoryId", "").forGetter(ReturnFrame::targetFactoryId),
                FactoryReference.CODEC.optionalFieldOf("sourceFactory")
                        .forGetter(frame -> Optional.ofNullable(frame.sourceFactory)),
                Codec.BOOL.optionalFieldOf("passage", false).forGetter(ReturnFrame::passage),
                Codec.STRING.optionalFieldOf("returnPassageId", "").forGetter(ReturnFrame::returnPassageId),
                DynamicReturnAnchor.CODEC.optionalFieldOf("dynamicAnchor")
                        .forGetter(frame -> Optional.ofNullable(frame.dynamicAnchor))
        ).apply(instance, (dimension, pos, yRot, xRot, sourceFactoryId, targetFactoryId, sourceFactory, passage,
                           returnPassageId, dynamicAnchor) ->
                new ReturnFrame(dimension, pos, yRot, xRot, sourceFactoryId, targetFactoryId,
                        sourceFactory.orElse(null), passage, returnPassageId, dynamicAnchor.orElse(null))));
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
