# Minecraft 1.20.1 port

This branch adapts the Minecraft 1.21.1 v1.5.2 baseline to Minecraft 1.20.1 and includes the fixes described in [v1.5.21](releases/mc1.20.1-v1.5.21.md) and [v1.5.22](releases/mc1.20.1-v1.5.22.md).

Development versions: Java 17, NeoForge 47.1.106, Create 6.0.8-291, Ponder 1.0.91, Flywheel 1.0.6-beta-266, Registrate MC1.20-1.3.3, and Mekanism API 1.20.1-10.4.16.80. Loader metadata permits Forge 47.1.33–47.x; Create metadata permits 6.0.8–6.0.x. Those permitted ranges do not establish that every version has been tested. Pipez fixes were investigated against 1.20.1-1.2.5 and reference sources for 1.20.1.

The build uses Forge 47 APIs, `META-INF/mods.toml`, Java 17 bytecode, and SRG reobfuscation. Item/fluid identity and player sessions use 1.20.1 NBT; networking uses SimpleChannel. Recipes, loot tables, advancements, and dimension resources use the 1.20.1 format. Logistics compatibility is included in the main JAR.

Automated validation includes 21 JVM regression groups. The 1.5.22 production JAR passed eight dedicated-server freeze compatibility combinations on Forge 47.3.0 and NeoForge 47.1.106 with base dependencies, Radium 0.12.4, Observable 4.4.2, and both. See the [validation report](freeze-compatibility-validation-1.20.1.md). Client GUI and complete gameplay coverage remain pending. Install the full Create distribution, not its development slim JAR. No conversion of 1.21.1 world/player data is provided. Moving-structure compatibility with Aeronautics, Simulated, and Sable is absent; ordinary world passages and black-box production remain available.

Build with Java 17 using `gradlew.bat clean build` on Windows or `bash gradlew clean build` on Linux/macOS. The Windows helper `build-1.20.1.ps1` uses a project-local Gradle cache and may need a suitable Java 17 JAVA_HOME on another machine; do not publish local proxy/cache settings.
