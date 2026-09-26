package com.createnestedfactory.create_nested_factory_logistics;

import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedExtensionInterfaceBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.NestedPortBlockEntity;
import com.createnestedfactory.create_nested_factory.block.entity.FactoryTransit;
import com.createnestedfactory.create_nested_factory_logistics.chemical.ChemicalLedger;
import com.createnestedfactory.create_nested_factory_logistics.chemical.MekanismChemicalHandler;
import com.createnestedfactory.create_nested_factory_logistics.chemical.MekanismExtensionChemicalHandler;
import com.createnestedfactory.create_nested_factory_logistics.heat.MekanismHeatHandler;
import com.createnestedfactory.create_nested_factory_logistics.heat.MekanismExtensionHeatHandler;
import mekanism.api.chemical.IChemicalHandler;
import mekanism.api.heat.IHeatHandler;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import com.createnestedfactory.create_nested_factory.registry.ModBlockEntities;

public final class MekanismLogisticsCompat {
    public static final String MOD_ID = "create_nested_factory_logistics";
    public static final String CHEMICAL_NAMESPACE = "mekanism";
    public static final String CHEMICAL_PATH = "chemical_handler";
    public static final String HEAT_PATH = "heat_handler";

    /** The registry-canonical capabilities used by Mekanism and Pipez. */
    public static final BlockCapability<IChemicalHandler, Direction> CHEMICAL =
            BlockCapability.createSided(
                    ResourceLocation.fromNamespaceAndPath(CHEMICAL_NAMESPACE, CHEMICAL_PATH),
                    IChemicalHandler.class);
    public static final BlockCapability<IHeatHandler, Direction> HEAT =
            BlockCapability.createSided(
                    ResourceLocation.fromNamespaceAndPath(CHEMICAL_NAMESPACE, HEAT_PATH),
                    IHeatHandler.class);

    public static void initialize(IEventBus modEventBus) {
        FactoryTransit.registerParticipant(ChemicalLedger.PARTICIPANT_ID, ChemicalLedger::new);
        modEventBus.addListener(MekanismLogisticsCompat::registerCapabilities);
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(CHEMICAL, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory
                        ? MekanismChemicalHandler.forFactory(factory, side) : null);
        event.registerBlockEntity(CHEMICAL, ModBlockEntities.NESTED_PORT.get(),
                (be, side) -> be instanceof NestedPortBlockEntity port
                        ? MekanismChemicalHandler.forPort(port, side) : null);
        event.registerBlockEntity(CHEMICAL, ModBlockEntities.NESTED_EXTENSION_INTERFACE.get(),
                (be, side) -> be instanceof NestedExtensionInterfaceBlockEntity extension
                        ? MekanismExtensionChemicalHandler.create(extension, side) : null);
        event.registerBlockEntity(HEAT, ModBlockEntities.NESTED_FACTORY.get(),
                (be, side) -> be instanceof NestedFactoryBlockEntity factory
                        ? MekanismHeatHandler.forFactory(factory, side) : null);
        event.registerBlockEntity(HEAT, ModBlockEntities.NESTED_PORT.get(),
                (be, side) -> be instanceof NestedPortBlockEntity port
                        ? MekanismHeatHandler.forPort(port, side) : null);
        event.registerBlockEntity(HEAT, ModBlockEntities.NESTED_EXTENSION_INTERFACE.get(),
                (be, side) -> be instanceof NestedExtensionInterfaceBlockEntity extension
                        ? MekanismExtensionHeatHandler.create(extension, side) : null);
    }
}



