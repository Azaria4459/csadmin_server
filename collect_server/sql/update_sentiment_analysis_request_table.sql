-- 修改 sentiment_analysis_request 表结构
-- 1. 将 gemini_request_json 改为 ai_request_json
-- 2. 将 gemini_response_json 改为 ai_response_json
-- 3. 添加 ai_service 字段记录使用的 AI 服务（gemini/deepseek）

-- 修改字段名
ALTER TABLE `sentiment_analysis_request` 
  CHANGE COLUMN `gemini_request_json` `ai_request_json` LONGTEXT COMMENT '发送给AI的完整JSON请求',
  CHANGE COLUMN `gemini_response_json` `ai_response_json` LONGTEXT COMMENT 'AI返回的完整JSON响应';

-- 添加 ai_service 字段
ALTER TABLE `sentiment_analysis_request` 
  ADD COLUMN `ai_service` varchar(20) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '使用的AI服务：gemini/deepseek' AFTER `conversation_id`;

-- 添加索引
ALTER TABLE `sentiment_analysis_request` 
  ADD KEY `idx_ai_service` (`ai_service`);
