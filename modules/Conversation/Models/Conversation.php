<?php

declare(strict_types=1);

namespace Modules\Conversation\Models;

use Catch\Base\CatchModel as Model;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Support\Facades\Request;

/**
 * 会话模型
 * 对应 wechat_conversation 表
 * 
 * @property int $id 自增主键
 * @property string $conversation_id 会话ID
 * @property string $conversation_type 会话类型：single/group
 * @property string|null $name 会话名称（群聊名称）
 * @property string|null $remark_name 备注名称
 * @property string|null $roomid 群聊ID
 * @property int $message_count 消息总数
 * @property int|null $first_message_time 第一条消息时间戳（毫秒）
 * @property int|null $last_message_time 最后一条消息时间戳（毫秒）
 * @property string|null $last_message_id 最后一条消息ID
 * @property string|null $last_message_type 最后一条消息类型
 * @property string|null $last_message_content 最后一条消息内容摘要
 * @property string|null $last_message_sender 最后一条消息发送者
 * @property array|null $participants 参与人员列表
 * @property int $participant_count 参与人数
 * @property string $create_time 记录创建时间
 * @property string $update_time 记录更新时间
 * @property int $is_delete 是否删除：0-未删除，1-已删除
 */
class Conversation extends Model
{
    /**
     * 表名
     *
     * @var string
     */
    protected $table = 'wechat_conversation';

    /**
     * 主键
     *
     * @var string
     */
    protected $primaryKey = 'id';

    /**
     * 不使用Laravel的时间戳管理
     *
     * @var bool
     */
    public $timestamps = false;

    /**
     * 禁用软删除
     * wechat_conversation 表没有 deleted_at 字段
     *
     * @return string|null
     */
    public function getDeletedAtColumn(): ?string
    {
        return null;
    }

    /**
     * 可批量赋值的属性
     *
     * @var array
     */
    protected $fillable = [
        'conversation_id',
        'conversation_type',
        'name',
        'remark_name',
        'responsible_user_id',
        'roomid',
        'message_count',
        'first_message_time',
        'last_message_time',
        'last_message_id',
        'last_message_type',
        'last_message_content',
        'last_message_sender',
        'participants',
        'participant_count',
        'is_delete',
    ];

    /**
     * 需要进行类型转换的属性
     *
     * @var array
     */
    protected $casts = [
        'id' => 'integer',
        'message_count' => 'integer',
        'first_message_time' => 'integer',
        'last_message_time' => 'integer',
        'participant_count' => 'integer',
        'participants' => 'array',
        'is_delete' => 'integer',
        'responsible_user_id' => 'integer',
    ];

    /**
     * 获取会话的聊天消息
     * 定义一对多关系
     *
     * @return HasMany
     */
    public function messages(): HasMany
    {
        return $this->hasMany(WeChatMessage::class, 'conversation_id', 'conversation_id')
                    ->orderByDesc('msgtime');
    }

