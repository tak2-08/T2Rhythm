# 1325 PR 전 기능·경험·디자인 보존 검증

기준은 1324 `caead2d44b209c81ba774c79fd3b6bec4abbab63`이다. 공유·전송·캐시 수정은 `6068609`, 마지막 장르 탐색 수정은 `90f1ab37946494f5d0cc3fff7f3dd8746666cd3f`이다. 장르 탭은 곡 검색에서 장르 목록 탐색으로 변경했고 일반 검색 로직은 기준 소스 그대로 유지했다. 새 장르 인덱스는 장르 탭을 열 때 만들며 선택 전 상태도 안전하게 처리한다. 버전은 5.5.499.1325, 기존 Kotlin/Compose APK 구조를 유지한다.

## 검증 기준

단순 빌드 성공만으로 보존을 판정하지 않는다. 앱/crawler 단위 테스트, Android 10·15·17 네이티브 테스트, 실제 APK 교체, 기준판과 후보판의 실제 화면 비교를 사용한다. 계측 테스트의 예상 개수와 실제 성공 개수를 함께 검사하며 실패·오류·건너뜀은 통과로 세지 않는다.

| 범위 | 환경 | 결과 | 증거 |
| --- | --- | --- | --- |
| 앱/crawler 단위 테스트 | JDK17 | 433개, 실패/오류/건너뜀 0 | [최종 장르 검사](https://github.com/tak2-08/T2Rhythm/actions/runs/39947986253) |
| Android Lint | githubDebug | 오류 0, 경고 368개 | 같은 실행의 preservation-unit-lint |
| 메타데이터·파일명·장르·가사·보고서·기존 UI | Android10 | 82개 통과 | [preservation-metadata-api29](https://github.com/tak2-08/T2Rhythm/actions/runs/39917813548) |
| 데이터/자산 보존·복원·추천·학습·마이그레이션 | Android10 | 63개 통과 | [preservation-persistence-api29](https://github.com/tak2-08/T2Rhythm/actions/runs/39917813548) |
| TLS 전송·승인·취소·네트워크·업데이트 기록 | Android17 | 44개 통과 | [preservation-streaming-api37.0](https://github.com/tak2-08/T2Rhythm/actions/runs/39917813548) |
| 커버·가사 가져오기·큰 글씨/가로 화면 | Android15 | 13개 통과 | [preservation-artwork-api35](https://github.com/tak2-08/T2Rhythm/actions/runs/39917813548) |
| 실제 APK 교체·설치 이력·설치 파일 정리 | Android15 | prepare/seed/verify 통과 | [설치 검증](https://github.com/tak2-08/T2Rhythm/actions/runs/39918641335) |
| 네이티브 탐색·검색·재생 | Android10 | 18개 통과 | [최종 탐색 검사](https://github.com/tak2-08/T2Rhythm/actions/runs/39951048107) |
| 장르 검색·QR·수동 초대·큰 글씨 | Android17 | 13개 통과 | 같은 최종 탐색 검사 |
| 장르 검색·QR·수동 초대·큰 글씨 | Android15 / Google ATD | 13개 통과 | [Android15 검사](https://github.com/tak2-08/T2Rhythm/actions/runs/39953603811) |
| 호환성 | Android15 | 44개 각 케이스 통과 (google_apis 39 + Google ATD 5) | [기존 39](https://github.com/tak2-08/T2Rhythm/actions/runs/39917813548) · [보완 5](https://github.com/tak2-08/T2Rhythm/actions/runs/39953603811) |
| 공유/스트리밍 | Android10 | 44개 통과 | [남은 검사](https://github.com/tak2-08/T2Rhythm/actions/runs/39919235281) |

실제 APK 교체 후 `installedCode=historyCode=554991326`, `stage=INSTALLED`, `apkRemoved=true`, `apkExists=false`를 확인했다. 이 버전은 교체 검사용으로만 올린 시험 APK이며 정식 릴리스를 배포하지 않았다.

검증 대상 앱 소스는 `90f1ab3`이며 이후 변경은 검사 코드와 CI 환경이다. 최종 장르 코드의 CI 앱/계측 APK 빌드, 로컬 `compileGithubDebugAndroidTestKotlin`, 앱/crawler 단위 테스트도 통과했다.

## 디자인과 사용 흐름 비교

공유 기능까지 반영한 `6068609`의 후보와 1324 기준판에서 각각 6개 감사 테스트로 실제 화면 7장씩 캡처했다. 이후 장르 탐색 변경은 검색 화면과 새 파일에 한정하며 기존 비교 대상 화면의 구현은 바꾸지 않았다. 홈, 설정, 설정 검색, 가사 타이밍 큰 글씨/가로, 다운로드 검색+키보드, 청취 기록 큰 글씨를 비교했다. Android 상태/탐색 표시줄·키보드 제안줄, 기존 무작위 설정 추천 카드는 실행 시 달라지는 영역으로 구분했다. 추천 카드의 위치·크기·기존 디자인 구현도 직접 확인했다. 이 영역을 제외하면 6개 화면은 픽셀 차이 0, 다운로드 화면은 최대 채널 차이 3/255, 평균 0.001966/255였다.

홈/플레이어/설정 구현, 테마·폰트·그림 자산, 데이터베이스 스키마·마이그레이션을 변경하지 않았다. 검색의 전체/장르 탭·관련/세부 장르·곡 목록 열기와 공유의 근처 연결/QR 버튼은 요청한 추가 UI이며 기존 곡 작업·필터·수동 초대·복사/공유·승인/취소는 유지했다.

장르 탭의 검색 결과는 장르 이름과 곡 수다. 장르를 선택하면 관련·세부 장르 탐색과 기존 곡 목록 열기를 모두 제공한다. 한국어/영어 별칭·초성, 두 탭의 독립적인 검색어, 뒤로 가기·소스 갱신을 검사한다. [장르 탐색 안내](GENRE_BROWSING.md)를 참고한다.

Android 15의 최종 추가 검사 18회(화면 13 + 호환성 보완 5)는 공식 Google ATD 이미지와 에뮬레이터 36.4.9에서 실행했다. 일반 google_apis 이미지의 가상기기는 UI 검사 중 연결이 끊겨 통과로 세지 않았다. ATD는 화면 출력을 비활성화하므로 검은 스크린샷을 디자인 증거로 사용하지 않는다. 해당 검사는 Android UI 의미 정보·클릭·배치 크기·상태를 검증하며, 픽셀 비교는 앞의 Android 10 캡처와 새 장르 화면의 Android 10·17 실제 출력으로 확인했다. 기존 Android 15 google_apis의 호환성 39개와 커버 13개 성공도 별도로 보존한다.

## 보존 검토에서 수정한 문제

- 새 장르 선택 칩의 닫기 아이콘 크기가 지정되지 않아 화면을 덮던 문제: 아이콘을 18dp로 고정했다. 실제 앱 캡처와 칩 높이 56dp 이하 검사로 검증한다.
- 새 장르 인덱스의 선택 전 상태에서 null 키로 정렬 맵을 조회하던 문제: 곡 목록을 빈 목록으로 처리한다. null 선택 단위 검사와 실제 일반 검색/장르 탭 진입 검사로 검증한다.
- 공유 화면을 나가거나 근처 공개를 끌 때 새로 만든 수동 초대까지 취소되던 문제: 공개할 때 만든 초대만 폐기한다. 실제 서버에 연결하고 승인하는 검사로 확인한다.
- 연결 오류가 새 근처 연결 카드 위쪽의 화면 밖에 놓이던 문제: 오류 안내로 이동하여 바로 볼 수 있게 한다. 실제로 화면에 표시되는지 검사한다.
- 메타데이터 저장과 캐시 초기화가 동시에 일어날 때 nullable 음악 캐시를 두 번 읽어 충돌하던 문제: 같은 캐시 스냅샷을 사용한다. 실제 파일 저장·큐·목록·페이지 갱신 검사로 확인한다.

기존 테스트의 장르 대문자 표기, 수동 이미지 선택의 영속 저장 방식과 Compose 가상 시간/목록 스크롤 전제도 현재 제품 동작에 맞췄다. 실제 MediaStore 음원을 사용하며 유지보수 검사는 강제 스캔으로 라이브러리를 채운다. 검사를 삭제하거나 건너뜀을 허용해 통과시키지 않았다.

## 한계와 배포 범위

물리 기기 간 Bluetooth 처리량·제조사별 페어링·실제 카메라 촬영은 이 환경에서 검증하지 못했다. Bluetooth 전송 프레임과 동시 요청·정지/취소·TLS는 자동 검사하지만 이를 실기기 통과로 표현하지 않는다. 한 Bluetooth RFCOMM 서비스 채널의 여러 폰 동시 청취를 보장하지 않으며 여러 폰에는 Wi-Fi를 권장한다. QR은 상대가 도달할 수 있는 Wi-Fi/VPN 주소를 사용하며 Wi-Fi 없는 연결은 Bluetooth 검색을 사용한다.

전용 실행기 APK+서명된 네이티브 기능 모듈은 [별도 전환 설계](RUNTIME_MODULES.md)다. 이번에 전체 앱을 동적 모듈로 전환하지 않았으며 사용자 설치 클릭·적용 확인·서명 검증·오프라인 저장·복구를 설계 조건으로 남겼다.

원래 정식 APK 키로 서명한 릴리스나 배포는 수행하지 않았다. 기능 검사 CI는 별도 debug 패키지와 시험 서명을 사용한다. 로컬 release 형식 빌드도 시험 서명이며 기존 정식 APK를 교체하는 배포본이 아니다.

## 재현

GitHub Actions의 `Personal experience regression`에서 `preservation=true`, `preservation_batch=full`, 기준 `caead2d44b209c81ba774c79fd3b6bec4abbab63`을 지정한다. `Private streaming regression`의 `update_only=true`는 실제 버전 증가 APK 교체를 검사한다. 스크립트는 계측 결과·실제 화면·로그·summary.json을 아티팩트로 보관한다.

처음 전체 실행에서 일부 테스트의 오래된 전제와 CI 도구 설치 대기가 발견되어 해당 그룹을 수정 후 다시 실행했다. 표는 공유 코드 `6068609`와 장르 수정 코드 `90f1ab3`에 대해 통과한 관련 그룹을 연결한다. 최종 장르 수정 뒤에 검색·탐색·공유 화면·단위 테스트·Lint를 다시 검사했다. 실패한 이전 실행을 성공으로 집계하지 않는다.
