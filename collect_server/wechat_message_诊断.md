# wechat_message 表写入任务诊断文档

## 写入任务说明

### 负责写入的服务
**`MessageCollectorService`** 是负责写入 `wechat_message` 表的服务。

### 调用的企业微信接口

系统使用的是**企业微信会话内容存档 SDK**（Native SDK），不是直接的 HTTP API。

**具体接口**：
- **SDK 方法**：`Finance.GetChatData()`
- **接口类型**：企业微信会话内容存档 Native SDK
- **官方文档**：企业微信管理后台 → 管理工具 → 聊天内容存档

**接口说明**：
- 这是企业微信提供的 C++ Native SDK，通过 JNI 调用
- SDK 文件：`java_sdk/libWeWorkFinanceSdk_Java.so`
- Java 封装类：`com.tencent.wework.Finance`
- 需要配置：企业ID（corpid）、聊天内容存档Secret、RSA私钥

**接口参数**：
```java
Finance.GetChatData(sdk, seq, limit, proxy, passwd, timeout, slice)
```
- `sdk`: SDK实例（通过 `Finance.NewSdk()` 创建）
- `seq`: 从指定的seq开始拉取消息（返回的消息从seq+1开始）
- `limit`: 一次拉取的消息条数（最大值1000条）
- `proxy`: 代理地址（空字符串表示不使用代理）
- `passwd`: 代理密码（空字符串表示不使用代理）
- `timeout`: 超时时间（秒）
- `slice`: 返回数据的容器（通过 `Finance.NewSlice()` 创建）

### 执行流程
1. **定时任务**：每30秒执行一次（在 `MessageCollectorService.start()` 中配置）
2. **拉取消息**：通过 `WeChatSdkService.fetchChatData()` 调用企业微信 SDK 的 `GetChatData()` 方法拉取消息
3. **消息解密**：使用 RSA 私钥解密加密的消息内容
4. **保存数据**：通过 `MessageRepository.batchInsert()` 批量保存到 `wechat_message` 表
5. **增量拉取**：基于 `seq` 顺序号，只拉取新消息（从数据库最大seq开始）

### 相关代码位置
- 服务类：`src/main/java/com/wechat/collector/service/MessageCollectorService.java`
- 数据访问：`src/main/java/com/wechat/collector/repository/MessageRepository.java`
- SDK服务：`src/main/java/com/wechat/collector/service/WeChatSdkService.java`
- 启动入口：`src/main/java/com/wechat/collector/Application.java` (第252-259行)

## 排查今日无数据更新的步骤

### 1. 检查服务是否正在运行

```bash
# 检查进程
ps aux | grep wechat-collector

# 检查PID文件
cat wechat-collector.pid

# 检查健康状态
curl http://localhost:7070/health
```

健康检查接口返回示例：
```json
{
  "status": "UP",
  "collector_running": true,
  "timestamp": 1234567890
}
```

如果 `collector_running` 为 `false`，说明消息收集服务未运行。

### 2. 检查日志文件

```bash
# 查看主日志
tail -f logs/wechat-collector.log

# 查看控制台日志
tail -f logs/console.log

# 查看错误日志
tail -f logs/error.log
```

**关键日志信息：**
- `Starting message collector service...` - 服务启动成功
- `Successfully saved X new messages` - 成功保存消息
- `No new messages to collect` - 没有新消息（正常情况）
- `Error collecting messages` - 收集消息时出错
- `Failed to fetch chat data` - 拉取消息失败
- `Failed to initialize WeChat SDK` - SDK初始化失败

### 3. 检查统计信息

```bash
# 查看统计信息
curl http://localhost:7070/stats
```

返回示例：
```json
{
  "total_messages": 12345,
  "max_seq": 67890,
  "collector_running": true,
  "total_conversations": 100
}
```

**检查点：**
- `max_seq` 是否在增长（如果长时间不变，说明没有新消息）
- `collector_running` 是否为 `true`

### 4. 手动触发收集

```bash
# 手动触发一次消息收集
curl -X POST http://localhost:7070/collect/trigger
```

然后查看日志，观察是否有错误信息。

### 5. 检查可能的问题原因

#### 问题1：服务未启动
**症状**：`collector_running` 为 `false`
**解决**：重启服务
```bash
./stop.sh
./start.sh
```

#### 问题2：SDK初始化失败
**症状**：日志中出现 `Failed to initialize WeChat SDK`
**可能原因**：
- native库文件不存在：`java_sdk/libWeWorkFinanceSdk_Java.so`
- 配置文件错误：`config/app.yml` 中的企业微信配置不正确
- RSA私钥文件不存在或格式错误

