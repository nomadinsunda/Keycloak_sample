// Gateway 호출 모듈
// - PMart JWT는 HttpOnly 쿠키라서 JavaScript가 읽지 않습니다. credentials: 'include'로 브라우저가 쿠키를 보내게 합니다. (D15)
// - 백오피스와 Gateway는 Origin이 달라 Gateway가 CORS를 허용합니다. (D16)
// - 데이터를 바꾸는 요청에는 GET /csrf로 받은 토큰을 X-XSRF-TOKEN 헤더로 보냅니다. (D17)

export const GATEWAY_URL = import.meta.env.VITE_GATEWAY_URL ?? 'http://localhost:8080'

let csrfToken = null

async function fetchCsrfToken() {
  const res = await fetch(`${GATEWAY_URL}/csrf`, { credentials: 'include' })
  if (!res.ok) throw new ApiError(res.status, 'CSRF 토큰 발급 실패')
  csrfToken = (await res.json()).token
  return csrfToken
}

export class ApiError extends Error {
  constructor(status, message, body) {
    super(message)
    this.status = status
    this.body = body
  }
}

// Access Token(15분) 만료 시 Refresh Token 쿠키로 갱신 (D19).
// 여러 요청이 동시에 401을 받아도 갱신은 한 번만 합니다.
// (같은 Refresh Token을 두 번 보내면 서버가 재사용으로 판단해 세션 전체를 폐기하기 때문)
let refreshing = null

function refreshSession() {
  refreshing ??= fetch(`${GATEWAY_URL}/employee-auth/refresh`, {
    method: 'POST',
    credentials: 'include',
    headers: { 'X-XSRF-TOKEN': csrfToken ?? '' },
  })
    .then(async (res) => {
      if (res.status === 403) {
        // CSRF 토큰이 없거나 바뀐 경우 한 번 다시 받아서 재시도
        await fetchCsrfToken()
        res = await fetch(`${GATEWAY_URL}/employee-auth/refresh`, {
          method: 'POST',
          credentials: 'include',
          headers: { 'X-XSRF-TOKEN': csrfToken },
        })
      }
      return res.ok
    })
    .finally(() => { refreshing = null })
  return refreshing
}

async function request(method, path, body, retried = false, refreshed = false) {
  const stateChanging = !['GET', 'HEAD'].includes(method)
  const headers = {}
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  if (stateChanging) headers['X-XSRF-TOKEN'] = csrfToken ?? (await fetchCsrfToken())

  const res = await fetch(`${GATEWAY_URL}${path}`, {
    method,
    headers,
    credentials: 'include',
    body: body !== undefined ? JSON.stringify(body) : undefined,
  })

  // Access Token 만료: 갱신 후 한 번만 재시도. 갱신도 실패하면 401 그대로 (로그인 화면으로)
  if (res.status === 401 && !refreshed && (await refreshSession())) {
    return request(method, path, body, retried, true)
  }
  // CSRF 토큰이 없거나 바뀌었으면 한 번만 다시 받아서 재시도
  if (res.status === 403 && stateChanging && !retried) {
    csrfToken = null
    return request(method, path, body, true, refreshed)
  }
  const text = await res.text()
  const data = text ? safeJson(text) : null
  if (!res.ok) throw new ApiError(res.status, data?.detail ?? data?.message ?? `요청 실패 (${res.status})`, data)
  return data
}

function safeJson(text) {
  try {
    return JSON.parse(text)
  } catch {
    return { message: text }
  }
}

export const api = {
  get: (path) => request('GET', path),
  post: (path, body) => request('POST', path, body),
}

/** 로그인: 페이지 이동으로 시작 (Employee Auth Service → Keycloak 로그인 화면) */
export function startLogin() {
  window.location.href = `${GATEWAY_URL}/employee-auth/oauth2/start`
}

/** 로그아웃: PMart JWT 쿠키 삭제 후 Keycloak SSO 세션도 로그아웃 */
export async function logout() {
  const { keycloakLogoutUrl } = await api.post('/employee-auth/logout')
  window.location.href = keycloakLogoutUrl
}
