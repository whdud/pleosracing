# 별마중

차가 멈춘 그 자리에서, 차가 바라보는 방향의 밤하늘을 보여주고 별자리 이야기를 들려주는 Pleos Connect 천체 관측 앱입니다.

## 주요 기능 (프로토타입 v0.1)

- **지금 이 자리의 밤하늘**: 현재 시각과 위치로 별 8,920개(6.5등급까지), 88개 별자리, 달(위상 포함), 수성·금성·화성·목성·토성의 위치를 실시간으로 계산합니다
- **실제 같은 하늘**: NASA 은하수 지도로 그린 은하수, 지평선의 대기 빛과 도시 불빛, 달빛에 밝아지는 하늘, 밝은 별의 빛번짐, 언덕과 나무 실루엣. 장소의 하늘 밝기(도시/시골)에 따라 보이는 별 수가 달라집니다
- **별자리 선**: 기본은 꺼져 있어 자연스러운 하늘로 보이고, 버튼으로 켜거나 이야기를 고르면 해당 별자리만 강조됩니다
- **차 방향 기준 보기**: FusedLocation SDK의 차량 heading으로 앞유리 너머 방향의 하늘을 보여줍니다
- **선루프 보기**: 머리 위 하늘 전체(천정 시점). 선루프 가림막이 열리면 자동으로 전환하고, 이 모드를 켜면 가림막을 엽니다
- **관측 모드**: 화면을 붉은색으로 바꾸고 실내등을 끄고 무드등을 최소 밝기로 낮춥니다. 끄면 원래대로 돌아갑니다
- **별자리 이야기 4편**: 견우와 직녀, 북두칠성이 된 일곱 형제, 오리온과 전갈, 페르세우스와 안드로메다 공주. 고르면 해당 별자리를 강조해서 찾아주고, 지금 안 보이면 잘 보이는 날짜와 시간으로 이동합니다
- **별·행성·달 정보**: 누르면 이름, 밝기, 거리("이 빛은 25년 전에 출발했어요"), 색과 온도를 쉬운 말로 설명합니다
- **시간 이동**: ±1시간, 빠르게 돌려보기, 낮에는 "오늘 밤 9시 하늘 보기"
- **주행 중 사용 제한**: CarUxRestrictions로 주행 중에는 화면을 가리고, 정차하면 보던 하늘을 이어서 보여줍니다

## 구조

| 모듈 | 내용 |
|---|---|
| `sky/` | Kotlin Multiplatform(JVM + JS) 순수 Kotlin 엔진: 천문 계산(항성시, 세차, 태양·달·행성), 별 목록, 투영(스테레오 투영), 화면 그리기 목록 생성, 별자리 이야기. JVM에서 단위 테스트와 미리보기 이미지 생성 |
| `app/` | Android Automotive 앱: 하늘 그리기(Canvas), 터치 조작, Pleos SDK 연동, 화면 구성 |
| `tools/` | 별·별자리 데이터 생성 스크립트 |

### 사용하는 Pleos Connect SDK / API

| 기능 | API | 권한 |
|---|---|---|
| 차량 위치·방향 | FusedLocation SDK 1.1.3 `registerFusedLocationCallback()` (위도, 경도, heading) | `pleos.car.permission.FUSED_LOCATION` |
| 관측 모드 조명 | Vehicle SDK 2.3.2 Light `setCabinLightSwitch()`, `setAmbientLightBrightness()` | `pleos.car.permission.CAR_LIGHTS`, `CONTROL_CAR_LIGHTS` |
| 선루프 가림막 | Vehicle SDK 2.3.2 Window `ROOF_TOP_1` `registerWindowBlindPosition()`, `moveWindowBlind()` | `pleos.car.permission.CAR_WINDOWS`, `CONTROL_CAR_WINDOWS` |
| 주행 중 제한 | AAOS `CarUxRestrictionsManager` | - |

모든 차량 기능은 capability를 먼저 확인하고, 지원하지 않는 차량(또는 에뮬레이터)에서는 조용히 건너뜁니다.
차 위치를 받을 수 없으면 "장소" 메뉴의 프리셋(서울, 영양 국제밤하늘보호공원, 화천 조경철천문대, 제주)으로 동작합니다.

## 실행 방법

### 준비
- Android Studio, Android SDK API 34, JDK 17 이상
- Pleos Connect Emulator (API 34, Pleos Connect v2.0), Cold boot로 실행

