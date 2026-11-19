/**
 * 所有普通 JSON 接口使用同一返回结构。
 */
interface Envelope<T> {
  /**
   * 业务结果代码。
   */
  code: number;
  /**
   * 可披露反馈。
   */
  message: string;
  /**
   * 类型化业务数据。
   */
  data: T;
}

/**
 * 保留业务码与受控字段错误，界面不展示服务端堆栈。
 */
export class ApiError extends Error {
  /**
   * 当前业务拒绝或失败代码。
   */
  readonly code: number;

  /**
   * 服务端可定位的字段错误。
   */
  readonly fields: { field: string; message: string }[];

  /**
   * 创建统一错误，不保存请求正文或秘密字段。
   */
  constructor(code: number, message: string, fields: { field: string; message: string }[] = []) {
    super(message);
    this.code = code;
    this.fields = fields;
  }
}

/**
 * 仅在当前进程内保留 CSRF，登录退出后主动清除。
 */
let csrf: { headerName: string; token: string } | null = null;

/**
 * 认证边界变化后重新获取服务端令牌。
 */
export function resetCsrf() {
  csrf = null;
}

/**
 * 用同源 Cookie 调用真实服务，写操作先获取当前 CSRF。
 */
export async function api<T>(
  path: string,
  options: { method?: string; body?: unknown; command?: string } = {},
): Promise<T> {
  const method = options.method ?? 'GET';

  const headers: Record<string, string> = { Accept: 'application/json' };
  if (method !== 'GET') {
    // 登录写入也受 CSRF 保护，令牌不进入 URL 或本地持久存储。
    csrf ??= await api<{ headerName: string; token: string }>('/auth/csrf');
    headers[csrf.headerName] = csrf.token;
  }
  const multipart = options.body instanceof FormData ? options.body : undefined;
  if (options.body !== undefined && !multipart) {
    headers['Content-Type'] = 'application/json';
  }
  if (options.command) {
    headers['Idempotency-Key'] = options.command;
  }

  let response: Response;
  try {
    // 连接和超时属于交付不明，不冒充业务拒绝，也不将浏览器技术错误直接展示。
    response = await fetch(`/api/v1${path}`, {
      method,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: AbortSignal.timeout(30000),
      headers,
      body: multipart ?? (options.body === undefined ? undefined : JSON.stringify(options.body)),
    });
  } catch {
    throw new Error('服务连接暂不可用，请检查连接后重试');
  }

  let result: Envelope<T>;
  try {
    // 代理中断可能返回空正文，保留不明提交边界并给出可理解的连接反馈。
    result = await response.json();
  } catch {
    throw new Error('服务连接暂不可用，请稍后重试');
  }
  if (!result || typeof result.code !== 'number' || typeof result.message !== 'string') {
    throw new Error('服务返回暂不可用，请稍后重试');
  }
  if (!response.ok || result.code !== 200) {
    // 过期立即清除已展示的受限内容，不自动重放写请求。
    if (response.status === 401 && path !== '/auth/login' && path !== '/auth/me') {
      window.dispatchEvent(new Event('auth-expired'));
    }
    if (result.code === 403001) {
      resetCsrf();
    }
    const fields = (result.data as { fieldErrors?: { field: string; message: string }[] } | null)
      ?.fieldErrors;
    throw new ApiError(result.code, result.message, fields);
  }
  return result.data;
}

/**
 * 为 GET 查询构造确定参数，保留服务端字段名和多值绑定。
 */
export function queryString(values: Record<string, string | number | boolean | undefined | null>) {
  const params = new URLSearchParams();
  for (const [name, value] of Object.entries(values)) {
    // 空筛选不发送，避免枚举校验把空字符串当作真实编码。
    if (value !== undefined && value !== null && value !== '') {
      params.set(name, String(value));
    }
  }
  return params.toString();
}

/**
 * 回包不明时核实原命令的提交事实，不将网络错误当作失败后重放。
 */
export async function mutation<T>(
  path: string,
  method: string,
  body: unknown,
  operation: string,
  command: string,
): Promise<T> {
  try {
    return await api<T>(path, { method, body, command });
  } catch (error) {
    // 明确业务拒绝直接反馈；网络或服务端故障均先查询原命令，避免误判已提交操作。
    if (error instanceof ApiError && error.code < 500000) {
      throw error;
    }
    const status = await api<{ state: string; target: { id: string; version: number } | null }>(
      `/commands/${command}?${queryString({ operationCode: operation })}`,
    ).catch(() => null);
    if (status?.state === 'COMMITTED') {
      return {
        commandId: command,
        targetId: status.target?.id,
        version: status.target?.version,
      } as T;
    }
    throw new Error('暂时无法核实提交结果，请恢复连接后再次核实此操作');
  }
}
