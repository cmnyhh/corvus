"""Validate cross-references in the shipped catalog, including legacy module identities."""
import json
from pathlib import Path

DATA = Path(__file__).resolve().parents[1] / 'src/main/resources/data/huhu_dronepal'


def entries(folder):
    return {p.stem: json.loads(p.read_bytes()) for p in sorted((DATA / folder).glob('*.json'))}


def main():
    vendors = entries('vendors')
    modules = entries('modules')
    cells = entries('batteries')
    paints = entries('paints')
    frames = entries('frames')
    errors = []
    for product, item in {**cells, **modules}.items():
        vendor = vendors.get(item.get('vendor'))
        if not vendor:
            errors.append(f'{product}: unknown vendor')
            continue
        tier = item['tier']
        if not 1 <= tier <= vendor.get('traits', {}).get('max_tier', 3):
            errors.append(f'{product}: invalid tier {tier}')
    for product, module in modules.items():
        legacy = f"{module['family']}_mk{module['tier']}"
        if legacy not in modules:
            errors.append(f'{product}: missing legacy primary product {legacy}')
    for product, frame in frames.items():
        if frame['default_paint'].split(':')[-1] not in paints:
            errors.append(f'{product}: unknown default paint')
    for product, cell in cells.items():
        if not (0 < cell['max_receive'] <= cell['capacity_fe']):
            errors.append(f'{product}: invalid capacity or receive rate')
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'{len(frames)} frames, {len(vendors)} vendors, {len(modules)} modules, {len(cells)} batteries, {len(paints)} paints')


if __name__ == '__main__':
    main()
