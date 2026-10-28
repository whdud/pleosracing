# 케플러-22b

차가 멈춘 그 자리에서, 차가 바라보는 방향의 밤하늘을 보여주고 별자리 이야기를 들려주는 Pleos Connect 천체 관측 앱입니다.
이름은 케플러 우주망원경이 태양 같은 별의 "생명가능지대"에서 처음 확인한 행성, 케플러-22b에서 따왔어요.

## 주요 기능 (프로토타입 v0.1)

- **지금 이 자리의 밤하늘**: 현재 시각과 위치로 별 8,920개(6.5등급까지), 88개 별자리, 달(위상 포함), 수성·금성·화성·목성·토성의 위치를 실시간으로 계산합니다
- **실제 같은 하늘**: NASA 은하수 지도로 그린 은하수, 지평선의 대기 빛과 도시 불빛, 달빛에 밝아지는 하늘, 밝은 별의 빛번짐, 언덕과 나무 실루엣. 장소의 하늘 밝기(도시/시골)에 따라 보이는 별 수가 달라집니다
- **사진 같은 달**: NASA 달 탐사선 사진 지도를 구에 입혀, 그날의 위상과 해 방향, 하늘에서의 기울기대로 비춥니다. 어두운 부분은 지구빛으로 아주 희미하게 보입니다
- **별자리 그림**: 버튼으로 켜면 별자리마다 그림(84개)이 실제 별 위치에 맞춰 겹쳐집니다. 이야기를 고르거나 별자리 이름을 누르면 그 별자리 그림만 진하게 보여 줍니다
- **별자리 선**: 기본은 꺼져 있어 자연스러운 하늘로 보이고, 버튼으로 켜거나 이야기를 고르면 해당 별자리만 강조됩니다
- **차 방향 기준 보기**: FusedLocation SDK의 차량 heading으로 앞유리 너머 방향의 하늘을 보여줍니다
- **선루프 보기**: 같은 화면을 고개를 들듯 위로 올려 머리 위 하늘을 보여 줍니다. 선루프 가림막이 열리면 자동으로 전환하고, 이 모드를 켜면 가림막을 엽니다
- **관측 모드**: 실내등, 독서등, 무드등을 모두 꺼서 눈이 어둠에 익게 돕습니다. 끄면 각 조명을 원래 상태로 되돌립니다
- **별자리 이야기 4편**: 견우와 직녀, 북두칠성이 된 일곱 형제, 오리온과 전갈, 페르세우스와 안드로메다 공주. 고르면 해당 별자리를 강조해서 찾아주고, 지금 안 보이면 잘 보이는 날짜와 시간으로 이동합니다
- **별·행성·달 정보**: 누르면 이름, 밝기, 거리("이 빛은 25년 전에 출발했어요"), 색과 온도를 쉬운 말로 설명합니다
- **설명 듣기**: 별이나 별자리를 고르고 "▶ 설명 듣기"를 누르면 지금 어느 쪽 하늘에 있는지와 그 별·별자리 이야기(밝은 별 20개, 별자리 20개)를 읽어 줍니다. 별자리 이야기는 "▶ 이야기 듣기"로 끝까지 읽어 주며 장을 저절로 넘깁니다
- **Gleo 음성 명령** (`app/src/main/assets/schema.json`): "글레오야, 이 별 뭐야?"(`DESCRIBE_SKY_OBJECT`), "목성 어디 있어?"(`FIND_SKY_OBJECT`), "견우와 직녀 이야기 들려줘"(`TELL_CONSTELLATION_STORY`). 결과는 `APP_ACTION_RESPONSE` 브로드캐스트로 Gleo에 돌려줍니다
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
| 관측 모드 조명 | Vehicle SDK 2.3.2 Light `setCabinLightSwitch()`, `setReadingLightSwitch()`, `setAmbientLightSwitch()` (좌석별) | `pleos.car.permission.CAR_LIGHTS`, `CONTROL_CAR_LIGHTS` |
| 선루프 가림막 | Vehicle SDK 2.3.2 Window `ROOF_TOP_1` `registerWindowBlindPosition()`, `moveWindowBlind()` | `pleos.car.permission.CAR_WINDOWS`, `CONTROL_CAR_WINDOWS` |
| 주행 중 제한 | AAOS `CarUxRestrictionsManager` | - |
| 음성 명령 | Gleo Interface: 기능 이름을 action으로 받는 intent-filter, `ACTION_PARAM`, `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` (`GleoBridge.kt`) | - |
| 설명 듣기 | TextToSpeech SDK 3.3.1 온디바이스 모드 `speak()`, 한국어(`ko-KR`) (`Speaker.kt`). Pleos 차와 에뮬레이터에는 Android 기본 음성 엔진이 없음 | `pleos.car.permission.TTS_SERVICE` |

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
같은 하늘 엔진(`sky/`)을 Kotlin/JS로 컴파일한 웹 버전입니다. 차 전용 기능(차 위치·방향, 조명, 선루프)은 장소 선택과 화면 효과로 대신합니다. 설명 듣기는 브라우저 음성 합성으로 읽습니다. Gleo는 차에서 음성으로만 동작하므로 웹 데모에는 없습니다.
```bash
./gradlew :sky:webDemo   # 결과: sky/build/web (index.html, byeolmajung.js, sky/ 데이터)
```

