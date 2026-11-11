#!/usr/bin/env bash
# PMart 직원 IAM 통합 검증 스크립트
# 브라우저 로그인(Authorization Code + PKCE)을 curl로 재현하고, 보안 규칙이 지켜지는지 확인합니다.
# 사용: docker compose up -d --build 후  bash scripts/e2e-test.sh
set -uo pipefail

cd "$(dirname "$0")/.."
set -a; source .env; set +a

GW="${PUBLIC_BASE_URL}"
ORIGIN="${BACKOFFICE_ORIGIN}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PASS=0; FAIL=0

ok()   { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
check() { if [ "$2" = "$3" ]; then ok "$1 ($3)"; else fail "$1: 기대 $2, 실제 $3"; fi; }

status()   { curl -s -o /dev/null -w '%{http_code}' "$@"; }
location() { curl -s -o /dev/null -w '%{redirect_url}' "$@"; }

# 브라우저 로그인 재현. $1=cookie jar, $2=username, $3=password, $4=(선택) 새 비밀번호
# 성공하면 Callback 이후 이동한 주소를 출력
browser_login() {
  local jar="$1" user="$2" pass="$3" newpass="${4:-}" start="${5:-$GW/employee-auth/oauth2/start}"
  local auth_url page action next
  auth_url=$(location -c "$jar" -b "$jar" "$start")
  echo "$auth_url" > "$WORK/auth_url"
  page=$(curl -s -c "$jar" -b "$jar" "$auth_url")
  action=$(echo "$page" | grep -o 'id="kc-form-login"[^>]*action="[^"]*"' | sed 's/.*action="//; s/"$//; s/&amp;/\&/g')
  next=$(curl -s -c "$jar" -b "$jar" -o "$WORK/page.html" -w '%{redirect_url}' \
    --data-urlencode "username=$user" --data-urlencode "password=$pass" "$action")
  if [[ "$next" == *"required-action"* ]] && [ -n "$newpass" ]; then
    # 임시 비밀번호: Keycloak이 비밀번호 변경 화면(required-action)으로 이동시킴
    curl -s -c "$jar" -b "$jar" -o "$WORK/page.html" "$next"
    action=$(grep -o '<form[^>]*action="[^"]*"' "$WORK/page.html" | head -1 | sed 's/.*action="//; s/"$//; s/&amp;/\&/g')
    next=$(curl -s -c "$jar" -b "$jar" -o /dev/null -w '%{redirect_url}' \
      --data-urlencode "password-new=$newpass" --data-urlencode "password-confirm=$newpass" "$action")
  fi
  echo "$next" > "$WORK/callback_url"
  : > "$WORK/callback.h"
  [ -n "$next" ] && curl -s -o /dev/null -D "$WORK/callback.h" -w '%{redirect_url}' -c "$jar" -b "$jar" "$next"
}

# Flutter 앱 로그인 재현: app_verifier 생성 → 시스템 브라우저 로그인 → 커스텀 스킴으로 받은 ticket 출력
# $1=app_verifier. Callback 응답 헤더는 $WORK/mobile_callback.h
mobile_login() {
  local verifier="$1" challenge
  # `--` 필수: verifier가 `-`로 시작하면 node가 옵션으로 해석해 실패함 (간헐 실패의 원인이었음)
  challenge=$(node -e 'console.log(require("crypto").createHash("sha256").update(process.argv[1]).digest("base64url"))' -- "$verifier")
  browser_login "$WORK/m.jar" "$HR_TEST_IAM_ID" "$HR_TEST_PASSWORD" "" \
    "$GW/employee-auth/oauth2/start?client=mobile&app_challenge=$challenge" > /dev/null
  cp "$WORK/callback.h" "$WORK/mobile_callback.h"   # browser_login이 받은 Callback 응답 (state는 한 번만 사용 가능)
  grep -i '^location:' "$WORK/mobile_callback.h" | tr -d '\r' | sed 's/^[Ll]ocation: //'
}
rand() { node -e 'console.log(require("crypto").randomBytes(32).toString("base64url"))'; }
json_field() { node -e 'const d=JSON.parse(require("fs").readFileSync(0,"utf8"));console.log(d[process.argv[1]]??"")' "$1"; }
post_json() { curl -s -o "$WORK/resp.json" -w '%{http_code}' -H 'Content-Type: application/json' -d "$2" "$GW$1"; }

csrf_token() { # $1=jar
  curl -s -c "$1" -b "$1" -H "Origin: $ORIGIN" "$GW/csrf" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

echo "== 1. 테스트용 인사팀 계정 로그인 (Authorization Code + PKCE) =="
HR="$WORK/hr.jar"
landing=$(browser_login "$HR" "$HR_TEST_IAM_ID" "$HR_TEST_PASSWORD")
check "로그인 후 백오피스로 이동" "$ORIGIN/" "$landing"
if grep -q "PMART_EMP_AT" "$HR"; then ok "PMart JWT 쿠키 수신"; else fail "PMart JWT 쿠키 없음"; fi
grep "PMART_EMP_AT" "$HR" | grep -q "#HttpOnly_" && ok "쿠키 HttpOnly" || fail "쿠키 HttpOnly 아님"
callback_url=$(cat "$WORK/callback_url")

echo "== 2. PMart JWT로 API 호출 (쿠키 → Gateway → Bearer 헤더) =="
check "/employee-auth/me" 200 "$(status -b "$HR" "$GW/employee-auth/me")"
check "/sample-api/hello" 200 "$(status -b "$HR" "$GW/sample-api/hello")"
check "/sample-api/hr-only (HR_ADMIN)" 200 "$(status -b "$HR" "$GW/sample-api/hr-only")"
check "/employee-service/employees 목록 (HR_ADMIN)" 200 "$(status -b "$HR" "$GW/employee-service/employees")"
TOKEN=$(grep "PMART_EMP_AT" "$HR" | awk '{print $7}')
check "Authorization 헤더로 호출 (Flutter 방식)" 200 "$(status -H "Authorization: Bearer $TOKEN" "$GW/sample-api/hello")"
echo "  JWT 헤더: $(echo "$TOKEN" | cut -d. -f1 | tr '_-' '/+' | base64 -d 2>/dev/null)"
echo "  JWT 클레임: $(echo "$TOKEN" | cut -d. -f2 | tr '_-' '/+' | { b=$(cat); while [ $(( ${#b} % 4 )) -ne 0 ]; do b="$b="; done; echo "$b"; } | base64 -d 2>/dev/null)"

echo "== 3. 인증·인가 거부 =="
check "토큰 없이 업무 API" 401 "$(status "$GW/sample-api/hello")"
check "위조 토큰" 401 "$(status -H "Authorization: Bearer $TOKEN-tampered" "$GW/sample-api/hello")"
check "Gateway 라우트 없는 경로" 401 "$(status "$GW/internal/employees/by-keycloak-id/x")"
check "Keycloak Admin API 외부 차단" 401 "$(status "$GW/iam/admin/realms/pmart-employees/users")"
check "JWKS 외부 비노출" 401 "$(status "$GW/.well-known/jwks.json")"

echo "== 4. 로그인 공격 방어 =="
check "사용한 Callback 재사용 (state 재사용)" "$ORIGIN/?login_error=invalid_state" "$(location "$callback_url")"
check "위조된 state" "$ORIGIN/?login_error=invalid_state" "$(location "$GW/employee-auth/callback?code=x&state=forged")"

echo "== 5. CORS (Gateway에서만 적용) =="
pre=$(curl -s -o /dev/null -D - -X OPTIONS -H "Origin: $ORIGIN" -H "Access-Control-Request-Method: POST" \
  -H "Access-Control-Request-Headers: content-type,x-xsrf-token" "$GW/employee-service/employees")
echo "$pre" | grep -qi "access-control-allow-origin: $ORIGIN" && ok "Preflight: 백오피스 Origin 허용" || fail "Preflight 허용 헤더 없음"
echo "$pre" | grep -qi "access-control-allow-credentials: true" && ok "Preflight: 쿠키 전송 허용" || fail "allow-credentials 없음"
evil=$(curl -s -o /dev/null -D - -X OPTIONS -H "Origin: http://evil.example" -H "Access-Control-Request-Method: POST" "$GW/employee-service/employees")
echo "$evil" | grep -qi "access-control-allow-origin" && fail "다른 Origin이 허용됨" || ok "다른 Origin Preflight 거부"
dup=$(curl -s -o /dev/null -D - -b "$HR" -H "Origin: $ORIGIN" "$GW/sample-api/hello" | grep -ci "access-control-allow-origin")
check "CORS 헤더 중복 없음" 1 "$dup"

echo "== 6. CSRF (Double Submit Cookie) =="
EMP_NO="EMP$(date +%H%M%S)"
IAM_ACCOUNT="gildong.$(date +%H%M%S)"            # IAM 계정명. IAM 계정 ID = 계정명@IAM_DOMAIN
EMP_IAM="$IAM_ACCOUNT@$IAM_DOMAIN"
# 한글 본문은 UTF-8 파일로 보냄 (Windows에서 curl 인자로 넘기면 다른 인코딩으로 전송됨)
emp_body() { # $1=사번 $2=IAM 계정명 → 파일 경로
  local f="$WORK/body-$1-$2.json"
  printf '{"employeeNo":"%s","iamAccount":"%s","firstName":"길동","lastName":"홍","department":"물류팀","initialPassword":"Temp-Pass-1234","hrAdmin":false}' "$1" "$2" > "$f"
  echo "@$f"
}
BODY=$(emp_body "$EMP_NO" "$IAM_ACCOUNT")
check "CSRF 토큰 없이 직원 등록" 403 "$(status -b "$HR" -H "Origin: $ORIGIN" -H 'Content-Type: application/json' --data-binary "$BODY" "$GW/employee-service/employees")"
XSRF=$(csrf_token "$HR")
[ -n "$XSRF" ] && ok "GET /csrf 토큰 발급" || fail "CSRF 토큰 발급 실패"
check "잘못된 CSRF 토큰" 403 "$(status -b "$HR" -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: wrong" -H 'Content-Type: application/json' --data-binary "$BODY" "$GW/employee-service/employees")"
check "다른 Origin에서 요청" 403 "$(status -b "$HR" -H "Origin: http://evil.example" -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' --data-binary "$BODY" "$GW/employee-service/employees")"

echo "== 7. 직원 등록 → Keycloak IAM 계정 발급 =="
reg() { curl -s -o "$WORK/reg.json" -w "%{http_code}" -b "$HR" -c "$HR" -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' --data-binary "$1" "$GW/employee-service/employees"; }
check "직원 등록 (CSRF 토큰 포함)" 201 "$(reg "$BODY")"
check "발급된 IAM 계정 ID" "$EMP_IAM" "$(json_field iamId < "$WORK/reg.json")"
check "Keycloak 이메일 = IAM 계정 ID" "$EMP_IAM" "$(json_field email < "$WORK/reg.json")"
KC_ROW=$(docker compose exec -T postgres psql -U postgres -d keycloak -tAc "SELECT u.username || '|' || u.email FROM user_entity u JOIN realm r ON r.id=u.realm_id WHERE r.name='pmart-employees' AND u.username='$EMP_IAM'" | tr -d '\r')
check "Keycloak username·email = IAM 계정 ID" "$EMP_IAM|$EMP_IAM" "$KC_ROW"
check "같은 사번 다시 등록" 409 "$(reg "$BODY")"
check "이미 쓰는 IAM 계정명으로 다른 사번 등록" 409 "$(reg "$(emp_body "EMP9$(date +%M%S)9" "$IAM_ACCOUNT")")"
check "IAM 계정명 형식 오류 (대문자·공백)" 400 "$(reg "$(emp_body "EMP8$(date +%M%S)8" "Bad Name")")"

echo "== 8. 새 직원 로그인 (IAM 계정 ID + 임시 비밀번호 → 변경) =="
EMP="$WORK/emp.jar"
landing=$(browser_login "$EMP" "$EMP_IAM" "Temp-Pass-1234" "New-Pass-5678")
check "IAM 계정 ID로 로그인 후 백오피스로 이동" "$ORIGIN/" "$landing"
check "/me의 IAM 계정 ID" "$EMP_IAM" "$(curl -s -b "$EMP" "$GW/employee-auth/me" | json_field iamId)"
check "/me의 사번 (인사 정보)" "$EMP_NO" "$(curl -s -b "$EMP" "$GW/employee-auth/me" | json_field employeeNo)"
landing=$(browser_login "$WORK/empno.jar" "$EMP_NO" "New-Pass-5678")
[ "$landing" != "$ORIGIN/" ] && ok "사번으로는 로그인 불가" || fail "사번으로 로그인됨"
check "새 직원 /sample-api/hello" 200 "$(status -b "$EMP" "$GW/sample-api/hello")"
check "새 직원 /sample-api/hr-only (권한 없음)" 403 "$(status -b "$EMP" "$GW/sample-api/hr-only")"
check "새 직원 직원 목록 조회 (권한 없음)" 403 "$(status -b "$EMP" "$GW/employee-service/employees")"

echo "== 9. Refresh Token (Rotation, 재사용 탐지) =="
R="$WORK/r.jar"
browser_login "$R" "$HR_TEST_IAM_ID" "$HR_TEST_PASSWORD" > /dev/null
grep "PMART_EMP_RT" "$R" | grep -q "#HttpOnly_" && ok "Refresh Token 쿠키 HttpOnly" || fail "Refresh Token 쿠키 없음 또는 HttpOnly 아님"
[ "$(grep PMART_EMP_RT "$R" | awk '{print $3}')" = "/employee-auth" ] && ok "Refresh Token 쿠키 Path=/employee-auth" || fail "Refresh Token 쿠키 Path 오류"
check "CSRF 토큰 없이 갱신" 403 "$(status -b "$R" -X POST -H "Origin: $ORIGIN" "$GW/employee-auth/refresh")"
XSRF=$(csrf_token "$R")
cp "$R" "$WORK/r-old.jar"                      # 갱신 전 Refresh Token 보관 (재사용 공격 재현용)
OLD_AT=$(grep PMART_EMP_AT "$R" | awk '{print $7}'); OLD_RT=$(grep PMART_EMP_RT "$R" | awk '{print $7}')
check "갱신 (Rotation)" 204 "$(status -b "$R" -c "$R" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" "$GW/employee-auth/refresh")"
[ "$(grep PMART_EMP_RT "$R" | awk '{print $7}')" != "$OLD_RT" ] && ok "새 Refresh Token 발급" || fail "Refresh Token이 바뀌지 않음"
[ "$(grep PMART_EMP_AT "$R" | awk '{print $7}')" != "$OLD_AT" ] && ok "새 Access Token 발급" || fail "Access Token이 바뀌지 않음"
sed -i '/PMART_EMP_AT/d' "$R"                  # Access Token 만료 상황 재현
check "Access Token 없이 API" 401 "$(status -b "$R" "$GW/sample-api/hello")"
check "만료 후 갱신" 204 "$(status -b "$R" -c "$R" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" "$GW/employee-auth/refresh")"
check "갱신 후 API" 200 "$(status -b "$R" "$GW/sample-api/hello")"
check "사용한 Refresh Token 재사용 (탈취 상황)" 401 "$(status -b "$WORK/r-old.jar" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" "$GW/employee-auth/refresh")"
check "재사용 탐지 후 정상 토큰도 폐기됨" 401 "$(status -b "$R" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" "$GW/employee-auth/refresh")"

