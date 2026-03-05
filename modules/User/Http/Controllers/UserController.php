<?php

namespace Modules\User\Http\Controllers;

use Catch\Base\CatchController as Controller;
use Catch\Exceptions\FailedException;
use Catch\Support\Module\ModuleRepository;
use Illuminate\Contracts\Auth\Authenticatable;
use Illuminate\Contracts\Pagination\LengthAwarePaginator;
use Modules\Permissions\Models\Departments;
use Modules\User\Models\LogLogin;
use Modules\User\Models\LogOperate;
use Modules\User\Models\User;
use Psr\Container\ContainerExceptionInterface;
use Psr\Container\NotFoundExceptionInterface;
use Modules\User\Http\Requests\UserRequest;
use Illuminate\Http\Request;

class UserController extends Controller
{
    public function __construct(
        protected readonly User $user
    ) {
    }

    /**
     * get list
     *
     * @return mixed
     */
    public function index()
    {
        return $this->user->setBeforeGetList(function ($query){
            if (! $this->getLoginUser()->isSuperAdmin()) {
                $query = $query->where('id', '<>', config('catch.super_admin'));
            }

            if (\request()->has('department_id')) {
                $departmentId = \request()->get('department_id');
                $followDepartmentIds = app(Departments::class)->findFollowDepartments(\request()->get('department_id'));
                $followDepartmentIds[] = $departmentId;
                $query = $query->whereIn('department_id', $followDepartmentIds);
            }

            return $query;
        })->getList();
    }

    /**
     * store
     *
     * @param UserRequest $request
     * @return false|mixed
     */
    public function store(UserRequest $request)
    {
        return $this->user->storeBy($request->all());
    }

    /**
     * show
     *
     * @param $id
     * @return mixed
     */
    /**
     * 获取用户的可管理员工列表
     * 
     * 权限要求：拥有"权限管理"相关权限的用户可以访问
     *
     * @param int $id 用户ID
     * @return array
     */
    public function getManagedEmployees($id)
    {
        $currentUser = $this->getLoginUser();
        
        // 权限检查：只有超级管理员或拥有权限管理权限的用户可以访问
        if (!$currentUser->isSuperAdmin()) {
            // 检查是否有权限管理相关权限
            // 可以通过角色或直接权限来判断
            $hasPermissionManagePermission = $this->checkPermissionManageAccess($currentUser);
            
            if (!$hasPermissionManagePermission) {
                throw new FailedException('无权限访问此接口', 10003);
            }
        }
        
        $user = $this->user->firstBy($id);
        
        if (!$user) {
            throw new FailedException('用户不存在', 10005);
        }

        $managedEmployees = $user->getManagedEmployees();

        return [
            'user_id' => $user->id,
            'username' => $user->username,
            'is_super_admin' => $user->isSuperAdmin(),
            'managed_employees' => $managedEmployees,
        ];
    }
    
    /**
     * 检查用户是否有权限管理访问权限
     * 
     * @param User $user 当前登录用户
     * @return bool 是否有权限
     */
    private function checkPermissionManageAccess($user): bool
    {
        // 加载用户权限
        $user->withPermissions();
        
        // 获取用户的所有权限
        $permissions = $user->getAttribute('permissions');
        
        if (!$permissions || $permissions->isEmpty()) {
            return false;
        }
        
        // 检查是否有用户管理或权限管理相关的权限标识
        foreach ($permissions as $permission) {
            $mark = $permission->permission_mark ?? '';
            
            // 如果有这些权限标识之一，认为有权限管理权限
            if (
                str_contains($mark, 'User@') ||           // 用户管理权限
                str_contains($mark, 'Permissions@') ||    // 权限管理权限
                str_contains($mark, 'Roles@')             // 角色管理权限
            ) {
                return true;
            }
        }
        
        return false;
    }

    /**
     * 设置用户的可管理员工列表
     *
     * @param Request $request
     * @param int $id 用户ID
     * @return array
     */
    public function setManagedEmployees(Request $request, $id)
    {
        // 只有超级管理员可以设置
        if (!$this->getLoginUser()->isSuperAdmin()) {
            throw new FailedException('只有超级管理员可以设置用户权限', 10003);
        }

        $user = $this->user->firstBy($id);
        
        if (!$user) {
            throw new FailedException('用户不存在', 10005);
        }

        if ($user->isSuperAdmin()) {
            throw new FailedException('不能设置超级管理员的权限', 10005);
        }

        // 获取员工列表
        $employees = $request->input('managed_employees', []);
        
        // 验证是否为数组
        if (!is_array($employees)) {
            throw new FailedException('员工列表必须是数组格式', 10005);
        }

        // 设置可管理的员工
        $user->managed_employees = !empty($employees) ? $employees : null;
        $user->save();

        return [
            'message' => '设置成功',
            'user_id' => $user->id,
            'username' => $user->username,
            'managed_employees' => $user->getManagedEmployees(),
        ];
    }

    public function show($id)
    {
        $user = $this->user->firstBy($id)->makeHidden('password');

        if (app(ModuleRepository::class)->enabled('permissions')) {
            $user->setRelations([
                'roles' => $user->roles->pluck('id'),

                'jobs' => $user->jobs->pluck('id')
            ]);
        }

        return $user;
    }

    /**
     * update
     *
     * @param $id
     * @param UserRequest $request
     * @return mixed
     */
    public function update($id, UserRequest $request)
    {
        return $this->user->updateBy($id, $request->all());
    }

    /**
     * destroy
     *
     * @param $id
     * @return bool|null
     */
    public function destroy($id)
    {
        if ($this->user->deleteBy($id)) {
            // 撤销用户的所有令牌
            $this->user->tokens()->delete();
        }

        return true;
    }

    /**
     * enable
     *
     * @param $id
     * @return bool
     */
    public function enable($id)
    {
        return $this->user->toggleBy($id);
    }

    /**
     *  online user
     *
     * @return Authenticatable
     */
    public function online(Request $request)
    {
        /* @var User $user */
        $user = $this->getLoginUser()->withPermissions();

        if ($request->isMethod('post')) {
            return $user->updateBy($user->id, $request->all());
        }

        return $user;
    }


    /**
     * login log
     * @param LogLogin $logLogin
     * @return LengthAwarePaginator
     * @throws ContainerExceptionInterface
     * @throws NotFoundExceptionInterface
     */
    public function loginLog(LogLogin $logLogin)
    {
        $user = $this->getLoginUser();

        return $logLogin->getUserLogBy($user->isSuperAdmin() ? null : $user->email);
    }

    public function operateLog(LogOperate $logOperate, Request $request)
    {
        $scope = $request->get('scope', 'self');

        return $logOperate->setBeforeGetList(function ($builder) use ($scope){
            if ($scope == 'self') {
                return $builder->where('creator_id', $this->getLoginUserId());
            }
            return $builder;
        })->getList();
    }

    /**
     * @return void
     */
    public function export()
    {
        return User::query()
                    ->select('id', 'username', 'email', 'created_at')
                    ->without('roles')
                    ->get()
                    ->download(['id', '昵称', '邮箱', '创建时间']);
    }
}