    /**
     * 获取会话列表
     * 支持分页和搜索，关联查询 wechat_member 表获取最后发送者的昵称、备注名称等信息
     *
     * @param int $page 页码
     * @param int $pageSize 每页数量
     * @param string|null $keyword 搜索关键词（搜索参与人员）
     * @param string|null $type 会话类型过滤：single/group
     * @param string|null $groupName 群名称搜索关键词（搜索群名称或备注名称）
     * @param array|null $managedEmployees 可管理的员工账号列表，null表示可以看到所有
     * @param string|null $messageContent 聊天记录搜索关键词（搜索消息内容）
     * @param string|null $conversationId 会话ID搜索关键词（精确匹配或模糊匹配）
     * @return array 返回包含列表和分页信息的数组，每条会话包含 last_sender_nick_name, last_sender_remark_name 等字段
     */
    public static function getConversationList(int $page = 1, int $pageSize = 20, ?string $keyword = null, ?string $type = null, ?string $groupName = null, ?array $managedEmployees = null, ?string $messageContent = null, ?string $conversationId = null): array
    {
        $query = self::query()
            ->withoutGlobalScopes() // 移除所有全局作用域（包括软删除）
            ->select([
                'wechat_conversation.id',
                'wechat_conversation.conversation_id',
                'wechat_conversation.conversation_type',
                'wechat_conversation.name',
                'wechat_conversation.remark_name',
                'wechat_conversation.roomid',
                'wechat_conversation.message_count',
                'wechat_conversation.first_message_time',
                'wechat_conversation.last_message_time',
                'wechat_conversation.last_message_id',
                'wechat_conversation.last_message_type',
                'wechat_conversation.last_message_content',
                'wechat_conversation.last_message_sender',
                'wechat_conversation.participants',
                'wechat_conversation.participant_count',
                'wechat_conversation.create_time',
                'wechat_conversation.update_time',
                'wechat_conversation.is_delete',
                'wechat_member.nick_name as last_sender_nick_name',
                'wechat_member.remark_name as last_sender_remark_name',
                'wechat_member.type as last_sender_type'
            ])
            ->leftJoin('wechat_member', function($join) {
                $join->on('wechat_conversation.last_message_sender', '=', \DB::raw('wechat_member.account_name COLLATE utf8mb4_unicode_ci'));
            })
            ->where('wechat_conversation.is_delete', 0) // 只查询未删除的会话
            ->orderByDesc('wechat_conversation.last_message_time');

        // 权限过滤：非超级管理员只能看到自己管理的员工相关的会话
        if ($managedEmployees !== null && !empty($managedEmployees)) {
            $query->where(function($q) use ($managedEmployees) {
                foreach ($managedEmployees as $accountName) {
                    $q->orWhereRaw('JSON_CONTAINS(wechat_conversation.participants, ?)', [json_encode($accountName)]);
                }
            });
        }

        // 会话类型过滤
        if ($type && in_array($type, ['single', 'group'])) {
            $query->where('wechat_conversation.conversation_type', $type);
        }

        // 搜索参与人员（使用JSON_CONTAINS）
        if ($keyword) {
            $query->whereRaw('JSON_CONTAINS(wechat_conversation.participants, ?)', [json_encode($keyword)]);
        }

        // 搜索群名称或备注名称
        if ($groupName) {
            $query->where(function($q) use ($groupName) {
                $q->where('wechat_conversation.name', 'like', '%' . $groupName . '%')
                  ->orWhere('wechat_conversation.remark_name', 'like', '%' . $groupName . '%');
            });
        }

        // 通过聊天记录搜索会话
        if ($messageContent) {
            // 先在 wechat_message 表中搜索包含关键词的消息，获取对应的 conversation_id
            $conversationIds = \DB::table('wechat_message')
                ->select('conversation_id')
                ->where(function($q) use ($messageContent) {
                    // 搜索文本消息内容
                    $q->whereRaw("JSON_EXTRACT(content, '$.text.content') LIKE ?", ['%' . $messageContent . '%'])
                      // 或搜索文件名
                      ->orWhereRaw("JSON_EXTRACT(content, '$.file.filename') LIKE ?", ['%' . $messageContent . '%']);
                })
                ->distinct()
                ->pluck('conversation_id')
                ->toArray();

            // 如果找到了匹配的会话ID，则只查询这些会话；否则返回空结果
            if (!empty($conversationIds)) {
                $query->whereIn('wechat_conversation.conversation_id', $conversationIds);
            } else {
                // 如果没有找到匹配的消息，返回空结果
                $query->whereRaw('1 = 0');
            }
        }

        // 搜索会话ID（支持精确匹配和模糊匹配）
        if ($conversationId) {
            $query->where('wechat_conversation.conversation_id', 'like', '%' . $conversationId . '%');
        }

        // 分页
        $total = $query->count();
        $list = $query->offset(($page - 1) * $pageSize)
                     ->limit($pageSize)
                     ->get()
                     ->toArray();

        return [
            'list' => $list,
            'page' => $page,
            'pageSize' => $pageSize,
            'total' => $total,
        ];
    }

    /**
     * 根据会话ID获取会话详情
     *
     * @param string $conversationId 会话ID
     * @return Conversation|null
     */
    public static function getConversationDetail(string $conversationId): ?Conversation
    {
        return self::query()
            ->withoutGlobalScopes() // 移除所有全局作用域（包括软删除）
            ->where('conversation_id', $conversationId)
            ->first();
    }

