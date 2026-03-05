<?php

namespace Modules\User\Models;

use Catch\Base\CatchModel as Model;
use Catch\Enums\Status;
use Illuminate\Contracts\Auth\Authenticatable as AuthenticatableContract;
use Illuminate\Database\Eloquent\Casts\Attribute;
use Laravel\Sanctum\HasApiTokens;
use Modules\User\Models\Traits\UserRelations;
use Illuminate\Auth\Authenticatable;

/**
 * @property int $id
 * @property string $username
 * @property string $email
 * @property string $avatar
 * @property string $password
 * @property int $creator_id
 * @property int $status
 * @property string $login_ip
 * @property int $login_at
 * @property int $created_at
 * @property int $updated_at
 * @property string $remember_token
 */
class User extends Model implements AuthenticatableContract
{
    use Authenticatable, UserRelations, HasApiTokens;

    protected $fillable = [
        'id', 'username', 'email', 'avatar', 'password', 'remember_token', 'creator_id', 'status', 'department_id', 'managed_employees', 'login_ip', 'login_at', 'created_at', 'updated_at', 'deleted_at'
    ];

    protected array $defaultHidden = ['password', 'remember_token'];

    /**
     * 类型转换
     * 确保 JSON 字段正确处理
     *
     * @var array
     */
    protected $casts = [
        'managed_employees' => 'json',  // 自动转换为 JSON，空值会转为 NULL
    ];

    /**
     * @var array|string[]
     */
    public array $searchable = [
        'username' => 'like',
        'email' => 'like',
        'status' => '=',
    ];

    /**
     * @var string
     */
    protected $table = 'users';

    protected array $fields = ['id', 'username', 'email', 'avatar',  'creator_id', 'status', 'department_id', 'created_at'];

    /**
     * @var array|string[]
     */
    protected array $form = ['username', 'email', 'password', 'department_id'];

    /**
     * @var array|string[]
     */
    protected array $formRelations = ['roles', 'jobs'];

    /**
     * password
     *
     * @return Attribute
     */
    protected function password(): Attribute
    {
        return new Attribute(
            // get: fn($value) => '',
            set: fn ($value) => bcrypt($value),
        );
    }

    protected function DepartmentId(): Attribute
    {
        return new Attribute(
            get: fn($value) => $value ? : null,
            set: fn($value) => $value ? : 0
        );
    }

    /**
     * avatar 属性访问器
     * 处理空字符串，转换为 NULL
     *
     * @return Attribute
     */
    protected function avatar(): Attribute
    {
        return new Attribute(
            set: fn($value) => $value === '' ? null : $value
        );
    }

    /**
     * 判断是否为超级管理员
     * 
     * 满足以下任一条件即为超级管理员：
     * 1. 用户ID等于配置的super_admin
     * 2. 用户拥有roles表中ID=1的角色
     *
     * @return bool 是否为超级管理员
     */
    public function isSuperAdmin(): bool
    {
        // 检查用户ID是否等于配置的super_admin
        if ($this->{$this->primaryKey} == config('catch.super_admin')) {
            return true;
        }
        
        // 检查用户是否拥有roles表中ID=1的角色
        if ($this->relationLoaded('roles')) {
            // 如果角色关系已加载，直接检查
            return $this->roles->pluck('id')->contains(1);
        } else {
            // 如果角色关系未加载，查询数据库
            return $this->roles()->where('roles.id', 1)->exists();
        }
    }

    /**
     * update
     * @param $id
     * @param array $data
     * @return mixed
     */
    public function updateBy($id, array $data): mixed
    {
        if (empty($data['password'])) {
            unset($data['password']);
        }

        return parent::updateBy($id, $data);
    }

    public function isDisabled(): bool
    {

        return $this->status == Status::Disable->value;
    }

    /**
     * 获取可管理的员工账号列表
     * 
     * @return array|null 员工账号数组，null表示可以看到所有员工
     */
    public function getManagedEmployees(): ?array
    {
        if ($this->isSuperAdmin()) {
            return null; // 超级管理员可以看到所有员工
        }

        if (empty($this->managed_employees)) {
            return []; // 空数组表示不能看到任何员工
        }

        $employees = is_string($this->managed_employees) 
            ? json_decode($this->managed_employees, true) 
            : $this->managed_employees;

        return is_array($employees) ? $employees : [];
    }

    /**
     * 设置可管理的员工账号列表
     * 
     * @param array|null $employees 员工账号数组，null表示可以看到所有员工
     * @return bool 是否设置成功
     */
    public function setManagedEmployees(?array $employees): bool
    {
        if ($this->isSuperAdmin()) {
            // 超级管理员不需要设置
            return false;
        }

        $this->managed_employees = $employees;
        return $this->save();
    }

    /**
     * 检查是否可以管理指定员工
     * 
     * @param string $accountName 员工账号名称
     * @return bool 是否可以管理
     */
    public function canManageEmployee(string $accountName): bool
    {
        if ($this->isSuperAdmin()) {
            return true; // 超级管理员可以管理所有员工
        }

        $managedEmployees = $this->getManagedEmployees();
        
        if ($managedEmployees === null) {
            return true; // null表示可以管理所有员工
        }

        if (empty($managedEmployees)) {
            return false; // 空数组表示不能管理任何员工
        }

        return in_array($accountName, $managedEmployees);
    }
}
