# T2Rhythm 공개 서명키

이 폴더는 기존 서명키에 대응하는 공개키와 APK 서명 인증서만 포함합니다. 별도 로그인 없이 공유하고 다운로드할 수 있습니다. APK와 모듈의 서명키를 새로 바꾸지는 않습니다.

- `apk-signing-public.pem` / `.der`: 기존 APK 서명 검증용 RSA 공개키.
- `apk-signing-certificate.pem`: 기존 APK 서명 인증서. 인증서 SHA-256은 `c55ec7843dd8190f2ce249ddc1562c616647c50c317930076e2e98bf7e304c18`입니다.
- `module-public.pem` / `.der`: 1329 이후 기본 APK에서 사용하는 모듈 서명 검증용 RSA 공개키. 키 ID는 `119bac2ee38b5fda50d2ea54af8560faac6135489bff778dfe0ea68440ebba3e`입니다.
- `SHA256SUMS`: 위 공개 파일의 SHA-256.

공개키는 서명을 검증하는 용도입니다. APK·모듈을 생성하여 서명하려면 대응하는 개인키가 필요합니다. 공개키에서 개인키를 복구할 수 없습니다. 개인키·keystore·비밀번호·AI API 키는 포함하지 않습니다. AI API 키는 공개키로 변환하여 대체할 수 없습니다.

개인키는 기존 암호화 백업에 보관되어 있습니다. 이 폴더의 공개는 개인키나 API 키를 GitHub Secrets에 등록한 것과는 별개입니다.
