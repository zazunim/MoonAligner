# 🌕☀️ MoonAligner (문얼라이너)
> **완벽한 달 & 태양 랜드마크 정렬 출사를 위한 스마트 플래너 & 초망원 화각 계산기**  
> *Smart Shot Planner & FOV Compressor for Moon & Sun Landmark Alignments*

[![Platform](https://img.shields.io/badge/Platform-Android%20%7C%20iOS-blue.svg)](https://github.com/zazunim/MoonAligner)
[![Latest Release](https://img.shields.io/badge/Release-v1.2.6-gold.svg)](https://github.com/zazunim/MoonAligner/releases)
[![Language](https://img.shields.io/badge/Kotlin-Compose-purple.svg)](Android/)
[![Language](https://img.shields.io/badge/Swift-SwiftUI-orange.svg)](iOS/)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

---

![MoonAligner 기능 가이드](assets/MoonAligner_Real_UI_Feature_Guide.png)

## 📌 프로젝트 소개
**MoonAligner**는 타워(도시 랜드마크), 비행기 등 피사체 뒤로 거대한 보름달이나 태양이 정확히 일치하여 떠오르고 지는 **초망원 3점 1열 정렬 샷(일출/일몰 및 슈퍼문 랜드마크 관통)**을 초보자도 쉽게 계획하고 촬영할 수 있도록 돕는 모바일 출사 플래너 애플리케이션입니다.

- **Android**: Kotlin & Jetpack Compose, Osmdroid, Sensor Framework
- **iOS**: Swift & SwiftUI, MapKit, CoreLocation, CoreMotion

---

## 🚀 빠른 다운로드 (Downloads)

| 플랫폼 | 설치 방법 | 다운로드 링크 |
| :--- | :--- | :--- |
| **Android** | 스마트폰에서 다운로드 후 바로 설치 (사이드로딩) | [📥 최신 MoonAligner-v1.2.6.apk 다운로드](https://github.com/zazunim/MoonAligner/releases/latest) |
| **iOS (iPhone)** | Xcode 소스 빌드 또는 AltStore / Sideloadly 설치 | [📦 최신 iOS 소스 패키지 (v1.2.6)](https://github.com/zazunim/MoonAligner/releases/latest) |

---

## ✨ 핵심 기능 (Key Features)

### 1. 🎯 달 & 태양 듀얼 정렬 플래너 (Dual Shot Planner)
- **원탭 3점 1열 자동 정렬**: `[카메라 1열]` 탭 한 번으로 카메라(삼각대), 피사체(건물), 천체(달/태양)의 궤적이 오차 0.0°로 일치하는 최적 촬영 지점을 자동 산출합니다.
- **가장 가까운 1열 시각 역산**: `[시각 1열]` 탭 시 현재 삼각대 조준선 위로 달이나 태양이 지나가는 가장 가까운 일출·일몰 또는 주간 통과 시각으로 자동 이동합니다.
- **[ 🌕 달 플래너 | ☀️ 태양 플래너 ] 즉시 전환**: 지도 최상단 플로팅 세그먼트 버튼으로 1초 만에 달과 태양 모드를 전환합니다.
- **90일 타임라인 & 정렬 검색**: 향후 3일, 7일, 30일, 90일 동안 랜드마크 정상을 관통하는 모든 정렬 기회를 자동 탐색합니다 (달 위상 필터 포함).
- **실시간 나침반 & 현장 유도**: 출사 현장에서 스마트폰이 바라보는 실제 시선 방향(예: `시선 218°`)과 나침반 빔을 지도 위에 표시하여 삼각대 위치를 직관적으로 유도합니다.
- **실시간 태양 그림자 시뮬레이터**: 태양 고도와 방위각에 따른 건물의 실시간 그림자 길이 및 배율을 3D로 시뮬레이션합니다.

### 2. 🔭 초망원 화각 압축 계산기 (FOV & Compressed Perspective Simulator)
- **달 & 태양 크기 비율 시뮬레이션**: 70m 여객기나 대형 타워 실루엣 대비 달(0.52°)과 태양(0.53°)의 실제 상대 크기 비율(예: `0.87×`)을 뷰파인더 미리보기로 확인합니다.
- **배경 압축 효과 체감**: 풀프레임 기준 24mm 광각부터 1200mm+ 초망원 렌즈까지 슬라이더를 조절하며 프레임 안의 구도를 사전 시각화합니다.
- **필수 촬영 거리 수학적 산출**: 원하는 크기 비율을 얻기 위해 필요한 삼각대 거리(예: `8.25 km`)를 광학 공식으로 자동 계산합니다.
- **원탭 거리 잠금 및 지도 동기화**: `[거리 고정]`을 켜면 지도 위에서 카메라가 피사체 주위를 완벽한 동심원 궤도로 회전하며 거리를 일정하게 유지합니다.
- **일출/일몰 및 골든아워·블루아워**: 매일의 시민박명, 일출, 일몰, 항해박명 시각을 정밀 계산하여 최적의 촬영 골든타임을 안내합니다.

---

## 💡 추천 출사 워크플로우 (3 Steps)

```
[화각 계산기]                      [정렬 플래너]                      [출사 현장]
목표 달/태양 크기 비율 결정    →    원탭 3점 1열 지점/시각 탐색    →    실시간 나침반(시선 방향) 유도
필수 촬영 거리 산출(8.25km)          90일 궤적 & 골든타임 확인           삼각대 세팅 및 셔터 찬스
```

---

## 🛠️ 프로젝트 빌드 방법

### Android
```bash
cd Android
./gradlew assembleRelease
# 빌드 결과물: Android/app/build/outputs/apk/release/app-release.apk
```

### iOS
1. macOS 환경에서 `iOS/MoonAligner.xcodeproj`를 Xcode로 엽니다.
2. 타깃 기기(본인 iPhone 또는 시뮬레이터)를 선택합니다.
3. `Cmd + R`을 눌러 빌드 및 실행합니다.

---

## 📄 라이선스 (License)
본 프로젝트는 [MIT License](LICENSE)를 따릅니다.
누구나 자유롭게 수정 및 배포할 수 있습니다.
