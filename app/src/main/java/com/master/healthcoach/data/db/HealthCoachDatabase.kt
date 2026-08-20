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
        GoalEntity::class,
        WeeklyReportEntity::class,
        ChatMessageEntity::class,
        ConversationMemoryEntity::class,
    ],
    version = 6,
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
            ).build()

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
    }
}
