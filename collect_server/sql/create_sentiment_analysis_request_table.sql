-- ============================================
-- 情绪分析请求记录表创建脚本
-- 说明：用于记录情绪分析的请求记录，方便查看和标记有问题的聊天记录
-- 日期：2025-01-15
-- ============================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================
-- 创建 sentiment_analysis_request 表
-- ============================================

CREATE TABLE IF NOT EXISTS `sentiment_analysis_request` (
  `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '主键ID',
  `analysis_date` DATE NOT NULL COMMENT '分析日期',
  `conversation_id` VARCHAR(128) NOT NULL COMMENT '会话ID',
  `ai_service` VARCHAR(20) COMMENT '使用的AI服务：gemini/deepseek',
  `request_content` LONGTEXT NOT NULL COMMENT '发送给AI的完整聊天内容',
  `ai_request_json` LONGTEXT COMMENT '发送给AI的完整JSON请求',
  `ai_response_json` LONGTEXT COMMENT 'AI返回的完整JSON响应',
  `sentiment_label` VARCHAR(20) COMMENT '情绪标签：positive/negative/neutral',
  `sentiment_score` DECIMAL(5,2) COMMENT '情绪分数（0-100，越高越负面）',
  `sentiment_confidence` DECIMAL(5,2) COMMENT '置信度（0-100）',
  `negative_content` TEXT COMMENT '容易导致用户情绪降低的内容（由AI分析提取）',
  `message_count` INT DEFAULT 0 COMMENT '本次分析的消息条数',
  `status` VARCHAR(20) DEFAULT 'success' COMMENT '状态：success/failed',
  `error_message` TEXT COMMENT '错误信息（如果失败）',
  `is_marked` TINYINT DEFAULT 0 COMMENT '是否已标记：0-未标记，1-已标记',
  `marked_by` VARCHAR(100) COMMENT '标记人',
  `marked_time` TIMESTAMP NULL COMMENT '标记时间',
  `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  INDEX `idx_analysis_date` (`analysis_date`),
  INDEX `idx_conversation_id` (`conversation_id`),
  INDEX `idx_ai_service` (`ai_service`),
  INDEX `idx_sentiment_label` (`sentiment_label`),
  INDEX `idx_status` (`status`),
  INDEX `idx_is_marked` (`is_marked`),
  INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='情绪分析请求记录表';

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================
-- 验证创建结果
-- ============================================

SHOW CREATE TABLE sentiment_analysis_request;

