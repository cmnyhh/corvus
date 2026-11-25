from pathlib import Path
import shutil,sys,subprocess
P=Path(__file__).resolve().parent
prev=P.parent/'refinement_v3'
s=(prev/'build_frame.py').read_text().replace("(P/'limbs.py')", "(P.parent/'refinement_v3/limbs.py')")
s=s.replace('[5.1,22.4,.65]','[5.1,19.9,.65]').replace('[7.65,13.8,-4.4]','[7.65,12.95,-6.25]')
assert '[5.1,19.9,.65]' in s and '[7.65,12.95,-6.25]' in s
anchor="exec((P.parent/'refinement_v3/limbs.py').read_text(),globals())"
assert s.count(anchor)==1
s=s.replace(anchor,anchor+";exec((P/'armor.py').read_text(),globals())")
s=s.replace("assert not any((P/n).exists() for n in files)","assert P.name=='refinement_v4'").replace(".open('xb')",".open('wb')")
s=s.replace('assert not coll,coll','assert not coll,coll\nassert len(parts)>550,len(parts)')
pos=s.index('allv=np.concatenate')
s=s[:pos]+'''for name,group in G.items():
 if name=='root' or name.startswith(('leg_','foot_')): continue
 group['origin'][1]-=2.5;coords[name][1]-=2.5;bones[name]['pivot'][1]-=2.5
 for cube in bones[name]['cubes']:
  cube['origin'][1]-=2.5;cube['pivot'][1]-=2.5
for e in parts:
 if not lookup[e['uuid']].startswith(('leg_','foot_')):
  for key in ('origin','from','to'):e[key][1]-=2.5
'''+s[pos:]
s=s.replace('31.75+r*math.sin','29.25+r*math.sin').replace("'center_xy':[0,31.75]","'center_xy':[0,29.25]")
exec(compile(s,str(P/'build.py'),'exec'),{'__file__':str(P/'build.py')})
for source,target in [('staging_corvus.geo.json','export/geo/corvus.geo.json'),('staging_corvus.png','export/textures/entity/mecha.png')]:shutil.copyfile(P/source,P/target)
