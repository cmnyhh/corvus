"""Generate the unarmed workshop frame from original geometry. No input model or atlas."""
import base64, io, itertools, json, math, uuid
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw
P=Path(__file__).resolve().parent
files=['checkpoint.bbmodel','dimensions.json','staging_corvus.geo.json','staging_corvus.png','staging_material_uv.json']
assert not any((P/n).exists() for n in files), 'Refusing to overwrite existing asset'
F=('north','south','east','west','up','down'); V=lambda v:np.array(v,dtype=float)
U=lambda n:str(uuid.uuid5(uuid.NAMESPACE_URL,'workshop/tulip-v1/'+n))
# Each painted panel has its own padded atlas tile, with a dedicated repaintable shoulder strip.
at=Image.new('RGBA',(128,128),'#30323b'); d=ImageDraw.Draw(at); tiles={}; colors={}
def tile(k,x,y,w,h,c,detail=False):
 tiles[k]=[x,y,w,h];colors[k]=c; d.rectangle((x,y,x+w-1,y+h-1), fill=c)
 if detail:
  rgb=at.getpixel((x,y))[:3]; tint=lambda v:tuple(int(min(255,max(0,i+v))) for i in rgb)
  d.line([(x+2,y+3),(x+w-7,y+3),(x+w-3,y+7)],fill=tint(-24));d.line([(x+3,y+h-4),(x+6,y+h-2),(x+w-5,y+h-2)],fill=tint(-25))
  for a,b in [(3,5),(w-5,h-5)]:d.rectangle((x+a,y+b,x+a+1,y+b+1),fill=tint(-68))
  d.line((x+6,y+h-7,x+11,y+h-7),fill=tint(25));d.line((x+7,y+h-6,x+10,y+h-6),fill=tint(-25))
