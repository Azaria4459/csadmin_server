<?php

declare(strict_types=1);

namespace Modules\Conversation\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Illuminate\Http\Request;
use Modules\Conversation\Services\ResponsibleUserStatisticsService;

/**
 * 责任人统计控制器
 * 提供责任人响应时间统计数据的查询功能
 */
class ResponsibleUserStatisticsController extends Controller
{
    /**
     * 统计服务
     *
     * @var ResponsibleUserStatisticsService
     */
    private ResponsibleUserStatisticsService $statisticsService;

    /**
     * 构造函数
     *
     * @param ResponsibleUserStatisticsService $statisticsService
     */
    public function __construct(ResponsibleUserStatisticsService $statisticsService)
    {
        $this->statisticsService = $statisticsService;
    }

    /**
     * 获取统计数据列表
     * 
     * 请求参数：
     * - page: 页码，默认1
     * - pageSize: 每页数量，默认20，最大100
     * - responsible_user_id: 责任人ID过滤（可选）
     * - orderBy: 排序字段（可选）
     * - order: 排序方向，asc 或 desc（可选）
     * 
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function index(Request $request)
    {
        try {
            // 获取请求参数
            $page = (int) $request->get('page', 1);
            // 支持 limit 和 pageSize 两种参数名（前端使用 limit，后端使用 pageSize）
            $pageSize = min((int) ($request->get('pageSize') ?: $request->get('limit', 20)), 100); // 最大100条
            $responsibleUserId = $request->get('responsible_user_id');
            $orderBy = $request->get('orderBy');
            $order = $request->get('order', 'asc'); // 默认升序

            // 参数校验
            if ($page < 1) {
                $page = 1;
            }
            if ($pageSize < 1) {
                $pageSize = 20;
            }
            
            // 排序方向校验
            if ($order !== 'asc' && $order !== 'desc') {
                $order = 'asc';
            }

            // 获取统计数据列表
            $result = $this->statisticsService->getStatisticsList(
                $page,
                $pageSize,
                $responsibleUserId,
                $orderBy,
                $order
            );

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return $result;

        } catch (\Exception $e) {
            \Log::error('获取责任人统计数据列表失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }
}

