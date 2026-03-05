<?php

declare(strict_types=1);

namespace Modules\Conversation\Services;

use Modules\Conversation\Models\Conversation;
use Modules\Conversation\Models\ResponsibleUserStatistics;
use Modules\Conversation\Models\WeChatMessage;

/**
 * 责任人统计服务
 * 负责计算和更新责任人的响应时间统计数据
 */
class ResponsibleUserStatisticsService
{
    /**
     * 更新所有会话的统计数据（每个会话一条记录）
     * 
     * @return int 更新的记录数
     */
    public function updateAllStatistics(): int
    {
        // 获取所有有责任人的会话
        $conversations = Conversation::query()
            ->withoutGlobalScopes()
            ->where('is_delete', 0)
            ->whereNotNull('responsible_user_id')
            ->get();

        $updatedCount = 0;

        foreach ($conversations as $conversation) {
            try {
                if ($this->updateStatisticsForConversation($conversation)) {
                    $updatedCount++;
                }
            } catch (\Exception $e) {
                \Log::error('更新责任人统计数据失败', [
                    'conversation_id' => $conversation->conversation_id,
                    'responsible_user_id' => $conversation->responsible_user_id,
                    'error' => $e->getMessage(),
                ]);
            }
        }

        return $updatedCount;
    }

    /**
     * 更新指定会话的统计数据（统计所有消息）
     * 如果有消息记录则更新，如果没有消息记录则不更新（不创建或更新记录）
     * 
     * @param Conversation $conversation 会话对象
     * @return bool 是否成功更新（有消息记录返回true，没有消息记录返回false）
     */
    public function updateStatisticsForConversation(Conversation $conversation): bool
    {
        $responsibleUserId = $conversation->responsible_user_id;

        // 获取该会话的所有消息（按时间正序）
        $messages = WeChatMessage::query()
            ->withoutGlobalScopes()
            ->where('conversation_id', $conversation->conversation_id)
            ->where('action', 'send') // 只统计发送的消息，不包括撤回
            ->orderBy('msgtime', 'asc')
            ->get();

        if ($messages->isEmpty()) {
            // 如果没有消息，不更新（不创建或更新记录）
            return false;
        }

        // 获取责任人对应的 account_name（用于判断消息是否来自责任人）
        $responsibleMember = null;
        if ($responsibleUserId) {
            $responsibleMember = \DB::table('wechat_member')
                ->where('id', $responsibleUserId)
                ->first();
        }

        // 判断消息发送者是否为员工（type = 1）
        $memberTypes = \DB::table('wechat_member')
            ->whereIn('account_name', $messages->pluck('from_user')->unique()->toArray())
            ->pluck('type', 'account_name')
            ->toArray();

        // 计算统计数据
        $firstResponseTime = null;
        $responseTimes = [];
        $over10minCount = 0;
        $over20minCount = 0;
        $over30minCount = 0;
        $totalUserMessages = 0;
        $pendingUserMessageTime = null; // 待响应的用户消息时间

        foreach ($messages as $message) {
            $fromUser = $message->from_user;
            $msgTime = $message->msgtime;
            // 判断是否来自责任人：比较 account_name
            $isResponsible = ($responsibleMember && $fromUser === $responsibleMember->account_name);
            $isEmployee = isset($memberTypes[$fromUser]) && $memberTypes[$fromUser] == 1;
            $isUserMessage = !$isEmployee; // 用户消息：不是员工发送的消息

            if ($isUserMessage) {
                // 用户消息
                $totalUserMessages++;
                // 如果用户连续发送消息（补充说明），重置待响应时间，不计入超时
                // 因为用户可能在补充说明，等待用户说完后再响应更合理
                $pendingUserMessageTime = $msgTime; // 记录待响应的用户消息时间（如果之前有待响应的消息，会被新消息覆盖）
            } elseif ($isResponsible && $pendingUserMessageTime !== null) {
                // 这是责任人的响应消息，且前面有待响应的用户消息
                $responseTime = ($msgTime - $pendingUserMessageTime) / 1000; // 转换为秒

                // 记录首次响应时间
                if ($firstResponseTime === null) {
                    $firstResponseTime = $responseTime;
                }

                // 记录响应时间（用于计算平均值）
                $responseTimes[] = $responseTime;

                // 统计超时次数
                if ($responseTime > 30 * 60) { // 30分钟 = 1800秒
                    $over30minCount++;
                }
                if ($responseTime > 20 * 60) { // 20分钟 = 1200秒
                    $over20minCount++;
                }
                if ($responseTime > 10 * 60) { // 10分钟 = 600秒
                    $over10minCount++;
                }

                $pendingUserMessageTime = null; // 重置，响应完成
            }
        }

        // 计算平均响应时间
        $avgResponseTime = null;
        if (!empty($responseTimes)) {
            $avgResponseTime = array_sum($responseTimes) / count($responseTimes);
        }

        // 获取最后一条消息时间
        $lastMessage = $messages->last();
        $lastMessageTime = $lastMessage ? $lastMessage->msgtime : null;

        // 保存统计数据
        $this->saveStatistics($conversation, [
            'first_response_time' => $firstResponseTime !== null ? (int)round($firstResponseTime) : null,
            'avg_response_time' => $avgResponseTime !== null ? round($avgResponseTime, 2) : null,
            'response_count' => count($responseTimes),
            'over_10min_count' => $over10minCount,
            'over_20min_count' => $over20minCount,
            'over_30min_count' => $over30minCount,
            'total_user_messages' => $totalUserMessages,
            'last_message_time' => $lastMessageTime,
        ]);
        
        return true;
    }

