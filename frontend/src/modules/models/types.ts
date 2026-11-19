/**
 * 服务端已支持的生成模型协议与后台显示名称。
 */
export const modelProtocols = {
  OPENAI_CHAT_COMPLETIONS: 'OpenAI Chat Completions',
  ANTHROPIC_MESSAGES: 'Anthropic Messages',
} as const;

/**
 * 模型配置与不可变修订的可披露字段。
 */
export interface ModelConfig {
  /**
   * 模型记录标识。
   */
  id: string;
  /**
   * 当次内容修订标识。
   */
  revisionId: string;
  /**
   * 配置修订号。
   */
  revision: number;
  /**
   * 后台配置名称。
   */
  name: string;
  /**
   * 选定接口格式。
   */
  protocol: keyof typeof modelProtocols;
  /**
   * SDK 服务地址。
   */
  endpoint: string;
  /**
   * 模型在提供方的调用名称。
   */
  modelName: string;
  /**
   * 单次超时上限，秒。
   */
  timeoutSeconds: number;
  /**
   * 当前启停状态。
   */
  enabled: boolean;
  /**
   * 用于并发写入保护。
   */
  version: number;
}

/**
 * 编辑提交仅包含可维护字段与明确填入的新凭据。
 */
export type ModelInput = Pick<
  ModelConfig,
  'name' | 'protocol' | 'endpoint' | 'modelName' | 'timeoutSeconds'
> & {
  /**
   * 仅为新增或更换提交，编辑留空沿用原凭据。
   */
  credential?: string;
};

/**
 * 已接入业务节点的独立模型选择。
 */
export interface ModelNode {
  /**
   * 业务节点固定标识。
   */
  code: string;
  /**
   * 未分配时为空。
   */
  modelId: string | null;
  /**
   * 节点分配并发版本。
   */
  version: number;
}

/**
 * 调用失败的稳定提示，供治理和诊断复用。
 */
export const modelFailureLabels: Record<string, string> = {
  STALE_INPUT: '结构、资料或任务权限发生变化，请重新发起。',
  CONTEXT_UNAVAILABLE: '暂时无法读取本次生成所需的资料。',
  MODEL_UNAVAILABLE: '当前节点的模型已停用或不可用。',
  MODEL_AUTH: '模型服务拒绝访问，请核对凭据。',
  MODEL_RATE_LIMIT: '模型服务限流，请稍后重试。',
  MODEL_TIMEOUT: '本次生成已超过处理时限。',
  MODEL_FORMAT: '模型未返回符合要求的建议。',
  MODEL_RESULT_UNKNOWN: '模型调用结果待核查，请先查看调用记录。',
};
