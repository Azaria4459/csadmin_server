<?php
declare(strict_types=1);

namespace Modules\Cms\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Illuminate\Support\Facades\Log;
use Modules\Cms\Support\WXBizMsgCrypt;

class MessageController extends Controller
{
    // 企业微信回调配置（需要在企业微信后台配置相同的值）
    private $token = 'zlNNWe7YLiC2GpAYLfM';  // TODO: 改为实际Token
    private $encodingAesKey = 'wuW0oshi3emAP3x3ancVVCrS7Wn9N8rnwBcsg78Tid1';  // TODO: 改为实际EncodingAESKey（43位）
    private $corpId = 'wwc8d94adad275d41c';  // 企业ID
    
    public function load()
    {
        
    }

    /**
     * 测试方法：调用飞书 getUserByDepartment API
     * 使用 Feishu.php 中的 getTenantAccessToken 获取访问令牌
     * 请求参数：
     * - department_id: 部门ID（必填）
     * - page_token: 分页token（可选）
     * 
     * @return \Illuminate\Http\JsonResponse
     */
    public function test()
    {
        try {
            $request = request();
            
            // 获取请求参数
            $departmentId = $request->input('department_id');
            $pageToken = $request->input('page_token', '');
            
            // 验证必填参数
            if (empty($departmentId)) {
                return response()->json([
                    'code' => 400,
                    'message' => 'department_id 参数必填',
                ], 400);
            }
            
            // 引入 Feishu 类
            // Feishu.php 位于 csadmin_server 根目录
            $feishuPath = base_path('Feishu.php');
            
            if (!file_exists($feishuPath)) {
                Log::error('Feishu.php 文件未找到', [
                    'tried_path' => $feishuPath,
                ]);
                return response()->json([
                    'code' => 500,
                    'message' => 'Feishu.php 文件未找到: ' . $feishuPath,
                ], 500);
            }
            
            require_once $feishuPath;
            
            // 创建 Feishu 实例
            $feishu = new \Feishu();
            
            // 使用 Feishu 的 getTenantAccessToken 方法获取访问令牌
            try {
                $accessToken = $feishu->getTenantAccessToken();
            } catch (\Exception $e) {
                Log::error('获取飞书访问令牌失败', [
                    'error' => $e->getMessage(),
                ]);
                return response()->json([
                    'code' => 500,
                    'message' => '获取飞书访问令牌失败: ' . $e->getMessage(),
                ], 500);
            }
            
            // 调用 getUserByDepartment 方法
            $result = $feishu->getUserByDepartment($accessToken, $departmentId, $pageToken);
            
            Log::info('飞书 getUserByDepartment 调用成功', [
                'department_id' => $departmentId,
                'page_token' => $pageToken,
                'has_result' => !empty($result),
            ]);
            
            return response()->json([
                'code' => 0,
                'message' => 'success',
                'data' => $result,
            ]);
            
        } catch (\Exception $e) {
            
            return response()->json([
                'code' => 500,
                'message' => '调用失败: ' . $e->getMessage(),
            ], 500);
        }
    }

