"""Rebuild the shared Ponder cutaway using only Python's standard library.

Coordinates match NestedFactoryScenes. This is a teaching diagram: item movement
is scripted, and no factory identity, real room, or production plan is serialized.
"""
import gzip
import struct
from pathlib import Path


def string(value):
    data = value.encode('utf-8')
    return struct.pack('>H', len(data)) + data


def payload(kind, value):
    if kind == 3:
        return struct.pack('>i', value)
    if kind == 8:
        return string(value)
    if kind == 9:
        child, entries = value
        return bytes([child]) + struct.pack('>i', len(entries)) + b''.join(payload(child, e) for e in entries)
    if kind == 10:
        return b''.join(bytes([t]) + string(k) + payload(t, v) for k, (t, v) in value.items()) + b'\x00'
    raise ValueError(kind)


def main():
    palette = []
    blocks = {}

    def block(x, y, z, name, **properties):
        state = {'Name': (8, name)}
        if properties:
            state['Properties'] = (10, {k: (8, v) for k, v in properties.items()})
        if state not in palette:
            palette.append(state)
        blocks[x, y, z] = {'pos': (9, (3, [x, y, z])), 'state': (3, palette.index(state))}

    for x in range(13):
        for z in range(13):
            name = 'minecraft:gray_concrete'
            if x <= 4 and 2 <= z <= 9:
                name = 'minecraft:andesite'
            if x >= 6 and 2 <= z <= 8:
                name = 'create_nested_factory:white_concrete_wall'
            if x == 5:
                name = 'minecraft:black_concrete'
            block(x, 0, z, name)
    for x in range(6, 13):
        for y in (1, 2):
            block(x, y, 2, 'create_nested_factory:white_concrete_wall')
    for x, z in [(2, 5), (2, 8), (10, 7)]:
        block(x, 1, z, 'create_nested_factory:nested_factory', facing='north')
    for x in (7, 11):
        block(x, 1, 5, 'create_nested_factory:nested_port')
    block(9, 1, 5, 'create:depot')
    block(9, 3, 5, 'create:mechanical_press', facing='north')
    block(9, 3, 4, 'create:shaft', axis='z')
    block(9, 3, 3, 'create_nested_factory:nested_stress_port', facing='south')
    block(2, 1, 2, 'create:creative_motor', facing='south')
    for z in (3, 4):
        block(2, 1, z, 'create:shaft', axis='z')
    for x in (0, 4):
        block(x, 1, 5, 'minecraft:barrel', facing='up', open='false')
    # Funnels indicate the external logistics connection; paths are animated.
    block(1, 1, 5, 'create:andesite_funnel', facing='east', extracting='true', powered='false', waterlogged='false')
    block(3, 1, 5, 'create:andesite_funnel', facing='west', extracting='false', powered='false', waterlogged='false')
    # Child-room miniature, revealed only by the nesting storyboard.
    for x in range(7, 12):
        for z in range(3, 8):
            block(x, 5, z, 'minecraft:light_blue_concrete')
    for x in range(7, 12):
        block(x, 6, 3, 'create_nested_factory:white_concrete_wall')
    block(9, 6, 5, 'create:depot')
    root = {'DataVersion': (3, 3955), 'size': (9, (3, [13, 8, 13])),
            'palette': (9, (10, palette)), 'blocks': (9, (10, list(blocks.values()))),
            'entities': (9, (10, []))}
    output = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/create_nested_factory/ponder/nested_factory/workshop.nbt'
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(gzip.compress(b'\x0a\x00\x00' + payload(10, root), mtime=0))
    print(f'{output}: {len(blocks)} blocks, {len(palette)} states')


if __name__ == '__main__':
    main()
