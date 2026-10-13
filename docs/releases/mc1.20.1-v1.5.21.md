# v1.5.21 - Minecraft 1.20.1

Requires Minecraft 1.20.1, Java 17, and Create 6.0.8–6.0.x. Development loader: NeoForge 47.1.106. Optional compatibility targets: Mekanism 10.4.16.80 and Pipez 1.20.1.

## Fixes and improvements

- Corrected GUI textures for face modes, pressed buttons, and the overclock slider.
- Fixed flight and night-vision synchronization, Shift-return while flying, previous effect restoration, cancelled teleports, and flight restoration after game-mode changes.
- Prevented factory-blueprint crashes and accidental entry into Create's schematic editor; corrected capability refresh after applying a blueprint and removed duplicate Shift hints.
- Fixed Mekanism pipe disconnections during capability invalidation and automatic reconnection after mode changes, preparation, thawing, and late port loading.
- Fixed Pipez fluid/universal-pipe endpoint detection for the 1.20.1 API.
- Prevented wrong-type and mixed-type extraction from losing gas, infusion, pigment, or slurry resources.
- Reused capability handlers, extension delegates, and heat endpoint lookups; reduced redundant port refreshes, blueprint parsing, and session NBT work.
- Corrected the 1.20.1 Pocket dimension light-provider format.

## Validation

Full build and all 20 JVM regression groups passed. Release metadata, Java 17 bytecode, resources, and Mixin/refmap packaging were checked. These fixes have not yet received a complete in-game retest or client/dedicated-server validation on both loaders.

Aeronautics, Simulated, and Sable moving-structure integration is not included in this 1.20.1 port. Minecraft 1.21.1 worlds are not converted to 1.20.1.

Install `create_nested_factory-1.20.1-1.5.21.jar`; the sources JAR is not a game mod.
