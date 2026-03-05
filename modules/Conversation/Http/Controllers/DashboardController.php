<?php

declare(strict_types=1);

namespace Modules\Conversation\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Illuminate\Support\Facades\DB;
use Modules\Conversation\Models\Conversation;
use Modules\Conversation\Models\WeChatMessage;

/**
 * Dashboard 控制器
 * 提供首页统计数据和最近活跃会话
 */
class DashboardController extends Controller
{
    /**
     * 获取 Dashboard 统计数据
     * 
     * 返回数据包括：
     * - 会话总数
     * - 单聊会话数
     * - 群聊会话数
     * - 消息总数
     * - 今日消息数
     * - 活跃会话数（最近7天）
     * - 最近活跃的会话列表
     * 
     * @return array 返回数据数组
     */
    public function index()
    {
        try {
            // 获取会话统计
            $conversationStats = Conversation::getStatistics();
            
            // 获取消息统计
            $messageStats = WeChatMessage::getMessageStatistics();
            
            // 获取今日消息数
            $todayMessageCount = $this->getTodayMessageCount();
            
            // 获取活跃会话数（最近7天有消息的会话）
            $activeConversationCount = $this->getActiveConversationCount(7);
            
            // 获取最近活跃的会话
            $recentConversations = $this->getRecentActiveConversations(10);
            
            // 获取每日消息统计（最近7天）
            $dailyMessageStats = $this->getDailyMessageStats(7);
            
            // 返回完整的 Dashboard 数据
            return [
                // 基础统计
                'total_conversations' => $conversationStats['total_conversations'] ?? 0,
                'single_conversations' => $conversationStats['single_conversations'] ?? 0,
                'group_conversations' => $conversationStats['group_conversations'] ?? 0,
                'total_messages' => $messageStats['total_messages'] ?? 0,
                'today_messages' => $todayMessageCount,
                'active_conversations' => $activeConversationCount,
                
                // 最近活跃会话
                'recent_conversations' => $recentConversations,
                
                // 每日消息统计
                'daily_message_stats' => $dailyMessageStats,
            ];

        } catch (\Exception $e) {
            \Log::error('获取 Dashboard 数据失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 获取今日消息数
     * 
     * @return int 今日消息数量
     */
    private function getTodayMessageCount(): int
    {
        try {
            $todayStart = strtotime('today') * 1000; // 转换为毫秒时间戳
            
            $count = DB::table('wechat_message')
                ->where('msgtime', '>=', $todayStart)
                ->count();
            
            return (int) $count;
        } catch (\Exception $e) {
            \Log::error('获取今日消息数失败', [
                'error' => $e->getMessage(),
            ]);
            return 0;
        }
    }

    /**
     * 获取活跃会话数
     * 
     * @param int $days 最近几天
     * @return int 活跃会话数量
     */
    private function getActiveConversationCount(int $days = 7): int
    {
        try {
            $startTime = strtotime("-{$days} days") * 1000; // 转换为毫秒时间戳
            
            $count = DB::table('wechat_conversation')
                ->where('last_message_time', '>=', $startTime)
                ->count();
            
            return (int) $count;
        } catch (\Exception $e) {
            \Log::error('获取活跃会话数失败', [
                'error' => $e->getMessage(),
            ]);
            return 0;
        }
    }

    /**
     * 获取最近活跃的会话列表
     * 
     * @param int $limit 获取数量
     * @return array 会话列表
     */
    private function getRecentActiveConversations(int $limit = 10): array
    {
        try {
            $conversations = DB::table('wechat_conversation')
                ->select([
                    'conversation_id',
                    'conversation_type',
                    'message_count',
                    'last_message_time',
                    'last_message_content',
                    'last_message_sender',
                    'participant_count'
                ])
                ->orderBy('last_message_time', 'desc')
                ->limit($limit)
                ->get()
                ->toArray();
            
            return array_map(function($item) {
                return (array) $item;
            }, $conversations);
            
        } catch (\Exception $e) {
            \Log::error('获取最近活跃会话失败', [
                'error' => $e->getMessage(),
            ]);
            return [];
        }
    }

    /**
     * 获取每日消息统计
     * 
     * @param int $days 最近几天
     * @return array 每日统计数据
     */
    private function getDailyMessageStats(int $days = 7): array
    {
        try {
            $stats = [];
            $endDate = strtotime('today');
            
            for ($i = $days - 1; $i >= 0; $i--) {
                $dayStart = strtotime("-{$i} days", $endDate) * 1000;
                $dayEnd = strtotime("-" . ($i - 1) . " days", $endDate) * 1000;
                
                $count = DB::table('wechat_message')
                    ->where('msgtime', '>=', $dayStart)
                    ->where('msgtime', '<', $dayEnd)
                    ->count();
                
                $stats[] = [
                    'date' => date('Y-m-d', $dayStart / 1000),
                    'count' => (int) $count,
                ];
            }
            
            return $stats;
            
        } catch (\Exception $e) {
            \Log::error('获取每日消息统计失败', [
                'error' => $e->getMessage(),
            ]);
            return [];
        }
    }
}

