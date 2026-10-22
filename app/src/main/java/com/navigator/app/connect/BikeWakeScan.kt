package com.navigator.app.connect

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.navigator.app.ble.BccuConnectionService
import com.navigator.app.logging.AppLogger
import com.navigator.app.settings.AppSettings

/**
 * Lets the connection service shut down completely while the bike is off.
 * The dash only advertises while it's powered, so a system-held BLE scan
 * filtered to the bike's address (hardware-offloaded, survives our process
 * being killed) wakes [BikeSeenReceiver] when the ignition comes on, which
 * starts the service again.
 *
 * Android drops these scans when Bluetooth turns off or the phone reboots;
 * [AutoConnectReceiver] re-arms on both.
 */
object BikeWakeScan {

    @SuppressLint("MissingPermission")
    fun arm(context: Context) {
        val address = AppSettings(context).bondedDeviceAddress ?: return
        val scanner = scanner(context) ?: run {
            AppLogger.log("Wake", "Bluetooth off - wake scan not armed (re-armed when Bluetooth turns on)")
            return
        }
        val filters = listOf(ScanFilter.Builder().setDeviceAddress(address).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
            .build()
        val result = runCatching { scanner.startScan(filters, settings, pendingIntent(context)) }
        AppLogger.log("Wake", if (result.getOrNull() == 0) "Watching for bike $address (low-power scan)"
            else "!! Wake scan failed: ${result.exceptionOrNull() ?: "code ${result.getOrNull()}"}")
    }

    @SuppressLint("MissingPermission")
    fun disarm(context: Context) {
        val scanner = scanner(context) ?: return
        runCatching { scanner.stopScan(pendingIntent(context)) }
    }

    private fun scanner(context: Context): BluetoothLeScanner? {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        return if (adapter.isEnabled) adapter.bluetoothLeScanner else null
    }

    // Must be mutable: the system adds the scan results as extras.
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0,
        Intent(context, BikeSeenReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )
}

class BikeSeenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AppLogger.init(context)
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0)
        if (error != 0) {
            AppLogger.log("Wake", "!! Wake scan error $error")
            return
        }
        val address = AppSettings(context).bondedDeviceAddress ?: return
        if (BccuConnectionService.isRunning) return
        BikeWakeScan.disarm(context)
        AppLogger.log("Wake", "Bike $address is advertising - starting connection service")
        runCatching { BccuConnectionService.start(context, address) }.onFailure {
            AppLogger.log("Wake", "!! Android refused to start the service from the background: $it")
            BikeWakeScan.arm(context)
        }
    }
}
