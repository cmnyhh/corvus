"""Generate the measured portion of MANIFEST.md from final assets."""
from pathlib import Path
import json
import sys
from PIL import Image

geo, atlas, editor = [Path(p) for p in sys.argv[1:4]]
g = json.loads(geo.read_bytes())['minecraft:geometry'][0]
bb = json.loads(editor.read_bytes())
png = Image.open(atlas).convert('RGBA')
bones = g['bones']
by_name = {b['name']: b for b in bones}
cubes = [c for b in bones for c in b.get('cubes', [])]

def tree(name, prefix=''):
    children = [b['name'] for b in bones if b.get('parent') == name]
    lines = []
    for i, child in enumerate(children):
        last = i + 1 == len(children)
        lines.append(prefix + ('└─ ' if last else '├─ ') + child)
        lines.extend(tree(child, prefix + ('   ' if last else '│  ')))
    return lines

print('# Corvus 双足机甲裸机\n')
print(f"Bedrock 几何 `{g['description']['identifier']}`，格式 `{json.loads(geo.read_bytes())['format_version']}`。模型包含 {len(bones)} 根骨骼、{len(cubes)} 个立方体和 {sum(len(c['uv']) for c in cubes)} 个已定义面。尺寸单位为 u，16 u = 1 格。\n")
print('## 骨骼树\n\n```text\n' + '\n'.join(['root', *tree('root')]) + '\n```\n')
print('## 空接口\n\n下表使用导出几何的模型空间坐标，Y 向上，单位为 u。所有挂点均为叶骨骼，包含 0 个立方体。\n')
print('| 挂点 | 父骨骼 | Pivot (x, y, z) |\n|---|---|---|')
for b in bones:
    if b['name'].startswith('mount_'):
        print(f"| `{b['name']}` | `{b['parent']}` | `{b['pivot']}` |")
