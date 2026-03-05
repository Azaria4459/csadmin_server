-- ============================================
-- 情绪分析相关数据表创建脚本
-- 说明：为情绪分析功能创建必要的数据表
-- 日期：2025-12-10
-- ============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 1. 扩展 wechat_message 表（添加情绪分析字段）
-- ============================================

-- 检查字段是否已存在，如果不存在则添加
SET @dbname = DATABASE();
SET @tablename = 'wechat_message';
SET @columnname = 'sentiment_score';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD COLUMN sentiment_score DECIMAL(5,2) DEFAULT NULL COMMENT ''情绪分数（0-100，越高越负面）'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- sentiment_label
SET @columnname = 'sentiment_label';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD COLUMN sentiment_label VARCHAR(20) DEFAULT NULL COMMENT ''情绪标签：positive/negative/neutral'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- sentiment_confidence
SET @columnname = 'sentiment_confidence';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD COLUMN sentiment_confidence DECIMAL(5,2) DEFAULT NULL COMMENT ''情绪分析置信度（0-100）'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- sentiment_analyzed_at
SET @columnname = 'sentiment_analyzed_at';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD COLUMN sentiment_analyzed_at TIMESTAMP NULL COMMENT ''情绪分析时间'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- sensitive_keywords
SET @columnname = 'sensitive_keywords';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD COLUMN sensitive_keywords VARCHAR(500) DEFAULT NULL COMMENT ''匹配到的敏感词（逗号分隔）'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- 添加索引
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND INDEX_NAME = 'idx_sentiment_score'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD INDEX idx_sentiment_score (sentiment_score)'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND INDEX_NAME = 'idx_sentiment_label'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD INDEX idx_sentiment_label (sentiment_label)'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND INDEX_NAME = 'idx_sentiment_analyzed'
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_message ADD INDEX idx_sentiment_analyzed (sentiment_analyzed_at)'
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- ============================================
-- 2. 扩展 wechat_conversation 表（添加情绪统计字段）
-- ============================================

SET @tablename = 'wechat_conversation';

-- last_sentiment_score
SET @columnname = 'last_sentiment_score';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_conversation ADD COLUMN last_sentiment_score DECIMAL(5,2) DEFAULT NULL COMMENT ''最近一次情绪分数'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- last_sentiment_label
SET @columnname = 'last_sentiment_label';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_conversation ADD COLUMN last_sentiment_label VARCHAR(20) DEFAULT NULL COMMENT ''最近一次情绪标签'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- negative_sentiment_count
SET @columnname = 'negative_sentiment_count';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_conversation ADD COLUMN negative_sentiment_count INT DEFAULT 0 COMMENT ''负面情绪检测次数'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- last_negative_sentiment_time
SET @columnname = 'last_negative_sentiment_time';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_conversation ADD COLUMN last_negative_sentiment_time BIGINT DEFAULT NULL COMMENT ''最后一次负面情绪时间戳'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- sentiment_trend
SET @columnname = 'sentiment_trend';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_conversation ADD COLUMN sentiment_trend VARCHAR(20) DEFAULT NULL COMMENT ''情绪趋势：improving/stable/worsening'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- avg_sentiment_score
SET @columnname = 'avg_sentiment_score';
SET @preparedStatement = (SELECT IF(
  (
    SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS
    WHERE
      TABLE_SCHEMA = @dbname
      AND TABLE_NAME = @tablename
      AND COLUMN_NAME = @columnname
  ) > 0,
  'SELECT 1',
  'ALTER TABLE wechat_conversation ADD COLUMN avg_sentiment_score DECIMAL(5,2) DEFAULT NULL COMMENT ''平均情绪分数（最近30天）'''
));
PREPARE alterIfNotExists FROM @preparedStatement;
EXECUTE alterIfNotExists;
DEALLOCATE PREPARE alterIfNotExists;

-- ============================================
-- 3. 创建 emotion_alert 表（情绪预警记录表）
-- ============================================

