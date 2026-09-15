package com.createnestedfactory.create_nested_factory.block.entity;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.TreeSet;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** Owns simulated production transactions by stable route id. */
public final class FactoryRuntimeScheduler {
    public static final int FORMAT_VERSION = 5;
    public static final String PROCESSING_ROUTE_ID = "processing";
    public static final String REGENERATIVE_ROUTE_ID = "regenerative";

    public record ScheduledRoute(String routeId, FactoryPlan plan, FactoryRuntime runtime) {
    }

    private final Map<String, FactoryRuntime> runtimes = new LinkedHashMap<>();
    private final Map<ItemVariant, Long> startupCapital = new LinkedHashMap<>();

    public FactoryRuntimeScheduler() {
    }

    public List<ScheduledRoute> scheduledRoutes(FactoryPlan plan) {
        return plan.routes().stream().map(route -> new ScheduledRoute(route.routeId(), route.contract(),
                runtimes.computeIfAbsent(route.routeId(), ignored -> new FactoryRuntime()))).toList();
    }

    public boolean isIdle() {
        return runtimes.values().stream().allMatch(FactoryRuntime::isTransactionIdle);
    }

    public boolean isEmpty() {
        return startupCapital.isEmpty()
                && runtimes.values().stream().allMatch(runtime -> runtime.isEmpty() && !runtime.hasLeasedTools());
    }

    public List<FactoryRuntime> allRuntimes() {
        return List.copyOf(runtimes.values());
    }

    public String debugSummary() {
        List<String> values = new ArrayList<>();
        runtimes.forEach((routeId, runtime) -> values.add(routeId + "={" + runtime.debugSummary() + "}"));
        if (!startupCapital.isEmpty()) values.add("startupCapital=" + startupCapital);
        return values.isEmpty() ? "empty" : String.join(";", values);
    }

    /** Stable factory-wide input view shared by every simulated INPUT face. */
    public List<ItemVariant> sortedInputItems(FactoryPlan plan) {
        TreeSet<ItemVariant> variants = new TreeSet<>();
        for (ScheduledRoute route : scheduledRoutes(plan)) variants.addAll(route.plan().getRecipeInputs().keySet());
        return List.copyOf(variants);
    }

