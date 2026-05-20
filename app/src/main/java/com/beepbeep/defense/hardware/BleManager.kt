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
        const val DEVICE_NAME    = "SmartBat_Pro"
        val SERVICE_UUID         = UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb")
        val DATA_UUID            = UUID.fromString("00002A56-0000-1000-8000-00805f9b34fb")
        val CONTROL_UUID         = UUID.fromString("00002A57-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID            = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val BATTERY_SERVICE_UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
        val BATTERY_LEVEL_UUID   = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")
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
        fun onReconnecting() {}   // 자동 재연결 시도 중 — UI 피드백용 (optional override)
        fun onPacket(packet: SensorPacket)
        fun onBatteryLevel(level: Int) {}
    }

    private var callback: Callback? = null
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 최초 연결 성공 시 저장 → 이후 재연결 시 스캔 없이 직접 connectGatt() 호출 */
    private var knownDevice: BluetoothDevice? = null

    /** 직접 재연결 실패 횟수. 3회 초과 시 스캔으로 폴백 */
    private var directReconnectFails = 0

    /** true면 disconnect() 가 직접 호출된 것 → 자동 재연결 억제 */
    private var intentionalDisconnect = false

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
                knownDevice = result.device   // MAC 주소 캐싱
                result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            this@BleManager.gatt = gatt
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> {
                    directReconnectFails = 0            // 재연결 성공 → 실패 카운터 초기화
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    gatt.requestMtu(512)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    gatt.close()                        // GATT 리소스 해제 — 없으면 재연결 루프 발생
                    this@BleManager.gatt = null
                    if (intentionalDisconnect) {
                        // 사용자가 직접 해제 → 재연결 없이 콜백만
                        intentionalDisconnect = false
                        mainHandler.post { callback?.onDisconnected() }
                        return
                    }
                    val known = knownDevice
                    if (known != null && directReconnectFails < 3) {
                        // 알려진 기기 → 스캔 없이 직접 재연결 (스캔 시간 ~5-8초 절약)
                        directReconnectFails++
                        mainHandler.post { callback?.onReconnecting() }
                        mainHandler.postDelayed({
                            if (this@BleManager.gatt == null && !intentionalDisconnect) {
                                known.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                            }
                        }, 800L)
                    } else {
                        // 직접 재연결 3회 실패 또는 알 수 없는 기기 → 스캔으로 폴백
                        directReconnectFails = 0
                        mainHandler.post { callback?.onDisconnected() }
                    }
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
            // onConnected는 DATA CCCD 완료 후 onDescriptorWrite에서 호출

            // 배터리 서비스 구독 (연결 직후 1초 후)
            mainHandler.postDelayed({
                val bService = gatt.getService(BATTERY_SERVICE_UUID)
                val bChar    = bService?.getCharacteristic(BATTERY_LEVEL_UUID) ?: return@postDelayed
                gatt.setCharacteristicNotification(bChar, true)
                val bCccd = bChar.getDescriptor(CCCD_UUID) ?: return@postDelayed
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(bCccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    bCccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(bCccd)
                }
            }, 1000)
        }

        // CCCD 쓰기 완료 처리
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            when (descriptor.characteristic?.uuid) {
                DATA_UUID -> {
                    // DATA CCCD 완료 — 배터리 CCCD가 끝난 뒤 onConnected()를 호출하므로 여기선 아무것도 하지 않음
                }
                BATTERY_LEVEL_UUID -> {
                    // 배터리 CCCD 완료 → GATT 충돌 없는 시점이므로 여기서 onConnected() 호출
                    mainHandler.post { callback?.onConnected() }
                    // 현재 배터리 값 즉시 읽기
                    val bChar = gatt.getService(BATTERY_SERVICE_UUID)
                        ?.getCharacteristic(BATTERY_LEVEL_UUID) ?: return
                    gatt.readCharacteristic(bChar)
                }
            }
        }

        // 배터리 즉시 읽기 결과 처리
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == BATTERY_LEVEL_UUID) {
                val raw = characteristic.value?.getOrNull(0) ?: return
                val level = raw.toInt() and 0xFF
                mainHandler.post { callback?.onBatteryLevel(level) }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            if (characteristic.uuid == BATTERY_LEVEL_UUID) {
                val raw = value.getOrNull(0) ?: return
                val level = raw.toInt() and 0xFF
                mainHandler.post { callback?.onBatteryLevel(level) }
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            when (characteristic.uuid) {
                BATTERY_LEVEL_UUID -> {
                    val raw = characteristic.value?.getOrNull(0) ?: return
                    val level = raw.toInt() and 0xFF
                    mainHandler.post { callback?.onBatteryLevel(level) }
                }
                DATA_UUID -> parseAndDeliver(characteristic.value?.toString(Charsets.UTF_8) ?: return)
            }
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            when (characteristic.uuid) {
                BATTERY_LEVEL_UUID -> {
                    val raw = value.getOrNull(0) ?: return
                    val level = raw.toInt() and 0xFF
                    mainHandler.post { callback?.onBatteryLevel(level) }
                }
                DATA_UUID -> parseAndDeliver(value.toString(Charsets.UTF_8))
            }
        }
    }

    private fun parseAndDeliver(raw: String) {
        try {
            val trimmed = raw.trim()

            if (trimmed == "훈련 시작") {
                val empty = floatArrayOf(0f, 0f, 0f)
                val press   = SensorPacket(empty, empty, empty, empty, empty, empty, true, true)
                val release = SensorPacket(empty, empty, empty, empty, empty, empty, false, false)
                mainHandler.post { callback?.onPacket(press) }
                mainHandler.postDelayed({ callback?.onPacket(release) }, 100)
                return
            }

            val btn1 = trimmed == "오른쪽 버튼"
            val btn2 = trimmed == "왼쪽 버튼"
            if (btn1 || btn2) {
                val empty = floatArrayOf(0f, 0f, 0f)
                val press   = SensorPacket(empty, empty, empty, empty, empty, empty, btn1, btn2)
                val release = SensorPacket(empty, empty, empty, empty, empty, empty, false, false)
                mainHandler.post { callback?.onPacket(press) }
                mainHandler.postDelayed({ callback?.onPacket(release) }, 100)
                return
            }

            val parts = trimmed.split("#")
            if (parts.size < 2) return
            val h = parseSide(parts[0]) ?: return
            val t = parseSide(parts[1]) ?: return
            val btnParts = parts.getOrNull(2)?.split(",")
            val b1 = btnParts?.getOrNull(0)?.trim() == "1"
            val b2 = btnParts?.getOrNull(1)?.trim() == "1"
            val packet = SensorPacket(h[0], h[1], h[2], t[0], t[1], t[2], b1, b2)
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
        val known = knownDevice
        if (known != null) {
            // 이미 알고 있는 기기 → 스캔 없이 직접 연결 시도
            known.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            return
        }
        scanning = true
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, settings, scanCallback)
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    fun sendControl(value: Int) {
        val g    = gatt ?: return
        // 측정 시작 시 HIGH 우선순위 재요청 — 버즈 등 다른 BLE 기기 연결 후 우선순위가 낮아지는 문제 방지
        if (value == 1) g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
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
        intentionalDisconnect = true    // 자동 재연결 억제
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    val isConnected: Boolean get() = gatt != null
}