**检查**：
```bash
# 检查native库
ls -l java_sdk/libWeWorkFinanceSdk_Java.so

# 检查配置文件
cat config/app.yml | grep -A 10 wechat
```

#### 问题3：企业微信API返回错误
**症状**：日志中出现 `Failed to fetch chat data, error code: X`
**可能原因**：
- 企业微信配置错误（corpid、secret）
- 公钥版本不匹配
- API调用频率限制
- 网络连接问题

**检查**：查看日志中的具体错误码和错误信息

#### 问题4：没有新消息（正常情况）
**症状**：日志中只有 `No new messages to collect`
**说明**：这是正常情况，表示企业微信服务器上没有新消息

**验证**：
```bash
# 查看数据库中最新的消息时间
mysql -u用户名 -p数据库名 -e "SELECT MAX(msgtime) FROM wechat_message;"

# 将时间戳转换为可读时间
# msgtime 是毫秒时间戳，可以用以下命令转换：
# date -d @$(echo "时间戳/1000" | bc)
```

#### 问题5：数据库连接问题
**症状**：日志中出现数据库连接错误
**检查**：
```bash
# 检查数据库配置
cat config/app.yml | grep -A 10 database

# 测试数据库连接
mysql -h主机 -u用户 -p密码 数据库名 -e "SELECT COUNT(*) FROM wechat_message;"
```

### 6. 检查配置文件

```bash
# 查看企业微信配置
cat config/app.yml
```

**关键配置项：**
- `wechat.corpid` - 企业ID
- `wechat.secret` - 应用密钥
- `wechat.privateKeyPath` - RSA私钥路径
- `wechat.publickeyVer` - 公钥版本
- `wechat.pullLimit` - 每次拉取的消息数量限制

### 7. 查看最近的数据库记录

```sql
-- 查看最近插入的消息
SELECT id, msgid, seq, msgtime, from_user, msgtype, create_time 
FROM wechat_message 
ORDER BY create_time DESC 
LIMIT 10;

-- 查看今天的消息数量
SELECT COUNT(*) as today_count
FROM wechat_message 
WHERE DATE(create_time) = CURDATE();

-- 查看最大seq值
SELECT MAX(seq) as max_seq FROM wechat_message;
```

## 常见问题解决方案

### 问题：服务启动但无数据更新

1. **检查服务状态**
   ```bash
   curl http://localhost:7070/health
   ```

2. **查看最近日志**
   ```bash
   tail -100 logs/wechat-collector.log | grep -i "collect\|error\|failed"
   ```

3. **手动触发收集并观察日志**
   ```bash
   curl -X POST http://localhost:7070/collect/trigger
   tail -f logs/wechat-collector.log
   ```

4. **检查企业微信是否有新消息**
   - 登录企业微信管理后台
   - 查看是否有新的聊天记录
   - 如果确实没有新消息，这是正常情况

### 问题：SDK初始化失败

1. **检查native库**
   ```bash
   ls -l java_sdk/libWeWorkFinanceSdk_Java.so
   file java_sdk/libWeWorkFinanceSdk_Java.so
   ```

2. **检查私钥文件**
   ```bash
   # 查看配置中的私钥路径
   grep privateKeyPath config/app.yml
   
   # 检查私钥文件是否存在
   ls -l $(grep privateKeyPath config/app.yml | awk '{print $2}')
   ```

3. **检查配置文件格式**
   ```bash
   # 验证YAML格式
   python3 -c "import yaml; yaml.safe_load(open('config/app.yml'))"
   ```

### 问题：API返回错误

查看日志中的具体错误码：
- `errcode: 40001` - 不合法的secret参数
- `errcode: 40013` - 不合法的corpid
- `errcode: 40014` - 不合法的access_token
- `errcode: 45009` - 接口调用超过限制

根据错误码调整配置或联系企业微信技术支持。

## 监控建议

建议定期检查以下指标：

1. **服务运行状态**：每小时检查一次 `/health` 接口
2. **消息数量**：每天检查一次 `/stats` 接口，观察 `max_seq` 是否增长
3. **日志监控**：设置日志告警，监控错误日志
4. **数据库监控**：监控 `wechat_message` 表的插入频率

## 联系支持

如果以上步骤都无法解决问题，请提供以下信息：
1. 服务健康检查结果：`curl http://localhost:7070/health`
2. 统计信息：`curl http://localhost:7070/stats`
3. 最近100行日志：`tail -100 logs/wechat-collector.log`
4. 配置文件（脱敏后）：`config/app.yml`（隐藏敏感信息）

