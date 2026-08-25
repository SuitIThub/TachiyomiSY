package mihon.core.migration.migrations

import eu.kanade.tachiyomi.source.online.all.NHentai
import mihon.core.migration.MigrateUtils
import mihon.core.migration.Migration
import mihon.core.migration.MigrationContext
import tachiyomi.core.common.util.lang.withIOContext

class NativeNHentaiMigration : Migration {
    override val version: Float = Migration.ALWAYS

    override suspend fun invoke(migrationContext: MigrationContext): Boolean = withIOContext {
        NHentai.LANGUAGE_SOURCE_IDS.forEach { oldId ->
            MigrateUtils.updateSourceId(migrationContext, NHentai.otherId, oldId)
        }
        return@withIOContext true
    }
}
