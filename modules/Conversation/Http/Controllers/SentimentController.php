<?php

declare(strict_types=1);

namespace Modules\Conversation\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

/**
 * 情绪分析控制器
 * 提供情绪分析历史查询、预警查询等功能
 */
class SentimentController extends Controller
{
    /**
     * 获取会话的情绪分析历史
     * 
     * GET /api/conversation/sentiment/history/{conversationId}
     * 
     * @param string $conversationId 会话ID
     * @param Request $request
     * @return array
     */
    public function getHistory(string $conversationId, Request $request)
    {
        try {
            $limit = min((int) $request->get('limit', 50), 100);
            
            $history = DB::table('wechat_message')
                ->where('conversation_id', $conversationId)
                ->whereNotNull('sentiment_analyzed_at')
                ->select([
                    'id as message_id',
                    'msgtime',
                    'sentiment_score',
                    'sentiment_label',
                    'sentiment_confidence',
                    'sensitive_keywords',
                    'sentiment_analyzed_at as analyzed_at'
                ])
                ->orderBy('sentiment_analyzed_at', 'desc')
                ->limit($limit)
                ->get();
            
            return [
                'conversation_id' => $conversationId,
                'total' => count($history),
                'data' => $history
            ];
            
        } catch (\Exception $e) {
            Log::error('获取情绪分析历史失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('获取情绪分析历史失败', 10001);
        }
    }
    
    /**
     * 获取情绪预警列表
     * 
     * GET /api/conversation/sentiment/alerts
     * 
     * @param Request $request
     * @return array
     */
    public function getAlerts(Request $request)
    {
        try {
            $page = (int) $request->get('page', 1);
            $pageSize = min((int) $request->get('pageSize', 20), 100);
            $alertType = $request->get('alertType');
            $status = $request->get('status'); // 0-未处理，1-已处理
            $startDate = $request->get('startDate');
            $endDate = $request->get('endDate');
            
            $query = DB::table('emotion_alert')
                ->leftJoin('wechat_conversation', 'emotion_alert.conversation_id', '=', 'wechat_conversation.conversation_id')
                ->select([
                    'emotion_alert.*',
                    'wechat_conversation.name as conversation_name',
                    'wechat_conversation.remark_name as conversation_remark_name'
                ]);
            
            if ($alertType) {
                $query->where('emotion_alert.alert_type', $alertType);
            }
            
            if ($status !== null) {
                $query->where('emotion_alert.handled', $status);
            }
            
            if ($startDate) {
                $query->where('emotion_alert.alert_time', '>=', $startDate);
            }
            
            if ($endDate) {
                $query->where('emotion_alert.alert_time', '<=', $endDate);
            }
            
            $total = $query->count();
            $data = $query->orderBy('emotion_alert.alert_time', 'desc')
                ->skip(($page - 1) * $pageSize)
                ->take($pageSize)
                ->get();
            
            return [
                'total' => $total,
                'page' => $page,
                'pageSize' => $pageSize,
                'data' => $data
            ];
            
        } catch (\Exception $e) {
            Log::error('获取情绪预警列表失败', [
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('获取情绪预警列表失败', 10002);
        }
    }
    
    /**
     * 标记预警为已处理
     * 
     * PUT /api/conversation/sentiment/alerts/{id}/handle
     * 
     * @param int $id 预警ID
     * @param Request $request
     * @return array
     */
    public function handleAlert(int $id, Request $request)
    {
        try {
            $user = $this->getLoginUser();
            $handledBy = $user ? $user->username : 'system';
            
            $affected = DB::table('emotion_alert')
                ->where('id', $id)
                ->update([
                    'handled' => 1,
                    'handled_by' => $handledBy,
                    'handled_time' => now()
                ]);
            
            if ($affected > 0) {
                return ['success' => true, 'message' => '标记成功'];
            } else {
                throw new FailedException('预警记录不存在', 10003);
            }
            
        } catch (\Exception $e) {
            Log::error('标记预警失败', [
                'id' => $id,
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('标记预警失败', 10004);
        }
    }
    
    /**
     * 获取会话的情绪统计
     * 
     * GET /api/conversation/sentiment/statistics/{conversationId}
     * 
     * @param string $conversationId 会话ID
     * @return array
     */
    public function getStatistics(string $conversationId)
    {
        try {
            $conversation = DB::table('wechat_conversation')
                ->where('conversation_id', $conversationId)
                ->first();
            
            if (!$conversation) {
                throw new FailedException('会话不存在', 10005);
            }
            
            // 统计最近30天的情绪数据
            $thirtyDaysAgo = date('Y-m-d', strtotime('-30 days'));
            
            $stats = DB::table('wechat_message')
                ->where('conversation_id', $conversationId)
                ->whereNotNull('sentiment_analyzed_at')
                ->where('sentiment_analyzed_at', '>=', $thirtyDaysAgo)
                ->selectRaw('
                    COUNT(*) as total_messages,
                    AVG(sentiment_score) as avg_score,
                    SUM(CASE WHEN sentiment_label = "negative" THEN 1 ELSE 0 END) as negative_count,
                    SUM(CASE WHEN sentiment_label = "positive" THEN 1 ELSE 0 END) as positive_count,
                    SUM(CASE WHEN sentiment_label = "neutral" THEN 1 ELSE 0 END) as neutral_count
                ')
                ->first();
            
            return [
                'conversation_id' => $conversationId,
                'last_sentiment_score' => $conversation->last_sentiment_score,
                'last_sentiment_label' => $conversation->last_sentiment_label,
                'negative_sentiment_count' => $conversation->negative_sentiment_count,
                'avg_sentiment_score' => $conversation->avg_sentiment_score,
                'sentiment_trend' => $conversation->sentiment_trend,
                'recent_30_days' => $stats
            ];
            
        } catch (\Exception $e) {
            Log::error('获取情绪统计失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('获取情绪统计失败', 10006);
        }
    }

    /**
     * 获取会话的情绪波动图数据和分析列表
     * 
     * GET /api/conversation/sentiment/trend/{conversationId}
     * 
     * 查询参数：
     * - startDate: 开始日期，格式：yyyy-MM-dd（可选，默认最近7天）
     * - endDate: 结束日期，格式：yyyy-MM-dd（可选，默认今天）
     * 
     * @param string $conversationId 会话ID
     * @param Request $request
     * @return array
     */
    public function getTrend(string $conversationId, Request $request)
    {
        try {
            // 解析日期参数
            $endDate = $request->get('endDate', date('Y-m-d'));
            $startDate = $request->get('startDate', date('Y-m-d', strtotime('-7 days')));
            
            // 从 sentiment_analysis_request 表获取情绪波动数据（用于图表）
            // 放宽条件：即使 sentiment_label 为 NULL，也可以根据 sentiment_score 判断
            $trendData = DB::table('sentiment_analysis_request')
                ->where('conversation_id', $conversationId)
                ->where('analysis_date', '>=', $startDate)
                ->where('analysis_date', '<=', $endDate)
                ->where('status', 'success')
                ->where(function($query) {
                    // 有标签或者有分数都可以显示
                    $query->whereNotNull('sentiment_label')
                          ->orWhereNotNull('sentiment_score');
                })
                ->select([
                    'analysis_date as date',
                    'sentiment_label as label',
                    'sentiment_score as score'
                ])
                ->orderBy('analysis_date', 'asc')
                ->get()
                ->map(function ($item) {
                    // 转换为y轴值：1=正面，0=中立，-1=负面
                    $yValue = 0;
                    if ($item->label === 'positive') {
                        $yValue = 1;
                    } elseif ($item->label === 'negative') {
                        $yValue = -1;
                    } elseif ($item->label === 'neutral') {
                        $yValue = 0;
                    } else {
                        // 如果没有标签，根据分数判断
                        if ($item->score !== null) {
                            if ($item->score < 40) {
                                $yValue = 1; // 正面（分数越低越正面）
                            } elseif ($item->score > 60) {
                                $yValue = -1; // 负面（分数越高越负面）
                            } else {
                                $yValue = 0; // 中立
                            }
                        }
                    }
                    
                    return [
                        'date' => $item->date,
                        'label' => $item->label,
                        'score' => $item->score,
                        'yValue' => $yValue
                    ];
                })
                ->values()
                ->all();
            
            // 获取分析列表数据（包含详细信息）
            $listData = DB::table('sentiment_analysis_request')
                ->where('conversation_id', $conversationId)
                ->where('analysis_date', '>=', $startDate)
                ->where('analysis_date', '<=', $endDate)
                ->where('status', 'success')
                ->select([
                    'id',
                    'analysis_date as date',
                    'sentiment_label as label',
                    'sentiment_score as score',
                    'sentiment_confidence as confidence',
                    'message_count',
                    'negative_content',
                    'create_time'
                ])
                ->orderBy('analysis_date', 'desc')
                ->orderBy('create_time', 'desc')
                ->get()
                ->map(function ($item) {
                    // 转换情绪标签为中文
                    $labelText = '';
                    if ($item->label === 'positive') {
                        $labelText = '正面';
                    } elseif ($item->label === 'negative') {
                        $labelText = '负面';
                    } elseif ($item->label === 'neutral') {
                        $labelText = '中立';
                    }
                    
                    return [
                        'id' => $item->id,
                        'date' => $item->date,
                        'label' => $item->label,
                        'labelText' => $labelText,
                        'score' => $item->score,
                        'confidence' => $item->confidence,
                        'messageCount' => $item->message_count,
                        'negativeContent' => $item->negative_content,
                        'createTime' => $item->create_time
                    ];
                })
                ->values()
                ->all();
            
            // 记录查询结果用于调试
            Log::info('情绪趋势查询结果', [
                'conversation_id' => $conversationId,
                'start_date' => $startDate,
                'end_date' => $endDate,
                'trend_data_count' => count($trendData),
                'list_data_count' => count($listData)
            ]);
            
            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'conversationId' => $conversationId,
                'startDate' => $startDate,
                'endDate' => $endDate,
                'chartData' => $trendData,
                'listData' => $listData,
                'count' => count($trendData)
            ];
            
        } catch (\Exception $e) {
            Log::error('获取情绪波动数据失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('获取情绪波动数据失败', 10007);
        }
    }
}

