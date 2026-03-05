# 服务器部署清单

本文档说明部署 `collect_server` 到服务器需要上传的文件。

## 部署方式

### 方式一：使用编译好的JAR包（推荐）

如果已经在本地编译好JAR包，直接上传编译产物即可。

### 方式二：上传源码在服务器编译

如果服务器有Maven环境，可以上传源码在服务器上编译。

---

## 必须上传的文件

### 1. 编译产物（方式一）

```
target/
  └── wechat-collector-jar-with-dependencies.jar  # 打包好的可执行JAR（包含所有依赖）
```

**说明**：这是Maven打包后的fat JAR，包含所有依赖，可以直接运行。

### 2. 配置文件

```
config/
  └── app.yml  # 应用配置文件（必须配置数据库、微信、OSS等信息）
```

**重要**：上传前需要根据服务器环境修改配置：
- 数据库连接信息
- 企业微信配置（corpId、secret、私钥路径等）
- OSS配置（如果使用）
- Gemini API Key（如果启用情绪分析）
- 飞书配置（如果启用飞书通知）

### 3. 启动脚本

```
start.sh      # 启动脚本（必须，需要有执行权限）
stop.sh       # 停止脚本（必须，需要有执行权限）
```

**说明**：
- 确保脚本有执行权限：`chmod +x start.sh stop.sh`
- `start.sh` 会自动检查并编译（如果JAR不存在）

### 4. Java SDK（企业微信SDK）

```
java_sdk/
  ├── libWeWorkFinanceSdk_Java.so  # Native库（必须，Linux版本）
  ├── wework-finance-sdk.jar       # SDK JAR包
  └── com/tencent/wework/Finance.* # SDK类文件
```

**重要**：
- `libWeWorkFinanceSdk_Java.so` 必须是Linux版本的（如果服务器是Linux）
- 如果是其他操作系统，需要对应版本的native库
- 确保文件有读取权限

### 5. 私钥文件（可选但推荐）

```
keys/
  └── private_key_pkcs1.pem  # RSA私钥文件（用于解密消息）
```

**说明**：
- 如果配置文件中私钥路径指向此文件，则需要上传
- 确保文件权限安全：`chmod 600 keys/private_key_pkcs1.pem`

### 6. SQL脚本（数据库初始化）

```
sql/
  ├── init.sql                           # 数据库初始化脚本
  ├── add_sentiment_analysis_fields.sql  # 情绪分析字段（如果未执行）
  ├── add_intent_analysis_tables.sql     # 购买意向表（如果未执行）
  └── 其他迁移脚本...
```

**说明**：
- 首次部署需要执行 `init.sql`
- 如果数据库已存在，只需执行新增的迁移脚本

---

## 可选文件

### 1. Maven Wrapper（如果需要在服务器编译）

```
mvnw          # Maven Wrapper（Unix）
mvnw.cmd      # Maven Wrapper（Windows）
.mvn/         # Maven Wrapper配置
pom.xml       # Maven项目配置
```

**说明**：如果使用方式二（在服务器编译），需要上传这些文件。

### 2. 源码（如果需要在服务器编译）

```
src/          # 源代码目录
pom.xml       # Maven项目配置
```

**说明**：如果使用方式二，需要上传完整源码。

---

## 不需要上传的文件

以下文件**不需要**上传到服务器：

```
target/          # 编译产物（如果使用方式二，会在服务器生成）
logs/            # 日志目录（会在服务器自动创建）
*.pid            # 进程ID文件（运行时生成）
*.log            # 日志文件（运行时生成）
.DS_Store        # macOS系统文件
.idea/           # IDE配置
.vscode/         # IDE配置
.git/            # Git仓库（如果不需要）
.gitignore       # Git配置
```

---

## 部署步骤

### 1. 准备文件

在本地执行编译（如果使用方式一）：
```bash
cd csadmin_server/collect_server
mvn clean package
```

确保生成 `target/wechat-collector-jar-with-dependencies.jar`

### 2. 创建部署包

创建部署目录并复制必要文件：
```bash
# 创建部署目录
mkdir -p deploy/collect_server

# 复制JAR包
cp target/wechat-collector-jar-with-dependencies.jar deploy/collect_server/

# 复制配置文件
cp -r config deploy/collect_server/

# 复制启动脚本
cp start.sh stop.sh deploy/collect_server/
chmod +x deploy/collect_server/*.sh

# 复制Java SDK
cp -r java_sdk deploy/collect_server/

# 复制私钥（如果使用）
cp -r keys deploy/collect_server/
chmod 600 deploy/collect_server/keys/*.pem

# 复制SQL脚本
cp -r sql deploy/collect_server/
```

### 3. 修改配置

编辑 `deploy/collect_server/config/app.yml`，根据服务器环境修改：
- 数据库连接信息
- 企业微信配置
- OSS配置（如使用）
- Gemini API Key
- 飞书配置

### 4. 上传到服务器

使用 scp 或其他方式上传：
```bash
scp -r deploy/collect_server user@server:/path/to/deploy/
```

### 5. 在服务器上执行

