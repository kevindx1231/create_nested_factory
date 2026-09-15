package com.createnestedfactory.create_nested_factory.block.entity;

import com.createnestedfactory.create_nested_factory.Config;
import com.createnestedfactory.create_nested_factory.block.PocketBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.BitSet;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/** Global allow-list for built-in and third-party regenerative source proofs. */
public final class RegenerativeSourceAdapters {
    static final String COBBLESTONE_PROOF = "create_nested_factory:cobblestone@8";
    static final String BASALT_PROOF = "create_nested_factory:basalt@8";
    static final String TREE_PROOF = "create_nested_factory:vanilla_tree@6";
    static final String CROP_PROOF = "create_nested_factory:crop@1";
    private static final Map<String, RegenerativeSourceAdapter> ADAPTERS = new ConcurrentHashMap<>();

    static {
        register(new GeneratorAdapter("create_nested_factory:cobblestone", FactoryPlan.SourceKind.COBBLESTONE,
                Blocks.COBBLESTONE, Items.COBBLESTONE,
                () -> configured(Config.cobblestoneSourceVerificationCycles), false));
        register(new GeneratorAdapter("create_nested_factory:basalt", FactoryPlan.SourceKind.BASALT,
                Blocks.BASALT, Items.BASALT,
                () -> configured(Config.basaltSourceVerificationCycles), true));
        register(new TreeAdapter());
        register(new CropAdapter());
    }

    private RegenerativeSourceAdapters() {
    }

    public static void register(RegenerativeSourceAdapter adapter) {
        if (adapter == null || adapter.id() == null || adapter.id().isBlank()) {
            throw new IllegalArgumentException("A regenerative source adapter needs a stable id");
        }
        RegenerativeSourceAdapter existing = ADAPTERS.putIfAbsent(adapter.id(), adapter);
        if (existing != null && existing != adapter) {
            throw new IllegalStateException("Duplicate regenerative source adapter: " + adapter.id());
        }
    }

    public static Collection<RegenerativeSourceAdapter> all() {
        return List.copyOf(ADAPTERS.values());
    }

