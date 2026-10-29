// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosInstance, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'

const router = vi.hoisted(() => ({
  currentRoute: { value: { path: '/admin/assets' } },
  push: vi.fn(),
}))
vi.mock('../router', () => ({ default: router }))

let http: AxiosInstance
const sessionA = 'test-only-account-a'
const sessionB = 'test-only-account-b'
const userB = JSON.stringify({ userId: 2, username: 'account-b' })

beforeEach(async () => {
  localStorage.clear()
  router.currentRoute.value.path = '/admin/assets'
  router.push.mockReset().mockResolvedValue(undefined)
  vi.resetModules()
  http = (await import('./http')).http
})

function pendingRequest(path = '/api/auth/me') {
  let dispatched!: (config: InternalAxiosRequestConfig) => void
  let rejectResponse!: (message?: string) => AxiosError
  const sent = new Promise<InternalAxiosRequestConfig>((resolve) => { dispatched = resolve })
  const adapter: AxiosAdapter = (config) => new Promise<AxiosResponse>((_resolve, reject) => {
    rejectResponse = (message) => {
      const response: AxiosResponse = { config, data: message ? { message } : {}, headers: new AxiosHeaders(), status: 401, statusText: 'Unauthorized' }
      const error = new AxiosError('Request failed with status code 401', AxiosError.ERR_BAD_REQUEST, config, undefined, response)
      reject(error)
      return error
    }
    dispatched(config)
  })
  const result = http.get(path, { adapter }).then(
    () => { throw new Error('The controlled 401 must reject the request') },
    (error: AxiosError) => error,
  )
  return { sent, result, fail: (message?: string) => rejectResponse(message) }
}

function loginB() {
  localStorage.setItem('dataocean_token', sessionB)
  localStorage.setItem('dataocean_user', userB)
}

describe('HTTP authentication response ownership', () => {
  it('keeps the new account when an old authenticated request returns 401 late', async () => {
    localStorage.setItem('dataocean_token', sessionA)
    const request = pendingRequest()
    const config = await request.sent
    expect(config.headers.get('Authorization')).toBe(`Bearer ${sessionA}`)
    loginB()

    const failure = request.fail()
    expect(await request.result).toBe(failure)
    expect(failure.message).toBe('登录状态已失效，请重新登录')
    expect(localStorage.getItem('dataocean_token')).toBe(sessionB)
    expect(localStorage.getItem('dataocean_user')).toBe(userB)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('keeps the new account when a request sent anonymously returns 401 late', async () => {
    const request = pendingRequest('/api/auth/login')
    const config = await request.sent
    expect(config.headers.get('Authorization')).toBeUndefined()
    loginB()

    const failure = request.fail('原登录请求的凭据无效')
    expect(await request.result).toBe(failure)
    expect(failure.message).toBe('原登录请求的凭据无效')
    expect(localStorage.getItem('dataocean_token')).toBe(sessionB)
    expect(localStorage.getItem('dataocean_user')).toBe(userB)
    expect(router.push).not.toHaveBeenCalled()
  })

  it('clears the current account and redirects when its own request returns 401', async () => {
    loginB()
    const request = pendingRequest()
    await request.sent

    const failure = request.fail()
    expect(await request.result).toBe(failure)
    expect(failure.message).toBe('登录状态已失效，请重新登录')
    expect(localStorage.getItem('dataocean_token')).toBeNull()
    expect(localStorage.getItem('dataocean_user')).toBeNull()
    expect(router.push).toHaveBeenCalledExactlyOnceWith({ path: '/login', query: { expired: '1' } })
  })

  it('clears a current expired session on the login page without a redirect loop', async () => {
    router.currentRoute.value.path = '/login'
    loginB()
    const request = pendingRequest()
    await request.sent
    request.fail()
    await request.result

    expect(localStorage.getItem('dataocean_token')).toBeNull()
    expect(localStorage.getItem('dataocean_user')).toBeNull()
    expect(router.push).not.toHaveBeenCalled()
  })

  it('retains the anonymous 401 redirect when no account has logged in meanwhile', async () => {
    const request = pendingRequest()
    await request.sent
    request.fail()
    await request.result

    expect(router.push).toHaveBeenCalledExactlyOnceWith({ path: '/login', query: { expired: '1' } })
  })

  it('does not redirect twice for concurrent expired requests from the current session', async () => {
    let finishNavigation!: () => void
    router.push.mockReturnValue(new Promise<void>((resolve) => { finishNavigation = resolve }))
    loginB()
    const first = pendingRequest()
    const second = pendingRequest()
    await Promise.all([first.sent, second.sent])
    first.fail()
    await first.result
    second.fail()
    await second.result

    expect(router.push).toHaveBeenCalledTimes(1)
    finishNavigation()
  })
})
