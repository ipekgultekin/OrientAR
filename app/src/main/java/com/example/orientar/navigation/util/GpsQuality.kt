package com.example.orientar.navigation.util

/**
 * GPS accuracy quality classification for user-facing UI. The 5m / 10m thresholds
 * match the calibration GPS ring (see activity_ar_navigation.xml ringF4ArReadiness
 * and ArNavigationActivity.updateGpsRing).
 */
enum class GpsQuality { GOOD, FAIR, POOR }

object GpsQualityClassifier {
    /** Below this accuracy (in meters), GPS is considered Good. */
    const val GOOD_THRESHOLD_M = 5f

    /** At or below this accuracy (in meters), GPS is considered Fair. Above this, Poor. */
    const val FAIR_THRESHOLD_M = 10f

    fun classify(accuracyM: Float): GpsQuality = when {
        accuracyM < GOOD_THRESHOLD_M -> GpsQuality.GOOD
        accuracyM <= FAIR_THRESHOLD_M -> GpsQuality.FAIR
        else -> GpsQuality.POOR
    }
}
