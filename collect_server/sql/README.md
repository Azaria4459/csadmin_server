# 数据库脚本说明

## 情绪分析表创建脚本

### create_sentiment_analysis_tables.sql

创建情绪分析功能所需的数据表和字段。

**功能：**
1. 扩展 `wechat_message` 表，添加情绪分析字段
2. 扩展 `wechat_conversation` 表，添加情绪统计字段
3. 创建 `emotion_alert` 表（情绪预警记录）
4. 创建 `sensitive_keywords` 表（敏感词配置）
5. 插入初始敏感词数据

**执行方式：**

```bash
cd /Users/chany/Projects/csadmin/csadmin_server/collect_server
mysql -u username -p database_name < sql/create_sentiment_analysis_tables.sql
```

**特点：**
- 使用 `IF NOT EXISTS` 和动态SQL检查，避免重复创建
- 可以安全地多次执行
- 包含初始敏感词数据

**表结构：**

### emotion_alert（情绪预警记录表）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT | 主键 |
| conversation_id | VARCHAR(128) | 会话ID |
| message_id | BIGINT | 触发预警的消息ID |
| alert_type | VARCHAR(20) | 预警类型 |
| alert_time | TIMESTAMP | 预警时间 |
| sentiment_score | DECIMAL(5,2) | 情绪分数 |
| sentiment_label | VARCHAR(20) | 情绪标签 |
| sensitive_keywords | VARCHAR(500) | 匹配到的敏感词 |
| message_summary | TEXT | 消息摘要 |
| alert_sent | TINYINT | 是否已发送提醒 |
| handled | TINYINT | 是否已处理 |
| handled_by | VARCHAR(100) | 处理人 |
| handled_time | TIMESTAMP | 处理时间 |

### sensitive_keywords（敏感词配置表）
| 字段 | 类型 | 说明 |
|------|------|------|
| id | INT | 主键 |
| keyword | VARCHAR(50) | 敏感词 |
| category | VARCHAR(20) | 类别 |
| severity | TINYINT | 严重程度（1-3） |
| enabled | TINYINT | 是否启用 |
| match_mode | VARCHAR(20) | 匹配模式 |
| description | VARCHAR(255) | 描述 |

**初始敏感词：**
- 投诉类：投诉
- 退款类：退款、退货
- 负面类：不满意、太差、垃圾、骗子、诈骗
- 紧急类：曝光、315、工商局、法院、起诉、律师

## 注意事项

1. 执行前请备份数据库
2. 确保数据库用户有足够的权限
3. 首次执行后，可以通过后台管理界面管理敏感词
4. 表结构变更使用动态SQL，可以安全地重复执行

