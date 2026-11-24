"""Assemble the standalone Dronepal model handoff from the checked game resources."""
import subprocess
import sys
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / 'src/main/resources'
ASSETS = RES / 'assets/huhu_dronepal'


def main():
    subprocess.run([sys.executable, str(ROOT / 'tools/check_model.py')], check=True)
    files = [ROOT / 'model/drone.bbmodel', ASSETS / 'geo/drone.geo.json',
             ASSETS / 'animations/drone.animation.json', ASSETS / 'textures/item/drone.png',
             ASSETS / 'models/item/drone.json']
    for frame in ('owl', 'bedrock', 'scrap'):
        files.extend((ROOT / f'model/{frame}.bbmodel', ASSETS / f'geo/{frame}.geo.json'))
    files.extend(sorted((ASSETS / 'textures/item').glob('frame_*.png')))
    files.extend(sorted((ASSETS / 'models/item').glob('frame_*.json')))
    files.extend(sorted((RES / 'data/huhu_dronepal/frames').glob('*.json')))
    files.extend(sorted((ASSETS / 'textures/entity').glob('drone_*.png')))
    files.extend(sorted((RES / 'data/huhu_dronepal/paints').glob('*.json')))
    files.extend(sorted((ROOT / 'model/previews').rglob('*.png')))
    destination = ROOT / 'dist/dronepal-model.zip'
    destination.parent.mkdir(exist_ok=True)
    temporary = destination.with_suffix('.zip.tmp')
    with ZipFile(temporary, 'w', ZIP_DEFLATED) as archive:
        for file in files:
            base = RES if file.is_relative_to(RES) else ROOT
            archive.write(file, file.relative_to(base))
    temporary.replace(destination)
    print(destination)


if __name__ == '__main__':
    main()
