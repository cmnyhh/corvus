"""Check the Dronepal renderer's asset contracts without starting Minecraft."""
import base64
import json
import struct
import unittest
from pathlib import Path

RESOURCES = Path(__file__).resolve().parents[1] / 'src/main/resources'
ASSETS = RESOURCES / 'assets/huhu_dronepal'
PAINTS = RESOURCES / 'data/huhu_dronepal/paints'


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


class ModelContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.geo, = load(ASSETS / 'geo/drone.geo.json')['minecraft:geometry']
        cls.bones = {bone['name']: bone for bone in cls.geo['bones']}

    def test_rig(self):
        parents = {'body': 'root', 'bottom': 'root', 'sensor': 'body',
                   'pod_l': 'body', 'pod_r': 'body', 'gimbal': 'bottom',
                   'gun_mount': 'gimbal'}
        self.assertEqual(len(self.bones), len(self.geo['bones']))
        for bone, parent in parents.items():
            self.assertEqual(self.bones[bone]['parent'], parent, bone)
        self.assertFalse(self.bones['gun_mount'].get('cubes'))
        for name in self.bones:
            ancestors = set()
            while name != 'root':
                self.assertNotIn(name, ancestors, 'cyclic hierarchy')
                ancestors.add(name)
                name = self.bones[name]['parent']

    def test_editor_project(self):
        project = load(RESOURCES.parents[2] / 'model/drone.bbmodel')
        self.assertEqual(len(project['elements']), sum(len(b.get('cubes', [])) for b in self.bones.values()))
        encoded = project['textures'][0]['source'].split(',', 1)[1]
        self.assertEqual(base64.b64decode(encoded), (ASSETS / 'textures/entity/drone_standard.png').read_bytes())

    def test_cube_faces(self):
        desc = self.geo['description']
        width, height = desc['texture_width'], desc['texture_height']
        for bone in self.bones.values():
            for cube in bone.get('cubes', []):
                size = cube['size']
                self.assertTrue(all(n > 0 for n in size), bone['name'])
                uv = cube['uv']
                if isinstance(uv, list):
                    u, v = uv
                    self.assertTrue(0 <= u <= u + 2 * (size[0] + size[2]) <= width)
                    self.assertTrue(0 <= v <= v + size[1] + size[2] <= height)
                    continue
                for face in uv.values():
                    u, v = face['uv']
                    du, dv = face['uv_size']
                    self.assertNotEqual(du * dv, 0)
                    self.assertTrue(all(0 <= x <= width for x in (u, u + du)))
                    self.assertTrue(all(0 <= y <= height for y in (v, v + dv)))

    def test_textures(self):
        skins = 'standard arctic sand jungle crimson neon void chrome rust'.split()
        self.assertEqual(set(skins), {p.stem for p in PAINTS.glob('*.json')})
        desc = self.geo['description']
        dimensions = (desc['texture_width'], desc['texture_height'])
        self.assertEqual(dimensions, (256, 256))
        images = set()
        for skin in skins:
            paint = load(PAINTS / f'{skin}.json')
            namespace, texture = paint['texture'].split(':')
            png = (RESOURCES / 'assets' / namespace / 'textures' / (texture + '.png')).read_bytes()
            self.assertEqual(png[:8], b'\x89PNG\r\n\x1a\n')
            self.assertEqual(struct.unpack('>II', png[16:24]), dimensions, skin)
            images.add(png)
        self.assertEqual(len(images), len(skins))

    def test_animation_bindings(self):
        clips = load(ASSETS / 'animations/drone.animation.json')['animations']
        for name in ('idle', 'boost', 'scan', 'damaged', 'stow', 'deploy'):
            self.assertTrue(clips[name]['bones'], name)
            self.assertTrue(clips[name]['bones'].keys() <= self.bones.keys(), name)
            self.assertNotIn('gun_mount', clips[name]['bones'], name)


if __name__ == '__main__':
    unittest.main()
