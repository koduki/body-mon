package com.master.healthcoach.data.health

import androidx.health.connect.client.records.ExerciseSessionRecord

internal enum class ExerciseClass {
    STRENGTH,
    CARDIO,
    MORNING_ROUTINE,
    INTERVAL,
    OTHER,
}

internal fun ExerciseClass.contributesToCardioMinutes(): Boolean =
    this == ExerciseClass.CARDIO || this == ExerciseClass.MORNING_ROUTINE || this == ExerciseClass.INTERVAL

internal fun classifyExercise(type: Int): ExerciseClass = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> ExerciseClass.OTHER
    ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> ExerciseClass.INTERVAL

    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    -> ExerciseClass.STRENGTH

    ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
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
        "一般運動（内容未確認）"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "登山"
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

/** Candidate morning habit, not proof of strength training. Long/afternoon Other remains unknown. */
internal fun classifyExerciseSession(type: Int, start: java.time.Instant, end: java.time.Instant, zone: java.time.ZoneId): ExerciseClass {
    val minutes = java.time.Duration.between(start, end).toMinutes()
    val hour = start.atZone(zone).hour
    return if (type == ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT && hour in 4..10 && minutes in 1..15) {
        ExerciseClass.MORNING_ROUTINE
    } else classifyExercise(type)
}