    /**
     * 获取会话统计信息
     *
     * @return array
     */
    public static function getStatistics(): array
    {
        $baseQuery = self::query()->withoutGlobalScopes(); // 移除所有全局作用域（包括软删除）
        
        return [
            'total_conversations' => (clone $baseQuery)->count(),
            'single_conversations' => (clone $baseQuery)->where('conversation_type', 'single')->count(),
            'group_conversations' => (clone $baseQuery)->where('conversation_type', 'group')->count(),
        ];
    }

    /**
     * 获取会话成员列表（包含昵称和责任人信息）
     * 从participants字段解析成员，并关联wechat_member表获取昵称和类型
     *
     * @param string $conversationId 会话ID
     * @return array 返回成员数组，每个成员包含：account_name, nick_name, type, is_responsible
     */
    public static function getConversationMembers(string $conversationId): array
    {
        // 获取会话详情
        $conversation = self::getConversationDetail($conversationId);
        
        if (!$conversation || empty($conversation->participants)) {
            return [];
        }

        // 获取参与者账号列表
        $accountNames = $conversation->participants;
        
        if (!is_array($accountNames) || empty($accountNames)) {
            return [];
        }

        // 获取当前责任人ID（wechat_member.id）
        $responsibleUserId = $conversation->responsible_user_id;

        // 查询这些账号的成员信息
        $members = \DB::table('wechat_member')
            ->select(['id', 'account_name', 'nick_name', 'remark_name', 'type'])
            ->whereIn('account_name', $accountNames)
            ->get()
            ->keyBy('account_name')
            ->toArray();

        // 构建结果数组，保持原有顺序
        $result = [];
        foreach ($accountNames as $accountName) {
            $member = [
                'account_name' => $accountName,
                'nick_name' => null,
                'remark_name' => null,
                'type' => null,
                'is_responsible' => false, // 是否为责任人
            ];

            // 如果在wechat_member表中找到了该成员，补充昵称和类型
            if (isset($members[$accountName])) {
                $memberData = $members[$accountName];
                $member['nick_name'] = $memberData->nick_name;
                $member['remark_name'] = $memberData->remark_name;
                $member['type'] = $memberData->type;
                
                // 判断是否为责任人（比较 member_id）
                if ($responsibleUserId && $memberData->id == $responsibleUserId) {
                    $member['is_responsible'] = true;
                }
            }

            $result[] = $member;
        }

        return $result;
    }

    /**
     * 设置会话负责人
     * 一个会话只能有一个负责人，设置新的负责人时会自动清除之前的负责人
     *
     * @param string $conversationId 会话ID
     * @param string $userId 员工账号ID（必须存在于会话的participants中）
     * @return bool 是否设置成功
     * @throws \Exception 如果会话不存在或用户不在参与者列表中
     */
    public static function setResponsibleUser(string $conversationId, string $userId): bool
    {
        // 获取会话详情
        $conversation = self::getConversationDetail($conversationId);
        
        if (!$conversation) {
            throw new \Exception('会话不存在');
        }

        // 验证用户是否在参与者列表中
        $participants = $conversation->participants;
        if (!is_array($participants) || !in_array($userId, $participants)) {
            throw new \Exception('该用户不在会话参与者列表中');
        }

        // 验证用户是否为员工（type = 1），并获取 member_id
        $member = \DB::table('wechat_member')
            ->where('account_name', $userId)
            ->first();
        
        if (!$member) {
            throw new \Exception('该用户不存在于成员表中');
        }

        if ($member->type != 1) {
            throw new \Exception('只能将员工设置为责任人');
        }

        // 更新负责人（使用 member_id 而不是 account_name）
        // 使用 withoutGlobalScopes 避免全局作用域干扰
        $updated = self::query()
            ->withoutGlobalScopes()
            ->where('conversation_id', $conversationId)
            ->where('is_delete', 0) // 只更新未删除的会话
            ->update(['responsible_user_id' => $member->id]);

        return $updated > 0;
    }
}

