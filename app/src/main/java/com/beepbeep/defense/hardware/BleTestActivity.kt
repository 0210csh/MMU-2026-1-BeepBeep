package com.beepbeep.defense.hardware

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.beepbeep.defense.R
import java.util.*

class BleTestActivity : AppCompatActivity() {

    private val SERVICE_UUID            = UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb")
    private val DATA_CHAR_UUID          = UUID.fromString("00002A56-0000-1000-8000-00805f9b34fb")
    private val CONTROL_CHAR_UUID       = UUID.fromString("00002A57-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CONFIG_UUID      = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private val BATTERY_SERVICE_UUID    = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    private val BATTERY_LEVEL_CHAR_UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    private val MEASURE_DURATION_MS = 5000L

    private var bluetoothGatt: BluetoothGatt? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private lateinit var tvStatus:       TextView
    private lateinit var tvMeasureTimer: TextView
    private lateinit var tvHEuler:       TextView
    private lateinit var tvHGyro:        TextView
    private lateinit var tvHAcc:         TextView
    private lateinit var tvTEuler:       TextView
    private lateinit var tvTGyro:        TextView
    private lateinit var tvTAcc:         TextView
    private lateinit var tvBatteryLevel: TextView
    private lateinit var tvButtonEvent:  TextView
    private lateinit var btnConnect:     Button
    private lateinit var btnSendData:    Button

    private var isScanning  = false
    private var isMeasuring = false
    private val handler     = Handler(Looper.getMainLooper())
    private var countDownTimer: CountDownTimer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ble_test)

        tvStatus       = findViewById(R.id.tv_status)
        tvMeasureTimer = findViewById(R.id.tv_measure_timer)
        tvHEuler       = findViewById(R.id.tv_h_euler)
        tvHGyro        = findViewById(R.id.tv_h_gyro)
        tvHAcc         = findViewById(R.id.tv_h_acc)
        tvTEuler       = findViewById(R.id.tv_t_euler)
        tvTGyro        = findViewById(R.id.tv_t_gyro)
        tvTAcc         = findViewById(R.id.tv_t_acc)
        tvBatteryLevel = findViewById(R.id.tv_battery_level)
        tvButtonEvent  = findViewById(R.id.tv_button_event)
        btnConnect     = findViewById(R.id.btn_connect)
        btnSendData    = findViewById(R.id.btn_send_data)

        btnConnect.setOnClickListener { checkPermissionsAndScan() }

