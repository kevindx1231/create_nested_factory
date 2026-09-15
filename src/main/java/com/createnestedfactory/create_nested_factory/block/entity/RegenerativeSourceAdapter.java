package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.Map;

/** Extension seam for a source which may legally produce without an external material input. */
public interface RegenerativeSourceAdapter {
    String id();

    /** Bump whenever proof, mutation or output-attribution rules change. */
    default int ruleVersion() {
        return 1;
    }

    Evidence begin(ServerLevel level, PocketBounds bounds, BlockPos origin);

    interface Evidence {
        FactoryPlan.SourceKind sourceKind();

        boolean isSupported();

        default void beginWindow(int windowIndex) {
        }

        default void tick(ServerLevel level, int windowIndex) {
        }

        /** Returns true only for a mutation owned by this source's declared dynamic structure. */
        default boolean onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState,
                                       BlockState newState, int windowIndex) {
            return false;
        }

        /** Returns true when this source recognizes a completed Create drill cycle. */
        default boolean onDrillProduction(ServerLevel level, DrillProductionEvent event, int windowIndex) {
            return false;
        }

        default void recordBoundaryItemOutput(ItemStack stack, long moved, long gameTime, int windowIndex) {
        }

        default void recordBoundaryFluidOutput(FluidStack stack, long moved, long gameTime, int windowIndex) {
        }

        default boolean hasPreliminaryEvidence() {
            return verifiedCycles() > 0;
        }

        boolean isReady();

        /** Allows delayed transport to finish after the last source event was proven. */
        default boolean isReadyToFinalize(long gameTime) {
            return isReady();
        }

        int verifiedCycles();

        default int requiredCycles() {
            return 0;
        }

        default int evidenceWindowCount() {
            return 0;
        }

        default boolean allowsOutput(ItemStack stack) {
            return false;
        }

        default boolean allowsOutput(FluidStack stack) {
            return false;
        }

        default boolean canQuantifyMixedOutput(ItemStack stack) {
            return false;
        }

        default boolean canQuantifyMixedOutput(FluidStack stack) {
            return false;
        }

        default long claimedOutputAmount(ItemStack stack, long observed, boolean hasMaterialInputs) {
            return !hasMaterialInputs && allowsOutput(stack) ? Math.max(0L, observed) : 0L;
        }

        default long claimedOutputAmount(FluidStack stack, long observed, boolean hasMaterialInputs) {
            return !hasMaterialInputs && allowsOutput(stack) ? Math.max(0L, observed) : 0L;
        }

        default Map<ItemVariant, Long> startupCapitalItems() {
            return Map.of();
        }

        default Map<ItemVariant, Long> toolDamageTotals() {
            return Map.of();
        }

        default boolean hasNaturalWait() {
            return false;
        }

        default boolean hasStatisticalOutput() {
            return false;
        }

        default String debugSummary() {
            return "kind=" + sourceKind() + ", supported=" + isSupported() + ", ready=" + isReady()
                    + ", cycles=" + verifiedCycles() + "/" + requiredCycles()
                    + ", windows=" + evidenceWindowCount();
        }
    }
}
