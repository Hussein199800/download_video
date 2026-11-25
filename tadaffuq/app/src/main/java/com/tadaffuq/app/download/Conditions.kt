package com.tadaffuq.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.tadaffuq.data.NetKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** حالة الجهاز الحقيقية التي تؤثر في التنزيل: الشبكة والبطارية والحرارة. */
data class DeviceState(
    val connected: Boolean = true,
    val net: NetKind = NetKind.OTHER,
    val metered: Boolean = false,
    val batteryPercent: Int = 100,
    val charging: Boolean = false,
    /** PowerManager.THERMAL_STATUS_* أو 0 إن لم تتوفر. */
    val thermal: Int = 0,
)

class Conditions(private val context: Context) {
    private val _state = MutableStateFlow(DeviceState())
    val state: StateFlow<DeviceState> = _state

    /** للاختبارات فقط: حالة جهاز محددة. */
    @androidx.annotation.VisibleForTesting
    fun set(state: DeviceState) { _state.value = state }

    fun start() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        updateNetwork(cm, cm.activeNetwork)
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateNetwork(cm, network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateFrom(caps)
            override fun onLost(network: Network) { _state.update { it.copy(connected = false) } }
        })

        val battery = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = updateBattery(i)
        }
        ContextCompat.registerReceiver(context, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let(::updateBattery)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val pm = context.getSystemService(PowerManager::class.java)
            _state.update { it.copy(thermal = pm.currentThermalStatus) }
            pm.addThermalStatusListener { s -> _state.update { it.copy(thermal = s) } }
        }
    }

    private fun updateNetwork(cm: ConnectivityManager, network: Network?) {
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        if (caps == null) _state.update { it.copy(connected = false) } else updateFrom(caps)
    }

    private fun updateFrom(caps: NetworkCapabilities) {
        val kind = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetKind.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetKind.CELLULAR
            else -> NetKind.OTHER
        }
        _state.update {
            it.copy(
                connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                net = kind,
                metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            )
        }
    }

    private fun updateBattery(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 100
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        _state.update { it.copy(batteryPercent = pct, charging = charging) }
    }
}
