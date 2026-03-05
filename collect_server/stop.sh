#!/bin/bash

APP_NAME="wechat-collector"
PID_FILE="${APP_NAME}.pid"

if [ ! -f ${PID_FILE} ]; then
    echo "PID file not found. Is ${APP_NAME} running?"
    exit 1
fi

PID=$(cat ${PID_FILE})

if ! ps -p ${PID} > /dev/null 2>&1; then
    echo "${APP_NAME} is not running (stale PID file)"
    rm -f ${PID_FILE}
    exit 1
fi

echo "Stopping ${APP_NAME} (PID: ${PID})..."

# 优雅关闭
kill ${PID}

# 等待进程结束
TIMEOUT=30
COUNT=0
while ps -p ${PID} > /dev/null 2>&1 && [ ${COUNT} -lt ${TIMEOUT} ]; do
    sleep 1
    COUNT=$((COUNT + 1))
    echo -n "."
done
echo ""

# 如果进程还在运行，强制杀死
if ps -p ${PID} > /dev/null 2>&1; then
    echo "Process did not stop gracefully, forcing shutdown..."
    kill -9 ${PID}
    sleep 1
fi

# 清理PID文件
rm -f ${PID_FILE}

if ps -p ${PID} > /dev/null 2>&1; then
    echo "Failed to stop ${APP_NAME}"
    exit 1
else
    echo "${APP_NAME} stopped successfully"
fi

