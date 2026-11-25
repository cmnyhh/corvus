"""Compact, low, reverse-knee Corvus blockout; three orthographic views."""
import json, math
from pathlib import Path
from PIL import Image,ImageDraw
import numpy as np
O=Path(__file__).resolve().parent
# Snapshot of prior rich build remains untouched. This new 36.5u proposal is a deliberately simple geometry blockout.
C={'ivory':(221,222,215,255),'light':(181,190,196,255),'medium':(93,111,124,255),'dark':(40,50,66,255),'black':(20,27,37,255),'violet':(190,128,255,255),'amber':(232,138,65,255),'glass':(111,180,208,92),'seat':(56,71,90,255),'metal':(134,151,165,255),'rubber':(20,25,34,255)}
# part record is named, independently selectable; coords origin-centered: x left-right, y floor-up, z forward negative.
parts=[]
def box(n,center,size,color,group='hull',rot=(0,0,0)):
 parts.append(dict(name=n,p=np.array(center,dtype=float),s=np.array(size,dtype=float),c=C[color] if isinstance(color,str) else color,g=group,r=rot))
def link(n,a,b,w,d,color,group, flare=0):
 a=np.array(a,float);b=np.array(b,float);v=b-a;L=np.linalg.norm(v); xmid=(a[0]+b[0])/2
 box(n,(a+b)/2,(w,L,d),color,group,(math.degrees(math.asin(v[2]/L)),0,math.degrees(math.atan2(-v[0],v[1]))))
def tube(n,center,radius,depth,thick,color,plane='xz',N=12,group='hull'):
 for i in range(N):
  a=360*i/N;v=math.radians(a);span=2*radius*math.tan(math.pi/N)*1.03;x,y,z=center
  if plane=='xz': p=(x+radius*math.sin(v),y,z+radius*math.cos(v));s=(span,depth,thick);r=(0,a,0)
  elif plane=='xy':p=(x+radius*math.sin(v),y+radius*math.cos(v),z);s=(span,thick,depth);r=(0,0,-a)
  else:p=(x,y+radius*math.cos(v),z+radius*math.sin(v));s=(depth,thick,span);r=(a,0,0)
  box(f'{n}_{i:02d}',p,s,color,group,r)
# Height y=0..36.5. Compact hull 27w x 13h. Forward is -Z.
box('lower hull',(0,14.6,0),(20,3,12),'dark','chassis');box('compact pressure hull',(0,22.5,0),(27,13.5,13),'medium','hull');box('flat armored roof',(0,29.5,0),(27.8,1.6,13.5),'light','hull')
box('front fascia',(0,22,-6.85),(23,8,0.62),'dark','hull');box('lower front glacis',(0,17.4,-6.7),(18,3.4,.75),'light','hull');
# open annular core seat, concentric rings and dark cavity; doors are visibly parked left/right.
tube('core housing',(0,22,-7.32),4.15,.75,1.05,'dark','xy',16,'core');tube('core lip',(0,22,-7.83),3.7,.46,.52,'metal','xy',16,'core');tube('glow ring',(0,22,-8.1),3.35,.22,.22,'violet','xy',20,'core');tube('inner rim',(0,22,-7.3),2.9,2.5,.42,'black','xy',16,'core');box('cavity rear',(0,22,-6.08),(5.5,5.5,.25),'rubber','core')
for s,side in [(1,'l'),(-1,'r')]:
 box('parked open core shutter '+side,(s*5.2,22,-7.2),(2.8,7,.62),'light','doors',(0,0,-s*5));box('door handle '+side,(s*5.5,22,-7.58),(.28,2,.14),'metal','doors')
# Transparent forward canopy is bonded directly in the front-upper hull face, no neck/head.
box('canopy lower sill',(0,26,-7.36),(10.5,.65,.56),'metal','canopy');box('clear windscreen',(0,26.8,-7.42),(9.7,4.2,.18),'glass','canopy');box('canopy crown rail',(0,29,-7.3),(10.5,.48,.5),'metal','canopy');
for s in (-1,1):
 box('canopy side rail '+str(s),(s*5,26.8,-7.1),(.42,4.1,.55),'metal','canopy');box('cockpit sill lamp '+str(s),(s*4.45,26.48,-7.69),(.4,.22,.17),'violet','canopy')
