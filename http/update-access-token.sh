#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
PRIVATE_ENV_FILE="${HTTP_CLIENT_PRIVATE_ENV_FILE:-${SCRIPT_DIR}/http-client.private.env.json}"
PUBLIC_ENV_FILE="${HTTP_CLIENT_ENV_FILE:-${SCRIPT_DIR}/http-client.env.json}"
ENVIRONMENT_NAME="${1:-}"
COMMAND="${2:-prepare}"
CURL_BIN="${OMAGOTCHI_CURL_BIN:-curl}"
SSH_BIN="${OMAGOTCHI_SSH_BIN:-ssh}"

TEMP_DIR=""
PRIVATE_ENV_TEMP=""
RESPONSE_BODY=""
RESPONSE_STATUS=""
SSH_PORT=""
SSH_OPTIONS=()

fail() {
  echo "인증 Token 처리 실패: $*" >&2
  exit 1
}

cleanup() {
  if [[ -n "${PRIVATE_ENV_TEMP}" && -f "${PRIVATE_ENV_TEMP}" ]]; then
    rm -f -- "${PRIVATE_ENV_TEMP}"
  fi
  if [[ -n "${TEMP_DIR}" && -d "${TEMP_DIR}" ]]; then
    rm -rf -- "${TEMP_DIR}"
  fi
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "필수 명령을 찾을 수 없습니다: $1"
}

private_value() {
  local key="$1"
  jq -r --arg environment "${ENVIRONMENT_NAME}" --arg key "${key}" \
    '.[$environment][$key] // "" | tostring' \
    "${PRIVATE_ENV_FILE}"
}

public_value() {
  local key="$1"
  jq -r --arg environment "${ENVIRONMENT_NAME}" --arg key "${key}" \
    '.[$environment][$key] // "" | tostring' \
    "${PUBLIC_ENV_FILE}"
}

is_missing_value() {
  local value="$1"
  [[ -z "${value}" || "${value}" == replace-* ]]
}

required_private_value() {
  local key="$1"
  local value
  value="$(private_value "${key}")"
  is_missing_value "${value}" && fail "${ENVIRONMENT_NAME}.${key} 값을 입력하세요."
  printf '%s' "${value}"
}

optional_private_value() {
  local value
  value="$(private_value "$1")"
  if is_missing_value "${value}"; then
    printf ''
  else
    printf '%s' "${value}"
  fi
}

required_public_value() {
  local key="$1"
  local value
  value="$(public_value "${key}")"
  is_missing_value "${value}" && fail "${ENVIRONMENT_NAME}.${key} 공개 설정이 없습니다."
  printf '%s' "${value}"
}

parse_http_response() {
  local raw_response="$1"

  [[ "${raw_response}" == *$'\n'* ]] || fail "Identity 응답에서 HTTP 상태를 확인할 수 없습니다."
  RESPONSE_STATUS="${raw_response##*$'\n'}"
  RESPONSE_BODY="${raw_response%$'\n'*}"
  [[ "${RESPONSE_STATUS}" =~ ^[0-9]{3}$ ]] \
    || fail "Identity HTTP 상태가 올바르지 않습니다."
}

invoke_local_identity() {
  local action="$1"
  local payload="$2"
  local identity_auth_base_url frontend_username frontend_password

  identity_auth_base_url="$(required_public_value "identityAuthBaseUrl")"
  frontend_username="$(required_private_value "frontendUsername")"
  frontend_password="$(required_private_value "frontendPassword")"

  printf '%s' "${payload}" | "${CURL_BIN}" \
    --silent \
    --show-error \
    --connect-timeout 2 \
    --max-time 10 \
    --user "${frontend_username}:${frontend_password}" \
    --header 'Content-Type: application/json' \
    --data-binary '@-' \
    --write-out $'\n%{http_code}' \
    "${identity_auth_base_url}/api/v1/auth/${action}"
}