        btnSendData.setOnClickListener {
            if (isMeasuring) {
                sendControl(0)
                stopMeasureUI()
            } else {
                sendControl(1)
                startMeasureUI()
            }
        }
    }

    private fun startMeasureUI() {
        isMeasuring = true
        btnSendData.text = "측정 중지"
        countDownTimer?.cancel()
        countDownTimer = object : CountDownTimer(MEASURE_DURATION_MS, 100) {
            override fun onTick(millisUntilFinished: Long) {
                tvMeasureTimer.text = "남은 시간: %.1f초".format(millisUntilFinished / 1000f)
            }
            override fun onFinish() {
                stopMeasureUI()
            }
        }.start()
    }

    private fun stopMeasureUI() {
        isMeasuring = false
        countDownTimer?.cancel()
        countDownTimer = null
        btnSendData.text = "측정 시작 (5초)"
        tvMeasureTimer.text = ""
    }

    @SuppressLint("MissingPermission")
    private fun sendControl(value: Int) {
        val gatt = bluetoothGatt ?: return
        val ctrl = gatt.getService(SERVICE_UUID)?.getCharacteristic(CONTROL_CHAR_UUID) ?: return
        val data = byteArrayOf(value.toByte())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(ctrl, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            @Suppress("DEPRECATION")
            ctrl.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(ctrl)
        }
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissions.all { ActivityCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        } else {
            ActivityCompat.requestPermissions(this, permissions, 101)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        tvStatus.text = "상태: 장치 찾는 중..."
        isScanning = true
        handler.postDelayed({ stopBleScan() }, 10000)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        if (isScanning && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (deviceName == "SmartBat_Pro") {
                stopBleScan()
                connectToDevice(result.device)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread {
                    tvStatus.text = "상태: 연결됨"
                    btnSendData.isEnabled = true
                }
                if (ActivityCompat.checkSelfPermission(this@BleTestActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.requestMtu(512)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread {
                    tvStatus.text = "상태: 연결 끊김"
                    btnSendData.isEnabled = false
                    stopMeasureUI()
                }
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        @SuppressLint("MissingPermission")
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "MTU 확장 성공: $mtu")
                if (ActivityCompat.checkSelfPermission(this@BleTestActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return
            val service  = gatt.getService(SERVICE_UUID) ?: return
            val dataChar = service.getCharacteristic(DATA_CHAR_UUID) ?: return
            if (ActivityCompat.checkSelfPermission(this@BleTestActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            gatt.setCharacteristicNotification(dataChar, true)
            val descriptor = dataChar.getDescriptor(CLIENT_CONFIG_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(descriptor)
            }
            handler.postDelayed({ setupBatteryNotification(gatt) }, 1000)
        }

        @SuppressLint("MissingPermission")
        private fun setupBatteryNotification(gatt: BluetoothGatt) {
            if (ActivityCompat.checkSelfPermission(this@BleTestActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
            val bChar = gatt.getService(BATTERY_SERVICE_UUID)?.getCharacteristic(BATTERY_LEVEL_CHAR_UUID) ?: return
            gatt.setCharacteristicNotification(bChar, true)
            val desc = bChar.getDescriptor(CLIENT_CONFIG_UUID) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION")
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                gatt.writeDescriptor(desc)
            }
            Log.d("BLE", "배터리 알림 설정 완료")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            when (characteristic.uuid) {
                BATTERY_LEVEL_CHAR_UUID -> {
                    val level = characteristic.getIntValue(BluetoothGattCharacteristic.FORMAT_UINT8, 0) ?: return
                    runOnUiThread { updateBattery(level) }
                }
                DATA_CHAR_UUID -> {
                    parseAndDisplay(characteristic.getStringValue(0) ?: return)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            when (characteristic.uuid) {
                BATTERY_LEVEL_CHAR_UUID -> {
                    val level = value.firstOrNull()?.toInt()?.and(0xFF) ?: return
                    runOnUiThread { updateBattery(level) }
                }
                DATA_CHAR_UUID -> {
                    parseAndDisplay(value.toString(Charsets.UTF_8))
                }
            }
        }
    }

    private fun updateBattery(level: Int) {
        tvBatteryLevel.text = "배터리: $level%"
        tvBatteryLevel.setTextColor(if (level <= 20) Color.RED else Color.BLACK)
    }

    private fun parseAndDisplay(packet: String) {
        runOnUiThread {
            try {
                if (!packet.contains("|") && !packet.contains("#")) {
                    tvButtonEvent.text = "최근 버튼 입력: $packet"
                    return@runOnUiThread
                }
                val sensors = packet.trim().split("#")
                if (sensors.size < 2) return@runOnUiThread

                val hParts = sensors[0].split("|")
                if (hParts.size >= 3) {
                    tvHEuler.text = "오일러 각   : ${hParts[0]}"
                    tvHGyro.text  = "자이로스코프 : ${hParts[1]}"
                    tvHAcc.text   = "가속도      : ${hParts[2]}"
                }

                val tParts = sensors[1].split("|")
                if (tParts.size >= 3) {
                    tvTEuler.text = "오일러 각   : ${tParts[0]}"
                    tvTGyro.text  = "자이로스코프 : ${tParts[1]}"
                    tvTAcc.text   = "가속도      : ${tParts[2]}"
                }
            } catch (e: Exception) {
                Log.e("PARSE_ERROR", "패킷 파싱 오류: $packet")
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101 && grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            startBleScan()
        }
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        countDownTimer?.cancel()
        if (isMeasuring) sendControl(0)
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
