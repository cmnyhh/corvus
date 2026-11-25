"""Build visible interface surrounds on the parents of the five attachment bones."""
from pathlib import Path

out = Path(__file__).resolve().parent
source = (out.parent / 'refinement_v6/build.py').read_text()
helpers = source[:source.index("strip('hip',lambda")].replace("out.parent / 'refinement_v5'", "out.parent / 'refinement_v6'").replace("base / 'staging_material_uv.json'", "out.parent / 'refinement_v3/staging_material_uv.json'")
exec(compile(helpers, 'v6_model_helpers', 'exec'))

def pivot(name):
    return np.array(groups[name]['origin'],dtype=float)

for side in ('l','r'):
    wrist = 'arm_'+side+'_fore'
    center = pivot('mount_arm_'+side)
    strip(wrist, lambda n:n.startswith('interface_') or n=='status')
    def wrist_part(label, offset, size, material, roll=0):
        add(wrist,'wrist port '+label,center+offset,size,(0,0,roll),material)
    wrist_part('well',(0,0,.20),(2.72,2.72,.30),'carbon')
    for i in range(8):
        angle=math.radians(i*45); direction=np.array((math.sin(angle),math.cos(angle),0));roll=-45*i
        wrist_part(f'gasket {i}',direction*1.39+[0,0,-.09],(1.16,.36,.39),'graphite',roll)
        wrist_part(f'jaw {i}',direction*1.45+[0,0,-.34],(.94,.42,.47),'pearl' if i%2==0 else 'steel',roll)
        wrist_part(f'tip {i}',direction*1.22+[0,0,-.44],(.49,.18,.24),'joint',roll)
    wrist_part('witness',(0,1.44,-.60),(.47,.20,.12),'mount_light')
    shoulder = 'shoulder_'+side
    center = pivot('mount_shoulder_'+side)
    strip(shoulder,lambda n:n.startswith('empty socket face_') or n=='pilot lamp')
    def deck(label,offset,size,material,yaw=0):
        add(shoulder,'shoulder receiver '+label,center+offset,size,(0,yaw,0),material)
    deck('floor',(0,.08,0),(3.33,.25,4.33),'carbon')
    for hand in (-1,1):
        deck(f'cheek {hand}',(hand*1.55,.30,.05),(.48,.56,3.8),'shoulder_edge')
        deck(f'guide {hand}',(hand*1.20,.36,0),(.22,.32,2.83),'steel')
        deck(f'lead {hand}',(hand*1.46,.28,-1.91),(.54,.48,.86),'pearl',hand*29)
        deck(f'heel {hand}',(hand*.92,.42,1.82),(1.24,.76,.62),'graphite')
        for j in (-1,1):deck(f'shoe {hand} {j}',(hand*1.57,.65,j*.96),(.51,.20,.48),'aluminium')
    deck('entry light',(0,.26,-1.83),(.86,.11,.24),'mount_light')
center=pivot('mount_back')
strip('torso',lambda n:n.startswith('back guide ') or n in ('back lamp','back rail bed'))
def rear(label,offset,size,material,roll=0):add('torso','dorsal receiver '+label,center+offset,size,(0,0,roll),material)
rear('well',(0,0,-.67),(4.25,7.55,.56),'carbon')
for hand in (-1,1):
    rear(f'rail {hand}',(hand*1.61,.1,-.10),(.59,6.72,.82),'steel')
    rear(f'runner {hand}',(hand*1.45,.10,.34),(.31,5.44,.28),'graphite')
    rear(f'funnel {hand}',(hand*1.86,3.37,.03),(.66,1.19,.76),'pearl',-hand*21)
    rear(f'stop {hand}',(hand*1.38,-3.08,.31),(.98,.73,.69),'pearl')
    for j in range(3):rear(f'clamp {hand} {j}',(hand*1.97,-1.88+1.79*j,.15),(.52,.59,.60),'face')
for j in range(4):rear(f'datum {j}',(0,-1.78+j*1.10,-.29),(1.37,.21,.18),'face')
rear('lamp',(0,-3.07,-.21),(.80,.28,.26),'mount_light')
for name in bones:
    if name.startswith('mount_'):
        assert groups[name]['children']==[] and bones[name]['cubes']==[]
        assert groups[name]['origin']==pivot(name).tolist()
finish=source[source.index("model['elements']="):].replace("'Corvus VI'","'Corvus VII'")
exec(compile(finish,'v6_serialization','exec'))
