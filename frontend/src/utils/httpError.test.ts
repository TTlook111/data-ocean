import { describe, expect, it } from 'vitest'
import { getHttpErrorMessage } from './httpError'

describe('getHttpErrorMessage', () => {
  it('将 Axios 500 默认英文文案转换为中文提示', () => {
    expect(getHttpErrorMessage({
      message: 'Request failed with status code 500',
      response: { status: 500, data: {} },
    })).toBe('服务暂时不可用，请稍后重试')
  })

  it('优先保留后端返回的中文业务提示', () => {
    expect(getHttpErrorMessage({ response: { status: 500, data: { message: '知识文档尚未发布' } } })).toBe('知识文档尚未发布')
  })

  it('为无权限和网络失败提供可操作提示', () => {
    expect(getHttpErrorMessage({ response: { status: 403, data: {} } })).toBe('当前账号没有执行此操作的权限')
    expect(getHttpErrorMessage({ code: 'ERR_NETWORK', message: 'Network Error' })).toBe('网络连接失败，请检查网络后重试')
  })
})
