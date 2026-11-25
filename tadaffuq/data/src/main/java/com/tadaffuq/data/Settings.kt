package com.tadaffuq.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tadaffuq.engine.SpeedMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class ListDensity { COMFORTABLE, COMPACT }

/** إعدادات التطبيق. القيم الافتراضية بسيطة، والمتقدم اختياري. */
data class Settings(
    val maxConnections: Int = 8,
    val maxParallel: Int = 3,
    val globalLimitBps: Long = 0,
    val wifiOnly: Boolean = false,
    val mode: SpeedMode = SpeedMode.NORMAL,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    /** "" = لغة النظام، "ar"، "en". */
    val language: String = "",
    val suggestClipboard: Boolean = false,
    val notifyOnComplete: Boolean = true,
    val autoVerifyHash: Boolean = true,
    val sortByCategory: Boolean = true,
    val pauseOnLowBattery: Int = 15,
    val onlyWhileCharging: Boolean = false,
    val pauseWhenHot: Boolean = true,
    val dailyLimitBytes: Long = 0,
    val monthlyLimitBytes: Long = 0,
    val blockPopups: Boolean = true,
    val blockTrackers: Boolean = false,
    val desktopMode: Boolean = false,
    val density: ListDensity = ListDensity.COMFORTABLE,
    val showLiveScene: Boolean = true,
    val userAgent: String = "",
    val warnExecutables: Boolean = true,
    val onboarded: Boolean = false,
)

private val Context.store: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val maxConnections = intPreferencesKey("maxConnections")
        val maxParallel = intPreferencesKey("maxParallel")
        val globalLimit = longPreferencesKey("globalLimitBps")
        val wifiOnly = booleanPreferencesKey("wifiOnly")
        val mode = stringPreferencesKey("mode")
        val theme = stringPreferencesKey("theme")
        val language = stringPreferencesKey("language")
        val clipboard = booleanPreferencesKey("suggestClipboard")
        val notify = booleanPreferencesKey("notifyOnComplete")
        val verify = booleanPreferencesKey("autoVerifyHash")
        val byCategory = booleanPreferencesKey("sortByCategory")
        val lowBattery = intPreferencesKey("pauseOnLowBattery")
        val charging = booleanPreferencesKey("onlyWhileCharging")
        val hot = booleanPreferencesKey("pauseWhenHot")
        val daily = longPreferencesKey("dailyLimitBytes")
        val monthly = longPreferencesKey("monthlyLimitBytes")
        val popups = booleanPreferencesKey("blockPopups")
        val trackers = booleanPreferencesKey("blockTrackers")
        val desktop = booleanPreferencesKey("desktopMode")
        val density = stringPreferencesKey("density")
        val scene = booleanPreferencesKey("showLiveScene")
        val ua = stringPreferencesKey("userAgent")
        val warnExe = booleanPreferencesKey("warnExecutables")
        val onboarded = booleanPreferencesKey("onboarded")
    }

    val settings: Flow<Settings> = context.store.data.map { p ->
        val s = readFrom(p)
        s.copy(maxConnections = s.maxConnections.coerceIn(1, 32), maxParallel = s.maxParallel.coerceIn(1, 10))
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.store.edit { p ->
            val s = transform(readFrom(p))
            p[K.maxConnections] = s.maxConnections.coerceIn(1, 32)
            p[K.maxParallel] = s.maxParallel.coerceIn(1, 10)
            p[K.globalLimit] = s.globalLimitBps.coerceAtLeast(0)
            p[K.wifiOnly] = s.wifiOnly
            p[K.mode] = s.mode.name
            p[K.theme] = s.theme.name
            p[K.language] = s.language
            p[K.clipboard] = s.suggestClipboard
            p[K.notify] = s.notifyOnComplete
            p[K.verify] = s.autoVerifyHash
            p[K.byCategory] = s.sortByCategory
            p[K.lowBattery] = s.pauseOnLowBattery.coerceIn(0, 50)
            p[K.charging] = s.onlyWhileCharging
            p[K.hot] = s.pauseWhenHot
            p[K.daily] = s.dailyLimitBytes.coerceAtLeast(0)
            p[K.monthly] = s.monthlyLimitBytes.coerceAtLeast(0)
            p[K.popups] = s.blockPopups
            p[K.trackers] = s.blockTrackers
            p[K.desktop] = s.desktopMode
            p[K.density] = s.density.name
            p[K.scene] = s.showLiveScene
            p[K.ua] = s.userAgent
            p[K.warnExe] = s.warnExecutables
            p[K.onboarded] = s.onboarded
        }
    }

    private fun readFrom(p: Preferences): Settings {
        val d = Settings()
        return Settings(
            maxConnections = p[K.maxConnections] ?: d.maxConnections,
            maxParallel = p[K.maxParallel] ?: d.maxParallel,
            globalLimitBps = p[K.globalLimit] ?: d.globalLimitBps,
            wifiOnly = p[K.wifiOnly] ?: d.wifiOnly,
            mode = p[K.mode]?.let { runCatching { SpeedMode.valueOf(it) }.getOrNull() } ?: d.mode,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            language = p[K.language] ?: d.language,
            suggestClipboard = p[K.clipboard] ?: d.suggestClipboard,
            notifyOnComplete = p[K.notify] ?: d.notifyOnComplete,
            autoVerifyHash = p[K.verify] ?: d.autoVerifyHash,
            sortByCategory = p[K.byCategory] ?: d.sortByCategory,
            pauseOnLowBattery = p[K.lowBattery] ?: d.pauseOnLowBattery,
            onlyWhileCharging = p[K.charging] ?: d.onlyWhileCharging,
            pauseWhenHot = p[K.hot] ?: d.pauseWhenHot,
            dailyLimitBytes = p[K.daily] ?: d.dailyLimitBytes,
            monthlyLimitBytes = p[K.monthly] ?: d.monthlyLimitBytes,
            blockPopups = p[K.popups] ?: d.blockPopups,
            blockTrackers = p[K.trackers] ?: d.blockTrackers,
            desktopMode = p[K.desktop] ?: d.desktopMode,
            density = p[K.density]?.let { runCatching { ListDensity.valueOf(it) }.getOrNull() } ?: d.density,
            showLiveScene = p[K.scene] ?: d.showLiveScene,
            userAgent = p[K.ua] ?: d.userAgent,
            warnExecutables = p[K.warnExe] ?: d.warnExecutables,
            onboarded = p[K.onboarded] ?: d.onboarded,
        )
    }
}
