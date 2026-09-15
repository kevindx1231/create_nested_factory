package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Source-agnostic evidence aggregate owned by one continuous black-box learning session.
 * Source-specific topology and event rules live exclusively behind {@link RegenerativeSourceAdapter}.
 */
public final class FactoryLearningSession {
    private record Proof(String adapterId, int ruleVersion, RegenerativeSourceAdapter.Evidence evidence) {
    }

    private final List<Proof> proofs = new ArrayList<>();
    private int currentWindow = -1;

    private FactoryLearningSession() {
    }

    public static FactoryLearningSession begin(ServerLevel level, PocketBounds bounds, BlockPos origin) {
        FactoryLearningSession session = new FactoryLearningSession();
        for (RegenerativeSourceAdapter adapter : RegenerativeSourceAdapters.all()) {
            RegenerativeSourceAdapter.Evidence evidence = adapter.begin(level, bounds, origin);
            if (evidence != null) session.proofs.add(new Proof(adapter.id(), adapter.ruleVersion(), evidence));
        }
        return session;
    }

    public void beginWindow(int windowIndex) {
        currentWindow = windowIndex;
        proofs.forEach(proof -> proof.evidence().beginWindow(windowIndex));
    }

    public void tick(ServerLevel level) {
        if (level == null) return;
        proofs.forEach(proof -> proof.evidence().tick(level, currentWindow));
    }

