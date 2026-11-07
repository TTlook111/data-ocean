// @vitest-environment happy-dom
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { getExperienceDetails, type ExperienceDetail } from '../../../api/admin/dashboard'
import type { PageResult } from '../../../api/types'
import { cutoff, deferred, grant, openWorkbench, secondStart } from './workbench.fixtures'

vi.mock('../../../api/admin/dashboard', () => ({ getExperienceDetails: vi.fn(), getQueryExperience: vi.fn() }))
const slow = '/admin/workbench/details?period=today&kind=slow&asOf=' + cutoff + '&bucketStart=' + secondStart
const record = { taskId: 'public-123', createdAt: secondStart, datasourceId: 5, seconds: 16 }
const page = { records: [record], current: 1, size: 10, total: 12 }
beforeEach(() => { vi.mocked(getExperienceDetails).mockReset().mockResolvedValue(page) })

describe('工作台的只读明细', () => {
  // 分页与原速率图使用同一截止点和桶，不从总任务数或数据库 ID 猜测成员。
  it('精确传入服务器桶与截止点，翻页保持原范围', async () => {
    const { wrapper } = await openWorkbench(slow)
    const scope = { period: 'today', kind: 'slow', asOf: cutoff, bucketStart: secondStart }
    expect(getExperienceDetails).toHaveBeenCalledWith(scope, 1)
    expect(wrapper.text()).toContain('public-123')
    expect(wrapper.get('.back').attributes('href')).toContain('bucketStart=')
    vi.mocked(getExperienceDetails).mockResolvedValue({ ...page, current: 2 })
    await wrapper.get('nav button:last-child').trigger('click')
    await flushPromises()
    expect(getExperienceDetails).toHaveBeenLastCalledWith(scope, 2)
  })
  it('权限与参数无效时不读取，反馈明细不可携带单个审计桶', async () => {
    const { wrapper, router } = await openWorkbench(slow, ['governance:field:view'])
    expect(wrapper.text()).toContain('没有查看此范围问数明细的权限')
    await router.push(slow.replace('kind=slow', 'kind=feedback'))
    await flushPromises()
    expect(wrapper.text()).toContain('明细范围无效')
    expect(getExperienceDetails).not.toHaveBeenCalled()
  })
  it('失去反馈范围后丢弃未完成的评论读取', async () => {
    const late = deferred<PageResult<ExperienceDetail>>()
    vi.mocked(getExperienceDetails).mockReturnValue(late.promise)
    const { wrapper } = await openWorkbench('/admin/workbench/details?kind=feedback&period=today&asOf=' + cutoff)
    grant(['audit:view'], true, 2)
    late.resolve({ ...page, records: [{ ...record, comment: '早期反馈内容' }] })
    await flushPromises()
    expect(wrapper.text()).not.toContain('早期反馈内容')
    expect(wrapper.text()).toContain('没有查看此范围问数明细的权限')
  })
})
