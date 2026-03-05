<?php

declare(strict_types=1);

namespace Modules\Conversation\Models;

use Catch\Traits\DB\BaseOperate;
use Catch\Traits\DB\ScopeTrait;
use Catch\Traits\DB\Trans;
use Illuminate\Database\Eloquent\Model;

/**
 * 微信成员模型
 * 对应 wechat_member 表
 * 
 * @property int $id 主键ID
 * @property int $type 类型：1-员工，2-用户
 * @property string $account_name WeChat账号名称
 * @property string|null $nick_name 别名
 * @property string|null $remark_name 备注昵称
 * @property string|null $avatar 头像URL
 * @property int|null $gender 性别：0-未知，1-男性，2-女性
 * @property string $create_time 创建时间
 * @property string|null $update_time 更新时间
 */
class WechatMember extends Model
{
    use BaseOperate, Trans, ScopeTrait;

    /**
     * 表名
     *
     * @var string
     */
    protected $table = 'wechat_member';

    /**
     * 主键
     *
     * @var string
     */
    protected $primaryKey = 'id';

    /**
     * 不使用Laravel的时间戳管理
     * wechat_member 表没有 created_at 和 updated_at 字段
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
        'account_name',
        'nick_name',
        'remark_name',
        'avatar',
        'gender',
        'type',
    ];

    /**
     * 需要进行类型转换的属性
     *
     * @var array
     */
    protected $casts = [
        'id' => 'integer',
        'type' => 'integer',
        'gender' => 'integer',
    ];

    /**
     * 获取成员列表
     * 
     * @param int $page 页码
     * @param int $pageSize 每页数量
     * @param string|null $keyword 搜索关键词（搜索账号名称或别名）
     * @param int|null $type 成员类型过滤：1-员工，2-用户
     * @param array|null $managedEmployees 可管理的员工账号列表，null表示可以看到所有
     * @return array 返回包含列表和分页信息的数组
     */
    public static function getMemberList(int $page = 1, int $pageSize = 20, ?string $keyword = null, ?int $type = null, ?array $managedEmployees = null): array
    {
        // 构建查询
        $query = self::query();

        // 权限过滤：
        // 规则：
        // 1. 如果 managedEmployees = null：超级管理员，可以看到所有成员（员工+用户）
        // 2. 如果 managedEmployees = []：普通用户无员工权限，只能看到所有用户（type=2）
        // 3. 如果 managedEmployees = ['emp1', 'emp2']：可以看到指定的员工 + 所有用户
        if ($managedEmployees !== null) {
            if (empty($managedEmployees)) {
                // 空数组：没有员工权限，只能看到所有用户
                $query->where('type', 2);
            } else {
                // 有指定的员工权限：可以看到指定的员工 + 所有用户
                $query->where(function ($q) use ($managedEmployees) {
                    $q->where(function($subQ) use ($managedEmployees) {
                        // 条件1：指定的员工
                        $subQ->whereIn('account_name', $managedEmployees)
                             ->where('type', 1);
                    })
                    ->orWhere(function($subQ) {
                        // 条件2：所有用户
                        $subQ->where('type', 2);
                    });
                });
            }
        }

        // 关键词搜索（搜索账号名称、别名或备注昵称）
        if ($keyword) {
            $query->where(function ($q) use ($keyword) {
                $q->where('account_name', 'LIKE', "%{$keyword}%")
                  ->orWhere('nick_name', 'LIKE', "%{$keyword}%")
                  ->orWhere('remark_name', 'LIKE', "%{$keyword}%");
            });
        }

        // 类型过滤
        if ($type !== null && in_array($type, [1, 2])) {
            $query->where('type', $type);
        }

        // 获取总数
        $total = $query->count();

        // 分页查询（按创建时间倒序）
        $list = $query->orderBy('create_time', 'DESC')
            ->offset(($page - 1) * $pageSize)
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
     * 根据ID获取成员详情
     * 
     * @param int $id 成员ID
     * @return array|null 成员信息数组，不存在返回null
     */
    public static function getMemberById(int $id): ?array
    {
        $member = self::find($id);
        
        return $member ? $member->toArray() : null;
    }

    /**
     * 更新成员信息
     * 
     * @param int $id 成员ID
     * @param array $data 更新数据
     * @return bool 是否更新成功
     */
    public static function updateMember(int $id, array $data): bool
    {
        $member = self::find($id);
        
        if (!$member) {
            return false;
        }

        // 允许更新 nick_name、remark_name、avatar、gender 和 type
        $allowedFields = ['nick_name', 'remark_name', 'avatar', 'gender', 'type'];
        $updateData = array_intersect_key($data, array_flip($allowedFields));

        if (empty($updateData)) {
            return false;
        }

        return $member->update($updateData);
    }

    /**
     * 获取成员统计信息
     * 
     * @return array 统计信息数组
     */
    public static function getStatistics(): array
    {
        return [
            'total_members' => self::count(),
            'employee_count' => self::where('type', 1)->count(),
            'user_count' => self::where('type', 2)->count(),
        ];
    }

    /**
     * 批量更新成员类型
     * 
     * @param array $ids 成员ID数组
     * @param int $type 目标类型：1-员工，2-用户
     * @return int 影响的行数
     */
    public static function batchUpdateType(array $ids, int $type): int
    {
        if (empty($ids) || !in_array($type, [1, 2])) {
            return 0;
        }

        return self::whereIn('id', $ids)->update(['type' => $type]);
    }
}

