const statusMessages: Record<number, string> = {
  400: '请求参数有误，请检查后重试',
  401: '登录状态已失效，请重新登录',
  403: '当前账号没有执行此操作的权限',
  404: '请求的资源不存在或已被移除',
  409: '数据已发生变化，请刷新后重试',
  422: '提交的数据无法处理，请检查后重试',
  429: '操作过于频繁，请稍后重试',
  500: '服务暂时不可用，请稍后重试',
  502: '后台服务暂时不可用，请稍后重试',
  503: '后台服务正在维护，请稍后重试',
}

function isGenericAxiosMessage(message: string) {
  return /^(Request failed with status code \d+|Network Error|timeout of \d+ms exceeded)$/i.test(message.trim())
}

/**
 * 将 Axios 原始错误转换为面向用户的中文提示。
 * 后端返回的业务 message 优先保留；只有缺失或仍是 Axios 默认英文文案时才按状态码兜底。
 */
export function getHttpErrorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  const value = error as {
    response?: { status?: number; data?: { message?: unknown } }
    code?: string
    message?: string
  } | null
  const response = value?.response
  const serverMessage = response?.data?.message
  if (typeof serverMessage === 'string' && serverMessage.trim() && !isGenericAxiosMessage(serverMessage)) {
    return serverMessage
  }

  if (typeof response?.status === 'number' && statusMessages[response.status]) {
    return statusMessages[response.status]
  }

  const message = typeof value?.message === 'string' ? value.message : ''
  if (value?.code === 'ECONNABORTED' || /timeout/i.test(message)) {
    return '请求超时，请稍后重试'
  }
  if (!response && (value?.code === 'ERR_NETWORK' || message === 'Network Error')) {
    return '网络连接失败，请检查网络后重试'
  }
  if (message && !isGenericAxiosMessage(message)) return message
  return fallback
}
