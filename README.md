# Create Nested Factory

A NeoForge mod for Minecraft 1.21.1 that adds nestable factory spaces and integrates with the Create mod's logistics and kinetic systems.

## Highlights

- Create expandable factory spaces that can be entered, exited, and collapsed.
- Place nested factories inside valid factory spaces for multi-level layouts.
- Configure directional ports for item and fluid transfer across factory boundaries.
- Use buffered handoff channels to preserve logistics behavior across loaded and unloaded spaces.
- Save and restore factory blueprints.

## Requirements

- Java 21
- Minecraft 1.21.1
- NeoForge 21.1.248
- Create 6.0.10 (for Minecraft 1.21.1)

The exact development dependency versions are defined in `gradle.properties`.

## Building from source

On Windows:

```powershell
.\gradlew.bat build
```

On macOS or Linux:

```bash
./gradlew build
```

The built JAR is written under `build/libs/`.

## Development documentation

- `CONTEXT.md` defines the project domain language and invariants.
- `docs/adr/` contains architecture decision records.
- `docs/create-mixing-recipe-format.md` documents the Create mixing recipe resource format used by the mod.

## Version

Current source release: **1.0.0**.

## License

All Rights Reserved. The repository is public for source availability and reference; no permission to copy, modify, or redistribute is granted unless the copyright holder explicitly provides it.
