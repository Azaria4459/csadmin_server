<?php

declare(strict_types=1);

namespace Modules\Conversation\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Illuminate\Http\Request;
use Modules\Conversation\Models\Conversation;
use Modules\Conversation\Models\WeChatMessage;

/**
 * 会话控制器
 * 提供会话列表、搜索、详情查询等功能
 */
class ConversationController extends Controller
{
    /**
     * 获取会话列表
     * 
     * 请求参数：
     * - page: 页码，默认1
     * - pageSize: 每页数量，默认20，最大100
     * - type: 会话类型过滤，可选值：single/group
     * - keyword: 搜索关键词（搜索参与人员userid）
     * - groupName: 群名称搜索关键词（搜索群名称或备注名称）
     * - messageContent: 聊天记录搜索关键词（搜索消息内容）
     * - conversationId: 会话ID搜索关键词（精确匹配或模糊匹配）
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
            $groupName = $request->get('groupName');
            $messageContent = $request->get('messageContent');
            $conversationId = $request->get('conversationId');

            // 参数校验
            if ($page < 1) {
                $page = 1;
            }
            if ($pageSize < 1) {
                $pageSize = 20;
            }

            // 获取当前登录用户的权限
            $user = $this->getLoginUser();
            
            // 超级管理员可以看到所有会话，传 null
            // 普通用户根据 managed_employees 配置进行权限过滤
            $managedEmployees = null;
            if ($user && !$user->isSuperAdmin()) {
                $managedEmployees = $user->getManagedEmployees();
            }

            // 调用模型方法获取会话列表（带权限过滤）
            $result = Conversation::getConversationList($page, $pageSize, $keyword, $type, $groupName, $managedEmployees, $messageContent, $conversationId);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return $result;

        } catch (\Exception $e) {
            \Log::error('获取会话列表失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 获取会话详情
     * 
     * @param string $conversationId 会话ID
     * @return array 返回数据数组
     */
    public function show(string $conversationId)
    {
        try {
            // 查询会话详情
            $conversation = Conversation::getConversationDetail($conversationId);

            if (!$conversation) {
                throw new FailedException('会话不存在', 10005);
            }

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'conversation' => $conversation
            ];

        } catch (\Exception $e) {
            \Log::error('获取会话详情失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 根据会话ID获取聊天记录
     * 
     * 请求参数：
     * - page: 页码，默认1
     * - pageSize: 每页数量，默认50，最大200
     * - searchSender: 搜索发送者（from_user 或昵称）
     * - searchContent: 搜索内容关键词
     * 
     * @param string $conversationId 会话ID
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function messages(string $conversationId, Request $request)
    {
        try {
            // 先检查会话是否存在
            $conversation = Conversation::getConversationDetail($conversationId);
            if (!$conversation) {
                throw new FailedException('会话不存在', 10005);
            }

            // 获取请求参数
            $page = (int) $request->get('page', 1);
            $pageSize = min((int) $request->get('pageSize', 50), 200); // 最大200条
            $searchSender = $request->get('searchSender');
            $searchContent = $request->get('searchContent');

            // 参数校验
            if ($page < 1) {
                $page = 1;
            }
            if ($pageSize < 1) {
                $pageSize = 50;
            }

            // 获取聊天记录
            $result = WeChatMessage::getMessagesByConversation(
                $conversationId, 
                $page, 
                $pageSize,
                $searchSender,
                $searchContent
            );

            // 添加会话信息
            $result['conversation'] = $conversation;

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return $result;

        } catch (\Exception $e) {
            \Log::error('获取聊天记录失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 搜索会话
     * 
     * 请求参数：
     * - keyword: 搜索关键词（必填，搜索参与人员userid）
     * - type: 会话类型过滤，可选值：single/group
     * - page: 页码，默认1
     * - pageSize: 每页数量，默认20，最大100
     * 
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function search(Request $request)
    {
        try {
            // 获取搜索关键词
            $keyword = $request->get('keyword');

            if (!$keyword) {
                throw new FailedException('请输入搜索关键词', 10005);
            }

            // 获取其他参数
            $page = (int) $request->get('page', 1);
            $pageSize = min((int) $request->get('pageSize', 20), 100);
            $type = $request->get('type');

            // 参数校验
            if ($page < 1) {
                $page = 1;
            }
            if ($pageSize < 1) {
                $pageSize = 20;
            }

            // 调用模型方法搜索会话
            $result = Conversation::getConversationList($page, $pageSize, $keyword, $type);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return $result;

        } catch (\Exception $e) {
            \Log::error('搜索会话失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 更新会话备注名称
     * 
     * 请求参数：
     * - remark_name: 备注名称
     * 
     * @param string $conversationId 会话ID
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function updateRemarkName(string $conversationId, Request $request)
    {
        try {
            // 获取备注名称（可以为空，表示清空备注）
            $remarkName = $request->input('remark_name', '');

            // 查找会话
            $conversation = Conversation::query()
                ->withoutGlobalScopes()
                ->where('conversation_id', $conversationId)
                ->first();

            if (!$conversation) {
                throw new FailedException('会话不存在', 10005);
            }

            // 更新备注名称
            $conversation->remark_name = $remarkName ?: null;
            $conversation->save();

            \Log::info('更新会话备注名称成功', [
                'conversation_id' => $conversationId,
                'remark_name' => $remarkName,
            ]);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'message' => '备注名称更新成功',
                'conversation' => $conversation
            ];

        } catch (\Exception $e) {
            \Log::error('更新会话备注名称失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 删除会话（软删除）
     * 设置 is_delete = 1
     * 
     * @param string $conversationId 会话ID
     * @return array 返回数据数组
     */
    public function delete(string $conversationId)
    {
        try {
            // 查找会话
            $conversation = Conversation::query()
                ->withoutGlobalScopes()
                ->where('conversation_id', $conversationId)
                ->first();

            if (!$conversation) {
                throw new FailedException('会话不存在', 10005);
            }

            // 软删除：设置 is_delete = 1
            $conversation->is_delete = 1;
            $conversation->save();

            \Log::info('删除会话成功', [
                'conversation_id' => $conversationId,
            ]);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'message' => '删除成功'
            ];

        } catch (\Exception $e) {
            \Log::error('删除会话失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 获取统计信息
     * 
     * @return array 返回数据数组
     */
    public function statistics()
    {
        try {
            $conversationStats = Conversation::getStatistics();
            $messageStats = WeChatMessage::getMessageStatistics();

            $result = array_merge($conversationStats, $messageStats);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return $result;

        } catch (\Exception $e) {
            \Log::error('获取统计信息失败', [
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 获取会话成员列表（包含昵称和责任人信息）
     * 
     * @param string $conversationId 会话ID
     * @return array 返回数据数组
     */
    public function members(string $conversationId)
    {
        try {
            // 查询会话详情
            $conversation = Conversation::getConversationDetail($conversationId);

            if (!$conversation) {
                throw new FailedException('会话不存在', 10005);
            }

            // 获取成员列表（包含昵称和责任人信息）
            $members = Conversation::getConversationMembers($conversationId);

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'members' => $members,
                'total' => count($members),
                'responsible_user_id' => $conversation->responsible_user_id // 返回当前责任人ID
            ];

        } catch (\Exception $e) {
            \Log::error('获取会话成员列表失败', [
                'conversation_id' => $conversationId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 设置会话负责人
     * 一个会话只能有一个负责人，设置新的负责人时会自动清除之前的负责人
     * 
     * 请求参数：
     * - user_id: 员工账号ID（必填）
     * 
     * @param string $conversationId 会话ID
     * @param Request $request HTTP请求对象
     * @return array 返回数据数组
     */
    public function setResponsibleUser(string $conversationId, Request $request)
    {
        try {
            // 获取请求参数
            $userId = $request->get('user_id');

            if (!$userId) {
                throw new FailedException('请提供员工账号ID', 10005);
            }

            // 设置负责人
            $success = Conversation::setResponsibleUser($conversationId, $userId);

            if (!$success) {
                throw new FailedException('设置负责人失败', 10005);
            }

            // 直接返回数据，JsonResponseMiddleware 会自动包装
            return [
                'message' => '设置负责人成功',
                'responsible_user_id' => $userId
            ];

        } catch (\Exception $e) {
            \Log::error('设置会话负责人失败', [
                'conversation_id' => $conversationId,
                'user_id' => $request->get('user_id'),
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException($e->getMessage(), 10005);
        }
    }

    /**
     * 重新下载消息的资源文件
     * 
     * POST /api/conversation/messages/{messageId}/redownload-media
     * 
     * @param int $messageId 消息ID
     * @return array 返回数据数组
     */
    public function redownloadMedia(int $messageId)
    {
        try {
            // 查找消息
            $message = WeChatMessage::withoutGlobalScopes()
                ->where('id', $messageId)
                ->first();

            if (!$message) {
                throw new FailedException('消息不存在', 10005);
            }

            // 检查消息类型是否为媒体类型
            $mediaTypes = ['image', 'voice', 'video', 'file'];
            if (!in_array($message->msgtype, $mediaTypes)) {
                throw new FailedException('该消息不是媒体类型，无需下载', 10005);
            }

            // 解析消息内容获取 sdkfileid
            $content = $message->content;
            if (is_string($content)) {
                $content = json_decode($content, true);
            }

            $msgType = $message->msgtype;
            $sdkFileId = $content[$msgType]['sdkfileid'] ?? null;
            $md5sum = $content[$msgType]['md5sum'] ?? null;

            if (!$sdkFileId) {
                throw new FailedException('消息中缺少 sdkfileid', 10005);
            }

            // 调用 collect_server 下载媒体文件
            $collectServerUrl = env('COLLECT_SERVER_URL', 'http://localhost:7070');
            
            $response = \Illuminate\Support\Facades\Http::timeout(120)->post(
                $collectServerUrl . '/chatrecord/download-media',
                [
                    'sdkfileid' => $sdkFileId,
                    'md5sum' => $md5sum,
                    'fileType' => $msgType,
                    'fileExtension' => $this->getFileExtension($msgType, $content[$msgType] ?? []),
                ]
            );

            if (!$response->successful()) {
                throw new FailedException('调用下载服务失败: ' . $response->body(), 10005);
            }

            $result = $response->json();
            
            if (!isset($result['success']) || !$result['success']) {
                throw new FailedException('下载失败: ' . ($result['message'] ?? '未知错误'), 10005);
            }

            // 更新消息的 resource_id
            if (isset($result['resourceId']) && $result['resourceId']) {
                $message->resource_id = $result['resourceId'];
                $message->save();
            }

            return [
                'success' => true,
                'message' => '下载任务已提交，请稍后刷新页面查看',
                'resourceId' => $result['resourceId'] ?? null,
                'url' => $result['url'] ?? null,
            ];

        } catch (\Exception $e) {
            \Log::error('重新下载媒体文件失败', [
                'message_id' => $messageId,
                'error' => $e->getMessage(),
                'trace' => $e->getTraceAsString(),
            ]);

            throw new FailedException('重新下载失败: ' . $e->getMessage(), 10005);
        }
    }

    /**
     * 获取文件扩展名
     * 
     * @param string $msgType 消息类型
     * @param array $mediaContent 媒体内容
     * @return string 文件扩展名
     */
    private function getFileExtension(string $msgType, array $mediaContent): string
    {
        // 尝试从 filename 中提取扩展名
        if (isset($mediaContent['filename']) && !empty($mediaContent['filename'])) {
            $filename = $mediaContent['filename'];
            if (strpos($filename, '.') !== false) {
                return strtolower(substr($filename, strrpos($filename, '.') + 1));
            }
        }

        // 根据消息类型返回默认扩展名
        $extensionMap = [
            'image' => 'jpg',
            'voice' => 'amr',
            'video' => 'mp4',
            'file' => '',
        ];

        return $extensionMap[$msgType] ?? '';
    }
}

