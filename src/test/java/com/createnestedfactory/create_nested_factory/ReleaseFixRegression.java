package com.createnestedfactory.create_nested_factory;

import com.createnestedfactory.create_nested_factory.block.entity.FactoryPassagePairing;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryPlacementRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.Level;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Focused regression loop for the release-audit fixes. */
public final class ReleaseFixRegression {
    private ReleaseFixRegression() {
    }

    public static void main(String[] args) throws Exception {
        StringBuilder failures = new StringBuilder();
        check(failures, "pipez-only initializer", ReleaseFixRegression::pipezInitializesWithoutMekanism);
        check(failures, "passage routing", ReleaseFixRegression::passageUsesPrimaryInnerOrDefaultEntry);
        check(failures, "passage indexes", ReleaseFixRegression::passageIndexesRemainSelective);
        check(failures, "terminal nesting", ReleaseFixRegression::terminalNestingClassification);
        check(failures, "diagnostic default", ReleaseFixRegression::blackboxDiagnosticsAreDisabledByDefault);
        if (!failures.isEmpty()) throw new AssertionError(failures.toString());
        System.out.println("Release audit regression checks passed.");
    }

    private static void check(StringBuilder failures, String name, CheckedRunnable check) {
        try {
            check.run();
        } catch (Throwable failure) {
            if (!failures.isEmpty()) failures.append(System.lineSeparator());
            failures.append(name).append(": ").append(failure);
        }
    }

    private static void pipezInitializesWithoutMekanism() {
        var pipezOnly = OptionalCompatBootstrap.initializersFor(false, true, false, false);
        require(pipezOnly.contains(OptionalCompatBootstrap.CompatInitializer.PIPEZ),
                "Pipez-only installation does not schedule its network adapter initializer");
        require(!pipezOnly.contains(OptionalCompatBootstrap.CompatInitializer.MEKANISM),
                "Pipez-only installation unexpectedly schedules Mekanism initialization");
        var mekanismOnly = OptionalCompatBootstrap.initializersFor(true, false, false, false);
        require(mekanismOnly.contains(OptionalCompatBootstrap.CompatInitializer.MEKANISM)
                        && !mekanismOnly.contains(OptionalCompatBootstrap.CompatInitializer.PIPEZ),
                "Mekanism-only installation did not remain isolated from Pipez");
        var bothLogistics = OptionalCompatBootstrap.initializersFor(true, true, false, false);
        require(bothLogistics.contains(OptionalCompatBootstrap.CompatInitializer.MEKANISM)
                        && bothLogistics.contains(OptionalCompatBootstrap.CompatInitializer.PIPEZ),
                "combined logistics installation did not schedule both independent initializers");
        require(!OptionalCompatBootstrap.initializersFor(false, false, true, false)
                        .contains(OptionalCompatBootstrap.CompatInitializer.SIMULATED),
                "Simulated initialized without Sable");
        require(OptionalCompatBootstrap.initializersFor(false, false, true, true)
                        .contains(OptionalCompatBootstrap.CompatInitializer.SIMULATED),
                "Simulated and Sable together did not initialize their integration");
    }

    private static void passageUsesPrimaryInnerOrDefaultEntry() {
        require(FactoryPassagePairing.classifyInto(true, true)
                        == FactoryPassagePairing.IntoStatus.PRIMARY_INNER,
                "a bound outer passage did not route to the primary inner passage");
        require(FactoryPassagePairing.classifyInto(true, false)
                        == FactoryPassagePairing.IntoStatus.DEFAULT_ENTRY,
                "a bound outer passage without an inner passage did not use the default entry");
        require(FactoryPassagePairing.classifyInto(false, true)
                        == FactoryPassagePairing.IntoStatus.UNPAIRED,
                "an unbound outer passage was allowed to enter");
    }

    private static void terminalNestingClassification() {
        require(NestedFactoryPlacementRules.classify(8, 8, true, true)
                        == NestedFactoryPlacementRules.Kind.ROOM,
                "the maximum configured room depth was not retained as a physical room");
        require(NestedFactoryPlacementRules.classify(9, 8, true, true)
                        == NestedFactoryPlacementRules.Kind.TERMINAL_BLUEPRINT_ONLY,
                "the layer after the room-depth limit was not classified as a terminal factory");
        require(NestedFactoryPlacementRules.classify(10, 8, true, true)
                        == NestedFactoryPlacementRules.Kind.INVALID,
                "a factory deeper than the terminal layer was accepted");
        require(NestedFactoryPlacementRules.classify(9, 8, false, true)
                        == NestedFactoryPlacementRules.Kind.INVALID,
                "an out-of-room terminal placement was accepted");
        require(NestedFactoryPlacementRules.classify(9, 8, true, false)
                        == NestedFactoryPlacementRules.Kind.INVALID,
                "a second terminal child was accepted");
    }

