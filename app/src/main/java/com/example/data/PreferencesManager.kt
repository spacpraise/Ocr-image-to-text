package com.example.data

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("snaptext_preferences", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ONBOARDING_COMPLETED = "key_onboarding_completed"
        private const val KEY_THEME_MODE = "key_theme_mode"
        private const val KEY_DAILY_SCAN_DATE = "key_daily_scan_date"
        private const val KEY_DAILY_SCAN_COUNT = "key_daily_scan_count"
        private const val KEY_INSTALL_PINGED = "key_install_pinged"
        private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    }

    var isOnboardingCompleted: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, value).apply()

    var themeMode: AppThemeMode
        get() {
            val name = prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.name)
            return try {
                AppThemeMode.valueOf(name ?: AppThemeMode.SYSTEM.name)
            } catch (e: Exception) {
                AppThemeMode.SYSTEM
            }
        }
        set(value) = prefs.edit().putString(KEY_THEME_MODE, value.name).apply()

    /**
     * Number of free-tier scans already used on today's local date. Automatically
     * resets to 0 the first time this is read/incremented on a new day -- no
     * background job or alarm needed, it just compares against the stored date.
     */
    fun scansUsedForToday(): Int {
        val today = dayFormatter.format(Date())
        val storedDate = prefs.getString(KEY_DAILY_SCAN_DATE, null)
        return if (storedDate == today) {
            prefs.getInt(KEY_DAILY_SCAN_COUNT, 0)
        } else {
            0
        }
    }

    fun incrementScansUsedForToday() {
        val today = dayFormatter.format(Date())
        val current = scansUsedForToday() // handles day rollover
        prefs.edit()
            .putString(KEY_DAILY_SCAN_DATE, today)
            .putInt(KEY_DAILY_SCAN_COUNT, current + 1)
            .apply()
    }

    /**
     * True only the first time this is called for this install -- lets the caller
     * fire a single "app downloaded/opened" ping without double-counting across
     * every subsequent launch.
     */
    fun markInstallPingSentIfNeeded(): Boolean {
        if (prefs.getBoolean(KEY_INSTALL_PINGED, false)) return false
        prefs.edit().putBoolean(KEY_INSTALL_PINGED, true).apply()
        return true
    }
}