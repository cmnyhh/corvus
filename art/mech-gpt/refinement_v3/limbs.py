def segment(b,n,a,q,t0,t1,w,d,off,m,face=None):
 a,q=V(a),V(q);link(b,n,a+(q-a)*t0+V(off),a+(q-a)*t1+V(off),w,d,m,{'north':face} if face else None)
elbow=coords[fo];wrist=V((X(13.65),20.8,-1.95));a,k,f=coords[th],coords[kn],coords[ft];h=V((X(8.15),6.8,5.1))
for b,n,p,q,w,d in [(ar,'upper spar',coords[ar],elbow,1.72,2),(fo,'wrist spar',elbow,wrist,1.55,1.85),(th,'femur',a,k,2.25,2.5),(kn,'ankle return',h,f,1.66,1.76)]:link(b,n,p,q,w,d,'joint')
for b,n,p,q,width,dz,face in [(ar,'upper arm',coords[ar],elbow,2.55,-.8,'arm_skin'),(fo,'forearm',elbow,wrist,2.85,-.72,'arm_skin'),(th,'thigh',a,k,3.65,-1.12,'leg_skin'),(kn,'shin',k,h,2.75,-.55,'sideplate'),(kn,'return',h,f,2.15,-.62,'leg_skin')]:
 for j,(u,v,scale) in enumerate([(.10,.36,1),(.33,.59,.85),(.56,.78,.69)]):segment(b,n+' plate '+str(j),p,q,u,v,width*scale,.92*scale,(0,0,dz+.2*j),'pearl',face)
for j in [-1,1]:segment(kn,'shin rail '+str(j),k,h,.03,.97,.59,.91,(X(j*.84),0,0),'graphite')
segment(kn,'damper barrel',k,h,.14,.54,.65,.67,(X(1.22),0,.28),'steel');segment(kn,'damper piston',k,h,.53,.91,.33,.35,(X(1.22),0,.28),'aluminium')
for b,n,p,width,radius in [(fo,'elbow',elbow,3.15,.9),(kn,'knee',k,3.85,1.1),(kn,'hock',h,2.65,.74),(ft,'ankle',f,2.5,.68)]:
 box(b,n+' axle',p,(width,2*radius,2*radius),'joint',(45,0,0));ring(b,n+' bearing',p+V((X(width/2),0,0)),radius,.26,.28,'steel','yz',8)
box(kn,'knee nose',k+V((0,.28,-1.48)),(2.92,2.55,.78),'chalk',(19,0,-sg*5),{'north':'leg_skin'});box(fo,'wrist cuff',wrist,(2.72,2.4,2.3),'face');ring(fo,'interface',(X(13.65),20.8,-3.4),1.19,.39,.6,'steel','xy',8);box(fo,'status',(X(13.65),22.04,-3.65),(.42,.18,.18),'mount_light')
