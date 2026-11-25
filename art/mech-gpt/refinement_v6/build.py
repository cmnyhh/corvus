"""Refit the v5 shell panels, pelvic guard and contact pads in editor space."""
import itertools
import json
import math
import shutil
import uuid
from copy import deepcopy
from pathlib import Path
import numpy as np

out = Path(__file__).resolve().parent
base = out.parent / 'refinement_v5'
def load(path):
    return json.loads(path.read_bytes())
def save(name, value):
    (out / name).write_text(json.dumps(value, ensure_ascii=False, separators=(',', ':')))
model = load(base / 'checkpoint.bbmodel')
geometry = load(base / 'export/geo/corvus.geo.json')
dims = load(base / 'dimensions.json')
materials = load(base / 'staging_material_uv.json')['uv_regions_xywh']
elements = {e['uuid']: e for e in model['elements']}
bones = {b['name']: b for b in geometry['minecraft:geometry'][0]['bones']}
groups = {}
def visit(group):
    groups[group['name']] = group
    for child in group['children']:
        if isinstance(child, dict):
            visit(child)
visit(model['outliner'][0])

def add(bone, name, center, size, angles, material):
    p = np.array(center); half = np.array(size) / 2
    lo, hi = p-half, p+half
    uid = str(uuid.uuid5(uuid.NAMESPACE_URL, f'corvus-v6/{bone}/{name}'))
    assert uid not in elements
    x,y,w,h = materials[material]; l,t,r,b = x+.25,y+.25,x+w-.25,y+h-.25
    faces = ['north','south','east','west','up','down']
    elements[uid] = {'uuid':uid,'type':'cube','name':name,'box_uv':False,'from':lo.tolist(),'to':hi.tolist(),'origin':p.tolist(),'rotation':list(angles),'faces':{f:{'uv':[l,t,r,b],'texture':0} for f in faces},'visibility':True,'rescale':False}
    groups[bone]['children'].append(uid)
    bones[bone]['cubes'].append({'origin':[-hi[0],lo[1],lo[2]],'size':list(size),'pivot':[-p[0],p[1],p[2]],'rotation':[-angles[0],-angles[1],angles[2]],'uv':{f:({'uv':[r,b],'uv_size':[l-r,t-b]} if f in ['up','down'] else {'uv':[l,t],'uv_size':[r-l,b-t]}) for f in faces}})

def strip(bone, pred):
    group = groups[bone]; kept=[]; cubes=[]; records=iter(bones[bone]['cubes'])
    for child in group['children']:
        if isinstance(child, dict):
            kept.append(child); continue
        rec=next(records); elem=elements[child]
        if pred(elem['name']):
            del elements[child]
        else:
            kept.append(child); cubes.append(rec)
    group['children']=kept; bones[bone]['cubes']=cubes

def blade(bone,name,a,b,width,depth,material):
    a,b=np.array(a),np.array(b); delta=b-a; length=np.linalg.norm(delta)
    rot=[math.degrees(math.asin(delta[2]/length)),0,math.degrees(math.atan2(-delta[0],delta[1]))]
    add(bone,name,(a+b)/2,(width,length,depth),rot,material)