    public static boolean isCurrentProof(String proof) {
        if (COBBLESTONE_PROOF.equals(proof) || BASALT_PROOF.equals(proof)
                || TREE_PROOF.equals(proof) || CROP_PROOF.equals(proof)) return true;
        if (proof == null) return false;
        int separator = proof.lastIndexOf('@');
        if (separator <= 0 || separator == proof.length() - 1) return false;
        RegenerativeSourceAdapter adapter = ADAPTERS.get(proof.substring(0, separator));
        if (adapter == null) return false;
        try {
            return Integer.parseInt(proof.substring(separator + 1)) == adapter.ruleVersion();
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static int configured(int value) {
        return Math.clamp(value <= 0 ? 3 : value, 3, 5);
    }

    private static final class GeneratorAdapter implements RegenerativeSourceAdapter {
        private final String id;
        private final FactoryPlan.SourceKind kind;
        private final Block generatedBlock;
        private final Item outputItem;
        private final IntSupplier requiredCycles;
        private final boolean basalt;

        private GeneratorAdapter(String id, FactoryPlan.SourceKind kind, Block generatedBlock, Item outputItem,
                                 IntSupplier requiredCycles, boolean basalt) {
            this.id = id;
            this.kind = kind;
            this.generatedBlock = generatedBlock;
            this.outputItem = outputItem;
            this.requiredCycles = requiredCycles;
            this.basalt = basalt;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public int ruleVersion() {
            return 8;
        }

        @Override
        public Evidence begin(ServerLevel level, PocketBounds bounds, BlockPos origin) {
            return new GeneratorEvidence(kind, generatedBlock, outputItem, requiredCycles.getAsInt(), basalt);
        }
    }

    private static final class GeneratorEvidence implements RegenerativeSourceAdapter.Evidence {
        private final FactoryPlan.SourceKind kind;
        private final Block generatedBlock;
        private final Item outputItem;
        private final int required;
        private final boolean basalt;
        private final Set<Long> candidates = new HashSet<>();
        private final BitSet eventWindows = new BitSet(3);
        private final BitSet outputWindows = new BitSet(3);
        private int events;
        private int stateGenerationCredits;
        private int optimizedDrillCredits;
        private int ordinaryDrillCycles;
        private int contraptionDrillCycles;
        private long boundaryOutputs;

        private GeneratorEvidence(FactoryPlan.SourceKind kind, Block generatedBlock, Item outputItem,
                                  int required, boolean basalt) {
            this.kind = kind;
            this.generatedBlock = generatedBlock;
            this.outputItem = outputItem;
            this.required = required;
            this.basalt = basalt;
        }

        /**
         * Fluid mixing replaces one of the participating fluids. Include the replaced state so
         * a fast generator remains provable even when no lava or water remains at that position
         * after LevelChunk#setBlockState returns.
         */
        private boolean validGenerationTopology(ServerLevel level, BlockPos pos, BlockState replacedState) {
            if (!basalt) {
                boolean water = replacedState.getFluidState().is(FluidTags.WATER);
                boolean lava = replacedState.getFluidState().is(FluidTags.LAVA);
                for (Direction direction : Direction.values()) {
                    water |= level.getFluidState(pos.relative(direction)).is(FluidTags.WATER);
                    lava |= level.getFluidState(pos.relative(direction)).is(FluidTags.LAVA);
                }
                return water && lava;
            }
            boolean blueIce = false;
            boolean soulSoil = false;
            boolean lava = replacedState.getFluidState().is(FluidTags.LAVA);
            for (BlockPos candidate : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 2, 2))) {
                BlockState state = level.getBlockState(candidate);
                blueIce |= state.is(Blocks.BLUE_ICE);
                soulSoil |= state.is(Blocks.SOUL_SOIL);
                lava |= level.getFluidState(candidate).is(FluidTags.LAVA);
            }
            return blueIce && soulSoil && lava;
        }

        @Override
        public FactoryPlan.SourceKind sourceKind() {
            return kind;
        }

        @Override
        public boolean isSupported() {
            return !candidates.isEmpty() && events > 0;
        }

        @Override
        public boolean onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState,
                                      BlockState newState, int windowIndex) {
            long key = pos.asLong();
            boolean generatedNow = newState.is(generatedBlock);
            boolean generatedBefore = oldState.is(generatedBlock);
            boolean validGeneration = !generatedBefore && generatedNow
                    && validGenerationTopology(level, pos, oldState);
            if (validGeneration) {
                candidates.add(key);
                recordGenerationCredit(windowIndex, false);
            }
            return (candidates.contains(key) || validGeneration)
                    && dynamicState(oldState) && dynamicState(newState);
        }

        @Override
        public boolean onDrillProduction(ServerLevel level, DrillProductionEvent event, int windowIndex) {
            if (event == null || !event.brokenState().is(generatedBlock)) return false;
            if (event.path() == DrillProductionEvent.Path.CONTRAPTION) {
                contraptionDrillCycles = Math.addExact(contraptionDrillCycles, 1);
            } else if (!event.provesRegeneration()) {
                ordinaryDrillCycles = Math.addExact(ordinaryDrillCycles, 1);
            }
            if (!event.provesRegeneration()) return true;

            // Create reached this path only after CobbleGenOptimisation validated the registered
            // fluid interaction and confirmed that it reproduces the block under the drill.
            candidates.add(event.targetPos().asLong());
            recordGenerationCredit(windowIndex, true);
            return true;
        }

        private void recordGenerationCredit(int windowIndex, boolean optimizedDrill) {
            events = Math.addExact(events, 1);
            if (optimizedDrill) {
                optimizedDrillCredits = Math.addExact(optimizedDrillCredits, 1);
            } else {
                stateGenerationCredits = Math.addExact(stateGenerationCredits, 1);
            }
            if (windowIndex >= 0) eventWindows.set(windowIndex);
        }

        private boolean dynamicState(BlockState state) {
            return state.isAir() || state.is(generatedBlock) || state.is(Blocks.WATER) || state.is(Blocks.LAVA);
        }

        @Override
        public void recordBoundaryItemOutput(ItemStack stack, long moved, long gameTime, int windowIndex) {
            if (stack == null || stack.isEmpty() || moved <= 0) return;
            if (!stack.is(outputItem)) return;
            boundaryOutputs = saturatingAdd(boundaryOutputs, moved);
            if (windowIndex >= 0) outputWindows.set(windowIndex);
        }

        @Override
        public void recordBoundaryFluidOutput(FluidStack stack, long moved, long gameTime, int windowIndex) {
        }

        private long certifiedOutputs() {
            return Math.min(Math.max(0L, events), boundaryOutputs);
        }

        @Override
        public boolean isReady() {
            return events >= required && certifiedOutputs() > 0L;
        }

        @Override
        public int verifiedCycles() {
            return events;
        }

        @Override
        public int requiredCycles() {
            return required;
        }

        @Override
        public int evidenceWindowCount() {
            return Math.max(eventWindows.cardinality(), outputWindows.cardinality());
        }

        @Override
        public boolean allowsOutput(ItemStack stack) {
            return stack != null && stack.is(outputItem);
        }

        @Override
        public boolean canQuantifyMixedOutput(ItemStack stack) {
            return allowsOutput(stack) && events > 0;
        }

        @Override
        public long claimedOutputAmount(ItemStack stack, long observed, boolean hasMaterialInputs) {
            if (!allowsOutput(stack) || observed <= 0) return 0;
            return Math.min(observed, certifiedOutputs());
        }

        @Override
        public String debugSummary() {
            return RegenerativeSourceAdapter.Evidence.super.debugSummary()
                    + ", candidates=" + candidates.size() + ", outputs="
                    + boundaryOutputs + ", generatedCredits=" + events
                    + ", certifiedOutputs=" + certifiedOutputs()
                    + ", stateCredits=" + stateGenerationCredits
                    + ", optimizedDrillCredits=" + optimizedDrillCredits
                    + ", ordinaryDrillCycles=" + ordinaryDrillCycles
                    + ", contraptionDrillCycles=" + contraptionDrillCycles
                    + ", outputWindows=" + outputWindows.cardinality();
        }

        private static long saturatingAdd(long left, long right) {
            return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }

    private static final class TreeAdapter implements RegenerativeSourceAdapter {
        @Override
        public String id() {
            return "create_nested_factory:vanilla_tree";
        }

        @Override
        public int ruleVersion() {
            return 6;
        }

        @Override
        public Evidence begin(ServerLevel level, PocketBounds bounds, BlockPos origin) {
            return new TreeEvidence(level, bounds, origin, configured(Config.treeSourceVerificationCycles));
        }
    }

    private static final class TreeEvidence implements RegenerativeSourceAdapter.Evidence {
        private enum Phase { ARMED, GROWING, GROWN }

        private static final class TreeGroup {
            private final Block sapling;
            private final BitSet eventWindows = new BitSet();
            private int events;
            private long lastEventTick = Long.MIN_VALUE;
            private int outputConfirmedEvents;

            private TreeGroup(Block sapling) {
                this.sapling = sapling;
            }

            private boolean matchesPrimary(ItemStack stack) {
                if (stack == null || stack.isEmpty() || !stack.is(ItemTags.LOGS)) return false;
                String saplingPath = BuiltInRegistries.BLOCK.getKey(sapling).getPath();
                String species = saplingPath.endsWith("_sapling")
                        ? saplingPath.substring(0, saplingPath.length() - "_sapling".length()) : saplingPath;
                return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().startsWith(species + "_");
            }
        }

        private final Map<Long, Phase> phases = new HashMap<>();
        private final Map<Long, Block> saplingTypes = new HashMap<>();
        private final Map<Block, TreeGroup> groups = new HashMap<>();
        private final Map<ItemVariant, Long> certifiedOutputs = new HashMap<>();
        private final Map<ItemVariant, Long> startupSaplings = new HashMap<>();
        private final int required;
        private long lastCertifiedOutputTick = Long.MIN_VALUE;

        private TreeEvidence(ServerLevel level, PocketBounds bounds, BlockPos origin, int required) {
            this.required = required;
            if (level == null) return;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = bounds.minX(origin) + 1; x < bounds.maxX(origin); x++) {
                for (int y = bounds.minY(origin) + 1; y < bounds.maxY(origin); y++) {
                    for (int z = bounds.minZ(origin) + 1; z < bounds.maxZ(origin); z++) {
                        pos.set(x, y, z);
                        BlockState state = level.getBlockState(pos);
                        if (isVanillaSapling(state)) addSapling(pos, state, true);
                    }
                }
            }
        }

        private void addSapling(BlockPos pos, BlockState state, boolean startup) {
            long key = pos.asLong();
            Block sapling = state.getBlock();
            phases.put(key, Phase.ARMED);
            saplingTypes.put(key, sapling);
            groups.computeIfAbsent(sapling, TreeGroup::new);
            if (startup) {
                ItemStack stack = new ItemStack(sapling.asItem());
                if (!stack.isEmpty()) startupSaplings.merge(ItemVariant.of(stack), 1L, Math::addExact);
            }
        }

        @Override
        public FactoryPlan.SourceKind sourceKind() {
            return FactoryPlan.SourceKind.TREE;
        }

        @Override
        public boolean isSupported() {
            return !groups.isEmpty();
        }

        @Override
        public boolean onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState,
                                      BlockState newState, int windowIndex) {
            long key = pos.asLong();
            if (isVanillaSapling(newState)) {
                addSapling(pos, newState, false);
                return true;
            }
            Block sapling = isVanillaSapling(oldState) ? oldState.getBlock() : saplingTypes.get(key);
            Phase phase = phases.get(key);
            if (phase == Phase.ARMED && isVanillaSapling(oldState) && !isVanillaSapling(newState)) {
                phases.put(key, newState.is(BlockTags.LOGS) ? Phase.GROWN : Phase.GROWING);
                if (newState.is(BlockTags.LOGS)) recordGrowth(sapling, level.getGameTime(), windowIndex);
                return true;
            }
            if ((phase == Phase.GROWING || phase == Phase.ARMED) && newState.is(BlockTags.LOGS)) {
                phases.put(key, Phase.GROWN);
                recordGrowth(sapling, level.getGameTime(), windowIndex);
                return true;
            }
            return isTreeDynamic(oldState) || isTreeDynamic(newState);
        }