echo "== 10. 퇴사(비활성화) 처리 =="
XSRF=$(csrf_token "$HR")
check "자기 자신 비활성화 거부" 400 "$(status -b "$HR" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" "$GW/employee-service/employees/$HR_TEST_EMPLOYEE_NO/deactivate")"
check "직원 비활성화" 200 "$(status -b "$HR" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" "$GW/employee-service/employees/$EMP_NO/deactivate")"
EXSRF=$(csrf_token "$EMP")
check "퇴사자 토큰 갱신 거부" 401 "$(status -b "$EMP" -c "$EMP" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $EXSRF" "$GW/employee-auth/refresh")"
grep -q "PMART_EMP_AT" "$EMP" && fail "갱신 실패 후에도 Access Token 쿠키가 남음" || ok "갱신 실패 시 쿠키 삭제"
landing=$(browser_login "$WORK/emp2.jar" "$EMP_IAM" "New-Pass-5678")
if [ "$landing" != "$ORIGIN/" ] && grep -qi "disabled" "$WORK/page.html"; then ok "퇴사자 재로그인 불가 (Keycloak: 계정 비활성화)"; else fail "퇴사자 재로그인 차단 확인 실패"; fi

echo "== 12. Flutter 앱 로그인 (커스텀 스킴 + 앱에 결속된 일회성 ticket) =="
check "app_challenge 없이 앱 로그인 시작" 400 "$(status "$GW/employee-auth/oauth2/start?client=mobile")"
V1=$(rand)
back=$(mobile_login "$V1")
# 앱 로그인 직후 Keycloak 세션을 종료하므로, 같은 브라우저로 다시 로그인하면 아이디 칸이 비어 있어야 함
NEXT_AUTH=$(location -c "$WORK/m.jar" -b "$WORK/m.jar" "$GW/employee-auth/oauth2/start?client=mobile&app_challenge=$(node -e "console.log(require('crypto').createHash('sha256').update('x').digest('base64url'))")")
curl -s -c "$WORK/m.jar" -b "$WORK/m.jar" "$NEXT_AUTH" > "$WORK/next.html"
if grep -q "re-authenticate" "$WORK/next.html" || grep -qE "name=\"username\"[^>]*value=\"[^\"]+\"" "$WORK/next.html"; then fail "앱 재로그인 시 아이디 칸이 이전 계정으로 고정됨"; else ok "앱 로그인 후 Keycloak 세션 종료 → 다음 로그인 때 아이디 입력 가능"; fi
[[ "$back" == pmart-employee://login-callback?ticket=* ]] && ok "커스텀 스킴으로 ticket 전달" || fail "앱 복귀 주소 오류: $back"
grep -qi "set-cookie: PMART_EMP" "$WORK/mobile_callback.h" && fail "앱 Callback에 토큰 쿠키가 실림" || ok "앱 Callback에는 토큰 없음 (ticket만)"
grep -qiE "location:.*(accessToken|eyJ)" "$WORK/mobile_callback.h" && fail "URL에 토큰이 실림" || ok "앱 복귀 URL에 토큰 없음"
T1="${back#*ticket=}"
check "가로챈 ticket (다른 app_verifier)" 401 "$(post_json /employee-auth/mobile/token "{\"ticket\":\"$T1\",\"appVerifier\":\"$(rand)\"}")"
check "실패한 ticket은 정상 verifier로도 재사용 불가" 401 "$(post_json /employee-auth/mobile/token "{\"ticket\":\"$T1\",\"appVerifier\":\"$V1\"}")"

V2=$(rand)
back=$(mobile_login "$V2"); T2="${back#*ticket=}"
check "ticket + app_verifier → 토큰 발급" 200 "$(post_json /employee-auth/mobile/token "{\"ticket\":\"$T2\",\"appVerifier\":\"$V2\"}")"
MAT=$(json_field accessToken < "$WORK/resp.json"); MRT=$(json_field refreshToken < "$WORK/resp.json")
check "같은 ticket 재사용" 401 "$(post_json /employee-auth/mobile/token "{\"ticket\":\"$T2\",\"appVerifier\":\"$V2\"}")"
check "앱 Access Token으로 API (Bearer)" 200 "$(status -H "Authorization: Bearer $MAT" "$GW/sample-api/hello")"
check "앱 Access Token으로 HR_ADMIN API" 200 "$(status -H "Authorization: Bearer $MAT" "$GW/employee-service/employees")"

check "앱 토큰 갱신 (CSRF 없이, JSON)" 200 "$(post_json /employee-auth/mobile/refresh "{\"refreshToken\":\"$MRT\"}")"
MRT2=$(json_field refreshToken < "$WORK/resp.json")
[ -n "$MRT2" ] && [ "$MRT2" != "$MRT" ] && ok "앱 Refresh Token Rotation" || fail "앱 Refresh Token이 바뀌지 않음"
check "앱의 사용한 Refresh Token 재사용" 401 "$(post_json /employee-auth/mobile/refresh "{\"refreshToken\":\"$MRT\"}")"
check "재사용 탐지 후 새 Refresh Token도 폐기" 401 "$(post_json /employee-auth/mobile/refresh "{\"refreshToken\":\"$MRT2\"}")"

V3=$(rand)
back=$(mobile_login "$V3"); T3="${back#*ticket=}"
post_json /employee-auth/mobile/token "{\"ticket\":\"$T3\",\"appVerifier\":\"$V3\"}" > /dev/null
MRT3=$(json_field refreshToken < "$WORK/resp.json")
check "앱 로그아웃" 204 "$(post_json /employee-auth/mobile/logout "{\"refreshToken\":\"$MRT3\"}")"
check "앱 로그아웃 후 갱신" 401 "$(post_json /employee-auth/mobile/refresh "{\"refreshToken\":\"$MRT3\"}")"

echo "== 13. 로그아웃 =="
XSRF=$(csrf_token "$HR")
logout=$(curl -s -D "$WORK/logout.h" -b "$HR" -c "$HR" -X POST -H "Origin: $ORIGIN" -H "X-XSRF-TOKEN: $XSRF" "$GW/employee-auth/logout")
grep -i "set-cookie: PMART_EMP_AT=;" "$WORK/logout.h" | grep -qi "max-age=0" && ok "로그아웃: JWT 쿠키 삭제" || fail "쿠키 삭제 헤더 없음"
echo "$logout" | grep -q "keycloakLogoutUrl" && ok "로그아웃: Keycloak SSO 로그아웃 주소 반환" || fail "Keycloak 로그아웃 주소 없음"
check "로그아웃 후 API" 401 "$(status -b "$HR" "$GW/sample-api/hello")"

echo
echo "결과: PASS $PASS / FAIL $FAIL"
[ "$FAIL" -eq 0 ]
