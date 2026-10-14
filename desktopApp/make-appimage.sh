#!/bin/sh
# 把 :desktopApp:createDistributable 的便携目录打成单文件 .AppImage。
#
# 背景：Compose 的 TargetFormat.AppImage 不是 AppImage 单文件，而是 jpackage
# --type app-image 的便携目录别名（TargetFormat.outputDirName 对 AppImage 返回
# "app"、fileExt 直接 check 失败抛异常），产物与 createDistributable 完全相同，
# 里面没有 type-2 runtime + squashfs 附加体。真单文件只能由 appimagetool 生成，
# 本脚本负责摆出合规 AppDir（AppRun + <name>.desktop + <name>.png + usr/）再调它。
#
# 用法：make-appimage.sh <便携目录根> <icon.png> <输出 .AppImage> <appimagetool 路径>
#   便携目录根 = desktopApp/build/compose/binaries/main/app，其下应恰好一个便携目录。
# 仅 Linux（appimagetool 是 x86_64 ELF，由 CI 的 ubuntu job 跑）。
set -eu

APP_ROOT=$1
ICON=$2
OUT=$3
APPIMAGETOOL=$4

[ -d "$APP_ROOT" ] || { echo "便携目录根不存在：$APP_ROOT" >&2; exit 1; }
[ -f "$ICON" ] || { echo "图标不存在：$ICON" >&2; exit 1; }
[ -x "$APPIMAGETOOL" ] || { echo "appimagetool 不可执行：$APPIMAGETOOL" >&2; exit 1; }

# 便携目录根下必须恰好一个目录，名字即 jpackage --name（nativeDistributions.packageName）。
set -- "$APP_ROOT"/*/
[ "$#" -eq 1 ] || { echo "期望 $APP_ROOT 下恰好 1 个便携目录，实际 $# 个：$*" >&2; exit 1; }
[ -d "${1%/}" ] || { echo "便携目录根下没有便携目录：$APP_ROOT（先跑 :desktopApp:createDistributable）" >&2; exit 1; }
NAME=$(basename "${1%/}")
APP_DIR=$APP_ROOT/$NAME
[ -x "$APP_DIR/bin/$NAME" ] || { echo "便携目录里没有 bin/${NAME}：$APP_DIR" >&2; exit 1; }

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
APPDIR="$WORK/$NAME.AppDir"

mkdir -p "$APPDIR/usr"
# 便携目录整体进 usr/：jpackage 启动器按自身路径推 $APPDIR（= <mount>/usr），
# classpath 与 jlink 运行时全部相对可达（app-image 本就为可重定位设计，
# 故无需 AppImage 文档里那条 /usr → ././ 的二进制打补丁）。
cp -R "$APP_DIR/." "$APPDIR/usr/"

cat > "$APPDIR/AppRun" <<EOF
#!/bin/sh
# 入口：AppDir 会被挂到任意路径，一律按 AppRun 自身位置找载荷。
exec "\$(dirname "\$(readlink -f "\$0")")/usr/bin/$NAME" "\$@"
EOF
chmod +x "$APPDIR/AppRun"

# 图标用 jpackage 打包的同一张（2048×2048 PNG）：AppDir 顶层 <Icon 名>.png，
# .DirIcon 是规范里的缩略图标位。
cp "$ICON" "$APPDIR/$NAME.png"
cp "$ICON" "$APPDIR/.DirIcon"

cat > "$APPDIR/$NAME.desktop" <<EOF
[Desktop Entry]
Type=Application
Name=$NAME
Comment=$NAME
Exec=$NAME
Icon=$NAME
Terminal=false
Categories=Utility;
EOF

mkdir -p "$(dirname "$OUT")"
# APPIMAGE_EXTRACT_AND_RUN=1：CI 无 FUSE 也能跑 appimagetool 自身（它自己就是 AppImage）。
# --no-appstream：不发 AppStream metainfo，跳过该校验。
ARCH=x86_64 APPIMAGE_EXTRACT_AND_RUN=1 "$APPIMAGETOOL" --no-appstream "$APPDIR" "$OUT"

chmod +x "$OUT"
# appimagetool 退 0 也不等于产物可用：确认它真是 ELF（type-2 runtime 头）而不是空文件/文本报错。
# （od 的空格数在 BSD/GNU 下不同，故先去掉空白再比对。）
MAGIC=$(head -c 4 "$OUT" | od -An -tx1 | tr -d ' \n')
[ "$MAGIC" = "7f454c46" ] || { echo "产物不是 ELF（AppImage runtime 头）：${OUT}（头=${MAGIC}）" >&2; exit 1; }
ls -l "$OUT"