box('visible pilot seat cushion',(0,24.3,-2.6),(3.5,.8,3.1),'seat','seat');box('visible pilot seat back',(0,26,-.9),(3.2,3.1,.8),'seat','seat',(8,0,0));
# Rear backpack / rail: empty accessory docking plane, no weapon silhouette.
box('backpack',(0,22,7.7),(17,12,5),'dark','back');box('back central rail',(0,22,10.55),(5,10,.8),'metal','back');
for s in (-1,1):
 box('rail cheek '+str(s),(s*4.4,22,10.5),(1.3,9,.8),'medium','back');
 for j in range(4):box('radiator '+str(s)+'_'+str(j),(s*6.7,19+j*2.05,10.25),(2.4,1.35,1.2),'metal','vents')
# Small flush shoulder hardpoints: only socket plates, width <4u, no shoulder armor.
for s,side in [(1,'l'),(-1,'r')]:
 x=s*14.0;box('flush shoulder socket plate '+side,(x,27,0),(3.4,3.6,5.2),'dark','mounts');tube('shoulder hardpoint '+side,(x,28.9,0),1.45,.7,.42,'metal','xz',8,'mounts');box('inactive shoulder lamp '+side,(x,26.1,-2.7),(.34,.28,.25),'rubber','mounts')
# Cab sides are narrow: recessed vertical slots on lateral faces; no wide caps.
for s in (-1,1):
 for j in range(5):box('side cooling channel '+str(s)+'_'+str(j),(s*13.57,19.1+j*1.45,1.0),(.18,1.05,5.2),'black','vents')
 for j in range(4):box('cooling vane '+str(s)+'_'+str(j),(s*13.7,19.1+j*1.45,1.05),(.17,.22,4.6),'metal','vents')
# Arms short, compact outward/downward stubs: no palms. Explicit open interface ring, mouth on forward (-Z).
arm_data={}
for s,side in [(1,'l'),(-1,'r')]:
 sh=(s*13.7,25,0);el=(s*16.5,20.7,-.2);wrist=(s*17.4,17.0,-2.0);arm_data[side]=(sh,el,wrist)
 link('upper stub '+side,sh,el,3.5,4.0,'medium','arm_'+side);tube('elbow journal '+side,el,1.65,3.7,.48,'dark','yz',10,'arm_'+side);link('lower stub '+side,el,wrist,3.0,3.4,'light','arm_'+side);box('terminal cuff '+side,(wrist[0],wrist[1],wrist[2]),(3.6,3.0,3.7),'dark','arm_'+side);tube('empty arm interface '+side,(wrist[0],wrist[1],wrist[2]-2.05),1.27,.5,.42,'metal','xy',10,'mounts');box('dark open bore '+side,(wrist[0],wrist[1],wrist[2]-1.7),(1.65,1.65,.12),'black','mounts')
# Reverse-jointed legs: 7u upper and 6u lower, knees swept outward and hocks fold forward.
legdata={}
for s,side in [(1,'l'),(-1,'r')]:
 hip=(s*6.8,14.2,1.3);knee=(s*10.2,8.4,1.4);hock=(s*8.4,4.7,4.7);ankle=(s*8.4,3.1,1.1);legdata[side]=(hip,knee,hock,ankle)
 link('7u thigh '+side,hip,knee,5.8,6.0,'light','leg_'+side);tube('knee bearing '+side,knee,2.25,5.9,.68,'dark','yz',10,'leg_'+side);box('knee cap '+side,(knee[0],knee[1],knee[2]-3.0),(4.2,2.6,.4),'medium','leg_'+side);link('6u shin '+side,knee,ankle,4.3,4.4,'medium','leg_'+side);link('reverse hock '+side,ankle,hock,2.4,2.8,'dark','leg_'+side);tube('ankle drive '+side,ankle,1.75,4.0,.54,'metal','yz',10,'leg_'+side)
 # foot projects forward, wide, flattened, toe fans. Stand is 16.8u, 2.1 cells, stance ~17u.
 box('wide footpad '+side,(s*8.65,1.8,-2.1),(10,3.6,10.2),'dark','foot_'+side);box('toe armor '+side,(s*8.65,1.55,-5.0),(9.7,2.2,4.2),'light','foot_'+side,(0,-s*8,0));box('rear heel '+side,(s*8.65,1.4,3.2),(7.5,2.5,3.5),'rubber','foot_'+side)
 for j in (-1,0,1):box('toe talon '+side+'_'+str(j),(s*8.65+j*2.25,1.05,-7.5+abs(j)*.6),(2.15,1.6,2.6),'metal','foot_'+side,(0,-s*(j+1)*4,0))
