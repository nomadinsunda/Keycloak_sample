import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, logout, startLogin } from './api.js'

const LOGIN_ERRORS = {
  invalid_state: '로그인 요청이 만료되었거나 올바르지 않습니다. 다시 로그인하세요.',
  invalid_id_token: 'Keycloak 인증 결과를 확인하지 못했습니다.',
  unknown_employee: '직원 정보에 등록되지 않은 계정입니다.',
  inactive_employee: '재직 중이 아닌 계정입니다.',
}

export default function App() {
  const [me, setMe] = useState(undefined) // undefined: 확인 중, null: 로그인 안 됨
  const loginError = new URLSearchParams(window.location.search).get('login_error')

  useEffect(() => {
    api.get('/employee-auth/me')
      .then(setMe)
      .catch((e) => (e instanceof ApiError && e.status === 401 ? setMe(null) : setMe(null)))
  }, [])

  if (me === undefined) {
    return <div className="flex min-h-screen items-center justify-center"><span className="loading loading-spinner loading-lg" /></div>
  }
  return me ? <Dashboard me={me} /> : <LoginPage error={loginError} />
}

function LoginPage({ error }) {
  return (
    <div className="flex min-h-screen items-center justify-center bg-base-200 p-4">
      <div className="card w-full max-w-md bg-base-100 shadow-xl">
        <div className="card-body">
          <h1 className="card-title text-2xl">PMart 인사팀 백오피스</h1>
          <p className="text-base-content/70">직원 계정(Keycloak)으로 로그인합니다.</p>
          {error && <div role="alert" className="alert alert-error"><span>{LOGIN_ERRORS[error] ?? `로그인 실패: ${error}`}</span></div>}
          <div className="card-actions mt-4">
            <button className="btn btn-primary w-full" onClick={startLogin}>로그인</button>
          </div>
        </div>
      </div>
    </div>
  )
}

function Dashboard({ me }) {
  const isHrAdmin = me.roles?.includes('HR_ADMIN')
  return (
    <div className="min-h-screen bg-base-200">
      <div className="navbar bg-base-100 shadow-sm px-4">
        <div className="flex-1 text-lg font-bold">PMart 인사팀 백오피스</div>
        <div className="flex items-center gap-3">
          <span className="text-sm">{me.lastName}{me.firstName} ({me.iamId || me.employeeNo})</span>
          {me.roles?.map((r) => <span key={r} className="badge badge-outline">{r}</span>)}
          <button className="btn btn-sm" onClick={() => logout().catch((e) => alert(e.message))}>로그아웃</button>
        </div>
      </div>
      <main className="mx-auto max-w-5xl space-y-6 p-4">
        <TokenInfo me={me} />
        {isHrAdmin ? <EmployeeAdmin me={me} /> : (
          <div role="alert" className="alert alert-warning"><span>직원 관리는 HR_ADMIN 역할이 필요합니다.</span></div>
        )}
        <SampleApiCheck />
      </main>
    </div>
  )
}

function TokenInfo({ me }) {
  return (
    <div className="card bg-base-100 shadow">
      <div className="card-body">
        <h2 className="card-title">로그인 정보</h2>
        <p className="text-sm">PMart JWT는 HttpOnly 쿠키로 보관되어 화면에서 읽을 수 없습니다. 아래 정보는 <code>/employee-auth/me</code> 응답입니다.</p>
        <p className="text-sm">토큰 만료: {new Date(me.expiresAt).toLocaleString()}</p>
      </div>
    </div>
  )
}

// IAM 계정 도메인 (D21). IAM 계정 ID = 계정명@도메인, Keycloak 로그인 ID이자 이메일
const IAM_DOMAIN = import.meta.env.VITE_IAM_DOMAIN ?? 'pmart.test'

const EMPTY_FORM = { employeeNo: '', iamAccount: '', lastName: '', firstName: '', department: '', initialPassword: '', hrAdmin: false }

