#!/bin/sh
# 给 jpackage 打出的 dmg 换上自定义宗卷图标。
#
# 背景：jpackage 只把 --icon 写进 .app 和 .VolumeIcon.icns 的占位，
# 实际 DMG 挂载后显示的是默认 Java Duke 图标。这里把宗卷根的
# .VolumeIcon.icns 替换为我们的 icon.icns 并置自定义图标位。
#
# 用法：stamp-dmg-icon.sh <Orilumn-1.0.dmg> <icons/icon.icns>
# 仅 macOS（依赖 hdiutil / SetFile，Xcode CLT 自带）。
set -euo pipefail

DMG="$1"
ICON="$2"

command -v hdiutil >/dev/null || { echo "need hdiutil (macOS only)"; exit 1; }
command -v SetFile >/dev/null || { echo "need SetFile (Xcode CLT)"; exit 1; }
[ -f "$DMG" ] || { echo "dmg not found: $DMG"; exit 1; }
[ -f "$ICON" ] || { echo "icon not found: $ICON"; exit 1; }

WORK="$(mktemp -d)"
RW="$WORK/rw.dmg"
MOUNT="$WORK/vol"
trap 'hdiutil detach "$MOUNT" >/dev/null 2>&1 || true; rm -rf "$WORK"' EXIT

# jpackage 产物是只读压缩镜像，先转读写再挂载（不动原文件）。
hdiutil convert "$DMG" -format UDRW -o "$RW" >/dev/null
hdiutil attach "$RW" -noverify -noautoopen -mountpoint "$MOUNT" >/dev/null

# 原 .VolumeIcon.icns 只读，先删后拷。
rm -f "$MOUNT/.VolumeIcon.icns"
cp "$ICON" "$MOUNT/.VolumeIcon.icns"
# 宗卷根置自定义图标位（Finder 边栏/桌面即显示该图标）。
SetFile -a C "$MOUNT"

hdiutil detach "$MOUNT" >/dev/null
trap - EXIT

# 压回只读 dmg，原子替换原文件。
rm -f "$DMG"
hdiutil convert "$RW" -format UDZO -o "$DMG" >/dev/null
rm -rf "$WORK"
echo "stamped volume icon: $DMG"
