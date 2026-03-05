<?php

declare(strict_types=1);

namespace Modules\Conversation\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Illuminate\Http\Request;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Log;

/**
 * 购买意向分析控制器
 * 提供购买意向查询、销售机会管理等功能
 */
class IntentController extends Controller
{
    /**
     * 获取会话的购买意向
     * 
     * GET /api/conversation/intent/{conversationId}
     * 
     * @param string $conversationId 会话ID
     * @return array
     */
    public function getIntent(string $conversationId)
    {
        try {
            $conversation = DB::table('wechat_conversation')
                ->where('conversation_id', $conversationId)
                ->first();
            
            if (!$conversation) {
                throw new FailedException('会话不存在', 10001);
            }
            
            return [
                'conversation_id' => $conversationId,
                'intent_score' => $conversation->intent_score,
                'intent_level' => $conversation->intent_level,
                'intent_keywords' => $conversation->intent_keywords ? explode(',', $conversation->intent_keywords) : [],
                'opportunity_status' => $conversation->opportunity_status,
                'intent_analyzed_at' => $conversation->intent_analyzed_at
            ];
            
        } catch (\Exception $e) {
            Log::error('获取购买意向失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('获取购买意向失败', 10002);
        }
    }
    
    /**
     * 获取销售机会列表
     * 
     * GET /api/conversation/intent/opportunities
     * 
     * @param Request $request
     * @return array
     */
    public function getOpportunities(Request $request)
    {
        try {
            $page = (int) $request->get('page', 1);
            $pageSize = min((int) $request->get('pageSize', 20), 100);
            $status = $request->get('status');
            $intentLevel = $request->get('intentLevel');
            $employeeAccount = $request->get('employeeAccount');
            $startDate = $request->get('startDate');
            $endDate = $request->get('endDate');
            
            $query = DB::table('sales_opportunity')
                ->leftJoin('wechat_conversation', 'sales_opportunity.conversation_id', '=', 'wechat_conversation.conversation_id')
                ->select([
                    'sales_opportunity.*',
                    'wechat_conversation.name as conversation_name',
                    'wechat_conversation.remark_name as conversation_remark_name'
                ]);
            
            if ($status) {
                $query->where('sales_opportunity.status', $status);
            }
            
            if ($intentLevel) {
                $query->where('sales_opportunity.intent_level', $intentLevel);
            }
            
            if ($employeeAccount) {
                $query->where('sales_opportunity.employee_account', $employeeAccount);
            }
            
            if ($startDate) {
                $query->where('sales_opportunity.detected_time', '>=', $startDate);
            }
            
            if ($endDate) {
                $query->where('sales_opportunity.detected_time', '<=', $endDate);
            }
            
            $total = $query->count();
            $data = $query->orderBy('sales_opportunity.detected_time', 'desc')
                ->skip(($page - 1) * $pageSize)
                ->take($pageSize)
                ->get();
            
            // 处理关键词数组
            foreach ($data as $item) {
                if ($item->intent_keywords) {
                    $item->intent_keywords = explode(',', $item->intent_keywords);
                } else {
                    $item->intent_keywords = [];
                }
            }
            
            return [
                'total' => $total,
                'page' => $page,
                'pageSize' => $pageSize,
                'data' => $data
            ];
            
        } catch (\Exception $e) {
            Log::error('获取销售机会列表失败', [
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('获取销售机会列表失败', 10003);
        }
    }
    
    /**
     * 更新销售机会状态
     * 
     * PUT /api/conversation/intent/opportunities/{id}/status
     * 
     * @param int $id 机会ID
     * @param Request $request
     * @return array
     */
    public function updateOpportunityStatus(int $id, Request $request)
    {
        try {
            $status = $request->get('status');
            $quoteAmount = $request->get('quoteAmount');
            $closeAmount = $request->get('closeAmount');
            $closeReason = $request->get('closeReason');
            
            if (!in_array($status, ['potential', 'quoted', 'closed', 'lost'])) {
                throw new FailedException('无效的状态', 10004);
            }
            
            $updateData = ['status' => $status];
            
            if ($status === 'quoted' && $quoteAmount) {
                $updateData['quote_amount'] = $quoteAmount;
                $updateData['quote_time'] = now();
            }
            
            if (in_array($status, ['closed', 'lost'])) {
                if ($closeAmount) {
                    $updateData['close_amount'] = $closeAmount;
                }
                $updateData['close_time'] = now();
                if ($closeReason) {
                    $updateData['close_reason'] = $closeReason;
                }
            }
            
            $affected = DB::table('sales_opportunity')
                ->where('id', $id)
                ->update($updateData);
            
            if ($affected > 0) {
                return ['success' => true, 'message' => '更新成功'];
            } else {
                throw new FailedException('销售机会不存在', 10005);
            }
            
        } catch (\Exception $e) {
            Log::error('更新销售机会状态失败', [
                'id' => $id,
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('更新销售机会状态失败', 10006);
        }
    }
    
    /**
     * 获取转化漏斗数据
     * 
     * GET /api/conversation/intent/funnel
     * 
     * @param Request $request
     * @return array
     */
    public function getFunnel(Request $request)
    {
        try {
            $startDate = $request->get('startDate', date('Y-m-d', strtotime('-30 days')));
            $endDate = $request->get('endDate', date('Y-m-d'));
            $employeeAccount = $request->get('employeeAccount');
            $departmentId = $request->get('departmentId');
            
            $query = DB::table('conversion_funnel')
                ->whereBetween('stat_date', [$startDate, $endDate]);
            
            if ($employeeAccount) {
                $query->where('employee_account', $employeeAccount);
            }
            
            if ($departmentId) {
                $query->where('department_id', $departmentId);
            }
            
            $data = $query->orderBy('stat_date', 'desc')
                ->orderBy('stage', 'asc')
                ->get();
            
            // 按日期分组
            $grouped = [];
            foreach ($data as $item) {
                $date = $item->stat_date;
                if (!isset($grouped[$date])) {
                    $grouped[$date] = [];
                }
                $grouped[$date][$item->stage] = [
                    'count' => $item->count,
                    'conversion_rate' => $item->conversion_rate,
                    'avg_duration' => $item->avg_duration
                ];
            }
            
            return [
                'start_date' => $startDate,
                'end_date' => $endDate,
                'data' => $grouped
            ];
            
        } catch (\Exception $e) {
            Log::error('获取转化漏斗失败', [
                'error' => $e->getMessage()
            ]);
            
            throw new FailedException('获取转化漏斗失败', 10007);
        }
    }
}

