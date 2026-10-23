#!/usr/bin/env bash
#
# 启动 Git 历史浏览器的 Web 界面
#
# 用法:
#   ./start.sh              # 起服务 (已在跑则先停掉)
#   ./start.sh --build      # 先重新构建再起
#   ./start.sh --stop       # 停掉
#
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
PORT=7788
JAR="$PROJECT_DIR/server/target/server-0.1.0-SNAPSHOT.jar"
APP_LOG="$PROJECT_DIR/server.log"
PID_FILE="$PROJECT_DIR/.server.pid"

# ===== 停止已有实例 =====
stop_server() {
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID" 2>/dev/null || true
            sleep 1
            kill -9 "$PID" 2>/dev/null || true
            echo "✅ 已停止旧进程 (PID $PID)"
        fi
        rm -f "$PID_FILE"
    fi
    # 兜底: 端口上还有谁在听就干掉
    LISTENERS=$(lsof -nP -iTCP:$PORT -sTCP:LISTEN -t 2>/dev/null || true)
    if [ -n "$LISTENERS" ]; then
        echo "$LISTENERS" | xargs kill -9 2>/dev/null || true
        echo "✅ 已清理端口 $PORT 上的残留进程"
    fi
}

case "${1:-}" in
    --stop)
        stop_server
        exit 0
        ;;
esac

# ===== 构建 =====
if [ "${1:-}" = "--build" ] || [ ! -f "$JAR" ]; then
    if ! command -v mvn >/dev/null 2>&1; then
        echo "❌ 找不到 mvn, 请安装 Maven 或加 --build 之外的预编译 jar"
        exit 1
    fi
    echo "→ 构建中..."
    (cd "$PROJECT_DIR" && mvn -q clean package -DskipTests)
    echo "✅ 构建完成"
fi

if [ ! -f "$JAR" ]; then
    echo "❌ 找不到 $JAR, 先执行 ./start.sh --build"
    exit 1
fi

# ===== 启动 =====
stop_server

echo "→ 启动中 (端口 $PORT)..."
nohup java -jar "$JAR" > "$APP_LOG" 2>&1 &
SERVER_PID=$!
echo "$SERVER_PID" > "$PID_FILE"

for i in $(seq 1 40); do
    if curl -sf --max-time 2 "http://localhost:$PORT/api/info?path=." >/dev/null 2>&1 \
       || curl -s --max-time 2 "http://localhost:$PORT/" >/dev/null 2>&1; then
        echo ""
        echo "========================================"
        echo " 🎉 Git 历史浏览器已启动"
        echo "========================================"
        echo ""
        echo "  界面:   http://localhost:$PORT"
        echo "  日志:   tail -f $APP_LOG"
        echo "  停止:   ./start.sh --stop"
        echo ""
        exit 0
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "❌ 启动失败, 日志尾部:"
        tail -20 "$APP_LOG"
        exit 1
    fi
    sleep 1
done

echo "❌ 40 秒内没就绪, 日志尾部:"
tail -20 "$APP_LOG"
exit 1