prepare_ssh_options() {
  local ssh_password="$1"
  local ssh_known_hosts="$2"

  SSH_OPTIONS=(
    -T
    -p "${SSH_PORT}"
    -o ConnectTimeout=10
    -o ServerAliveInterval=5
    -o NumberOfPasswordPrompts=1
  )

  if [[ -n "${ssh_known_hosts}" ]]; then
    if [[ -z "${TEMP_DIR}" ]]; then
      TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/omagotchi-http-token.XXXXXX")"
    fi
    printf '%s\n' "${ssh_known_hosts}" > "${TEMP_DIR}/known_hosts"
    chmod 600 "${TEMP_DIR}/known_hosts"
    SSH_OPTIONS+=(
      -o StrictHostKeyChecking=yes
      -o "UserKnownHostsFile=${TEMP_DIR}/known_hosts"
    )
  else
    SSH_OPTIONS+=(-o StrictHostKeyChecking=yes)
  fi

  if [[ -n "${ssh_password}" ]]; then
    if [[ -z "${TEMP_DIR}" ]]; then
      TEMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/omagotchi-http-token.XXXXXX")"
    fi
    cat > "${TEMP_DIR}/ssh-askpass.sh" <<'ASKPASS'
#!/bin/sh
printf '%s\n' "${OMAGOTCHI_SSH_PASSWORD}"
ASKPASS
    chmod 700 "${TEMP_DIR}/ssh-askpass.sh"
    export DISPLAY="${DISPLAY:-omagotchi-http-client:0}"
    export SSH_ASKPASS="${TEMP_DIR}/ssh-askpass.sh"
    export SSH_ASKPASS_REQUIRE=force
    export OMAGOTCHI_SSH_PASSWORD="${ssh_password}"
    SSH_OPTIONS+=(
      -o BatchMode=no
      -o 'PreferredAuthentications=password,keyboard-interactive'
      -o PubkeyAuthentication=no
    )
  else
    SSH_OPTIONS+=(-o BatchMode=yes)
  fi
}

invoke_prod_identity() {
  local action="$1"
  local payload="$2"
  local ssh_host ssh_username ssh_password ssh_known_hosts remote_command

  ssh_host="$(required_private_value "sshHost")"
  SSH_PORT="$(required_private_value "sshPort")"
  ssh_username="$(required_private_value "sshUsername")"
  ssh_password="$(optional_private_value "sshPassword")"
  ssh_known_hosts="$(optional_private_value "sshKnownHosts")"

  [[ "${SSH_PORT}" =~ ^[0-9]+$ ]] || fail "prod.sshPort는 숫자여야 합니다."
  [[ "${ssh_username}" =~ ^[A-Za-z0-9._-]+$ ]] \
    || fail "prod.sshUsername 형식이 올바르지 않습니다."
  [[ "${ssh_host}" =~ ^[A-Za-z0-9._:-]+$ ]] \
    || fail "prod.sshHost 형식이 올바르지 않습니다."

  prepare_ssh_options "${ssh_password}" "${ssh_known_hosts}"

  remote_command="$(cat <<REMOTE_COMMAND
set -eu
container_id=\$(docker ps \\
  --filter 'label=com.docker.compose.service=identity-service' \\
  --filter 'health=healthy' \\
  --format '{{.ID}}' | head -n 1)
test -n "\${container_id}" || {
  echo '정상 실행 중인 Identity Container를 찾을 수 없습니다.' >&2
  exit 1
}
docker exec -i "\${container_id}" sh -c '
  curl --silent --show-error \\
    --connect-timeout 2 \\
    --max-time 10 \\
    --user "\$FRONTEND_USERNAME:\$FRONTEND_PASSWORD" \\
    --header "Content-Type: application/json" \\
    --data-binary @- \\
    --write-out "\\n%{http_code}" \\
    "http://127.0.0.1:8080/api/v1/auth/${action}"
'
REMOTE_COMMAND
)"

  printf '%s' "${payload}" | "${SSH_BIN}" \
    "${SSH_OPTIONS[@]}" \
    "${ssh_username}@${ssh_host}" \
    "${remote_command}"
}

