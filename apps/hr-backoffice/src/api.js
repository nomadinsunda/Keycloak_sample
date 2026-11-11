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

async function request(method, path, body, retried = false) {
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

  // CSRF 토큰이 없거나 바뀌었으면 한 번만 다시 받아서 재시도
  if (res.status === 403 && stateChanging && !retried) {
    csrfToken = null
    return request(method, path, body, true)
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
