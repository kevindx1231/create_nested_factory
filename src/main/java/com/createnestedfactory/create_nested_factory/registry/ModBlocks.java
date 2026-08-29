package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.NestedFactoryBlock;
import com.createnestedfactory.create_nested_factory.block.NestedPortBlock;
import com.createnestedfactory.create_nested_factory.block.NestedStressPortBlock;
import com.createnestedfactory.create_nested_factory.block.NestedWallBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Create_nested_factory.MODID);

    public static final DeferredBlock<NestedFactoryBlock> NESTED_FACTORY = BLOCKS.register("nested_factory",
            () -> new NestedFactoryBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(5.0F, 6.0F)
                    .noOcclusion()));

    public static final DeferredBlock<NestedPortBlock> NESTED_PORT = BLOCKS.register("nested_port",
            () -> new NestedPortBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0F, 3.0F)));

    public static final DeferredBlock<NestedStressPortBlock> NESTED_STRESS_PORT = BLOCKS.register("nested_stress_port",
            () -> new NestedStressPortBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.METAL)
                    .strength(2.0F, 3.0F)
                    .noOcclusion()));

    public static final DeferredBlock<NestedWallBlock> SNOW_WALL = BLOCKS.register("snow_wall",
            () -> new NestedWallBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SNOW)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()
                    .sound(SoundType.SNOW)));

    public static final DeferredBlock<NestedWallBlock> WHITE_CONCRETE_WALL = BLOCKS.register("white_concrete_wall",
            () -> new NestedWallBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.SNOW)
                    .strength(-1.0F, 3600000.0F)
                    .noLootTable()
                    .sound(SoundType.STONE)));
}
