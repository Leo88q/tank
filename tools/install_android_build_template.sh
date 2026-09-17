#!/usr/bin/env bash
# Раскладывает Android Build Template (нужен для gradle-экспорта с плагином MWA)
# из локальных экспортных шаблонов Godot в res://android/build.
# В CI то же самое делает шаг "Install Android build template".
set -euo pipefail
cd "$(dirname "$0")/.."

GODOT_VERSION="${GODOT_VERSION:-4.7.2}"
TPL="${GODOT_TEMPLATES:-$HOME/.local/share/godot/export_templates/${GODOT_VERSION}.stable}"
[ -f "$TPL/android_source.zip" ] || { echo "нет $TPL/android_source.zip"; exit 1; }

rm -rf /tmp/asrc android/build
unzip -oq "$TPL/android_source.zip" -d /tmp/asrc
if [ -d /tmp/asrc/android_source ]; then
  mv /tmp/asrc/android_source android/build
else
  mkdir -p android/build && mv /tmp/asrc/* android/build/
fi
echo "OK: android/build готов"
