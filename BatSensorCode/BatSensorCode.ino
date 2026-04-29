#include <Wire.h>
#include <Adafruit_Sensor.h>
#include <Adafruit_BNO055.h>
#include <ArduinoBLE.h>

// 센서 객체
Adafruit_BNO055 bnoHandle = Adafruit_BNO055(55, 0x28);
Adafruit_BNO055 bnoTip = Adafruit_BNO055(56, 0x29);

// BLE 설정
BLEService batService("180C"); 
BLEByteCharacteristic controlCharacteristic("2A57", BLERead | BLEWrite | BLENotify);
BLEStringCharacteristic dataCharacteristic("2A56", BLERead | BLENotify, 128);

unsigned long startTime = 0;
bool isMeasuring = false;
const unsigned long MEASURE_DURATION = 5000; 
const unsigned long INTERVAL = 10000;        
unsigned long lastSampleTime = 0;
unsigned long lastSerialPrintTime = 0; // 시리얼 출력 주기 관리용

void startMeasurement() {
  Serial.println("\n========= 측정 시작 =========");
  isMeasuring = true;
  startTime = millis();
  lastSampleTime = micros();
  lastSerialPrintTime = millis(); 
  controlCharacteristic.writeValue(1);
}

void stopMeasurement() {
  Serial.println("\n========= 측정 종료 =========");
  isMeasuring = false;
  controlCharacteristic.writeValue(0);
}

void setup() {
  Serial.begin(115200);
  while (!Serial);

  if (!bnoHandle.begin() || !bnoTip.begin()) {
    Serial.println("BNO055 연결 실패!");
    while (1);
  }

  if (!BLE.begin()) {
    Serial.println("BLE 시작 실패!");
    while (1);
  }

  BLE.setLocalName("SmartBat_Pro");
  BLE.setAdvertisedService(batService);
  batService.addCharacteristic(controlCharacteristic);
  batService.addCharacteristic(dataCharacteristic);
  BLE.addService(batService);
  
  controlCharacteristic.writeValue(0); 
  BLE.advertise();
  Serial.println("SmartBat_Pro 준비 완료. ('1': 시작, '0': 중지)");
}

void loop() {
  BLEDevice central = BLE.central();

  // 1. 입력 감시 (Serial & BLE)
  if (Serial.available() > 0) {
    char input = Serial.read();
    if (input == '1' && !isMeasuring) startMeasurement();
    else if (input == '0' && isMeasuring) stopMeasurement();
  }

  if (central && central.connected()) {
    if (controlCharacteristic.written()) {
      uint8_t receivedVal = controlCharacteristic.value();
      if (receivedVal == 1 && !isMeasuring) startMeasurement();
      else if (receivedVal == 0 && isMeasuring) stopMeasurement();
    }
  }

  // 2. 측정 및 데이터 처리
  if (isMeasuring) {
    unsigned long currentMicros = micros();
    if (currentMicros - lastSampleTime >= INTERVAL) {
      lastSampleTime = currentMicros;

      // 데이터 추출
      imu::Vector<3> hEuler = bnoHandle.getVector(Adafruit_BNO055::VECTOR_EULER);
      imu::Vector<3> tEuler = bnoTip.getVector(Adafruit_BNO055::VECTOR_EULER);

      imu::Vector<3> hGyro  = bnoHandle.getVector(Adafruit_BNO055::VECTOR_GYROSCOPE);
      imu::Vector<3> tGyro  = bnoTip.getVector(Adafruit_BNO055::VECTOR_GYROSCOPE);

      imu::Vector<3> hAcc   = bnoHandle.getVector(Adafruit_BNO055::VECTOR_ACCELEROMETER);
      imu::Vector<3> tAcc   = bnoTip.getVector(Adafruit_BNO055::VECTOR_ACCELEROMETER);

      // BLE 패킷 생성 및 전송 (100Hz)
      String packet = String(hEuler.x(),1)+","+String(hEuler.y(),1)+","+String(hEuler.z(),1)+"|"+
                      String(hGyro.x(),0)+","+String(hGyro.y(),0)+","+String(hGyro.z(),0)+"|"+
                      String(hAcc.x(),1)+","+String(hAcc.y(),1)+","+String(hAcc.z(),1)+"#"+

                      String(tEuler.x(),1)+","+String(tEuler.y(),1)+","+String(tEuler.z(),1)+"|"+
                      String(tGyro.x(),0)+","+String(tGyro.y(),0)+","+String(tGyro.z(),0)+"|"+
                      String(tAcc.x(),1)+","+String(tAcc.y(),1)+","+String(tAcc.z(),1);
      
      dataCharacteristic.writeValue(packet);

      // 3. 시리얼 모니터 1초마다 출력
      if (millis() - lastSerialPrintTime >= 1000) {
        lastSerialPrintTime = millis();
        
        Serial.print("[Time: "); Serial.print((millis() - startTime)/1000); Serial.println("s]");
        Serial.print("  Handle -> Angle:"); Serial.print(hEuler.x(),1);
        Serial.print(" | Gyro:"); Serial.print(hGyro.z(),0); // Z축 회전 위주
        Serial.print(" | Acc:"); Serial.println(hAcc.magnitude(),1); // 가속도 합계(세기)
        
        Serial.print("  Tip    -> Angle:"); Serial.print(tEuler.x(),1);
        Serial.print(" | Gyro:"); Serial.print(tGyro.z(),0);
        Serial.print(" | Acc:"); Serial.println(tAcc.magnitude(),1);
        Serial.println("--------------------------------------------");
      }

      // 4. 자동 종료
      if (millis() - startTime >= MEASURE_DURATION) {
        stopMeasurement();
      }
    }
  }
}