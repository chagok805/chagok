# 차곡차곡

**"한 번 채워 달린 612km 중 380km는 가족을 위한 길이었어요."** 차가 주행거리와 정차 신호로 그 길이 누구를 위한 길이었는지 먼저 적어 두고, 틀렸을 때만 고치게 해서 말하지 못한 돌봄 운전을 처음으로 숫자로 보여 주는 Pleos Connect 앱입니다.

- **실행 파일:** [`apk/해달_차곡차곡_release.apk`](apk/해달_차곡차곡_release.apk)
- **시연 영상 (2분 58초):** [`docs/chagok-demo.mp4`](docs/chagok-demo.mp4)

## 주요 기능

| 기능 | 하는 일 |
|---|---|
| 차가 먼저 적기 | 도착해 P에 두면 장소·시간 패턴과 뒷문 신호로 "지우를 위한 길로 적어 뒀어요." D로 세워 두고 내려 줘도 뒷문이나 조수석 문이 열린 곳에서 구간을 나누고, 목적지가 없으면 동 이름과 시간대로 패턴을 찾습니다. 틀렸을 때만 고칩니다 |
| 왕복 묶기, 기다린 시간 | 내려 주고 곧바로 비슷한 거리를 돌아오는 구간은 그 사람을 위한 길로 묶습니다. 정차해 기다린 시간은 다음 구간이 가족을 위한 길일 때만, 3분에서 2시간 사이만 셉니다 |
| 앱이 못 본 거리 | 앱이 꺼져 있던 동안 달린 거리를 다음 실행 때 주행거리 차이로 복구합니다. 아는 왕복 거리로 제안하고, 모르면 미분류로 둡니다 |
| 채워 달린 길 결산 | 주유(충전) 때마다 가족을 위한 길, 내 길, 미분류를 나눠 보여 주고, 주유 뒤 처음 세운 곳에서 음성으로 들려줍니다 |
| 다른 사람이 맡아 준 길 | 고치기에서 "다른 사람이 운전했어요"를 누르면 가족 몫에서 빠지고 결산에 따로 보입니다. 운전자를 자동으로 나누지는 않습니다 |
| 한 줄 건네기 | 운전자가 고른 문장만 QR로 폰에 옮깁니다. 자동 공유는 없습니다 |
| 그날, 차 안에서 | 가족이 정한 날, 온 가족이 탄 차에서 "우리가 함께 달린 길"을 들려주고 부모가 녹음한 한마디를 잇습니다. 앰비언트 조명이 있는 트림에서는 실내 조명도 따뜻해지고, 장면을 닫거나 P를 벗어나면 원래대로 돌아옵니다 |

주행 규제가 걸리면 누를 것 없는 쉬는 화면 한 장만 보여 주고, 음성은 기어가 P일 때만 나옵니다. 기록과 녹음은 차 안에만 저장하고 서버로 보내지 않습니다.

## 실행 방법

### 개발 환경
- Android Studio, JDK 21
- Pleos Connect Emulator v2.1.0 (API 34)

