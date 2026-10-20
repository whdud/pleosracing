# Pleos Input Probe

플레오스 커넥트(Pleos Connect) 차량에서 **레이싱 게임 입력으로 쓸 수 있는 신호가 앱까지 실제로 들어오는지** 확인하는 진단 앱입니다.
화면 한 곳에 각 신호의 지원 여부, 값 범위, 현재 값, 갱신 주기(Hz)를 실시간으로 보여주고, 같은 내용을 logcat(`PleosProbe` 태그)에도 남깁니다.

## 확인하는 항목

| 섹션 | 항목 | 출처 | 권한 |
|---|---|---|---|
| Pleos Vehicle SDK | 조향각 (앞바퀴 기준, 도, 왼쪽이 음수) | `SteeringWheel.registerSteeringWheelAngle` | `pleos.car.permission.CAR_DRIVE` (normal) |
| | 브레이크 페달 밟음/뗌 (Boolean) | `Brake.registerBrakePedalStatus` | 위와 같음 |
| AAOS CarPropertyManager | **브레이크 페달 압력 %** | `BRAKE_PEDAL_COMPRESSION_PERCENTAGE` (0x11600310) | `READ_CAR_PEDALS` (dangerous) |
| | 액셀 페달 압력 % (참고용) | `ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE` (0x1160030f) | 위와 같음 |
| | 조향각 | `PERF_STEERING_ANGLE` | `READ_CAR_STEERING` |
| | 기어 | `GEAR_SELECTION`, `CURRENT_GEAR` | `CAR_POWERTRAIN` |
| | 주차 브레이크 | `PARKING_BRAKE_ON` | `CAR_POWERTRAIN` |
| | 차속 / 시동 상태 / RPM | `PERF_VEHICLE_SPEED`, `IGNITION_STATE`, `ENGINE_RPM` | `CAR_SPEED`, `CAR_POWERTRAIN`, `CAR_ENGINE_DETAILED` |
| 주행 상태 | 주행 제한 여부 | `CarUxRestrictionsManager` | - |
| 버튼 입력 | 앱(Activity)으로 들어오는 모든 키 | `dispatchKeyEvent` | - |
| | 미디어 키 (트랙 이동 버튼 등) | `MediaSession` (버튼으로 켜고 끔) | - |

추가로, 앱이 접근할 수 있는 **모든 차량 속성 목록**(제조사 전용 vendor 속성 포함)을 시작할 때 로그로 출력합니다.
문서에 없는 신호를 찾는 데 씁니다.

### 브레이크 % 관련 주의

페달 압력 % 속성은 AAOS 15(API 35)에서 공개된 속성인데, Pleos Connect는 Android 14(API 34) 기반입니다.
그래서 SDK 상수 대신 원본 ID로 조회합니다. 차량의 VHAL이 이 속성을 구현하지 않았으면 `미지원 또는 권한 없음`으로 표시됩니다.
Virtual Console의 Brake Pedal 슬라이더(0~100%)를 움직였을 때 이 값이 따라 변하는지가 핵심 확인 포인트입니다.

## 빌드

준비물: Android Studio(또는 Android SDK API 34), JDK 17 이상

```bash
cd input-probe
./gradlew assembleDebug
# 결과물: app/build/outputs/apk/debug/app-debug.apk
```

Pleos Vehicle SDK(`ai.pleos.playground:Vehicle:2.3.2`)는 Pleos Maven 저장소에서 받으며, 이미 `settings.gradle.kts`에 설정돼 있습니다.

## 에뮬레이터에서 테스트

1. Pleos Connect Emulator(API 34, Pleos Connect v2.0)를 **Cold boot**로 실행합니다. Quick boot로 실행하면 VHAL이 동작하지 않습니다.
2. 앱을 설치하고 실행합니다.
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell monkey -p com.pleosracing.probe -c android.intent.category.LAUNCHER 1
   ```
3. 권한 요청 팝업이 뜨면 모두 허용합니다.
4. **Pleos Connect Virtual Console → Emulation Controls → Motion**에서 값을 바꿔가며 화면을 확인합니다.
   - Steering Angle (-540~540°)
   - Brake Pedal (0~100%): Boolean 행과 % 행이 각각 어떻게 반응하는지 비교합니다
   - Gear Selection (P/R/N/D)
5. 버튼 입력을 테스트합니다.
   ```bash
   adb shell input keyevent KEYCODE_MEDIA_NEXT
   adb shell input keyevent KEYCODE_MEDIA_PREVIOUS
   adb shell input keyevent KEYCODE_VOLUME_UP
   ```
   화면의 **미디어 키 수신 켜기**를 누른 상태와 끈 상태를 각각 비교합니다.
6. 전체 로그를 저장합니다.
   ```bash
   adb logcat -s PleosProbe > probe-log.txt
   ```

## 실차에서

실차에 설치할 수 있게 되면 같은 APK를 그대로 실행하면 됩니다.
특히 아래 항목을 기록합니다.

- 조향각: 핸들을 끝까지 돌렸을 때의 값(앞바퀴 기준 최대 각도), 갱신 주기(Hz)
- 브레이크 %: 지원 여부, 살짝 밟았을 때와 끝까지 밟았을 때의 값
- 핸들의 트랙 이동, 볼륨, 음성 버튼과 패들 시프트를 눌렀을 때 로그에 키가 찍히는지
- 시동 ON 상태와 시동 없이 전원만 켠(IGN ON) 상태에서 각각 결과가 다른지