invoke_identity() {
  local action="$1"
  local payload="$2"

  case "${ENVIRONMENT_NAME}" in
    local)
      invoke_local_identity "${action}" "${payload}"
      ;;
    prod)
      invoke_prod_identity "${action}" "${payload}"
      ;;
    *)
      fail "실행 환경은 local 또는 prod여야 합니다."
      ;;
  esac
}

request_token_bundle() {
  local refresh_token raw_response payload login_email account_password

  refresh_token="$(optional_private_value "refreshToken")"
  if [[ -n "${refresh_token}" ]]; then
    payload="$(jq -nc --arg refresh_token "${refresh_token}" \
      '{refreshToken: $refresh_token}')"
    if ! raw_response="$(invoke_identity "refresh" "${payload}")"; then
      fail "Identity Refresh 요청을 전송하지 못했습니다."
    fi
    parse_http_response "${raw_response}"

    if [[ "${RESPONSE_STATUS}" =~ ^2[0-9]{2}$ ]]; then
      return
    fi
    [[ "${RESPONSE_STATUS}" == "401" ]] \
      || fail "Identity Refresh 응답: HTTP ${RESPONSE_STATUS}"
    clear_token_bundle
    echo "Refresh Token이 거절되어 Login으로 재발급합니다." >&2
  fi

  login_email="$(required_private_value "loginEmail")"
  account_password="$(required_private_value "accountPassword")"
  payload="$(jq -nc \
    --arg email "${login_email}" \
    --arg password "${account_password}" \
    '{email: $email, password: $password}')"

  if ! raw_response="$(invoke_identity "login" "${payload}")"; then
    fail "Identity Login 요청을 전송하지 못했습니다."
  fi
  parse_http_response "${raw_response}"
  [[ "${RESPONSE_STATUS}" =~ ^2[0-9]{2}$ ]] \
    || fail "Identity Login 응답: HTTP ${RESPONSE_STATUS}"
}

store_token_bundle() {
  local access_token="$1"
  local refresh_token="$2"
  local access_token_expires_at="$3"
  local refresh_token_expires_at="$4"

  PRIVATE_ENV_TEMP="$(mktemp "${PRIVATE_ENV_FILE}.tmp.XXXXXX")"
  jq \
    --arg environment "${ENVIRONMENT_NAME}" \
    --arg access_token "${access_token}" \
    --arg refresh_token "${refresh_token}" \
    --arg access_token_expires_at "${access_token_expires_at}" \
    --arg refresh_token_expires_at "${refresh_token_expires_at}" \
    '.[$environment].accessToken = $access_token
     | .[$environment].refreshToken = $refresh_token
     | .[$environment].accessTokenExpiresAt = $access_token_expires_at
     | .[$environment].refreshTokenExpiresAt = $refresh_token_expires_at' \
    "${PRIVATE_ENV_FILE}" > "${PRIVATE_ENV_TEMP}"
  chmod 600 "${PRIVATE_ENV_TEMP}"
  mv -f -- "${PRIVATE_ENV_TEMP}" "${PRIVATE_ENV_FILE}"
  PRIVATE_ENV_TEMP=""
}

clear_token_bundle() {
  PRIVATE_ENV_TEMP="$(mktemp "${PRIVATE_ENV_FILE}.tmp.XXXXXX")"
  jq \
    --arg environment "${ENVIRONMENT_NAME}" \
    '.[$environment].accessToken = ""
     | .[$environment].refreshToken = ""
     | .[$environment].accessTokenExpiresAt = ""
     | .[$environment].refreshTokenExpiresAt = ""' \
    "${PRIVATE_ENV_FILE}" > "${PRIVATE_ENV_TEMP}"
  chmod 600 "${PRIVATE_ENV_TEMP}"
  mv -f -- "${PRIVATE_ENV_TEMP}" "${PRIVATE_ENV_FILE}"
  PRIVATE_ENV_TEMP=""
}

