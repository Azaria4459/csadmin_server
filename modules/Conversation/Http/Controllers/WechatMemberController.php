<?php

declare(strict_types=1);

namespace Modules\Conversation\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Illuminate\Http\Request;
use Modules\Conversation\Models\WechatMember;

/**
 * 微信成员控制器
 * 提供成员列表、搜索、更新等功能
 */
class WechatMemberController extends Controller
{
    /**
     * 获取成员列表
     * 
     * 请求参数：
     * - page: 页码，默认1
     * - pageSize: 每页数量，默认20，最大100
     * - type: 成员类型过滤，可选值：1-员工，2-用户
     * - keyword: 搜索关键词（搜索账号名称或别名）
     * 
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function index(Request $request)
    {
        try {
            // 获取请求参数
            $page = (int) $request->get('page', 1);
            $pageSize = min((int) $request->get('pageSize', 20), 100); // 最大100条
            $type = $request->get('type');
            $keyword = $request->get('keyword');

            // 参数校验
            if ($page < 1) {
                $page = 1;
            }
            if ($pageSize < 1) {
                $pageSize = 20;
            }

            // 类型参数转换
            $typeInt = null;
            if ($type !== null && $type !== '') {
                $typeInt = (int) $type;
                if (!in_array($typeInt, [1, 2])) {
                    $typeInt = null;
                }
            }

            // 获取当前登录用户的权限
            $user = $this->getLoginUser();
            
            // 权限过滤逻辑：
            // 1. 超级管理员 -> 可以看到所有成员
            // 2. 拥有"用户管理"或"权限管理"权限 -> 可以看到所有成员（用于分配权限）
            // 3. 普通用户 -> 受 managed_employees 限制
            $managedEmployees = null;  // 默认为超级管理员模式（看所有）
            
            if ($user && !$user->isSuperAdmin()) {
                // 检查是否有权限管理相关权限
                if (!$this->hasPermissionManageAccess($user)) {
                    // 普通用户，受 managed_employees 限制
                    $managedEmployees = $user->getManagedEmployees();
                }
                // 否则 managedEmployees 保持 null，可以看到所有
            }

            // 调用模型方法获取成员列表（带权限过滤）
            $result = WechatMember::getMemberList($page, $pageSize, $keyword, $typeInt, $managedEmployees);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return $result;

        } catch (\Exception $e) {
            \Log::error('获取成员列表失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 获取成员详情
     * 
     * @param int $id 成员ID
     * @return array 返回数据数组
     */
    public function show(int $id)
    {
        try {
            // 查询成员详情
            $member = WechatMember::getMemberById($id);

            if (!$member) {
                throw new FailedException('成员不存在', 10005);
            }

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'member' => $member
            ];

        } catch (\Exception $e) {
            \Log::error('获取成员详情失败', [
                'member_id' => $id,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 更新成员信息
     * 
     * 请求参数：
     * - nick_name: 别名（可选）
     * - remark_name: 备注昵称（可选）
     * - avatar: 头像URL（可选）
     * - gender: 性别，0-未知，1-男性，2-女性（可选）
     * - type: 类型，1-员工，2-用户（可选）
     * 
     * @param Request $request HTTP请求对象
     * @param int $id 成员ID
     * @return array 返回数据数组
     */
    public function update(Request $request, int $id)
    {
        try {
            // 获取更新数据
            $data = [];
            
            if ($request->has('nick_name')) {
                $data['nick_name'] = $request->input('nick_name');
            }
            
            if ($request->has('remark_name')) {
                $data['remark_name'] = $request->input('remark_name');
            }
            
            if ($request->has('avatar')) {
                $data['avatar'] = $request->input('avatar');
            }
            
            if ($request->has('gender')) {
                $gender = (int) $request->input('gender');
                if (in_array($gender, [0, 1, 2])) {
                    $data['gender'] = $gender;
                } else {
                    throw new FailedException('性别参数无效，必须是0（未知）、1（男性）或2（女性）', 10005);
                }
            }
            
            if ($request->has('type')) {
                $type = (int) $request->input('type');
                if (in_array($type, [1, 2])) {
                    $data['type'] = $type;
                } else {
                    throw new FailedException('类型参数无效，必须是1（员工）或2（用户）', 10005);
                }
            }

            if (empty($data)) {
                throw new FailedException('没有需要更新的数据', 10005);
            }

            // 更新成员信息
            $success = WechatMember::updateMember($id, $data);

            if (!$success) {
                throw new FailedException('更新失败，成员不存在或数据无变化', 10005);
            }

            // 获取更新后的成员信息
            $member = WechatMember::getMemberById($id);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'message' => '更新成功',
                'member' => $member
            ];

        } catch (\Exception $e) {
            \Log::error('更新成员信息失败', [
                'member_id' => $id,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 获取成员统计信息
     * 
     * @return array 返回数据数组
     */
    public function statistics()
    {
        try {
            $stats = WechatMember::getStatistics();

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return $stats;

        } catch (\Exception $e) {
            \Log::error('获取成员统计信息失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 批量更新成员类型
     * 
     * 请求参数：
     * - ids: 成员ID数组（必填）
     * - type: 目标类型，1-员工，2-用户（必填）
     * 
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function batchUpdateType(Request $request)
    {
        try {
            // 获取参数
            $ids = $request->input('ids', []);
            $type = (int) $request->input('type');

            // 参数校验
            if (empty($ids) || !is_array($ids)) {
                throw new FailedException('请选择要更新的成员', 10005);
            }

            if (!in_array($type, [1, 2])) {
                throw new FailedException('类型参数无效，必须是1（员工）或2（用户）', 10005);
            }

            // 批量更新
            $affectedRows = WechatMember::batchUpdateType($ids, $type);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'message' => '批量更新成功',
                'affected_rows' => $affectedRows
            ];

        } catch (\Exception $e) {
            \Log::error('批量更新成员类型失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 同步微信成员信息
     * 
     * 调用本地 Java 服务接口同步员工和外部联系人信息
     * 
     * @return array 返回数据数组
     */
    public function syncFromWeChat()
    {
        try {
            \Log::info('开始同步微信成员信息');

            $results = [
                'employees' => ['success' => false, 'message' => ''],
                'contacts' => ['success' => false, 'message' => ''],
            ];

            // 1. 同步员工信息
            try {
                $employeesResponse = $this->callLocalApi('http://localhost:7070/wechat/sync/employees');
                $results['employees'] = [
                    'success' => true,
                    'message' => $employeesResponse['message'] ?? '员工信息同步已启动',
                    'data' => $employeesResponse
                ];
                \Log::info('员工信息同步请求成功', ['response' => $employeesResponse]);
            } catch (\Exception $e) {
                $results['employees'] = [
                    'success' => false,
                    'message' => '员工信息同步失败: ' . $e->getMessage()
                ];
                \Log::error('员工信息同步请求失败', [
                    'error' => $e->getMessage(),
                    'trace' => $e->getTraceAsString(),
                ]);
            }

            // 2. 同步外部联系人信息
            try {
                $contactsResponse = $this->callLocalApi('http://localhost:7070/wechat/sync/contacts');
                $results['contacts'] = [
                    'success' => true,
                    'message' => $contactsResponse['message'] ?? '外部联系人信息同步已启动',
                    'data' => $contactsResponse
                ];
                \Log::info('外部联系人信息同步请求成功', ['response' => $contactsResponse]);
            } catch (\Exception $e) {
                $results['contacts'] = [
                    'success' => false,
                    'message' => '外部联系人信息同步失败: ' . $e->getMessage()
                ];
                \Log::error('外部联系人信息同步请求失败', [
                    'error' => $e->getMessage(),
                    'trace' => $e->getTraceAsString(),
                ]);
            }

            // 判断整体是否成功
            $overallSuccess = $results['employees']['success'] || $results['contacts']['success'];
            
            $message = '同步请求已提交';
            if ($results['employees']['success'] && $results['contacts']['success']) {
                $message = '员工和外部联系人信息同步已启动，请稍后刷新查看结果';
            } elseif ($results['employees']['success']) {
                $message = '员工信息同步已启动，但外部联系人同步失败';
            } elseif ($results['contacts']['success']) {
                $message = '外部联系人信息同步已启动，但员工同步失败';
            } else {
                $message = '同步失败，请检查 collect_server 服务是否正常运行';
            }

            \Log::info('微信成员信息同步完成', [
                'results' => $results,
                'overall_success' => $overallSuccess,
            ]);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'message' => $message,
                'success' => $overallSuccess,
                'details' => $results
            ];

        } catch (\Exception $e) {
            \Log::error('同步微信成员信息失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 调用本地 API
     * 
     * @param string $url API URL
     * @param string $method HTTP方法，默认POST
     * @param array $data 请求数据
     * @return array 响应数据
     * @throws \Exception
     */
    private function callLocalApi(string $url, string $method = 'POST', array $data = []): array
    {
        $ch = curl_init();

        curl_setopt($ch, CURLOPT_URL, $url);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch, CURLOPT_TIMEOUT, 10); // 10秒超时
        curl_setopt($ch, CURLOPT_CONNECTTIMEOUT, 5); // 5秒连接超时
        curl_setopt($ch, CURLOPT_CUSTOMREQUEST, $method);

        // 记录请求详情
        \Log::debug('调用本地API', [
            'url' => $url,
            'method' => $method,
            'data' => $data
        ]);

        $response = curl_exec($ch);
        $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
        $error = curl_error($ch);
        
        // 记录响应详情
        \Log::debug('本地API响应', [
            'url' => $url,
            'http_code' => $httpCode,
            'response' => $response,
            'error' => $error
        ]);
        
        curl_close($ch);

        if ($error) {
            throw new \Exception("请求失败: {$error}");
        }

        if ($httpCode !== 200) {
            throw new \Exception("HTTP错误: {$httpCode}, 响应: {$response}");
        }

        $result = json_decode($response, true);
        if (json_last_error() !== JSON_ERROR_NONE) {
            throw new \Exception("响应解析失败: " . json_last_error_msg() . ", 原始响应: {$response}");
        }

        return $result ?? [];
    }
    
    /**
     * 检查用户是否有权限管理访问权限
     * 
     * 拥有以下权限之一即可：
     * - User@* (用户管理权限)
     * - Permissions@* (权限管理权限)
     * - Roles@* (角色管理权限)
     * 
     * @param \Modules\User\Models\User $user 当前登录用户
     * @return bool 是否有权限
     */
    private function hasPermissionManageAccess($user): bool
    {
        // 加载用户权限
        $user->withPermissions();
        
        // 获取用户的所有权限
        $permissions = $user->getAttribute('permissions');
        
        if (!$permissions || $permissions->isEmpty()) {
            return false;
        }
        
        // 检查是否有用户管理或权限管理相关的权限标识
        foreach ($permissions as $permission) {
            $mark = $permission->permission_mark ?? '';
            
            // 如果有这些权限标识之一，认为有权限管理权限
            if (
                str_contains($mark, 'User@') ||           // 用户管理权限
                str_contains($mark, 'Permissions@') ||    // 权限管理权限
                str_contains($mark, 'Roles@')             // 角色管理权限
            ) {
                return true;
            }
        }
        
        return false;
    }
}

