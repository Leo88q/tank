#!/usr/bin/env bash
# Сборка AAR плагина OrbitfallMwa (Solana Mobile Wallet Adapter bridge).
# Требования: JDK 17, Android SDK (ANDROID_HOME), Gradle 8.11+ (или укажите GRADLE=/path/to/gradle).
# В CI это делает шаг "Build OrbitfallMwa plugin AAR" (.github/workflows/fork-build.yml).
set -euo pipefail
cd "$(dirname "$0")/.."

GRADLE="${GRADLE:-gradle}"
if ! command -v "$GRADLE" >/dev/null 2>&1; then
  echo "gradle не найден в PATH; задайте GRADLE=/path/to/gradle (8.11+)" >&2
  exit 1
fi

"$GRADLE" -p android/orbitfall-mwa :plugin:assembleRelease
mkdir -p addons/orbitfall_mwa
cp android/orbitfall-mwa/plugin/build/outputs/aar/plugin-release.aar \
   addons/orbitfall_mwa/orbitfall_mwa-release.aar
echo "OK: addons/orbitfall_mwa/orbitfall_mwa-release.aar"
