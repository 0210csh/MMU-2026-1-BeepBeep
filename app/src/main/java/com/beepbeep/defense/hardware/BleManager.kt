package com.beepbeep.defense.hardware

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    companion object {
        const val DEVICE_NAME = "SmartBat_Pro"
        val SERVICE_UUID = UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb")
        val DATA_UUID    = UUID.fromString("00002A56-0000-1000-8000-00805f9b34fb")
        val CONTROL_UUID = UUID.fromString("00002A57-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID    = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    data class SensorPacket(
        val handleEuler: FloatArray,
        val handleGyro:  FloatArray,
        val handleAccel: FloatArray,
        val tipEuler:    FloatArray,
        val tipGyro:     FloatArray,
        val tipAccel:    FloatArray,
        val btn1: Boolean = false,
        val btn2: Boolean = false
    )

    interface Callback {
        fun onConnected()
        fun onDisconnected()
        fun onPacket(packet: SensorPacket)
    }

    private var callback: Callback? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothAdapter by lazy {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName
                ?: result.device.name
                ?: return
            if (name == DEVICE_NAME) {
                stopScan()
                result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            this@BleManager.gatt = gatt
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> gatt.requestMtu(512)
                BluetoothProfile.STATE_DISCONNECTED -> {
                    this@BleManager.gatt = null
                    mainHandler.post { callback?.onDisconnected() }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service  = gatt.getService(SERVICE_UUID) ?: return
            val dataChar = service.getCharacteristic(DATA_UUID) ?: return
            gatt.setCharacteristicNotification(dataChar, true)
            val cccd = dataChar.getDescriptor(CCCD_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(cccd)
            }
            mainHandler.post { callback?.onConnected() }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            if (characteristic.uuid != DATA_UUID) return
            parseAndDeliver(characteristic.value?.toString(Charsets.UTF_8) ?: return)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid != DATA_UUID) return
            parseAndDeliver(value.toString(Charsets.UTF_8))
        }
    }

    private fun parseAndDeliver(raw: String) {
        try {
            val parts = raw.trim().split("#")
            if (parts.size < 2) return
            val h = parseSide(parts[0]) ?: return
            val t = parseSide(parts[1]) ?: return
            val btnParts = parts.getOrNull(2)?.split(",")
            val btn1 = btnParts?.getOrNull(0)?.trim() == "1"
            val btn2 = btnParts?.getOrNull(1)?.trim() == "1"
            val packet = SensorPacket(h[0], h[1], h[2], t[0], t[1], t[2], btn1, btn2)
            mainHandler.post { callback?.onPacket(packet) }
        } catch (_: Exception) {}
    }

    private fun parseSide(s: String): Array<FloatArray>? {
        val groups = s.split("|")
        if (groups.size < 3) return null
        return Array(3) { i ->
            val xyz = groups[i].split(",")
            if (xyz.size < 3) return null
            floatArrayOf(xyz[0].toFloat(), xyz[1].toFloat(), xyz[2].toFloat())
        }
    }

    fun setCallback(cb: Callback) { callback = cb }

    fun startScan() {
        if (scanning || gatt != null) return
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // 이름 필터 없이 전체 스캔 후 콜백에서 기기명 확인
        // (ArduinoBLE가 이름을 Scan Response에 넣는 경우 필터에 안 걸릴 수 있음)
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun sendControl(value: Int) {
        val g    = gatt ?: return
        val ctrl = g.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(ctrl, byteArrayOf(value.toByte()), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ctrl.value = byteArrayOf(value.toByte())
            @Suppress("DEPRECATION")
            g.writeCharacteristic(ctrl)
        }
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    val isConnected: Boolean get() = gatt != null
}
