#!/usr/bin/env bash
# 桌面打包一键脚本（PRD F10）：构建前端 → 嵌入静态资源 → jpackage 生成应用镜像
# 用法：在项目根目录运行  bash package-desktop.sh
# 产物：backend/target/desktop/LyraDB （自带精简 JRE 的可运行应用镜像）
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
FRONTEND="$ROOT/frontend"
BACKEND="$ROOT/backend"
STATIC="$BACKEND/src/main/resources/static"

export JAVA_HOME="${JAVA_HOME:-/d/jdk-21.0.9}"
export PATH="$JAVA_HOME/bin:$PATH"

echo "==> [1/4] 构建前端 (Vue)"
cd "$FRONTEND"
npm install
npm run build

echo "==> [2/4] 嵌入前端到后端 static 资源"
rm -rf "$STATIC"
mkdir -p "$STATIC"
cp -r "$FRONTEND/dist/"* "$STATIC/"

echo "==> [3/4] 构建后端 fat jar 并 jpackage 打包"
cd "$BACKEND"
mvn -q -Pdesktop package

echo "==> [4/4] 完成"
echo "产物: $BACKEND/target/desktop/LyraDB"
echo "运行: $BACKEND/target/desktop/LyraDB/bin/LyraDB   (Windows: LyraDB.exe)"
echo ""
echo "如需生成安装包(msi/dmg/deb)：在 pom.xml 的 desktop profile 中将 --type app-image"
echo "改为 --type msi（需安装 WiX Toolset）/ dmg / deb 后重新执行。"
