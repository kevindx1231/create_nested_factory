package com.createnestedfactory.create_nested_factory_logistics;
import com.createnestedfactory.create_nested_factory.compat.LegacyCapabilities;
import com.createnestedfactory.create_nested_factory.block.entity.*;
import com.createnestedfactory.create_nested_factory_logistics.chemical.*;
import com.createnestedfactory.create_nested_factory_logistics.heat.*;
import mekanism.api.heat.IHeatHandler;
import net.minecraftforge.common.capabilities.*;
import net.minecraftforge.eventbus.api.IEventBus;
import mekanism.api.chemical.gas.IGasHandler;
import mekanism.api.chemical.infuse.IInfusionHandler;
import mekanism.api.chemical.pigment.IPigmentHandler;
import mekanism.api.chemical.slurry.ISlurryHandler;

/** Registers all four Mekanism 10.4 chemical capabilities separately, plus heat. */
public final class MekanismLogisticsCompat {
    public static final String MOD_ID = "create_nested_factory_logistics";
    public static final Capability<IGasHandler> GAS = CapabilityManager.get(new CapabilityToken<>() {});
    public static final Capability<IInfusionHandler> INFUSION = CapabilityManager.get(new CapabilityToken<>() {});
    public static final Capability<IPigmentHandler> PIGMENT = CapabilityManager.get(new CapabilityToken<>() {});
    public static final Capability<ISlurryHandler> SLURRY = CapabilityManager.get(new CapabilityToken<>() {});
    public static final Capability<IHeatHandler> HEAT = CapabilityManager.get(new CapabilityToken<>() {});
    private MekanismLogisticsCompat() {}
    public static void initialize(IEventBus modEventBus) {
        FactoryTransit.registerParticipant(GasLedger.PARTICIPANT_ID, GasLedger::new);
        LegacyCapabilities.register(GAS, (be, side) -> {
            if (be instanceof NestedFactoryBlockEntity factory) return MekanismGasHandler.forFactory(factory, side);
            if (be instanceof NestedPortBlockEntity port) return MekanismGasHandler.forPort(port, side);
            if (be instanceof NestedExtensionInterfaceBlockEntity extension) return MekanismExtensionGasHandler.create(extension, side);
            return null;
        }, endpoint -> endpoint instanceof LiveMekanismEndpoint live && live.isAvailable());
        FactoryTransit.registerParticipant(InfusionLedger.PARTICIPANT_ID, InfusionLedger::new);
        LegacyCapabilities.register(INFUSION, (be, side) -> {
            if (be instanceof NestedFactoryBlockEntity factory) return MekanismInfusionHandler.forFactory(factory, side);
            if (be instanceof NestedPortBlockEntity port) return MekanismInfusionHandler.forPort(port, side);
            if (be instanceof NestedExtensionInterfaceBlockEntity extension) return MekanismExtensionInfusionHandler.create(extension, side);
            return null;
        }, endpoint -> endpoint instanceof LiveMekanismEndpoint live && live.isAvailable());
        FactoryTransit.registerParticipant(PigmentLedger.PARTICIPANT_ID, PigmentLedger::new);
        LegacyCapabilities.register(PIGMENT, (be, side) -> {
            if (be instanceof NestedFactoryBlockEntity factory) return MekanismPigmentHandler.forFactory(factory, side);
            if (be instanceof NestedPortBlockEntity port) return MekanismPigmentHandler.forPort(port, side);
            if (be instanceof NestedExtensionInterfaceBlockEntity extension) return MekanismExtensionPigmentHandler.create(extension, side);
            return null;
        }, endpoint -> endpoint instanceof LiveMekanismEndpoint live && live.isAvailable());
        FactoryTransit.registerParticipant(SlurryLedger.PARTICIPANT_ID, SlurryLedger::new);
        LegacyCapabilities.register(SLURRY, (be, side) -> {
            if (be instanceof NestedFactoryBlockEntity factory) return MekanismSlurryHandler.forFactory(factory, side);
            if (be instanceof NestedPortBlockEntity port) return MekanismSlurryHandler.forPort(port, side);
            if (be instanceof NestedExtensionInterfaceBlockEntity extension) return MekanismExtensionSlurryHandler.create(extension, side);
            return null;
        }, endpoint -> endpoint instanceof LiveMekanismEndpoint live && live.isAvailable());
        LegacyCapabilities.register(HEAT, (be, side) -> {
            if (be instanceof NestedFactoryBlockEntity factory) return MekanismHeatHandler.forFactory(factory, side);
            if (be instanceof NestedPortBlockEntity port) return MekanismHeatHandler.forPort(port, side);
            if (be instanceof NestedExtensionInterfaceBlockEntity extension) return MekanismExtensionHeatHandler.create(extension, side);
            return null;
        }, endpoint -> endpoint instanceof LiveMekanismEndpoint live && live.isAvailable());
    }
}
