// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { getQueryExperience } from '../api/admin/dashboard'
import { cutoff, firstStart, openWorkbench, secondStart, summary } from './admin/workbench/workbench.fixtures'

vi.mock('../api/admin/dashboard', () => ({ getQueryExperience: vi.fn(), getExperienceDetails: vi.fn() }))
beforeEach(() => { vi.mocked(getQueryExperience).mockReset().mockImplementation(async p => summary(p)) })

describe('问数运行工作台', () => {
  // 原工作台只读原则延续到新的展示口径；冷启动和选周期均按 Java 数据显示。
  it('分别展示受理耗时和反馈分母，缺少独立抽检时不生成准确率', async () => {
    const { wrapper, router } = await openWorkbench()
    expect(wrapper.text()).toContain('超目标 2 / 6 次')
    expect(wrapper.text()).toContain('60 个回答中有 25 个获得评价')
    expect(wrapper.text()).toContain('占已评价回答 20%')
    expect(wrapper.text()).toContain('暂无独立抽检数据')
    expect(wrapper.find('table').exists()).toBe(false)
    expect(wrapper.get('.big-value strong').classes()).not.toContain('breach')
    expect(wrapper.get('select').element.value).toBe(secondStart)
    expect(router.resolve(wrapper.get('.chart-footer a').attributes('href')!).query).toEqual({
      kind: 'slow', period: 'today', asOf: cutoff, bucketStart: secondStart,
    })
    await wrapper.get('select').setValue(firstStart)
    expect(wrapper.find('.chart-footer a').exists()).toBe(false)
    await router.push('/admin/workbench?period=week')
    await flushPromises()
    expect(getQueryExperience).toHaveBeenLastCalledWith('week')
    const q = router.resolve(wrapper.get('.feedback a').attributes('href')!).query
    expect(q).toEqual({ kind:'feedback', period:'week', asOf:cutoff, returnBucket:secondStart })
  })
  it('没有计时或评价样本时保持空值，只有一个时段有样本时不画趋势', async () => {
    const r = summary()
    r.usage = { ...r.usage!, measured: 0, avgSeconds: null, p95Seconds: null, slow: 0, buckets: [] }
    r.feedback = { eligible: 9, likes: 0, dislikes: 0, previousLikes: null, previousDislikes: null }
    vi.mocked(getQueryExperience).mockResolvedValue(r)
    const { wrapper } = await openWorkbench()
    expect(wrapper.text()).toContain('暂无计时样本')
    expect(wrapper.text()).toContain('缺少可核对的起止时间')
    expect(wrapper.text()).not.toContain('0 / 0 次')
    expect(wrapper.text()).toContain('暂无评价')
    expect(wrapper.find('.feedback a').exists()).toBe(false)
    r.usage = { ...summary().usage!, buckets: [summary().usage!.buckets[0]!] }
    await wrapper.get('.refresh').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('仅 09:00–09:30 有可计时回答')
    expect(wrapper.find('waiting-time-chart-stub').exists()).toBe(false)
  })
  it('工作台只有反馈权限时保留合法评价，不展示任何审计计数', async () => {
    const { wrapper } = await openWorkbench(undefined, ['governance:field:view'])
    expect(wrapper.find('.speed').exists()).toBe(false)
    expect(wrapper.find('.traffic').exists()).toBe(false)
    expect(wrapper.text()).toContain('用户反馈')
  })
  it('旧来源书签只打开授权的精确来源，非法来源清晰报错', async () => {
    const { router, wrapper } = await openWorkbench('/admin/workbench?focusSourceId=5')
    expect(router.currentRoute.value.path).toBe('/admin/data-sources/5')
    await router.push('/admin/workbench?focusSourceId=7')
    await flushPromises()
    expect(wrapper.text()).toContain('没有查看指定来源的权限')
    await router.push('/admin/workbench?focusSourceId=1oops')
    await flushPromises()
    expect(wrapper.text()).toContain('指定来源标识无效')
    expect(getQueryExperience).not.toHaveBeenCalled()
  })
})
