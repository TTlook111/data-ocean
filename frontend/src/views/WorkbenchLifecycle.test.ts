// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { getQueryExperience, type QueryExperience } from '../api/admin/dashboard'
import { deferred, grant, openWorkbench, summary } from './admin/workbench/workbench.fixtures'

vi.mock('../api/admin/dashboard', () => ({ getQueryExperience: vi.fn(), getExperienceDetails: vi.fn() }))
beforeEach(() => { vi.mocked(getQueryExperience).mockReset().mockImplementation(async p => summary(p)) })

describe('工作台读取生命周期', () => {
  it('没有工作台权限时零读取，有工作台但没有统计范围不伪造零', async () => {
    const denied = await openWorkbench(undefined, ['audit:view'], false)
    expect(denied.wrapper.text()).toContain('没有查看工作台的权限')
    expect(getQueryExperience).not.toHaveBeenCalled()
    const limited = await openWorkbench(undefined, [])
    expect(limited.wrapper.text()).toContain('没有可查看的问数审计或反馈统计')
    expect(limited.wrapper.find('.big-value').exists()).toBe(false)
  })
  // 时间切换、业务拒绝和撤权必须清理旧指标，迟到的成功不能重新暴露数据。
  it('旧周期晚回不能覆盖新周期，工作台撤权立即清理', async () => {
    const late = deferred<QueryExperience>()
    vi.mocked(getQueryExperience).mockImplementationOnce(() => late.promise)
    const { wrapper, router } = await openWorkbench()
    await router.push('/admin/workbench?period=week')
    await flushPromises()
    expect(wrapper.text()).toContain('超目标 2 / 6')
    late.resolve({ ...summary(), usage: null })
    await flushPromises()
    expect(wrapper.find('.speed').exists()).toBe(true)
    grant([], false, 2)
    await flushPromises()
    expect(wrapper.text()).not.toContain('超目标 2 / 6')
    expect(wrapper.text()).toContain('没有查看工作台的权限')
  })
  it('接口 403 及响应不完整时不保留旧统计', async () => {
    const { wrapper } = await openWorkbench()
    vi.mocked(getQueryExperience).mockRejectedValueOnce({ response: { status: 403, data: { message: '负责范围已撤销' } } })
    await wrapper.get('.refresh').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('负责范围已撤销')
    expect(wrapper.find('.feedback').exists()).toBe(false)
    vi.mocked(getQueryExperience).mockResolvedValueOnce({ ...summary(), feedback: undefined } as unknown as QueryExperience)
    await wrapper.get('.error-retry').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('返回的范围或指标不完整')
  })
})
