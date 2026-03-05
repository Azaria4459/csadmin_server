<?php

namespace App\Http\Middleware;

use Closure;
use Illuminate\Http\Request;

/**
 * 定义 CURL 常量中间件
 * 
 * 修复某些 PHP 环境（特别是 php-fpm）中 CURL_SSLVERSION_TLSv1_2 等常量未定义的问题
 */
class DefineCurlConstants
{
    /**
     * 处理请求
     *
     * @param  \Illuminate\Http\Request  $request
     * @param  \Closure  $next
     * @return mixed
     */
    public function handle(Request $request, Closure $next)
    {
        // 确保 CURL SSL 版本常量被定义
        $this->ensureCurlSslConstants();
        
        return $next($request);
    }
    
    /**
     * 确保 CURL SSL 相关常量被定义
     * 
     * @return void
     */
    private function ensureCurlSslConstants(): void
    {
        // CURL_SSLVERSION_TLSv1_2 (值: 6)
        if (!defined('CURL_SSLVERSION_TLSv1_2')) {
            define('CURL_SSLVERSION_TLSv1_2', 6);
        }
        
        // CURL_SSLVERSION_TLSv1_3 (值: 7)
        if (!defined('CURL_SSLVERSION_TLSv1_3')) {
            define('CURL_SSLVERSION_TLSv1_3', 7);
        }
    }
}

