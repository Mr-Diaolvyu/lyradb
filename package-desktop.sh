#!/usr/bin/env bash
# 桌面应用镜像打包：前端质量门禁 → 后端测试 → jpackage。
set -Eeuo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND="$ROOT/frontend"
BACKEND="$ROOT/backend"
STATIC="$BACKEND/src/main/resources/static"
IMAGE="$BACKEND/target/desktop/LyraDB"
VERSION="${1:-3.0.0}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "版本号必须使用 X.Y.Z 格式，实际：$VERSION" >&2
  exit 1
fi

for command_name in npm mvn jpackage; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "缺少命令：$command_name" >&2
    exit 1
  fi
done

echo "==> [1/4] 校验并构建前端"
pushd "$FRONTEND" >/dev/null
npm ci
npm run lint
npm run typecheck
npm run test
npm run build
popd >/dev/null

if [[ ! -d "$FRONTEND/dist" ]]; then
  echo "前端构建产物缺失：$FRONTEND/dist" >&2
  exit 1
fi

echo "==> [2/4] 嵌入前端静态资源"
case "$STATIC" in
  "$BACKEND"/src/main/resources/*) ;;
  *)
    echo "拒绝清理意外路径：$STATIC" >&2
    exit 1
    ;;
esac
rm -rf -- "$STATIC"
mkdir -p -- "$STATIC"
cp -R -- "$FRONTEND/dist/." "$STATIC/"

echo "==> [3/4] 验证后端并生成桌面应用镜像"
pushd "$BACKEND" >/dev/null
mvn -B -q -Pdesktop clean verify "-Drevision=$VERSION"
popd >/dev/null

if [[ ! -x "$IMAGE/bin/LyraDB" && ! -f "$IMAGE/LyraDB.exe" ]]; then
  echo "jpackage 产物缺失：$IMAGE" >&2
  echo "请确认 Maven 使用的 JDK 包含 jpackage。" >&2
  exit 1
fi

echo "==> [4/4] 完成"
echo "应用镜像：$IMAGE"
