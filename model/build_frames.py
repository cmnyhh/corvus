"""Create the three expansion frames using the same UV and rig convention as Peregrine."""
from pathlib import Path
from PIL import Image, ImageDraw
import build_drone as art


def chassis(frame, paint, museum, size):
    art.bones.clear()
    art.names.clear()
    root = art.bone('root', None, (0, 0, 0))
    body = art.bone('body', 'root', (0, size, 0))
    pods = [art.bone('pod_' + side, 'body', (sign * size, size, 0)) for side,sign in [('l',-1),('r',1)]]
    sensor = art.bone('sensor', 'body', (0, size * 1.4, -1))
    bottom = art.bone('bottom', 'root', (0, 2, 0))
    cradle = art.bone('gimbal', 'bottom', (0, 1.7, -1.0))
    art.bone('gun_mount','gimbal',(0,0,-1.0))
    part(bottom,'suspension bearing',(0,2.7,-1),(size,2.5,size),'carbon')
    part(cradle,'keel clevis',(0,1.4,-1),(size*.84,1.8,size*.85),'trim')
    part(cradle,'underslung cover',(0,.48,-1),(size*.78,.96,size*.77),'upper')
    museum(body,pods,sensor,bottom)
    art.write_frame(frame, 'dronepal.'+frame, paint, art.bones, art.names)

part = art.part

def owl(body,pods,sensor,bottom):
    part(body,'avian spindle',(0,4.2,0),(5.3,2.4,12.8),'carbon',panel=True)
    part(body,'mantle plate',(0,5.52,.15),(4.7,.72,9.4),'light',panel=True)
    part(body,'forehead wedge',(0,5.1,-4.65),(4.45,1.22,4.6),'upper',(13,0,0),True)
    part(body,'tail rudder',(0,5.95,5.48),(.64,2.05,3.3),'shell',(-22,0,0),True)
    for side,pod in zip((-1,1),pods):
        x=side*5.18
        part(body,'swept shoulder',(side*3.3,4.82,1.25),(3.3,.81,5.9),'shell',(0,side*27,0),True)
        part(pod,'swept scout wing',(x,4.66,1.2),(3.1,1.05,8.0),'upper',(0,side*28,0),True)
        part(pod,'whisper nacelle',(side*6.96,4.49,2.6),(2.58,1.82,7.8),'carbon',panel=True)
        part(pod,'ceramic cheek',(side*7.0,5.52,2.65),(2.26,.43,6.75),'light',panel=True)
        part(pod,'outer guide vane',(side*8.55,4.87,4.42),(.5,.91,4.7),'shell',(-14,0,0),True)
        part(pod,'wing socket',(side*7.21,5.76,2.8),(1.15,.14,2.24),'black')
        part(pod,'single slot marker',(side*7.21,5.87,2.8),(.48,.11,1.50),'glow')
        part(pod,'cold exhaust',(side*6.95,4.65,6.55),(1.88,.62,.17),'glow')
        part(pod,'navigation light',(side*6.95,4.62,-1.37),(1.54,.46,.2),'glow')
    part(sensor,'rotating knuckle',(0,5.96,-1.2),(2.35,1.8,2.7),'black')
    part(sensor,'telescope',(0,6.7,-3.23),(2.95,2.66,6.28),'carbon',panel=True)
    part(sensor,'eyebrow visor',(0,7.83,-3.8),(3.7,.48,5.95),'light',panel=True)
    part(sensor,'primary objective',(0,6.75,-6.55),(2.28,2.28,.38),'black',(0,0,45))
    art.bezel(sensor,'objective rim',0,6.75,-6.83,1.25,'steel')
    art.bezel(sensor,'focus ring',0,6.75,-6.96,.91,'glow')
    part(sensor,'glass glint',(-.13,6.9,-6.93),(.48,.53,.24),'hot')


