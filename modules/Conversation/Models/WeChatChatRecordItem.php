<?php

declare(strict_types=1);

namespace Modules\Conversation\Models;

use Illuminate\Database\Eloquent\Model;
use Illuminate\Database\Eloquent\Relations\BelongsTo;

/**
 * 聊天记录条目模型
 * 存储聊天记录中的每一条消息
 * 
 * @property int $id 主键ID
 * @property int $chatrecord_id 关联的聊天记录ID
 * @property int $item_index 条目索引
 * @property string $item_type 条目类型
 * @property string|null $content 条目内容（JSON格式）
 * @property int|null $msgtime 消息时间戳
 * @property int $from_chatroom 是否来自群聊
 * @property int $media_status 媒体下载状态
 * @property string|null $media_url 媒体文件URL
 * @property string|null $media_local_path 媒体文件本地路径
 * @property string $create_time 创建时间
 * @property string $update_time 更新时间
 */
class WeChatChatRecordItem extends Model
{
    /**
     * 表名
     *
     * @var string
     */
    protected $table = 'wechat_chatrecord_item';

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
        'chatrecord_id',
        'item_index',
        'item_type',
        'content',
        'msgtime',
        'from_chatroom',
        'media_status',
        'media_url',
        'media_local_path',
        'resource_id',
    ];

    /**
     * 需要进行类型转换的属性
     *
     * @var array
     */
    protected $casts = [
        'id' => 'integer',
        'chatrecord_id' => 'integer',
        'item_index' => 'integer',
        'msgtime' => 'integer',
        'from_chatroom' => 'boolean',
        'media_status' => 'integer',
    ];

    /**
     * 媒体下载状态常量
     */
    const MEDIA_STATUS_NONE = 0;       // 无需下载
    const MEDIA_STATUS_PENDING = 1;    // 待下载
    const MEDIA_STATUS_DOWNLOADED = 2; // 已下载
    const MEDIA_STATUS_FAILED = 3;     // 失败

    /**
     * 获取所属的聊天记录
     * 定义多对一关系
     *
     * @return BelongsTo
     */
    public function chatRecord(): BelongsTo
    {
        return $this->belongsTo(WeChatChatRecord::class, 'chatrecord_id', 'id');
    }

    /**
     * 获取关联的资源
     * 定义多对一关系
     *
     * @return BelongsTo
     */
    public function resource(): BelongsTo
    {
        return $this->belongsTo(WeChatResource::class, 'resource_id', 'id');
    }

    /**
     * 获取解析后的内容
     *
     * @return array|null
     */
    public function getParsedContent(): ?array
    {
        if (!$this->content) {
            return null;
        }

        try {
            return json_decode($this->content, true);
        } catch (\Exception $e) {
            return null;
        }
    }

    /**
     * 判断是否需要下载媒体文件
     *
     * @return bool
     */
    public function needsMediaDownload(): bool
    {
        return in_array($this->item_type, [
            'ChatRecordImage',
            'ChatRecordVoice',
            'ChatRecordVideo',
            'ChatRecordFile',
        ]) && $this->media_status === self::MEDIA_STATUS_PENDING;
    }

    /**
     * 标记媒体为已下载
     *
     * @param string $mediaUrl 媒体URL
     * @param string|null $localPath 本地路径
     * @return bool
     */
    public function markMediaAsDownloaded(string $mediaUrl, ?string $localPath = null): bool
    {
        $this->media_status = self::MEDIA_STATUS_DOWNLOADED;
        $this->media_url = $mediaUrl;
        $this->media_local_path = $localPath;
        return $this->save();
    }

    /**
     * 标记媒体下载失败
     *
     * @return bool
     */
    public function markMediaAsFailed(): bool
    {
        $this->media_status = self::MEDIA_STATUS_FAILED;
        return $this->save();
    }
}

