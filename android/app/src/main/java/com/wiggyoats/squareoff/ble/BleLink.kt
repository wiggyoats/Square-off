package com.wiggyoats.squareoff.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.UUID

private const val TAG = "SquareOffBle"

val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

data class FoundDevice(val address: String, val name: String)

fun isSquareOff(name: String?): Boolean =
    name != null && "squareoff" in name.lowercase().replace(" ", "")

class BleException(message: String) : Exception(message)

/**
 * One GATT connection, with Android's one-operation-at-a-time rule handled by a
 * mutex: each operation waits for its callback before the next one starts.
 *
 * Callers must already hold the Bluetooth permissions (checked in the UI).
 */
@SuppressLint("MissingPermission")
class BleLink(private val context: Context) {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter get() = manager.adapter

    private var gatt: BluetoothGatt? = null
    private val opLock = Mutex()
    private var pending: CompletableDeferred<Int>? = null
    private var connectWait: CompletableDeferred<Int>? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Called on a binder thread for every notification: (characteristic uuid, bytes). */
    var onNotify: ((UUID, ByteArray) -> Unit)? = null

    val bluetoothOn: Boolean get() = adapter?.isEnabled == true

    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner ?: throw BleException("Bluetooth is off.")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                trySend(FoundDevice(result.device.address, name))
            }

            override fun onScanFailed(errorCode: Int) {
                close(BleException("Bluetooth scan failed (code $errorCode)."))
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, cb)
        awaitClose { runCatching { scanner.stopScan(cb) } }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connection state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                _connected.value = true
                connectWait?.complete(status)
            } else {
                _connected.value = false
                connectWait?.complete(if (status == BluetoothGatt.GATT_SUCCESS) -1 else status)
                pending?.complete(-1)
                g.close()
                if (gatt === g) gatt = null
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "mtu $mtu")
            pending?.complete(status)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pending?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pending?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify?.invoke(c.uuid, value)
        }

        @Deprecated("Used below Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) onNotify?.invoke(c.uuid, c.value ?: return)
        }
    }

    /** Runs one GATT operation and waits for its callback. */
    private suspend fun op(name: String, timeoutMs: Long = 10_000, start: (BluetoothGatt) -> Boolean): Int =
        opLock.withLock {
            val g = gatt ?: throw BleException("Not connected.")
            val d = CompletableDeferred<Int>()
            pending = d
            try {
                if (!start(g)) throw BleException("$name could not start.")
                val status = withTimeout(timeoutMs) { d.await() }
                if (status != BluetoothGatt.GATT_SUCCESS) throw BleException("$name failed (status $status).")
                status
            } finally {
                pending = null
            }
        }

    suspend fun connect(address: String) {
        disconnect()
        val device: BluetoothDevice = adapter?.getRemoteDevice(address) ?: throw BleException("Bluetooth is off.")
        val wait = CompletableDeferred<Int>()
        connectWait = wait
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw BleException("Could not open a connection.")
        gatt = g
        val status = try {
            withTimeout(20_000) { wait.await() }
        } catch (e: Exception) {
            g.close()
            gatt = null
            throw BleException("The board didn't answer. Is it switched on?")
        } finally {
            connectWait = null
        }
        if (status != BluetoothGatt.GATT_SUCCESS) {
            gatt = null
            throw BleException("Connection failed (status $status).")
        }
        // Long motion paths must go in one write, so ask for a big MTU first.
        runCatching { op("MTU request") { it.requestMtu(517) } }
        op("Service discovery", 15_000) { it.discoverServices() }
    }

    fun disconnect() {
        gatt?.let {
            runCatching { it.disconnect() }
            runCatching { it.close() }
        }
        gatt = null
        _connected.value = false
    }

    private fun find(uuid: UUID): BluetoothGattCharacteristic? =
        gatt?.services?.firstNotNullOfOrNull { it.getCharacteristic(uuid) }

    fun has(uuid: UUID): Boolean = find(uuid) != null

    suspend fun enableNotify(uuid: UUID): Boolean {
        val c = find(uuid) ?: return false
        val g = gatt ?: return false
        g.setCharacteristicNotification(c, true)
        val desc = c.getDescriptor(CCCD) ?: return true
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        op("Subscribe") {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeDescriptor(desc, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                desc.value = value
                @Suppress("DEPRECATION")
                it.writeDescriptor(desc)
            }
        }
        return true
    }

    suspend fun write(uuid: UUID, data: ByteArray, withResponse: Boolean = true) {
        val c = find(uuid) ?: throw BleException("The board has no characteristic $uuid.")
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        op("Write") {
            if (Build.VERSION.SDK_INT >= 33) {
                it.writeCharacteristic(c, data, type) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = type
                @Suppress("DEPRECATION")
                c.value = data
                @Suppress("DEPRECATION")
                it.writeCharacteristic(c)
            }
        }
    }
}
