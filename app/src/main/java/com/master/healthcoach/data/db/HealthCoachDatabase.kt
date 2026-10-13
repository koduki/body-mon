package com.master.healthcoach.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        DailyHealthSummaryEntity::class,
        BodyCompositionEntity::class,
        HealthSourceStatusEntity::class,
        ExerciseSessionEntity::class,
        NutritionMealEntity::class,
        GoalEntity::class,
        WeeklyReportEntity::class,
        ChatMessageEntity::class,
        ConversationMemoryEntity::class,
        AppEventEntity::class, ResponseEvidenceEntity::class, ReportArchiveEntity::class, AppMetadataEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class HealthCoachDatabase : RoomDatabase() {
    abstract fun dao(): HealthCoachDao

    companion object {
        fun create(context: Context): HealthCoachDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                HealthCoachDatabase::class.java,
                "health-coach.db",
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
            ).addCallback(object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) { installRevisionTriggers(db) }
            }).build()

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS app_events (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, timestamp INTEGER NOT NULL, category TEXT NOT NULL, outcome TEXT NOT NULL, durationMs INTEGER NOT NULL, correlationId TEXT NOT NULL, attributesJson TEXT NOT NULL, appVersion TEXT NOT NULL, commitSha TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS response_evidence (messageId INTEGER NOT NULL PRIMARY KEY, timestamp INTEGER NOT NULL, encryptedPayload TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS report_archive (id TEXT NOT NULL PRIMARY KEY, periodStart TEXT NOT NULL, periodEnd TEXT NOT NULL, generatedAt INTEGER NOT NULL, revision INTEGER NOT NULL, policyVersion TEXT NOT NULL, commitSha TEXT NOT NULL, snapshotJson TEXT NOT NULL, inputsJson TEXT NOT NULL)")
                db.execSQL("CREATE TABLE IF NOT EXISTS app_metadata (`key` TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL)")
                installRevisionTriggers(db)
            }
        }
        private fun installRevisionTriggers(db: SupportSQLiteDatabase) {
            db.execSQL("INSERT OR IGNORE INTO app_metadata (`key`, value) VALUES ('revision', '0')")
            listOf("daily_health_summary", "body_composition_daily", "health_source_status", "exercise_sessions", "nutrition_meals", "goals", "weekly_reports", "chat_messages", "conversation_memory", "report_archive").forEach { table ->
                listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                    db.execSQL("CREATE TRIGGER IF NOT EXISTS revision_${table}_$operation AFTER $operation ON $table BEGIN UPDATE app_metadata SET value = CAST(value AS INTEGER) + 1 WHERE `key` = 'revision'; END")
                }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN sleepMinutes INTEGER")
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN moderateIntensityMinutes INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN vigorousIntensityMinutes INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN heartRateAverageBpm INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN heartRateMinimumBpm INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN heartRateMaximumBpm INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN heartRateMeasurementCount INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN basalCaloriesKcal REAL",
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN sleepStartEpochMillis INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN sleepEndEpochMillis INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN sleepHeartRateAverageBpm INTEGER",
                )
                db.execSQL("ALTER TABLE goals ADD COLUMN dietStartDate TEXT")
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN attachmentNames TEXT")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN morningRoutineMinutes INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN intakeCaloriesKcal REAL",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN proteinGrams REAL",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN totalFatGrams REAL",
                )
                db.execSQL(
                    "ALTER TABLE daily_health_summary ADD COLUMN carbohydrateGrams REAL",
                )
            }
        }

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN nutritionComplete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN nutritionRecordStatus TEXT NOT NULL DEFAULT 'legacy'")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN metricOriginsJson TEXT NOT NULL DEFAULT '{}'")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN heartRateCoverageMinutes INTEGER")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN heartRateOutsideExerciseSamples INTEGER")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN hikingMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN hiitMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE nutrition_meals ADD COLUMN isDailyRecord INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE goals ADD COLUMN monthlyWeightLossKg REAL")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN modelId TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN appVersionName TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN policyVersion TEXT")
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN toolTraceJson TEXT")
            }
        }

        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN sleepSessionMinutes INTEGER")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN sleepDurationSource TEXT NOT NULL DEFAULT 'legacy_session'")
                db.execSQL("ALTER TABLE daily_health_summary ADD COLUMN cyclingMinutes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE daily_health_summary SET sleepSessionMinutes = sleepMinutes")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE daily_health_summary " +
                        "ADD COLUMN mealCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS nutrition_meals (" +
                        "recordId TEXT NOT NULL PRIMARY KEY, " +
                        "date TEXT NOT NULL, " +
                        "startEpochMillis INTEGER NOT NULL, " +
                        "endEpochMillis INTEGER NOT NULL, " +
                        "mealLabel TEXT NOT NULL, " +
                        "mealType INTEGER NOT NULL, " +
                        "intakeCaloriesKcal REAL, " +
                        "proteinGrams REAL, " +
                        "totalFatGrams REAL, " +
                        "carbohydrateGrams REAL, " +
                        "recordCount INTEGER NOT NULL, " +
                        "dataOrigin TEXT NOT NULL" +
                        ")",
                )
            }
        }
    }
}