function EmployeeAdmin({ me }) {
  const [employees, setEmployees] = useState([])
  const [form, setForm] = useState(EMPTY_FORM)
  const [message, setMessage] = useState(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(() => api.get('/employee-service/employees').then(setEmployees)
    .catch((e) => setMessage({ type: 'error', text: e.message })), [])
  useEffect(() => { load() }, [load])

  const submit = async (e) => {
    e.preventDefault()
    setBusy(true)
    setMessage(null)
    try {
      const created = await api.post('/employee-service/employees', form)
      setMessage({ type: 'success', text: `IAM 계정 ${created.iamId} 발급 완료 (사번 ${created.employeeNo}). 이 계정과 초기 비밀번호로 로그인하면 비밀번호 변경 화면이 나옵니다.` })
      setForm(EMPTY_FORM)
      load()
    } catch (err) {
      const detail = err.body?.errors?.map((x) => x.defaultMessage).join(', ')
      setMessage({ type: 'error', text: detail || err.message })
    } finally {
      setBusy(false)
    }
  }
  const deactivate = async (employeeNo) => {
    if (!window.confirm(`${employeeNo} 직원을 비활성화(퇴사 처리)할까요?\nKeycloak 계정이 잠기고 로그인 세션이 모두 폐기됩니다.`)) return
    setMessage(null)
    try {
      await api.post(`/employee-service/employees/${encodeURIComponent(employeeNo)}/deactivate`)
      setMessage({ type: 'success', text: `${employeeNo} 비활성화 완료. 이미 발급된 Access Token은 최대 15분 동안 유효합니다.` })
      load()
    } catch (err) {
      setMessage({ type: 'error', text: err.message })
    }
  }
  const set = (key) => (e) => setForm({ ...form, [key]: e.target.type === 'checkbox' ? e.target.checked : e.target.value })

  return (
    <div className="card bg-base-100 shadow">
      <div className="card-body">
        <h2 className="card-title">직원 등록</h2>
        <p className="text-sm text-base-content/70">등록하면 Employee Service가 Keycloak Admin REST API로 직원 계정을 만듭니다.</p>
        <form onSubmit={submit} className="grid grid-cols-1 gap-3 md:grid-cols-3">
          <label className="input w-full md:col-span-2">
            <input placeholder="IAM 계정명 (예: gildong.hong)" value={form.iamAccount} onChange={set('iamAccount')} required
              pattern="[a-z0-9]+([._\-][a-z0-9]+)*" minLength={3} maxLength={30} title="영문 소문자·숫자와 . _ - 만 사용" />
            <span className="text-base-content/60">@{IAM_DOMAIN}</span>
          </label>
          <input className="input w-full" placeholder="사번 (인사 정보, 영문 대문자·숫자 3~20자)" value={form.employeeNo}
            onChange={(e) => setForm({ ...form, employeeNo: e.target.value.toUpperCase() })}
            required minLength={3} maxLength={20} pattern="[A-Z0-9]{3,20}" title="영문 대문자·숫자 3~20자" />
          <input className="input w-full" placeholder="성" value={form.lastName} onChange={set('lastName')} required />
          <input className="input w-full" placeholder="이름" value={form.firstName} onChange={set('firstName')} required />
          <input className="input w-full" placeholder="부서" value={form.department} onChange={set('department')} />
          <input className="input w-full" type="password" placeholder="초기 비밀번호 (8자 이상)" value={form.initialPassword} onChange={set('initialPassword')} required minLength={8} />
          <label className="label cursor-pointer gap-2">
            <input type="checkbox" className="checkbox" checked={form.hrAdmin} onChange={set('hrAdmin')} /> 인사팀 권한(HR_ADMIN)
          </label>
          <button className="btn btn-primary md:col-span-3" disabled={busy}>{busy ? '등록 중…' : '직원 등록'}</button>
        </form>
        {message && <div role="alert" className={`alert ${message.type === 'success' ? 'alert-success' : 'alert-error'}`}><span>{message.text}</span></div>}

        <h2 className="card-title mt-6">직원 목록</h2>
        <div className="overflow-x-auto">
          <table className="table table-zebra">
            <thead><tr><th>IAM 계정</th><th>사번</th><th>이름</th><th>부서</th><th>상태</th><th>역할</th><th>계정 발급</th><th /></tr></thead>
            <tbody>
              {employees.map((e) => (
                <tr key={e.employeeNo}>
                  <td>{e.iamId ?? <span className="text-base-content/40">(이전 방식)</span>}</td>
                  <td>{e.employeeNo}</td>
                  <td>{e.lastName}{e.firstName}</td>
                  <td>{e.department}</td>
                  <td>{e.status}</td>
                  <td>{e.roles.join(', ')}</td>
                  <td><span className={`badge ${e.provisioningStatus === 'COMPLETED' ? 'badge-success' : 'badge-error'}`}>{e.provisioningStatus}</span></td>
                  <td>
                    {e.status === 'ACTIVE' && e.employeeNo !== me.employeeNo && (
                      <button className="btn btn-xs btn-error btn-outline" onClick={() => deactivate(e.employeeNo)}>비활성화</button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  )
}

function SampleApiCheck() {
  const [result, setResult] = useState(null)
  const call = (path) => api.get(path)
    .then((data) => setResult({ path, ok: true, data }))
    .catch((e) => setResult({ path, ok: false, data: { status: e.status, message: e.message } }))

  return (
    <div className="card bg-base-100 shadow">
      <div className="card-body">
        <h2 className="card-title">샘플 업무 API 호출</h2>
        <div className="flex gap-2">
          <button className="btn btn-outline btn-sm" onClick={() => call('/sample-api/hello')}>모든 직원 API</button>
          <button className="btn btn-outline btn-sm" onClick={() => call('/sample-api/hr-only')}>HR_ADMIN 전용 API</button>
        </div>
        {result && (
          <div className={`alert ${result.ok ? 'alert-success' : 'alert-error'} items-start`}>
            <pre className="whitespace-pre-wrap text-xs">{result.path}{'\n'}{JSON.stringify(result.data, null, 2)}</pre>
          </div>
        )}
      </div>
    </div>
  )
}