    /**
     * 测试方法1：调用飞书获取子部门列表 API
     * 使用 Feishu.php 中的 getTenantAccessToken 获取访问令牌
     * 请求参数：
     * - department_id: 部门ID（必填）
     * - department_id_type: 部门ID类型（可选，默认：department_id）
     * - fetch_child: 是否递归获取子部门（可选，默认：false）
     * - page_size: 分页大小（可选，默认：50）
     * - page_token: 分页token（可选）
     * 
     * @return \Illuminate\Http\JsonResponse
     */
    public function test1()
    {
        try {
            $request = request();
            
            // 获取请求参数
            $departmentId = $request->input('department_id');
            $departmentIdType = $request->input('department_id_type', 'department_id');
            $fetchChild = $request->input('fetch_child', false);
            $pageSize = (int)$request->input('page_size', 50);
            $pageToken = $request->input('page_token', '');
             
            // 引入 Feishu 类
            $feishuPath = base_path('Feishu.php');
            
            if (!file_exists($feishuPath)) {
                Log::error('Feishu.php 文件未找到', [
                    'tried_path' => $feishuPath,
                ]);
                return response()->json([
                    'code' => 500,
                    'message' => 'Feishu.php 文件未找到: ' . $feishuPath,
                ], 500);
            }
            
            require_once $feishuPath;
            
            // 创建 Feishu 实例
            $feishu = new \Feishu();
            
            // 使用 Feishu 的 getTenantAccessToken 方法获取访问令牌
            try {
                $accessToken = $feishu->getTenantAccessToken();
            } catch (\Exception $e) { 
                return response()->json([
                    'code' => 500,
                    'message' => '获取飞书访问令牌失败: ' . $e->getMessage(),
                ], 500);
            }
            
            // 调用 getDepartmentChildren 方法
            $result = $feishu->getDepartmentChildren(
                $accessToken, 
                $departmentId, 
                $departmentIdType, 
                $fetchChild, 
                $pageSize, 
                $pageToken
            ); 
            
            return response()->json([
                'code' => 0,
                'message' => 'success',
                'data' => $result,
            ]);
            
        } catch (\Exception $e) {
            
            return response()->json([
                'code' => 500,
                'message' => '调用失败: ' . $e->getMessage(),
            ], 500);
        }
    }

