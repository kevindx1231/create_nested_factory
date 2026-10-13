package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.OperationMode;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.event.PocketEvents;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundUpdateMobEffectPacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Runs the real entry/session code; world I/O models 1.20.1's client reset on dimension respawn. */
public final class ExplorationSessionRegression {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        for (GameType mode : new GameType[]{GameType.SURVIVAL, GameType.CREATIVE}) {
            Fixture f = new Fixture(mode);
            NestedFactoryBlock.enterFactory(f.player, f.factory);
            require(f.clientAbilities.mayfly && f.clientAbilities.flying,
                    "client flight lost after dimension respawn in " + mode);
            require(f.clientEffects.containsKey(MobEffects.NIGHT_VISION)
                            && f.clientEffects.get(MobEffects.NIGHT_VISION).isInfiniteDuration(),
                    "client infinite night vision lost after dimension respawn");
        }
        Fixture denied = new Fixture(GameType.SURVIVAL);
        denied.denyTravel = true;
        NestedFactoryBlock.enterFactory(denied.player, denied.factory);
        require(!ModAttachments.get(denied.player, ModAttachments.FACTORY_SESSION).isActive(),
                "cancelled dimension travel committed a factory session");
        require(!denied.serverAbilities.mayfly && !denied.serverEffects.containsKey(MobEffects.NIGHT_VISION),
                "cancelled entry leaked exploration abilities");
        flyingWallReturn();
        for (int duration : new int[]{400, 100, MobEffectInstance.INFINITE_DURATION}) restoreNightVision(duration);
        recoverAfterRespawn();
        nestedAndLogin();
        cancelledReturn();
        legacySession();
        gameModeChanges();
        gameModeChangesInsideAndExistingGrant();
        System.out.println("Exploration entry, Shift return, effect restoration, nested/login and cancelled-travel regressions passed.");
    }

    private static void flyingWallReturn() throws Exception {
        Fixture f = new Fixture(GameType.SURVIVAL);
        f.serverEffects.put(MobEffects.MOVEMENT_SPEED, new MobEffectInstance(MobEffects.MOVEMENT_SPEED, 600));
        NestedFactoryBlock.enterFactory(f.player, f.factory);
        require(f.clientEffects.containsKey(MobEffects.MOVEMENT_SPEED), "pre-existing effects were not resent after respawn");
        when(f.player.isShiftKeyDown()).thenReturn(true);
        when(f.player.isCrouching()).thenReturn(false);
        BlockPos wall = new BlockPos(0, 130, 2);
        var hit = new BlockHitResult(Vec3.atCenterOf(wall), Direction.EAST, wall, false);
        var event = new PlayerInteractEvent.RightClickBlock(f.player, InteractionHand.MAIN_HAND, wall, hit);
        // World block identity is the seam; the real interaction handler and exit path still execute.
        try (var walls = mockStatic(NestedFactoryBlock.class, call ->
                call.getMethod().getName().equals("isWallBlock") ? true : call.callRealMethod())) {
            PocketEvents.onRightClickBlock(event);
        }
        require(event.isCanceled() && f.level.get() == f.overworld, "Shift+right-click while flying did not return");
        require(!f.clientAbilities.mayfly && !f.clientAbilities.flying, "outside survival player retained flight");
        require(!f.serverEffects.containsKey(MobEffects.NIGHT_VISION)
                && !f.clientEffects.containsKey(MobEffects.NIGHT_VISION), "temporary night vision leaked on exit");
        require(f.clientEffects.containsKey(MobEffects.MOVEMENT_SPEED), "exit lost another active effect");
    }

    private static void restoreNightVision(int duration) throws Exception {
        Fixture f = new Fixture(GameType.CREATIVE);
        f.serverAbilities.flying = true;
        f.serverEffects.put(MobEffects.NIGHT_VISION,
                new MobEffectInstance(MobEffects.NIGHT_VISION, duration, 1, true, true, false));
        NestedFactoryBlock.enterFactory(f.player, f.factory);
        var session = ModAttachments.get(f.player, ModAttachments.FACTORY_SESSION);
        require(session.originalNightVisionEffect().isPresent(), "original effect snapshot was not persisted");
        f.time += 150;
        NestedFactoryBlock.exitCurrentFactory(f.player);
        require(f.level.get() == f.overworld && f.clientAbilities.flying, "original creative flight was not restored after respawn");
        MobEffectInstance restored = f.serverEffects.get(MobEffects.NIGHT_VISION);
        if (duration == 100) {
            require(restored == null && !f.clientEffects.containsKey(MobEffects.NIGHT_VISION), "expired original effect was revived");
        } else {
            require(restored != null && restored.getDuration() == (duration == -1 ? -1 : duration - 150)
                    && restored.getAmplifier() == 1 && restored.isAmbient() && restored.isVisible() && !restored.showIcon(),
                    "original night vision duration/amplifier/display flags were not restored");
            require(f.clientEffects.get(MobEffects.NIGHT_VISION).getDuration() == restored.getDuration(), "restored effect was not synchronized");
        }
    }

    private static void recoverAfterRespawn() throws Exception {
        Fixture f = new Fixture(GameType.CREATIVE);
        f.serverAbilities.flying = true;
        f.serverEffects.put(MobEffects.NIGHT_VISION, new MobEffectInstance(MobEffects.NIGHT_VISION, 500));
        NestedFactoryBlock.enterFactory(f.player, f.factory);
        when(f.factory.isEnterable()).thenReturn(false);
        NestedFactoryBlock.exitCurrentFactory(f.player);
        require(f.level.get() == f.overworld && f.clientAbilities.flying, "recovery did not sync abilities AFTER its teleport");
        require(f.clientEffects.get(MobEffects.NIGHT_VISION).getDuration() == 500, "recovery lost original night vision");
        require(!ModAttachments.get(f.player, ModAttachments.FACTORY_SESSION).isActive(), "recovery left an active session");
    }

    private static void nestedAndLogin() throws Exception {
        Fixture f = new Fixture(GameType.SURVIVAL);
        NestedFactoryBlock.enterFactory(f.player, f.factory);
        f.serverAbilities.flying = false;
        f.clientAbilities.flying = false;
        NestedFactoryBlockEntity child = mock(NestedFactoryBlockEntity.class);
        when(child.isEnterable()).thenReturn(true);
        when(child.requestRoomBuild()).thenReturn(true);
        when(child.getOperationMode()).thenReturn(OperationMode.CHUNK_LOADED);
        when(child.getFactoryId()).thenReturn("child");
        when(child.getRootFactoryId()).thenReturn("factory");
        when(child.getParentFactoryId()).thenReturn("factory");
        when(child.getLevel()).thenReturn(f.pocket);
        when(child.getBlockPos()).thenReturn(new BlockPos(2, 130, 2));
        when(child.roomOrigin()).thenReturn(new BlockPos(32, 128, 0));
        when(child.getBounds()).thenReturn(new PocketBounds());
        when(f.pocket.getBlockEntity(child.getBlockPos())).thenReturn(child);
        NestedFactoryBlock.enterFactory(f.player, child);
        var session = ModAttachments.get(f.player, ModAttachments.FACTORY_SESSION);
        require(session.currentFactoryId().equals("child") && session.stack().size() == 2
                && session.explorationStartedAt() == 1000 && !f.clientAbilities.flying, "nested entry reset the session or forced flight");
        NestedFactoryBlock.exitCurrentFactory(f.player);
        require(ModAttachments.get(f.player, ModAttachments.FACTORY_SESSION).currentFactoryId().equals("factory")
                && !f.clientAbilities.flying, "parent return changed landed flight state");
        f.clientAbilities.mayfly = false;
        f.clientEffects.clear();
        CompoundTag savedPlayerData = f.player.getPersistentData().copy();
        when(f.player.getPersistentData()).thenReturn(savedPlayerData);
        NestedFactoryBlock.resumeSessionForPlayer(f.player);
        require(f.clientAbilities.mayfly && !f.clientAbilities.flying && f.clientEffects.containsKey(MobEffects.NIGHT_VISION),
                "login resume failed to resend existing effects or forced takeoff");
        f.serverEffects.remove(MobEffects.NIGHT_VISION);
        NestedFactoryBlock.ensureExplorationNightVision(f.player);
        require(f.serverEffects.get(MobEffects.NIGHT_VISION).isInfiniteDuration() && !f.serverAbilities.flying,
                "night vision maintenance changed flight state");
    }

    private static void cancelledReturn() throws Exception {
        Fixture f = new Fixture(GameType.SURVIVAL);
        NestedFactoryBlock.enterFactory(f.player, f.factory);
        f.denyTravel = true;
        NestedFactoryBlock.exitCurrentFactory(f.player);
        require(f.level.get() == f.pocket && ModAttachments.get(f.player, ModAttachments.FACTORY_SESSION).stack().size() == 1,
                "cancelled return discarded its return frame");
        require(f.serverAbilities.mayfly && f.clientAbilities.mayfly, "cancelled return revoked flight inside the factory");
        verify(f.factory, never()).onPlayerExited();
    }

    private static void legacySession() throws Exception {
        Fixture f = new Fixture(GameType.SURVIVAL);
        NestedFactoryBlock.enterFactory(f.player, f.factory);
        CompoundTag tag = f.player.getPersistentData().getCompound(Create_nested_factory.MODID + ":factory_session");
        tag.remove("originalNightVisionEffect");
        tag.remove("explorationStartedAt");
        tag.remove("flightGameMode");
        var session = ModAttachments.get(f.player, ModAttachments.FACTORY_SESSION);
        require(session.isActive() && session.explorationStartedAt() == -1 && session.originalNightVisionEffect().isEmpty()
                        && session.flightGameMode().isEmpty(),
                "legacy session could not load without new snapshot fields");
        NestedFactoryBlock.exitCurrentFactory(f.player);
        require(f.level.get() == f.overworld && !f.serverEffects.containsKey(MobEffects.NIGHT_VISION),
                "legacy session restoration failed");
    }

    private static void gameModeChanges() throws Exception {
        for (GameType initial : GameType.values()) {
            for (GameType target : GameType.values()) {
                if (initial == target) continue;
                Fixture f = new Fixture(initial);
                NestedFactoryBlock.enterFactory(f.player, f.factory);
                f.mode.set(target);
                target.updatePlayerAbilities(f.serverAbilities);
                f.player.onUpdateAbilities();
                NestedFactoryBlock.exitCurrentFactory(f.player);
                require(f.clientAbilities.mayfly == (target == GameType.CREATIVE || target == GameType.SPECTATOR),
                        "exit restored stale flight permission after " + initial + " -> " + target);
                if (target == GameType.SPECTATOR) require(f.clientAbilities.flying, "spectator lost flying state");
                if (target == GameType.SURVIVAL || target == GameType.ADVENTURE)
                    require(!f.clientAbilities.flying, "survival/adventure retained temporary flight");
            }
        }
    }

    private static void gameModeChangesInsideAndExistingGrant() throws Exception {
        Fixture f = new Fixture(GameType.SURVIVAL);
        NestedFactoryBlock.enterFactory(f.player, f.factory);
        for (GameType mode : new GameType[]{GameType.CREATIVE, GameType.SPECTATOR, GameType.ADVENTURE, GameType.SURVIVAL}) {
            f.mode.set(mode);
            mode.updatePlayerAbilities(f.serverAbilities);
            NestedFactoryBlock.refreshExplorationFlight(f.player);
            require(f.clientAbilities.mayfly, "successful mode change revoked exploration flight inside");
            require(ModAttachments.get(f.player, ModAttachments.FACTORY_SESSION).flightGameMode().equals(mode.getName()),
                    "mode-change restoration baseline did not persist");
        }
        NestedFactoryBlock.exitCurrentFactory(f.player);
        require(!f.clientAbilities.mayfly && !f.clientAbilities.flying, "round-trip mode changes leaked flight");

        Fixture granted = new Fixture(GameType.SURVIVAL);
        granted.serverAbilities.mayfly = true;
        granted.serverAbilities.flying = true;
        NestedFactoryBlock.enterFactory(granted.player, granted.factory);
        // A cancelled change leaves the actual game mode unchanged; the tick must preserve the baseline.
        NestedFactoryBlock.refreshExplorationFlight(granted.player);
        NestedFactoryBlock.exitCurrentFactory(granted.player);
        require(granted.clientAbilities.mayfly && granted.clientAbilities.flying,
                "unchanged survival mode lost a pre-existing external flight grant");
    }

    private static final class Fixture {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel overworld = mock(ServerLevel.class);
        final ServerLevel pocket = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final NestedFactoryBlockEntity factory = mock(NestedFactoryBlockEntity.class);
        final Abilities serverAbilities = new Abilities();
        final Abilities clientAbilities = new Abilities();
        final Map<MobEffect, MobEffectInstance> serverEffects = new HashMap<>();
        final Map<MobEffect, MobEffectInstance> clientEffects = new HashMap<>();
        final AtomicReference<ServerLevel> level = new AtomicReference<>(overworld);
        final AtomicReference<Vec3> position = new AtomicReference<>(new Vec3(10.5, 64, 10.5));
        final AtomicReference<GameType> mode = new AtomicReference<>();
        boolean denyTravel;
        long time = 1000;

        Fixture(GameType mode) throws Exception {
            this.mode.set(mode);
            var gameMode = mock(ServerPlayerGameMode.class);
            when(gameMode.getGameModeForPlayer()).thenAnswer(ignored -> this.mode.get());
            var gameModeField = ServerPlayer.class.getDeclaredField("gameMode");
            gameModeField.setAccessible(true);
            gameModeField.set(player, gameMode);
            when(server.getLevel(Level.OVERWORLD)).thenReturn(overworld);
            when(server.getLevel(NestedFactoryBlock.POCKET_DIMENSION)).thenReturn(pocket);
            when(server.overworld()).thenReturn(overworld);
            when(overworld.dimension()).thenReturn(Level.OVERWORLD);
            when(pocket.dimension()).thenReturn(NestedFactoryBlock.POCKET_DIMENSION);
            when(overworld.getServer()).thenReturn(server);
            when(pocket.getServer()).thenReturn(server);
            when(overworld.getGameTime()).thenAnswer(ignored -> time);
            when(pocket.getGameTime()).thenAnswer(ignored -> time);
            when(overworld.hasChunkAt(any())).thenReturn(true);
            when(pocket.hasChunkAt(any())).thenReturn(true);
            when(overworld.getBlockEntity(new BlockPos(10, 64, 10))).thenReturn(factory);
            when(pocket.getBlockState(any())).thenReturn(Blocks.STONE.defaultBlockState());
            Field serverField = ServerPlayer.class.getDeclaredField("server");
            serverField.setAccessible(true);
            serverField.set(player, server);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            when(player.serverLevel()).thenAnswer(ignored -> level.get());
            when(player.level()).thenAnswer(ignored -> level.get());
            when(player.position()).thenAnswer(ignored -> position.get());
            when(player.blockPosition()).thenAnswer(ignored -> BlockPos.containing(position.get()));
            when(player.getPersistentData()).thenReturn(new CompoundTag());
            when(player.getAbilities()).thenReturn(serverAbilities);
            when(player.getItemInHand(any())).thenReturn(ItemStack.EMPTY);
            when(player.getMainHandItem()).thenReturn(ItemStack.EMPTY);
            when(player.getOffhandItem()).thenReturn(ItemStack.EMPTY);
            mode.updatePlayerAbilities(serverAbilities);
            mode.updatePlayerAbilities(clientAbilities);
            doAnswer(ignored -> {
                clientAbilities.mayfly = serverAbilities.mayfly;
                clientAbilities.flying = serverAbilities.flying;
                return null;
            }).when(player).onUpdateAbilities();
            when(player.getEffect(any())).thenAnswer(call -> serverEffects.get(call.getArgument(0)));
            when(player.hasEffect(any())).thenAnswer(call -> serverEffects.containsKey(call.getArgument(0)));
            when(player.getActiveEffects()).thenAnswer(ignored -> serverEffects.values());
            when(player.addEffect(any(MobEffectInstance.class))).thenAnswer(call -> {
                MobEffectInstance effect = call.getArgument(0);
                MobEffectInstance old = serverEffects.get(effect.getEffect());
                boolean changed = old == null || old.update(effect);
                if (old == null) serverEffects.put(effect.getEffect(), new MobEffectInstance(effect));
                if (changed) clientEffects.put(effect.getEffect(), new MobEffectInstance(serverEffects.get(effect.getEffect())));
                return changed;
            });
            when(player.removeEffect(any())).thenAnswer(call -> {
                MobEffect effect = call.getArgument(0);
                clientEffects.remove(effect);
                return serverEffects.remove(effect) != null;
            });
            doAnswer(call -> {
                if (call.getArgument(0) instanceof ClientboundUpdateMobEffectPacket packet) {
                    clientEffects.put(packet.getEffect(), new MobEffectInstance(packet.getEffect(), packet.getEffectDurationTicks(),
                            packet.getEffectAmplifier(), packet.isEffectAmbient(), packet.isEffectVisible(), packet.effectShowsIcon()));
                }
                return null;
            }).when(player.connection).send(any());
            doAnswer(call -> {
                travel(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), this.mode.get());
                return null;
            }).when(player).teleportTo(any(ServerLevel.class), anyDouble(), anyDouble(), anyDouble(), anyFloat(), anyFloat());
            when(player.teleportTo(any(ServerLevel.class), anyDouble(), anyDouble(), anyDouble(), anySet(), anyFloat(), anyFloat()))
                    .thenAnswer(call -> {
                        travel(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3), this.mode.get());
                        return true; // 1.20.1 may return true even when Forge cancels a dimension change.
                    });
            when(factory.isEnterable()).thenReturn(true);
            when(factory.requestRoomBuild()).thenReturn(true);
            when(factory.getOperationMode()).thenReturn(OperationMode.CHUNK_LOADED);
            when(factory.getFactoryId()).thenReturn("factory");
            when(factory.getRootFactoryId()).thenReturn("factory");
            when(factory.getLevel()).thenReturn(overworld);
            when(factory.getBlockPos()).thenReturn(new BlockPos(10, 64, 10));
            when(factory.roomOrigin()).thenReturn(new BlockPos(0, 128, 0));
            when(factory.getBounds()).thenReturn(new PocketBounds());
        }

        private void travel(ServerLevel target, double x, double y, double z, GameType mode) {
            if (denyTravel) return;
            if (target != level.get()) {
                clientEffects.clear();
                clientAbilities.flying = false;
                mode.updatePlayerAbilities(clientAbilities);
            }
            level.set(target);
            position.set(new Vec3(x, y, z));
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
