package com.master.healthcoach.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
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
            ).build()
    }
}
