<?php

declare(strict_types=1);

namespace Modules\Conversation\Models;

use Catch\Base\CatchModel as Model;

/**
 * 责任人统计数据模型
 * 对应 responsible_user_statistics 表
 * 
 * @property int $id 自增主键
 * @property int $responsible_user_id 责任人ID（关联wechat_member.id）
 * @property string $conversation_id 会话ID
 * @property string|null $conversation_name 会话名称（群名称）
 * @property string|null $conversation_remark_name 会话备注名称
 * @property string $conversation_type 会话类型：single/group
 * @property int|null $first_response_time 首次响应用户发言时间（秒）
 * @property float|null $avg_response_time 平均响应用户发言时间（秒）
 * @property int $response_count 响应次数（用于计算平均值）
 * @property int $over_10min_count 超过10分钟未响应用户发言次数
 * @property int $over_20min_count 超过20分钟未响应用户发言次数
     * @property int $over_30min_count 超过30分钟未响应用户发言次数
     * @property int $total_user_messages 用户发言总数
     * @property int|null $last_message_time 最后一条消息时间戳（毫秒）
     * @property string $update_time 统计数据更新时间
     * @property string $create_time 记录创建时间
     */
class ResponsibleUserStatistics extends Model
{
    /**
     * 表名
     *
     * @var string
     */
    protected $table = 'responsible_user_statistics';

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
        'responsible_user_id',
        'conversation_id',
        'conversation_name',
        'conversation_remark_name',
        'conversation_type',
        'first_response_time',
        'avg_response_time',
        'response_count',
        'over_10min_count',
        'over_20min_count',
        'over_30min_count',
        'total_user_messages',
        'last_message_time',
    ];

    /**
     * 需要进行类型转换的属性
     *
     * @var array
     */
    protected $casts = [
        'id' => 'integer',
        'responsible_user_id' => 'integer',
        'first_response_time' => 'integer',
        'avg_response_time' => 'decimal:2',
        'response_count' => 'integer',
        'over_10min_count' => 'integer',
        'over_20min_count' => 'integer',
        'over_30min_count' => 'integer',
        'total_user_messages' => 'integer',
        'last_message_time' => 'integer',
    ];
}