### 빌드와 설치
```bash
cd byeolmajung
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

### 웹 데모 (브라우저에서 바로 조작)
같은 하늘 엔진(`sky/`)을 Kotlin/JS로 컴파일한 웹 버전입니다. 차 전용 기능(차 위치·방향, 조명, 선루프)은 장소 선택과 화면 효과로 대신합니다.
```bash
./gradlew :sky:webDemo   # 결과: sky/build/web (index.html, byeolmajung.js, sky/ 데이터)
```

### Pleos 에뮬레이터 없이 PC에서 보기 (Windows)
Pleos 이미지를 받기 전에도 구글의 일반 Android Automotive 에뮬레이터로 화면을 볼 수 있습니다.
1. [Android Studio](https://developer.android.com/studio)를 설치합니다.
2. **Device Manager → Create Virtual Device → Automotive** 탭에서 `Automotive (1408p landscape)` 같은 가로형 기기를 고릅니다.
3. 시스템 이미지는 **API 34, x86_64** (Android Automotive)를 받아서 선택하고 만듭니다.
4. 에뮬레이터를 켠 뒤, APK 파일을 에뮬레이터 창으로 **끌어다 놓으면** 설치됩니다.
5. 앱 목록에서 **별마중**을 실행합니다. 차 위치가 없으니 "장소" 메뉴에서 장소를 고르세요.
   - 시간이 이상하면 에뮬레이터 설정에서 시간대를 서울로 바꿉니다.
   - Pleos 전용 기능(차 위치·방향, 조명, 선루프)은 일반 에뮬레이터에서는 동작하지 않고 건너뜁니다.

### 에뮬레이터에서 확인할 것
- **Virtual Console → Motion → Gear Selection**을 D로 바꾸면 주행 제한 화면이 뜨고, P로 돌리면 보던 하늘로 돌아오는지
- 차량 위치가 오는지, 안 오면 "장소" 메뉴로 바꿔서 확인
- "관측 모드"를 켤 때 Virtual Console의 Cabin Lights, Ambient Brightness가 바뀌는지

### 테스트와 미리보기 (에뮬레이터 없이)
```bash
./gradlew :sky:jvmTest            # 천문 계산 테스트 + 하늘 미리보기: sky/build/preview/*.png
./gradlew :app:testDebugUnitTest  # 실제 앱 화면 스크린샷 (Robolectric): app/build/screenshots/*.png
```
앱 스크린샷 테스트는 실제 `MainActivity`를 띄워 장소 선택, 별자리 선, 이야기, 선루프 보기, 관측 모드를 차례로 눌러 보며 화면을 저장합니다. Pleos 서비스가 없는 환경이라 일반 에뮬레이터와 같은 상태로 동작합니다.
천문 계산은 Meeus 예제(항성시), 북극성 고도 = 위도, 2026년 춘분·하지 태양 적위, 2026년 3월 보름달·삭으로 검증합니다.

## 확인이 필요한 가정

- **선루프 가림막 값의 방향**: 문서에 열림/닫힘 방향이 나와 있지 않아서, AAOS `WINDOW_POS`처럼 최댓값이 "완전히 열림", `MoveControl.FORWARD`가 "여는 방향"이라고 가정했습니다 (`VehicleServices.kt`). 에뮬레이터나 실차에서 확인 후 수정합니다.
- **조명·창문 제어 권한**: Pleos Playground에서 차량 제어 SDK가 "준비 중"으로 표시되어 있어, 현재는 권한이 거부될 수 있습니다. 거부되면 화면만 붉게 바뀝니다.

## 데이터 출처와 라이선스

| 데이터 | 출처 | 라이선스 |
|---|---|---|
| 별 위치·밝기·색·거리 (`stars.csv`) | [HYG Database v4.4](https://codeberg.org/astronexus/hyg) (astronexus) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| 별자리 선과 이름 (`constellations.txt`) | [d3-celestial](https://github.com/ofrohn/d3-celestial) (Olaf Frohn) | BSD 3-Clause |
| 은하수 배경 (`milkyway.jpg`) | [NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851) `milkyway_2020_4k.exr`를 2048×1024로 줄이고 톤 조정. Credit: NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2: ESA/Gaia/DPAC | NASA 공개 자료 (출처 표기) |
| 행성 궤도 요소 | JPL "Keplerian Elements for Approximate Positions of the Major Planets" (E. M. Standish) | 공개 자료 |

`stars.csv`는 HYG 데이터베이스를 가공한 것이므로 CC BY-SA 4.0을 따릅니다. 별자리 이야기와 앱 코드, 아이콘은 직접 작성했습니다.

데이터를 다시 만들려면 위 출처에서 `hyg_v44.csv`, `constellations.lines.json`, `constellations.json`을 받은 뒤 실행합니다.
```bash
python3 tools/prepare_sky_data.py <받은 폴더> app/src/main/assets/sky
python3 tools/prepare_milkyway.py <받은 폴더>/milkyway_2020_4k.exr app/src/main/assets/sky/milkyway.jpg   # ffmpeg, numpy, Pillow 필요
```
