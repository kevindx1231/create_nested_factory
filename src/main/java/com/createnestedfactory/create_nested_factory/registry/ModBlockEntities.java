package com.createnestedfactory.create_nested_factory.registry;

import com.createnestedfactory.create_nested_factory.Create_nested_factory;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedStressPortBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, Create_nested_factory.MODID);

    public static final Supplier<BlockEntityType<NestedFactoryBlockEntity>> NESTED_FACTORY =
            BLOCK_ENTITIES.register("nested_factory",
                    () -> BlockEntityType.Builder.of(NestedFactoryBlockEntity::new, ModBlocks.NESTED_FACTORY.get()).build(null));

    public static final Supplier<BlockEntityType<NestedPortBlockEntity>> NESTED_PORT =
            BLOCK_ENTITIES.register("nested_port",
                    () -> BlockEntityType.Builder.of(NestedPortBlockEntity::new, ModBlocks.NESTED_PORT.get()).build(null));

    public static final Supplier<BlockEntityType<NestedStressPortBlockEntity>> NESTED_STRESS_PORT =
            BLOCK_ENTITIES.register("nested_stress_port",
                    () -> BlockEntityType.Builder.of(NestedStressPortBlockEntity::new, ModBlocks.NESTED_STRESS_PORT.get()).build(null));
}
