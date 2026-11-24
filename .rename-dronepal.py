#!/usr/bin/env python3
"""Batch rename: modId/namespace dronepal -> huhu_dronepal. Java package dev.dronepal and
translation keys (dronepal.*) stay untouched."""
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
APPLY = "--apply" in sys.argv

SKIP_DIRS = {
    "build", ".gradle", ".gradle-tacz-curios-test", "run", "run-tacz", "dist",
    "artifacts", "handoff", "model", "__pycache__", ".git", "node_modules", "saves",
}
ROOTS = ["src", "gradle", "docs", "tools"]
TOP_FILES = ["README.md", "build.gradle", "settings.gradle", "gen_drone.py", "gen_drone_v2.py"]
BINARY_EXT = {".png", ".nbt", ".ogg", ".zip", ".jar", ".pyc", ".bbmodel", ".ogg.mcmeta", ".ico", ".pdf"}

# directory renames: old -> new (relative to ROOT)
DIR_RENAMES = [
    "src/main/resources/assets/dronepal",
    "src/main/resources/assets/dronepal_survival",
    "src/main/resources/assets/dronepal_terminal",
    "src/main/resources/data/dronepal",
    "src/curiosGameTest/resources/data/dronepal",
    "src/taczGameTest/resources/data/dronepal",
]
FILE_RENAMES = [
    "src/main/resources/dronepal.lamps.mixins.json",
    "src/main/resources/dronepal.tacz.mixins.json",
]

# ordered (regex, replacement) applied to every text file in scope
RULES = [
    # 1. resource namespace literals: "dronepal:foo", dronepal:drone, dronepal:voice/...
    (r"dronepal:", "huhu_dronepal:"),
    # 2. mixin config file names (dot form, not a translation key)
    (r"dronepal\.lamps\.mixins\.json", "huhu_dronepal.lamps.mixins.json"),
    (r"dronepal\.tacz\.mixins\.json", "huhu_dronepal.tacz.mixins.json"),
    # 3. neoforge.mods.toml
    (r"\[\[dependencies\.dronepal\]\]", "[[dependencies.huhu_dronepal]]"),
    (r'modId="dronepal"', 'modId="huhu_dronepal"'),
    (r'displayName="Dronepal"', 'displayName="Corvus"'),
    (r"description='''A personal hovering companion.'''", "description='''Corvus — 随行无人机搭档。'''"),
    # 4. gradle mod identifier
    (r"mods\.dronepal\b", "mods.huhu_dronepal"),
    (r"mods \{ dronepal \{", "mods { huhu_dronepal {"),
    (r"rootProject\.name = 'dronepal'", "rootProject.name = 'huhu_dronepal'"),
    # 5. java singletons: the mod id constant, namespaced lookups, event bus / gametest namespaces
    (r'public static final String ID = "dronepal";', 'public static final String ID = "huhu_dronepal";'),
    (r'fromNamespaceAndPath\("dronepal"', 'fromNamespaceAndPath("huhu_dronepal"'),
    (r'GameTestHolder\("dronepal"\)', 'GameTestHolder("huhu_dronepal")'),
    (r'modid="dronepal"', 'modid="huhu_dronepal"'),
    (r"enabledGameTestNamespaces', 'dronepal'", "enabledGameTestNamespaces', 'huhu_dronepal'"),
    # 6. resource paths in docs / asset generator scripts
    (r"assets/dronepal", "assets/huhu_dronepal"),
    (r"data/dronepal", "data/huhu_dronepal"),
]

# after the rules: restore internal CustomData keys that merely look namespaced
POST_FIX = [
    ("src/main/java/dev/dronepal/DroneIdentity.java",
     'tag.getString("huhu_dronepal:name")', 'tag.getString("dronepal:name")'),
    ("src/main/java/dev/dronepal/Frames.java",
     'List.of("dronepal_frame", "huhu_dronepal:frame", "frame")',
     'List.of("dronepal_frame", "dronepal:frame", "frame")'),
]


def in_scope(p: Path) -> bool:
    if any(part in SKIP_DIRS for part in p.relative_to(ROOT).parts[:-1]):
        return False
    return True


def text_files():
    for r in ROOTS:
        base = ROOT / r
        for p in sorted(base.rglob("*")):
            if p.is_file() and in_scope(p) and p.suffix not in BINARY_EXT:
                yield p
    for name in TOP_FILES:
        p = ROOT / name
        if p.is_file():
            yield p


def read_text(p: Path):
    data = p.read_bytes()
    if b"\0" in data:
        return None
    try:
        return data.decode("utf-8")
    except UnicodeDecodeError:
        return None


def main():
    plan_dirs, plan_files, plan_text = [], [], []

    for rel in DIR_RENAMES:
        src = ROOT / rel
        if src.is_dir():
            dst = src.with_name(src.name.replace("dronepal", "huhu_dronepal", 1))
            plan_dirs.append((src, dst))
    for rel in FILE_RENAMES:
        src = ROOT / rel
        if src.is_file():
            plan_files.append((src, src.with_name(src.name.replace("dronepal", "huhu_dronepal", 1))))

    for p in text_files():
        text = read_text(p)
        if text is None:
            continue
        new = text
        for pat, rep in RULES:
            new = re.sub(pat, rep, new)
        if new != text:
            plan_text.append((p, text, new))

    print(f"dirs: {len(plan_dirs)}  files: {len(plan_files)}  text files: {len(plan_text)}")
    for s, d in plan_dirs:
        print("  DIR ", s.relative_to(ROOT), "->", d.name)
    for s, d in plan_files:
        print("  FILE", s.relative_to(ROOT), "->", d.name)
    for p, t, n in plan_text:
        changed = sum(1 for a, b in zip(t.splitlines(), n.splitlines()) if a != b)
        print(f"  TEXT {p.relative_to(ROOT)} ({changed} lines)")

    if not APPLY:
        print("\n(dry run — pass --apply to write)")
        return

    # text first: paths are resolved before any directory moves
    for p, t, n in plan_text:
        p.write_text(n, encoding="utf-8")
    for s, d in plan_dirs:
        if d.exists():
            raise SystemExit(f"refusing, target exists: {d}")
        s.rename(d)
    for s, d in plan_files:
        if d.exists():
            raise SystemExit(f"refusing, target exists: {d}")
        s.rename(d)
    for rel, old, new in POST_FIX:
        p = ROOT / rel
        txt = p.read_text(encoding="utf-8")
        if old not in txt:
            raise SystemExit(f"post-fix pattern missing in {rel}: {old!r}")
        p.write_text(txt.replace(old, new), encoding="utf-8")
    print("\napplied.")


if __name__ == "__main__":
    main()
