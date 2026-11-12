# 케플러-22b

차를 세운 자리에서 차의 위치와 방향에 맞춘 밤하늘을 보여 주고, 별과 별자리 이야기를 소리로 들려주는 Pleos Connect 별 관측 앱입니다.

## APK

[`apk/조영환_케플러-22b_release.apk`](apk/조영환_케플러-22b_release.apk) (release 빌드, versionName 0.1.0, 패키지 `com.kepler22b.app`)

공모전 시연용이라 별도 출시 키 대신 Android 디버그 키로 서명했습니다. release 빌드라 디버그 모드는 꺼져 있습니다.

## 실행 방법

1. Pleos Connect 에뮬레이터를 Cold boot로 켭니다.
2. APK를 설치합니다.
   ```bash
   adb install -r apk/조영환_케플러-22b_release.apk
   ```
3. 앱 목록이나 하단 앱 바에서 **케플러-22b**를 실행합니다.
4. 처음 실행하면 차 위치 권한(`pleos.car.permission.FUSED_LOCATION`)을 묻습니다. 허용하면 차 위치와 방향으로 하늘을 맞춥니다. 거부하면 화면 왼쪽 위의 장소 이름("내 차 위치")을 눌러 프리셋 장소(서울, 영양, 화천, 제주)를 고를 수 있습니다.

소스에서 직접 빌드하려면:
```bash
cd kepler22b
./gradlew :app:assembleRelease   # 결과: app/build/outputs/apk/release/app-release.apk
```

## 테스트 환경과 필요한 설정

| 항목 | 내용 |
|---|---|
| 에뮬레이터 | Pleos Connect Emulator **v2.1.0** (Android 14, API 34, `android-automotive`, x86_64) |
| 화면 규격 | **2560 × 1440**, 160 dpi (가로형). 앱은 오른쪽 메인 화면 영역에 표시됩니다 |
| 개발 PC | Windows 11, Android Studio, JDK 21 (Gradle 8.14는 JDK 25에서 실행되지 않음) |
| 사용 SDK | Pleos Vehicle 2.3.2, FusedLocation 1.1.3, TextToSpeech 3.3.1, Gleo Interface, AAOS CarUxRestrictions |

- **권한:** 차 위치는 첫 실행 때 허용하면 됩니다. 조명·창문·음성(TTS) 권한은 매니페스트에 선언되어 있어 따로 설정할 것이 없습니다.
- **Virtual Console:** `adb forward tcp:8088 tcp:8088` 후 http://localhost:8088 에서 기어(P/D)와 조명을 바꿔 볼 수 있습니다. 기어를 D로 바꾸면 운전 중 잠금 화면이, P로 돌리면 보던 하늘이 다시 나옵니다. "관측 모드"를 켜면 실내등·독서등·무드등이 꺼지고, 끄면 원래대로 돌아옵니다.
- **에뮬레이터 제약:** 에뮬레이터에는 선루프 가림막이 없어 "선루프" 버튼은 머리 위 하늘 보기 화면 전환만 확인할 수 있습니다.
- **소리:** "설명 듣기"는 Pleos TextToSpeech 온디바이스 엔진으로 읽습니다. 에뮬레이터 소리는 에뮬레이터를 켤 때의 Windows 기본 출력 장치로 나갑니다.
- **시간:** 낮에는 별이 거의 보이지 않으므로, 화면 위 "오늘 밤 9시 하늘" 버튼이나 아래의 −1시간/+1시간으로 밤하늘을 볼 수 있습니다.

## 저장소 구성

| 경로 | 내용 |
|---|---|
| [`kepler22b/`](kepler22b/) | 앱 소스 (Kotlin, Android Automotive). 기능, 구조, SDK 사용 내역, 테스트 방법은 [kepler22b/README.md](kepler22b/README.md) |
| [`apk/`](apk/) | 제출용 release APK |
| [`input-probe/`](input-probe/) | 개발 중 차량 신호가 앱까지 들어오는지 확인한 진단 앱 |

## 출처와 라이선스

별 데이터와 이미지 일부는 아래 공개 자료를 가공해 썼습니다. 자세한 내용은 [kepler22b/README.md의 데이터 출처와 라이선스](kepler22b/README.md#데이터-출처와-라이선스)에 있고, 라이선스 전문은 APK 안(`kepler22b/app/src/main/assets/licenses/`)에도 들어 있습니다.

| 자료 | 출처 | 라이선스 |
|---|---|---|
| 별 위치·밝기 데이터 | HYG Database v4.4 (astronexus) | CC BY-SA 4.0 |
| 별자리 선과 이름 | d3-celestial (Olaf Frohn) | BSD 3-Clause |
| 별자리 그림 | Stellarium 서양 별자리 그림 (Johan Meuris) | Free Art License 1.3 |
| 은하수 배경·앱 아이콘 | NASA SVS Deep Star Maps 2020. Credit: NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2: ESA/Gaia/DPAC | NASA 공개 자료 |
| 달 표면 | NASA SVS CGI Moon Kit (LRO) | 퍼블릭 도메인 |
| 행성 궤도 요소 | NASA JPL (E. M. Standish) | 공개 자료 |
| 글꼴 | Hahmlet | SIL Open Font License 1.1 |
| Kotlin 표준 라이브러리 | JetBrains | Apache License 2.0 |