logout_token_bundle() {
  local refresh_token raw_response payload

  refresh_token="$(optional_private_value "refreshToken")"
  if [[ -n "${refresh_token}" ]]; then
    payload="$(jq -nc --arg refresh_token "${refresh_token}" \
      '{refreshToken: $refresh_token}')"
    if ! raw_response="$(invoke_identity "logout" "${payload}")"; then
      fail "Identity Logout 요청을 전송하지 못했습니다."
    fi
    parse_http_response "${raw_response}"
    [[ "${RESPONSE_STATUS}" == "204" ]] \
      || fail "Identity Logout 응답: HTTP ${RESPONSE_STATUS}"
  fi

  clear_token_bundle
}

main() {
  local access_token refresh_token access_token_expires_at refresh_token_expires_at

  require_command jq
  [[ -f "${PRIVATE_ENV_FILE}" ]] || fail "private 환경 파일이 없습니다: ${PRIVATE_ENV_FILE}"
  [[ -f "${PUBLIC_ENV_FILE}" ]] || fail "공개 환경 파일이 없습니다: ${PUBLIC_ENV_FILE}"
  chmod 600 "${PRIVATE_ENV_FILE}" \
    || fail "private 환경 파일 권한을 600으로 설정하지 못했습니다."
  jq empty "${PRIVATE_ENV_FILE}" >/dev/null 2>&1 \
    || fail "private 환경 파일이 올바른 JSON이 아닙니다."
  jq empty "${PUBLIC_ENV_FILE}" >/dev/null 2>&1 \
    || fail "공개 환경 파일이 올바른 JSON이 아닙니다."

  case "${COMMAND}" in
    prepare|logout|clear)
      ;;
    *)
      fail "두 번째 인자는 prepare, logout 또는 clear여야 합니다."
      ;;
  esac

  case "${ENVIRONMENT_NAME}" in
    local)
      if [[ "${COMMAND}" != "clear" ]]; then
        require_command "${CURL_BIN}"
      fi
      ;;
    prod)
      if [[ "${COMMAND}" != "clear" ]]; then
        require_command "${SSH_BIN}"
      fi
      ;;
    *)
      fail "첫 번째 인자로 local 또는 prod를 지정하세요."
      ;;
  esac

  case "${COMMAND}" in
    clear)
      clear_token_bundle
      return
      ;;
    logout)
      logout_token_bundle
      return
      ;;
    prepare)
      request_token_bundle
      ;;
  esac

  access_token="$(jq -er '.accessToken | select(type == "string" and length > 0)' \
    <<< "${RESPONSE_BODY}")" \
    || fail "Identity 응답에 Access Token이 없습니다."
  refresh_token="$(jq -er '.refreshToken | select(type == "string" and length > 0)' \
    <<< "${RESPONSE_BODY}")" \
    || fail "Identity 응답에 Refresh Token이 없습니다."
  access_token_expires_at="$(jq -er '.accessTokenExpiresAt | select(type == "string" and length > 0)' \
    <<< "${RESPONSE_BODY}")" \
    || fail "Identity 응답에 Access Token 만료 시각이 없습니다."
  refresh_token_expires_at="$(jq -er '.refreshTokenExpiresAt | select(type == "string" and length > 0)' \
    <<< "${RESPONSE_BODY}")" \
    || fail "Identity 응답에 Refresh Token 만료 시각이 없습니다."

  store_token_bundle \
    "${access_token}" \
    "${refresh_token}" \
    "${access_token_expires_at}" \
    "${refresh_token_expires_at}"

  printf '%s\n' "${access_token}"
}

main "$@"
