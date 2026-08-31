# Create Mechanical Mixer (`create:mixing`) recipe JSON format

**Scope:** Create `6.0.10-280` for Minecraft `1.21.1`, matching this project's resolved dependency. This is the schema used by the installed Create source artifact; do not assume it applies unchanged to other Create versions.

Place a datapack recipe at `src/main/resources/data/<namespace>/recipe/<recipe_name>.json`.

## Minimal valid shape

```json
{
  "type": "create:mixing",
  "ingredients": [
    { "item": "minecraft:iron_ingot" }
  ],
  "results": [
    { "id": "minecraft:gold_ingot" }
  ]
}
```

`ingredients` and `results` are required arrays. `processing_time` is optional and defaults to `0`; `heat_requirement` is optional and defaults to `"none"`. [S1]

## Full practical example

```json
{
  "type": "create:mixing",
  "heat_requirement": "heated",
  "processing_time": 100,
  "ingredients": [
    { "tag": "c:ingots/copper" },
    { "tag": "c:ingots/zinc" },
    {
      "type": "neoforge:single",
      "fluid": "minecraft:water",
      "amount": 250
    }
  ],
  "results": [
    { "id": "create:brass_ingot", "count": 2 },
    { "id": "minecraft:water", "amount": 250 }
  ]
}
```

The example deliberately demonstrates both item and fluid inputs/outputs. In a real recipe, the output fluid need not match the input fluid.

## Top-level fields

| Field | Required | Form / default |
|---|---:|---|
| `type` | Yes | Exactly `"create:mixing"`. |
| `ingredients` | Yes | Array of item ingredients and/or sized fluid ingredients. [S1] |
| `results` | Yes | Array of item processing outputs and/or fluid stacks. [S1] |
| `processing_time` | No | Integer; default `0`. Basin recipes permit a duration. [S1][S2] |
| `heat_requirement` | No | `"none"` (default), `"heated"`, or `"superheated"`. [S1][S3] |

`create:mixing` is a `BasinRecipe`. Validation permits at most **64 item inputs**, **2 fluid inputs**, **4 item outputs**, and **2 fluid outputs**. [S2]

## `ingredients` entries

Each array element is decoded as either a vanilla item `Ingredient` or Create's sized fluid ingredient. [S1]

### Item ingredient

Use the normal simple item-ingredient forms:

```json
{ "item": "minecraft:iron_ingot" }
```

```json
{ "tag": "c:ingots/copper" }
```

Each occurrence consumes one matching item; repeat an entry when the recipe needs multiple items. Basin matching extracts one item for every item-ingredient entry. [S2]

### Fluid ingredient

For Create 6, use Create's typed flat format. State `amount` explicitly (millibuckets), even though the codec's default is `1000`:

```json
{ "type": "neoforge:single", "fluid": "minecraft:water", "amount": 1000 }
```

```json
{ "type": "neoforge:tag", "tag": "c:milk", "amount": 250 }
```

The installed Create codec requires the `type` field for this form; it reads a positive `amount` and defaults that amount to `1000` if omitted. [S4] The bundled Create recipes use `neoforge:single` for a specific fluid and `neoforge:tag` for a fluid tag. [S5]

## `results` entries

Each element is decoded as either a fluid stack or an item processing output. [S1]

### Item output (recommended current form)

```json
{ "id": "create:brass_ingot", "count": 2, "chance": 1.0 }
```

| Field | Required | Default / constraint |
|---|---:|---|
| `id` | Yes | Item id. |
| `count` | No | `1`; integer from `1` to `99`. |
| `chance` | No | `1.0`; positive float. A chance below `1` is rolled separately for each produced item. |
| `components` | No | Item data-component patch. |

Use `id`, not the older `item` form: the Create source keeps the latter only as a deprecated compatibility alternative. [S6]

### Fluid output

```json
{ "id": "create:chocolate", "amount": 250 }
```

`id` (fluid id) and positive `amount` are required. `components` is optional. [S7] Create's own mixer recipes use exactly this form for chocolate, tea, and lava outputs. [S5]

## Heat condition

- Omit `heat_requirement` (or use `"none"`) for no heat requirement.
- `"heated"` requires a blaze burner level other than `none` or `smouldering`.
- `"superheated"` requires the `seething` blaze-burner level.

The mixer/basin checks this condition before consuming inputs. [S2][S3]

## Primary-source citations

All locations below are inside the locally resolved primary-source artifacts for the exact versions used by this project.

- **[S1] Create `create-1.21.1-6.0.10-280-sources.jar`**, `com/simibubi/create/content/processing/recipe/ProcessingRecipeParams.java`, lines 43–64: required `ingredients`/`results`, their union codecs, and optional top-level defaults.
- **[S2] Create `create-1.21.1-6.0.10-280-sources.jar`**, `com/simibubi/create/content/processing/basin/BasinRecipe.java`, lines 73–76, 93–136, 195–223: heat gate, item/fluid consumption, limits, and duration/heat support.
- **[S3] Create `create-1.21.1-6.0.10-280-sources.jar`**, `com/simibubi/create/content/processing/recipe/HeatCondition.java`, lines 12–42: serialized heat names and their blaze-burner tests.
- **[S4] Create `create-1.21.1-6.0.10-280-sources.jar`**, `com/simibubi/create/foundation/codec/CreateCodecs.java`, lines 54–62: Create's typed sized-fluid-ingredient codec.
- **[S5] Create `create-1.21.1-6.0.10-280-sources.jar`**, bundled recipes: `data/create/recipe/mixing/brass_ingot.json`, `dough_by_mixing.json`, `chocolate.json`, `lava_from_cobble.json`, and `tea.json`.
- **[S6] Create `create-1.21.1-6.0.10-280-sources.jar`**, `com/simibubi/create/content/processing/recipe/ProcessingOutput.java`, lines 122–143: current `id` output schema, optional fields, and deprecated compatibility schema.
- **[S7] NeoForge `neoforge-21.1.248-sources.jar`**, `net/neoforged/neoforge/fluids/FluidStack.java`, lines 52–68: fluid-stack `id`, positive `amount`, and optional `components` codec.

Resolved artifact locations:

```text
.gradle/caches/modules-2/files-2.1/com.simibubi.create/create-1.21.1/6.0.10-280/*/create-1.21.1-6.0.10-280-sources.jar
.gradle/caches/modules-2/files-2.1/net.neoforged/neoforge/21.1.248/*/neoforge-21.1.248-sources.jar
```
