import assert from "node:assert/strict";

/**
 * 真实 HTTP 验收客户端，Cookie 和令牌只留在当前进程内。
 */
export class IdentityClient {
  /**
   * 当前开发服务地址。
   */
  base;

  /**
   * 当前浏览器等价会话的 Cookie 集合。
   */
  cookies = new Map();

  /**
   * 当前会话的 CSRF 信息。
   */
  token = null;

  /**
   * 接受明确的本机地址，不读取或输出连接秘密。
   */
  constructor(base = "http://127.0.0.1:18080") {
    this.base = base;
  }

  /**
   * 发送实际请求并保留业务错误，允许用例检查拒绝行为。
   */
  async request(path, { method = "GET", body, command, csrf = true } = {}) {
    const headers = {
      Cookie: [...this.cookies]
        .map(([name, value]) => `${name}=${value}`)
        .join(";"),
    };
    if (method !== "GET" && csrf) {
      if (!this.token) {
        // 依赖未就绪时报告原业务状态，不把空令牌误报为客户端属性异常。
        const issued = await this.request("/auth/csrf");
        assert.equal(
          issued.code,
          200,
          `CSRF 未就绪：HTTP ${issued.status}, code=${issued.code}`,
        );
        this.token = issued.data;
      }
      headers[this.token.headerName] = this.token.token;
    }
    // 首次获取 CSRF 时会创建匿名会话，随后写请求必须携带新 Cookie。
    headers.Cookie = [...this.cookies]
      .map(([name, value]) => `${name}=${value}`)
      .join(";");
    const multipart = body instanceof FormData;
    if (body !== undefined && !multipart) headers["Content-Type"] = "application/json";
    if (command) headers["Idempotency-Key"] = command;

    const response = await fetch(`${this.base}/api/v1${path}`, {
      method,
      headers,
      body: multipart || body === undefined ? body : JSON.stringify(body),
      signal: AbortSignal.timeout(30000),
    });
    for (const header of response.headers.getSetCookie()) {
      // 只提取 Cookie 值，禁止把响应头写入报告。
      const [name, ...value] = header.split(";")[0].split("=");
      this.cookies.set(name, value.join("="));
    }

    const result = await response.json();
    return { status: response.status, ...result };
  }

  /**
   * 成功响应必须同时具有正确 HTTP 状态与统一成功业务码。
   */
  async ok(path, options = {}, status = 200) {
    const result = await this.request(path, options);
    assert.equal(
      result.status,
      status,
      `${options.method ?? "GET"} ${path}: HTTP ${result.status}, code=${result.code}, message=${result.message}`,
    );
    assert.equal(result.code, 200, `${path}: code=${result.code}`);
    return result.data;
  }

  /**
   * 登录成功后主动刷新 CSRF，模拟浏览器认证边界轮换。
   */
  async login(username, password) {
    // 改密或退出后重新登录必须取得新的匿名会话令牌。
    this.token = null;
    const result = await this.ok("/auth/login", {
      method: "POST",
      body: { username, password },
    });
    this.token = null;
    return result;
  }

  /**
   * 先获得服务端核对，再以唯一命令提交同一份完整提案。
   */
  async reviewed(
    resource,
    action,
    proposal,
    {
      id,
      version,
      path = "",
      method = id ? "PUT" : "POST",
      body = proposal,
      command = crypto.randomUUID(),
    } = {},
  ) {
    const preview = await this.ok(
      `${resource}${id ? `/${id}` : ""}/impact-preview`,
      {
        method: "POST",
        body: id
          ? { action, expectedVersion: version, proposal }
          : { proposal },
      },
    );
    return this.ok(
      `${resource}${id ? `/${id}` : ""}${path}`,
      {
        method,
        command,
        body: {
          ...body,
          ...(id ? { expectedVersion: version } : {}),
          reviewToken: preview.reviewToken,
        },
      },
      id ? 200 : 201,
    );
  }
}
