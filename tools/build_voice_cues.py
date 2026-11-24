"""Generate DronePal's original non-verbal chip cues offline using Python and ffmpeg or VLC.

The sounds consist solely of oscillators. They contain no speech, samples or third-party voices.
Minecraft consumes only the pre-rendered Ogg Vorbis files and localized captions.
"""
from array import array
import json
import math
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import wave

RES = Path(__file__).resolve().parents[1] / 'src/main/resources'
ASSETS = RES / 'assets/huhu_dronepal'
RATE = 24000
# Intervals in semitones, followed by localized descriptions of the cue.
CUES = {
    'hostile_near': ((0, 7, 7), '附近发现敌对目标', 'Hostiles nearby'),
    'fire_start': ((0, 5, 12), '开始交战', 'Engaging target'),
    'kill': ((7, 4, 0), '目标已清除', 'Target cleared'),
    'no_ammo': ((4, -2, -5), '弹药耗尽', 'Out of ammunition'),
    'friend_low': ((12, 7, 12), '生命体征偏低', 'Low health warning'),
    'low_ammo': ((7, 5, 2), '备弹偏低', 'Low reserve ammunition'),
    # Remaining captions exist so the packaged cues cover every authored voice path.
    'hostile_group': ((0, 3, 7), '多个敌对目标', 'Several hostiles'),
    'module_offline': ((-3, 0, -3), '模块离线', 'Module offline'),
    'rare_ore': ((0, 7, 12, 7), '发现稀有矿物', 'Rare ore found'),
    'fall': ((-2, -5, -9), '侦测到坠落', 'Fall detected'),
    'death': ((-5, -9, -12), '机体损毁', 'Drone destroyed'),
    'respawn': ((0, 5, 12, 17), '机体修复完成', 'Drone repaired'),
    'help': ((0, 4, 7), '指令帮助', 'Command help'),
    'thanks': ((0, 5, 9), '回应致谢', 'Acknowledging thanks'),
    'insult': ((3, -2, 0), '回应负面评价', 'Reacting to criticism'),
    'smalltalk': ((2, 7, 5), '闲聊回应', 'Small talk'),
    'unknown': ((0, 3, -2), '指令未识别', 'Unknown command'),
    'low_battery': ((5, 0, -7), '电量偏低', 'Low battery'),
    'dimension': ((0, 7, 14), '已进入新维度', 'Dimension changed'),
    'nightfall': ((7, 3, -2), '夜幕降临', 'Nightfall'),
    'rain_start': ((9, 5, 2), '开始下雨', 'Rain starting'),
}


def waveform(pack, intervals):
    chatty = pack == 'chatty'
    base = 540 if chatty else 370
    notes = (intervals[0], intervals[0] + 2, *intervals[1:]) if chatty else intervals
    durations = (.09, .065, .12, .19) if chatty else (.085, .085, .15)
    samples = array('h', [0] * 120)
    for index, (note, duration) in enumerate(zip(notes, durations)):
        frequency = base * 2 ** (note / 12)
        phase = 0.0
        for i in range(round(duration * RATE)):
            t = i / RATE
            bend = 1 + (.045 if chatty else .008) * math.sin(math.pi * t / duration)
            phase += math.tau * frequency * bend / RATE
            envelope = min(1, t / .008) * min(1, (duration-t) / .025) * math.exp(-t / .24)
            tone = math.sin(phase) + (.21 if chatty else .10) * math.sin(2 * phase)
            if not chatty:
                tone += .12 * math.sin(3 * phase)
            samples.append(round(7500 * envelope * tone))
        samples.extend([0] * round(RATE * (.018 if chatty and index < 2 else .034)))
    if sys.byteorder != 'little':
        samples.byteswap()
    return samples.tobytes()


def encode(wav, ogg):
    ffmpeg = shutil.which('ffmpeg')
    if ffmpeg:
        cmd = [ffmpeg, '-loglevel', 'error', '-y', '-i', str(wav), '-c:a', 'libvorbis', '-q:a', '4', str(ogg)]
    else:
        vlc = shutil.which('vlc') or '/Applications/VLC.app/Contents/MacOS/VLC'
        if not Path(vlc).is_file():
            raise RuntimeError('Install a local ffmpeg or VLC encoder to rebuild sound assets.')
        cmd = [vlc, '-I', 'dummy', '--ignore-config', '--no-media-library', '--no-video', '--no-repeat', '--no-loop', str(wav), '--sout',
               f'#transcode{{acodec=vorb,ab=64,channels=1,samplerate={RATE}}}:std{{access=file,mux=ogg,dst="{ogg}"}}', 'vlc://quit']
    subprocess.run(cmd, check=True, capture_output=True, timeout=25)
    data = ogg.read_bytes()
    if not data.startswith(b'OggS') or b'\x01vorbis\x00\x00\x00\x00\x01' not in data:
        raise ValueError('Expected a mono Ogg Vorbis stream')
    return data


def write_json(path, changes):
    current = json.loads(path.read_text()) if path.exists() else {}
    current.update(changes)
    path.write_text(json.dumps(current, ensure_ascii=False, indent=2)+'\n')


def main():
    paths = set()
    for file in (RES/'data/huhu_dronepal/lines').glob('*/*.json'):
        for line in json.loads(file.read_text())['lines']:
            if line.get('voice'):
                paths.add(line['voice'])
    events, english, chinese = {}, {}, {}
    with tempfile.TemporaryDirectory() as temp:
        for path in sorted(paths):
            pack, cue = path.split('/')
            intervals, zh, en = CUES[cue]
            wav = Path(temp) / (pack + '_' + cue + '.wav')
            with wave.open(str(wav), 'wb') as audio:
                audio.setparams((1, 2, RATE, 0, 'NONE', 'NONE'))
                audio.writeframes(waveform(pack, intervals))
            event = 'voice/' + path
            destination = ASSETS/'sounds'/f'{event}.ogg'
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_bytes(encode(wav, wav.with_suffix('.ogg')))
            key = 'subtitles.dronepal.' + event.replace('/', '.')
            events[event] = {'subtitle': key, 'sounds': ['huhu_dronepal:' + event]}
            chinese[key] = ('话痨芯片轻鸣：' if pack == 'chatty' else '战术芯片短鸣：') + zh
            english[key] = ('Companion chirps: ' if pack == 'chatty' else 'Tactical chip beeps: ') + en
    write_json(ASSETS/'sounds.json', events)
    write_json(ASSETS/'lang/en_us.json', english)
    write_json(ASSETS/'lang/zh_cn.json', chinese)
    print(f'Generated {len(events)} mono Vorbis cues with EN/ZH captions')


if __name__ == '__main__':
    main()
