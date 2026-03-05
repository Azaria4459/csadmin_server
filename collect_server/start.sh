#!/bin/bash

APP_NAME="wechat-collector"
JAR_FILE="target/wechat-collector-jar-with-dependencies.jar"
CONFIG_PATH="./config/app.yml"
# 获取当前目录的绝对路径
CURRENT_DIR=$(cd "$(dirname "$0")" && pwd)
LIBRARY_PATH="${CURRENT_DIR}/java_sdk"
LOG_DIR="./logs"
PID_FILE="${APP_NAME}.pid"

# 创建日志目录
mkdir -p ${LOG_DIR}

# 清空上一轮日志
: > ${LOG_DIR}/wechat-collector.log
: > ${LOG_DIR}/error.log
: > ${LOG_DIR}/console.log

# 如有源码或构建文件变更，自动重新打包
NEED_BUILD=0
if [ ! -f ${JAR_FILE} ]; then
    NEED_BUILD=1
else
    # 任一源码/资源或 pom.xml 比 jar 新则需要重建
    if find src -type f -newer ${JAR_FILE} -print -quit | grep -q .; then
        NEED_BUILD=1
    elif [ pom.xml -nt ${JAR_FILE} ]; then
        NEED_BUILD=1
    fi
fi

if [ ${NEED_BUILD} -eq 1 ]; then
    echo "Detected source changes or missing JAR, building project..."
    if [ -x ./mvnw ]; then
        ./mvnw clean package >> ${LOG_DIR}/console.log 2>&1
    else
        mvn clean package >> ${LOG_DIR}/console.log 2>&1
    fi
    if [ $? -ne 0 ]; then
        echo "Build failed, see ${LOG_DIR}/console.log"
        exit 1
    fi
fi

# 检查是否已经在运行
if [ -f ${PID_FILE} ]; then
    OLD_PID=$(cat ${PID_FILE})
    if ps -p ${OLD_PID} > /dev/null 2>&1; then
        echo "${APP_NAME} is running with PID: ${OLD_PID}, stopping it..."
        if [ -x ./stop.sh ]; then
            ./stop.sh
        else
            kill ${OLD_PID} 2>/dev/null || true
            for i in {1..30}; do
                if ps -p ${OLD_PID} > /dev/null 2>&1; then
                    sleep 1
                else
                    break
                fi
            done
            if ps -p ${OLD_PID} > /dev/null 2>&1; then
                echo "Force stopping PID: ${OLD_PID}"
                kill -9 ${OLD_PID} 2>/dev/null || true
            fi
            rm -f ${PID_FILE}
        fi
    else
        echo "Removing stale PID file"
        rm -f ${PID_FILE}
    fi
fi

# 检查JAR文件是否存在
if [ ! -f ${JAR_FILE} ]; then
    echo "Error: JAR file not found: ${JAR_FILE}"
    echo "Please run 'mvn clean package' first"
    exit 1
fi

# 检查配置文件是否存在
if [ ! -f ${CONFIG_PATH} ]; then
    echo "Error: Config file not found: ${CONFIG_PATH}"
    exit 1
fi

# 检查native库是否存在
if [ ! -f ${LIBRARY_PATH}/libWeWorkFinanceSdk_Java.so ]; then
    echo "Error: Native library not found: ${LIBRARY_PATH}/libWeWorkFinanceSdk_Java.so"
    exit 1
fi

echo "Starting ${APP_NAME}..."

# 启动应用
nohup java \
  -Djava.library.path=${LIBRARY_PATH} \
  -Dconfig.path=${CONFIG_PATH} \
  -Xms512m -Xmx1024m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -jar ${JAR_FILE} \
  > ${LOG_DIR}/console.log 2>&1 &

# 保存进程ID
echo $! > ${PID_FILE}

# 等待启动
sleep 2

# 检查是否启动成功
if ps -p $(cat ${PID_FILE}) > /dev/null 2>&1; then
    echo "${APP_NAME} started successfully with PID: $(cat ${PID_FILE})"
    echo "Logs: ${LOG_DIR}/wechat-collector.log"
    echo "Health check: http://localhost:7070/health"
else
    echo "Failed to start ${APP_NAME}"
    echo "Check logs: ${LOG_DIR}/console.log"
    rm -f ${PID_FILE}
    exit 1
fi

