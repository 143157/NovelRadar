package com.novelradar.app.data.db

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.LoadedChapter
import com.novelradar.app.domain.repository.ReadingLocation
import com.squareup.moshi.Moshi
import java.io.File
import org.junit.Test

/** Offline schema and producer defaults; migration execution is checked separately with SQLite. */
class ChapterContentFormatContractTest {
    @Test fun persistedContentDefaultsToLegacy() {
        assertThat(ChapterEntity("book", "source", "chapter", 2, "Title").contentFormatVersion)
            .isEqualTo(0)
    }

    @Test fun persistedPositionsDefaultToLegacy() {
        assertThat(ReadingProgressEntity("book", "source", "chapter", 2, 77, 50).contentFormatVersion)
            .isEqualTo(0)
    }

    @Test fun newPositionsDefaultToCurrentBoundaryFormat() {
        assertThat(ReadingLocation("book", "source", "chapter", 2, 77, 50).contentFormatVersion)
            .isEqualTo(1)
    }

    @Test fun loadedContentDefaultsToVerifiedBoundaryFormat() {
        assertThat(LoadedChapter(Chapter("book", "source", "chapter", 2, "Title"),
            "Content", ChapterOrigin.Network).boundaryVerified).isTrue()
    }

    @Test fun versionFiveMigrationOnlyAddsTwoConservativeColumns() {
        val source = File("src/main/java/com/novelradar/app/data/db/NovelDatabase.kt").readText()
        assertThat(source).contains("version = 5")
        val migration = Regex("val MIGRATION_4_5 =.*?(?=val MIGRATION_|\\z)", RegexOption.DOT_MATCHES_ALL)
            .find(source)?.value.orEmpty()
        assertThat(migration).contains("Migration(4, 5)")
        assertThat(Regex("db\\.execSQL\\(\"([^\"]+)\"\\)").findAll(migration).map { it.groupValues[1] }.toList())
            .containsExactly(
                "ALTER TABLE chapters ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE reading_progress ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0",
            ).inOrder()
    }

    @Test fun builderRegistersAllExistingMigrationsAndVersionFive() {
        val source = File("src/main/java/com/novelradar/app/app/AppContainer.kt").readText()
        for (name in listOf("MIGRATION_1_2", "MIGRATION_2_3", "MIGRATION_3_4", "MIGRATION_4_5")) {
            assertThat(source).contains("NovelDatabase.$name")
        }
        assertThat(source).doesNotContain("fallbackToDestructiveMigration")
    }

    @Test fun storedContentUpdateIncludesMarkerInsideExistingUrlGuard() {
        val source = File("src/main/java/com/novelradar/app/data/db/ChapterDao.kt").readText()
        assertThat(source).contains("contentFormatVersion = :contentFormatVersion")
        assertThat(source).contains("AND url = :expectedUrl")
        assertThat(source).contains("CASE WHEN localContentState = 'DOWNLOADED' THEN 'DOWNLOADED' ELSE :state END")
    }

    @Test fun shelfProjectionSelectsTheStoredPositionMarker() {
        val source = File("src/main/java/com/novelradar/app/data/db/BookDao.kt").readText()
        assertThat(source).contains("p.contentFormatVersion AS progress_contentFormatVersion")
    }

    @Test fun generatedVersionFivePreservesEveryExistingFieldKeyAndForeignKey() {
        val directory = File("schemas/com.novelradar.app.data.db.NovelDatabase")
        assertThat(File(directory, "5.json").isFile).isTrue()
        val before = schema(File(directory, "4.json"))
        val after = schema(File(directory, "5.json"))
        assertThat(after["version"]).isEqualTo(5.0)
        val oldEntities = entities(before)
        val newEntities = entities(after)
        assertThat(newEntities.keys).containsExactlyElementsIn(oldEntities.keys)
        for ((table, old) in oldEntities) {
            val current = newEntities.getValue(table)
            assertThat(current["primaryKey"]).isEqualTo(old["primaryKey"])
            assertThat(current["foreignKeys"]).isEqualTo(old["foreignKeys"])
            assertThat(current["indices"]).isEqualTo(old["indices"])
            val fields = current["fields"] as List<*>
            if (table in setOf("chapters", "reading_progress")) {
                assertThat(fields.dropLast(1)).isEqualTo(old["fields"])
                assertThat(fields.last()).isEqualTo(mapOf(
                    "fieldPath" to "contentFormatVersion", "columnName" to "contentFormatVersion",
                    "affinity" to "INTEGER", "notNull" to true, "defaultValue" to "0",
                ))
                assertThat(current["createSql"] as String).contains("`contentFormatVersion` INTEGER NOT NULL DEFAULT 0")
            } else {
                assertThat(current).isEqualTo(old)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun schema(file: File): Map<String, Any?> =
        Moshi.Builder().build().adapter(Map::class.java).fromJson(file.readText())!!["database"] as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun entities(schema: Map<String, Any?>): Map<String, Map<String, Any?>> =
        (schema.getValue("entities") as List<Map<String, Any?>>).associateBy { it["tableName"] as String }
}
