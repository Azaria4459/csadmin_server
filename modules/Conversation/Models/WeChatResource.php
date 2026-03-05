<?php

declare(strict_types=1);

namespace Modules\Conversation\Models;

use Illuminate\Database\Eloquent\Model;

/**
 * 微信媒体资源模型
 * 统一管理所有上传到OSS的媒体资源，避免重复上传
 * 
 * @property int $id 主键ID
 * @property string|null $md5sum 文件MD5值
 * @property string|null $sdkfileid 企业微信SDK文件ID
 * @property string $file_type 文件类型：image/voice/video/file
 * @property int $file_size 文件大小（字节）
 * @property string|null $file_extension 文件扩展名
 * @property string|null $mime_type MIME类型
 * @property string $oss_url OSS访问URL
 * @property string|null $oss_path OSS存储路径
 * @property string|null $oss_bucket OSS Bucket名称
 * @property string|null $local_path 本地路径
 * @property int $download_count 被引用次数
 * @property int $status 状态：0-已删除，1-正常
 * @property string $create_time 创建时间
 * @property string $update_time 更新时间
 */
class WeChatResource extends Model
{
    /**
     * 表名
     *
     * @var string
     */
    protected $table = 'wechat_resource';

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
        'md5sum',
        'sdkfileid',
        'file_type',
        'file_size',
        'file_extension',
        'mime_type',
        'oss_url',
        'oss_path',
        'oss_bucket',
        'local_path',
        'download_count',
        'status',
    ];

    /**
     * 需要进行类型转换的属性
     *
     * @var array
     */
    protected $casts = [
        'id' => 'integer',
        'file_size' => 'integer',
        'download_count' => 'integer',
        'status' => 'integer',
    ];

    /**
     * 状态常量
     */
    const STATUS_DELETED = 0;  // 已删除
    const STATUS_NORMAL = 1;   // 正常

    /**
     * 根据 MD5 或 sdkfileid 查找资源
     * 避免重复上传相同的文件
     * 优先使用 md5sum（有唯一索引，查询快），sdkfileid 作为辅助
     *
     * @param string|null $md5sum 文件MD5值
     * @param string|null $sdkfileid SDK文件ID
     * @return WeChatResource|null
     */
    public static function findByIdentifier(?string $md5sum, ?string $sdkfileid): ?WeChatResource
    {
        // 优先使用 md5sum 查找（有唯一索引，更快更准确）
        if ($md5sum) {
            $resource = self::where('status', self::STATUS_NORMAL)
                           ->where('md5sum', $md5sum)
                           ->first();
            if ($resource) {
                return $resource;
            }
        }

        // md5sum 未找到，尝试使用 sdkfileid 查找
        // 注意：sdkfileid 是 TEXT 类型，没有唯一索引，查询较慢
        if ($sdkfileid) {
            $resource = self::where('status', self::STATUS_NORMAL)
                           ->where('sdkfileid', $sdkfileid)
                           ->first();
            if ($resource) {
                return $resource;
            }
        }

        return null;
    }

    /**
     * 创建或获取资源
     * 如果资源已存在（通过md5sum或sdkfileid判断），则返回现有资源并增加引用计数
     * 如果资源不存在，则创建新资源
     *
     * @param array $data 资源数据
     * @return WeChatResource
     */
    public static function createOrGet(array $data): WeChatResource
    {
        $md5sum = $data['md5sum'] ?? null;
        $sdkfileid = $data['sdkfileid'] ?? null;

        // 查找现有资源
        $resource = self::findByIdentifier($md5sum, $sdkfileid);

        if ($resource) {
            // 资源已存在，增加引用计数
            $resource->incrementDownloadCount();
            return $resource;
        }

        // 创建新资源
        $resource = self::create([
            'md5sum' => $md5sum,
            'sdkfileid' => $sdkfileid,
            'file_type' => $data['file_type'] ?? 'file',
            'file_size' => $data['file_size'] ?? 0,
            'file_extension' => $data['file_extension'] ?? null,
            'mime_type' => $data['mime_type'] ?? null,
            'oss_url' => $data['oss_url'],
            'oss_path' => $data['oss_path'] ?? null,
            'oss_bucket' => $data['oss_bucket'] ?? null,
            'local_path' => $data['local_path'] ?? null,
            'download_count' => 1,
            'status' => self::STATUS_NORMAL,
        ]);

        return $resource;
    }

    /**
     * 增加引用计数
     *
     * @return bool
     */
    public function incrementDownloadCount(): bool
    {
        $this->download_count++;
        return $this->save();
    }

    /**
     * 减少引用计数
     *
     * @return bool
     */
    public function decrementDownloadCount(): bool
    {
        if ($this->download_count > 0) {
            $this->download_count--;
            return $this->save();
        }
        return false;
    }

    /**
     * 软删除资源
     *
     * @return bool
     */
    public function softDelete(): bool
    {
        $this->status = self::STATUS_DELETED;
        return $this->save();
    }

    /**
     * 恢复资源
     *
     * @return bool
     */
    public function restore(): bool
    {
        $this->status = self::STATUS_NORMAL;
        return $this->save();
    }

    /**
     * 判断是否正常状态
     *
     * @return bool
     */
    public function isNormal(): bool
    {
        return $this->status === self::STATUS_NORMAL;
    }

    /**
     * 获取完整的访问URL
     * 如果oss_url已经是完整URL则直接返回，否则拼接
     *
     * @return string
     */
    public function getFullUrl(): string
    {
        return $this->oss_url;
    }
}

