# Parts share the previous edition's atlas and follow their owning joint.
for sig,tag in [(1,'l'),(-1,'r')]:
 x=lambda t:sig*t
 sh='shoulder_'+tag; ar='arm_'+tag; fore=ar+'_fore'; foot='foot_'+tag
 def panel(b,n,c,sz,mat,rot=(0,0,0)):box(b,n,c,sz,mat,rot)
 # Swept outer shoulder: broad plates overlap over a charcoal reveal.
 for j in range(3):
  panel(sh,'mantle blade '+str(j),(x(11.65+.23*j),37.05-.34*j,-2.85+2.10*j),(4.1-.25*j,.68,2.98),'shoulder_shell',(12,-sig*11,-sig*16))
  panel(sh,'mantle return '+str(j),(x(13.42+.10*j),36.27-.25*j,-2.7+2.12*j),(.37,.72,2.44),'shoulder_edge',(10,-sig*10,-sig*16))
 panel(sh,'forward cap',(x(11.73),35.65,-3.55),(3.5,.52,.77),'graphite',(15,-sig*13,-sig*15))
 # Tapered four-sided bracers: leave inner elbow and distal mount accessible.
 for b,start,end,wide in [(ar,V((x(10.8),34.9,.65)),V((x(13),27.5,1.7)),1.37),(fore,V((x(13),27.5,1.7)),V((x(13.65),20.8,-1.95)),1.57)]:
  axis=end-start;length=np.linalg.norm(axis);u=axis/length;lat=V((sig,0,0));lat-=u*np.dot(u,lat);lat/=np.linalg.norm(lat);front=np.cross(lat,u)*sig
  for row,(t0,t1,scale) in enumerate([(.12,.44,1),(.41,.68,.87),(.65,.82,.70)]):
   for j,angle in enumerate([-48,0,48,96,148]):
    t=math.radians(angle);n=front*math.cos(t)+lat*math.sin(t);tan=np.cross(u,n);R=np.column_stack((tan,u,n));rot=np.degrees([math.atan2(R[2,1],R[2,2]),math.asin(np.clip(-R[2,0],-1,1)),math.atan2(R[1,0],R[0,0])]).tolist();rad=wide*scale;c=start+axis*(t0+t1)/2+n*rad;w=rad*.84;ht=(t1-t0)*length
    panel(b,f'gauntlet {row}.{j}',c,(w,ht,.32),('shell' if j in (1,2) else 'graphite'),rot)
    if j in (1,2,3):panel(b,f'gauntlet cleat {row}.{j}',c+n*.20+u*ht*.36,(w*.75,.18,.12),'steel',rot)
  for j in range(3):panel(b,'inspection band '+str(j),start+axis*(.29+.095*j)+lat*(wide+.16),(.23,.24,.73),'aluminium',(12,0,-sig*7))
 # Sloped chest brow, inner canopy seals, recessed radiator louvers.
 link('torso','cheek brow '+tag,(x(3.95),33.15,-5.25),(x(7.92),35.1,-2.72),.67,1.14,'face');link('torso','brow edging '+tag,(x(4.1),33.14,-5.79),(x(7.94),35.05,-3.18),.20,.28,'steel')
 link('cockpit_glass','glazing bead '+tag,(x(3.39),35.77,-5.89),(x(3.39),39.67,-1.59),.16,.22,'carbon')
 for j in range(4):panel('cockpit_glass','canopy clamp '+tag+str(j),(x(3.68),36.03+j*.99,-5.47+j*1.08),(.45,.25,.43),'steel',(44,0,0))
 panel('hip','iliac shield '+tag,(x(4.67),23.33,-1.53),(3.7,3.42,1.38),'face',(13,sig*17,-sig*21));panel('hip','iliac inset '+tag,(x(4.62),23.54,-2.34),(2.62,1.64,.25),'pearl',(13,sig*17,-sig*21))
 for j in range(3):link('hip','waist stay '+tag+str(j),(x(2.45+j*.57),24.42,.1),(x(3.04+j*.54),26.85,1.4),.32,.48,'aluminium' if j==1 else 'steel')
 ex='thruster_'+tag
 for j in range(5):
  panel(ex,'exhaust baffle '+str(j),(x(6.3),29.51+j*.78,7.27),(2.98,.24,.85),'graphite',(-28,0,0));panel(ex,'radiator lip '+str(j),(x(6.3),29.61+j*.78,7.67),(2.80,.14,.19),'aluminium',(-28,0,0))
 for j in (-1,1):link(ex,'plenum rib '+str(j),(x(6.3+j*1.56),29.13,6.40),(x(6.3+j*1.56),33.45,7.05),.36,.54,'pearl')
 for j in range(3):panel('torso','spine shutter '+tag+str(j),(x(3.44),30.06+j*1.68,6.55),(1.92,1.84,.55),'pearl',(-14,0,sig*6))
 for j in [-1,0,1]:
  panel(foot,'toe tip '+str(j),(x(8.35+j*1.88),.75,-5.11+abs(j)*.47),(1.39,.75,.74),'steel',(-12,-sig*j*11,0))
 panel(foot,'heel skid',(x(8.35),.55,3.28),(3.56,.77,1.30),'graphite',(-4,0,0))
for j in range(10):
 a=math.radians(36*j);panel('core_ring','bezel clamp '+str(j),(3.43*math.sin(a),31.75+3.43*math.cos(a),-5.42),(.42,.38,.19),'aluminium',(0,0,-36*j))