def bedrock(body,pods,sensor,bottom):
    # The load-bearing spine ends ahead of the two divided exhaust packs.
    part(body,'spinal casting',(0,5.7,-.25),(7.4,4.1,13.2),'carbon',panel=True)
    part(body,'midline backplate',(0,8.0,.1),(6.7,1.15,10.5),'shell',panel=True)
    part(body,'lower prow',(0,4.39,-7.65),(4.75,1.65,9.95),'black',(7,0,0))
    part(body,'sloping prow',(0,5.79,-7.34),(4.20,1.35,10.7),'upper',(19,0,0),True)
    part(body,'fore keel',(0,4.2,-11.45),(2.85,1.05,3.45),'carbon',(13,0,0),True)
    part(body,'spine inlay',(0,6.59,-6.82),(1.32,.25,7.8),'shell',(19,0,0),True)
    part(body,'aft deck well',(0,7.74,4.27),(5.62,.52,3.03),'black')
    for z in (3.45,4.33,5.21):
        part(body,'deck louvre',(0,8.02,z),(4.76,.32,.42),'steel',(-17,0,0))
    sensor['pivot']=[0,8.37,-2.84]
    part(sensor,'recessed scan bearing',(0,8.35,-2.4),(3.2,1.37,2.78),'black')
    part(sensor,'armored scan bridge',(0,9.03,-2.55),(4.4,1.32,3.45),'carbon',panel=True)
    part(sensor,'periscope brow',(0,9.76,-2.49),(4.05,.51,3.18),'shell',(5,0,0),True)
    for side,pod in zip((-1,1),pods):
        part(sensor,'split glass',(side*.98,9.09,-4.32),(1.58,.42,.20),'glow')
        part(body,'prow chine',(side*2.76,5.39,-7.54),(2.08,1.67,9.14),'shell',(16,side*13,side*18),True)
        part(body,'prow light',(side*2.9,5.83,-8.05),(.36,.34,5.50),'glow',(16,side*13,side*18))
        part(bottom,'belly fork',(side*2.85,2.68,-2.61),(1.44,1.3,8.3),'trim',(6,side*9,0),True)
        # A swept spar bridges the visible space between fuselage and engine.
        part(pod,'shoulder spar',(side*6.23,5.65,-2.15),(6.9,1.63,2.25),'black',(0,-side*29,0),True)
        part(pod,'shoulder collar',(side*6.16,6.12,-2.2),(4.16,1.23,2.44),'trim',(0,-side*29,0),True)
        part(pod,'swept shoulder core',(side*9.10,6.56,-1.45),(4.58,3.65,11.32),'carbon',(8,side*29,side*12),True)
        part(pod,'shoulder crown',(side*9.09,8.26,-1.12),(4.79,1.42,10.68),'upper',(8,side*29,side*17),True)
        part(pod,'shoulder cheek',(side*10.25,6.55,-.7),(2.33,2.31,9.86),'shell',(9,side*29,side*26),True)
        part(pod,'clavicle stripe',(side*8.94,8.94,-1.13),(.67,.29,8.31),'carbon',(8,side*29,side*17))
        part(pod,'clavicle filament',(side*8.85,9.14,-.87),(.23,.22,5.88),'glow',(8,side*29,side*17))
        # A narrow coupling sets each engine apart from the larger shoulder shell.
        part(pod,'engine coupling',(side*10.86,6.37,4.17),(3.21,2.45,4.25),'steel',(0,-side*9,0),True)
        part(pod,'aft engine',(side*10.63,6.1,8.46),(5.18,3.76,7.80),'carbon',(0,-side*9,0),True)
        part(pod,'aft carapace',(side*10.53,7.96,8.06),(4.88,1.36,7.32),'shell',(-6,-side*9,side*8),True)
        part(pod,'exhaust lip',(side*10.1,5.95,12.15),(4.48,3.05,1.16),'trim',(0,-side*9,0),True)
        part(pod,'exhaust throat',(side*10.0,5.96,12.78),(3.61,2.30,.49),'black',(0,-side*9,0))
        for y in (5.58,6.35):
            part(pod,'thermal vane',(side*9.97,y,13.03),(2.87,.29,.25),'optic',(0,-side*9,0))
        for index,z in enumerate((6.51,9.52)):
            x=side*(10.82-index*.49)
            part(pod,'module hatch',(x,8.70-index*.28,z),(3.61,.44,2.66),'carbon',(-6,-side*9,side*8),True)
            for dz in (-.57,.57):
                part(pod,'ribbed clasp',(x,9.00-index*.28,z+dz),(2.42,.23,.36),'upper',(-6,-side*9,side*8))
        part(pod,'outer docking lug',(side*13.72,6.64,6.81),(1.36,.97,1.86),'shell',panel=True)
        for z in (6.35,7.21):
            part(pod,'captive bolt',(side*14.29,6.69,z),(.22,.41,.41),'steel')


