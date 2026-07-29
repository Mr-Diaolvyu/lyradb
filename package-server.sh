#!/usr/bin/env bash
# BS 服务端打包：前端质量门禁 → 嵌入静态资源 → 后端测试与 fat jar。
set -Eeuo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
FRONTEND="$ROOT/frontend"
BACKEND="$ROOT/backend"
STATIC="$BACKEND/src/main/resources/static"
VERSION="${1:-3.0.1}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "版本号必须使用 X.Y.Z 格式，实际：$VERSION" >&2
  exit 1
fi

for command_name in npm mvn; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "缺少命令：$command_name" >&2
    exit 1
  fi
done

echo "==> [1/3] 校验并构建前端"
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

echo "==> [2/3] 嵌入前端静态资源"
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

echo "==> [3/3] 验证并打包后端"
pushd "$ROOT" >/dev/null
mvn -B -q -pl backend -am clean verify "-Drevision=$VERSION"
popd >/dev/null

ARTIFACT="$BACKEND/target/lyradb-backend-$VERSION.jar"
if [[ ! -f "$ARTIFACT" ]]; then
  echo "构建结束但未找到产物：$ARTIFACT" >&2
  exit 1
fi

echo "==> 完成"
echo "产物：$ARTIFACT"
echo "生产启动必须设置 SPRING_PROFILES_ACTIVE=prod、JASYPT_PASSWORD 与 LYRADB_DB_PASSWORD；企业空用户库首次启动还需管理员 bootstrap 变量。"
