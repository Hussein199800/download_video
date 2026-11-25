package com.tadaffuq.app

import com.tadaffuq.app.core.Fmt
import com.tadaffuq.app.core.Search
import com.tadaffuq.app.core.formatLatin
import com.tadaffuq.app.download.DeviceState
import com.tadaffuq.app.download.ErrorCodes
import com.tadaffuq.app.download.Hold
import com.tadaffuq.app.download.Policy
import com.tadaffuq.data.NetKind
import com.tadaffuq.data.Settings
import com.tadaffuq.engine.DownloadError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PureAppLogicTest {
    private val wifi = DeviceState(connected = true, net = NetKind.WIFI, batteryPercent = 80)
    private val cell = DeviceState(connected = true, net = NetKind.CELLULAR, metered = true, batteryPercent = 80)

    @Test fun holds() {
        assertNull(Policy.hold(Settings(), wifi, 0, 0))
        assertEquals(Hold.NO_NETWORK, Policy.hold(Settings(), DeviceState(connected = false), 0, 0))
        assertEquals(Hold.WIFI_ONLY, Policy.hold(Settings(wifiOnly = true), cell, 0, 0))
        assertNull(Policy.hold(Settings(wifiOnly = true), wifi, 0, 0))
        assertEquals(Hold.LOW_BATTERY, Policy.hold(Settings(), wifi.copy(batteryPercent = 10), 0, 0))
        assertNull(Policy.hold(Settings(), wifi.copy(batteryPercent = 10, charging = true), 0, 0))
        assertEquals(Hold.NOT_CHARGING, Policy.hold(Settings(onlyWhileCharging = true), wifi, 0, 0))
        assertEquals(Hold.TOO_HOT, Policy.hold(Settings(), wifi.copy(thermal = 4), 0, 0))
        assertEquals(Hold.DAILY_LIMIT, Policy.hold(Settings(dailyLimitBytes = 100), cell, 100, 100))
        assertNull("limits are for mobile data only", Policy.hold(Settings(dailyLimitBytes = 100), wifi, 100, 100))
        assertEquals(Hold.WIFI_ONLY, Policy.hold(Settings(), cell, 0, 0, taskWifiOnly = true))
    }

    @Test fun thermalCapsConnections() {
        assertEquals(8, Policy.connectionCap(wifi, 8))
        assertEquals(2, Policy.connectionCap(wifi.copy(thermal = 2), 8))
    }

    @Test fun arabicNumbersAreLatinAndIsolated() {
        Locale.setDefault(Locale.forLanguageTag("ar"))
        val s = formatLatin("محدد: %1\$d من %2\$s", arrayOf(12, "x"))
        assertTrue(s.contains("⁦12⁩"))
        assertFalse(s.any { it in '٠'..'٩' })
        assertTrue(Fmt.bytes(1536, true).contains("1.5"))
        assertEquals(12.5, Fmt.parseNumber("١٢٫٥")!!, 0.0)
        Locale.setDefault(Locale.US)
    }

    @Test fun arabicSearchNormalization() {
        assertTrue(Search.matches("أداة إدارة الملفات.zip", "اداه"))
        assertTrue(Search.matches("مستشفى", "مستشفي"))
        assertTrue(Search.matches("Report.PDF", "report"))
    }

    @Test fun errorCodesRoundTrip() {
        listOf(DownloadError.NotFound, DownloadError.ChangedOnServer, DownloadError.LinkExpired(403), DownloadError.Http(503)).forEach {
            assertEquals(it, ErrorCodes.toError(ErrorCodes.of(it)))
        }
    }
}
