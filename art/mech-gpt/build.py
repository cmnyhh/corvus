"""Build Corvus in Blockbench editor axes, north = -Z, port = +X."""
import base64, json, math, uuid
from pathlib import Path
from PIL import Image, ImageDraw
import numpy as np

OUT=Path(__file__).resolve().parent
FACES=('north','east','south','west','up','down')
uid=lambda s:str(uuid.uuid5(uuid.NAMESPACE_URL,'corvus-cv03/'+s))
colors={'ceramic':'#deded6','pearl':'#bac0c4','slate':'#667582','steel':'#414f60','iron':'#283242','black':'#19212d','rubber':'#101722','metal':'#8998a6','orange':'#e88b43','violet':'#b97afe','lamp':'#f4d4ff','seat':'#39475b','strap':'#d7af72','glass':'#81b5c8','socket':'#514d60'}
tex=Image.new('RGBA',(128,128),(0,0,0,0)); draw=ImageDraw.Draw(tex); tiles={}; islands={}
for i,(k,c) in enumerate(colors.items()):
    x=(i%8)*8;y=(i//8)*8; draw.rectangle((x,y,x+7,y+7), fill=c if k!='glass' else (129,181,200,65)); tiles[k]=[x+2,y+2,4,4]
# Distinct coated face islands for service markings, panel seams and manageable repainting.
for i,(name,base) in enumerate([('shoulder_l','ceramic'),('shoulder_r','ceramic'),('thigh_l','pearl'),('thigh_r','pearl'),('shin_l','ceramic'),('shin_r','ceramic'),('cheek_l','steel'),('cheek_r','steel'),('door_l','pearl'),('door_r','pearl'),('rear_l','slate'),('rear_r','slate'),('forearm_l','pearl'),('forearm_r','pearl'),('pelvis','slate'),('seatback','seat')]):
    x=(i%4)*32;y=16+(i//4)*24; tiles[name]=[x+1,y+1,30,22]; draw.rectangle((x,y,x+31,y+23),fill=colors[base]); dark=colors['iron']; mid=colors['metal'];
    draw.line([(x+1,y+18),(x+5,y+22),(x+26,y+22),(x+30,y+18)],fill=mid);draw.line([(x+2,y+2),(x+24,y+2),(x+29,y+7)], fill=mid)
    for dx,dy in [(3,4),(28,16)]: draw.point((x+dx,y+dy), fill=dark)
    draw.rectangle((x+6,y+15,x+12,y+16),fill=colors['orange'] if name.startswith(('shoulder','thigh','forearm')) else mid)
    for j in range(3):draw.line((x+15+2*j,y+16,x+16+2*j,y+14), fill=dark)
    islands[name]=[x,y,32,24]
# Additional unused space is transparent, including the final 16 rows.
bones={};groups={};elements=[]; geo_groups={}; names={}
def bone(name,parent,p):
    bones[name]=(parent,list(p));g={'name':name,'origin':list(p),'uuid':uid(name),'export':True,'isOpen':False,'children':[]};groups[name]=g
    if parent:groups[parent]['children'].append(g)
    geo_groups[name]={'name':name,'pivot':[-p[0],p[1],p[2]],**({'parent':parent} if parent else {}),'cubes':[]};return name

def cube(b,n,p,s,m,r=(0,0,0),panel=None,face='north'):
    n=b+'/'+n; assert n not in names; names[n]=1;a=np.array(p)-np.array(s)/2;t=a+s;uv={k:tiles[panel if panel and k==face else m] for k in FACES};f={k:{'uv':[u,v,u+w,v+h],'texture':0} for k,(u,v,w,h) in uv.items()}; eid=uid(n)
    elements.append({'name':n.split('/')[-1],'uuid':eid,'type':'cube','from':a.tolist(),'to':t.tolist(),'origin':list(p),'rotation':list(r),'box_uv':False,'faces':f,'rescale':False,'visibility':True});groups[b]['children'].append(eid)
    guv={k:{'uv':([u+w,v+h] if k in ('up','down') else [u,v]),'uv_size':([-w,-h] if k in ('up','down') else [w,h])} for k,(u,v,w,h) in uv.items()};geo_groups[b]['cubes'].append({'origin':[-float(t[0]),float(a[1]),float(a[2])],'size':list(s),'pivot':[-p[0],p[1],p[2]],'rotation':[-r[0],-r[1],r[2]],'uv':guv})

def save(stem):
    tex.save(OUT/'corvus.png'); src='data:image/png;base64,'+base64.b64encode((OUT/'corvus.png').read_bytes()).decode();bb={'meta':{'format_version':'4.10','model_format':'bedrock','box_uv':False},'name':'Corvus','model_identifier':'corvus','resolution':{'width':128,'height':128},'elements':elements,'outliner':[groups['root']],'textures':[{'id':'0','uuid':uid('atlas'),'name':'corvus.png','relative_path':'corvus.png','source':src,'internal':True,'mode':'bitmap','width':128,'height':128,'uv_width':128,'uv_height':128,'render_mode':'default'}]};(OUT/(stem+'.bbmodel')).write_text(json.dumps(bb,separators=(',',':')))
    if stem=='corvus':
        geo={'format_version':'1.12.0','minecraft:geometry':[{'description':{'identifier':'geometry.corvus','texture_width':128,'texture_height':128,'visible_bounds_width':3,'visible_bounds_height':3.5,'visible_bounds_offset':[0,1.5,0]},'bones':list(geo_groups.values())}]};(OUT/'corvus.geo.json').write_text(json.dumps(geo,separators=(',',':')));(OUT/'rig_uv.json').write_text(json.dumps({'rig':geo_groups.keys() and {n:{'parent':b['parent'] if 'parent'in b else None,'bedrock_pivot':b['pivot'],'editor_pivot':bones[n][1],'cubes':len(b['cubes'])} for n,b in geo_groups.items()},'uv_xywh':tiles,'coated_islands_xywh':islands,'unused_rows':[112,127]},indent=2));print(len(bones),'bones',len(elements),'cubes')

def beam(b,n,start,end,w,d,m,panel=None):
    a=np.array(start);v=np.array(end)-a;L=np.linalg.norm(v);rx=math.degrees(math.asin(v[2]/L));rz=math.degrees(math.atan2(-v[0],v[1]));cube(b,n,(a+v/2).tolist(),[w,L,d],m,[rx,0,rz],panel)

root=bone('root',None,(0,0,0)); hip=bone('pelvis',root,(0,25,1));tor=bone('hull',hip,(0,28,1));cube(hip,'transverse differential',(0,25,1),(13,3.5,5),'iron');cube(hip,'codpiece',(0,24,-1.8),(5.8,4.6,4),'slate',(12,0,0),'pelvis');cube(tor,'pressure bulkhead',(0,35,2),(16,10,7),'iron');cube(tor,'floor pan',(0,30,-1),(13,3,8),'steel');cube(tor,'deck',(0,39.5,0),(17,2,9),'slate')
for sign,side in [(1,'l'),(-1,'r')]:
    X=lambda a:sign*a
    sh=bone('shoulder_'+side,tor,(X(11),39,1));cube(sh,'cap',(X(12.4),40,0),(6.6,4,7),'ceramic',(0,0,-sign*10),'shoulder_'+side)
    ar=bone('arm_'+side,sh,(X(12.3),39,0));beam(ar,'yoke',(X(13.8),31,2),(X(12.3),39,0),3.7,3.9,'steel');fo=bone('forearm_'+side,ar,(X(13.8),31,2));beam(fo,'fore shell',(X(13.9),25,-3),(X(13.8),31,2),4.2,4,'pearl','forearm_'+side)
    th=bone('thigh_'+side,hip,(X(5),25,1));kn=bone('shin_'+side,th,(X(7.1),16,-3.7));hk=bone('hock_'+side,kn,(X(8.1),7,4));ft=bone('foot_'+side,hk,(X(8.4),2,-1));beam(th,'upper shell',(X(7.1),16,-3.7),(X(5),25,1),5.3,5.7,'pearl','thigh_'+side);beam(kn,'lower shell',(X(8.1),7,4),(X(7.1),16,-3.7),4,4,'ceramic','shin_'+side);beam(hk,'heel link',(X(8.4),2,-1),(X(8.1),7,4),2.7,2.9,'steel');cube(ft,'instep',(X(8.4),1.8,-1.5),(5,2.5,5.5),'steel');
    for j in [-1,0,1]:cube(ft,'toe'+str(j),(X(8.4+j*1.9),0.85,-5.2+(abs(j)*0.6)),(1.55,1.7,5.6),'pearl',(0,-j*sign*10,0))
save('checkpoint_blockout')

# Segmented bezels have an actual empty bore. All measurements use editor axes.
def ring(b,label,x,y,z,radius,wall,depth,mat,count=12,plane='xy',phase=0):
    for i in range(count):
        a=360*i/count+phase; v=math.radians(a); span=2*radius*math.tan(math.pi/count)*1.015
        if plane=='xy': pos=(x+radius*math.sin(v),y+radius*math.cos(v),z);size=(span,wall,depth); rot=(0,0,-a)
        elif plane=='xz': pos=(x+radius*math.sin(v),y,z+radius*math.cos(v));size=(span,depth,wall);rot=(0,a,0)
        else: pos=(x,y+radius*math.cos(v),z+radius*math.sin(v));size=(depth,wall,span);rot=(a,0,0)
        cube(b,label+str(i),pos,size,mat,rot)

ringbone=bone('core_ring',tor,(0,34.8,-7));bone('core_insert',ringbone,(0,34.8,-6.3))
ring(ringbone,'housing',0,34.8,-5.4,3.35,1.1,4.4,'iron');ring(ringbone,'lip',0,34.8,-7.75,3.42,.84,.57,'metal');ring(ringbone,'luminous gasket',0,34.8,-8.08,3.03,.26,.25,'violet',16);ring(ringbone,'inner wall',0,34.8,-5.15,2.8,.28,4.7,'black',16)
cube(tor,'recess back wall',(0,34.8,-2.82),(6.6,6.6,.42),'rubber');cube(tor,'lower central bib',(0,30.6,-4.9),(5,3.35,4),'slate',(-14,0,0));cube(hip,'turntable',(0,27.4,1),(7.4,2.7,6.2),'black');cube(hip,'bearing flange',(0,27,1),(8.6,.67,7),'metal');cube(hip,'bearing crown',(0,28.4,1),(8,.6,6.4),'steel')
# Seat, sill and raked windscreen occupy a cutout ahead of the upper bulkhead.
cab=bone('cockpit',tor,(0,40,-1));seat=bone('seat',cab,(0,40.8,0));glass=bone('canopy',cab,(0,40.5,-6.1));cube(cab,'well floor',(0,40.75,-.75),(8.7,.5,8.6),'black');cube(cab,'rear pressure wall',(0,43.2,3.65),(8.6,5.35,.72),'iron');cube(seat,'cushion',(0,41.45,-.3),(3.7,.8,3.45),'seat');cube(seat,'backrest',(0,43.1,1.65),(3.45,3.6,.75),'seat',(8,0,0),'seatback');cube(seat,'head cushion',(0,45.28,1.93),(2.85,1.18,1.15),'pearl',(8,0,0));
for s in (-1,1):
    cube(seat,'rest'+str(s),(2.12*s,42.0,.0),(.65,.72,2.65),'metal');cube(seat,'restraint'+str(s),(.86*s,43.28,1.12),(.42,2.85,.18),'strap',(8,0,-s*8));cube(cab,'console'+str(s),(3.35*s,41.6,-2.35),(1.25,1.35,2.2),'steel',(-15,0,0));cube(cab,'display'+str(s),(3.35*s,42.30,-2.48),(.72,.13,1.22),'violet',(-15,0,0))
    cube(glass,'A pillar'+str(s),(s*4.28,42.53,-4.38),(.45,6.35,.57),'steel',(36,0,0));cube(glass,'side window'+str(s),(s*4.32,43.03,-.05),(.18,4.12,4.85),'glass');cube(cab,'side coaming'+str(s),(s*4.38,41.16,-.55),(.66,.65,6.9),'slate');cube(glass,'roof rail'+str(s),(s*4.3,45.22,.25),(.47,.55,4.86),'metal');cube(cab,'sill marker'+str(s),(s*3.8,40.2,-6.36),(.48,.22,.24),'lamp')
cube(glass,'windscreen',(0,42.53,-4.38),(8.15,6.32,.15),'glass',(36,0,0));cube(glass,'skylight',(0,45.19,.15),(8.15,.14,4.85),'glass');cube(glass,'windscreen header',(0,45.12,-2.55),(8.95,.43,.43),'metal');cube(glass,'windscreen sill',(0,39.99,-6.25),(8.95,.48,.68),'steel');cube(cab,'rear arch',(0,45.38,2.84),(8.95,.72,1),'slate')
ant=bone('antenna',tor,(-5.2,41.1,3.4));cube(ant,'base',(-5.2,41.85,3.4),(1.5,1.4,1.55),'iron');beam(ant,'mast',(-5.2,42.3,3.4),(-5.52,47.8,4.4),.32,.36,'metal');cube(ant,'tip',(-5.52,47.77,4.4),(.38,.46,.4),'orange')
rail=bone('back_rail',tor,(0,36,7.7));cube(rail,'channel',(0,35.75,6.2),(6.7,8.1,2.2),'black');
for s in [-1,1]:
    cube(rail,'guide'+str(s),(2.45*s,35.8,8.15),(.88,8.8,1.72),'metal');cube(rail,'slide'+str(s),(2.02*s,35.8,8.84),(.68,8.8,.54),'steel')
for j in range(5):cube(rail,'detent'+str(j),(0,32.8+j*1.42,7.47),(3.3,.44,.54),'slate')
bone('mount_back',rail,(0,35.8,9.2));cube(rail,'standby',(0,31.75,7.5),(1.1,.37,.23),'socket')
for sign,side in [(1,'l'),(-1,'r')]:
    X=lambda a:sign*a
    sh='shoulder_'+side;ar='arm_'+side;fo='forearm_'+side;th='thigh_'+side;kn='shin_'+side;hk='hock_'+side;ft='foot_'+side
    cube(tor,'cheek '+side,(X(6.25),35.9,-4.1),(4.65,6.45,6.1),'slate',(8,sign*10,-sign*7),'cheek_'+side);cube(tor,'cheek grille '+side,(X(6.4),37.55,-7.16),(3.27,1.69,.51),'black',(8,sign*10,-sign*7));
    for j in range(4):cube(tor,'grille vane'+side+str(j),(X(5.35+j*.65),37.56,-7.47+(.12*j)),(.19,1.22,.26),'metal',(8,sign*10,-sign*7))
    cube(tor,'running light '+side,(X(6.24),36.22,-7.52),(3.17,.37,.4),'violet',(8,sign*10,-sign*7));cube(tor,'upper collar '+side,(X(6.74),40,-.7),(3.85,2.8,8.65),'pearl',(10,0,-sign*12));
    door=bone('core_door_'+side,tor,(X(5.1),33.45,-7.2));cube(door,'parked shutter',(X(5.5),33.63,-7.34),(2.65,4.48,.71),'pearl',(0,sign*13,-sign*8),'door_'+side);cube(door,'pull',(X(5.88),33.52,-7.87),(.35,1.8,.32),'steel',(0,sign*13,-sign*8));cube(tor,'shutter track '+side,(X(4.5),31.72,-6.75),(4.5,.52,.65),'iron')
    accent=bone('shoulder_trim_'+side,sh,(X(12.5),40,0));cube(accent,'signal stripe',(X(13.94),40.65,-3.61),(.77,2,.25),'orange',(0,0,-sign*10));cube(sh,'outer cheek',(X(14.95),39.25,.0),(1.45,3.35,6.25),'pearl',(0,0,-sign*11));
    # Horizontal receiver on top, with an empty gap above the black contact floor.
    pos=X(12.05);cube(sh,'receiver bed',(pos,42.47,0),(4.05,.44,4.25),'black');ring(sh,'socket rim',pos,42.9,0,1.73,.49,.75,'steel',8,'xz');cube(sh,'port standby',(pos,42.83,-2.18),(.79,.36,.32),'socket');bone('mount_shoulder_'+side,sh,(pos,43.3,0))
    cube(ar,'piston',(X(13.9),35,1.78),(1.1,5.2,1.15),'metal',(13,0,-sign*6));cube(fo,'elbow',(X(13.7),31,2),(4.2,3.2,3.5),'black');ring(fo,'elbow flange',X(15.63),31,2,1.23,.53,.6,'metal',8,'yz');cube(fo,'palm block',(X(13.8),25.65,-3.5),(3.55,3.35,3.0),'iron');ring(fo,'wrist flange',X(13.8),25.5,-5.37,1.48,.55,.86,'metal',8);cube(fo,'empty contact floor',(X(13.8),25.5,-4.99),(2.63,2.65,.12),'rubber');cube(fo,'standby',(X(13.8),27.25,-5.27),(.65,.28,.37),'socket');bone('mount_arm_'+side,fo,(X(13.8),25.5,-5.9));
    vent=bone('vent_'+side,tor,(X(5.9),36.75,5.9));cube(vent,'radiator trunk',(X(6.1),36.5,5.82),(4.25,7,2.75),'slate',(-7,0,0),'rear_'+side,'south');cube(vent,'vent pocket',(X(6.1),36.65,7.37),(3.43,5.45,.62),'black',(-7,0,0));
    for j in range(5):cube(vent,'louver'+str(j),(X(6.1),34.9+j*.88,7.81-j*.1),(3.32,.42,.77),'metal',(-20,0,0))
    # Pair of visually distinct journal caps face the outside, leaving the bent leg profile readable.
    for b,h,z,x,r in [(th,24.6,.98,5.1,2.05),(kn,16,-3.7,7.1,2.08),(hk,7,4,8.1,1.48)]:
        cube(b,'journal body',(X(x),h,z),(4.7,r*1.72,r*1.75),'iron',(43,0,0));ring(b,'journal',X(x+2.13),h,z,r,.56,.75,'steel',8,'yz');cube(b,'bearing pin',(X(x+2.52),h,z),(.39,r*.8,r*.8),'metal',(45,0,0));
    beam(th,'hip side cover',(X(8),18.9,-2.1),(X(6.4),23.15,.22),2.65,5.68,'ceramic');cube(th,'knee guard',(X(7.1),16.75,-5.65),(4.0,3.1,2.35),'steel',(17,0,0));cube(th,'warning stripe',(X(7.1),17.3,-6.97),(2.35,.42,.22),'orange',(17,0,0));beam(kn,'hydraulic rod',(X(9.55),8.3,4.0),(X(8.75),15.3,-1.25),.67,.78,'metal');beam(kn,'hydraulic boot',(X(9.55),8.25,4.03),(X(9.19),11.25,1.8),1.16,1.25,'iron');cube(hk,'ankle cuff',(X(8.35),3.55,.6),(3.65,1.45,2.9),'pearl',(42,0,0));cube(ft,'heel',(X(8.4),1.12,2.3),(4.12,2.24,3.3),'iron');cube(ft,'heel vent',(X(8.4),.73,4.05),(2.9,.56,.3),'violet');
    for j in [-1,0,1]:cube(ft,'toe hinge'+str(j),(X(8.4+j*1.88),1.62,-3.95+abs(j)*.45),(1.58,.75,2.45),'slate',(-12,-j*sign*10,0))
save('corvus')

# Transparent orthographic previews directly rasterize the generated boxes and alpha glass.
def render_view(name,yaw,el):
    from PIL import ImageDraw
    yaw=math.radians(yaw);el=math.radians(el);sy,cy=math.sin(yaw),math.cos(yaw);se,ce=math.sin(el),math.cos(el)
    # Camera coordinates: right, up, depth; looks at body center.
    rot=lambda x,y,z:(cy*x-sy*z, se*sy*x+ce*y+se*cy*z, ce*sy*x-se*y-ce*cy*z)
    W=768; H=768; sc=13.0; img=Image.new('RGBA',(W,H),(24,31,43,255));d=ImageDraw.Draw(img)
    d.rectangle((0,0,W-1,H-1),outline=(58,70,89),width=2)
    # floor grid
    for v in range(-30,31,5):
        pts=[]
        for x,z in [(-25,v),(25,v)]:
            a,b,c=rot(x,0,z);pts.append((W/2+sc*a,H*.76-sc*b))
        d.line(pts,fill=(36,44,57),width=1)
    faces=[]; shades={'ceramic':(220,220,214,255),'pearl':(186,192,196,255),'slate':(102,117,130,255),'steel':(65,79,96,255),'iron':(40,50,66,255),'black':(25,33,45,255),'rubber':(16,23,34,255),'metal':(137,152,166,255),'orange':(232,139,67,255),'violet':(185,122,254,255),'lamp':(244,212,255,255),'seat':(57,71,91,255),'strap':(215,175,114,255),'glass':(115,186,216,88),'socket':(81,77,96,255)}
    # infer cube material by stable generation order from element atlas swatch
    colmap={tuple(tex.getpixel((u+2,v+2))[:3]):k for k,(u,v,w,h) in tiles.items()}
    for e in elements:
        lo=np.array(e['from']);hi=np.array(e['to']);center=(lo+hi)/2; sz=(hi-lo)/2
        rx,ry,rz=[math.radians(a) for a in e['rotation']]; Rx=np.array([[1,0,0],[0,math.cos(rx),-math.sin(rx)],[0,math.sin(rx),math.cos(rx)]]);Ry=np.array([[math.cos(ry),0,math.sin(ry)],[0,1,0],[-math.sin(ry),0,math.cos(ry)]]);Rz=np.array([[math.cos(rz),-math.sin(rz),0],[math.sin(rz),math.cos(rz),0],[0,0,1]]);R=Rz@Ry@Rx
        verts=[]
        for i in range(8):
            q=np.array([sz[k]*(1 if (i>>k)&1 else -1) for k in range(3)]);verts.append(center+R@q)
        # facet normals and average depth for painter sorting
        mats=[]
        for k in range(3):
            uv=e['faces'][('north','east','up')[k]]['uv'];px=int((uv[0]+uv[2])/2);py=int((uv[1]+uv[3])/2);m=colmap.get(tex.getpixel((px,py))[:3],'steel');mats.append(m)
        for axis in range(3):
            for sign in [-1,1]:
                corners=[i for i in range(8) if ((i>>axis)&1)==(1 if sign==1 else 0)];p=[verts[i] for i in corners];proj=[rot(*q) for q in p];cx=sum(q[0] for q in proj)/4;cy=sum(q[1] for q in proj)/4;proj.sort(key=lambda q:math.atan2(q[1]-cy,q[0]-cx));depth=sum(q[2] for q in proj)/4
                # crude lit values by visible normal direction
                base=shades.get(mats[axis],shades['steel']); light=(.75 if axis==2 else .9 if axis==1 else .8)*(1 if sign>0 else .75);rgba=tuple(min(255,int(c*light)) for c in base[:3])+(base[3],)
                faces.append((depth,proj,rgba,e['name']))
    for depth,p,rgba,label in sorted(faces,key=lambda f:f[0],reverse=True):
        pts=[(W/2+sc*q[0],H*.76-sc*q[1]) for q in p]
        poly=Image.new('RGBA',(W,H),(0,0,0,0));pd=ImageDraw.Draw(poly);pd.polygon(pts,fill=rgba,outline=(15,21,31,180));img=Image.alpha_composite(img,poly)
    # Standby no weapons, title and coordinates
    d=ImageDraw.Draw(img);d.text((20,18),f'CORVUS / BARE FRAME   •   {name.upper()}',fill=(220,229,240));d.text((20,738),'32 × 48 × 19 u  |  NO WEAPON / NO SHIELD  |  CANOPY α≈0.35',fill=(175,190,205))
    img.save(OUT/(name+'.png'))
for n,y,e in [('front',0,0),('side',90,0),('back',180,0),('three_quarter',35,15)]:render_view(n,y,e)
thumbs=[]
for n in ['front','side','back','three_quarter']:
    im=Image.open(OUT/(n+'.png')).convert('RGB').resize((512,512));thumbs.append(im)
sheet=Image.new('RGB',(1024,1024),(17,22,31))
for i,im in enumerate(thumbs):sheet.paste(im,((i%2)*512,(i//2)*512))
sheet.save(OUT/'preview_4view.png')
