"""Build Dronepal's Bedrock geometry, editable Blockbench project and paint atlases.
Run with Python 3 and Pillow. Coordinates are Bedrock model units, front is -Z.
"""
import base64
import json
import math
import uuid
from pathlib import Path
from PIL import Image, ImageColor, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / 'model'
ASSETS = ROOT / 'src/main/resources/assets/dronepal'
FACES = ('north','south','east','west','up','down')
MATERIALS = ('carbon','shell','upper','light','steel','black','optic','glow','hot','warn','pearl','trim')
PAINTS = {
    'standard': ('#38343e','#59545d','#ac9da4','#dbd0d1',None),
    'arctic': ('#768598','#acbcc9','#d6e3ef','#f7fafb',None),
    'sand': ('#615244','#98744e','#c7a878','#e9d7a8',None),
    'jungle': ('#35473e','#55674a','#82966d','#b8c2a0',None),
    'crimson': ('#34212b','#683343','#b33d55','#d78e93',None),
    'neon': ('#242939','#3e3952','#625179','#a399bd',None),
    'void': ('#302a44','#514264','#85749d','#bca6cc',None),
    'chrome': ('#515c67','#8e9ba8','#c2cbd4','#ecf1f3',None),
}

def mix(a,b,t):
    return tuple(round(x*(1-t)+y*t) for x,y in zip(a,b))

def atlas(paint):
    c,s,u,l,g = PAINTS[paint]
    paint_data = ROOT/'src/main/resources/data/dronepal/paints'/f'{paint}.json'
    glow = ImageColor.getrgb(g or json.loads(paint_data.read_text())['glow_color'])
    colors = [ImageColor.getrgb(x) for x in (c,s,u,l,'#a6a2ae','#201d28')]
    colors += [mix(glow,(42,20,49),.62),glow,mix(glow,(255,230,248),.62),(234,158,72),(202,193,235),mix(colors[1],colors[2],.45)]
    im = Image.new('RGBA',(256,256)); draw = ImageDraw.Draw(im)
    # The 24x24 cells have a 4px margin around the sampled island. Large plates
    # use the patterned row; small bevels sample a flat central texel.
    for i,color in enumerate(colors):
        x,y = i%8*32,i//8*64
        for detail in (0,1):
            yy=y+32*detail
            draw.rectangle((x,yy,x+27,yy+27), fill=color)
            if detail and i in (0,1,2,3,4,11):
                edge=mix(color,(39,33,45),.36); shine=mix(color,(233,226,225),.27)
                draw.line([(x+4,yy+5),(x+15,yy+5),(x+17,yy+7),(x+21,yy+7)],fill=edge)
                draw.line([(x+4,yy+6),(x+14,yy+6)],fill=shine)
                draw.line([(x+20,yy+12),(x+20,yy+18),(x+18,yy+20)],fill=edge)
                for xx in (7,10): draw.point((x+xx,yy+18),fill=edge)
                draw.line([(x+6,yy+14),(x+8,yy+14)],fill=mix(color,(239,162,98),.62))
    return im

bones=[]; names=[]
def bone(name,parent,pivot):
    b=dict(name=name,pivot=list(pivot)); bones.append(b)
    if parent: b['parent']=parent
    return b

def part(b,name,pos,size,material,rot=(0,0,0),panel=False):
    i=MATERIALS.index(material); x=i%8*32; y=i//8*64
    uv={}
    for face in FACES:
        detailed=panel and min({'north':(size[0],size[1]),'south':(size[0],size[1]),'up':(size[0],size[2]),'down':(size[0],size[2]),'west':(size[1],size[2]),'east':(size[1],size[2])}[face])>=1.25
        uv[face]={'uv':[x+4,y+(36 if detailed else 4)],'uv_size':([18,18] if detailed else [1,1])}
    cube=dict(origin=[round(p-s/2,4) for p,s in zip(pos,size)],size=list(size),uv=uv)
    if any(rot): cube.update(pivot=list(pos),rotation=list(rot))
    b.setdefault('cubes',[]).append(cube); names.append((b['name'],name,cube))

def bezel(b,label,x,y,z,r,material,deep=.22):
    for k in range(8):
        a=k*math.pi/4
        part(b,label+' '+str(k+1),(x+r*math.sin(a),y+r*math.cos(a),z),(r*.88,r*.27,deep),material,(0,0,k*45))

