from pathlib import Path

out = Path(__file__).resolve().parent
source = (out.parent / 'refinement_v6/build.py').read_text()
helpers = source[:source.index("strip('hip',lambda")].replace("out.parent / 'refinement_v5'", "out.parent / 'refinement_v7'").replace("base / 'staging_material_uv.json'", "out.parent / 'refinement_v3/staging_material_uv.json'")
exec(compile(helpers, 'model_helpers', 'exec'))
strip('core_ring', lambda name: True)
# Coordinates trace the bore clockwise in the editor's front elevation.
outline = [(-2.7,2.75),(2.7,2.75),(3.35,1.9),(3.15,-.65),(1.84,-2.43),(.76,-3.03),(-.76,-3.03),(-1.84,-2.43),(-3.15,-.65),(-3.35,1.9)]
height = 27.25
for index, point in enumerate(outline):
    a = np.array(point)
    b = np.array(outline[(index+1)%len(outline)])
    tangent = (b-a)/np.linalg.norm(b-a)
    normal = np.array((-tangent[1],tangent[0]))
    angle = math.degrees(math.atan2(tangent[1],tangent[0]))
    def slab(name,offset,z,width,depth,material,span=1,slide=0):
        mid=(a+b)/2 + normal*offset + tangent*slide
        add('core_ring',f'{name} {index}',(mid[0],mid[1]+height,z),(np.linalg.norm(b-a)*span,width,depth),(0,0,angle),material)
    slab('bore liner',.14,-4.10,.28,4.90,'lining',1.06)
    slab('inner step',.20,-5.99,.36,.54,'graphite',1.03)
    slab('light channel',.20,-6.31,.31,.25,'joint',1.02)
    slab('violet key',.19,-6.47,.19,.13,'core_light',.85)
    slab('recess bevel',.50,-6.46,.34,.55,'steel',1.04)
    slab('outer facet',.87,-6.73,.56,.87,'face' if index in (0,4,5,6) else 'graphite',1.04)
    slab('armor face',.90,-7.20,.48,.16,'steel' if index in (1,9) else 'face',.80)
    slab('retainer',.91,-7.32,.27,.13,'aluminium',.24)
for hand in (-1,1):
    blade('core_ring',f'upper brow {hand}',(hand*.13,height+3.35,-7.18),(hand*3.00,height+3.76,-6.91),.49,.64,'pearl')
    blade('core_ring',f'cheek spur {hand}',(hand*1.53,height-3.17,-7.04),(hand*3.20,height-1.14,-6.99),.40,.41,'steel')
# Check the full circular gauge against the transformed cuboids before serialization.
gauge=np.array([(r*math.cos(a),height+r*math.sin(a),z) for r in (0,1.3,2.45) for a in np.arange(24)*math.pi/12 for z in np.linspace(-6.8,-1.61,25)])
for e in elements.values():
    rx,ry,rz=np.radians(e['rotation']);cx,cy,cz=np.cos((rx,ry,rz));sx,sy,sz=np.sin((rx,ry,rz));rotation=np.array([[cy*cz,sx*sy*cz-cx*sz,cx*sy*cz+sx*sz],[cy*sz,sx*sy*sz+cx*cz,cx*sy*sz-sx*cz],[-sy,sx*cy,cx*cy]])
    pivot=np.array(e['origin']); local=(gauge-pivot)@rotation+pivot
    assert not np.any(np.all((local>e['from'])&(local<e['to']),axis=1)), e['name']
finish=source[source.index("model['elements']="):].replace("'Corvus VI'","'Corvus VIII'")
exec(compile(finish,'serialization','exec'))
