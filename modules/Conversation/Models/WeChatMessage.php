<?php

declare(strict_types=1);

namespace Modules\Conversation\Models;

use Catch\Base\CatchModel as Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * 企业微信聊天消息模型
 * 对应 wechat_message 表
 * 
 * @property int $id 自增主键
 * @property string $msgid 企业微信消息ID
 * @property int $seq 顺序号
 * @property int $msgtime 消息时间戳（毫秒）
 * @property string $action 消息动作：send/recall
 * @property string|null $roomid 群聊ID
 * @property string|null $conversation_id 会话ID
 * @property string $conversation_type 会话类型：single/group
 * @property string $from_user 发送者userid
 * @property array|null $to_list 接收人列表
 * @property string $msgtype 消息类型
 * @property array|null $content 消息主要内容（JSON）
 * @property array|null $raw_json 原始微信返回JSON
 * @property string $create_time 记录创建时间
 */
class WeChatMessage extends Model
{
    /**
     * 表名
     *
     * @var string
     */
    protected $table = 'wechat_message';

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
     * wechat_message 表没有 deleted_at 字段
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
        'msgid',
        'seq',
        'msgtime',
        'action',
        'roomid',
        'conversation_id',
        'conversation_type',
        'from_user',
        'to_list',
        'msgtype',
        'content',
        'raw_json',
        'resource_id',
    ];

    /**
     * 需要进行类型转换的属性
     *
     * @var array
     */
    protected $casts = [
        'id' => 'integer',
        'seq' => 'integer',
        'msgtime' => 'integer',
        'to_list' => 'array',
        'content' => 'array',
        'raw_json' => 'array',
    ];

    /**
     * 获取消息所属的会话
     * 定义多对一关系
     *
     * @return BelongsTo
     */
    public function conversation(): BelongsTo
    {
        return $this->belongsTo(Conversation::class, 'conversation_id', 'conversation_id');
    }

    /**
     * 获取关联的媒体资源
     * 定义多对一关系
     *
     * @return BelongsTo
     */
    public function resource(): BelongsTo
    {
        return $this->belongsTo(WeChatResource::class, 'resource_id', 'id');
    }

    /**
     * 根据会话ID获取聊天记录
     * 支持分页、搜索，关联查询 wechat_member 表获取发送者的昵称、备注名称、头像等信息
     *
     * @param string $conversationId 会话ID
     * @param int $page 页码
     * @param int $pageSize 每页数量
     * @param string|null $searchSender 搜索发送者（from_user、昵称或备注名称）
     * @param string|null $searchContent 搜索内容关键词
     * @return array 返回包含列表和分页信息的数组，每条消息包含 sender_nick_name, sender_remark_name, sender_avatar 等字段
     */
    public static function getMessagesByConversation(
        string $conversationId, 
        int $page = 1, 
        int $pageSize = 50,
        ?string $searchSender = null,
        ?string $searchContent = null
    ): array
    {
        $query = self::query()
                    ->withoutGlobalScopes() // 移除所有全局作用域（包括软删除）
                    ->select([
                        'wechat_message.*',
                        'wechat_member.nick_name as sender_nick_name',
                        'wechat_member.remark_name as sender_remark_name',
                        'wechat_member.type as sender_type',
                        'wechat_member.avatar as sender_avatar',
                        'wechat_member.gender as sender_gender',
                        'wechat_resource.oss_url as media_url'
                    ])
                    ->leftJoin('wechat_member', function($join) {
                        $join->on('wechat_message.from_user', '=', \DB::raw('wechat_member.account_name COLLATE utf8mb4_unicode_ci'));
                    })
                    ->leftJoin('wechat_resource', function($join) {
                        $join->on('wechat_message.resource_id', '=', 'wechat_resource.id')
                             ->where('wechat_resource.status', '=', 1);
                    })
                    ->where('wechat_message.conversation_id', $conversationId);

        // 搜索发送者（支持 from_user、nick_name 和 remark_name）
        if ($searchSender) {
            $query->where(function($q) use ($searchSender) {
                $q->where('wechat_message.from_user', 'like', '%' . $searchSender . '%')
                  ->orWhere('wechat_member.nick_name', 'like', '%' . $searchSender . '%')
                  ->orWhere('wechat_member.remark_name', 'like', '%' . $searchSender . '%');
            });
        }

        // 搜索内容（搜索 JSON 字段）
        if ($searchContent) {
            $query->where(function($q) use ($searchContent) {
                // 搜索文本消息内容
                $q->whereRaw("JSON_EXTRACT(content, '$.text.content') LIKE ?", ['%' . $searchContent . '%'])
                  // 或搜索文件名
                  ->orWhereRaw("JSON_EXTRACT(content, '$.file.filename') LIKE ?", ['%' . $searchContent . '%']);
            });
        }

        $query->orderByDesc('wechat_message.msgtime');

        // 统计总数（使用相同的查询条件）
        $countQuery = self::query()
                    ->withoutGlobalScopes()
                    ->where('conversation_id', $conversationId);

        if ($searchSender) {
            $countQuery->leftJoin('wechat_member', function($join) {
                $join->on('wechat_message.from_user', '=', \DB::raw('wechat_member.account_name COLLATE utf8mb4_unicode_ci'));
            })->where(function($q) use ($searchSender) {
                $q->where('wechat_message.from_user', 'like', '%' . $searchSender . '%')
                  ->orWhere('wechat_member.nick_name', 'like', '%' . $searchSender . '%')
                  ->orWhere('wechat_member.remark_name', 'like', '%' . $searchSender . '%');
            });
        }

        if ($searchContent) {
            $countQuery->where(function($q) use ($searchContent) {
                $q->whereRaw("JSON_EXTRACT(content, '$.text.content') LIKE ?", ['%' . $searchContent . '%'])
                  ->orWhereRaw("JSON_EXTRACT(content, '$.file.filename') LIKE ?", ['%' . $searchContent . '%']);
            });
        }

        $total = $countQuery->count();
                    
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
     * 根据消息ID获取消息详情
     *
     * @param string $msgid 消息ID
     * @return WeChatMessage|null
     */
    public static function getMessageDetail(string $msgid): ?WeChatMessage
    {
        return self::query()
            ->withoutGlobalScopes() // 移除所有全局作用域（包括软删除）
            ->where('msgid', $msgid)
            ->first();
    }

    /**
     * 获取消息统计信息
     *
     * @param string|null $conversationId 可选的会话ID
     * @return array
     */
    public static function getMessageStatistics(?string $conversationId = null): array
    {
        $query = self::query()
            ->withoutGlobalScopes(); // 移除所有全局作用域（包括软删除）

        if ($conversationId) {
            $query->where('conversation_id', $conversationId);
        }

        $stats = [
            'total_messages' => $query->count(),
        ];

        // 按消息类型统计
        $typeStats = (clone $query)->selectRaw('msgtype, COUNT(*) as count')
                                   ->groupBy('msgtype')
                                   ->get()
                                   ->pluck('count', 'msgtype')
                                   ->toArray();

        $stats['by_type'] = $typeStats;

        return $stats;
    }
}