root=bone('root',None,(0,0,0)); body=bone('body','root',(0,6,0))
part(body,'central pressure shell',(0,6,0),(9.28,4,9.28),'shell',panel=True)
part(body,'belly keel',(0,4.2,0),(10.5,2.25,16.5),'carbon',panel=True)
part(body,'bridge deck',(0,6.79,.8),(12.8,2.7,15.4),'upper',panel=True)
part(body,'aft deck',(0,9.04,3.1),(12,1.17,10.2),'light',panel=True)
part(body,'aft deck inset',(0,9.68,3.4),(8.9,.27,9.4),'shell',panel=True)
part(body,'tapered aft deck',(0,9.05,8.88),(11.5,.64,3.7),'upper',(-12,0,0),True)
# Layer the nose and roof along one continuous fore-aft sill.
part(body,'sloping bridge glass',(0,8.11,-6.20),(8.52,1.25,10.30),'carbon',(18,0,0),True)
for sign in (-1,1):
    part(body,'foredeck shoulder backing',(sign*4.78,7.42,-6.22),(2.48,1.95,10.35),'upper',(18,0,0),True)
    part(body,'foredeck raised rim',(sign*4.88,8.2,-6.15),(1.83,1.10,10.3),'light',(18,0,0),True)
    part(body,'deck signal',(sign*4.05,8.22,-6.6),(.24,.86,1.65),'glow',(18,0,0))
    part(body,'bridge caution',(sign*5.05,7.89,-8.32),(.54,.12,.9),'warn',(18,0,0))
    part(body,'aft shoulder',(sign*5.6,9.10,3.25),(1.32,1.28,10.7),'upper',panel=True)
# Face wraps around the recessed dual-row camera cluster.
part(body,'nose gasket',(0,5.92,-9.5),(10.65,3.85,3),'shell',panel=True)
part(body,'nose front fascia',(0,5.93,-10.93),(8.0,3.45,1.08),'upper',panel=True)
part(body,'optics well',(0,6.02,-11.32),(5.10,2.18,.48),'black')
part(body,'hood brow',(0,7.29,-11.10),(5.54,.46,.9),'light')
part(body,'lower sill',(0,4.72,-11.10),(5.50,.45,1),'light')
for sign in (-1,1):
    part(body,'cheek armor',(sign*4.44,5.83,-10.84),(2.7,3.22,1.2),'light',(0,sign*6,sign*7),True)
    part(body,'visor edge',(sign*2.73,6.0,-11.09),(.48,2.53,.84),'steel',(0,0,sign*8))
    part(body,'cheek lamp',(sign*4.54,6.51,-11.41),(1.95,.23,.18),'glow')
for row in range(2):
    for col,x in enumerate((-1.25,0,1.25)):
        y=5.58+.82*row
        part(body,'lens cup',(x,y,-11.55),(.68,.68,.1),'shell',(0,0,45))
        part(body,'lens glass',(x,y,-11.575),(.44,.44,.05),'optic' if col==1 else 'pearl',(0,0,45))
        part(body,'optic reflection',(x-.06,y+.08,-11.595),(.14,.14,.01),'hot' if col==1 else 'light')

# Rake the fascia and its six glazing elements together about the brow.
# All components share an optical plane, so their registration is preserved.
face_start=next(i for i,(_,name,_) in enumerate(names) if name=='nose gasket')
angle=math.radians(22)
for bone_name, name, cube in names[face_start:]:
    size=cube['size']; center=[p+d/2 for p,d in zip(cube['origin'],size)]
    if name == 'lens glass': center[2] -= .18
    if name == 'optic reflection': center[2] -= .21
    if name == 'cheek lamp': center[2] -= .30
    y,z=center[1]-7.2,center[2]+11.3
    center[1]=round(7.2+math.cos(angle)*y+math.sin(angle)*z,4)
    center[2]=round(-11.3-math.sin(angle)*y+math.cos(angle)*z,4)
    cube['origin']=[round(p-d/2,4) for p,d in zip(center,size)]
    cube['pivot']=center
    old=cube.get('rotation',[0,0,0]);cube['rotation']=[old[0]+22,old[1],old[2]]

