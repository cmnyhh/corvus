from pathlib import Path

out = Path(__file__).resolve().parent
source = (out.parent / 'refinement_v6/build.py').read_text()
helpers = source[:source.index("strip('hip',lambda")].replace("out.parent / 'refinement_v5'", "out.parent / 'refinement_v8'").replace("base / 'staging_material_uv.json'", "out.parent / 'refinement_v3/staging_material_uv.json'")
exec(compile(helpers,'model_helpers','exec'))
strip('core_ring',lambda name:name.startswith(('violet key','light channel','inner step')))
outline=np.array([(-2.7,2.75),(2.7,2.75),(3.35,1.9),(3.15,-.65),(1.84,-2.43),(.76,-3.03),(-.76,-3.03),(-1.84,-2.43),(-3.15,-.65),(-3.35,1.9)])
for index,a in enumerate(outline):
    b=outline[(index+1)%len(outline)]
    edge=b-a
    tangent=edge/np.linalg.norm(edge)
    normal=np.array([-tangent[1],tangent[0]])
    mid=(a+b)/2
    # Stepped segment lengths give the funnel its taper without using meshes.
    for row,(outer,inner,outer_z,inner_z,material) in enumerate([(1.08,.84,-7.14,-6.65,'steel'),(.845,.63,-6.66,-6.19,'face'),(.635,.57,-6.21,-6.03,'core_light')]):
        width=(outer-inner)*np.dot(mid,normal)
        depth=inner_z-outer_z
        center=np.array([*(mid*(outer+inner)/2), (outer_z+inner_z)/2]);center[1]+=27.25
        roll=math.degrees(math.atan2(tangent[1],tangent[0]))
        pitch=-math.degrees(math.atan2(depth,width))
        add('core_ring',f'throat bevel {index}.{row}',center,(np.linalg.norm(edge)*(outer+inner)/2*1.08,math.hypot(width,depth)+.10,.14),(pitch,0,roll),material)
    center=mid*.57+normal*.09
    add('core_ring',f'throat return {index}',(center[0],27.25+center[1],-3.74),(np.linalg.norm(edge)*.60,.22,4.48),(0,0,roll),'lining')
finish=source[source.index("model['elements']="):].replace("'Corvus VI'","'Corvus IX'")
exec(compile(finish,'serialization','exec'))
# Verify clearance at the smaller bore directly against transformed cube interiors.
points=np.array([(r*math.cos(angle),27.25+r*math.sin(angle),z) for r in (0,.7,1.35) for angle in np.arange(24)*math.pi/12 for z in np.linspace(-7.2,-1.61,25)])
for element in elements.values():
    rx,ry,rz=np.radians(element['rotation']);cx,cy,cz=np.cos((rx,ry,rz));sx,sy,sz=np.sin((rx,ry,rz))
    matrix=np.array([[cy*cz,sx*sy*cz-cx*sz,cx*sy*cz+sx*sz],[cy*sz,sx*sy*sz+cx*cz,cx*sy*sz-sx*cz],[-sy,sx*cy,cx*cy]])
    p=np.array(element['origin']); local=(points-p)@matrix+p
    assert not np.any(np.all((local>element['from'])&(local<element['to']),axis=1)), element['name']
dims['core_clearance'].update(clear_radius_u=1.35,clear_z_range_u=[-7.2,-1.61],sample_points=len(points),obstruction_count=0)
save('dimensions.json',dims)
