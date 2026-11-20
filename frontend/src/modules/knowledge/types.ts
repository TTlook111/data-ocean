import type { Target } from '../governance/types';

/** 候选页查看修订时保留原查询和触发入口。 */
export type ReleaseRevisionReturn = {
  /** 原来候选页的来源、文档和分页查询。 */
  search: string;
  /** 返回后需要恢复焦点的触发按钮。 */
  focus: string;
};

/** 知识引用还包括具备正式审核修订的关联与指标。 */
type KnowledgeTarget = Omit<Target, 'kind'> & { kind: Target['kind'] | 'RELATION' | 'METRIC' };

/** 已确认的稳定事实，可用于当前文档的新修订。 */
export type KnowledgeFact = {
  /** 归属表及稳定目标。 */
  target: KnowledgeTarget;
  /** 确切确认内容编号。 */
  revisionId: string;
  /** 已确认事实的当前可读名称。 */
  label: string;
};

/** 选择器保留当前受限状态。 */
export type FactOption = {
  /** 对应当前目录目标。 */
  target: KnowledgeTarget;
  /** 可直接选择的确认。 */
  fact: KnowledgeFact | null;
  /** 当前复核或查看限制。 */
  unavailableReason: string | null;
};

/** 有顺序的知识正文。 */
export type KnowledgeContent = {
  /** 业务事项名。 */
  title: string;
  /** 文档用途与实际边界。 */
  purpose: string;
  /** 不适用条件。 */
  exclusions: string;
  /** 代表性问题。 */
  questions: string[];
  /** 人工可编辑的各节内容。 */
  sections: { title: string; body: string }[];
};

/** 知识文档的稳定身份和实际指针。 */
export type KnowledgeRow = {
  /** 稳定文档编号。 */
  id: string;
  /** 当前标题。 */
  title: string;
  /** 写操作使用的当前聚合版本。 */
  version: number;
  /** 当前待用正文及已确认正文编号。 */
  headRevisionId: string;
  /** 已确认的确切修订。 */
  confirmedRevisionId: string | null;
  /** 实际办理状态。 */
  state: 'DRAFT' | 'CONFIRMED' | 'REVIEW';
  /** 确认实际时间。 */
  confirmedAt: string | null;
};

/** 文档详情供内容、来源和历史共用。 */
export type KnowledgeDocument = {
  /** 文档的当前状态。 */
  document: KnowledgeRow;
  /** 当前查看的实际正文修订。 */
  revisionId: string;
  /** 固定正文。 */
  content: KnowledgeContent;
  /** 当前修订的对应依据。 */
  references: {
    sectionIndex: number;
    target: KnowledgeTarget;
    factRevisionId: string;
    selected: boolean;
    label: string;
  }[];
  /** 不可变修订。 */
  history: { id: string; number: number; createdAt: string }[];
};

/** 与后台通用审核词汇一致。 */
export const documentStates = {
  DRAFT: '草稿',
  CONFIRMED: '已确认',
  REVIEW: '待复核',
};
