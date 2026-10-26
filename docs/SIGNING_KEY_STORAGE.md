# T2Rhythm 키 보관

## 1331: 원래 모듈 키 복구

사용자가 원래 모듈 개인키를 제공하여 공개키 ID `ee26407974d949d48aa67ace73347c1dfda97dd93e7e8d2b472fe2144bc41270`와 일치함을 확인했습니다. 1331부터 이 원래 키를 현재 프로덕션 모듈 서명키로 사용합니다. 임시 1329/1330의 공개키 `119bac2ee38b5fda50d2ea54af8560faac6135489bff778dfe0ea68440ebba3e`는 그 버전의 활성화 기록 인증에만 보존합니다. APK 인증서는 계속 기존 `c55ec7843dd8190f2ce249ddc1562c616647c50c317930076e2e98bf7e304c18`을 사용합니다. 기본 APK 업데이트 후 보관함·설정은 그대로 유지합니다. 아래의 1329 전환 설명은 당시의 이력입니다.

프로젝트 작업에 쓰는 비밀키는 GitHub의 암호화된 Secrets에 보관합니다. 공개 소스, PR 본문, 빌드 로그, APK와 일반 빌드 산출물에는 개인키나 비밀번호를 넣지 않습니다. 공개키와 인증서 지문만 버전 관리합니다. 사용자가 앱에서 직접 등록하는 AI API 키는 사용자 기기의 Android Keystore 암호화 저장소에 보관하며 개발자 GitHub로 수집하지 않습니다.

필수 저장 항목은 아래 세 가지입니다. 모두 원본 바이트를 base64로 인코딩한 값을 등록합니다. base64 자체는 암호화가 아니며 GitHub Secrets의 저장·접근 제어를 사용해야 합니다.

| Secret | 내용 |
| --- | --- |
| `SIGNING_KEYSTORE` | 기존 APK 서명 keystore |
| `SIGNING_PROPERTIES` | 기존 별칭·비밀번호 및 CI용 경로 `config/signing/rhythm_keystore.jks` |
| `RUNTIME_MODULE_SIGNING_KEY` | 현재 공개키에 대응하는 별도 RSA PKCS#8 모듈 개인키 |

GitHub Secrets는 등록 뒤 원문을 조회·다운로드할 수 없습니다. 계정 관리자와 키를 사용하는 허용된 워크플로의 접근 범위도 고려해야 하므로 “특정 두 사람만 원문을 볼 수 있는 파일 보관함”과는 다릅니다. 복구 가능한 키 파일은 소유자 전용 비공개 보관소 또는 별도 암호화 백업에도 보존해야 합니다. 비공개 저장소의 생성·접근 제한 및 실제 Secrets 등록을 확인하기 전에는 보관 완료로 기록하지 않습니다.

인증된 GitHub CLI와 `cryptography`가 설치된 로컬 환경에서 다음 스크립트로 기존 APK 인증서와 현재 모듈 공개키의 일치를 먼저 검사합니다. 비밀 값은 명령행 인수나 출력에 쓰지 않고 `gh secret set`의 표준 입력으로 전달합니다. 실행 환경의 셸 추적은 사용하지 않습니다.

```bash
python3 scripts/store_signing_secrets.py --module-key /private/runtime-module-signing.pk8 --check-only
python3 scripts/store_signing_secrets.py --module-key /private/runtime-module-signing.pk8
```

스크립트는 `.config/keystore.properties`에 지정된 기존 keystore를 읽고 CI용 경로만 정규화합니다. 잘못된 키·인증서 또는 지원하지 않는 properties 형식이면 업로드 전에 중단합니다. 등록 중 일부 항목이 실패한 경우 인증·저장소 권한을 확인한 뒤 같은 입력으로 다시 실행합니다. GitHub 로그인 비밀번호·토큰을 채팅에 입력하지 않습니다.

## 1329 모듈 키 전환

기존 모듈 개인키를 찾을 수 없어 새로운 RSA 3072비트 키를 생성했습니다. 새 공개키 ID는 `119bac2ee38b5fda50d2ea54af8560faac6135489bff778dfe0ea68440ebba3e`입니다. 새 기본 APK는 사용자가 제공한 기존 APK 인증서로 서명합니다. 모듈 개인키는 APK 서명키와 분리합니다.

`runtime-contract/module-public.der`는 현재 실행·다운로드 검증용 공개키입니다. `legacy-module-public.der`는 기존 활성화 기록을 인증하고 기본 APK 업데이트와 조정할 때만 사용합니다. 과거 키로 서명된 새 다운로드와 실행은 현재 검증기가 거부합니다. 서명 키와 기본 소스 변경은 런타임 호환성 지문에 반영되므로 이 전환에는 기본 APK 업데이트가 필요합니다. 기존 호환성·변조·승인·롤백 방어는 유지합니다.

이 문서는 저장 정책과 등록 절차를 설명합니다. 현재 GitHub에 비밀키가 저장되었다는 증빙은 아닙니다.