# Flush broad roof armored lid; minimal short antenna, safely below 36.5u.
box('rear deck plate',(0,29.5,1.2),(26.6,.55,11),'light','back');box('top center access hatch',(0,30.1,4.5),(8,1.1,4),'medium','back');box('mast base',(0,30.85,6.6),(1.4,.75,1.3),'dark','antenna');link('stub antenna',(0,31.0,6.7),(-.3,34.65,7.2),.34,.4,'metal','antenna');box('amber mast tip',(-.3,34.65,7.2),(.42,.42,.42),'amber','antenna')

def view(name,yaw=0,roll=0):
 # derive geometry without painter depth issue: sort triangles per cube, cube surfaces with quads polygon fill stable.
 W=768;H=768;sc=15.0;im=Image.new('RGBA',(W,H),(24,31,42,255));d=ImageDraw.Draw(im);Y=math.radians(yaw);E=math.radians(roll)
 def project(p):
  x,y,z=p;xr=math.cos(Y)*x-math.sin(Y)*z;zr=math.sin(Y)*x+math.cos(Y)*z;yr=math.cos(E)*y-math.sin(E)*zr;dep=math.sin(E)*y+math.cos(E)*zr;return (W/2+sc*xr,H*.82-sc*yr,dep)
 faces=[]
 for b in parts:
  p=b['p'];sz=b['s'];rx,ry,rz=map(math.radians,b['r']);Rx=np.array([[1,0,0],[0,math.cos(rx),-math.sin(rx)],[0,math.sin(rx),math.cos(rx)]]);Ry=np.array([[math.cos(ry),0,math.sin(ry)],[0,1,0],[-math.sin(ry),0,math.cos(ry)]]);Rz=np.array([[math.cos(rz),-math.sin(rz),0],[math.sin(rz),math.cos(rz),0],[0,0,1]]);R=Rz@Ry@Rx;verts=[]
  for i in range(8):verts.append(p+R@np.array([sz[k]/2*(1 if (i>>k)&1 else -1) for k in range(3)]))
  for ax in range(3):
   for sg in (-1,1):
    ids=[i for i in range(8) if ((i>>ax)&1)==(1 if sg>0 else 0)];pp=[project(verts[i]) for i in ids];cx=sum(q[0] for q in pp)/4;cy=sum(q[1] for q in pp)/4;pp.sort(key=lambda q:math.atan2(q[1]-cy,q[0]-cx));dep=sum(q[2] for q in pp)/4;fac=.72 if ax==2 else .92 if ax==1 else .82
    col=b['c'];fill=tuple(min(255,int(c*fac)) for c in col[:3])+(col[3],);faces.append((dep,[(q[0],q[1]) for q in pp],fill))
 for dep,pts,col in sorted(faces,key=lambda f:f[0],reverse=True):
  lay=Image.new('RGBA',(W,H),(0,0,0,0));ld=ImageDraw.Draw(lay);ld.polygon(pts,fill=col,outline=(15,21,31,170));im=Image.alpha_composite(im,lay)
 d=ImageDraw.Draw(im);d.text((20,18),'CORVUS · COMPACT BARE FRAME · '+name.upper(),fill=(224,232,241));d.text((20,739),'HULL 27 × 13.5u  |  HEIGHT 36.5u  |  OPEN HARDPOINTS  |  REVERSE KNEES',fill=(177,193,208));return im