CREATE TABLE IF NOT EXISTS `emotion_alert` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY,
  `conversation_id` VARCHAR(128) NOT NULL COMMENT '会话ID',
  `message_id` BIGINT COMMENT '触发预警的消息ID',
  `alert_type` VARCHAR(20) NOT NULL COMMENT '预警类型：negative_sentiment/sensitive_keyword',
  `alert_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '预警时间',
  `sentiment_score` DECIMAL(5,2) COMMENT '情绪分数',
  `sentiment_label` VARCHAR(20) COMMENT '情绪标签',
  `sensitive_keywords` VARCHAR(500) COMMENT '匹配到的敏感词',
  `message_summary` TEXT COMMENT '消息摘要',
  `alert_sent` TINYINT DEFAULT 0 COMMENT '是否已发送提醒：0-未发送，1-已发送',
  `alert_sent_time` TIMESTAMP NULL COMMENT '提醒发送时间',
  `handled` TINYINT DEFAULT 0 COMMENT '是否已处理：0-未处理，1-已处理',
  `handled_by` VARCHAR(100) COMMENT '处理人',
  `handled_time` TIMESTAMP NULL COMMENT '处理时间',
  INDEX `idx_conversation_time` (`conversation_id`, `alert_time`),
  INDEX `idx_alert_type_status` (`alert_type`, `alert_sent`, `handled`),
  INDEX `idx_alert_time` (`alert_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='情绪预警记录表';

-- ============================================
-- 4. 创建 sensitive_keywords 表（敏感词配置表）
-- ============================================

CREATE TABLE IF NOT EXISTS `sensitive_keywords` (
  `id` INT AUTO_INCREMENT PRIMARY KEY,
  `keyword` VARCHAR(50) NOT NULL COMMENT '敏感词',
  `category` VARCHAR(20) NOT NULL COMMENT '类别：complaint/refund/negative/urgent等',
  `severity` TINYINT DEFAULT 1 COMMENT '严重程度：1-低，2-中，3-高',
  `enabled` TINYINT DEFAULT 1 COMMENT '是否启用：0-禁用，1-启用',
  `match_mode` VARCHAR(20) DEFAULT 'contains' COMMENT '匹配模式：contains/exact/regex',
  `description` VARCHAR(255) COMMENT '描述',
  `creator_id` INT COMMENT '创建人ID',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY `uk_keyword` (`keyword`),
  INDEX `idx_category_enabled` (`category`, `enabled`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='敏感词配置表';

-- ============================================
-- 5. 插入初始敏感词数据
-- ============================================

INSERT IGNORE INTO `sensitive_keywords` (`keyword`, `category`, `severity`, `enabled`, `match_mode`, `description`) VALUES
('投诉', 'complaint', 3, 1, 'contains', '客户投诉'),
('退款', 'refund', 2, 1, 'contains', '退款请求'),
('退货', 'refund', 2, 1, 'contains', '退货请求'),
('不满意', 'negative', 2, 1, 'contains', '不满意表达'),
('太差', 'negative', 2, 1, 'contains', '负面评价'),
('垃圾', 'negative', 3, 1, 'contains', '恶劣评价'),
('骗子', 'negative', 3, 1, 'contains', '恶劣评价'),
('诈骗', 'negative', 3, 1, 'contains', '诈骗指控'),
('曝光', 'urgent', 3, 1, 'contains', '威胁曝光'),
('315', 'urgent', 3, 1, 'contains', '投诉威胁'),
('工商局', 'urgent', 3, 1, 'contains', '投诉威胁'),
('法院', 'urgent', 3, 1, 'contains', '法律威胁'),
('起诉', 'urgent', 3, 1, 'contains', '法律威胁'),
('律师', 'urgent', 2, 1, 'contains', '法律相关');

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 验证创建结果
-- ============================================

-- 查看 emotion_alert 表结构
SHOW CREATE TABLE emotion_alert;

-- 查看 sensitive_keywords 表结构
SHOW CREATE TABLE sensitive_keywords;

-- 查看敏感词数据
SELECT * FROM sensitive_keywords WHERE enabled = 1 ORDER BY severity DESC, category;

-- 查看 wechat_message 表新增字段
SHOW COLUMNS FROM wechat_message LIKE 'sentiment%';

-- 查看 wechat_conversation 表新增字段
SHOW COLUMNS FROM wechat_conversation LIKE '%sentiment%';

