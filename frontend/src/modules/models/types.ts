/**
 * 后台模型协议和显示名称，与服务端可保存协议对应。
 */
export const modelProtocols = {
  OPENAI_CHAT_COMPLETIONS: 'OpenAI Chat Completions',
  ANTHROPIC_MESSAGES: 'Anthropic Messages',
  OPENAI_EMBEDDINGS: 'OpenAI Embeddings',
  COHERE_RERANK: 'Cohere Rerank',
} as const;

/**
 * 模型能力与接口格式分别表达，节点只接受匹配类型。
 */
export const modelCapabilities = {
  GENERATION: '生成',
  EMBEDDING: 'Embedding',
  RERANK: 'Rerank',
} as const;

/**
 * 由声明的接口格式确定模型能力。
 */
export const protocolCapability: Record<
  keyof typeof modelProtocols,
  keyof typeof modelCapabilities
> = {
  OPENAI_CHAT_COMPLETIONS: 'GENERATION',
  ANTHROPIC_MESSAGES: 'GENERATION',
  OPENAI_EMBEDDINGS: 'EMBEDDING',
  COHERE_RERANK: 'RERANK',
};

/**
 * 产品方案定义的节点及其简要用途。
 */
export const modelNodeInfo: Record<string, { name: string; description: string; module?: string }> =
  {
    'table-description': {
      name: '表说明生成',
      description: '结合表结构和业务说明，建议表的用途、粒度和范围。',
    },
    'field-explanation': {
      name: '字段解释生成',
      description: '结合字段结构和业务依据，建议字段含义及用法。',
    },
    'relation-discovery': {
      name: '关联候选发现',
      description: '根据同源外键和业务资料，给出可审核的表关联。',
    },
    'metric-discovery': {
      name: '指标候选发现',
      description: '根据实际字段和业务依据，给出指标公式及适用条件。',
    },
    'document-plan': {
      name: '知识文档规划',
      module: '知识库与发布',
      description: '建议文档清单、用途与依赖。',
    },
    'document-write': {
      name: '知识正文生成与更新',
      module: '知识库与发布',
      description: '依据已确认定义生成正文和更新差异。',
    },
    'question-suggestion': {
      name: '标准题建议',
      module: '知识库与发布',
      description: '建议标准问题、验证条件和边界案例。',
    },
    intent: { name: '问题理解', module: '智能问数', description: '结合有效对话理解并规范问题。' },
    clarification: {
      name: '追问生成',
      module: '智能问数',
      description: '追问仍会影响结果的缺项或歧义。',
    },
    'history-summary': {
      name: '历史对话摘要',
      module: '智能问数',
      description: '整理本次仍可使用的对话上下文。',
    },
    'sql-generate': {
      name: 'SQL 生成',
      module: '智能问数',
      description: '根据问题和正式依据生成 SQL 候选。',
    },
    'sql-semantic-check': {
      name: 'SQL 意图语义核对',
      module: '智能问数',
      description: '辅助核对条件、粒度、单位与需求覆盖。',
    },
    'output-meaning': {
      name: '输出含义识别',
      module: '智能问数',
      description: '识别结果别名和计算列的业务含义。',
    },
    'sql-repair': {
      name: 'SQL 修正',
      module: '智能问数',
      description: '按实际差异和权限范围修正 SQL。',
    },
    'result-explanation': {
      name: '结果解释',
      module: '智能问数',
      description: '解释实际结果并提供适用的图表建议。',
    },
    'rag-embedding': {
      name: '知识与问题向量化',
      module: 'RAG',
      description: '为知识索引与问题检索选择配套模型。',
    },
    'rag-rerank': {
      name: '候选知识重排',
      module: 'RAG',
      description: '可选地比较候选知识相关性。',
    },
    'feedback-similarity': {
      name: '反馈问题向量化',
      module: '反馈处理',
      description: '向量化反馈，用于召回相似候选。',
    },
  };

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
   * 对应节点可选择的模型能力。
   */
  capability: keyof typeof modelCapabilities;
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
 * 已保存配置修订的最近连接测试。
 */
export interface ModelConnectionTest {
  /** 固定合成请求是否返回预期结构。 */
  passed: boolean;
  /** 可披露的失败原因编码，通过时为空。 */
  failureCode: string | null;
  /** 测试完成的 UTC 时刻。 */
  testedAt: string;
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
  MODEL_CALL_LIMIT: '已达到本次生成的模型调用次数上限。',
  MODEL_FORMAT: '模型未返回符合要求的建议。',
  MODEL_RESULT_UNKNOWN: '模型调用结果待核查，请先查看调用记录。',
  MODEL_TEST_UNAVAILABLE: '暂时无法从 Python 服务取得测试结果。',
};
