#!/usr/bin/env bash
# Init file for kupal-coder/IdleFantasy-modded
# Source (via GitHub MCP): https://github.com/kupal-coder/IdleFantasy-modded
# Fork of: https://github.com/tristinbaker/IdleFantasy
# What: Fantasy Inspired Idle Skilling Game - offline idle RPG for Android
# Stack: Kotlin, Jetpack Compose + Material3, Room, Hilt, AlarmManager, MVVM
# AppId: com.tristinbaker.idlefantasy, Namespace: com.fantasyidler, v1.15.4 (154000)
set -e

REPO="https://github.com/kupal-coder/IdleFantasy-modded.git"
DIR="IdleFantasy-modded"

echo "==> [1/4] Clone"
if [ ! -d "$DIR" ]; then
  git clone "$REPO" "$DIR"
fi
cd "$DIR"

echo "==> [2/4] Check tools (need: JDK 17+, Android SDK 35, Android Studio Hedgehog+)"
java -version 2>&1 | head -n 1 || echo "!! install JDK 17+"
echo "ANDROID_HOME=${ANDROID_HOME:-<not set>} ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<not set>}"
test -f gradlew || { echo "!! gradlew missing"; exit 1; }

echo "==> [3/4] Build debug APK"
chmod +x gradlew
./gradlew :app:assembleDebug

echo "==> [4/4] Done"
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
echo "Run: adb install -r app/build/outputs/apk/debug/app-debug.apk"
echo "Upstream docs: README.md, TRANSLATING.md, CONTRIBUTING.md, wiki/, docs/"
