package com.createnestedfactory.create_nested_factory;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.dimension.DimensionType;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Uses the registry's actual codec so valid JSON with a newer schema cannot pass unnoticed. */
public final class DimensionResourcesRegression {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        String resource = "data/create_nested_factory/dimension_type/nested_factory.json";
        try (var stream = DimensionResourcesRegression.class.getClassLoader().getResourceAsStream(resource)) {
            require(stream != null, "Missing dimension resource: " + resource);
            var json = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            var parsed = DimensionType.DIRECT_CODEC.parse(JsonOps.INSTANCE, json);
            var dimension = parsed.result().orElseThrow(() -> new AssertionError(
                    "Minecraft 1.20.1 rejected " + resource + ": "
                            + parsed.error().map(error -> error.message()).orElse("No decoded dimension")));
            var light = dimension.monsterSettings().monsterSpawnLightTest();
            require(light.getMinValue() == 0 && light.getMaxValue() == 7,
                    "Monster spawn light range changed");
            require(dimension.monsterSettings().monsterSpawnBlockLightLimit() == 0,
                    "Monster spawn block light limit changed");
            require(dimension.fixedTime().orElseThrow() == 6000, "Fixed dimension time changed");
            require(dimension.minY() == -64 && dimension.height() == 384 && dimension.logicalHeight() == 384,
                    "Dimension vertical bounds changed");
        }
        System.out.println("Minecraft 1.20.1 dimension resource codec regression passed.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