strip('hip',lambda n:n=='apron')
add('hip','recessed pelvic yoke',(0,18.5,-1.15),(4.5,2.65,2.05),(10,0,0),'joint')
for sign,side in [(1,'l'),(-1,'r')]:
    def pelvic(n,c,s,r,m): add('hip',side+' '+n,(sign*c[0],c[1],c[2]),s,(r[0],sign*r[1],sign*r[2]),m)
    pelvic('upper hip brow',(1.65,19.55,-2.48),(3.08,.91,1.12),(9,-14,16),'graphite')
    blade('hip',side+' swept guard',(sign*.55,16.63,-3.18),(sign*2.07,19.49,-2.24),1.54,.90,'pearl')
    blade('hip',side+' inset bevel',(sign*.54,16.70,-3.64),(sign*1.94,19.34,-2.72),.57,.15,'face')
    pelvic('latch',(1.82,19.18,-2.95),(.59,.33,.21),(10,-14,16),'aluminium')
    foot='foot_'+side
    strip(foot,lambda n:n.startswith(('split digit','toe ','digit hinge','instep','plantar pad','heel skid')))
    def sole(n,x,y,z,size,rot,mat):add(foot,n,(sign*(8.35+x),y,z),size,(rot[0],sign*rot[1],sign*rot[2]),mat)
    sole('heel contact',0,.49,1.56,(3.97,.98,3.69),(0,0,0),'carbon')
    sole('heel saddle',0,1.41,1.05,(3.81,1.18,3.42),(-8,0,0),'graphite')
    for j in (-1,1):
        sole('heel retainer '+str(j),j*1.92,1.46,1.30,(.48,1.21,3.02),(-12,0,-j*14),'pearl')
    for j in (-1,0,1):
        ztip=-6.23+abs(j)*1.03
        sole(f'{j} toe knuckle',j*1.41,1.48,-.84,(1.48,1.38,1.60),(15,-j*13,0),'steel')
        sole(f'{j} toe hinge cap',j*1.46,2.10,-.89,(1.13,.52,1.01),(13,-j*13,0),'face')
        for i in range(3):
            z=-1.64+(ztip+1.64)*i/2; spread=j*(1.58+.42*i); yaw=-j*(12+i*2)
            sole(f'{j} tread {i}',spread,.32,z,(1.45-i*.19,.64,2.26-i*.25),(0,yaw,0),'carbon')
            sole(f'{j} metatarsal scale {i}',spread,1.12-i*.15,z,(1.50-i*.26,.76-i*.10,2.32-i*.22),(-13,yaw,j*6),'shell' if i==0 else 'pearl')
            sole(f'{j} toe ridge {i}',spread,1.56-i*.18,z+.05,(.45-i*.08,.22,1.83-i*.24),(-13,yaw,j*6),'graphite')
    for bone,start,end,radius in [('leg_'+side+'_upper',(5.1,17.9,.65),(7.65,11.95,-7.05),2.13),('leg_'+side+'_lower',(7.65,11.95,-7.05),(8.15,6.8,5.1),1.66),('arm_'+side+'_fore',(11.45,23,.7),(12.1,16.3,-2.95),1.45)]:
        # Remove three middle facets and their fittings. Replace with two intersecting planes.
        def obsolete(n):
            fields=n.split(); head=fields[0]; tail=fields[-1]
            if head in ('femoral','tibial') and len(fields)>2:
                return 'recessed spine' in n or ('-' in tail and tail.rsplit('-',1)[1] in ('2','3','4'))
            if head=='gauntlet' and '.' in tail:return tail.split('.')[-1] in ('0','1','2')
            return False
        strip(bone,obsolete)
        start=np.array(start)*[sign,1,1]; end=np.array(end)*[sign,1,1]; axis=end-start; u=axis/np.linalg.norm(axis)
        lat=np.array([1.,0.,0.]); lat-=u*np.dot(u,lat);lat/=np.linalg.norm(lat); front=np.cross(lat,u); front*=1 if front[2]<0 else -1
        for row,(low,high,scale) in enumerate([(.12,.52,1),(.50,.85,.76)]):
            mid=start+axis*(low+high)/2; width=radius*scale; length=np.linalg.norm(axis)*(high-low)
            for hand in (-1,1):
                n=front*.82+lat*hand*.572; n/=np.linalg.norm(n); tan=np.cross(u,n); mat=np.column_stack((tan,u,n)); angles=np.degrees([math.atan2(mat[2,1],mat[2,2]),math.asin(np.clip(-mat[2,0],-1,1)),math.atan2(mat[1,0],mat[0,0])]).tolist();c=mid+front*(radius*scale*.93)+hand*lat*width*.53
                add(bone,f'folded shell {row} {hand}',c,(width*1.38,length,.41),angles,'shell' if hand*sign>0 else 'pearl')
                add(bone,f'fold lip {row} {hand}',c+n*.24+u*length*.36,(width*1.04,.20,.13),angles,'steel')
            blade(bone,f'longitudinal crest {row}',mid-u*length*.48+front*width*1.31,mid+u*length*.48+front*width*1.31,.30,.39,'graphite')

model['elements']=list(elements.values());model['name']='Corvus VI';save('checkpoint.bbmodel',model);save('export/geo/corvus.geo.json',geometry)
shutil.copyfile(base/'export/textures/entity/mecha.png',out/'export/textures/entity/mecha.png');shutil.copyfile(base/'staging_corvus.png',out/'staging_corvus.png')
verts=[]
for elem in elements.values():
    a,b,c=np.radians(elem['rotation']); ca,cb,cc=np.cos([a,b,c]);sa,sb,sc=np.sin([a,b,c]); rot=np.array([[cb*cc,sa*sb*cc-ca*sc,ca*sb*cc+sa*sc],[cb*sc,sa*sb*sc+ca*cc,ca*sb*sc-sa*cc],[-sb,sa*cb,ca*cb]])
    p=np.array(elem['origin']);verts.extend((np.array(list(itertools.product(*zip(elem['from'],elem['to']))))-p)@rot.T+p)
lo=np.min(verts,axis=0);hi=np.max(verts,axis=0);dims['bounds_editor']={'min':lo.tolist(),'max':hi.tolist()}
for prop,v in zip(['width','height','depth'],hi-lo):dims['measurements'][prop].update(u=round(v,5),cells=round(v/16,6))
hip_ids=set(c for c in groups['hip']['children'] if isinstance(c,str));hip_lo=min(min(v[1] for v in verts[idx*8:idx*8+8]) for idx,e in enumerate(elements.values()) if e['uuid'] in hip_ids);v=hip_lo-lo[1];dims['measurements']['ground_clearance'].update(u=round(v,5),cells=round(v/16,6))
dims['counts']['cubes']=len(elements);save('dimensions.json',dims);print(dims['counts'],dims['bounds_editor'])
