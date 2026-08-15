package com.campuslink.mobile

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.campuslink.mobile.core.storage.CampusDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room v1 是当前迁移基线。此测试从导出的 v1 schema 创建数据库并检查核心表；
 * 后续提升版本时，在这里追加逐版本 Migration 与 runMigrationsAndValidate 验证。
 */
@RunWith(AndroidJUnit4::class)
class CampusDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CampusDatabase::class.java,
    )

    @Test
    fun versionOneSchemaContainsChatTables() {
        helper.createDatabase(DATABASE_NAME, 1).use { database ->
            database.query(
                "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name IN (?, ?)",
                arrayOf("conversations", "messages"),
            ).use { cursor ->
                cursor.moveToFirst()
                assertEquals(2, cursor.getInt(0))
            }
        }
    }

    private companion object {
        const val DATABASE_NAME = "migration-test"
    }
}
