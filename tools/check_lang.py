"""Verify that every registered item/block/entity has a language entry in both locales.

Minecraft derives an item's default translation key from its registry name
(`item.<namespace>.<path>`), so a namespace rename silently breaks every item
that does not override its name. This guard rebuilds the registry id list from
source and asserts the key exists in both zh_cn and en_us.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src' / 'main' / 'resources'
NS = 'huhu_dronepal'
LANG = RES / 'assets' / NS / 'lang'


def lang(name):
    return json.loads((LANG / f'{name}.json').read_text(encoding='utf-8'))


def ids_from_java(pattern):
    found = set()
    for path in (ROOT / 'src' / 'main' / 'java').rglob('*.java'):
        for match in re.finditer(pattern, path.read_text(encoding='utf-8')):
            found.add(match.group(1))
    return found


def main():
    items = ids_from_java(r'registerSimpleItem\(\s*"([a-z0-9_]+)"')
    items |= ids_from_java(r'ITEMS\.register\(\s*"([a-z0-9_]+)"')
    # SurvivalWorks registers its parts and the block item from literals in a List.of/loop.
    for path in (ROOT / 'src' / 'main' / 'java').rglob('*.java'):
        text = path.read_text(encoding='utf-8')
        for block in re.findall(r'List\.of\(([^)]*)\)\)\s*ITEMS\.registerSimpleItem', text):
            items |= set(re.findall(r'"([a-z0-9_]+)"', block))
    items |= {p.stem for p in (RES / 'data' / NS / 'batteries').glob('*.json')}
    items |= {f'paint_can_{p.stem}' for p in (RES / 'data' / NS / 'paints').glob('*.json')}
    items |= {f'chip_{c}' for c in ('silent', 'chatty', 'tactical')}
    items = {i for i in items if not i.endswith('_')}  # loop prefixes such as chip_ / paint_can_
    items |= {'drone', 'module'}
    blocks = ids_from_java(r'BLOCKS\.register\(\s*"([a-z0-9_]+)"')
    entities = ids_from_java(r'ENTITIES\.register\(\s*"([a-z0-9_]+)"')

    locales = {name: lang(name) for name in ('zh_cn', 'en_us')}
    missing = []
    for prefix, ids in (('item', items), ('block', blocks), ('entity', entities)):
        for ident in sorted(ids):
            key = f'{prefix}.{NS}.{ident}'
            for name, table in locales.items():
                # A BlockItem inherits Minecraft's block description id (`block.<ns>.<id>`), so a block
                # that is also a registered item may be named by either key. The power blocks use one
                # `block.*` key for block and item; inventing an unused `item.*` alias would hide
                # nothing and show nothing.
                if prefix == 'item' and ident in blocks and f'block.{NS}.{ident}' in table:
                    continue
                if key not in table:
                    missing.append(f'{name}: {key}')
    if missing:
        print('\n'.join(missing))
        return 1
    print(f'{len(items)} items, {len(blocks)} blocks, {len(entities)} entities covered by both locales')
    return 0


if __name__ == '__main__':
    sys.exit(main())