for sign,label in ((-1,'pod_l'),(1,'pod_r')):
    x=sign*7.55; p=bone(label,'body',(x,5.6,0))
    part(p,'pontoon spine',(x,5.4,0),(5.5,4.2,16.5),'carbon',panel=True)
    part(p,'lower fairing',(x,3.81,.7),(3.9,2.1,16.5),'shell',panel=True)
    part(p,'upper shoulder facet',(sign*9.65,7.13,.3),(2.42,1.0,14.1),'shell',(0,0,sign*25),True)
    part(p,'lower shoulder facet',(sign*9.70,4.53,.3),(2.65,1.35,13.8),'carbon',(0,0,-sign*30),True)
    part(p,'outer rubrail',(sign*10.64,5.78,-1.32),(1.92,1.65,11.26),'carbon',panel=True)
    part(p,'tail flank bevel',(sign*10.14,5.72,5.94),(1.69,1.57,4.18),'carbon',(0,sign*16,0),True)
    part(p,'tail crown bevel',(x,6.99,6.80),(4.15,1.10,4.15),'shell',(-19,0,0),True)
    part(p,'tail keel bevel',(x,4.16,6.52),(3.7,1.11,4.05),'shell',(15,0,0),True)
    part(p,'sloping cowl',(x,7.37,-.2),(4.4,1.35,14.7),'shell',(-3,0,0),True)
    part(p,'cap ridge',(x,8.13,2.0),(3.25,.46,8.6),'carbon',panel=True)
    part(p,'intake brow',(x,6.85,-7.91),(4.47,1.12,2.05),'shell',(-10,0,0),True)
    part(p,'front lateral band',(sign*8.62,6.48,-8.99),(5.28,.58,.44),'black')
    part(p,'front lateral filament',(sign*8.62,6.48,-9.225),(4.82,.26,.10),'glow')
    part(p,'front intake housing',(x,4.59,-8.2),(3.9,3.03,2.2),'carbon',panel=True)
    part(p,'intake dark center',(x,4.54,-9.32),(2.15,2.15,.26),'black',(0,0,45))
    bezel(p,'intake alloy lip',x,4.54,-9.49,1.13,'trim')
    bezel(p,'intake luminous lip',x,4.54,-9.62,.88,'glow')
    part(p,'long channel',(sign*11.54,6.16,-.18),(.11,.62,13.9),'black')
    part(p,'long filament',(sign*11.582,6.18,-.25),(.036,.27,13.25),'glow')
    for z in (-3.25,2.75): part(p,'light clamp',(sign*11.587,6.18,z),(.026,.48,.21),'trim')
    for y in (3.78,7.42):
        part(p,'exhaust barrel',(x,y,8.8),(3.12,2.8,4.85),'shell',panel=True)
        part(p,'exhaust collar',(x,y,10.26),(3.32,2.93,1.07),'carbon')
        part(p,'exhaust face',(x,y,11.23),(2.68,2.68,.5),'black',(0,0,45))
        bezel(p,'exhaust rim',x,y,11.48,1.22,'steel',.24)
        bezel(p,'exhaust glow',x,y,11.48,.91,'optic',.24)

sensor=bone('sensor','body',(0,10.25,0)); part(body,'scanner bearing',(0,9.64,.6),(3.3,1.56,3.1),'black')
part(sensor,'swivel collar',(0,10.62,.55),(3.6,.85,3.45),'shell',panel=True)
part(sensor,'scanner housing',(0,12.0,.36),(4.58,2.15,7.8),'carbon',panel=True)
part(sensor,'scanner hood',(0,13.21,.45),(4,.89,7.45),'upper',panel=True)
part(sensor,'crown',(0,13.79,.75),(2.85,.26,6.18),'shell',panel=True)
part(sensor,'scanner tail',(0,12.0,4.45),(3.8,1.59,1.0),'shell',panel=True)
part(sensor,'optic socket',(0,12.15,-3.58),(3.75,1.53,.54),'black')
part(sensor,'visor rim',(0,12.15,-3.84),(3.08,1.13,.18),'optic')
part(sensor,'visor glass',(0,12.15,-3.94),(2.57,.78,.12),'glow')
part(sensor,'optic pupil',(0,12.15,-4.01),(1.05,.61,.1),'optic')
part(sensor,'focal glint',(0,12.18,-4.09),(.36,.27,.08),'hot')
for sign in (-1,1):
    part(sensor,'scanner flank lamp',(sign*2.295,11.91,.1),(.1,.20,4.82),'glow')
    part(sensor,'visor keeper',(sign*1.15,12.15,-4.06),(.2,.89,.16),'carbon')