        private void recordGrowth(Block sapling, long gameTime, int windowIndex) {
            if (sapling == null) return;
            TreeGroup group = groups.computeIfAbsent(sapling, TreeGroup::new);
            // One 2x2 tree replaces four saplings in the same tick; that is one source cycle.
            if (group.lastEventTick == gameTime) return;
            group.lastEventTick = gameTime;
            group.events = Math.addExact(group.events, 1);
            if (windowIndex >= 0) group.eventWindows.set(windowIndex);
        }

        @Override
        public void recordBoundaryItemOutput(ItemStack stack, long moved, long gameTime, int windowIndex) {
            if (moved <= 0 || !isTreeOutput(stack)) return;
            boolean followsEvent = false;
            for (TreeGroup group : groups.values()) {
                if (group.events <= 0) continue;
                followsEvent = true;
                if (group.matchesPrimary(stack)) group.outputConfirmedEvents = group.events;
            }
            if (followsEvent) {
                certifiedOutputs.merge(ItemVariant.of(stack), moved, TreeEvidence::saturatingAdd);
                lastCertifiedOutputTick = gameTime;
            }
        }

        @Override
        public boolean hasPreliminaryEvidence() {
            return groups.values().stream().anyMatch(group -> group.events > 0 || group.outputConfirmedEvents > 0);
        }