for i,(k,c) in enumerate({'shell':'#d2d1d6','chalk':'#eeeaeb','pearl':'#aaa8b2','graphite':'#494953','face':'#595a66','joint':'#292a31','carbon':'#191b23','steel':'#777780','aluminium':'#aeacb7','pad':'#3a3d4b','belt':'#ad875f','amber':'#bd825d','lining':'#323440','blue':'#5e657a'}.items()):tile(k,(i%8)*8,(i//8)*8,8,8,c)
for i,(k,c) in enumerate({'core_light':'#a49aff','cab_light':'#eee8ff','foot_light':'#a994d8','mount_light':'#696278'}.items()):tile(k,i*6,20,4,4,c)
tile('glass',32,20,12,8,(167,186,207,62));tile('stripe',48,20,16,4,'#bd85ff');tile('shoulder_shell',64,0,32,20,'#ddd9dc',True);tile('shoulder_edge',96,0,32,20,'#bfbdc6',True)
for i,(k,c) in enumerate([('chest_l','#51515c'),('chest_r','#51515c'),('pelvic','#66636c'),('dorsal','#777580'),('leg_skin','#d6d3d6'),('arm_skin','#b6b6c0'),('foot_skin','#888995'),('seatpanel','#333542'),('sideplate','#686973'),('hatch','#b6b3bb'),('shutter','#a19fa9'),('exhaust','#585861')]):tile(k,(i%4)*32,32+(i//4)*30,32,28,c,True)
G={}; bones={}; parts=[]; lookup={}; coords={}
def bone(n,p,xyz):
 g={'name':n,'origin':list(xyz),'rotation':[0,0,0],'uuid':U('b/'+n),'children':[],'export':True,'isOpen':False};G[n]=g;coords[n]=V(xyz)
 if p:G[p]['children'].append(g)
 bones[n]={'name':n,'pivot':[-xyz[0],xyz[1],xyz[2]],**({'parent':p} if p else {}),'cubes':[]}
def box(b,n,p,s,m,r=(0,0,0),paint=None):
 p=V(p);s=V(s)
 if b.startswith(('arm_', 'shoulder_')):
  side=1 if b.endswith('_l') or '_l_' in b else -1
  p[0]-=side*1.55
  if b.startswith('shoulder_') and n in ('saddle','leading brow'):
   s=s*V((.87,.76,.93))
  if n=='chamfer cheek':p[0]-=side*.45;s=s*V((.82,.84,.9))
 if b=='torso' and n=='keel':s=V((6.4,1.65,6.4));p[1]+=.35
 if b=='torso' and n=='aft pressure wall':s=V((10.65,7.25,2.65))
 if b=='torso' and n.startswith(('cheek ','inlet ','running stripe ')):
  side=np.sign(p[0]);angle=-side*21;ax=side*4.0;az=-4.1
  dx,dz=p[0]-ax,p[2]-az;cs,sn=np.cos(np.radians(angle)),np.sin(np.radians(angle))
  p[0],p[2]=ax+cs*dx+sn*dz,az-sn*dx+cs*dz+.65
  p[1]+=.55;r=(r[0],angle+r[1],side*11)
  if n.startswith('cheek '):s=s*V((.87,.72,.89));p[2]+=.35
  elif n.startswith(('inlet ','running stripe ')):p[2]+=1.0
 lo=p-s/2;hi=p+s/2; faces={};uvgeo={};eid=U(b+'/'+n);assert eid not in lookup
 for f in F:
  k=(paint or {}).get(f,m);x,y,w,h=tiles[k];a,c=x+.25,y+.25;z,t=x+w-.25,y+h-.25
  faces[f]={'uv':[a,c,z,t],'texture':0};uvgeo[f]={'uv':[z,t] if f in ['up','down'] else [a,c],'uv_size':[a-z,c-t] if f in ['up','down'] else [z-a,t-c]}
 e={'name':n,'uuid':eid,'type':'cube','box_uv':False,'from':lo.tolist(),'to':hi.tolist(),'origin':p.tolist(),'rotation':list(r),'faces':faces,'visibility':True,'rescale':False};parts.append(e);lookup[eid]=b;G[b]['children'].append(eid)
 bones[b]['cubes'].append({'origin':[-hi[0],lo[1],lo[2]],'size':s.tolist(),'pivot':[-p[0],p[1],p[2]],'rotation':[-r[0],-r[1],r[2]],'uv':uvgeo})
def link(b,n,a,c,w,h,m,paint=None):
 a=V(a);c=V(c);v=c-a;L=np.linalg.norm(v);box(b,n,(a+c)/2,(w,L,h),m,(math.degrees(math.asin(v[2]/L)),0,math.degrees(math.atan2(-v[0],v[1]))),paint)
def ring(b,n,p,r,th,depth,m,plane='xy',N=10):
 for i in range(N):
  a=i*360/N;sn=math.sin(math.radians(a));cs=math.cos(math.radians(a));w=2*(r+th/2)*math.tan(math.pi/N);delta,size,rot={'xy':((r*sn,r*cs,0),(w,th,depth),(0,0,-a)),'yz':((0,r*cs,r*sn),(depth,th,w),(a,0,0)),'xz':((r*sn,0,r*cs),(w,depth,th),(0,a,0))}[plane];box(b,n+'_'+str(i),V(p)+delta,size,m,rot)
bone('root',None,[0,0,0]);bone('hip','root',[0,23,1]);bone('torso','hip',[0,27,1]);bone('cockpit_glass','torso',[0,35.65,-5.7]);bone('core_ring','torso',[0,31.75,-5]);bone('core_socket','core_ring',[0,31.75,-3]);bone('antenna','torso',[-3.85,38.75,2.8]);bone('mount_back','torso',[0,32,7.35]);
for sign,side in [(1,'l'),(-1,'r')]:
 for n,par,p in [('shoulder_'+side,'torso',[9.1,35.3,.5]),('arm_'+side,'shoulder_'+side,[10.8,34.9,.65]),('arm_'+side+'_fore','arm_'+side,[13,28.35,1.7]),('mount_arm_'+side,'arm_'+side+'_fore',[13.65,23,-3.85]),('mount_shoulder_'+side,'shoulder_'+side,[11.55,38.95,.8]),('leg_'+side+'_upper','hip',[5.1,22.4,.65]),('leg_'+side+'_lower','leg_'+side+'_upper',[7.65,13.05,-2.6]),('foot_'+side,'leg_'+side+'_lower',[8.35,3.05,.95]),('thruster_'+side,'torso',[6.25,30.6,5.15])]:bone(n,par,[sign*p[0],p[1],p[2]])
# Midbody is built as two fairings, a keel and an aft bulkhead. No solid occupies either aperture.
box('hip','bridge',(0,22.9,1),(12.7,2.9,5.4),'joint');box('hip','apron',(0,22.6,-1.35),(4.65,4.8,3.5),'graphite',(14,0,0),{'north':'pelvic'});box('hip','turntable',(0,25.7,1),(6.2,2.4,5),'joint');box('hip','collar',(0,26.2,1),(6.55,.48,5.35),'steel');box('torso','keel',(0,27.7,.1),(11.8,2.25,7.8),'graphite',(-4,0,0));box('torso','aft pressure wall',(0,32,3.55),(13.1,8.3,2.9),'face');box('torso','socket stop',(0,31.75,-.35),(6.45,6.45,.4),'carbon');box('torso','cockpit floor',(0,35.2,.0),(7.1,.5,7.65),'lining');box('torso','seat cushion',(0,35.9,.65),(3.8,.75,2.85),'pad');box('torso','reclined seat',(0,37.05,2.15),(3.5,2.9,.65),'pad',(12,0,0),{'north':'seatpanel'});box('torso','head pad',(0,38.62,2.55),(2.65,.8,.72),'pearl',(12,0,0));box('torso','cockpit aft wall',(0,37.1,3.5),(7.7,4.2,.6),'graphite');
for i,(radius,th,dpt,z,m) in enumerate([(3.0,.55,4.6,-2.95,'lining'),(3.26,.65,.72,-5.05,'steel'),(2.83,.22,.24,-5.52,'core_light')]):ring('core_ring','sleeve'+str(i),(0,31.75,z),radius,th,dpt,m,N=10)
for sg,side in [(1,'l'),(-1,'r')]:
 X=lambda x:sg*x; sh='shoulder_'+side; ar='arm_'+side;fo=ar+'_fore';th='leg_'+side+'_upper';kn='leg_'+side+'_lower';ft='foot_'+side;ex='thruster_'+side
 box('torso','cheek '+side,(X(6.35),32.6,-.55),(4.8,7.7,9.45),'graphite',(5,sg*9,-sg*9),{'north':'chest_'+side,'up':'sideplate'});box('torso','inlet '+side,(X(6.7),33.25,-5.45),(2.9,2.25,.65),'carbon',(5,sg*9,-sg*9));
 for i in range(5):box('torso','inlet fin '+side+str(i),(X(5.75+i*.46),33.23,-5.83+.07*i),(.12,1.7,.17),'steel',(5,sg*9,-sg*9))
 box('torso','running stripe '+side,(X(6.56),31.48,-5.77),(3.45,.3,.29),'stripe',(5,sg*9,-sg*9));box('torso','parked sliding door '+side,(X(4.45),30.75,-4.97),(1.65,3.65,.42),'pearl',(0,sg*9,-sg*6),{'north':'shutter'});box('torso','slide guide '+side,(X(4.45),29.17,-4.74),(2.45,.45,.59),'joint');box('torso','cockpit lateral tub '+side,(X(3.65),36.35,.1),(.6,2.15,7.0),'graphite');box('torso','armrest '+side,(X(2.17),36.5,.65),(.42,.45,2.15),'steel');box('torso','harness '+side,(X(.92),37.06,1.74),(.34,2.2,.11),'belt',(12,0,sg*6));box('torso','console '+side,(X(2.76),35.96,-1.2),(.78,.65,1.75),'steel',(-18,0,0));
 # Sloped front lightwell with two side panes and a low aft crown.
 b='cockpit_glass';link(b,'pillar '+side,(X(3.63),35.7,-5.87),(X(3.63),39.7,-1.48),.32,.38,'chalk');box(b,'side glass '+side,(X(3.65),38.6,.1),(.13,1.9,3.25),'glass');box(b,'roof edge '+side,(X(3.64),39.66,.55),(.35,.33,4.3),'chalk');box(b,'sill light '+side,(X(3.28),35.68,-6.08),(.48,.25,.18),'cab_light');
 # Fractured white silhouette: saddle, outer cheek, downturned leading edge.
 box(sh,'bearing',(X(9.85),35.05,1),(4.3,4.1,4.4),'joint',(35,0,0));box(sh,'saddle',(X(11.67),36.65,.55),(5.4,3.8,7.5),'shoulder_shell',(5,0,-sg*15));box(sh,'chamfer cheek',(X(13.89),35.25,.6),(1.72,3.1,6.85),'shoulder_edge',(4,0,-sg*22));box(sh,'leading brow',(X(11.9),36.6,-3.16),(4.2,2.9,.57),'shoulder_shell',(5,0,-sg*15));box(sh,'amber index',(X(13.13),36.8,-3.57),(.42,.95,.18),'amber');ring(sh,'empty socket face',(X(11.55),38.55,.8),1.14,.32,.49,'steel','xz',8);box(sh,'pilot lamp',(X(12.91),38.33,-.65),(.35,.19,.4),'mount_light');
 link(ar,'proximal spar',coords[ar],coords[fo],2.8,3.05,'joint');link(ar,'external shroud',(X(11.3),33.95,.35),(X(12.78),29.7,1.23),3.15,3.25,'face',{'north':'arm_skin'});box(fo,'elbow axle',coords[fo],(3.66,2.7,2.7),'joint',(45,0,0));ring(fo,'axle face',(X(14.55),28.35,1.7),1.12,.35,.32,'steel','yz',8);link(fo,'fore fairing',(X(13),27.8,1.4),(X(13.65),23,-1.6),3.55,3.7,'graphite',{'north':'arm_skin'});box(fo,'distal shell',(X(13.65),23,-1.95),(3.35,2.85,2.3),'face');ring(fo,'interface',(X(13.65),23,-3.4),1.19,.39,.6,'steel','xy',8);box(fo,'status',(X(13.65),24.35,-3.21),(.42,.18,.18),'mount_light');
 a=coords[th];k=coords[kn];f=coords[ft];h=V((X(8.15),7.25,3.05));link(th,'femur',a,k,3.5,3.85,'joint');link(th,'thigh shell',(X(5.93),20.85,.1),(X(7.52),14.0,-2.65),4.5,4.85,'pearl',{'north':'leg_skin','east':'leg_skin','west':'leg_skin'});link(th,'proximal cover',(X(5.65),21.4,.7),(X(6.25),19.25,-.2),4.8,5.1,'graphite');box(kn,'knee axle',k,(4.6,2.85,2.85),'joint',(45,0,0));ring(kn,'knee bearing',(X(9.91),13.05,-2.6),1.35,.46,.47,'steel','yz',10);box(th,'knee apron',(X(7.55),14.75,-4.83),(3.85,3.05,1.3),'chalk',(17,0,-sg*5),{'north':'leg_skin'});link(kn,'tibia',k,h,3.5,3.3,'graphite',{'north':'sideplate'});link(kn,'brake rod',(X(9.72),12.45,-2.55),(X(10.05),7.4,2.7),.49,.52,'aluminium');link(kn,'hock',h,f,2.8,2.7,'joint');box(kn,'distal shroud',(X(8.25),6.75,2.72),(3.92,3.3,3.8),'pearl',(25,0,0),{'north':'leg_skin'});box(ft,'plantar pad',(X(8.35),.83,.85),(5.3,1.66,5.4),'joint');box(ft,'instep',(X(8.35),1.88,.2),(4.85,2.0,4.65),'face',(-10,0,0),{'north':'foot_skin'});
 for j in [-1,0,1]:
  box(ft,'split digit '+str(j),(X(8.35+j*1.79),.85,-2.96+abs(j)*.55),(1.58,1.47,4.65),'graphite',(-3,-sg*j*11,0),{'up':'foot_skin'});box(ft,'sole outlet '+str(j),(X(8.35+j*1.37),.55,3.61),(.85,.24,.28),'foot_light')
 box(ex,'aft impeller',(X(6.3),31.15,4.8),(3.68,5.5,3.6),'graphite',(-12,0,0),{'south':'exhaust'});box(ex,'aft plenum',(X(6.3),31,6.55),(2.89,3.85,.61),'carbon');
 for j in range(4):box(ex,'heat fin '+str(j),(X(6.3),29.85+j*.74,6.99),(2.72,.26,.65),'steel',(-21,0,0))
 box('torso','back guide '+side,(X(1.82),32,6.77),(.6,7.05,.83),'steel');box('torso','spine shroud '+side,(X(3.32),32,5.65),(1.8,7.7,1.52),'pearl',(-4,0,0),{'south':'dorsal'})
box('torso','back rail bed',(0,32,5.69),(4.85,7.25,1.35),'joint');box('torso','back lamp',(0,29.55,6.47),(.6,.22,.22),'mount_light');box('cockpit_glass','windscreen',(0,37.7,-3.675),(6.9,5.92,.13),'glass',(47.67,0,0));box('cockpit_glass','windscreen sill',(0,35.70,-5.90),(7.55,.4,.45),'chalk');box('cockpit_glass','windscreen brow',(0,39.7,-1.48),(7.55,.36,.43),'chalk');box('cockpit_glass','crown',(0,39.66,.55),(6.97,.2,3.7),'pearl',paint={'up':'hatch'});box('antenna','pedestal',(-3.85,38.72,2.8),(.75,.75,.75),'joint');link('antenna','mast',(-3.85,38.8,2.8),(-4.25,42.25,3.9),.2,.24,'steel');
# Recenter rig pivots with the narrower shoulder and elbow assemblies.
for name in G:
 if name.startswith(('arm_', 'shoulder_', 'mount_arm_', 'mount_shoulder_')):
  dx=-1.55 if name.endswith('_l') or '_l_' in name else 1.55
  G[name]['origin'][0]+=dx;coords[name][0]+=dx;bones[name]['pivot'][0]-=dx
for sg,side in [(1,'l'),(-1,'r')]:
 link('torso','lower diagonal chine '+side,(sg*2.05,27.5,-3.25),(sg*6.75,30.35,-1.4),1.22,2.8,'face',{'north':'sideplate'})
 link('torso','chine binding '+side,(sg*2.0,27.48,-4.57),(sg*6.55,30.28,-2.77),.31,.26,'steel')
 box('shoulder_'+side,'rear overlap '+side,(sg*11.55,37.98,1.64),(4.38,.43,3.7),'shoulder_edge',(5,0,-sg*15))
# Measurements include all transformed corners; all rest-bone rotation values are zero.
def matrix(r):
 a,b,c=np.radians(r);ca,cb,cc=np.cos([a,b,c]);sa,sb,sc=np.sin([a,b,c]);return np.array([[cb*cc,sa*sb*cc-ca*sc,ca*sb*cc+sa*sc],[cb*sc,sa*sb*sc+ca*cc,ca*sb*sc-sa*cc],[-sb,sa*cb,ca*cb]])
def vertices(e):
 p=V(e['origin']);return (V(list(itertools.product(*zip(e['from'],e['to']))))-p)@matrix(e['rotation']).T+p
allv=np.concatenate([vertices(e) for e in parts]);lo,hi=allv.min(0),allv.max(0);w,h,dep=hi-lo;hipmin=min(vertices(e)[:,1].min() for e in parts if lookup[e['uuid']]=='hip'); chain=lambda *ns:sum(np.linalg.norm(coords[x]-coords[y]) for x,y in zip(ns,ns[1:])); measure=lambda a,b:{'u':round(float(a),5),'cells':round(float(a)/16,6),'definition':b};metrics={k:measure(a,b) for k,a,b in [('width',w,'rotated X extent'),('height',h,'rotated Y extent incl. antenna'),('depth',dep,'rotated Z extent incl. toes and heat exchangers'),('ground_clearance',hipmin-lo[1],'lowest hip geometry minus ground plane'),('leg_length',chain('leg_l_upper','leg_l_lower','foot_l'),'hip to knee to ankle pivot along the two bones; same both sides'),('arm_length',chain('arm_l','arm_l_fore','mount_arm_l'),'shoulder to elbow to distal empty interface pivot; same both sides'),('stance_width',chain('foot_l','foot_r'),'distance between foot pivots at ankle centers')]}
# Interior occupancy check: full-length circular gauge of radius 2.45, sampled at 24 azimuths and along Z.
gauge=V([(r*math.cos(a),31.75+r*math.sin(a),z) for r in [0,1.3,2.45] for a in np.arange(24)*math.pi/12 for z in np.linspace(-5.8,-.61,25)]);coll=[]
for e in parts:
 p=V(e['origin']);local=(gauge-p)@matrix(e['rotation'])+p
 if np.any(np.all((local>e['from'])&(local<e['to']),axis=1)):coll.append(e['name'])
assert not coll,coll
buf=io.BytesIO();at.save(buf,'PNG');png=buf.getvalue();source='data:image/png;base64,'+base64.b64encode(png).decode();model={'meta':{'format_version':'4.10','model_format':'bedrock','box_uv':False},'name':'Unarmed service frame','model_identifier':'service_frame','resolution':{'width':128,'height':128},'elements':parts,'outliner':[G['root']],'textures':[{'id':'0','uuid':U('atlas'),'name':files[3],'relative_path':files[3],'source':source,'internal':True,'mode':'bitmap','width':128,'height':128,'uv_width':128,'uv_height':128,'render_mode':'default'}]};geo={'format_version':'1.12.0','minecraft:geometry':[{'description':{'identifier':'geometry.service_frame','texture_width':128,'texture_height':128,'visible_bounds_width':3.25,'visible_bounds_height':3.25,'visible_bounds_offset':[0,1.3,0]},'bones':list(bones.values())}]};dim={'units_per_cell':16,'basis':'Only user image SHA-256 67943adbfe93a50f2e5f7684d2bd9b1955731c075557d3250a29c75bcdcba21a. No source models, atlases or dimensions used.','axes':'Blockbench editor: Y up, front -Z; Bedrock X is mirrored.','measurements':metrics,'bounds_editor':{'min':lo.tolist(),'max':hi.tolist()},'core_clearance':{'axis':'Z','center_xy':[0,31.75],'clear_radius_u':2.45,'clear_z_range_u':[-5.8,-.61],'sample_points':len(gauge),'obstruction_count':0,'rear_stop_front_z_u':-.55},'counts':{'bones':len(G),'cubes':len(parts),'empty_mount_leaves':5},'inferred':'Rear rail, exhausts, cockpit internals and unarmed attachment surfaces inferred; reference only shows front three-quarter view.'};mat={'texture':files[3],'texture_size':[128,128],'coordinates':'atlas pixels from upper-left; xywh','uv_regions_xywh':tiles,'color_values':colors,'glass_only_translucent_region':'glass','four_exclusive_4x4_tiles':['core_light','cab_light','foot_light','mount_light'],'recolorable':['stripe','shoulder_shell','shoulder_edge'],'materials':'One RGBA atlas, opaque except glass; separate glow masks reserved, not a shader.'}
for n,data in zip(files,[model,dim,geo,png,mat]):
 with (P/n).open('xb') as f:f.write(data if isinstance(data,bytes) else json.dumps(data,indent=2 if n in [files[1],files[4]] else None,separators=None if n in [files[1],files[4]] else (',',':')).encode())
print(json.dumps({'bones':len(G),'cubes':len(parts),'gauge':len(gauge),'measurements':metrics},indent=2))