def scrap(body,pods,sensor,bottom):
    part(body,'salvage cage',(0,5.15,0),(8.45,4.77,13.42),'carbon',panel=True)
    part(body,'cast toolbox lid',(-1.4,7.68,-.4),(4.92,1.12,10.92),'upper',panel=True)
    part(body,'scavenged hatch',(2.61,7.63,.18),(2.84,.9,9.42),'trim',(0,0,-7),True)
    part(body,'salvaged grille',(0,4.91,-6.91),(6.92,2.95,.78),'black')
    for x in (-2.6,-1.45,-.3,.85,2.0):part(body,'grille strip',(x,4.84,-7.36),(.43,2.56,.45),'steel',(0,0,10))
    part(body,'off-center headlamp',(2.8,6.73,-7.0),(2.22,1.62,1.54),'shell',panel=True)
    part(body,'headlamp glass',(2.8,6.73,-7.82),(1.55,1.16,.23),'hot')
    for side,pod in zip((-1,1),pods):
        x=7.33*side
        part(body,'engine bracket',(side*4.62,5.02,0),(3.54,1.25,5.0),'steel',panel=True)
        part(pod,'engine block',(x,5.22,.33),(4.52,3.85,12.62),'shell',panel=True)
        for z in (-4.08,3.92):
            part(pod,'engine hoop',(x,5.24,z),(4.92,4.13,.94),'steel')
        part(pod,'bumper',(side*10.35,4.75,.0),(.9,.81,10.38),'carbon')
        part(pod,'exhaust lip',(x,5.29,6.76),(3.73,2.4,1.17),'black')
        part(pod,'warm exhaust',(x,5.29,7.41),(2.68,.64,.24),'optic')
        part(bottom,'salvage rail',(side*3.15,1.42,.32),(1.01,.87,10.8),'steel')
        for z in (-3.7,3.6):part(bottom,'rail spacer',(side*3.15,2.6,z),(.82,2.25,.87),'shell')
    left,right=pods
    part(left,'corrugated shroud',(-7.39,7.49,-.28),(5.13,1.01,10.62),'upper',(0,0,6),True)
    for z in (-3.75,-1.86,.04,1.95,3.84):part(left,'pressed stiffener',(-7.34,8.09,z),(4.18,.3,.44),'shell',(0,0,6))
    part(right,'exposed manifold',(7.3,7.28,.19),(3.65,.87,8.62),'black')
    for x in (6.13,7.33,8.53):
        part(right,'copper return pipe',(x,7.91,.06),(.65,.7,8.26),'warn')
        for z in (-3.94,4.06):part(right,'pipe elbow',(x,7.39,z),(.69,1.52,.64),'trim')
    for n,z in enumerate((-4.2,0,4.2)):
        part(body,'module clasp',(-1.25,8.4,z),(2.74,.34,.73),'black')
        part(body,'amber slot count',(-1.25,8.64,z),(1.64,.19,.40),'glow')
    part(sensor,'salvaged sensor pedestal',(0,8.56,-1),(2.31,1.75,2.25),'black')
    part(sensor,'offset rangefinder',(-1.24,9.61,-1.17),(3.77,2.17,4.02),'shell',(0,0,5),True)
    part(sensor,'sensor patch',(-1.2,10.8,-1.22),(3.01,.37,3.48),'light',(0,0,5),True)
    part(sensor,'single lens',(-1.24,9.75,-3.29),(1.53,1.55,.39),'glow',(0,0,45))
    part(sensor,'whip mast',(1.24,10.83,-.88),(.29,3.73,.32),'steel',(0,0,-12))
    part(sensor,'mast cap',(1.61,12.61,-.88),(.4,.47,.46),'warn')


def rust_paint():
    art.PAINTS['rust']=('#392f29','#71503b','#a45e38','#ba9670',None)
    image=art.atlas('rust'); draw=ImageDraw.Draw(image)
    for i in (0,1,2,3,4,11):
        x=i%8*32; y=i//8*64+32
        draw.polygon([(x+12,y+10),(x+17,y+10),(x+17,y+13),(x+15,y+15),(x+12,y+15)],fill='#745c4e')
        draw.line((x+13,y+10,x+16,y+10),fill='#bc9273')
        draw.point((x+13,y+11),fill='#dbb898');draw.point((x+16,y+13),fill='#c0a180')
        draw.line((x+6,y+16,x+9,y+16),fill='#9b5230');draw.point((x+7,y+17),fill='#b88655')
    image.save(art.ASSETS/'textures/entity/drone_rust.png')
    can=Image.new('RGBA',(16,16));p=ImageDraw.Draw(can)
    p.rectangle((6,1,9,3),fill='#363138');p.line((7,1,9,1),fill='#ccb59d');p.point((9,2),fill='#211e25')
    p.polygon([(5,3),(10,3),(12,5),(12,13),(10,14),(5,14),(3,13),(3,5)],fill='#32292a');p.rectangle((4,5,11,12),fill='#80543d');p.rectangle((5,6,10,11),fill='#af7950');p.rectangle((4,7,5,11),fill='#cd9866');p.line((4,4,10,4),fill='#cfb9a2');p.line((4,13,10,13),fill='#a19284');p.line((6,7,10,7),fill='#e89322');p.rectangle((7,9,10,10),fill='#615246');p.point((7,9),fill='#d3b499');p.point((10,10),fill='#ccae94');can.save(art.ASSETS/'textures/item/paint_can_rust.png')

if __name__=='__main__':
    rust_paint()
    for name,paint,design,height in [('owl','arctic',owl,4.2),('bedrock','crimson',bedrock,7.9),('scrap','rust',scrap,5.5)]:
        chassis(name,paint,design,height)
