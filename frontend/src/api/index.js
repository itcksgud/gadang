import axios from 'axios'

const api = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
  // refresh 토큰이 HttpOnly 쿠키로 오가므로 크로스오리진에서도 쿠키 전송 허용
  withCredentials: true,
})

// refresh 쿠키만으로 동작하는 엔드포인트 — 만료된 access 토큰을 붙이면
// JwtAuthenticationFilter가 401로 끊어버리므로 Authorization을 생략한다
const COOKIE_ONLY_PATHS = ['/auth/refresh', '/auth/logout']

// 요청 인터셉터 - JWT 토큰 자동 첨부
api.interceptors.request.use((config) => {
  if (COOKIE_ONLY_PATHS.some((p) => config.url?.includes(p))) {
    return config
  }
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 동시에 여러 요청이 401을 맞아도 refresh 호출은 한 번만 나가도록 공유
let refreshPromise = null

function refreshAccessToken() {
  if (!refreshPromise) {
    refreshPromise = api
      .post('/auth/refresh')
      .finally(() => {
        refreshPromise = null
      })
  }
  return refreshPromise
}

function clearSession() {
  localStorage.removeItem('token')
  localStorage.removeItem('user')
  // useAuth 모듈의 반응형 ref는 직접 접근 불가 — 이벤트로 동기화
  window.dispatchEvent(new Event('auth:logout'))
}

// 응답 인터셉터 - access 만료(401) 시 refresh 쿠키로 재발급 후 원 요청 1회 재시도
api.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config
    const status = error.response?.status
    const isAuthCall = original?.url?.includes('/auth/')

    if (status === 401 && original && !original._retried && !isAuthCall) {
      original._retried = true
      try {
        const { data } = await refreshAccessToken()
        if (data.success && data.data?.token) {
          localStorage.setItem('token', data.data.token)
          if (data.data.user) {
            localStorage.setItem('user', JSON.stringify(data.data.user))
          }
          window.dispatchEvent(new CustomEvent('auth:refreshed', { detail: data.data }))
          original.headers.Authorization = `Bearer ${data.data.token}`
          return api(original)
        }
      } catch {
        // refresh 실패(만료·재사용 감지) → 아래에서 세션 정리
      }
      clearSession()
    } else if (status === 401 && !isAuthCall) {
      clearSession()
    }
    return Promise.reject(error)
  },
)

export default api
