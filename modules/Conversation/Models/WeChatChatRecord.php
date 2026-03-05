<?php

declare(strict_types=1);

namespace Modules\Conversation\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\HasMany;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * 聊天记录模型
 * 用于缓存 chatrecord 类型消息的详细内容
 * 
 * @property int $id 主键ID
 * @property int $message_id 关联的消息ID
 * @property string $msgid 消息ID
 * @property string $conversation_id 会话ID
 * @property string $from_user 发送者账号
 * @property int $item_count 聊天记录条目数量
 * @property int $download_status 下载状态：0-未下载，1-下载中，2-已完成，3-失败
 * @property string|null $download_time 下载完成时间
 * @property string|null $error_message 错误信息
 * @property string $create_time 创建时间
 * @property string $update_time 更新时间
 */
class WeChatChatRecord extends Model
{
    /**
     * 表名
     *
     * @var string
     */
    protected $table = 'wechat_chatrecord';

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
     * 可批量赋值的属性
     *
     * @var array
     */
    protected $fillable = [
        'message_id',
        'msgid',
        'conversation_id',
        'from_user',
        'item_count',
        'download_status',
        'download_time',
        'error_message',
    ];

    /**
     * 需要进行类型转换的属性
     *
     * @var array
     */
    protected $casts = [
        'id' => 'integer',
        'message_id' => 'integer',
        'item_count' => 'integer',
        'download_status' => 'integer',
    ];

    /**
     * 下载状态常量
     */
    const STATUS_PENDING = 0;      // 未下载
    const STATUS_DOWNLOADING = 1;  // 下载中
    const STATUS_COMPLETED = 2;    // 已完成
    const STATUS_FAILED = 3;       // 失败

    /**
     * 获取聊天记录的条目列表
     * 定义一对多关系
     *
     * @return HasMany
     */
    public function items(): HasMany
    {
        return $this->hasMany(WeChatChatRecordItem::class, 'chatrecord_id', 'id')
                    ->orderBy('item_index');
    }

    /**
     * 获取关联的消息
     * 定义多对一关系
     *
     * @return BelongsTo
     */
    public function message(): BelongsTo
    {
        return $this->belongsTo(WeChatMessage::class, 'message_id', 'id');
    }

    /**
     * 根据消息ID获取或创建聊天记录
     *
     * @param int $messageId 消息ID
     * @param string $msgid 消息msgid
     * @param string $conversationId 会话ID
     * @param string $fromUser 发送者
     * @return WeChatChatRecord
     */
    public static function getOrCreate(int $messageId, string $msgid, string $conversationId, string $fromUser): WeChatChatRecord
    {
        return self::firstOrCreate(
            ['msgid' => $msgid],
            [
                'message_id' => $messageId,
                'conversation_id' => $conversationId,
                'from_user' => $fromUser,
                'item_count' => 0,
                'download_status' => self::STATUS_PENDING,
            ]
        );
    }

    /**
     * 标记为下载中
     *
     * @return bool
     */
    public function markAsDownloading(): bool
    {
        $this->download_status = self::STATUS_DOWNLOADING;
        return $this->save();
    }

    /**
     * 标记为下载完成
     *
     * @param int $itemCount 条目数量
     * @return bool
     */
    public function markAsCompleted(int $itemCount): bool
    {
        $this->download_status = self::STATUS_COMPLETED;
        $this->item_count = $itemCount;
        $this->download_time = date('Y-m-d H:i:s');
        $this->error_message = null;
        return $this->save();
    }

    /**
     * 标记为下载失败
     *
     * @param string $errorMessage 错误信息
     * @return bool
     */
    public function markAsFailed(string $errorMessage): bool
    {
        $this->download_status = self::STATUS_FAILED;
        $this->error_message = $errorMessage;
        return $this->save();
    }

    /**
     * 判断是否已下载完成
     *
     * @return bool
     */
    public function isCompleted(): bool
    {
        return $this->download_status === self::STATUS_COMPLETED;
    }

    /**
     * 判断是否正在下载
     *
     * @return bool
     */
    public function isDownloading(): bool
    {
        return $this->download_status === self::STATUS_DOWNLOADING;
    }
}