    public long committedItem(FactoryPlan plan, ItemVariant variant) {
        long total = 0L;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            total = Math.addExact(total, route.runtime().stagedItem(variant));
        }
        return total;
    }

    /** Routes one insertion into active/look-ahead ownership without a face cache or rate quota. */
    public int acceptItemInput(FactoryPlan plan, ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) return 0;
        int remaining = stack.getCount();
        int accepted = 0;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            if (remaining <= 0) break;
            ItemStack offered = stack.copyWithCount(remaining);
            int moved = route.runtime().acceptItemInput(route.plan(), offered, simulate);
            accepted = Math.addExact(accepted, moved);
            remaining -= moved;
        }
        return accepted;
    }

    /** Package insertion is all-or-nothing across the combined route demand. */
    public boolean acceptItemInputs(FactoryPlan plan, List<ItemStack> stacks, boolean simulate) {
        if (stacks == null || stacks.stream().allMatch(stack -> stack == null || stack.isEmpty())) return false;
        Map<ItemVariant, Long> requested = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) {
                requested.merge(ItemVariant.of(stack), (long) stack.getCount(), Math::addExact);
            }
        }
        for (Map.Entry<ItemVariant, Long> request : requested.entrySet()) {
            long available = 0L;
            for (ScheduledRoute route : scheduledRoutes(plan)) {
                ItemStack probe = request.getKey().createStack(1);
                if (route.runtime().acceptItemInput(route.plan(), probe, true) > 0) {
                    available = Math.addExact(available,
                            route.runtime().remainingItemInputCapacity(route.plan(), request.getKey()));
                }
            }
            if (available < request.getValue()) return false;
        }
        if (simulate) return true;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            int accepted = acceptItemInput(plan, stack, false);
            if (accepted != stack.getCount()) {
                throw new IllegalStateException("Simulated package input could not be committed atomically");
            }
        }
        return true;
    }

    public List<FluidVariant> sortedInputFluids(FactoryPlan plan) {
        TreeSet<FluidVariant> variants = new TreeSet<>();
        for (ScheduledRoute route : scheduledRoutes(plan)) variants.addAll(route.plan().getRecipeInputFluids().keySet());
        return List.copyOf(variants);
    }

    public long committedFluid(FactoryPlan plan, FluidVariant variant) {
        long total = 0L;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            total = Math.addExact(total, route.runtime().stagedFluid(variant));
        }
        return total;
    }

    public int acceptFluidInput(FactoryPlan plan, FluidStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) return 0;
        int remaining = stack.getAmount();
        int accepted = 0;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            if (remaining <= 0) break;
            FluidStack offered = stack.copyWithAmount(remaining);
            int moved = route.runtime().acceptFluidInput(route.plan(), offered, simulate);
            accepted = Math.addExact(accepted, moved);
            remaining -= moved;
        }
        return accepted;
    }

    public long remainingItemOutput(FactoryPlan plan, ItemVariant variant) {
        long total = 0L;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            total = Math.addExact(total, route.runtime().remainingItemOutput(variant));
        }
        return total;
    }

    public ItemStack extractItemOutput(FactoryPlan plan, ItemVariant variant, int amount, boolean simulate) {
        int remaining = Math.max(0, amount);
        ItemStack result = ItemStack.EMPTY;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            if (remaining <= 0) break;
            ItemStack extracted = route.runtime().extractItemOutput(route.plan(), variant, remaining, simulate);
            if (extracted.isEmpty()) continue;
            if (result.isEmpty()) result = extracted; else result.grow(extracted.getCount());
            remaining -= extracted.getCount();
        }
        return result;
    }

    public long remainingFluidOutput(FactoryPlan plan, FluidVariant variant) {
        long total = 0L;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            total = Math.addExact(total, route.runtime().remainingFluidOutput(variant));
        }
        return total;
    }

    public FluidStack extractFluidOutput(FactoryPlan plan, FluidVariant variant, int amount, boolean simulate) {
        int remaining = Math.max(0, amount);
        FluidStack result = FluidStack.EMPTY;
        for (ScheduledRoute route : scheduledRoutes(plan)) {
            if (remaining <= 0) break;
            FluidStack extracted = route.runtime().drainFluidOutput(route.plan(), variant, remaining, simulate);
            if (extracted.isEmpty()) continue;
            if (result.isEmpty()) result = extracted; else result.grow(extracted.getAmount());
            remaining -= extracted.getAmount();
        }
        return result;
    }

    public boolean validate(FactoryPlan plan) {
        if (startupCapital.entrySet().stream().anyMatch(entry -> entry.getValue() <= 0L
                || entry.getValue() > plan.getStartupCapitalItems().getOrDefault(entry.getKey(), 0L))) return false;
        var validIds = plan.routes().stream().map(FactoryRoute::routeId).collect(java.util.stream.Collectors.toSet());
        if (runtimes.entrySet().stream().anyMatch(entry -> !validIds.contains(entry.getKey())
                && (!entry.getValue().isEmpty() || entry.getValue().hasLeasedTools()))) return false;
        return scheduledRoutes(plan).stream().allMatch(route -> route.runtime().isValidFor(route.plan()));
    }

    public void rebindIdlePlans(FactoryPlan plan) {
        for (ScheduledRoute route : scheduledRoutes(plan)) route.runtime().rebindIdlePlan(route.plan());
    }

    public boolean isAtBatchBoundary() {
        return runtimes.values().stream().allMatch(FactoryRuntime::isAtBatchBoundary);
    }

    /** Atomically retimes every route while preserving resource ownership and completed work. */
    public boolean retime(FactoryPlan currentPlan, FactoryPlan replacementPlan) {
        Map<String, FactoryPlan> replacements = new LinkedHashMap<>();
        for (FactoryRoute route : replacementPlan.routes()) replacements.put(route.routeId(), route.contract());
        List<ScheduledRoute> currentRoutes = scheduledRoutes(currentPlan);
        if (currentRoutes.size() != replacements.size()) return false;
        for (ScheduledRoute current : currentRoutes) {
            FactoryPlan replacement = replacements.get(current.routeId());
            if (replacement == null
                    || !current.runtime().canRetime(current.plan(), replacement)) return false;
        }
        for (ScheduledRoute current : currentRoutes) {
            current.runtime().retime(current.plan(), replacements.get(current.routeId()));
        }
        return true;
    }

    public void clear() {
        runtimes.values().forEach(FactoryRuntime::clear);
        startupCapital.clear();
    }

    public List<ItemVariant> sortedCapitalRequirements(FactoryPlan plan) {
        return plan.getStartupCapitalItems().keySet().stream().sorted().toList();
    }

    public long capitalAmount(ItemVariant variant) {
        return startupCapital.getOrDefault(variant, 0L);
    }

    public long missingCapital(FactoryPlan plan, ItemVariant variant) {
        return Math.max(0L, plan.getStartupCapitalItems().getOrDefault(variant, 0L)
                - startupCapital.getOrDefault(variant, 0L));
    }

    public boolean capitalReady(FactoryPlan plan) {
        return plan.getStartupCapitalItems().entrySet().stream()
                .allMatch(entry -> startupCapital.getOrDefault(entry.getKey(), 0L) >= entry.getValue());
    }

    public int acceptCapital(FactoryPlan plan, ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) return 0;
        ItemVariant variant = ItemVariant.of(stack);
        int accepted = (int) Math.min(stack.getCount(), missingCapital(plan, variant));
        if (accepted > 0 && !simulate) startupCapital.merge(variant, (long) accepted, Math::addExact);
        return Math.max(0, accepted);
    }

    public List<ItemStack> releaseCapital() {
        List<ItemStack> result = new ArrayList<>();
        startupCapital.forEach((variant, amount) -> {
            int max = Math.max(1, variant.prototype().getMaxStackSize());
            for (long remaining = amount; remaining > 0; remaining -= Math.min(max, remaining)) {
                result.add(variant.createStack((int) Math.min(max, remaining)));
            }
        });
        startupCapital.clear();
        return result;
    }

    public CompoundTag write(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("SchedulerFormatVersion", FORMAT_VERSION);
        ListTag values = new ListTag();
        runtimes.forEach((routeId, runtime) -> {
            CompoundTag value = new CompoundTag();
            value.putString("RouteId", routeId);
            value.put("Runtime", runtime.write(new CompoundTag(), registries));
            values.add(value);
        });
        tag.put("RouteRuntimes", values);
        ListTag capital = new ListTag();
        startupCapital.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            CompoundTag value = new CompoundTag();
            value.put("Stack", entry.getKey().write(registries));
            value.putLong("Value", entry.getValue());
            capital.add(value);
        });
        tag.put("StartupCapital", capital);
        return tag;
    }

    public void read(CompoundTag tag, HolderLookup.Provider registries) {
        clear();
        if (tag.getInt("SchedulerFormatVersion") != FORMAT_VERSION) return;
        for (Tag raw : tag.getList("RouteRuntimes", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            String routeId = value.getString("RouteId");
            if (!routeId.isBlank()) runtimes.computeIfAbsent(routeId, ignored -> new FactoryRuntime())
                    .read(value.getCompound("Runtime"), registries);
        }
        for (Tag raw : tag.getList("StartupCapital", Tag.TAG_COMPOUND)) {
            CompoundTag value = (CompoundTag) raw;
            ItemVariant variant = ItemVariant.read(registries, value.getCompound("Stack"));
            long amount = value.getLong("Value");
            if (variant != null && amount > 0L) startupCapital.put(variant, amount);
        }
    }
}
