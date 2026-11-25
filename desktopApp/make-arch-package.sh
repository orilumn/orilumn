#!/bin/sh
# 把 :desktopApp:createDistributable 的便携目录打成 Arch / EndeavourOS 的 pacman 包
# （.pkg.tar.zst）。jpackage 没有 pacman 目标，这一步是补上的。
#
# 为什么走容器：pacman 包的元数据（.PKGINFO / .MTREE / .BUILDINFO）由 makepkg 生成才规范，
# 手写 tar 容易缺东西、也没法验证。CI 的 ubuntu runner 自带 docker，于是：
#   1. archlinux:base-devel 里以普通用户跑 makepkg（makepkg 拒绝以 root 构建）；
#   2. archlinux:base 里 pacman -U 真装一遍，确认包能被 pacman 接受、桌面入口与启动器落地。
#
# 用法：make-arch-package.sh <便携目录根> <版本> <输出 .pkg.tar.zst>
#   <便携目录根> = desktopApp/build/compose/binaries/main/app（其下恰好一个便携目录）
set -eu

APP_ROOT=$1
VERSION=$2
OUT=$3

[ -d "$APP_ROOT" ] || { echo "便携目录根不存在：$APP_ROOT" >&2; exit 1; }
[ -n "$VERSION" ] || { echo "版本号为空：版本必须来自 gradle.properties 的 orilumn.versionName" >&2; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "需要 docker：makepkg 要在 archlinux 容器里跑" >&2; exit 1; }

# 便携目录根下必须恰好一个目录，名字即 jpackage --name（nativeDistributions.packageName）。
set -- "$APP_ROOT"/*/
[ "$#" -eq 1 ] || { echo "期望 $APP_ROOT 下恰好 1 个便携目录，实际 $# 个：$*" >&2; exit 1; }
APP_DIR=$(cd "${1%/}" && pwd)
NAME=$(basename "$APP_DIR")
[ -x "$APP_DIR/bin/$NAME" ] || { echo "便携目录里没有 bin/${NAME}：$APP_DIR" >&2; exit 1; }

HERE=$(cd "$(dirname "$0")" && pwd)
ICON="$HERE/src/main/resources/icon.png"
[ -f "$ICON" ] || { echo "图标不存在：$ICON" >&2; exit 1; }

WORK=$(mktemp -d)
# 清理失败不许把整步判红：真正的校验在下面，临时目录残留顶多占点磁盘。
trap 'rm -rf "$WORK" || true' EXIT
BUILD="$WORK/build"
mkdir -p "$BUILD"
cp "$HERE/arch/PKGBUILD" "$HERE/arch/orilumn.install" "$BUILD/"

# makepkg 以普通用户构建：容器里建 builder，把构建目录交给它，变量一律显式 env 传下去
# （su 在部分发行版会重置环境，runuser + env 不依赖那个默认行为）。
docker run --rm \
    -e ORILUMN_VERSION="$VERSION" \
    -e APP_IMAGE_DIR=/appimage \
    -e ICON_PNG=/icon.png \
    -e HOST_UID="$(id -u)" \
    -e HOST_GID="$(id -g)" \
    -v "$BUILD:/build" \
    -v "$APP_DIR:/appimage:ro" \
    -v "$ICON:/icon.png:ro" \
    -w /build \
    --entrypoint bash \
    archlinux:base-devel -lc '
        set -eu
        # zstd 有了 makepkg 才能压成 .pkg.tar.zst（现在的默认压缩格式）。
        pacman -Sy --noconfirm --needed zstd >/dev/null
        id builder >/dev/null 2>&1 || useradd -m builder
        chown -R builder /build
        chmod a+rwx /build
        runuser -u builder -- env HOME=/home/builder \
            ORILUMN_VERSION="$ORILUMN_VERSION" \
            APP_IMAGE_DIR="$APP_IMAGE_DIR" \
            ICON_PNG="$ICON_PNG" \
            makepkg -f --nodeps --noconfirm
        # 产物交回宿主用户：builder（uid 1000）建的目录宿主没写权限，宿主删不掉里面
        # 的文件，临时目录的清理会失败。
        chown -R "$HOST_UID:$HOST_GID" /build
    '

PKG=$(ls "$BUILD"/*.pkg.tar.zst 2>/dev/null | head -1)
[ -n "$PKG" ] || { echo "makepkg 没有产出 .pkg.tar.zst：" >&2; ls -l "$BUILD" >&2; exit 1; }

mkdir -p "$(dirname "$OUT")"
cp "$PKG" "$OUT"

# 干净容器里真装一次。用 -dd（跳过**全部**依赖检查，`--nodeps` 只跳过版本检查）：
# 这里验证的是包本身的结构与落点，依赖解析留给真机 —— 容器里没有 X11/GTK 那一套，
# 也没有同步数据库，装依赖既慢又没必要。
docker run --rm \
    -e ORILUMN_VERSION="$VERSION" \
    -e PKG_NAME="$(basename "$OUT")" \
    -v "$(cd "$(dirname "$OUT")" && pwd):/pkg:ro" \
    --entrypoint bash \
    archlinux:base -lc '
        set -eu
        pacman -U --noconfirm -dd "/pkg/$PKG_NAME" >/dev/null
        test -f /usr/share/applications/orilumn.desktop
        test -x /opt/orilumn/bin/Orilumn
        test -e /usr/bin/orilumn
        # 版本必须与 gradle.properties 派生的版本一致（pkgver-pkgrel）。
        pacman -Qi orilumn | grep -q "^Version *: ${ORILUMN_VERSION}-1"
        pacman -Ql orilumn | grep -q /usr/share/applications/orilumn.desktop
        echo "安装自检通过：orilumn ${ORILUMN_VERSION}-1"
    '

ls -l "$OUT"