### 빌드와 설치
```bash
# 제출한 실행 파일을 설치
adb install -r apk/해달_차곡차곡_release.apk

# 또는 소스에서 빌드
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 테스트
핵심 기록 로직(`app/src/main/java/dev/chagok/app/ledger/`)은 차 없이 단위 테스트로 검증합니다.
```bash
./gradlew :app:testDebugUnitTest
```

### 에뮬레이터에서 시나리오 재현 (Virtual Console)
1. 앱 설정에서 **시연 데이터 넣기**를 누르고 **넣기**로 확인합니다. 화면에 "시연용 데이터"가 표시되고, 600km 중 368km로 시작합니다.
2. Virtual Console에서 기어를 D로 바꾸고 주행거리를 6km 올립니다. 기어가 D인 채로 왼쪽 뒷문을 열었다 닫고, 주행거리를 다시 6km 올린 뒤 P로 바꿉니다. "지우를 위한 길로 적어 뒀어요."가 나오고, 돌아온 길까지 묶여 612km 중 380km가 됩니다.
3. 배터리 잔량을 15% 이상 한 번에 올리면 충전으로 보고 결산 카드가 나옵니다. 기어를 D로 바꿔 주행거리를 1km 이상 올리고 P로 바꾸면 결산을 음성으로 들려줍니다. **결산 보기**에서 한 줄을 고르면 QR이 나옵니다. 에뮬레이터는 연료 잔량을 지원하지 않아 배터리로 대신합니다.
4. 앱을 종료하고 주행거리를 올린 뒤 다시 실행하면 "그 사이 달린 거리"가 나옵니다.
5. **그날**에서 **들려주기**를 누릅니다. 소리를 들으려면 에뮬레이터 오디오를 켜 둡니다. **녹음**으로 한마디를 남기면 숫자 다음에 이어서 재생됩니다.

참고:
- 단계 사이에 2초쯤 기다립니다. 기어와 주행거리는 서로 다른 경로로 들어옵니다.
- Virtual Console로 주행해도 주행거리는 늘지 않아 직접 올려야 합니다.
- 에뮬레이터는 콜드 부트마다 주행거리가 0으로 돌아갑니다. 앱은 앱을 켠 뒤 20초 안에 시연 데이터를 현재 주행거리에 맞춰 다시 채우며, 이때 녹음은 지워집니다. 직접 쌓은 기록은 그대로 이어집니다.

## 사용한 Pleos SDK와 권한

| SDK | 쓰임 | 권한 |
|---|---|---|
| Vehicle 2.3.2 | 주행거리, 문, 조수석 착좌, 연료·배터리 잔량, 그날 장면의 실내 조명 | `CAR_DRIVE`, `CAR_DOORS`, `CAR_SEATS`, `CAR_ENERGY`, `CAR_LIGHTS`, `CONTROL_CAR_LIGHTS` |
| NaviHelper 3.0.5 | 세운 곳의 이름. 도착 알림이 없으면 현재 주소에서 동 또는 도로 이름만 남깁니다. 설정에서 끌 수 있습니다 (내비 탑재 차량) | `NAVI_ROUTE` |
| TextToSpeech 3.3.1 | 정차 중 음성 (on-device, 자격 증명 불필요) | `TTS_SERVICE` |
| AAOS | 기어 | `CAR_POWERTRAIN` |
| Android | 부모의 한마디 녹음 (정차 중, 버튼을 누를 때만) | `RECORD_AUDIO` |
| Android | 앱을 떠나도 구간 경계를 놓치지 않는 기록, 부팅 후 시작 (설정에서 끄면 함께 꺼짐) | `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `RECEIVE_BOOT_COMPLETED` |

앱 코드에는 네트워크 통신이 없습니다. 최종 매니페스트의 `INTERNET`은 TextToSpeech SDK가 함께 넣는 권한이고, 앱은 on-device 모드만 씁니다.

## 테스트 환경과 필요한 설정
- Pleos Connect Emulator v2.1.0 (API 34, arm64), 화면 2560x1440. 설치 방법은 Pleos Playground의 개발 문서를 따릅니다.
- 차량 상태는 Virtual Console로 바꿉니다. `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`을 엽니다.
- 음성을 들으려면 에뮬레이터 오디오를 켜 둡니다. 부모의 한마디를 녹음하려면 앱이 요청할 때 마이크 권한을 허용합니다.
- 계정, 서버, 자격 증명은 필요 없습니다. Pleos SDK Maven 저장소는 공개되어 있어 별도 인증 없이 빌드됩니다.

## 오픈소스와 글꼴
- [Kotlin](https://kotlinlang.org), [AndroidX, Jetpack Compose](https://developer.android.com/jetpack) (Apache License 2.0): 앱 기반
- [ZXing](https://github.com/zxing/zxing) (Apache License 2.0): QR 코드 생성
- [AndroidX Room](https://developer.android.com/jetpack/androidx/releases/room) (Apache License 2.0): 기기 안 저장
- Asta Sans (SIL Open Font License 1.1): [ASTA_SANS_OFL.txt](ASTA_SANS_OFL.txt)
- Do Hyeon (SIL Open Font License 1.1): [DO_HYEON_OFL.txt](DO_HYEON_OFL.txt)
- Bricolage Grotesque (SIL Open Font License 1.1, 좁은 폭 정적 인스턴스로 잘라 씀): [BRICOLAGE_GROTESQUE_OFL.txt](BRICOLAGE_GROTESQUE_OFL.txt)
