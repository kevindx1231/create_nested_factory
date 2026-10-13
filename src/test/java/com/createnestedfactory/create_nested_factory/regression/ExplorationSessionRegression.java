package com.createnestedfactory.create_nested_factory.regression;

import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.registry.ModAttachments;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.level.GameType;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.effect.MobEffects;

import java.lang.reflect.Method;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public final class ExplorationSessionRegression {
    public static void main(String[] args) throws Exception {
        MekanismLogisticsRegression.bootstrap();
        for (GameType entered : GameType.values()) {
            for (GameType exited : GameType.values()) {
                Fixture f = new Fixture(exited, false);
                f.session = session(entered, entered == GameType.SPECTATOR);
                finish(f);
                require(f.abilities.flying == (exited == GameType.SPECTATOR), "invalid exit flight: " + entered + " -> " + exited);
                require(f.abilities.mayfly == (exited.isCreative() || exited == GameType.SPECTATOR),
                        "native game mode flight authorization changed");
                require(!f.session.isActive(), "session did not end");
                verify(f.player, never()).removeEffect(MobEffects.NIGHT_VISION);
            }
        }
        Fixture authorized = new Fixture(GameType.SURVIVAL, true);
        authorized.session = session(GameType.SURVIVAL, true);
        finish(authorized);
        require(authorized.abilities.flying && authorized.player.mayFly(), "external flight authorization was lost");
        var saved = ModAttachments.FactorySession.CODEC.encodeStart(NbtOps.INSTANCE, session(GameType.CREATIVE, true)).getOrThrow();
        ((net.minecraft.nbt.CompoundTag) saved).remove("flightGameMode");
        var legacy = ModAttachments.FactorySession.CODEC.parse(NbtOps.INSTANCE, saved).getOrThrow();
        require(legacy.flightGameMode().isEmpty() && legacy.originalFlying(), "legacy snapshot was not preserved");
        Fixture spectator = new Fixture(GameType.SPECTATOR, false);
        spectator.session = legacy;
        finish(spectator);
        require(spectator.abilities.flying, "legacy save disabled spectator flight");
        Fixture switching = new Fixture(GameType.SURVIVAL, false);
        switching.session = session(GameType.SURVIVAL, false);
        switching.mode = GameType.SPECTATOR;
        NestedFactoryBlock.synchronizeExplorationFlight(switching.player);
        require(switching.session.flightGameMode().equals("spectator") && switching.session.originalFlying(), "mode change was not persisted");
        switching.mode = GameType.ADVENTURE;
        NestedFactoryBlock.synchronizeExplorationFlight(switching.player);
        require(switching.session.flightGameMode().equals("adventure") && !switching.session.originalFlying(), "second mode change kept stale snapshot");
        require(switching.abilities.flying, "mode switch removed exploration flight inside factory");
        clearInvocations(switching.player);
        NestedFactoryBlock.synchronizeExplorationFlight(switching.player);
        verify(switching.player, never()).getData(ModAttachments.FACTORY_SESSION);
        verify(switching.player, never()).setData(eq(ModAttachments.FACTORY_SESSION), any(ModAttachments.FactorySession.class));
        System.out.println("Exploration regressions passed (16 mode pairs, external authorization, legacy save and repeated changes).");
    }

    private static ModAttachments.FactorySession session(GameType entered, boolean flying) {
        return new ModAttachments.FactorySession("root", "current", null,
                true, entered.isCreative() || entered == GameType.SPECTATOR, flying,
                true, true, List.of(), entered.getName());
    }

    private static void finish(Fixture f) throws Exception {
        Method finish = NestedFactoryBlock.class.getDeclaredMethod("finishSessionWithoutCurrent", ServerPlayer.class,
                ModAttachments.FactorySession.class);
        finish.setAccessible(true);
        finish.invoke(null, f.player, f.session);
    }

    private static final class Fixture {
        final ServerPlayer player = mock(ServerPlayer.class);
        final Abilities abilities = new Abilities();
        ModAttachments.FactorySession session;
        GameType mode;
        String marker = "";
        boolean exploration = true;

        Fixture(GameType mode, boolean externalPermission) throws Exception {
            this.mode = mode;
            mode.updatePlayerAbilities(abilities);
            abilities.flying = true;
            when(player.getAbilities()).thenReturn(abilities);
            when(player.isSpectator()).thenAnswer(call -> this.mode == GameType.SPECTATOR);
            when(player.isCreative()).thenAnswer(call -> this.mode == GameType.CREATIVE);
            when(player.mayFly()).thenAnswer(call -> abilities.mayfly || exploration || externalPermission);
            ServerPlayerGameMode gameMode = mock(ServerPlayerGameMode.class);
            when(gameMode.getGameModeForPlayer()).thenAnswer(call -> this.mode);
            var field = ServerPlayer.class.getDeclaredField("gameMode");
            field.setAccessible(true);
            field.set(player, gameMode);
            AttributeInstance attribute = mock(AttributeInstance.class);
            when(player.getAttribute(any())).thenReturn(attribute);
            doAnswer(call -> { exploration = false; return null; }).when(attribute).removeModifier(any(net.minecraft.resources.ResourceLocation.class));
            when(player.getData(ModAttachments.FACTORY_SESSION)).thenAnswer(call -> session);
            when(player.getData(ModAttachments.EXPLORATION_GAME_MODE)).thenAnswer(call -> marker);
            doAnswer(call -> { session = call.getArgument(1); return null; })
                    .when(player).setData(eq(ModAttachments.FACTORY_SESSION), any(ModAttachments.FactorySession.class));
            doAnswer(call -> { marker = call.getArgument(1); return null; })
                    .when(player).setData(eq(ModAttachments.EXPLORATION_GAME_MODE), anyString());
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