        @Override
        public boolean isReady() {
            return !groups.isEmpty() && groups.values().stream()
                    .allMatch(group -> group.events >= required && group.outputConfirmedEvents >= required);
        }

        @Override
        public boolean isReadyToFinalize(long gameTime) {
            return isReady() && lastCertifiedOutputTick != Long.MIN_VALUE
                    && gameTime - lastCertifiedOutputTick >= 40L;
        }

        @Override
        public int verifiedCycles() {
            return groups.values().stream().mapToInt(group -> group.events).min().orElse(0);
        }

        @Override
        public int requiredCycles() {
            return required;
        }

        @Override
        public int evidenceWindowCount() {
            return groups.values().stream().mapToInt(group -> group.eventWindows.cardinality()).min().orElse(0);
        }

        @Override
        public boolean allowsOutput(ItemStack stack) {
            return isTreeOutput(stack);
        }

        @Override
        public boolean canQuantifyMixedOutput(ItemStack stack) {
            return certifiedOutputs.containsKey(ItemVariant.of(stack));
        }

        @Override
        public long claimedOutputAmount(ItemStack stack, long observed, boolean hasMaterialInputs) {
            if (!allowsOutput(stack) || observed <= 0) return 0;
            return Math.min(observed, certifiedOutputs.getOrDefault(ItemVariant.of(stack), 0L));
        }

        @Override
        public Map<ItemVariant, Long> startupCapitalItems() {
            return Map.copyOf(startupSaplings);
        }

        @Override
        public boolean hasNaturalWait() {
            return true;
        }

        @Override
        public boolean hasStatisticalOutput() {
            return true;
        }