### Pleos 에뮬레이터 없이 PC에서 보기 (Windows)
Pleos 이미지를 받기 전에도 구글의 일반 Android Automotive 에뮬레이터로 화면을 볼 수 있습니다.
1. [Android Studio](https://developer.android.com/studio)를 설치합니다.
2. **Device Manager → Create Virtual Device → Automotive** 탭에서 `Automotive (1408p landscape)` 같은 가로형 기기를 고릅니다.
3. 시스템 이미지는 **API 34, x86_64** (Android Automotive)를 받아서 선택하고 만듭니다.
4. 에뮬레이터를 켠 뒤, APK 파일을 에뮬레이터 창으로 **끌어다 놓으면** 설치됩니다.
5. 앱 목록에서 **케플러-22b**를 실행합니다. 차 위치가 없으니 "장소" 메뉴에서 장소를 고르세요.
   - 시간이 이상하면 에뮬레이터 설정에서 시간대를 서울로 바꿉니다.
   - Pleos 전용 기능(차 위치·방향, 조명, 선루프)은 일반 에뮬레이터에서는 동작하지 않고 건너뜁니다.

### 에뮬레이터에서 확인할 것
- **Virtual Console → Motion → Gear Selection**을 D로 바꾸면 주행 제한 화면이 뜨고, P로 돌리면 보던 하늘로 돌아오는지
- 차량 위치가 오는지, 안 오면 "장소" 메뉴로 바꿔서 확인
- "관측 모드"를 켤 때 Virtual Console의 Cabin Lights, Reading Lights가 꺼지고, 끄면 원래대로 돌아오는지

### 테스트와 미리보기 (에뮬레이터 없이)
```bash
./gradlew :sky:jvmTest            # 천문 계산 테스트 + 하늘 미리보기: sky/build/preview/*.png
./gradlew :app:testDebugUnitTest  # 실제 앱 화면 스크린샷 (Robolectric): app/build/screenshots/*.png
```
앱 스크린샷 테스트는 실제 `MainActivity`를 띄워 장소 선택, 별자리 선, 이야기, Gleo 명령(`DESCRIBE_SKY_OBJECT` 인텐트와 응답 브로드캐스트), 선루프 보기, 관측 모드를 차례로 눌러 보며 화면을 저장합니다. Pleos 서비스가 없는 환경이라 일반 에뮬레이터와 같은 상태로 동작합니다.
천문 계산은 Meeus 예제(항성시), 북극성 고도 = 위도, 2026년 춘분·하지 태양 적위, 2026년 3월 보름달·삭으로 검증합니다.

## 확인이 필요한 가정

- **선루프 가림막 값의 방향**: 문서에 열림/닫힘 방향이 나와 있지 않아서, AAOS `WINDOW_POS`처럼 최댓값이 "완전히 열림", `MoveControl.FORWARD`가 "여는 방향"이라고 가정했습니다 (`VehicleServices.kt`). 에뮬레이터나 실차에서 확인 후 수정합니다.
- **Gleo 파라미터 형식**: Pleos 샘플처럼 `ACTION_PARAM`이 따옴표로 한 번 더 감싸진 JSON 문자열로 와도, 그냥 JSON으로 와도 받도록 했습니다. 실제 Gleo 호출로 확인이 필요합니다.
- **조명·창문 제어 권한**: Pleos Playground에서 차량 제어 SDK가 "준비 중"으로 표시되어 있어, 현재는 권한이 거부될 수 있습니다. 거부되면 "이 차에서는 조명을 끌 수 없어요"라고 안내합니다.

## 데이터 출처와 라이선스

| 데이터 | 출처 | 라이선스 |
|---|---|---|
| 별 위치·밝기·색·거리 (`stars.csv`) | [HYG Database v4.4](https://codeberg.org/astronexus/hyg) (astronexus) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) |
| 별자리 선과 이름 (`constellations.txt`) | [d3-celestial](https://github.com/ofrohn/d3-celestial) (Olaf Frohn) | BSD 3-Clause |
| 은하수 배경 (`milkyway.jpg`) | [NASA SVS Deep Star Maps 2020](https://svs.gsfc.nasa.gov/4851) `milkyway_2020_4k.exr`를 2048×1024로 줄이고 톤 조정. Credit: NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2: ESA/Gaia/DPAC | NASA 공개 자료 (출처 표기) |
| 앱 아이콘 (`mipmap-*/ic_launcher_sky.png`) | 같은 NASA SVS Deep Star Maps 2020의 `starmap_2020_4k_gal.exr`에서 은하 중심을 잘라냄 (`tools/prepare_icon.py`). Credit: NASA/Goddard Space Flight Center Scientific Visualization Studio. Gaia DR2: ESA/Gaia/DPAC | NASA 공개 자료 (출처 표기) |
| 행성 궤도 요소 | JPL "Keplerian Elements for Approximate Positions of the Major Planets" (E. M. Standish) | 공개 자료 |

달 표면 지도(`moon.jpg`)는 NASA LRO(Lunar Reconnaissance Orbiter) 사진으로 만든 NASA SVS CGI Moon Kit의 이미지를 줄인 것입니다 (퍼블릭 도메인, 출처: NASA's Scientific Visualization Studio).

별자리 그림 84점은 Stellarium의 서양 별자리 그림(Johan Meuris 작)을 가공한 것으로 Free Art License 1.3을 따릅니다 (`app/licenses/Stellarium-constellation-art.txt`, 변환: `tools/prepare_art.py`).

글꼴 Hahmlet(시계, 제목, 이야기 본문)은 SIL Open Font License 1.1로 배포되며 라이선스 전문은 `app/licenses/Hahmlet-OFL.txt`에 있습니다.

`stars.csv`는 HYG 데이터베이스를 가공한 것이므로 CC BY-SA 4.0을 따릅니다. 별자리 이야기와 앱 코드, 아이콘은 직접 작성했습니다.

데이터를 다시 만들려면 위 출처에서 `hyg_v44.csv`, `constellations.lines.json`, `constellations.json`을 받은 뒤 실행합니다.
```bash
python3 tools/prepare_sky_data.py <받은 폴더> app/src/main/assets/sky
python3 tools/prepare_milkyway.py <받은 폴더>/milkyway_2020_4k.exr app/src/main/assets/sky/milkyway.jpg   # ffmpeg, numpy, Pillow 필요
python3 tools/prepare_icon.py <받은 폴더>/starmap_2020_4k_gal.exr app/src/main/res   # OpenEXR, numpy, Pillow 필요
```
