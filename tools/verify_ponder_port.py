"""Check scene/resource parity and confirm the 1.21.1 source snapshot was not changed."""
import hashlib
import json
from pathlib import Path
import gzip
import sys

from port_ponder_1_20_1 import ASSETS, PACKAGE, TARGET, convert_items, java_for_legacy, read_nbt


source = Path(sys.argv[1]).resolve()
manifest = json.loads((TARGET / "migration/ponder-port/source-sha256.json").read_text(encoding="utf-8"))
current = {path.relative_to(source).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
           for path in (source / "src").rglob("*") if path.is_file()}
assert current == manifest, "The 1.21.1 source tree changed during the port"

report = {"source_files_unchanged": len(manifest), "java": 0, "structures": 0, "translations": {}}
for path in (source / PACKAGE / "ponder/generated").rglob("*.java"):
    relative = path.relative_to(source)
    if path.name == "GeneratedPonderNeoForgeClient.java":
        relative = relative.with_name("GeneratedPonderForgeClient.java")
    expected = java_for_legacy(path.read_text(encoding="utf-8-sig"))
    assert (TARGET / relative).read_text(encoding="utf-8") == expected, f"Scene/support drift: {relative}"
    report["java"] += 1

for path in (source / ASSETS / "ponder").rglob("*.nbt"):
    original = read_nbt(gzip.decompress(path.read_bytes()))
    convert_items(original[0], original[2])
    original[2]["DataVersion"] = (3, 3465)
    actual = read_nbt(gzip.decompress((TARGET / path.relative_to(source)).read_bytes()))
    assert original == actual, f"Structure geometry/state drift: {path.name}"
    report["structures"] += 1

for language in ("zh_cn", "en_us"):
    relative = ASSETS / "lang" / f"{language}.json"
    original = json.loads((source / relative).read_text(encoding="utf-8-sig"))
    actual = json.loads((TARGET / relative).read_text(encoding="utf-8-sig"))
    keys = [key for key in original if ".ponder." in key]
    assert all(actual.get(key) == original[key] for key in keys), f"Translation drift: {language}"
    before = json.loads((TARGET / "migration/ponder-port/before" / relative).read_text(encoding="utf-8-sig"))
    assert all(actual.get(key) == value for key, value in before.items() if ".ponder." not in key)
    report["translations"][language] = len(keys)

for folder in ("models", "textures", "blockstates"):
    for path in (source / ASSETS / folder).rglob("*"):
        if path.is_file():
            assert path.read_bytes() == (TARGET / path.relative_to(source)).read_bytes(), f"Visual asset drift: {path}"

report["result"] = "PASS: scene timing, coordinates, camera and animation code preserved; NBT differs only by item format/data version"
(TARGET / "migration/ponder-port/parity-report.json").write_text(
    json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps(report, ensure_ascii=False))
