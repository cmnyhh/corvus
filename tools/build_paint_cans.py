"""Generate Minecraft item models, recipes, and pixel art for the eight spray cans."""
import json
from pathlib import Path
from PIL import Image, ImageDraw

RES = Path(__file__).resolve().parents[1] / 'src/main/resources'
DYES = dict(standard='gray', arctic='white', sand='yellow', jungle='green', crimson='red', neon='pink', void='purple', chrome='light_gray')
for paint, dye in DYES.items():
    name = 'paint_can_' + paint
    atlas = RES / 'assets/huhu_dronepal'
    data = RES / 'data/huhu_dronepal'
    files = {
        atlas / 'models/item' / (name + '.json'): {'parent':'minecraft:item/generated','textures':{'layer0':'huhu_dronepal:item/' + name}},
        data / 'recipe' / (name + '.json'): {'type':'minecraft:crafting_shapeless','category':'misc','ingredients':[{'item':'minecraft:iron_nugget'},{'item':'minecraft:glass_bottle'},{'item':f'minecraft:{dye}_dye'}],'result':{'id':'huhu_dronepal:' + name,'count':1}},
    }
    for path, value in files.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value, indent=2) + '\n')
    tint = json.loads((data / 'paints' / (paint + '.json')).read_text())['glow_color']
    im = Image.new('RGBA',(16,16)); draw = ImageDraw.Draw(im)
    draw.rectangle((5,4,11,14),fill='#292936'); draw.rectangle((6,4,10,14),fill='#697280'); draw.rectangle((6,6,10,11),fill=tint)
    draw.rectangle((5,7,5,11),fill='#454952'); draw.rectangle((6,7,6,9),fill='#e1dce5'); draw.rectangle((5,13,11,13),fill='#afb7bd')
    draw.rectangle((6,3,10,4),fill='#aab0ba'); draw.rectangle((7,1,9,2),fill='#585965'); draw.point((7,1),fill=tint)
    target = atlas / 'textures/item' / (name + '.png'); target.parent.mkdir(parents=True,exist_ok=True); im.save(target)
