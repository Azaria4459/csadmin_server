<?php

use Illuminate\Support\Facades\Route;
use Modules\Cms\Http\Controllers\CategoryController;
use Modules\Cms\Http\Controllers\PostController;
use Modules\Cms\Http\Controllers\TagController;
use Modules\Cms\Http\Controllers\SettingController;
use Modules\Cms\Http\Controllers\ResourceController;
use Modules\Cms\Http\Controllers\MessageController;

Route::prefix('cms')->group(function(){

	Route::apiResource('category', CategoryController::class);

	Route::apiResource('post', PostController::class);
    Route::put('post/enable/{id}', [PostController::class, 'enable']);

	Route::apiResource('tag', TagController::class);

    Route::post('setting', [SettingController::class, 'store']);
    Route::get('setting/{key?}', [SettingController::class, 'index']);


	Route::apiResource('resource', ResourceController::class);
    Route::put('resource/enable/{id}', [ResourceController::class, 'enable']);

    Route::get('message/load', [MessageController::class, 'load'])
        ->withoutMiddleware([\Catch\Middleware\AuthMiddleware::class]);

    // 企业微信回调接口（支持GET验证和POST接收事件）
    Route::match(['get', 'post'], 'message/notify', [MessageController::class, 'notify'])
        ->withoutMiddleware([\Catch\Middleware\AuthMiddleware::class]);

    // 测试接口：调用飞书 getUserByDepartment（无需登录）
    Route::get('message/test', [MessageController::class, 'test'])
        ->withoutMiddleware([\Catch\Middleware\AuthMiddleware::class]);

    // 测试接口1：调用飞书获取子部门列表（无需登录）
    Route::get('message/test1', [MessageController::class, 'test1'])
        ->withoutMiddleware([\Catch\Middleware\AuthMiddleware::class]);

    // 测试接口2：遍历所有子部门查找符合条件的员工（无需登录）
    Route::get('message/test2', [MessageController::class, 'test2'])
        ->withoutMiddleware([\Catch\Middleware\AuthMiddleware::class]);

    // 绑定资源文件到聊天记录（无需登录，支持GET访问）
    Route::get('message/bind-resources', [MessageController::class, 'bindResources'])
        ->withoutMiddleware([\Catch\Middleware\AuthMiddleware::class]);

    //next
});



