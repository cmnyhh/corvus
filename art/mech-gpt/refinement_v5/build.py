from pathlib import Path
P=Path(__file__).resolve().parent
source=(P.parent/'refinement_v4/build.py').read_text()
source=source.replace("P.name=='refinement_v4'","P.name=='refinement_v5'").replace("(P/'armor.py')","(P.parent/'refinement_v4/armor.py')")
source=source.replace('[5.1,19.9,.65]','[5.1,17.9,.65]').replace('[7.65,12.95,-6.25]','[7.65,11.95,-7.05]')
source=source.replace("s[:pos]+'''for name", "s[:pos]+'''exec((P/'detail.py').read_text(),globals())\nfor name").replace('-=2.5','-=4.5')
source=source.replace(" for cube in bones[name]['cubes']:"," shift=-1.0 if name!='hip' else 0.0\n group['origin'][2]+=shift;coords[name][2]+=shift;bones[name]['pivot'][2]+=shift\n for cube in bones[name]['cubes']:\n  cube['origin'][2]+=shift;cube['pivot'][2]+=shift")
source=source.replace("e[key][1]-=4.5", "e[key][1]-=4.5;e[key][2]-=float(lookup[e['uuid']]!='hip')").replace('29.25','27.25')
source=source.replace('exec(compile(s,',"s=s.replace('np.linspace(-5.8,-.61,25)','np.linspace(-6.8,-1.61,25)').replace('[-5.8,-.61]','[-6.8,-1.61]').replace('rear_stop_front_z_u\\\':-.55','rear_stop_front_z_u\\\':-1.55')\nexec(compile(s,")
exec(compile(source,__file__,'exec'),{'__file__':__file__})
