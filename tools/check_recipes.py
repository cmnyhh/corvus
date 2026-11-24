"""Audit every crafting path: reachability of mod items from vanilla + loot + trades.

A recipe is only usable if all of its ingredients are obtainable. This walks the
recipes and assembly blueprints to a fixed point and reports unreachable items,
blueprints whose inputs have no source, and outputs nothing else consumes.
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / 'src' / 'main' / 'resources' / 'data' / 'huhu_dronepal'
NS = 'huhu_dronepal'


def key(item, module=''):
    return f'{item}#{module}' if module else item


def read(folder):
    out = {}
    for path in sorted((DATA / folder).glob('*.json')):
        out[path.stem] = json.loads(path.read_text(encoding='utf-8'))
    return out


def recipe_inputs(recipe):
    found = []
    for ing in recipe.get('ingredients', []):
        if isinstance(ing, str):
            found.append(key(ing))
        elif isinstance(ing, dict):
            if 'item' in ing:
                found.append(key(ing['item'], ing.get('module', '')))
            elif 'tag' in ing:
                found.append('#' + ing['tag'])
    for value in recipe.get('key', {}).values():
        if isinstance(value, dict) and 'item' in value:
            found.append(key(value['item']))
    return found


def main():
    recipes = read('recipe')
    blueprints = read('assembly')

    # Sources that do not need a recipe.
    obtainable = set()
    for table in read('loot_table/supplies').values():
        for pool in table.get('pools', []):
            for entry in pool.get('entries', []):
                obtainable.add(key(entry.get('name', '')))
                item = entry.get('name', '')
                for function in entry.get('functions', []):
                    components = function.get('components', {})
                    nested = components.get('minecraft:custom_data')
                    for data in [components] + ([nested] if isinstance(nested, dict) else []):
                        if 'module_product' in data:
                            obtainable.add(key(item, data['module_product'].split(':')[-1]))
                        for field, value in data.items():
                            if field.endswith(':frame') and isinstance(value, str):
                                obtainable.add(key(item, 'frame:' + value.split(':')[-1]))
    # Cleric trades the three White Crow medical modules.
    for tier in (1, 2, 3):
        obtainable.add(key(f'{NS}:module', f'medic_mk{tier}'))
    # The creative catalogue is not a survival source, so nothing else is seeded here.

    def outputs():
        for name, recipe in recipes.items():
            yield name, recipe.get('result', {}).get('id', ''), recipe_inputs(recipe)
        for name, plan in blueprints.items():
            result = plan.get('result', {})
            item = result.get('item', '')
            out_key = key(item, result.get('module', ''))
            if result.get('frame'):
                out_key = key(item, 'frame:' + result['frame'])
            yield name, out_key, [key(i['item'], i.get('module', '')) for i in plan.get('ingredients', [])]

    changed = True
    while changed:
        changed = False
        for name, out, inputs in outputs():
            if out and out not in obtainable and all(i.startswith('minecraft:') or i.startswith('#') or i in obtainable for i in inputs):
                obtainable.add(out)
                changed = True

    # Which mod items still have no path?
    declared = {f'{NS}:{p.stem}' for p in (DATA / 'modules').glob('*.json')}
    declared |= {f'{NS}:{p.stem}' for p in (DATA / 'batteries').glob('*.json')}
    declared |= {f'{NS}:{p.stem}' for p in (DATA / 'frames').glob('*.json')}
    products = set()
    for p in (DATA / 'modules').glob('*.json'):
        products.add(key(f'{NS}:module', p.stem))
    products |= {f'{NS}:{p.stem}' for p in (DATA / 'batteries').glob('*.json')}
    products |= {key(f'{NS}:drone', 'frame:' + p.stem) for p in (DATA / 'frames').glob('*.json')}

    # Every mod item the game registers must have a source.
    parts = set()
    for path in (ROOT / 'src/main/java').rglob('*.java'):
        text = path.read_text(encoding='utf-8')
        for block in re.findall(r'List\.of\(([^)]*)\)\)\s*ITEMS\.registerSimpleItem', text):
            parts |= set(re.findall(r'"([a-z0-9_]+)"', block))
    paint_ids = {f'{NS}:paint_can_{p.stem}' for p in (DATA / 'paints').glob('*.json')}
    chips = {f'{NS}:chip_{c}' for c in ('silent', 'chatty', 'tactical')}
    parts = {f'{NS}:{x}' for x in parts}
    blocks = {f'{NS}:{p.name}' for p in []}
    inventory = parts | paint_ids | chips | {f'{NS}:module', f'{NS}:drone',
                f'{NS}:assembly_bench', f'{NS}:charging_dock'}
    inventory |= products

    missing_items = sorted(i for i in inventory if i not in obtainable and not i.startswith('#'))

    # Ingredient ids must name a real registered item, not a typo.
    registered = {f'{NS}:{p.stem}' for p in (DATA / 'modules').glob('*.json')}
    registered |= {f'{NS}:{p.stem}' for p in (DATA / 'batteries').glob('*.json')}
    registered |= parts | paint_ids | chips | {f'{NS}:module', f'{NS}:drone'}
    typos = []
    for name, out, inputs in outputs():
        for i in inputs:
            base = i.split('#')[0]
            if base.startswith('minecraft:') or base.startswith('#') or base.startswith(NS + ':'):
                if base.startswith(NS + ':') and base not in registered:
                    typos.append(f'{name}: {base} is not a registered item')

    problems = []
    for name, out, inputs in outputs():
        for i in inputs:
            if i.startswith('minecraft:') or i.startswith('#'):
                continue
            if i not in obtainable:
                problems.append(f'{name}: ingredient {i} has no source')
    dead = sorted(products - obtainable)

    print(f'recipes: {len(recipes)}  blueprints: {len(blueprints)}')
    print(f'obtainable keys: {len(obtainable)}')
    print(f'mod products with no path: {len(dead)}')
    for d in dead:
        print('  NO PATH', d)
    print(f'mod items with no source: {len(missing_items)}')
    for i in missing_items:
        print('  NO SOURCE', i)
    print(f'ingredient ids that are not registered: {len(set(typos))}')
    for t in sorted(set(typos)):
        print('  ', t)
    # The station has 9 input slots and the screen lists up to 5 ingredient lines.
    capacity = []
    for name, plan in blueprints.items():
        distinct = len(plan.get('ingredients', []))
        if distinct > 9:
            capacity.append(f'{name}: {distinct} ingredients do not fit the 9 input slots')
        elif distinct > 5:
            capacity.append(f'{name}: {distinct} ingredient lines exceed the 5-line ledger (display only)')
    outputs_seen = {}
    for name, out, _ in outputs():
        if out in outputs_seen:
            capacity.append(f'{name}: duplicate output {out} (also {outputs_seen[out]})')
        outputs_seen[out] = name
    print(f'blueprint capacity/duplicate issues: {len(capacity)}')
    for c in capacity:
        print('  ', c)
    print(f'ingredients without source: {len(problems)}')
    for p in problems:
        print('  ', p)
    return 1 if dead or problems or missing_items or typos or capacity else 0


if __name__ == '__main__':
    sys.exit(main())
