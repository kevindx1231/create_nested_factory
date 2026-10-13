package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public class ModAttachments {
    public record Key<T>(String name, Supplier<T> defaults, Codec<T> codec) {}
    public static final Key<FactorySession> FACTORY_SESSION = new Key<>("factory_session", FactorySession::defaults, FactorySession.CODEC);
    public static final Key<PassageSelection> PASSAGE_SELECTION = new Key<>("passage_selection", PassageSelection::empty, null);
    public static final Key<PassageTravelGuard> PASSAGE_TRAVEL_GUARD = new Key<>("passage_travel_guard", PassageTravelGuard::empty, null);
    public static final Key<Boolean> PASSAGE_RESUME_PENDING = new Key<>("passage_resume_pending", () -> false, null);
    public static final Key<String> EXPLORATION_FLIGHT_MODE = new Key<>("exploration_flight_mode", () -> "", null);
    private static final java.util.Map<net.minecraft.world.entity.player.Player, java.util.Map<Key<?>, Object>> TRANSIENT = new java.util.WeakHashMap<>();

    public static synchronized <T> T get(net.minecraft.world.entity.player.Player player, Key<T> key) {
        if (key.codec() != null) {
            net.minecraft.nbt.Tag tag = player.getPersistentData().get(Create_nested_factory.MODID + ":" + key.name());
            if (tag != null) return key.codec().parse(net.minecraft.nbt.NbtOps.INSTANCE, tag).result().orElseGet(key.defaults());
            return key.defaults().get();
        }
        Object value = TRANSIENT.computeIfAbsent(player, ignored -> new java.util.HashMap<>())
                .computeIfAbsent(key, ignored -> key.defaults().get());
        @SuppressWarnings("unchecked") T result = (T) value;
        return result;
    }

    public static synchronized <T> void set(net.minecraft.world.entity.player.Player player, Key<T> key, T value) {
        if (key.codec() != null) {
            net.minecraft.nbt.Tag tag = key.codec().encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, value).result()
                    .orElseThrow(() -> new IllegalArgumentException("Cannot persist factory session"));
            player.getPersistentData().put(Create_nested_factory.MODID + ":" + key.name(), tag);
        } else TRANSIENT.computeIfAbsent(player, ignored -> new java.util.HashMap<>()).put(key, value);
    }

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
                                     int settlingTicks, int outsideTicks, boolean arrived, boolean contactObserved) {
        public static final int ARRIVAL_SETTLING_TICKS = 40;
        public static final int EXIT_CONFIRM_TICKS = 2;

        public PassageTravelGuard {
            passageId = passageId == null ? "" : passageId;
            pos = pos == null ? null : pos.immutable();
        }

        public static PassageTravelGuard empty() {
            return new PassageTravelGuard("", null, null, 0, 0, false, false);
        }

        public static PassageTravelGuard arrival(String passageId, ResourceKey<Level> dimension, BlockPos pos) {
            if (passageId == null || passageId.isBlank() || dimension == null || pos == null) return empty();
            return new PassageTravelGuard(passageId, dimension, pos, ARRIVAL_SETTLING_TICKS, 0, false, false);
        }

        public boolean isActive() {
            return !passageId.isBlank() && dimension != null && pos != null;
        }

        public boolean blocks(String candidatePassageId) {
            return isActive() && passageId.equals(candidatePassageId);
        }

        /** Null contact means the endpoint is temporarily unavailable, not that it was left. */
        public PassageTravelGuard afterTick(ResourceKey<Level> playerDimension, Boolean inside,
                                             BlockPos currentPos) {
            if (!isActive()) return this;
            if (!dimension.equals(playerDimension)) return arrived ? empty() : this;
            if (inside == null) {
                return new PassageTravelGuard(passageId, dimension, currentPos, settlingTicks, 0, true, contactObserved);
            }
            int remaining = Math.max(0, settlingTicks - 1);
            if (inside) return new PassageTravelGuard(passageId, dimension, currentPos, remaining, 0, true, true);
            // Once contact is observed, leaving a static passage releases the guard after two ticks.
            if (!contactObserved && remaining > 0) {
                return new PassageTravelGuard(passageId, dimension, currentPos, remaining, 0, true, contactObserved);
            }
            int outside = outsideTicks + 1;
            return outside >= EXIT_CONFIRM_TICKS ? empty()
                    : new PassageTravelGuard(passageId, dimension, currentPos, remaining, outside, true, contactObserved);
        }
    }

    public record ReturnFrame(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                              String sourceFactoryId, String targetFactoryId,
                              FactoryReference sourceFactory, boolean passage, String returnPassageId) {
        public static ReturnFrame external(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                                           String targetFactoryId) {
            return new ReturnFrame(dimension, pos, yRot, xRot, "", targetFactoryId, null, false, "");
        }
        public static ReturnFrame externalPassage(ResourceKey<Level> dimension, Vec3 pos, float yRot, float xRot,
                                                  String targetFactoryId, String returnPassageId) {
            return new ReturnFrame(dimension, pos, yRot, xRot, "", targetFactoryId, null, true, returnPassageId);
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
                Codec.STRING.optionalFieldOf("returnPassageId", "").forGetter(ReturnFrame::returnPassageId)
        ).apply(instance, (dimension, pos, yRot, xRot, sourceFactoryId, targetFactoryId, sourceFactory, passage, returnPassageId) ->
                new ReturnFrame(dimension, pos, yRot, xRot, sourceFactoryId, targetFactoryId,
                        sourceFactory.orElse(null), passage, returnPassageId)));
    }

    public record FactorySession(String rootFactoryId, String currentFactoryId,
                                 FactoryReference currentFactory,
                                 boolean grantedFlight, boolean originalMayFly, boolean originalFlying,
                                 boolean nightVisionGranted, boolean originalNightVision,
                                  List<ReturnFrame> stack, Optional<CompoundTag> originalNightVisionEffect,
                                  long explorationStartedAt, String flightGameMode) {
        public FactorySession(String rootFactoryId, String currentFactoryId, FactoryReference currentFactory,
                              boolean grantedFlight, boolean originalMayFly, boolean originalFlying,
                              boolean nightVisionGranted, boolean originalNightVision, List<ReturnFrame> stack,
                              Optional<CompoundTag> originalNightVisionEffect, long explorationStartedAt) {
            this(rootFactoryId, currentFactoryId, currentFactory, grantedFlight, originalMayFly, originalFlying,
                    nightVisionGranted, originalNightVision, stack, originalNightVisionEffect, explorationStartedAt, "");
        }
        // Older saves and callers have no full effect snapshot; retain their legacy restoration policy.
        public FactorySession(String rootFactoryId, String currentFactoryId, FactoryReference currentFactory,
                              boolean grantedFlight, boolean originalMayFly, boolean originalFlying,
                              boolean nightVisionGranted, boolean originalNightVision, List<ReturnFrame> stack) {
            this(rootFactoryId, currentFactoryId, currentFactory, grantedFlight, originalMayFly, originalFlying,
                    nightVisionGranted, originalNightVision, stack, Optional.empty(), -1L);
        }

        public FactorySession withCurrentFactory(String rootId, String currentId, FactoryReference reference,
                                                 List<ReturnFrame> frames) {
            return new FactorySession(rootId, currentId, reference, grantedFlight, originalMayFly, originalFlying,
                    nightVisionGranted, originalNightVision, frames, originalNightVisionEffect, explorationStartedAt,
                    flightGameMode);
        }

        public FactorySession withFlightSnapshot(String mode, boolean mayFly, boolean flying) {
            return new FactorySession(rootFactoryId, currentFactoryId, currentFactory, grantedFlight, mayFly, flying,
                    nightVisionGranted, originalNightVision, stack, originalNightVisionEffect, explorationStartedAt, mode);
        }
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
                Codec.list(ReturnFrame.CODEC).fieldOf("stack").forGetter(FactorySession::stack),
                CompoundTag.CODEC.optionalFieldOf("originalNightVisionEffect").forGetter(FactorySession::originalNightVisionEffect),
                Codec.LONG.optionalFieldOf("explorationStartedAt", -1L).forGetter(FactorySession::explorationStartedAt),
                Codec.STRING.optionalFieldOf("flightGameMode", "").forGetter(FactorySession::flightGameMode)
        ).apply(instance, (rootFactoryId, currentFactoryId, currentFactory, grantedFlight, originalMayFly,
                            originalFlying, nightVisionGranted, originalNightVision, stack, originalNightVisionEffect, explorationStartedAt,
                            flightGameMode) ->
                new FactorySession(rootFactoryId, currentFactoryId, currentFactory.orElse(null), grantedFlight,
                        originalMayFly, originalFlying, nightVisionGranted, originalNightVision, stack,
                        originalNightVisionEffect, explorationStartedAt, flightGameMode)));
    }
}