    /**
     * 保存统计数据
     * 
     * @param Conversation $conversation 会话对象
     * @param array $statistics 统计数据
     */
    private function saveStatistics(Conversation $conversation, array $statistics): void
    {
        ResponsibleUserStatistics::updateOrCreate(
            [
                'responsible_user_id' => $conversation->responsible_user_id,
                'conversation_id' => $conversation->conversation_id,
            ],
            array_merge($statistics, [
                'conversation_name' => $conversation->name,
                'conversation_remark_name' => $conversation->remark_name,
                'conversation_type' => $conversation->conversation_type,
            ])
        );
    }

    /**
     * 获取统计数据列表
     * 在返回列表之前，会自动更新有聊天记录的会话的统计数据
     * 
     * @param int $page 页码
     * @param int $pageSize 每页数量
     * @param string|null $responsibleUserId 责任人ID过滤
     * @param string|null $orderBy 排序字段（可选）
     * @param string $order 排序方向，asc 或 desc，默认 asc
     * @return array 返回包含列表和分页信息的数组
     */
    public function getStatisticsList(
        int $page = 1,
        int $pageSize = 20,
        ?string $responsibleUserId = null,
        ?string $orderBy = null,
        string $order = 'asc'
    ): array {
        // 先获取所有有责任人的会话（用于更新统计数据）
        $conversationsQuery = Conversation::query()
            ->withoutGlobalScopes()
            ->where('is_delete', 0)
            ->whereNotNull('responsible_user_id');
        
        if ($responsibleUserId) {
            $conversationsQuery->where('responsible_user_id', $responsibleUserId);
        }
        
        $conversations = $conversationsQuery->get();
        
        // 对每个会话，如果有聊天记录则更新统计数据
        foreach ($conversations as $conversation) {
            try {
                // updateStatisticsForConversation 内部会检查是否有消息，如果有消息则更新，没有消息则返回 false
                $this->updateStatisticsForConversation($conversation);
            } catch (\Exception $e) {
                \Log::error('自动更新责任人统计数据失败', [
                    'conversation_id' => $conversation->conversation_id,
                    'responsible_user_id' => $conversation->responsible_user_id,
                    'error' => $e->getMessage(),
                ]);
            }
        }
        
        // 查询统计数据列表
        $query = ResponsibleUserStatistics::query()
            ->withoutGlobalScopes()
            ->select([
                'responsible_user_statistics.*',
                'wechat_member.nick_name as responsible_user_nick_name',
                'wechat_member.remark_name as responsible_user_remark_name',
            ])
            ->leftJoin('wechat_member', function($join) {
                $join->on('responsible_user_statistics.responsible_user_id', '=', 'wechat_member.id');
            });

        // 责任人过滤
        if ($responsibleUserId) {
            $query->where('responsible_user_statistics.responsible_user_id', $responsibleUserId);
        }

        // 排序处理
        // 允许排序的字段白名单
        $allowedOrderByFields = [
            'first_response_time',
            'avg_response_time',
            'response_count',
            'total_user_messages',
            'over_10min_count',
            'over_20min_count',
            'over_30min_count',
            'update_time',
            'conversation_id',
        ];
        
        if ($orderBy && in_array($orderBy, $allowedOrderByFields)) {
            // 使用指定的排序字段和方向
            $query->orderBy('responsible_user_statistics.' . $orderBy, $order);
            // 如果有排序，使用会话ID作为次要排序（确保结果稳定）
            if ($orderBy !== 'conversation_id') {
                $query->orderBy('responsible_user_statistics.conversation_id', 'asc');
            }
        } else {
            // 默认排序：按更新时间降序和会话ID升序
            $query->orderBy('responsible_user_statistics.update_time', 'desc')
                  ->orderBy('responsible_user_statistics.conversation_id', 'asc');
        }

        // 分页
        $total = $query->count();
        $list = $query->skip(($page - 1) * $pageSize)
                      ->take($pageSize)
                      ->get()
                      ->toArray();

        return [
            'list' => $list,
            'total' => $total,
            'page' => $page,
            'pageSize' => $pageSize,
        ];
    }
}