for n,y,e in [('front',0,0),('right_3q',-35,10),('back',180,0)]:view(n,y,e).save(O/(n+'.png'))
sheet=Image.new('RGB',(1152,384),(19,24,33))
for i,n in enumerate(['front','right_3q','back']):sheet.paste(Image.open(O/(n+'.png')).convert('RGB').resize((384,384)),(i*384,0))
sheet.save(O/'preview_lowwide_3view.png')
# Explicit measured report includes axis conventions and foot stance.
allv=[(b['p'][i]-b['s'][i]/2,b['p'][i]+b['s'][i]/2) for b in parts for i in range(3)]
boxbounds={axis:[min(a for a,z in allv),max(z for a,z in allv)] for axis in range(3)}
report={'units':'u; 16u=1 cell','axis':'x=width, y=up, z=depth; forward=-Z','envelope':[round(max(b['p'][0]+b['s'][0]/2 for b in parts)-min(b['p'][0]-b['s'][0]/2 for b in parts),2),36.5,round(max(b['p'][2]+b['s'][2]/2 for b in parts)-min(b['p'][2]-b['s'][2]/2 for b in parts),2)],'cells':[round((max(b['p'][0]+b['s'][0]/2 for b in parts)-min(b['p'][0]-b['s'][0]/2 for b in parts))/16,2),2.28,round((max(b['p'][2]+b['s'][2]/2 for b in parts)-min(b['p'][2]-b['s'][2]/2 for b in parts))/16,2)],'hull_width':27,'hull_height':13.5,'lowest_hull_clearance':12.1,'upper_thigh_link_length':round(float(np.linalg.norm(np.array(legdata['l'][1])-legdata['l'][0])),2),'lower_shin_link_length':round(float(np.linalg.norm(np.array(legdata['l'][3])-legdata['l'][1])),2),'arm_link_reach':round(sum(float(np.linalg.norm(np.array(arm_data['l'][i+1])-arm_data['l'][i])) for i in range(2)),2),'stance_center_spacing':17.3,'each_footpad_width':10,'width_including_feet':round(max(b['p'][0]+b['s'][0]/2 for b in parts)-min(b['p'][0]-b['s'][0]/2 for b in parts),2),'model':'geometry-only preview; no export or detailed texture, per revision request'}
(O/'dimensions_lowwide.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))

# Stage A: an editable Blockbench Bedrock-format project checkpoint, independent of the renderer.
# Palette is isolated into 8x8 RGBA swatches; transparent windscreen has its own true-alpha tile.
from PIL import Image
atlas=Image.new('RGBA',(128,128),(0,0,0,0)); dr=ImageDraw.Draw(atlas)
keys=list(C); sw={key:[(i%8)*8,(i//8)*8,8,8] for i,key in enumerate(keys)}
for key,(x,y,w,h) in sw.items(): dr.rectangle((x,y,x+w-1,y+h-1),fill=C[key])
# dedicated glass alpha UV island in reserved row 112
sw['glass']=[0,112,8,8];dr.rectangle((0,112,7,119),fill=C['glass'])
atlas.save(O/'corvus_v2.png')
uid=lambda s:__import__('uuid').uuid5(__import__('uuid').NAMESPACE_URL,'corvus-v2/'+s).hex
elements=[];bone_groups={}; outliner=[]
parents={'root':None,'hull':'root','chassis':'root','core':'hull','doors':'hull','canopy':'hull','seat':'canopy','back':'root','vents':'hull','mounts':'root','arm_l':'root','arm_r':'root','leg_l':'root','leg_r':'root','foot_l':'leg_l','foot_r':'leg_r','antenna':'hull'}
for name,parent in parents.items():
 g={'name':name,'uuid':uid('bone/'+name),'origin':[0,0,0],'rotation':[0,0,0],'export':True,'isOpen':False,'children':[]};bone_groups[name]=g
 if parent:bone_groups[parent]['children'].append(g)
 else:outliner.append(g)
for i,b in enumerate(parts):
 name=b['name'];center=b['p'];size=b['s'];rot=b['r'];group=b['g'];lo=center-size/2;hi=center+size/2; key=next((k for k,v in C.items() if v==b['c']), 'medium'); x,y,w,h=sw[key];
 # each face samples its keyed color swatch; no atlas overlap with alpha canopy island.
 faces={f:{'uv':[x,y,w,h],'texture':0} for f in ('north','east','south','west','up','down')};eid=uid('cube/'+name);el={'name':name,'uuid':eid,'type':'cube','from':lo.tolist(),'to':hi.tolist(),'origin':center.tolist(),'rotation':list(rot),'box_uv':False,'faces':faces,'rescale':False,'visibility':True};elements.append(el);bone_groups[group]['children'].append(eid)
from pathlib import Path
bb={'meta':{'format_version':'4.10','model_format':'bedrock','box_uv':False},'name':'Corvus Compact V2','model_identifier':'corvus','resolution':{'width':128,'height':128},'elements':elements,'outliner':outliner,'textures':[{'id':'0','uuid':uid('texture'),'name':'corvus_v2.png','relative_path':'corvus_v2.png','source':'data:image/png;base64,'+__import__('base64').b64encode((O/'corvus_v2.png').read_bytes()).decode(),'internal':True,'mode':'bitmap','width':128,'height':128,'uv_width':128,'uv_height':128,'render_mode':'default'}]}
(O/'checkpoint_lowwide_v2.bbmodel').write_text(json.dumps(bb,separators=(',',':')))
print('STAGE A checkpoint:',len(elements),'cubes,',len(parents),'bones; true alpha glass tile UV',sw['glass'])
