"""Generate the native-scale panel and per-frame previews from the shipped meshes and atlases."""
from pathlib import Path
import json
import math
import numpy as np
from PIL import Image, ImageDraw

ASSETS = Path(__file__).resolve().parents[1] / 'src/main/resources/assets/huhu_dronepal'
OUT = ASSETS / 'textures/gui'
OUT.mkdir(exist_ok=True)
PAINTS = ('standard', 'arctic', 'sand', 'jungle', 'crimson', 'neon', 'void', 'chrome', 'rust')

def bevel(d, x, y, w, h, hi, lo, fill):
    d.rectangle((x,y,x+w-1,y+h-1), fill=lo)
    d.rectangle((x,y,x+w-2,y+h-2), fill=hi)
    d.rectangle((x+1,y+1,x+w-2,y+h-2), fill=fill)

panel = Image.new('RGBA', (264,226))
d = ImageDraw.Draw(panel)
bevel(d,1,1,262,224,'#fff','#181818','#c6c6c6')
bevel(d,2,2,260,222,'#fff','#555','#c6c6c6')
for x,y in [(8,36),(126,36),(8,72)] + [(8+18*c,y) for y in (144,162,180,202) for c in range(9)]:
    bevel(d,x-1,y-1,18,18,'#373737','#fff','#8b8b8b')
bevel(d,175,34,82,68,'#373737','#fff','#343f48')
for x in range(177,255,8): d.line((x,36,x,99),fill='#39464e')
for y in range(36,101,8): d.line((177,y,254,y),fill='#39464e')
bevel(d,105,79,61,7,'#373737','#fff','#555')
panel.save(OUT/'terminal.png')

for frame in ('peregrine','owl','bedrock','scrap'):
    geo = json.loads((ASSETS / ('geo/drone.geo.json' if frame == 'peregrine' else f'geo/{frame}.geo.json')).read_text())['minecraft:geometry'][0]
    bones = {b['name']: b for b in geo['bones']}
    def transform(obj):
        x,y,z = np.radians(obj.get('rotation',[0,0,0]))
        a,b,c = np.cos([x,y,z]); u,v,w = np.sin([x,y,z])
        r = np.array([[c*b,c*v*u-w*a,c*v*a+w*u],[w*b,w*v*u+c*a,w*v*a-c*u],[-v,b*u,b*a]])
        m = np.eye(4); m[:3,:3] = r
        p = np.array(obj.get('pivot',[0,0,0])); m[:3,3] = p-r@p
        return m
    def bone_transform(b):
        return (bone_transform(bones[b['parent']]) if 'parent' in b else np.eye(4)) @ transform(b)

    camera = np.array([.62,.47,-1]); camera /= np.linalg.norm(camera)
    right = np.cross(camera,[0,1,0]); right /= np.linalg.norm(right)
    up = np.cross(right,camera)
    faces=[]
    for bone in bones.values():
        for cube in bone.get('cubes',[]):
            world = bone_transform(bone) @ transform(cube)
            pts = np.array([np.array(cube['origin']) + np.array(cube['size'])*[(i>>j)&1 for j in range(3)] for i in range(8)])
            pts = pts @ world[:3,:3].T + world[:3,3]
            view = pts @ np.array([right,-up,camera]).T
            for key,idx,n in zip(('north','south','east','west','up','down'),((3,1,0,2),(6,4,5,7),(7,5,1,3),(2,0,4,6),(2,6,7,3),(4,0,1,5)),((0,0,-1),(0,0,1),(1,0,0),(-1,0,0),(0,1,0),(0,-1,0))):
                normal = world[:3,:3] @ n
                if normal@camera<=0 or key not in cube['uv']: continue
                faces.append((view[list(idx)],cube['uv'][key], .72+.28*max(0, normal@np.array([-.3,.8,-.52]))))
    allpts=np.concatenate([f[0] for f in faces]); size=np.ptp(allpts[:,:2],axis=0)
    scale=min(72/size[0],53/size[1]); center=(allpts[:,:2].max(0)+allpts[:,:2].min(0))/2
    W,H=78,60
    zbuf=np.full((H,W),-np.inf); uvmap=np.zeros((H,W,2),int); light=np.zeros((H,W,1))
    for face,uv,intensity in faces:
        xy=(face[:,:2]-center)*scale+[W/2,H/2]
        axes=np.array([xy[3]-xy[0],xy[1]-xy[0]]).T
        if abs(np.linalg.det(axes))<1e-6: continue
        iy,ix=np.mgrid[0:H,0:W]; st=(np.stack([ix+.5,iy+.5],axis=-1)-xy[0]) @ np.linalg.inv(axes).T
        depth=face[0,2]+st[...,0]*(face[3,2]-face[0,2])+st[...,1]*(face[1,2]-face[0,2])
        mask=(st.min(-1)>=0)&(st.max(-1)<1)&(depth>zbuf)
        zbuf[mask]=depth[mask]; uvmap[mask]=(uv['uv']+st[mask]*uv['uv_size']).astype(int); light[mask]=intensity
    sheet=Image.new('RGBA',(W,H*len(PAINTS)))
    for i,name in enumerate(PAINTS):
        atlas=np.array(Image.open(ASSETS/f'textures/entity/drone_{name}.png').convert('RGBA'))
        pixels=atlas[uvmap[:,:,1]%atlas.shape[0],uvmap[:,:,0]%atlas.shape[1]].copy()
        pixels[:,:,:3]=(pixels[:,:,:3]*light).astype('uint8'); pixels[:,:,3]=np.where(np.isfinite(zbuf),255,0)
        sheet.paste(Image.fromarray(pixels),(0,H*i))
    sheet.save(OUT/f'paint_{frame}.png')
