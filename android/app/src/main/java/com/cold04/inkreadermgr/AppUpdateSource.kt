package com.cold04.inkreadermgr

import android.content.SharedPreferences

internal enum class AppUpdateSource(
    val preferenceValue: String,
    val label: String,
    val repositoryUrl: String,
    val releasesUrl: String,
) {
    GITEE(
        "gitee",
        "Gitee",
        "https://gitee.com/coldin04/Pico_Manager",
        "https://gitee.com/api/v5/repos/coldin04/Pico_Manager/releases?per_page=100",
    ),
    GITHUB(
        "github",
        "GitHub",
        "https://github.com/Coldin04/Pico_Manager",
        "https://api.github.com/repos/Coldin04/Pico_Manager/releases?per_page=100",
    );

    fun acceptsUrl(url: String): Boolean = url.startsWith("$repositoryUrl/", ignoreCase = true)

    companion object {
        const val PREFERENCE_KEY = "app_update_source"

        fun fromPreference(value: String?): AppUpdateSource =
            entries.firstOrNull { it.preferenceValue == value } ?: GITEE

        fun load(preferences: SharedPreferences): AppUpdateSource =
            fromPreference(preferences.getString(PREFERENCE_KEY, null))
    }
}
