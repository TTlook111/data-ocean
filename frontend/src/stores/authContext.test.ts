// @vitest-environment happy-dom
import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from './auth'
import { useAdminContextStore } from './adminContext'
import { useIamS1Store } from './iamS1'

const api = vi.hoisted(() => ({ login: vi.fn(), logout: vi.fn(), me: vi.fn(),
  getIamS1Capabilities: vi.fn(), listSimpleDatasources: vi.fn(), listSnapshots: vi.fn(), listKnowledgeDocs: vi.fn() }))
vi.mock('../api/auth', () => ({ login: api.login, logout: api.logout, me: api.me }))
vi.mock('../api/iamS1', () => ({ getIamS1Capabilities: api.getIamS1Capabilities }))
vi.mock('../api/admin/datasource', () => ({ listSimpleDatasources: api.listSimpleDatasources }))
vi.mock('../api/admin/metadata', () => ({ listSnapshots: api.listSnapshots, getSnapshotDetail: vi.fn() }))
vi.mock('../api/admin/knowledge', () => ({ listKnowledgeDocs: api.listKnowledgeDocs }))

const loginPayload = { username: 'actor-b', password: 'test-only', captchaKey: 'test', captchaCode: 'test' }
const capability = (userId: number) => ({ protocolVersion: 'IAM-SIMPLE-1' as const, userId, systemAdmin: true,
  globalFunctions: [], datasourceCapabilities: [], queryUse: false, viewSql: false, export: false, permissionRevision: 1 })

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('dataocean_token', 'test-session-a')
  localStorage.setItem('dataocean_user', JSON.stringify({ userId: 1, username: 'actor-a' }))
  setActivePinia(createPinia())
  vi.resetAllMocks()
  const iam = useIamS1Store()
  iam.snapshot = capability(1)
  iam.loaded = true
  api.getIamS1Capabilities.mockResolvedValue({ data: capability(2) })
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: 1, name: 'Actor A source', databaseName: 'fixture_a' }] })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 11, datasourceId: 1, snapshotVersion: 1 }] } })
  api.listKnowledgeDocs.mockResolvedValue({ data: { records: [] } })
  api.login.mockResolvedValue({ data: { token: 'test-session-b', userId: 2, username: 'actor-b', passwordChanged: true } })
  api.logout.mockResolvedValue({ data: null })
})

it('revalidates the datasource scope after another account logs in within the SPA', async () => {
  const context = useAdminContextStore()
  await context.initialize()
  expect(context.currentDatasource?.name).toBe('Actor A source')
  api.listSimpleDatasources.mockResolvedValue({ data: [] })

  await useAuthStore().login(loginPayload)
  await context.initialize()

  expect(api.listSimpleDatasources).toHaveBeenCalledTimes(2)
  expect(context.datasources).toEqual([])
  expect(context.snapshots).toEqual([])
  expect(context.datasourceId).toBeUndefined()
  expect(context.snapshotId).toBeUndefined()
})

it('drops the previous session lists on logout but restores a still-valid selection after login', async () => {
  localStorage.setItem('dataocean_user', JSON.stringify({ userId: 2, username: 'actor-b' }))
  localStorage.setItem('dataocean_admin_context', JSON.stringify({ datasourceId: 2, snapshotId: 22 }))
  api.listSimpleDatasources.mockResolvedValue({ data: [{ id: 1 }, { id: 2 }] })
  api.listSnapshots.mockResolvedValue({ data: { records: [{ id: 22, datasourceId: 2, snapshotVersion: 2 }] } })
  const context = useAdminContextStore()
  const auth = useAuthStore()
  await context.initialize()

  await auth.logout()
  expect(context.initialized).toBe(false)
  expect(context.datasources).toEqual([])
  expect(context.snapshots).toEqual([])

  await auth.login(loginPayload)
  await context.initialize()
  expect(api.listSimpleDatasources).toHaveBeenCalledTimes(2)
  expect(context.datasourceId).toBe(2)
  expect(context.snapshotId).toBe(22)
})

it('does not let an old account initialization response refill the new account scope', async () => {
  let finish!: (value: unknown) => void
  api.listSimpleDatasources.mockReturnValueOnce(new Promise((resolve) => { finish = resolve }))
    .mockResolvedValueOnce({ data: [] })
  const context = useAdminContextStore()
  const oldRequest = context.initialize()

  await useAuthStore().login(loginPayload)
  const currentRequest = context.initialize()
  finish({ data: [{ id: 1, name: 'Actor A source', databaseName: 'fixture_a' }] })
  await Promise.all([oldRequest, currentRequest])

  expect(api.listSimpleDatasources).toHaveBeenCalledTimes(2)
  expect(context.datasources).toEqual([])
  expect(context.snapshots).toEqual([])
  expect(context.loading).toBe(false)
})

it('clears cached account details when another account logs in', async () => {
  const auth = useAuthStore()
  api.me.mockResolvedValue({ data: { id: 1, username: 'actor-a' } })
  await auth.fetchUserInfo()
  await auth.login(loginPayload)
  expect(auth.currentUser).toBeNull()
  expect(localStorage.getItem('dataocean_current_user')).toBeNull()
})

it('does not let a late user-info response refill another account details', async () => {
  let finish!: (value: unknown) => void
  api.me.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
    .mockResolvedValueOnce({ data: { id: 2, username: 'actor-b' } })
  const auth = useAuthStore()
  const oldRequest = auth.fetchUserInfo()
  await auth.login(loginPayload)
  await auth.fetchUserInfo()
  finish({ data: { id: 1, username: 'actor-a' } })
  await oldRequest
  expect(auth.currentUser?.username).toBe('actor-b')
  expect(JSON.parse(localStorage.getItem('dataocean_current_user') || '{}').username).toBe('actor-b')
})

it('does not let an earlier logout response clear the new login', async () => {
  let finish!: (value: unknown) => void
  api.logout.mockReturnValueOnce(new Promise(resolve => { finish = resolve }))
  const auth = useAuthStore()
  const oldLogout = auth.logout()
  await auth.login(loginPayload)
  finish({ data: null })
  await oldLogout
  expect(auth.token).toBe('test-session-b')
  expect(auth.user?.username).toBe('actor-b')
  expect(localStorage.getItem('dataocean_token')).toBe('test-session-b')
})

it('still saves user details returned for the current session', async () => {
  const auth = useAuthStore()
  api.me.mockResolvedValue({ data: { id: 1, username: 'actor-a' } })
  await auth.fetchUserInfo()
  expect(auth.currentUser?.username).toBe('actor-a')
  expect(JSON.parse(localStorage.getItem('dataocean_current_user') || '{}').username).toBe('actor-a')
})
