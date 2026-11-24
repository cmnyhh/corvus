#!/usr/bin/env bash
# Every offline contract the mod ships with. Run before handing out a build.
set -e
cd "$(dirname "$0")/.."
python3 tools/check_catalog.py
python3 tools/check_recipes.py
python3 tools/check_lang.py
python3 tools/check_model.py
