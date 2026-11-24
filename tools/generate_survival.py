"""Generate Dronepal's survival recipes, assembly blueprints and additive structure supplies."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / 'src/main/resources'
DATA = ROOT / 'data/huhu_dronepal'
ASSETS = ROOT / 'assets/huhu_dronepal'
N = 'huhu_dronepal:'
M = 'minecraft:'


def emit(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


# (internal id, Chinese name, representative vanilla texture)
parts = [
    ('airframe','机架','iron_ingot'),('servo_motor','伺服电机','compass_16'),('circuit_board','电路板','comparator'),
    ('battery_cell','电池芯','copper_ingot'),('optics','光学组件','amethyst_shard'),('armor_plate','装甲板','iron_nugget'),('heat_sink','散热片','netherite_scrap'),
    ('flight_controller','飞控板','repeater'),('speed_controller','电调','comparator'),('powertrain','动力总成','minecart'),('sensor_array','传感模块','spyglass'),
    ('weapon_interface','武器接口','iron_ingot'),('medical_pump','医疗泵组','glass_bottle'),('combat_core','火控核心','fire_charge'),('scan_core','嗅探核心','echo_shard'),
    ('carry_core','货舱核心','bundle'),('medic_core','修复核心','glistering_melon_slice'),('light_core','照明核心','glowstone_dust'),
    ('solar_core','光伏核心','prismarine_crystals'),('link_core','链路核心','ender_pearl'),('upgrade_kit','升级套件','gold_ingot'),
    ('advanced_upgrade_kit','高级升级套件','netherite_ingot'),('medical_supply','医用耗材','paper')]
local = {k for k, _, _ in parts} | {'drone','assembly_bench','charging_dock','module','omd_bat_100','omd_bat_200','omd_bat_300', 'chip_tactical','chip_chatty','chip_silent'}


def product(id):
    if id == 'peregrine': return {'item': N+'drone', 'frame': id}
    if '_mk' in id: return {'item': N+'module', 'module': id}
    return {'item': (N if id in local else M) + id}


def costs(**kwargs):
    return [dict(product(id), count=count) for id, count in kwargs.items()]


def workbench(id, **kwargs):
    ingredients = [{'item': i['item']} for i in costs(**kwargs) for _ in range(i['count'])]
    assert 1 <= len(ingredients) <= 9
    emit(DATA/f'recipe/{id}.json', {'type':M+'crafting_shapeless','category':'misc','ingredients':ingredients,'result':{'id': N+id, 'count':1}})
    emit(DATA/f'advancement/recipes/{id}.json', {'parent':M+'recipes/root', 'criteria':{'material':{'trigger':M+'inventory_changed','conditions':{'items':[{'items':ingredients[0]['item']}]}}}, 'requirements':[['material']], 'rewards':{'recipes':[N+id]}})


def bench(id, **kwargs):
    emit(DATA/f'assembly/{id}.json', {'ingredients':costs(**kwargs),'result':product(id)})


families = ['combat','scan','carry','medic','light','solar','link']
# Remove exactly the old live recipes and their unlocks. The handoff stays a reference archive.
legacy = [f'{family}_mk{tier}' for family in families + ['battery'] for tier in (1,2,3)] + ['drone','chip_tactical','chip_silent','chip_chatty']
for subdir in ['recipe','advancement/recipes']:
    base = (DATA/subdir).resolve()
    for name in legacy:
        target = (base/(name+'.json')).resolve()
        assert target.parent == base and target.stem == name
        target.unlink(missing_ok=True)

workbench('assembly_bench', iron_ingot=4, copper_block=2, piston=1, anvil=1)
workbench('airframe', iron_ingot=4, copper_ingot=2)
workbench('servo_motor', copper_ingot=3, redstone=2, iron_ingot=1)
workbench('circuit_board', gold_ingot=1, redstone=4, quartz=2)
workbench('battery_cell', copper_ingot=2, redstone=2, paper=1)
workbench('optics', glass=2, amethyst_shard=1)
workbench('armor_plate', iron_ingot=3, leather=1)
workbench('heat_sink', iron_ingot=2, blue_ice=1)
workbench('medical_supply', sugar=1, paper=2, redstone=1)
workbench('charging_dock', iron_ingot=6, copper_ingot=2, redstone=1)
for paint, dye in {'standard':'gray','arctic':'white','sand':'yellow','jungle':'green','crimson':'red','neon':'pink','void':'purple','chrome':'light_gray','rust':'orange'}.items():
    workbench('paint_can_'+paint, iron_ingot=1, gunpowder=1, **{dye+'_dye':1})
bench('flight_controller', circuit_board=2, servo_motor=1, gold_ingot=1)
bench('speed_controller', circuit_board=1, servo_motor=2, copper_block=1)
bench('powertrain', battery_cell=2, speed_controller=1, airframe=1)
bench('sensor_array', optics=2, circuit_board=1)
bench('weapon_interface', airframe=1, servo_motor=2, iron_ingot=2)
bench('medical_pump', battery_cell=1, glass=2, sugar=2)
bench('combat_core', weapon_interface=1, circuit_board=2, gunpowder=4, heat_sink=1)
bench('scan_core', sensor_array=1, optics=2, amethyst_shard=2)
bench('carry_core', airframe=1, chest=1, servo_motor=2)
bench('medic_core', medical_pump=1, sugar=3, redstone=2)
bench('light_core', optics=1, glowstone_dust=2, copper_ingot=2)
bench('solar_core', optics=2, copper_ingot=3, glass_pane=2)
bench('link_core', circuit_board=2, iron_ingot=2, redstone=3)
bench('upgrade_kit', circuit_board=2, copper_block=1, amethyst_shard=1)
bench('advanced_upgrade_kit', circuit_board=3, diamond=1, netherite_scrap=1, heat_sink=2)
for fam in ['carry','light','solar']:
    bench(f'{fam}_mk1', **{fam+'_core':1,'airframe':1})
    bench(f'{fam}_mk2', **{fam+'_mk1':1,'upgrade_kit':1})
    bench(f'{fam}_mk3', **{fam+'_mk2':1,'advanced_upgrade_kit':1})
bench('peregrine', airframe=2, powertrain=1, flight_controller=1, sensor_array=1, glass=1)
bench('omd_bat_100', battery_cell=3, airframe=1)
bench('omd_bat_200', omd_bat_100=1, battery_cell=2, copper_block=2)
bench('omd_bat_300', omd_bat_200=1, battery_cell=4, diamond=1)
for id,material in [('tactical','compass'),('chatty','book'),('silent','white_wool')]:
    bench('chip_'+id, circuit_board=1, redstone=2, **{material:1})


def supply(name, chance, tables, entries):
    emit(DATA/f'loot_modifiers/{name}.json', {'type':'neoforge:add_table', 'conditions':[{'condition': M+'any_of', 'terms':[{'condition':'neoforge:loot_table_id','loot_table_id':M+'chests/'+table} for table in tables]}, {'condition':M+'random_chance','chance':chance}], 'table':N+'supplies/'+name})
    emit(DATA/f'loot_table/supplies/{name}.json', {'type':M+'chest','pools':[{'rolls':1, 'entries':entries}]})


def entry(id,weight,components=None):
    e={'type':M+'item','name':N+id,'weight':weight}
    if components: e['functions']=[{'function':M+'set_components','components':components}]
    return e


def module(family,tier,rust=False):
    id = family+('_rust_g1' if rust else f'_mk{tier}')
    return entry('module',[6,3,1][tier-1], {M+'custom_data':{'module_product':N+id,'module_family':family,'module_tier':tier}})


def frame(id): return entry('drone',1,{N+'frame':N+id})

supply('military',0.42,['pillager_outpost','stronghold_corridor','end_city_treasure'],
       [module(f,t) for f in ['combat','scan','link'] for t in (1,2,3)] + [frame('owl'),frame('bedrock')] + [entry(i,w) for i,w in [('ahi_bat_mk1',6),('ahi_bat_mk2',3),('ahi_bat_mk3',1),('cvs_bat_r1',6),('cvs_bat_r2',3)]])
supply('salvage',0.35,['abandoned_mineshaft','shipwreck_supply','underwater_ruin_big'],
       [module(f,1,True) for f in families if f!='link'] + [frame('scrap'),entry('rn_bat_g1',5),entry('rn_bat_g2',2)])
supply('medical',0.30,['village/village_temple','igloo_chest'],[module('medic',t) for t in (1,2,3)])
emit(ROOT/'data/neoforge/loot_modifiers/global_loot_modifiers.json',{'replace':False,'entries':[N+i for i in ['military','salvage','medical']]})
emit(DATA/'loot_table/blocks/assembly_bench.json',{'type':M+'block','pools':[{'rolls':1,'entries':[entry('assembly_bench',1)],'conditions':[{'condition':M+'survives_explosion'}]}]})

for id,_,texture in parts:
    emit(ASSETS/f'models/item/{id}.json', {'parent':M+'item/generated','textures':{'layer0':M+'item/'+texture}})
emit(ASSETS/'models/block/assembly_bench.json',{'parent':M+'block/cube_bottom_top','textures':{'top':M+'block/smithing_table_top','bottom':M+'block/iron_block','side':M+'block/copper_bulb'}})
emit(ASSETS/'blockstates/assembly_bench.json',{'variants':{'':{'model':N+'block/assembly_bench'}}})
emit(ASSETS/'models/item/assembly_bench.json',{'parent':N+'block/assembly_bench'})
for locale, labels in [('zh_cn',['装配台','%s × %s/%s','点击装配，材料从背包扣除','装配完成','请检查材料及背包空间']),('en_us',['Assembly Bench','%s × %s/%s','Click to assemble using your inventory','Assembly complete','Check materials and inventory space'])]:
    lang=dict(zip(['block.dronepal.assembly_bench','assembly.dronepal.material','assembly.dronepal.click','assembly.dronepal.done','assembly.dronepal.missing'],labels))
    lang.update(dict(zip(['assembly.dronepal.start','assembly.dronepal.seconds','assembly.dronepal.blocked','assembly.dronepal.hint'], ['开始装配','%s 秒','请检查材料槽及成品槽空间','悬停蓝图查看材料'] if locale=='zh_cn' else ['Start assembly','%s s','Check the input materials and output space','Hover over the blueprint for materials'])))
    for id,zh,_ in parts: lang['item.dronepal.'+id]=zh if locale=='zh_cn' else id.replace('_',' ').title()
    emit(ROOT/f'assets/huhu_dronepal_survival/lang/{locale}.json',lang)
