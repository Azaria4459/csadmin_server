<?php

namespace Modules\Conversation;

use Catch\Support\Module\Installer as ModuleInstaller;
use Modules\Conversation\Providers\ConversationServiceProvider;

class Installer extends ModuleInstaller
{
    protected function info(): array
    {
        return [
            'title' => '微信会话管理',
            'name' => 'conversation',
            'path' => 'conversation',
            'keywords' => '企业微信聊天记录',
            'description' => '企业微信聊天记录',
            'provider' => ConversationServiceProvider::class,
        ];
    }

    protected function requirePackages(): void
    {
        // 无需额外包
    }

    protected function removePackages(): void
    {
        // 无需移除包
    }
}