#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
HTTP_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
TEST_DIR="$(mktemp -d "${TMPDIR:-/tmp}/omagotchi-http-token-test.XXXXXX")"
trap 'rm -rf -- "${TEST_DIR}"' EXIT

PRIVATE_ENV_FILE="${TEST_DIR}/http-client.private.env.json"
PUBLIC_ENV_FILE="${TEST_DIR}/http-client.env.json"
SSH_STATE_FILE="${TEST_DIR}/ssh-state"
CURL_STATE_FILE="${TEST_DIR}/curl-state"

cat > "${PRIVATE_ENV_FILE}" <<'JSON'
{
  "local": {
    "frontendUsername": "frontend",
    "frontendPassword": "local-frontend-password",
    "loginEmail": "local@example.com",
    "accountPassword": "local-account-password",
    "accessToken": "",
    "refreshToken": "",
    "accessTokenExpiresAt": "",
    "refreshTokenExpiresAt": ""
  },
  "prod": {
    "sshHost": "example.internal",
    "sshPort": "22",
    "sshUsername": "operator",
    "sshPassword": "server-password",
    "sshKnownHosts": "example.internal ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAITestOnly",
    "loginEmail": "prod@example.com",
    "accountPassword": "prod-account-password",
    "accessToken": "",
    "refreshToken": "",
    "accessTokenExpiresAt": "",
    "refreshTokenExpiresAt": ""
  }
}
JSON

cat > "${PUBLIC_ENV_FILE}" <<'JSON'
{
  "local": {
    "identityAuthBaseUrl": "http://localhost:8083"
  },
  "prod": {}
}
JSON

cat > "${TEST_DIR}/fake-curl" <<'FAKE_CURL'
#!/usr/bin/env bash
set -euo pipefail

payload="$(cat)"
count=0
if [[ -f "${FAKE_CURL_STATE_FILE}" ]]; then
  count="$(cat "${FAKE_CURL_STATE_FILE}")"
fi
count=$((count + 1))
printf '%s' "${count}" > "${FAKE_CURL_STATE_FILE}"

if [[ "$*" == *"/api/v1/auth/logout"* ]]; then
  printf '\n204'
  exit 0
fi

if jq -e 'has("refreshToken")' >/dev/null <<< "${payload}"; then
  printf '{"accessToken":"local-access-2","accessTokenExpiresAt":"2030-01-01T00:15:00Z","refreshToken":"local-refresh-2","refreshTokenExpiresAt":"2030-01-08T00:00:00Z"}\n200'
else
  printf '{"accessToken":"local-access-1","accessTokenExpiresAt":"2030-01-01T00:15:00Z","refreshToken":"local-refresh-1","refreshTokenExpiresAt":"2030-01-08T00:00:00Z"}\n200'
fi
FAKE_CURL

cat > "${TEST_DIR}/fake-ssh" <<'FAKE_SSH'
#!/usr/bin/env bash
set -euo pipefail

payload="$(cat)"
count=0
if [[ -f "${FAKE_SSH_STATE_FILE}" ]]; then
  count="$(cat "${FAKE_SSH_STATE_FILE}")"
fi
count=$((count + 1))
printf '%s' "${count}" > "${FAKE_SSH_STATE_FILE}"

if [[ "$*" == *"/api/v1/auth/logout"* ]]; then
  printf '\n204'
elif [[ "$(jq -r '.refreshToken // ""' <<< "${payload}")" == "prod-refresh-rejected" ]]; then
  printf '{"code":"INVALID_REFRESH_TOKEN"}\n401'
elif jq -e 'has("refreshToken")' >/dev/null <<< "${payload}"; then
  printf '{"accessToken":"prod-access-2","accessTokenExpiresAt":"2030-01-01T00:15:00Z","refreshToken":"prod-refresh-2","refreshTokenExpiresAt":"2030-01-08T00:00:00Z"}\n200'
else
  printf '{"accessToken":"prod-access-1","accessTokenExpiresAt":"2030-01-01T00:15:00Z","refreshToken":"prod-refresh-1","refreshTokenExpiresAt":"2030-01-08T00:00:00Z"}\n200'
fi
FAKE_SSH

chmod 700 "${TEST_DIR}/fake-curl" "${TEST_DIR}/fake-ssh"