    private static void blackboxDiagnosticsAreDisabledByDefault() throws Exception {
        Field field = Config.class.getDeclaredField("BLACKBOX_DEBUG_LOGGING");
        field.setAccessible(true);
        Object value = field.get(null);
        Method getDefault = value.getClass().getMethod("getDefault");
        require(Boolean.FALSE.equals(getDefault.invoke(value)),
                "black-box diagnostic logging remains enabled by default");
    }

    private static void passageIndexesRemainSelective() {
        FactoryPassageSavedData data = new FactoryPassageSavedData();
        ResourceKey<Level> pocket = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.fromNamespaceAndPath(Create_nested_factory.MODID, "nested_factory"));
        for (int i = 0; i < 10_000; i++) {
            data.register(Level.OVERWORLD, new BlockPos(i, 64, 0), "other-" + i);
            data.register(pocket, new BlockPos(i * 16, 128, 32), "");
        }
        BlockPos targetOuter = new BlockPos(2, 70, 2);
        BlockPos targetInner = new BlockPos(8, 130, 8);
        data.register(Level.OVERWORLD, targetOuter, "passage-stable-id", "target", "root-target");
        data.register(pocket, targetInner, "");

        var bound = data.boundTo("target");
        require(bound.size() == 1 && bound.getFirst().pos().equals(targetOuter),
                "factory-id index returned unrelated bound passages");
        require(data.findByPassageId("passage-stable-id").pos().equals(targetOuter),
                "stable passage id did not resolve its endpoint");
        var unbound = data.unboundInPocket(new BlockPos(0, 120, 0), new BlockPos(15, 140, 15));
        require(unbound.size() == 1 && unbound.getFirst().pos().equals(targetInner),
                "Pocket chunk index returned passages outside the requested room");

        BlockPos movedOuter = targetOuter.offset(32, 0, 0);
        data.register(Level.OVERWORLD, movedOuter, "passage-stable-id", "retargeted", "root-retargeted");
        require(data.boundTo("target").isEmpty(), "retargeting left a stale factory-id index entry");
        require(data.boundTo("retargeted").size() == 1, "retargeting did not update the factory-id index");
        require(data.findByPassageId("passage-stable-id").pos().equals(movedOuter),
                "moving a passage did not migrate its stable endpoint");
        data.unregister(pocket, targetInner);
        require(data.unboundInPocket(new BlockPos(0, 120, 0), new BlockPos(15, 140, 15)).isEmpty(),
                "unregister left a stale Pocket chunk index entry");

        data.register(pocket, targetInner, "");
        BlockPos laterInner = new BlockPos(9, 130, 8);
        data.register(pocket, laterInner, "");
        var ordered = data.unboundInPocket(new BlockPos(0, 120, 0), new BlockPos(15, 140, 15));
        require(ordered.size() == 2 && ordered.getFirst().pos().equals(targetInner)
                        && ordered.get(1).pos().equals(laterInner),
                "inner passages were not returned in persistent placement order");
        FactoryPassageSavedData loaded = FactoryPassageSavedData.load(
                data.save(new CompoundTag(), null), null);
        require(loaded.boundTo("retargeted").size() == 1,
                "factory-id index was not rebuilt after SavedData reload");
        require(loaded.findByPassageId("passage-stable-id").pos().equals(movedOuter),
                "stable passage-id index was not rebuilt after SavedData reload");
        var reloadedInners = loaded.unboundInPocket(new BlockPos(0, 120, 0), new BlockPos(15, 140, 15));
        require(reloadedInners.size() == 2 && reloadedInners.getFirst().pos().equals(targetInner)
                        && reloadedInners.get(1).pos().equals(laterInner),
                "Pocket chunk index was not rebuilt after SavedData reload");

        CompoundTag legacy = new CompoundTag();
        legacy.putInt("Format", 1);
        ListTag legacyEntries = new ListTag();
        legacyEntries.add(legacyPassageEntry(pocket, new BlockPos(2, 130, 8)));
        legacyEntries.add(legacyPassageEntry(pocket, new BlockPos(1, 130, 8)));
        legacy.put("Endpoints", legacyEntries);
        var migrated = FactoryPassageSavedData.load(legacy, null)
                .unboundInPocket(new BlockPos(0, 120, 0), new BlockPos(15, 140, 15));
        require(migrated.size() == 2 && migrated.getFirst().pos().equals(new BlockPos(1, 130, 8)),
                "format-1 passages did not receive deterministic initial placement order");
    }

    private static CompoundTag legacyPassageEntry(ResourceKey<Level> dimension, BlockPos pos) {
        CompoundTag entry = new CompoundTag();
        entry.putString("Dimension", dimension.location().toString());
        entry.putLong("Pos", pos.asLong());
        return entry;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
