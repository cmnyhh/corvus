# Panels follow each link in its local cross section. Leave clearance at both bearings.
for bone_id,label,top,bot,r0,r1,end,coat in [(th,'femoral',a,k,2.52,1.78,.87,'shell'),(kn,'tibial',k,h,1.92,1.40,.79,'pearl'),(kn,'metatarsal',h,f,1.50,1.09,.72,'graphite')]:
 axis=bot-top;length=np.linalg.norm(axis);along=axis/length
 sidevec=V((1,0,0));sidevec-=along*np.dot(sidevec,along);sidevec/=np.linalg.norm(sidevec)
 front=np.cross(sidevec,along);front*=(-1 if front[2]>0 else 1)
 for row,(lo,hi) in enumerate([(.08,.43),(.42,.68),(.675,end)]):
  if hi<=lo:continue
  r=r0+(r1-r0)*(lo+hi)/2;mid=top+axis*(lo+hi)/2
  for j,ang in enumerate([-111,-74,-37,0,37,74,111,165]):
   theta=math.radians(ang);normal=front*math.cos(theta)+sidevec*math.sin(theta);tang=np.cross(along,normal);tang/=np.linalg.norm(tang)
   frame=np.column_stack((tang,along,normal));pitch=math.asin(np.clip(-frame[2,0],-1,1));rot=np.degrees([math.atan2(frame[2,1],frame[2,2]),pitch,math.atan2(frame[1,0],frame[0,0])]).tolist()
   c=mid+normal*r;w=.68*r;ht=(hi-lo)*length
   box(bone_id,f'{label} wrap {row}-{j}',c,(w,ht,.44),('face' if j==7 else coat),rot)
   if abs(ang)<80 and row<2:
    box(bone_id,f'{label} hem {row}-{j}',c+normal*.25+along*(ht*.39),(w*.89,.15,.12),'steel',rot)
    box(bone_id,f'{label} latch {row}-{j}',c+normal*.29+tang*w*.30-along*ht*.27,(.16,.25,.15),'joint',rot)
   if ang==0:
    box(bone_id,f'{label} recessed spine {row}',c+normal*.23,(w*.48,ht*.95,.11),'graphite',rot)
for n,p,r,wide in [('patella',k,1.15,3.85),('rear hinge',h,.82,2.65),('ankle boss',f,.74,2.5)]:
 b=ft if n=='ankle boss' else kn
 c=p+V((X(wide*.5+.17),0,0));box(b,n+' hub',c,(.38,r*1.43,r*1.43),'graphite',(45,0,0))
 for j in range(6):
  theta=j*math.pi/3;box(b,n+' lock '+str(j),c+V((X(.22),math.cos(theta)*r*.79,math.sin(theta)*r*.79)),(.14,.23,.23),'aluminium',(45,0,0))
for j in [-1,0,1]:
 for i in range(3):
  box(ft,f'toe {j} lamina {i}',(X(8.35+j*(1.68+i*.14)),1.69-i*.12,-1.7-i*.98+abs(j)*.45),(1.43-.12*i,.46,1.20),'pearl' if i==0 else 'steel',(-6,-sg*j*11,0))
 box(ft,'digit hinge '+str(j),(X(8.35+j*1.65),1.8,-1.28+abs(j)*.45),(1.53,.64,.64),'joint',(45,0,0))