run_updater() {
  local environment="$1"
  local command="${2:-prepare}"
  HTTP_CLIENT_PRIVATE_ENV_FILE="${PRIVATE_ENV_FILE}" \
  HTTP_CLIENT_ENV_FILE="${PUBLIC_ENV_FILE}" \
  OMAGOTCHI_CURL_BIN="${TEST_DIR}/fake-curl" \
  OMAGOTCHI_SSH_BIN="${TEST_DIR}/fake-ssh" \
  FAKE_CURL_STATE_FILE="${CURL_STATE_FILE}" \
  FAKE_SSH_STATE_FILE="${SSH_STATE_FILE}" \
    "${HTTP_DIR}/update-access-token.sh" "${environment}" "${command}"
}

assert_equals() {
  local expected="$1"
  local actual="$2"
  local message="$3"
  [[ "${actual}" == "${expected}" ]] || {
    echo "${message}: expected=${expected}, actual=${actual}" >&2
    exit 1
  }
}

assert_token_bundle_cleared() {
  local environment="$1"
  local key

  for key in accessToken refreshToken accessTokenExpiresAt refreshTokenExpiresAt; do
    assert_equals "" \
      "$(jq -r --arg environment "${environment}" --arg key "${key}" \
        '.[$environment][$key]' "${PRIVATE_ENV_FILE}")" \
      "${environment} ${key} 초기화"
  done
}

local_access_token="$(run_updater local)"
assert_equals "local-access-1" "${local_access_token}" "로컬 Login Access Token"
assert_equals "local-refresh-1" \
  "$(jq -r '.local.refreshToken' "${PRIVATE_ENV_FILE}")" \
  "로컬 Login Refresh Token 저장"

local_refreshed_access_token="$(run_updater local)"
assert_equals "local-access-2" "${local_refreshed_access_token}" "로컬 Refresh Access Token"
assert_equals "local-refresh-2" \
  "$(jq -r '.local.refreshToken' "${PRIVATE_ENV_FILE}")" \
  "로컬 Refresh Token 회전"

run_updater local logout
assert_token_bundle_cleared local

prod_access_token="$(run_updater prod)"
assert_equals "prod-access-1" "${prod_access_token}" "운영 Login Access Token"
assert_equals "prod-refresh-1" \
  "$(jq -r '.prod.refreshToken' "${PRIVATE_ENV_FILE}")" \
  "운영 Login Refresh Token 저장"

prod_refreshed_access_token="$(run_updater prod)"
assert_equals "prod-access-2" "${prod_refreshed_access_token}" "운영 Refresh Access Token"
assert_equals "prod-refresh-2" \
  "$(jq -r '.prod.refreshToken' "${PRIVATE_ENV_FILE}")" \
  "운영 Refresh Token 회전"

rejected_refresh_env="${PRIVATE_ENV_FILE}.rejected-refresh"
jq '.prod.refreshToken = "prod-refresh-rejected"' \
  "${PRIVATE_ENV_FILE}" > "${rejected_refresh_env}"
mv "${rejected_refresh_env}" "${PRIVATE_ENV_FILE}"
prod_reissued_access_token="$(run_updater prod 2> "${TEST_DIR}/refresh-fallback.log")"
assert_equals "prod-access-1" "${prod_reissued_access_token}" "운영 Login 재발급 Access Token"
grep -Fq "Refresh Token이 거절되어 Login으로 재발급합니다." \
  "${TEST_DIR}/refresh-fallback.log" \
  || {
    echo "운영 Refresh 거절 안내 누락" >&2
    exit 1
  }

assert_equals "2030-01-01T00:15:00Z" \
  "$(jq -r '.prod.accessTokenExpiresAt' "${PRIVATE_ENV_FILE}")" \
  "운영 Access Token 만료 시각 저장"
assert_equals "2030-01-08T00:00:00Z" \
  "$(jq -r '.prod.refreshTokenExpiresAt' "${PRIVATE_ENV_FILE}")" \
  "운영 Refresh Token 만료 시각 저장"

run_updater prod logout
assert_token_bundle_cleared prod

ssh_call_count_before_idempotent_logout="$(cat "${SSH_STATE_FILE}")"
run_updater prod logout
assert_equals "${ssh_call_count_before_idempotent_logout}" \
  "$(cat "${SSH_STATE_FILE}")" \
  "저장된 Refresh Token이 없는 멱등 Logout의 원격 요청 생략"

echo "update-access-token.sh 테스트 성공"