    /** Returns true when at least one adapter owns this dynamic mutation. */
    public boolean onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (level == null || pos == null) return false;
        boolean accepted = false;
        for (Proof proof : proofs) {
            accepted |= proof.evidence().onBlockChanged(level, pos, oldState, newState, currentWindow);
        }
        return accepted;
    }

    /** Dispatches one completed drill cycle without treating the event itself as item output. */
    public boolean onDrillProduction(ServerLevel level, DrillProductionEvent event) {
        if (level == null || event == null) return false;
        boolean accepted = false;
        for (Proof proof : proofs) {
            accepted |= proof.evidence().onDrillProduction(level, event, currentWindow);
        }
        return accepted;
    }

    public void recordBoundaryItemOutput(ItemStack stack, long moved, long gameTime) {
        if (stack == null || stack.isEmpty() || moved <= 0) return;
        proofs.forEach(proof -> proof.evidence()
                .recordBoundaryItemOutput(stack, moved, gameTime, currentWindow));
    }

    public void recordBoundaryFluidOutput(FluidStack stack, long moved, long gameTime) {
        if (stack == null || stack.isEmpty() || moved <= 0) return;
        proofs.forEach(proof -> proof.evidence()
                .recordBoundaryFluidOutput(stack, moved, gameTime, currentWindow));
    }

    public boolean hasPreliminaryEvidence() {
        return proofs.stream().anyMatch(proof -> proof.evidence().isSupported()
                && proof.evidence().hasPreliminaryEvidence());
    }

    public boolean isSupported() {
        return proofs.stream().anyMatch(proof -> proof.evidence().isSupported());
    }

    public boolean isReady() {
        List<Proof> supported = supportedProofs();
        return !supported.isEmpty()
                && supported.stream().allMatch(proof -> proof.evidence().isReady());
    }

    public boolean isReadyToFinalize(long gameTime) {
        List<Proof> supported = supportedProofs();
        return !supported.isEmpty()
                && supported.stream().allMatch(proof -> proof.evidence().isReadyToFinalize(gameTime));
    }

    public int verifiedCycles() {
        return supportedProofs().stream().mapToInt(proof -> proof.evidence().verifiedCycles()).min().orElse(0);
    }

    public int requiredCycles() {
        return supportedProofs().stream().mapToInt(proof -> proof.evidence().requiredCycles()).max().orElse(0);
    }

    public FactoryPlan.SourceKind sourceKind() {
        List<Proof> supported = supportedProofs();
        if (supported.isEmpty()) return FactoryPlan.SourceKind.NONE;
        FactoryPlan.SourceKind first = supported.getFirst().evidence().sourceKind();
        return supported.stream().allMatch(proof -> proof.evidence().sourceKind() == first)
                ? first : FactoryPlan.SourceKind.COMPOSITE;
    }

    public boolean hasStatisticalOutput() {
        return supportedProofs().stream().anyMatch(proof -> proof.evidence().hasStatisticalOutput());
    }

    public boolean hasNaturalWait() {
        return supportedProofs().stream().anyMatch(proof -> proof.evidence().hasNaturalWait());
    }

    public List<String> proofIds() {
        return supportedProofs().stream().filter(proof -> proof.evidence().isReady())
                .map(proof -> proof.adapterId() + "@" + proof.ruleVersion()).distinct().sorted().toList();
    }

    public Map<ItemVariant, Long> startupCapitalItems() {
        Map<ItemVariant, Long> result = new HashMap<>();
        for (Proof proof : supportedProofs()) {
            proof.evidence().startupCapitalItems().forEach((variant, count) -> {
                if (variant != null && count != null && count > 0) result.merge(variant, count, Math::addExact);
            });
        }
        return result;
    }

    public Map<ItemVariant, Long> toolDamageTotals() {
        Map<ItemVariant, Long> result = new HashMap<>();
        for (Proof proof : supportedProofs()) {
            proof.evidence().toolDamageTotals().forEach((variant, count) -> {
                if (variant != null && count != null && count > 0) result.merge(variant, count, Math::addExact);
            });
        }
        return result;
    }

    public long claimedAmount(ItemVariant variant, long observed, boolean hasMaterialInputs) {
        if (variant == null || observed <= 0 || !isReady()) return 0;
        ItemStack stack = variant.createStack(1);
        long claimed = 0;
        for (Proof proof : supportedProofs()) {
            claimed = saturatingAdd(claimed,
                    proof.evidence().claimedOutputAmount(stack, observed - Math.min(observed, claimed), hasMaterialInputs));
            if (claimed >= observed) return observed;
        }
        return Math.clamp(claimed, 0, observed);
    }

    public long claimedAmount(FluidVariant variant, long observed, boolean hasMaterialInputs) {
        if (variant == null || observed <= 0 || !isReady()) return 0;
        FluidStack stack = variant.prototype();
        long claimed = 0;
        for (Proof proof : supportedProofs()) {
            claimed = saturatingAdd(claimed,
                    proof.evidence().claimedOutputAmount(stack, observed - Math.min(observed, claimed), hasMaterialInputs));
            if (claimed >= observed) return observed;
        }
        return Math.clamp(claimed, 0, observed);
    }

    public boolean isPotentialRegenerativeOutput(ItemVariant variant) {
        if (variant == null) return false;
        ItemStack stack = variant.createStack(1);
        return supportedProofs().stream().anyMatch(proof -> proof.evidence().allowsOutput(stack));
    }

    public boolean isPotentialRegenerativeOutput(FluidVariant variant) {
        if (variant == null) return false;
        FluidStack stack = variant.prototype();
        return supportedProofs().stream().anyMatch(proof -> proof.evidence().allowsOutput(stack));
    }

    /**
     * True only when ready source evidence can pay for at least part of this observed output.
     * Room stock changes for that resource are backlog movement, not a reusable recipe input.
     */
    public boolean certifiesOutput(ItemVariant variant, long observed) {
        return claimedAmount(variant, observed, false) > 0L;
    }

    public boolean certifiesOutput(FluidVariant variant, long observed) {
        return claimedAmount(variant, observed, false) > 0L;
    }

    public boolean hasAmbiguousMixedOutput(ItemVariant output, Iterable<ItemVariant> inputs) {
        if (output == null || inputs == null) return false;
        ItemStack outputStack = output.createStack(1);
        for (Proof proof : supportedProofs()) {
            RegenerativeSourceAdapter.Evidence source = proof.evidence();
            if (!source.allowsOutput(outputStack) || source.canQuantifyMixedOutput(outputStack)) continue;
            for (ItemVariant input : inputs) {
                if (input != null && source.allowsOutput(input.createStack(1))) return true;
            }
        }
        return false;
    }

    public boolean hasAmbiguousMixedOutput(FluidVariant output, Iterable<FluidVariant> inputs) {
        if (output == null || inputs == null) return false;
        FluidStack outputStack = output.prototype();
        for (Proof proof : supportedProofs()) {
            RegenerativeSourceAdapter.Evidence source = proof.evidence();
            if (!source.allowsOutput(outputStack) || source.canQuantifyMixedOutput(outputStack)) continue;
            for (FluidVariant input : inputs) {
                if (input != null && source.allowsOutput(input.prototype())) return true;
            }
        }
        return false;
    }

    public String debugSummary() {
        return "supported=" + isSupported() + ", preliminary=" + hasPreliminaryEvidence()
                + ", ready=" + isReady() + ", kind=" + sourceKind()
                + ", window=" + currentWindow + ", proofs=" + proofs.stream()
                .map(proof -> proof.adapterId() + "={" + proof.evidence().debugSummary() + "}").toList();
    }

    private List<Proof> supportedProofs() {
        return proofs.stream().filter(proof -> proof.evidence().isSupported()).toList();
    }

    private static long saturatingAdd(long left, long right) {
        if (right <= 0) return left;
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