part(sensor,'service mark',(1.19,13.93,2.2),(.42,.02,.92),'warn')
bottom=bone('bottom','root',(0,4,0)); part(bottom,'belly socket',(0,3.2,-2),(6,2.65,5.16),'carbon',panel=True)
for sign in (-1,1):
    part(bottom,'lower cheek',(sign*4.2,2.65,.25),(2.25,1.32,10.5),'upper',panel=True)
    part(bottom,'runner pad',(sign*4.15,1.88,.65),(1.72,.46,8.2),'carbon')
gimbal=bone('gimbal','bottom',(0,2.20,-2)); part(gimbal,'underbody capsule',(0,1.48,-2),(5.45,1.7,6.9),'shell',panel=True)
part(gimbal,'forward fairing',(0,1.42,-5.5),(4.65,1.23,1.68),'upper',panel=True)
part(gimbal,'mount cover',(0,.48,-2),(4.8,.96,5.4),'light',panel=True)
mount=bone('gun_mount','gimbal',(0,0,-2))

def dump(path,data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=1)+'\n')

def uid(name): return str(uuid.uuid5(uuid.NAMESPACE_URL,'dronepal/v3/'+name))
def bbpos(p): return [-p[0],p[1],p[2]]

def build():
    tex=ASSETS/'textures/entity'; tex.mkdir(parents=True,exist_ok=True)
    for paint in PAINTS: atlas(paint).save(tex/f'drone_{paint}.png')
    write_frame('drone', 'dronepal', 'standard', bones, names)


def write_frame(filename, identifier, paint, bones, names):
    desc=dict(identifier='geometry.' + identifier,texture_width=256,texture_height=256,visible_bounds_width=2.25,visible_bounds_height=2.25,visible_bounds_offset=[0,.45,0])
    geo={'format_version':'1.12.0','minecraft:geometry':[dict(description=desc,bones=bones)]}
    dump(ASSETS/f'geo/{filename}.geo.json',geo)
    tex=ASSETS/'textures/entity'
    groups={}; elements=[]
    for b in bones:
        group=dict(name=b['name'],origin=bbpos(b['pivot']),uuid=uid(b['name']),children=[],isOpen=True,export=True)
        groups[b['name']]=group
        if 'parent' in b: groups[b['parent']]['children'].append(group)
    for i,(group,name,cube) in enumerate(names):
        a=cube['origin']; d=cube['size']; bb_from=[-a[0]-d[0],a[1],a[2]]
        faces={f:{'uv':v['uv']+[sum(t) for t in zip(v['uv'],v['uv_size'])],'texture':0} for f,v in cube['uv'].items()}
        for face in ('up','down'):
            u=faces[face]['uv']; faces[face]['uv']=u[2:]+u[:2]
        r=cube.get('rotation',[0,0,0]); el={'name':name,'type':'cube','uuid':uid(str(i)),'from':bb_from,'to':[a+b for a,b in zip(bb_from,d)],'origin':bbpos(cube.get('pivot',[0,0,0])),'rotation':[-r[0],-r[1],r[2]],'faces':faces,'box_uv':False}
        elements.append(el); groups[group]['children'].append(el['uuid'])
    data='data:image/png;base64,'+base64.b64encode((tex/f'drone_{paint}.png').read_bytes()).decode()
    dump(MODEL/f'{filename}.bbmodel',dict(meta=dict(format_version='4.10',model_format='bedrock',box_uv=False),name='Dronepal' if filename=='drone' else filename.title(),model_identifier=identifier,visible_box=[2.25,2.25,.45],resolution=dict(width=256,height=256),elements=elements,outliner=[groups['root']],textures=[dict(name=f'drone_{paint}.png',id='0',uuid=uid('texture'),source=data,internal=True,mode='bitmap',width=256,height=256)]))
    print(f'{len(bones)} bones, {len(elements)} cubes, {len(PAINTS)} paint atlases')
if __name__=='__main__': build()
