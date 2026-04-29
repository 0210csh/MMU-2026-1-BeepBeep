package kr.ac.ble_connect // 본인의 패키지명 확인

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Log.d
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val SERVICE_UUID = UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb")
    private val DATA_CHAR_UUID = UUID.fromString("00002A56-0000-1000-8000-00805f9b34fb")
    private val CLIENT_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private var bluetoothGatt: BluetoothGatt? = null
    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }

    private lateinit var tvStatus: TextView
    private lateinit var tvHEuler: TextView
    private lateinit var tvHGyro: TextView
    private lateinit var tvHAcc: TextView

    private lateinit var tvTEuler: TextView
    private lateinit var tvTGyro: TextView
    private lateinit var tvTAcc: TextView

    private lateinit var btnSendData: Button
    private var isScanning = false
    private val handler = Handler(Looper.getMainLooper())


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        tvHEuler = findViewById(R.id.tv_h_euler); tvHGyro = findViewById(R.id.tv_h_gyro); tvHAcc = findViewById(R.id.tv_h_acc)
        tvTEuler = findViewById(R.id.tv_t_euler); tvTGyro = findViewById(R.id.tv_t_gyro); tvTAcc = findViewById(R.id.tv_t_acc)

        findViewById<Button>(R.id.btn_connect).setOnClickListener { checkPermissionsAndScan() }



        btnSendData = findViewById(R.id.btn_send_data) // XML에 추가할 버튼 ID

        btnSendData.setOnClickListener {
            sendDataToBoard(1) // 문자열 "1"이 아닌 숫자 1 전송
        }

    }
    @SuppressLint("MissingPermission")
    private fun sendDataToBoard(value: Int) { // 매개변수를 Int로 변경
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(UUID.fromString("0000180C-0000-1000-8000-00805f9b34fb"))
        // controlCharacteristic UUID인 2A57로 전송해야 함!
        val controlChar = service?.getCharacteristic(UUID.fromString("00002A57-0000-1000-8000-00805f9b34fb"))

        if (controlChar != null) {
            val data = byteArrayOf(value.toByte()) // 숫자 1을 1바이트로 변환

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(controlChar, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                controlChar.value = data
                gatt.writeCharacteristic(controlChar)
            }
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

    private fun startBleScan() {
        tvStatus.text = "상태: 장치 찾는 중..."
        isScanning = true

        // 10초 후 스캔 자동 중지
        handler.postDelayed({ stopBleScan() }, 10000)

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bluetoothLeScanner?.startScan(scanCallback)
        }
    }

    private fun stopBleScan() {
        if (isScanning && ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            isScanning = false
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name ?: "Unknown"
            if (deviceName == "SmartBat_Pro") {
                stopBleScan()
                connectToDevice(result.device)
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                runOnUiThread { tvStatus.text = "상태: 연결됨" }
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.requestMtu(512)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                runOnUiThread { tvStatus.text = "상태: 연결 끊김" }
                bluetoothGatt?.close()
                bluetoothGatt = null
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d("BLE", "MTU 확장 성공: $mtu")
                // MTU가 성공적으로 변경된 후 서비스를 찾아야 안전합니다.
                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                    gatt.discoverServices()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(SERVICE_UUID)
            val characteristic = service?.getCharacteristic(DATA_CHAR_UUID)

            if (characteristic != null && ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                // 알림 설정
                gatt.setCharacteristicNotification(characteristic, true)
                val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_UUID)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val fullPacket = characteristic.getStringValue(0) ?: return

            // 데이터 파싱 시작
            // packet 예: "hE|hG|hA#tE|tG|tA"
            runOnUiThread {
                try {
                    val sensors = fullPacket.split("#") // 손잡이와 끝 센서 분리
                    if (sensors.size >= 2) {
                        // 1. 손잡이 센서 파싱
                        val hParts = sensors[0].split("|")
                        if (hParts.size >= 3) {
                            tvHEuler.text = "오일러 각 값 : ${hParts[0]}"
                            tvHGyro.text = "자이로 스코프 값 : ${hParts[1]}"
                            tvHAcc.text = "가속도 값 : ${hParts[2]}"
                        }

                        // 2. 배트 끝 센서 파싱
                        val tParts = sensors[1].split("|")
                        if (tParts.size >= 3) {
                            tvTEuler.text = "오일러 각 값 : ${tParts[0]}"
                            tvTGyro.text = "자이로 스코프 값 : ${tParts[1]}"
                            tvTAcc.text = "가속도 값 : ${tParts[2]}"
                        }
                    }
                } catch (e: Exception) {
                    Log.e("PARSE_ERROR", "Data format error: $fullPacket")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 메모리 누수 방지를 위한 필수 처리
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            bluetoothGatt?.disconnect()
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}