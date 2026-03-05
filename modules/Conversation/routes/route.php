<?php

use Illuminate\Support\Facades\Route;
use Modules\Conversation\Http\Controllers\ConversationController;
use Modules\Conversation\Http\Controllers\DashboardController;
use Modules\Conversation\Http\Controllers\WechatMemberController;
use Modules\Conversation\Http\Controllers\ChatRecordController;
use Modules\Conversation\Http\Controllers\SentimentController;
use Modules\Conversation\Http\Controllers\IntentController;
use Modules\Conversation\Http\Controllers\ResponsibleUserStatisticsController;

/**
 * Dashboard 路由
 */
Route::get('dashboard', [DashboardController::class, 'index']);

/**
 * 会话模块路由配置
 * 路由前缀：/conversation
 */
Route::prefix('conversation')->group(function(){

    // 获取会话列表
    Route::get('/', [ConversationController::class, 'index']);

    // 搜索会话
    Route::get('/search', [ConversationController::class, 'search']);

    // 获取统计信息
    Route::get('/statistics', [ConversationController::class, 'statistics']);

    // 责任人统计（必须在动态路由之前）
    Route::get('/responsible-statistics', [ResponsibleUserStatisticsController::class, 'index']);

    // 获取会话详情
    Route::get('/{conversationId}', [ConversationController::class, 'show']);

    // 根据会话ID获取聊天记录
    Route::get('/{conversationId}/messages', [ConversationController::class, 'messages']);

    // 获取会话成员列表（包含昵称）
    Route::get('/{conversationId}/members', [ConversationController::class, 'members']);
    
    // 设置会话负责人
    Route::put('/{conversationId}/responsible-user', [ConversationController::class, 'setResponsibleUser']);
    
    // 更新会话备注名称
    Route::put('/{conversationId}/remark', [ConversationController::class, 'updateRemarkName']);
    
    // 删除会话（软删除）
    Route::delete('/{conversationId}', [ConversationController::class, 'delete']);
    
    // 重新下载消息的资源文件
    Route::post('/messages/{messageId}/redownload-media', [ConversationController::class, 'redownloadMedia']);

});

/**
 * 微信成员管理路由配置
 * 路由前缀：/wechat-member
 */
Route::prefix('wechat-member')->group(function(){

    // 获取成员列表
    Route::get('/', [WechatMemberController::class, 'index']);

    // 获取成员统计信息
    Route::get('/statistics', [WechatMemberController::class, 'statistics']);

    // 从企业微信同步成员信息
    Route::post('/sync', [WechatMemberController::class, 'syncFromWeChat']);

    // 批量更新成员类型
    Route::put('/batch-update-type', [WechatMemberController::class, 'batchUpdateType']);

    // 获取成员详情
    Route::get('/{id}', [WechatMemberController::class, 'show']);

    // 更新成员信息
    Route::put('/{id}', [WechatMemberController::class, 'update']);

});

/**
 * 聊天记录管理路由配置
 * 路由前缀：/chatrecord
 */
Route::prefix('chatrecord')->group(function(){

    // 获取聊天记录详情（按需下载）
    Route::get('/show', [ChatRecordController::class, 'show']);

});

/**
 * 情绪分析路由配置
 * 路由前缀：/conversation/sentiment
 */
Route::prefix('conversation/sentiment')->group(function(){

    // 获取会话的情绪分析历史
    Route::get('/history/{conversationId}', [SentimentController::class, 'getHistory']);

    // 获取会话的情绪统计
    Route::get('/statistics/{conversationId}', [SentimentController::class, 'getStatistics']);

    // 获取会话的情绪波动图数据
    Route::get('/trend/{conversationId}', [SentimentController::class, 'getTrend']);

    // 获取情绪预警列表
    Route::get('/alerts', [SentimentController::class, 'getAlerts']);

    // 标记预警为已处理
    Route::put('/alerts/{id}/handle', [SentimentController::class, 'handleAlert']);

});

/**
 * 购买意向分析路由配置
 * 路由前缀：/conversation/intent
 */
Route::prefix('conversation/intent')->group(function(){

    // 获取会话的购买意向
    Route::get('/{conversationId}', [IntentController::class, 'getIntent']);

    // 获取销售机会列表
    Route::get('/opportunities', [IntentController::class, 'getOpportunities']);

    // 更新销售机会状态
    Route::put('/opportunities/{id}/status', [IntentController::class, 'updateOpportunityStatus']);

    // 获取转化漏斗数据
    Route::get('/funnel', [IntentController::class, 'getFunnel']);

});