        @Override
        public String debugSummary() {
            List<String> status = groups.values().stream()
                    .map(group -> BuiltInRegistries.BLOCK.getKey(group.sapling) + "=" + group.events + "/"
                            + required + ",confirmed=" + group.outputConfirmedEvents)
                    .sorted().toList();
            return RegenerativeSourceAdapter.Evidence.super.debugSummary() + ", groups=" + status
                    + ", treeCells=" + phases.size() + ", certifiedOutputs=" + certifiedOutputs;
        }

        private static boolean isTreeDynamic(BlockState state) {
            return state.isAir() || isVanillaSapling(state) || state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
        }

        private static boolean isTreeOutput(ItemStack stack) {
            return stack != null && !stack.isEmpty() && (stack.is(ItemTags.LOGS) || stack.is(ItemTags.SAPLINGS)
                    || stack.is(ItemTags.LEAVES) || stack.is(Items.STICK) || stack.is(Items.APPLE));
        }

        private static boolean isVanillaSapling(BlockState state) {
            return state.getBlock() instanceof SaplingBlock
                    && "minecraft".equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()).getNamespace());
        }

        private static long saturatingAdd(long left, long right) {
            return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }

    private static final class CropAdapter implements RegenerativeSourceAdapter {
        @Override
        public String id() {
            return "create_nested_factory:crop";
        }

        @Override
        public Evidence begin(ServerLevel level, PocketBounds bounds, BlockPos origin) {
            return new CropEvidence(level, bounds, origin, configured(Config.cropSourceVerificationCycles));
        }
    }

    /** Per-crop-type event evidence for mixed farms sharing the same room and output ports. */
    private static final class CropEvidence implements RegenerativeSourceAdapter.Evidence {
        private static final class CropGroup {
            private final Block crop;
            private final Set<Item> outputs;
            private final String namespace;
            private final String speciesPath;
            private final BitSet eventWindows = new BitSet();
            private int events;
            private int outputConfirmedEvents;

            private CropGroup(Block crop) {
                this.crop = crop;
                this.outputs = cropOutputs(crop);
                var id = BuiltInRegistries.BLOCK.getKey(crop);
                this.namespace = id.getNamespace();
                this.speciesPath = normalizedCropPath(id.getPath());
            }

            private boolean matches(ItemStack stack) {
                if (stack == null || stack.isEmpty()) return false;
                if (outputs.contains(stack.getItem())) return true;
                var itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                String itemPath = itemId.getPath();
                return namespace.equals(itemId.getNamespace())
                        && (itemPath.equals(speciesPath) || itemPath.startsWith(speciesPath + "_"));
            }
        }

        private final Map<Long, Block> cells = new HashMap<>();
        private final Map<Block, CropGroup> groups = new HashMap<>();
        private final Map<ItemVariant, Long> certifiedOutputs = new HashMap<>();
        private final Map<ItemVariant, Long> startupItems = new HashMap<>();
        private final int required;
        private long lastCertifiedOutputTick = Long.MIN_VALUE;

        private CropEvidence(ServerLevel level, PocketBounds bounds, BlockPos origin, int required) {
            this.required = required;
            if (level == null) return;
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int x = bounds.minX(origin) + 1; x < bounds.maxX(origin); x++) {
                for (int y = bounds.minY(origin) + 1; y < bounds.maxY(origin); y++) {
                    for (int z = bounds.minZ(origin) + 1; z < bounds.maxZ(origin); z++) {
                        pos.set(x, y, z);
                        Block crop = cropType(level.getBlockState(pos));
                        if (crop != null) {
                            boolean root = cropType(level.getBlockState(pos.below())) != crop;
                            addCell(pos, crop, root);
                        }
                    }
                }
            }
        }

        private void addCell(BlockPos pos, Block crop, boolean startup) {
            long key = pos.asLong();
            Block previous = cells.put(key, crop);
            groups.computeIfAbsent(crop, CropGroup::new);
            if (startup && previous == null) {
                ItemStack capital = new ItemStack(crop.asItem());
                if (!capital.isEmpty()) startupItems.merge(ItemVariant.of(capital), 1L, Math::addExact);
            }
        }

        @Override
        public FactoryPlan.SourceKind sourceKind() {
            return FactoryPlan.SourceKind.CROP;
        }

        @Override
        public boolean isSupported() {
            return !groups.isEmpty();
        }

        @Override
        public boolean onBlockChanged(ServerLevel level, BlockPos pos, BlockState oldState,
                                      BlockState newState, int windowIndex) {
            Block oldCrop = cropType(oldState);
            Block newCrop = cropType(newState);
            if (newCrop != null) addCell(pos, newCrop, false);

            Block fruitStem = stemCropForFruit(level, pos, oldState, newState);
            if (fruitStem != null) {
                recordEvent(fruitStem, windowIndex);
                return true;
            }

            if (oldCrop != null && oldCrop == newCrop && isMature(oldState) && !isMature(newState)) {
                recordEvent(oldCrop, windowIndex);
                return true;
            }
            if (oldCrop != null && oldCrop != newCrop && isMature(oldState)) {
                recordEvent(oldCrop, windowIndex);
                return true;
            }
            if (oldCrop == null && newCrop != null && isVerticalCrop(newCrop)) {
                Block below = cropType(level.getBlockState(pos.below()));
                if (below == newCrop) recordEvent(newCrop, windowIndex);
                return true;
            }
            return oldCrop != null || newCrop != null;
        }

        /** Melons and pumpkins complete a growth cycle by placing an adjacent fruit block. */
        private Block stemCropForFruit(ServerLevel level, BlockPos fruitPos,
                                       BlockState oldState, BlockState newState) {
            Block fruit = newState.getBlock();
            if ((!oldState.isAir() && oldState.getBlock() == fruit)
                    || fruit != Blocks.MELON && fruit != Blocks.PUMPKIN) {
                return null;
            }
            Block expectedStem = fruit == Blocks.MELON ? Blocks.MELON_STEM : Blocks.PUMPKIN_STEM;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                Block neighbor = cropType(level.getBlockState(fruitPos.relative(direction)));
                if (neighbor == expectedStem && groups.containsKey(expectedStem)) return expectedStem;
            }
            return null;
        }

        private void recordEvent(Block crop, int windowIndex) {
            CropGroup group = groups.computeIfAbsent(crop, CropGroup::new);
            group.events = Math.addExact(group.events, 1);
            if (windowIndex >= 0) group.eventWindows.set(windowIndex);
        }

        @Override
        public void recordBoundaryItemOutput(ItemStack stack, long moved, long gameTime, int windowIndex) {
            if (stack == null || stack.isEmpty() || moved <= 0) return;
            boolean certified = false;
            for (CropGroup group : groups.values()) {
                if (group.events > 0 && group.matches(stack)) {
                    group.outputConfirmedEvents = group.events;
                    certified = true;
                }
            }
            if (certified) {
                certifiedOutputs.merge(ItemVariant.of(stack), moved, CropEvidence::saturatingAdd);
                lastCertifiedOutputTick = gameTime;
            }
        }

        @Override
        public boolean hasPreliminaryEvidence() {
            return groups.values().stream().anyMatch(group -> group.events > 0 || group.outputConfirmedEvents > 0);
        }

        @Override
        public boolean isReady() {
            return !groups.isEmpty() && groups.values().stream()
                    .allMatch(group -> group.events >= required && group.outputConfirmedEvents >= required);
        }

        @Override
        public boolean isReadyToFinalize(long gameTime) {
            return isReady() && lastCertifiedOutputTick != Long.MIN_VALUE
                    && gameTime - lastCertifiedOutputTick >= 40L;
        }

        @Override
        public int verifiedCycles() {
            return groups.values().stream().mapToInt(group -> group.events).min().orElse(0);
        }

        @Override
        public int requiredCycles() {
            return required;
        }

        @Override
        public int evidenceWindowCount() {
            return groups.values().stream().mapToInt(group -> group.eventWindows.cardinality()).min().orElse(0);
        }

        @Override
        public boolean allowsOutput(ItemStack stack) {
            return stack != null && groups.values().stream().anyMatch(group -> group.matches(stack));
        }

        @Override
        public boolean canQuantifyMixedOutput(ItemStack stack) {
            return stack != null && certifiedOutputs.containsKey(ItemVariant.of(stack));
        }

        @Override
        public long claimedOutputAmount(ItemStack stack, long observed, boolean hasMaterialInputs) {
            if (!allowsOutput(stack) || observed <= 0) return 0;
            return Math.min(observed, certifiedOutputs.getOrDefault(ItemVariant.of(stack), 0L));
        }

        @Override
        public Map<ItemVariant, Long> startupCapitalItems() {
            return Map.copyOf(startupItems);
        }

        @Override
        public boolean hasNaturalWait() {
            return true;
        }

        @Override
        public boolean hasStatisticalOutput() {
            return true;
        }

        @Override
        public String debugSummary() {
            List<String> status = groups.values().stream()
                    .map(group -> BuiltInRegistries.BLOCK.getKey(group.crop) + "=" + group.events + "/"
                            + required + ",confirmed=" + group.outputConfirmedEvents)
                    .sorted().toList();
            return RegenerativeSourceAdapter.Evidence.super.debugSummary() + ", groups=" + status
                    + ", cropCells=" + cells.size() + ", certifiedOutputs=" + certifiedOutputs;
        }

        private static Block cropType(BlockState state) {
            Block block = state.getBlock();
            if (block == Blocks.ATTACHED_MELON_STEM) return Blocks.MELON_STEM;
            if (block == Blocks.ATTACHED_PUMPKIN_STEM) return Blocks.PUMPKIN_STEM;
            if (state.is(BlockTags.CROPS) || block == Blocks.NETHER_WART || block == Blocks.COCOA
                    || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.SUGAR_CANE
                    || block == Blocks.CACTUS || block == Blocks.BAMBOO || block == Blocks.BAMBOO_SAPLING) {
                return block == Blocks.BAMBOO_SAPLING ? Blocks.BAMBOO : block;
            }
            return null;
        }

        private static boolean isVerticalCrop(Block crop) {
            return crop == Blocks.SUGAR_CANE || crop == Blocks.CACTUS || crop == Blocks.BAMBOO;
        }

        private static boolean isMature(BlockState state) {
            for (Property<?> property : state.getProperties()) {
                if (property instanceof IntegerProperty age && "age".equals(age.getName())) {
                    int value = state.getValue(age);
                    int maximum = age.getPossibleValues().stream().mapToInt(Integer::intValue).max().orElse(value);
                    return value >= maximum;
                }
            }
            return false;
        }

        private static Set<Item> cropOutputs(Block crop) {
            Set<Item> result = new HashSet<>();
            if (crop == Blocks.WHEAT) {
                result.add(Items.WHEAT);
                result.add(Items.WHEAT_SEEDS);
            } else if (crop == Blocks.CARROTS) {
                result.add(Items.CARROT);
            } else if (crop == Blocks.POTATOES) {
                result.add(Items.POTATO);
                result.add(Items.POISONOUS_POTATO);
            } else if (crop == Blocks.BEETROOTS) {
                result.add(Items.BEETROOT);
                result.add(Items.BEETROOT_SEEDS);
            } else if (crop == Blocks.NETHER_WART) {
                result.add(Items.NETHER_WART);
            } else if (crop == Blocks.COCOA) {
                result.add(Items.COCOA_BEANS);
            } else if (crop == Blocks.SWEET_BERRY_BUSH) {
                result.add(Items.SWEET_BERRIES);
            } else if (crop == Blocks.SUGAR_CANE) {
                result.add(Items.SUGAR_CANE);
            } else if (crop == Blocks.CACTUS) {
                result.add(Items.CACTUS);
            } else if (crop == Blocks.BAMBOO) {
                result.add(Items.BAMBOO);
            } else if (crop == Blocks.MELON_STEM) {
                result.add(Items.MELON);
                result.add(Items.MELON_SLICE);
                result.add(Items.MELON_SEEDS);
            } else if (crop == Blocks.PUMPKIN_STEM) {
                result.add(Items.PUMPKIN);
                result.add(Items.PUMPKIN_SEEDS);
            } else if (crop == Blocks.TORCHFLOWER_CROP) {
                result.add(Items.TORCHFLOWER);
                result.add(Items.TORCHFLOWER_SEEDS);
            } else if (crop == Blocks.PITCHER_CROP) {
                result.add(Items.PITCHER_PLANT);
                result.add(Items.PITCHER_POD);
            } else {
                Item item = crop.asItem();
                if (item != Items.AIR) result.add(item);
            }
            return Set.copyOf(result);
        }

        private static String normalizedCropPath(String path) {
            if (path == null || path.isEmpty()) return "";
            for (String suffix : List.of("_crop", "_crops", "_plant", "_bush")) {
                if (path.endsWith(suffix) && path.length() > suffix.length()) {
                    return path.substring(0, path.length() - suffix.length());
                }
            }
            return path;
        }

        private static long saturatingAdd(long left, long right) {
            return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
        }
    }
}
