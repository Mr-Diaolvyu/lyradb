#!/usr/bin/env bash
# BS 架构版本·服务端打包脚本：构建前端 → 嵌入静态资源 → 打包可独立部署的 fat jar
# 用法：在项目根目录运行  bash package-server.sh
# 产物：backend/target/lyradb-backend-1.0.0-SNAPSHOT.jar （内嵌前端，浏览器访问 http://<host>:8080 即用）
# 运行：java -jar backend/target/lyradb-backend-1.0.0-SNAPSHOT.jar
#       环境变量：LYRADB_EDITION=personal|enterprise（默认 personal）；生产建议 SPRING_PROFILES_ACTIVE=prod
set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
FRONTEND="$ROOT/frontend"
BACKEND="$ROOT/backend"
STATIC="$BACKEND/src/main/resources/static"

echo "==> [1/3] 构建前端 (Vue)"
cd "$FRONTEND"
npm install
npm run build

echo "==> [2/3] 嵌入前端到后端 static 资源"
rm -rf "$STATIC"
mkdir -p "$STATIC"
cp -r "$FRONTEND/dist/"* "$STATIC/"

echo "==> [3/3] 打包后端 fat jar"
cd "$BACKEND"
mvn -q clean package -DskipTests

echo "==> 完成"
echo "产物: $BACKEND/target/lyradb-backend-1.0.0-SNAPSHOT.jar"
echo ""
echo "启动示例："
echo "  # 个人版（默认）"
echo "  java -jar $BACKEND/target/lyradb-backend-1.0.0-SNAPSHOT.jar"
echo "  # 企业版"
echo "  LYRADB_EDITION=enterprise java -jar $BACKEND/target/lyradb-backend-1.0.0-SNAPSHOT.jar"
echo ""
echo "启动后浏览器访问 http://localhost:8080 （企业版默认账号 admin/admin，请立即改密）"
