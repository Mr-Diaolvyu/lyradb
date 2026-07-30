#!/usr/bin/env bash
# LyraDB 个人版原生桌面打包：core/desktop 测试 → jpackage → 独立架构扫描 → 原生冒烟。
set -Eeuo pipefail

ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
DESKTOP="$ROOT/desktop"
IMAGE_BASE="$DESKTOP/target/desktop"
VERSION="${1:-3.1.0}"
WORKSPACE="$ROOT/数据架构师工作空间"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "版本号必须使用 X.Y.Z 格式，实际：$VERSION" >&2
  exit 1
fi

for command_name in mvn jpackage jar jdeps; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "缺少命令：$command_name" >&2
    exit 1
  fi
done

echo "==> [1/4] 运行 core 与原生 desktop 测试并生成应用镜像"
(
  cd "$ROOT"
  mvn -B -ntp -pl desktop -am -Pdesktop-package clean verify "-Drevision=$VERSION"
)

case "$(uname -s)" in
  Darwin*)
    PLATFORM="macos"
    IMAGE="$IMAGE_BASE/LyraDB.app"
    APP_DIR="$IMAGE/Contents/app"
    EXECUTABLE="$IMAGE/Contents/MacOS/LyraDB"
    ;;
  Linux*)
    PLATFORM="linux"
    IMAGE="$IMAGE_BASE/LyraDB"
    APP_DIR="$IMAGE/lib/app"
    EXECUTABLE="$IMAGE/bin/LyraDB"
    ;;
  MINGW*|MSYS*|CYGWIN*)
    PLATFORM="windows"
    IMAGE="$IMAGE_BASE/LyraDB"
    APP_DIR="$IMAGE/app"
    EXECUTABLE="$IMAGE/LyraDB.exe"
    ;;
  *)
    echo "不支持的打包平台：$(uname -s)" >&2
    exit 1
    ;;
esac

LAUNCHER_CONFIG="$APP_DIR/LyraDB.cfg"
if [[ ! -e "$EXECUTABLE" ]]; then
  echo "jpackage 原生应用镜像缺失：$IMAGE" >&2
  exit 1
fi
if [[ "$PLATFORM" != "windows" && ! -x "$EXECUTABLE" ]]; then
  echo "jpackage 原生启动器不可执行：$EXECUTABLE" >&2
  exit 1
fi
if [[ ! -f "$LAUNCHER_CONFIG" ]]; then
  echo "jpackage 启动配置缺失：$LAUNCHER_CONFIG" >&2
  exit 1
fi
if ! grep -Eq \
  '^app[.]mainclass=io[.]github[.]lexaquila[.]lyradb[.]desktop[.]NativeDesktopApplication[[:space:]]*$' \
  "$LAUNCHER_CONFIG"; then
  echo "启动器未指向原生桌面入口：$LAUNCHER_CONFIG" >&2
  exit 1
fi
if grep -Eq \
  'LyraDbApplication|spring[.]profiles[.]active=desktop|server[.]port|JarLauncher' \
  "$LAUNCHER_CONFIG"; then
  echo "启动器仍包含旧 B/S 桌面包装配置：$LAUNCHER_CONFIG" >&2
  exit 1
fi

echo "==> [2/4] 独立扫描依赖清单、JAR 内容与核心字节码"
DESKTOP_JAR="$APP_DIR/lyradb-desktop-$VERSION.jar"
CORE_JAR="$APP_DIR/lyradb-core-$VERSION.jar"
for required_jar in "$DESKTOP_JAR" "$CORE_JAR"; do
  if [[ ! -f "$required_jar" ]]; then
    echo "原生桌面核心 JAR 缺失：$required_jar" >&2
    exit 1
  fi
done

