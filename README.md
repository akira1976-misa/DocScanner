# DocScanner - 문서 스캔 → PDF 변환 앱

카메라로 책이나 종이 문서를 찍으면 **접힌 부분 보정·화질 개선·원근 교정**을 자동으로 처리하고 깨끗한 PDF로 저장합니다.

---

## 핵심 기능

| 기능 | 설명 |
|------|------|
| 자동 문서 감지 | 카메라 프레임에서 문서 경계 실시간 인식 |
| 원근 보정 | 비틀어 찍어도 정면 뷰로 자동 교정 |
| 접힘·굴곡 보정 | ML Kit 기반 표면 왜곡 자동 제거 |
| 화질 개선 | 노이즈 제거, 대비 강화, 텍스트 선명화 |
| 다중 페이지 | 최대 20페이지를 한 번에 스캔 후 단일 PDF로 저장 |
| 갤러리 가져오기 | 기존에 찍은 사진도 스캔 처리 가능 |
| 공유 | 이메일, 카카오톡 등 앱으로 즉시 공유 |

---

## 기술 스택

- **언어**: Kotlin
- **스캔 엔진**: Google ML Kit Document Scanner API
  - `GmsDocumentScannerOptions.SCANNER_MODE_FULL` — 자동 감지 + 수동 조절 모드
  - `RESULT_FORMAT_PDF` + `RESULT_FORMAT_JPEG` — PDF 및 이미지 동시 출력
- **UI**: Material Design 3, View Binding
- **파일 공유**: FileProvider (보안 URI)
- **최소 SDK**: API 24 (Android 7.0)

---

## 빌드 및 실행 방법

### 요구사항

- Android Studio Hedgehog (2023.1.1) 이상
- JDK 17
- 실제 기기 권장 (에뮬레이터는 카메라 제한)

### 실행 순서

```bash
# 1. 프로젝트 열기
Android Studio → Open → DocScanner 폴더 선택

# 2. Gradle Sync (자동 실행됨)
# ML Kit 라이브러리는 처음 실행 시 Google Play Services에서 자동 다운로드

# 3. 실기기 연결 후 Run
```

> ML Kit Document Scanner는 모델을 **앱 최초 실행 시 백그라운드에서 다운로드**합니다.
> Wi-Fi 연결 상태에서 처음 실행하세요.

---

## 프로젝트 구조

```
DocScanner/
├── app/src/main/
│   ├── java/com/docscanner/app/
│   │   ├── MainActivity.kt          # 스캔 실행 · 파일 목록 관리
│   │   ├── PdfViewerActivity.kt     # PDF 열기 · 공유
│   │   ├── ScannedFileAdapter.kt    # 파일 목록 RecyclerView
│   │   └── ScannedFile.kt           # 데이터 모델
│   ├── res/
│   │   ├── layout/
│   │   │   ├── activity_main.xml
│   │   │   ├── activity_pdf_viewer.xml
│   │   │   └── item_scanned_file.xml
│   │   ├── drawable/                # 아이콘 벡터 리소스
│   │   ├── values/                  # 색상·문자열·테마
│   │   └── xml/file_paths.xml       # FileProvider 경로 설정
│   └── AndroidManifest.xml
├── build.gradle.kts
└── gradle/libs.versions.toml
```

---

## 스캔 처리 흐름

```
카메라 촬영
    ↓
GmsDocumentScanner (ML Kit)
    ├─ 문서 경계 자동 감지
    ├─ 원근·왜곡 보정
    ├─ 화질 향상 (노이즈 제거)
    └─ 다중 페이지 병합
         ↓
PDF + JPEG 생성
    ↓
앱 내부 저장소 저장
    ↓
목록 표시 / 열기 / 공유
```

---

## 자주 묻는 질문

**Q. 인터넷 없이 사용 가능한가요?**
최초 1회 ML Kit 모델 다운로드 후에는 오프라인에서도 동작합니다.

**Q. 저장된 파일은 어디에 있나요?**
앱 내부 저장소(`/data/data/com.docscanner.app/files/scanned_pdfs/`)에 저장됩니다.
외부에서 접근하려면 공유 기능을 사용하세요.

**Q. 에뮬레이터에서 테스트할 수 있나요?**
카메라 입력이 제한되어 갤러리 가져오기 모드로만 테스트 가능합니다.
