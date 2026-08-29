package com.createnestedfactory.create_nested_factory.blueprint;

import com.createnestedfactory.create_nested_factory.block.FactoryPowerProfile;
import com.createnestedfactory.create_nested_factory.block.PortMode;
import com.createnestedfactory.create_nested_factory.block.entity.BlackboxData;
import com.createnestedfactory.create_nested_factory.block.entity.NestedFactoryBlockEntity;
import com.simibubi.create.AllItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

import java.util.Locale;

public final class NestedFactoryBlueprint {
    public static final String ITEM_KEY = "NestedFactoryBlueprint";
    private static final String MARKER = "create_nested_factory:blueprint_v1";

    private String displayName = "";
    private String sourceFactoryId = "";
    private String sourceFactoryName = "";
    private String sourceDimension = "";
    private BlockPos sourcePos = BlockPos.ZERO;
    private int sourceDepth;
    private float productionEfficiency = 1.0f;
    private final BlackboxData blackbox = new BlackboxData();
    private final FactoryPowerProfile powerProfile = new FactoryPowerProfile();
    private final PortMode[] faceModes = new PortMode[6];
    private final int[] portIds = new int[6];

    public NestedFactoryBlueprint() {
        for (int i = 0; i < 6; i++) {
            faceModes[i] = PortMode.NONE;
        }
    }

    public static NestedFactoryBlueprint fromFactory(NestedFactoryBlockEntity factory) {
        NestedFactoryBlueprint blueprint = new NestedFactoryBlueprint();
        blueprint.sourceFactoryId = factory.getFactoryId();
        blueprint.sourceFactoryName = factory.getCustomName() == null
                ? factory.getDisplayName().getString()
                : factory.getCustomName();
        blueprint.displayName = blueprint.sourceFactoryName;
        blueprint.sourceDimension = factory.getLevel().dimension().location().toString();
        blueprint.sourcePos = factory.getBlockPos().immutable();
        blueprint.sourceDepth = factory.getNestingDepth();
        blueprint.productionEfficiency = 1.0f;
        blueprint.blackbox.read(factory.getBlackbox().write(new CompoundTag()));
        blueprint.powerProfile.read(factory.getPowerProfile().write());
        for (int i = 0; i < 6; i++) {
            blueprint.faceModes[i] = factory.getFaceMode(net.minecraft.core.Direction.from3DDataValue(i));
            blueprint.portIds[i] = factory.getPortId(net.minecraft.core.Direction.from3DDataValue(i));
        }
        return blueprint;
    }

    public static NestedFactoryBlueprint fromItem(ItemStack stack) {
        if (!stack.is(AllItems.SCHEMATIC.get())) {
            return null;
        }
        CompoundTag root = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (root == null || !root.contains(ITEM_KEY)) {
            return null;
        }
        return fromTag(root.getCompound(ITEM_KEY));
    }

    public static NestedFactoryBlueprint fromTag(CompoundTag tag) {
        if (!MARKER.equals(tag.getString("Marker"))) {
            return null;
        }
        NestedFactoryBlueprint blueprint = new NestedFactoryBlueprint();
        blueprint.read(tag);
        return blueprint;
    }

    public void writeToItem(ItemStack stack) {
        CompoundTag data = new CompoundTag();
        write(data);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, root -> root.put(ITEM_KEY, data));
    }

    public CompoundTag write(CompoundTag tag) {
        tag.putString("Marker", MARKER);
        tag.putString("DisplayName", displayName);
        tag.putString("SourceFactoryId", sourceFactoryId);
        tag.putString("SourceFactoryName", sourceFactoryName);
        tag.putString("SourceDimension", sourceDimension);
        tag.putLong("SourcePos", sourcePos.asLong());
        tag.putInt("SourceDepth", sourceDepth);
        tag.putFloat("ProductionEfficiency", productionEfficiency);
        tag.put("Blackbox", blackbox.write(new CompoundTag()));
        tag.put("PowerProfile", powerProfile.write());
        for (int i = 0; i < 6; i++) {
            tag.putString("FaceMode" + i, faceModes[i].getSerializedName());
            tag.putInt("PortId" + i, portIds[i]);
        }
        return tag;
    }

    public void read(CompoundTag tag) {
        displayName = tag.getString("DisplayName");
        sourceFactoryId = tag.getString("SourceFactoryId");
        sourceFactoryName = tag.getString("SourceFactoryName");
        sourceDimension = tag.getString("SourceDimension");
        sourcePos = tag.contains("SourcePos") ? BlockPos.of(tag.getLong("SourcePos")) : BlockPos.ZERO;
        sourceDepth = tag.getInt("SourceDepth");
        productionEfficiency = tag.contains("ProductionEfficiency") ? tag.getFloat("ProductionEfficiency") : 1.0f;
        blackbox.read(tag.getCompound("Blackbox"));
        powerProfile.read(tag.getCompound("PowerProfile"));
        for (int i = 0; i < 6; i++) {
            faceModes[i] = readPortMode(tag.getString("FaceMode" + i));
            portIds[i] = tag.getInt("PortId" + i);
        }
    }

    public NestedFactoryBlueprint copy() {
        CompoundTag tag = new CompoundTag();
        write(tag);
        return fromTag(tag);
    }

    public boolean hasCompleteRunData() {
        return sourceFactoryId != null && !sourceFactoryId.isBlank();
    }

    private static PortMode readPortMode(String value) {
        try {
            return PortMode.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return PortMode.NONE;
        }
    }

    public String displayName() {
        return displayName;
    }

    public String sourceFactoryId() {
        return sourceFactoryId;
    }

    public String sourceFactoryName() {
        return sourceFactoryName;
    }

    public String sourceDimension() {
        return sourceDimension;
    }

    public BlockPos sourcePos() {
        return sourcePos;
    }

    public int sourceDepth() {
        return sourceDepth;
    }

    public float productionEfficiency() {
        return productionEfficiency;
    }

    public BlackboxData blackbox() {
        return blackbox;
    }

    public FactoryPowerProfile powerProfile() {
        return powerProfile;
    }

    public PortMode faceMode(int index) {
        return faceModes[index];
    }

    public int portId(int index) {
        return portIds[index];
    }
}
