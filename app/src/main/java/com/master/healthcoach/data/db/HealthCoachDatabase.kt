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
    version = 2,
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
            ).addMigrations(MIGRATION_1_2).build()

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
    }
}