shopt -s nullglob
APP_JARS=("$APP_DIR"/*.jar)
shopt -u nullglob
if (( ${#APP_JARS[@]} == 0 )); then
  echo "jpackage 应用依赖目录中没有 JAR：$APP_DIR" >&2
  exit 1
fi

FORBIDDEN_JAR_PATTERN='(^lyradb-backend-|^spring-web(mvc|flux)?-|^tomcat-embed-|^jetty-|^undertow-|javafx-web|jxbrowser|(^|-)cef(-|$)|playwright)'
FORBIDDEN_ENTRY_PATTERN='(^|/)(javafx/scene/web|org/cef|com/teamdev/jxbrowser|com/microsoft/playwright)/'
for jar_file in "${APP_JARS[@]}"; do
  jar_name="${jar_file##*/}"
  if [[ "$jar_name" =~ $FORBIDDEN_JAR_PATTERN ]]; then
    echo "原生桌面镜像包含禁止的 B/S 或嵌入式浏览器依赖：$jar_name" >&2
    exit 1
  fi
  if ! jar_entries="$(jar tf "$jar_file")"; then
    echo "无法检查 JAR 内容：$jar_file" >&2
    exit 1
  fi
  if forbidden_entry="$(grep -Eim1 "$FORBIDDEN_ENTRY_PATTERN" <<< "$jar_entries")"; then
    echo "原生桌面镜像包含嵌入式浏览器类：$jar_name ($forbidden_entry)" >&2
    exit 1
  fi
done

if ! jdeps_output="$(
  jdeps \
    --ignore-missing-deps \
    --multi-release 17 \
    -verbose:class \
    --class-path "$APP_DIR/*" \
    "$DESKTOP_JAR" \
    "$CORE_JAR" 2>&1
)"; then
  echo "$jdeps_output" >&2
  echo "jdeps 原生桌面字节码扫描失败" >&2
  exit 1
fi
FORBIDDEN_DEPENDENCY_PATTERN='java[.]awt[.]Desktop|com[.]sun[.]net[.]httpserver[.]|javafx[.]scene[.]web[.]|org[.]cef[.]|com[.]teamdev[.]jxbrowser[.]|com[.]microsoft[.]playwright[.]|org[.]springframework[.]boot[.]SpringApplication'
if forbidden_dependency="$(
  grep -Eim1 "$FORBIDDEN_DEPENDENCY_PATTERN" <<< "$jdeps_output"
)"; then
  echo "原生桌面核心字节码引用了浏览器、本地 HTTP 服务或 B/S 启动入口：" >&2
  echo "$forbidden_dependency" >&2
  exit 1
fi

echo "==> [3/4] 执行原生架构冒烟测试"
mkdir -p -- "$WORKSPACE"
SMOKE_DIR="$(mktemp -d "$WORKSPACE/native-desktop-smoke.XXXXXX")"
cleanup() {
  case "$SMOKE_DIR" in
    "$WORKSPACE"/native-desktop-smoke.*) rm -rf -- "$SMOKE_DIR" ;;
    *) echo "拒绝清理意外路径：$SMOKE_DIR" >&2 ;;
  esac
}
trap cleanup EXIT

MARKER="$SMOKE_DIR/native-smoke.json"
"$EXECUTABLE" "--smoke-test=$MARKER" "--data-dir=$SMOKE_DIR/data"
test -f "$MARKER"
grep -Eq '"status"[[:space:]]*:[[:space:]]*"ok"' "$MARKER"
grep -Eq '"architecture"[[:space:]]*:[[:space:]]*"native-swing"' "$MARKER"
grep -Eq '"nativeUiToolkit"[[:space:]]*:[[:space:]]*"javax[.]swing"' "$MARKER"
grep -Eq '"browserLaunched"[[:space:]]*:[[:space:]]*false' "$MARKER"
grep -Eq '"webViewEmbedded"[[:space:]]*:[[:space:]]*false' "$MARKER"
grep -Eq '"localHttpServerStarted"[[:space:]]*:[[:space:]]*false' "$MARKER"
grep -Eq '"aiConfigAvailable"[[:space:]]*:[[:space:]]*true' "$MARKER"
grep -Eq '"driverCount"[[:space:]]*:[[:space:]]*9' "$MARKER"

echo "==> [4/4] 完成"
echo "原生应用镜像：$IMAGE"
