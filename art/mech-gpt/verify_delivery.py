"""Run with checkpoint, Bedrock geometry and PNG as arguments."""
import base64
from collections import Counter
from io import BytesIO
import json
from pathlib import Path
import sys
from PIL import Image

bb_path, geo_path, png_path = sys.argv[1:]
bb = json.loads(Path(bb_path).read_bytes())
g = json.loads(Path(geo_path).read_bytes())['minecraft:geometry'][0]
required = set('root hip torso cockpit_glass core_ring core_socket shoulder_l shoulder_r arm_l arm_l_fore arm_r arm_r_fore mount_arm_l mount_arm_r mount_shoulder_l mount_shoulder_r mount_back leg_l_upper leg_l_lower foot_l leg_r_upper leg_r_lower foot_r thruster_l thruster_r antenna'.split())
bones = {b['name']: b for b in g['bones']}
assert set(bones) == required and len(g['bones']) == len(required)
seen, used = set(), set()

def walk(group, parent=None):
    b = bones[group['name']]
    assert b['name'] not in seen and b.get('parent') == parent
    seen.add(b['name'])
    kids = group['children']
    cubes = b.get('cubes', [])
    refs = [k for k in kids if isinstance(k, str)]
    assert len(refs) == len(cubes)
    if b['name'].startswith('mount_'):
        assert not kids and not cubes
    for uid, cube in zip(refs, cubes):
        assert uid not in used
        used.add(uid)
        assert all(v > 0 for v in cube['size'])
        for prop in ('origin', 'size', 'pivot', 'rotation'):
            assert len(cube[prop]) == 3
        for face in cube['uv'].values():
            for u, du in zip(face['uv'], face['uv_size']):
                assert 0 <= u <= 128 and 0 <= u + du <= 128
    for child in kids:
        if isinstance(child, dict):
            walk(child, b['name'])

walk(bb['outliner'][0])
assert seen == required and used == {e['uuid'] for e in bb['elements']}
assert g['description']['texture_width'] == g['description']['texture_height'] == 128
atlas = Image.open(png_path).convert('RGBA')
embedded = Image.open(BytesIO(base64.b64decode(bb['textures'][0]['source'].split(',')[1]))).convert('RGBA')
assert atlas.size == embedded.size == (128, 128) and atlas.tobytes() == embedded.tobytes()
alpha = Counter(atlas.getchannel('A').getdata())
assert 0 not in alpha and any(0 < a < 255 for a in alpha)
print(f'{len(bones)} bones; {len(used)} cubes; alpha histogram: {dict(alpha)}')
