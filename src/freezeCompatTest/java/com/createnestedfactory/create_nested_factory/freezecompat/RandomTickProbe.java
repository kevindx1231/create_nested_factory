package com.createnestedfactory.create_nested_factory.freezecompat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** A whole section of inert probes makes random-position selection deterministic. Test mod only. */
final class RandomTickProbe {
    private static final DeferredRegister<Fluid> FLUIDS = DeferredRegister.create(ForgeRegistries.FLUIDS, "cnf_freeze_test");
    private static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, "cnf_freeze_test");
    static final RegistryObject<FlowingFluid> FLUID = FLUIDS.register("probe", ProbeFluid::new);
    static final RegistryObject<Block> BLOCK = BLOCKS.register("probe", () -> new LiquidBlock(FLUID,
            BlockBehaviour.Properties.of().noCollission().noLootTable().randomTicks()) {
        @Override public boolean isRandomlyTicking(BlockState state) { return true; }
        @Override public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
            blockTicks[pos.getY() >= 160 ? 1 : 0]++;
        }
    });
    static final int[] blockTicks = new int[2];
    static final int[] fluidTicks = new int[2];

    static void register(IEventBus bus) {
        FLUIDS.register(bus);
        BLOCKS.register(bus);
    }

    private static final class ProbeFluid extends WaterFluid.Source {
        @Override public net.minecraftforge.fluids.FluidType getFluidType() {
            return net.minecraftforge.common.ForgeMod.WATER_TYPE.get();
        }
        @Override public Fluid getFlowing() { return net.minecraft.world.level.material.Fluids.FLOWING_WATER; }
        @Override public Fluid getSource() { return this; }
        @Override public boolean isSame(Fluid fluid) { return fluid == this; }
        @Override protected boolean isRandomlyTicking() { return true; }
        @Override protected void randomTick(Level level, BlockPos pos, FluidState state, RandomSource random) {
            fluidTicks[pos.getY() >= 160 ? 1 : 0]++;
        }
        @Override public BlockState createLegacyBlock(FluidState state) { return BLOCK.get().defaultBlockState(); }
    }
}
