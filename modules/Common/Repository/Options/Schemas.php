<?php

namespace Modules\Common\Repository\Options;

use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Schema;
use Illuminate\Support\Str;

class Schemas implements OptionInterface
{
    public function get(): array
    {
        $options = [];
        $connection = DB::connection();
        $databaseName = $connection->getDatabaseName();
        $tablePrefix = $connection->getTablePrefix();

        foreach (Schema::getTables($databaseName) as $table) {
            $tableName = Str::of($table['name'])->replaceStart($tablePrefix, '');

            $options[] = [
                'label' => $tableName."\t\t\t\t".$table['comment'],
                'value' => $tableName,
            ];
        }

        return $options;
    }
}