    /**
     * 测试方法2：遍历指定部门的所有子部门，查找符合条件的员工
     * 使用 Feishu.php 中的 getTenantAccessToken 获取访问令牌
     * 请求参数：
     * - department_id: 部门ID（必填）
     * 
     * @return \Illuminate\Http\JsonResponse
     */
    public function test2()
    {
        try {
            $request = request();
            
            // 获取请求参数
            $departmentId = $request->input('department_id');
            
            // 验证必填参数
            if (empty($departmentId)) {
                return response()->json([
                    'code' => 400,
                    'message' => 'department_id 参数必填',
                ], 400);
            }
            
            // 目标邮箱列表（完全匹配）
            $targetEmails = [
                'cindy@feilong-consult.com',
                'alex@starlight.ph',
                'elsa@feilong-consult.com'
            ];
            
            // 目标关键词列表（部分匹配）
            $targetKeywords = ['cindy', 'alex', 'elsa'];
            
            // 引入 Feishu 类
            $feishuPath = base_path('Feishu.php');
            
            if (!file_exists($feishuPath)) {
                Log::error('Feishu.php 文件未找到', [
                    'tried_path' => $feishuPath,
                ]);
                return response()->json([
                    'code' => 500,
                    'message' => 'Feishu.php 文件未找到: ' . $feishuPath,
                ], 500);
            }
            
            require_once $feishuPath;
            
            // 创建 Feishu 实例
            $feishu = new \Feishu();
            
            // 使用 Feishu 的 getTenantAccessToken 方法获取访问令牌
            try {
                $accessToken = $feishu->getTenantAccessToken();
            } catch (\Exception $e) {
                Log::error('获取飞书访问令牌失败', [
                    'error' => $e->getMessage(),
                ]);
                return response()->json([
                    'code' => 500,
                    'message' => '获取飞书访问令牌失败: ' . $e->getMessage(),
                ], 500);
            }
            
            // 递归获取所有子部门ID（包括当前部门）
            $allDepartmentIds = $this->getAllSubDepartments($feishu, $accessToken, $departmentId);
            
            Log::info('找到所有部门', [
                'root_department_id' => $departmentId,
                'total_departments' => count($allDepartmentIds),
                'department_ids' => $allDepartmentIds,
            ]);
            
            // 遍历所有部门，获取员工信息
            $matchedEmployees = [];
            $allEmployees = [];
            
            foreach ($allDepartmentIds as $deptId) {
                try {
                    // 获取该部门的所有员工（处理分页）
                    $employees = $this->getAllEmployeesFromDepartment($feishu, $accessToken, $deptId);
                    $allEmployees = array_merge($allEmployees, $employees);
                    
                    // 过滤符合条件的员工
                    foreach ($employees as $employee) {
                        $email = $employee['email'] ?? '';
                        
                        if (empty($email)) {
                            continue;
                        }
                        
                        // 检查是否完全匹配目标邮箱
                        $isExactMatch = in_array(strtolower($email), array_map('strtolower', $targetEmails));
                        
                        // 检查是否包含目标关键词
                        $isKeywordMatch = false;
                        $emailLower = strtolower($email);
                        foreach ($targetKeywords as $keyword) {
                            if (strpos($emailLower, strtolower($keyword)) !== false) {
                                $isKeywordMatch = true;
                                break;
                            }
                        }
                        
                        // 如果匹配，添加到结果中
                        if ($isExactMatch || $isKeywordMatch) {
                            // 避免重复添加
                            $employeeId = $employee['user_id'] ?? $employee['open_id'] ?? '';
                            if (!isset($matchedEmployees[$employeeId])) {
                                $matchedEmployees[$employeeId] = $employee;
                            }
                        }
                    }
                } catch (\Exception $e) {
                    Log::warning('获取部门员工失败', [
                        'department_id' => $deptId,
                        'error' => $e->getMessage(),
                    ]);
                    // 继续处理下一个部门
                    continue;
                }
            }
            
            Log::info('员工查找完成', [
                'root_department_id' => $departmentId,
                'total_departments' => count($allDepartmentIds),
                'total_employees' => count($allEmployees),
                'matched_employees_count' => count($matchedEmployees),
            ]);
            
            return response()->json([
                'code' => 0,
                'message' => 'success',
                'data' => [
                    'root_department_id' => $departmentId,
                    'total_departments' => count($allDepartmentIds),
                    'department_ids' => $allDepartmentIds,
                    'total_employees' => count($allEmployees),
                    'matched_employees_count' => count($matchedEmployees),
                    'matched_employees' => array_values($matchedEmployees),
                ],
            ]);
            
        } catch (\Exception $e) {
            Log::error('调用飞书查找员工失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);
            
            return response()->json([
                'code' => 500,
                'message' => '调用失败: ' . $e->getMessage(),
            ], 500);
        }
    }
    
    /**
     * 递归获取所有子部门ID（包括当前部门）
     * 
     * @param \Feishu $feishu Feishu实例
     * @param string $accessToken 访问令牌
     * @param string $departmentId 部门ID
     * @return array 所有部门ID列表
     */
    private function getAllSubDepartments($feishu, $accessToken, $departmentId)
    {
        $allDepartmentIds = [$departmentId]; // 包含当前部门
        $processedDepartments = []; // 已处理的部门，避免重复
        
        // 递归获取所有子部门
        $this->recursiveGetSubDepartments($feishu, $accessToken, $departmentId, $allDepartmentIds, $processedDepartments);
        
        // 去重
        return array_unique($allDepartmentIds);
    }
    
    /**
     * 递归获取子部门（内部方法）
     * 
     * @param \Feishu $feishu Feishu实例
     * @param string $accessToken 访问令牌
     * @param string $departmentId 部门ID
     * @param array &$allDepartmentIds 所有部门ID列表（引用传递）
     * @param array &$processedDepartments 已处理的部门列表（引用传递）
     */
    private function recursiveGetSubDepartments($feishu, $accessToken, $departmentId, &$allDepartmentIds, &$processedDepartments)
    {
        // 避免重复处理
        if (in_array($departmentId, $processedDepartments)) {
            return;
        }
        
        $processedDepartments[] = $departmentId;
        
        try {
            $pageToken = '';
            
            do {
                // 获取直接子部门（不递归，手动递归）
                $result = $feishu->getDepartmentChildren($accessToken, $departmentId, 'department_id', false, 50, $pageToken);
                
                // 解析返回的子部门列表
                if (isset($result['data']['items']) && is_array($result['data']['items'])) {
                    foreach ($result['data']['items'] as $dept) {
                        $childDeptId = $dept['department_id'] ?? null;
                        if ($childDeptId && !in_array($childDeptId, $allDepartmentIds)) {
                            $allDepartmentIds[] = $childDeptId;
                            // 递归获取子部门的子部门
                            $this->recursiveGetSubDepartments($feishu, $accessToken, $childDeptId, $allDepartmentIds, $processedDepartments);
                        }
                    }
                }
                
                // 获取下一页token
                $pageToken = $result['data']['page_token'] ?? '';
                
            } while (!empty($pageToken));
            
        } catch (\Exception $e) {
            Log::warning('获取子部门失败', [
                'department_id' => $departmentId,
                'error' => $e->getMessage(),
            ]);
        }
    }
    
    /**
     * 获取部门的所有员工（处理分页）
     * 
     * @param \Feishu $feishu Feishu实例
     * @param string $accessToken 访问令牌
     * @param string $departmentId 部门ID
     * @return array 员工列表
     */
    private function getAllEmployeesFromDepartment($feishu, $accessToken, $departmentId)
    {
        $allEmployees = [];
        $pageToken = '';
        
        do {
            try {
                $result = $feishu->getUserByDepartment($accessToken, $departmentId, $pageToken);
                
                // 解析返回的员工列表
                if (isset($result['data']['items']) && is_array($result['data']['items'])) {
                    $allEmployees = array_merge($allEmployees, $result['data']['items']);
                }
                
                // 获取下一页token
                $pageToken = $result['data']['page_token'] ?? '';
                
            } catch (\Exception $e) {
                Log::warning('获取部门员工失败', [
                    'department_id' => $departmentId,
                    'page_token' => $pageToken,
                    'error' => $e->getMessage(),
                ]);
                break;
            }
        } while (!empty($pageToken));
        
        return $allEmployees;
    }

    /**
     * 接收企业微信回调通知
     * 参考文档: https://developer.work.weixin.qq.com/document/10514
     * 
     * GET请求: 用于验证URL有效性
     * POST请求: 接收事件推送
     */
    public function notify()
    {
        $request = request();

        // 记录完整请求日志
        Log::info('WeChat notify callback', [
            'method' => $request->method(),
            'path' => $request->path(),
            'ip' => $request->ip(),
            'query' => $request->query(),
            'input' => $request->all(),
            'raw_body' => $request->getContent(),
            'headers' => $request->headers->all(),
        ]);

        try {
            // GET请求：验证URL
            if ($request->isMethod('GET')) {
                return $this->verifyUrl($request);
            }
            
            // POST请求：接收事件推送
            if ($request->isMethod('POST')) {
                return $this->handleEvent($request);
            }

            return response()->json(['code' => 405, 'message' => 'Method not allowed'], 405);

        } catch (\Exception $e) {
            Log::error('WeChat notify callback error', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);
            return response()->json(['code' => 500, 'message' => 'Internal error'], 500);
        }
    }

    /**
     * 验证URL有效性（企业微信首次配置回调URL时调用）
     * GET参数：msg_signature, timestamp, nonce, echostr
     */
    private function verifyUrl($request)
    {
        $msgSignature = $request->query('msg_signature');
        $timestamp = $request->query('timestamp');
        $nonce = $request->query('nonce');
        $echostr = $request->query('echostr');

        Log::info('WeChat URL verification request', [
            'msg_signature' => $msgSignature,
            'timestamp' => $timestamp,
            'nonce' => $nonce,
            'echostr' => $echostr,
        ]);

        try {
            $wxcpt = new WXBizMsgCrypt($this->token, $this->encodingAesKey, $this->corpId);
            list($errCode, $sEchoStr) = $wxcpt->verifyUrl($msgSignature, $timestamp, $nonce, $echostr);

            if ($errCode != 0) {
                Log::error('WeChat URL verification failed', [
                    'error_code' => $errCode,
                    'msg_signature' => $msgSignature,
                ]);
                return response('验证失败', 403);
            }

            Log::info('WeChat URL verification success', [
                'decrypted_echostr' => $sEchoStr,
            ]);

            // 返回解密后的echostr
            return response($sEchoStr);

        } catch (\Exception $e) {
            Log::error('WeChat URL verification exception', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);
            return response('验证异常', 500);
        }
    }

    /**
     * 处理企业微信事件推送
     * POST body: <xml>加密的消息体</xml>
     * GET参数：msg_signature, timestamp, nonce
     */
    private function handleEvent($request)
    {
        $msgSignature = $request->query('msg_signature');
        $timestamp = $request->query('timestamp');
        $nonce = $request->query('nonce');
        $postData = $request->getContent();

        Log::info('WeChat event push received', [
            'msg_signature' => $msgSignature,
            'timestamp' => $timestamp,
            'nonce' => $nonce,
            'post_data_length' => strlen($postData),
        ]);

        try {
            $wxcpt = new WXBizMsgCrypt($this->token, $this->encodingAesKey, $this->corpId);
            list($errCode, $sMsg) = $wxcpt->decryptMsg($msgSignature, $timestamp, $nonce, $postData);

            if ($errCode != 0) {
                Log::error('WeChat message decrypt failed', [
                    'error_code' => $errCode,
                    'post_data' => $postData,
                ]);
                // 即使解密失败也要返回success，避免企业微信重试
                return response('success');
            }

            // 解析解密后的XML
            $xml = simplexml_load_string($sMsg, 'SimpleXMLElement', LIBXML_NOCDATA);
            
            Log::info('WeChat event decrypted', [
                'decrypted_xml' => $sMsg,
                'parsed_data' => json_decode(json_encode($xml), true),
            ]);

            // 根据不同的事件类型进行处理
            $msgType = (string)$xml->MsgType;
            
            switch ($msgType) {
                case 'event':
                    $this->handleEventType($xml);
                    break;
                case 'text':
                case 'image':
                case 'voice':
                case 'video':
                case 'file':
                    $this->handleMessageType($xml);
                    break;
                default:
                    Log::warning('Unknown message type', ['msg_type' => $msgType]);
            }

            // 企业微信要求5秒内响应success
            return response('success');

        } catch (\Exception $e) {
            Log::error('WeChat event handle exception', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);
            // 即使异常也返回success
            return response('success');
        }
    }

    /**
     * 处理事件类型消息
     */
    private function handleEventType($xml)
    {
        $event = (string)$xml->Event;
        
        Log::info('Handle WeChat event', [
            'event' => $event,
            'from_user' => (string)$xml->FromUserName,
            'to_user' => (string)$xml->ToUserName,
            'create_time' => (string)$xml->CreateTime,
        ]);

        // TODO: 根据具体事件类型处理
        // subscribe - 成员关注
        // unsubscribe - 成员取消关注
        // enter_agent - 进入应用
        // click - 点击菜单
        // view - 点击菜单跳转链接
        // scancode_push - 扫码推事件
        // location - 上报地理位置
        // change_contact - 通讯录变更事件（需要解析ChangeType）
    }

    /**
     * 处理普通消息类型
     */
    private function handleMessageType($xml)
    {
        $msgType = (string)$xml->MsgType;
        
        Log::info('Handle WeChat message', [
            'msg_type' => $msgType,
            'msg_id' => (string)$xml->MsgId,
            'from_user' => (string)$xml->FromUserName,
            'to_user' => (string)$xml->ToUserName,
            'create_time' => (string)$xml->CreateTime,
        ]);

        // TODO: 根据具体消息类型处理并存储到数据库
    }

    /**
     * 绑定资源文件到聊天记录
     * 处理 msgtype 为资源文件类型（image/voice/video/file）且 resource_id 为空的消息
     * 通过 content 中的 sdkfileid 或 md5sum 匹配 wechat_resource 表中的资源并绑定
     * 
     * 请求参数（可选）：
     * - limit: 每次处理的消息数量（默认1000）
     * - dry_run: 是否仅预览不实际更新（默认false）
     * 
     * @return \Illuminate\Http\JsonResponse
     */
    public function bindResources()
    {
        try {
            $request = request();
            $limit = (int)$request->input('limit', 1000);
            $dryRun = (bool)$request->input('dry_run', false);
            
            Log::info('开始绑定资源文件到聊天记录', [
                'limit' => $limit,
                'dry_run' => $dryRun,
            ]);
            
            // 1. 查询所有资源文件类型且 resource_id 为空的消息
            $messages = \DB::table('wechat_message')
                ->whereIn('msgtype', ['image', 'voice', 'video', 'file'])
                ->whereNull('resource_id')
                ->whereNotNull('content')
                ->limit($limit)
                ->get(['id', 'msgid', 'msgtype', 'content']);
            
            if ($messages->isEmpty()) {
                return response()->json([
                    'code' => 0,
                    'message' => '没有需要绑定的消息',
                    'data' => [
                        'total' => 0,
                        'matched' => 0,
                        'updated' => 0,
                    ],
                ]);
            }
            
            Log::info('找到需要绑定的消息', ['count' => $messages->count()]);
            
            $matchedCount = 0;
            $updatedCount = 0;
            $errorCount = 0;
            $details = [];
            
            // 2. 遍历每条消息，提取 sdkfileid 和 md5sum，查找匹配的资源
            foreach ($messages as $message) {
                try {
                    $content = is_string($message->content) 
                        ? json_decode($message->content, true) 
                        : $message->content;
                    
                    if (!is_array($content) || !isset($content[$message->msgtype])) {
                        continue;
                    }
                    
                    $mediaData = $content[$message->msgtype];
                    $sdkfileid = $mediaData['sdkfileid'] ?? null;
                    $md5sum = $mediaData['md5sum'] ?? null;
                    
                    if (empty($sdkfileid) && empty($md5sum)) {
                        continue;
                    }
                    
                    // 3. 在 wechat_resource 表中查找匹配的资源
                    // 优先使用 md5sum（有唯一索引，查询快），其次使用 sdkfileid
                    $resource = null;
                    
                    if (!empty($md5sum)) {
                        $resource = \DB::table('wechat_resource')
                            ->where('md5sum', $md5sum)
                            ->where('status', 1)
                            ->first(['id', 'md5sum', 'sdkfileid']);
                    }
                    
                    if (!$resource && !empty($sdkfileid)) {
                        $resource = \DB::table('wechat_resource')
                            ->where('sdkfileid', $sdkfileid)
                            ->where('status', 1)
                            ->first(['id', 'md5sum', 'sdkfileid']);
                    }
                    
                    if ($resource) {
                        $matchedCount++;
                        
                        if (!$dryRun) {
                            // 4. 更新消息的 resource_id
                            $updated = \DB::table('wechat_message')
                                ->where('id', $message->id)
                                ->update(['resource_id' => $resource->id]);
                            
                            if ($updated) {
                                $updatedCount++;
                                
                                // 增加资源的引用计数
                                \DB::table('wechat_resource')
                                    ->where('id', $resource->id)
                                    ->increment('download_count');
                            }
                        }
                        
                        $details[] = [
                            'msgid' => $message->msgid,
                            'msgtype' => $message->msgtype,
                            'resource_id' => $resource->id,
                            'matched_by' => !empty($md5sum) ? 'md5sum' : 'sdkfileid',
                        ];
                    }
                    
                } catch (\Exception $e) {
                    $errorCount++;
                    Log::warning('处理消息时发生错误', [
                        'msgid' => $message->msgid ?? 'unknown',
                        'error' => $e->getMessage(),
                    ]);
                }
            }
            
            $result = [
                'total' => $messages->count(),
                'matched' => $matchedCount,
                'updated' => $dryRun ? 0 : $updatedCount,
                'errors' => $errorCount,
                'dry_run' => $dryRun,
            ];
            
            if ($dryRun && $matchedCount > 0) {
                $result['preview'] = array_slice($details, 0, 10); // 预览前10条
            }
            
            Log::info('资源绑定完成', $result);
            
            return response()->json([
                'code' => 0,
                'message' => $dryRun ? '预览完成（未实际更新）' : '绑定完成',
                'data' => $result,
            ]);
            
        } catch (\Exception $e) {
            Log::error('绑定资源文件失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);
            
            return response()->json([
                'code' => 500,
                'message' => '绑定失败: ' . $e->getMessage(),
            ], 500);
        }
    }
}


