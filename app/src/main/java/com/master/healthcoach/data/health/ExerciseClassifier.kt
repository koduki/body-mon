package com.master.healthcoach.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord

internal enum class ExerciseClass {
    STRENGTH,
    CARDIO,
    MORNING_ROUTINE,
    OTHER,
}

internal fun ExerciseClass.contributesToCardioMinutes(): Boolean =
    this == ExerciseClass.CARDIO || this == ExerciseClass.MORNING_ROUTINE

internal fun classifyExercise(type: Int): ExerciseClass = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> ExerciseClass.MORNING_ROUTINE

    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING,
    -> ExerciseClass.STRENGTH

    ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER,
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL,
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE,
    -> ExerciseClass.CARDIO

    else -> ExerciseClass.OTHER
}

internal fun exerciseLabel(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT ->
        "朝の5分ルーティン（軽い筋トレ＋有酸素）"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "筋力トレーニング"
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "ウェイトリフティング"
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS -> "自重トレーニング"
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "HIIT"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "ウォーキング"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "ランニング"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "トレッドミル"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "サイクリング"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "エアロバイク"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "オープンウォータースイム"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "プール"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING -> "ローイング"
    ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE -> "ローイングマシン"
    else -> "その他の運動"
}
