package com.example.goldoverlay

import android.content.Context
import android.content.SharedPreferences

object OverlayPositionManager {

    private const val PREFS_NAME = "gold_overlay_prefs"
    private const val KEY_X = "position_x"
    private const val KEY_Y = "position_y"
    private const val DEFAULT_X = 100
    private const val DEFAULT_Y = 200

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getSavedX(context: Context): Int {
        return getPrefs(context).getInt(KEY_X, DEFAULT_X)
    }

    fun getSavedY(context: Context): Int {
        return getPrefs(context).getInt(KEY_Y, DEFAULT_Y)
    }

    fun savePosition(context: Context, x: Int, y: Int) {
        getPrefs(context).edit()
            .putInt(KEY_X, x)
            .putInt(KEY_Y, y)
            .apply()
    }
}