```bash
# SSH登录服务器
ssh user@server

# 进入部署目录
cd /path/to/deploy/collect_server

# 执行数据库迁移（首次部署）
mysql -u username -p database_name < sql/init.sql
mysql -u username -p database_name < sql/add_sentiment_analysis_fields.sql
mysql -u username -p database_name < sql/add_intent_analysis_tables.sql

# 启动服务
./start.sh

# 查看日志
tail -f logs/wechat-collector.log
```

---

## 服务器环境要求

### 1. Java环境

- **Java版本**：JDK 17 或更高版本
- **检查命令**：`java -version`

### 2. 系统库

- **Linux**：需要安装必要的系统库（libc等）
- **Native库**：确保 `libWeWorkFinanceSdk_Java.so` 与系统架构匹配（x86_64/arm64）

### 3. 网络

- 能够访问企业微信API
- 能够访问数据库
- 如果使用OSS，需要能访问阿里云OSS
- 如果使用Gemini API，需要能访问Google API

### 4. 文件权限

```bash
# 启动脚本需要执行权限
chmod +x start.sh stop.sh

# 私钥文件需要安全权限
chmod 600 keys/private_key_pkcs1.pem

# JAR包需要读取权限
chmod 644 target/wechat-collector-jar-with-dependencies.jar
```

### 5. 目录结构

部署后的目录结构应该是：
```
collect_server/
├── config/
│   └── app.yml
├── java_sdk/
│   ├── libWeWorkFinanceSdk_Java.so
│   ├── wework-finance-sdk.jar
│   └── com/tencent/wework/Finance.*
├── keys/
│   └── private_key_pkcs1.pem
├── logs/              # 自动创建
├── sql/
│   └── *.sql
├── start.sh
├── stop.sh
└── target/
    └── wechat-collector-jar-with-dependencies.jar
```

---

## 快速部署脚本

可以创建一个部署脚本 `deploy.sh`：

```bash
#!/bin/bash

# 配置
SERVER_USER="your_user"
SERVER_HOST="your_server"
SERVER_PATH="/path/to/deploy"
LOCAL_DIR="csadmin_server/collect_server"

# 编译
echo "Building project..."
cd $LOCAL_DIR
mvn clean package -DskipTests

# 创建临时部署目录
TEMP_DIR=$(mktemp -d)
mkdir -p $TEMP_DIR/collect_server

# 复制文件
cp target/wechat-collector-jar-with-dependencies.jar $TEMP_DIR/collect_server/
cp -r config $TEMP_DIR/collect_server/
cp start.sh stop.sh $TEMP_DIR/collect_server/
cp -r java_sdk $TEMP_DIR/collect_server/
cp -r keys $TEMP_DIR/collect_server/ 2>/dev/null || true
cp -r sql $TEMP_DIR/collect_server/

# 设置权限
chmod +x $TEMP_DIR/collect_server/*.sh
chmod 600 $TEMP_DIR/collect_server/keys/*.pem 2>/dev/null || true

# 上传
echo "Uploading to server..."
scp -r $TEMP_DIR/collect_server $SERVER_USER@$SERVER_HOST:$SERVER_PATH/

# 清理
rm -rf $TEMP_DIR

echo "Deployment completed!"
echo "Please SSH to server and run: cd $SERVER_PATH/collect_server && ./start.sh"
```

---

## 验证部署

部署后验证：

1. **检查进程**：
   ```bash
   ps aux | grep wechat-collector
   ```

2. **检查日志**：
   ```bash
   tail -f logs/wechat-collector.log
   ```

3. **健康检查**：
   ```bash
   curl http://localhost:7070/health
   ```

4. **检查PID文件**：
   ```bash
   cat wechat-collector.pid
   ```

---

## 注意事项

1. **配置文件安全**：`app.yml` 包含敏感信息，上传时注意安全
2. **私钥安全**：私钥文件权限设置为 600，不要提交到版本控制
3. **Native库**：确保 `libWeWorkFinanceSdk_Java.so` 与服务器架构匹配
4. **数据库迁移**：首次部署或更新时需要执行相应的SQL脚本
5. **日志目录**：`logs/` 目录会在首次运行时自动创建
6. **端口占用**：确保配置的端口（默认7070）未被占用
7. **内存配置**：根据服务器配置调整 `start.sh` 中的 `-Xms` 和 `-Xmx` 参数

---

## 故障排查

### JAR文件不存在
```bash
# 在服务器上编译
cd /path/to/collect_server
mvn clean package
```

### Native库加载失败
- 检查 `java_sdk/libWeWorkFinanceSdk_Java.so` 是否存在
- 检查文件权限：`ls -l java_sdk/libWeWorkFinanceSdk_Java.so`
- 检查系统架构是否匹配：`file java_sdk/libWeWorkFinanceSdk_Java.so`

### 配置文件找不到
- 检查 `config/app.yml` 是否存在
- 检查启动脚本中的 `CONFIG_PATH` 配置

### 数据库连接失败
- 检查 `config/app.yml` 中的数据库配置
- 检查数据库服务是否运行
- 检查网络连接和防火墙设置

