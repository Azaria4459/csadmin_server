<?php

namespace Modules\Conversation\Providers;

use Catch\CatchAdmin;
use Catch\Providers\CatchModuleServiceProvider;

/**
 * 会话模块服务提供者
 */
class ConversationServiceProvider extends CatchModuleServiceProvider
{
    /**
     * 模块名称
     *
     * @return string
     */
    public function moduleName(): string
    {
        return 'conversation';
    }

    /**
     * 启动服务
     * 
     * @return void
     */
    public function boot(): void
    {
        // 注册命令
        if ($this->app->runningInConsole()) {
            $this->commands([
                \Modules\Conversation\Commands\DownloadChatRecordMediaCommand::class,
                \Modules\Conversation\Commands\MigrateMediaToResourceCommand::class,
                \Modules\Conversation\Commands\UpdateResponsibleUserStatisticsCommand::class,
            ]);
        }
    }
}

