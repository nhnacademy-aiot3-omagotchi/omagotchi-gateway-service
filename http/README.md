# Gateway API 점검

IntelliJ HTTP Client를 이용한 `Nginx → Gateway → Domain Service` 호출 경로 점검.

## 처음 사용

- **환경 파일 생성**

  - 로컬: `http-client.local.private.env.json.example` 복사
  - 운영: `http-client.prod.private.env.json.example` 복사
  - 복사본 이름: `http-client.private.env.json`

- **접속·계정 정보 입력**

  - 공통
    - `tokenUpdaterPath`: PC마다 한 번 직접 입력하는 값
      - 자동 입력되지 않음
      - `gateway-service/http/update-access-token.sh`의 절대 경로
      - 예: `/Users/<사용자>/.../gateway-service/http/update-access-token.sh`
  - 로컬
    - `frontendUsername`·`frontendPassword`
    - `loginEmail`·`accountPassword`
  - 운영
    - `sshHost`·`sshPort`·`sshUsername`
    - `sshPassword`: SSH Password 사용 시 입력, Key·Agent 사용 시 빈 값
    - `sshKnownHosts`: 검증한 `known_hosts` 한 줄, 기본 파일 사용 시 빈 값
    - `loginEmail`·`accountPassword`
  - 비밀번호 변경
    - `newPassword`

- **Access Token 준비**

  - `auth.http` 열기
  - 상단 **Run with**에서 로컬은 `local`, 운영은 `prod` 선택
  - **Access Token 발급·갱신** 실행
  - 최초 실행: 이메일·비밀번호 Login
  - 이후 실행: 저장된 Refresh Token 회전
  - Refresh Token 거절: 이메일·비밀번호 Login으로 재발급
  - 발급 결과: Access·Refresh Token과 만료 시각의 private 환경 파일 자동 저장
  - IDE 공통 상태: 선택한 `baseUrl`과 Access Token 저장
  - 확인 요청: 새 Access Token으로 본인 계정 조회

- **API 요청 실행**

  - 필요한 `.http` 파일 선택
  - 원하는 요청만 개별 실행
  - 다른 `.http` 파일에서 `Run with` 선택 불필요
  - Access Token 만료로 401 발생 시 `auth.http` 다시 실행
  - `baseUrl` 미치환 오류 시 `auth.http`의 **Access Token 발급·갱신** 재실행

- **다른 계정으로 전환**

  - `auth.http`의 **로그아웃·저장 Token 초기화** 실행
  - `loginEmail`·`accountPassword` 변경
  - **Access Token 발급·갱신** 재실행

IntelliJ의 **Run with** 선택은 `.http` 파일별로 저장됨. 이 구성은 `auth.http`에서만 환경을 선택하고, 발급한 값을 IDE 공통 상태로 전달하는 방식.

운영 Token 준비 과정의 SSH 접속과 Identity 내부 호출은 스크립트가 수행하므로 서버 Terminal 직접 접속 불필요.

## 요청 파일

- **인증**

  - `auth.http`: Access Token 발급·갱신, 로그아웃·계정 전환

- **Identity**

  - `identity.http`: 본인 계정·관리자 사용자 목록 조회
  - `identity-mutations.http`: 이름·비밀번호·탈퇴·관리자 상태 변경

- **Learning**

  - `learning.http`: 기수·공간·Timer·점유·프로필·Telegram 조회
  - `learning-ai.http`: AI Chat·공부 시간 예측

- **Rule**

  - `rule.http`: Rule·Flow·Topology·Engine 조회

## 요청별 설정

- `.http` 파일 상단

  - 페이지·기수·Flow·AI
  - 변경 대상·상태 변경 확인 문자열

- `http-client.private.env.json`

  - SSH 접속 정보
  - 사용자 이메일·비밀번호
  - Access·Refresh Token과 만료 시각

## 주의사항

- `http-client.private.env.json`: Git 추적 제외
- `PROD_ENV`: 복사 금지
- 실제 Secret: 문서·Issue·PR·메신저·실행 로그 기록 금지
- `sshKnownHosts` 미입력: 기본 `~/.ssh/known_hosts` 사용
- 알 수 없는 SSH Host Key: 자동 신뢰하지 않고 실행 실패
- 상태 변경 요청: 확인 문자열 변경 후 개별 실행
- 변경 대상·확인 문자열: 실행 후 기본값으로 복원
- AI Chat·공부 시간 예측: 외부 호출과 상태 변경 가능성으로 개별 실행
- 공유 관리자·자기 자신·마지막 관리자: 상태 변경 대상에서 제외
- 운영 점검 종료: private 환경 파일의 운영 Token 제거

## Gateway 제외 경로

- Identity 인증 API
  - `auth.http`가 SSH를 통해 Identity 내부 API 호출
- Prediction 직접 API
  - Learning의 공부 시간 예측 API를 통해 연동 점검
- Rule Recovery·`/api/v1/internal/**`
