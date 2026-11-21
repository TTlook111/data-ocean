import { Alert, App, Button, Form, Input, Modal, Radio, Select, Table, Tag } from 'antd';
import { useState } from 'react';
import { displayTime } from '../../shared/Records';
import { useSession } from '../../shared/session';
import { factKindNames, releaseStates, type ReleaseTask } from './types';
import { useReleaseCommand } from './useReleaseCommand';

/**
 * 逐题结果来自当前或指定的历史运行。
 */
export type ValidationDetail = {
  run: ValidationRun | null;
  history: ValidationRun[];
  canReadResults: boolean;
  /** 完整文档片段和可能引述原文的裁定依据需要正文查看权。 */
  canReadEvidence: boolean;
  questions: ValidationQuestion[];
};

/**
 * 一次执行绑定确切的候选和构建代次。
 */
export type ValidationRun = {
  id: string;
  candidateId: string;
  buildId: string;
  state: ReleaseTask['status'] | 'QUEUED' | 'RUNNING' | 'RECOVERY_PASSED';
  createdAt: string;
};

/**
 * 原始技术结果及该轮实际证据。
 */
export type ValidationQuestion = {
  id: string;
  testId: string;
  testRevisionId: string;
  revision: number;
  mode: 'NUMERIC' | 'EXPLANATION';
  question: string;
  expected: string;
  basis: string;
  actualCount: string | null;
  statement: string | null;
  failureCode: string | null;
  state: keyof typeof results;
  executorId: string;
  evidence: {
    ordinal: number;
    body: string;
    digest: string;
    evidence: { objectId: string; kind: string; targetId: string; factRevisionId: string }[];
  }[];
  judgment: {
    decision: 'PASSED' | 'FAILED';
    reason: string | null;
    decidedBy: string;
    decidedAt: string;
  } | null;
};

/** 正文之外的确认引用按已有治理分类阅读。 */
function ValidationReferences({
  references,
}: {
  references: ValidationQuestion['evidence'][number]['evidence'];
}) {
  const kinds = [...new Set(references.map((reference) => reference.kind))];
  return (
    <details className="knowledge-validation-references">
      <summary>同版治理引用 · {references.length} 项</summary>
      {/* 类型标题用于定位，回看时仍能核对条目和对应的确认修订。 */}
      {kinds.map((kind) => {
        const group = references.filter((reference) => reference.kind === kind);
        return (
          <section key={kind}>
            <h5>
              {factKindNames[kind] ?? kind} <span>{group.length}</span>
            </h5>
            <ul>
              {group.map((reference) => (
                // 修订身份属于该条来源证据，始终与条目编号同时显示。
                <li key={reference.targetId + ':' + reference.factRevisionId}>
                  <span>{reference.targetId}</span>
                  <small>确认修订 {reference.factRevisionId}</small>
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </details>
  );
}

/**
 * 与原型一致的逐题状态名称。
 */
const results = {
  PASSED: '通过',
  FAILED: '未通过',
  PENDING_JUDGMENT: '待裁定',
  EXECUTION_ERROR: '执行异常',
  CONDITIONS_INVALID: '条件失效',
  PENDING: '待执行',
  CANCELED: '已取消',
} as const;

/**
 * 以有效裁定作为展示状态，保留原技术行为。
 */
function resultState(question: ValidationQuestion) {
  return question.judgment?.decision ?? question.state;
}

/**
 * 单题执行异常可回看明确原因。
 */
const failureLabels: Record<string, string> = {
  NO_EVIDENCE: '未召回候选证据',
  ACCESS_CHANGED: '题目执行人的来源或规则已变化',
  QUERY_BASIS_INVALID: '召回内容或治理定义不能支持本题计数',
  QUERY_FAILED: '未取得只读查询结果',
  INPUT_STALE: '候选依据已变化',
  INDEX_READBACK_MISMATCH: '索引回读与候选不一致',
  INDEX_UNAVAILABLE: '索引暂不可用',
  VALIDATION_TIMEOUT: '本轮验证超时',
  MODEL_AUTH: '向量服务鉴权失败',
  MODEL_FORMAT: '向量服务响应不可用',
  MODEL_RATE_LIMIT: '向量服务暂时限流',
  MODEL_RESULT_UNKNOWN: '向量服务结果待核实',
};

/**
 * 在任务首屏显示本轮四类结果数量。
 */
export function ValidationSummary({ data }: { data: ValidationDetail }) {
  if (!data.run || !data.canReadResults || ['QUEUED', 'RUNNING'].includes(data.run.state)) {
    return null;
  }
  const states = data.questions.map(resultState);
  const metrics = [
    { label: '通过', count: states.filter((state) => state === 'PASSED').length, tone: 'success' },
    { label: '未通过', count: states.filter((state) => state === 'FAILED').length, tone: 'error' },
    {
      label: '待裁定',
      count: states.filter((state) => state === 'PENDING_JUDGMENT').length,
      tone: 'warning',
    },
    {
      label: '执行异常',
      count: states.filter((state) => ['CONDITIONS_INVALID', 'EXECUTION_ERROR'].includes(state))
        .length,
      tone: 'error',
    },
  ];
  return (
    <div className="knowledge-validation-summary" aria-label="验证结果汇总">
      {/* 数量为零时使用中性色，避免误认为存在待办。 */}
      {metrics.map(({ label, count, tone }) => (
        <Tag key={label} color={count ? tone : undefined}>
          {label} {count}
        </Tag>
      ))}
    </div>
  );
}

/**
 * 展示本轮或历史逐题结果，回看不改变当前运行。
 */
export function ValidationReport({
  data,
  title = '候选验证',
  activeRun,
  selected,
  select,
  open,
  canJudge,
}: {
  data: ValidationDetail;
  /** 恢复验证沿用报告布局和逐题证据。 */
  title?: string;
  activeRun?: string;
  selected?: string;
  select: (run?: string) => void;
  open: (item: ValidationQuestion, judge: boolean) => void;
  canJudge: boolean;
}) {
  const order = {
    PENDING_JUDGMENT: 0,
    EXECUTION_ERROR: 1,
    CONDITIONS_INVALID: 1,
    FAILED: 1,
    PENDING: 2,
    CANCELED: 2,
    PASSED: 3,
  };
  const list = [...data.questions].sort((a, b) => order[resultState(a)] - order[resultState(b)]);
  const judging = canJudge && data.run?.id === activeRun;
  return (
    <section className="knowledge-build-section" aria-label={title}>
      {/* 原型的独立报告区承载运行切换及逐题结果。 */}
      <header className="knowledge-validation-heading">
        <h2>{title}</h2>
        {data.history.some((run) => run.id !== activeRun) && (
          <Select
            aria-label="验证运行"
            value={selected ?? ''}
            options={[
              { value: '', label: '当前运行' },
              ...data.history
                .filter((run) => run.id !== activeRun)
                .map((run) => ({
                  value: run.id,
                  label:
                    displayTime(run.createdAt) +
                    ' · ' +
                    (run.state === 'QUEUED' || run.state === 'RUNNING'
                      ? '验证中'
                      : run.state === 'RECOVERY_PASSED'
                        ? '验证已完成'
                        : releaseStates[run.state]),
                })),
            ]}
            onChange={(run) => select(run || undefined)}
          />
        )}
      </header>
      {data.run ? (
        <>
          <span className="muted">
            {data.run.id} · {displayTime(data.run.createdAt)}
          </span>
          {data.canReadResults ? (
            <Table<ValidationQuestion>
              rowKey="id"
              size="middle"
              pagination={false}
              scroll={{ x: 700 }}
              dataSource={list}
              columns={[
                { title: '标准题', dataIndex: 'question', ellipsis: true },
                { title: '预期', dataIndex: 'expected', width: 150, ellipsis: true },
                {
                  title: '实际及依据',
                  width: 172,
                  ellipsis: true,
                  render: (_, row) =>
                    row.actualCount ??
                    (row.evidence.length
                      ? '已召回 ' + row.evidence.length + ' 段依据'
                      : row.failureCode
                        ? (failureLabels[row.failureCode] ?? '未取得技术结果')
                        : '—'),
                },
                {
                  title: '结果',
                  width: 100,
                  render: (_, row) => <Tag>{results[resultState(row)]}</Tag>,
                },
                {
                  title: '操作',
                  width: 116,
                  render: (_, row) => {
                    // 历史、失败与已裁定结果只回读原技术记录。
                    const judge = judging && resultState(row) === 'PENDING_JUDGMENT';
                    return (
                      <Button
                        id={'release-validation-question-' + row.id}
                        type="link"
                        onClick={() => open(row, judge)}
                      >
                        {judge ? '办理裁定' : '查看结果'}
                      </Button>
                    );
                  },
                },
              ]}
            />
          ) : (
            <Alert type="info" showIcon title="当前账号无法查看此来源的逐题结果和证据" />
          )}
        </>
      ) : (
        <p className="muted">尚未运行验证</p>
      )}
    </section>
  );
}

/**
 * 宽弹窗保留原型的双栏证据与底部裁定操作。
 */
export function ValidationQuestionDialog({
  row,
  recovery = false,
  run,
  path,
  version,
  judge,
  canReadEvidence,
  close,
  refresh,
}: {
  row: ValidationQuestion;
  /** 恢复证据来自正式版本而不是待发布候选。 */
  recovery?: boolean;
  run: ValidationRun;
  path: string;
  version: number;
  judge: boolean;
  /** 本人当前可以披露候选原文及依据。 */
  canReadEvidence: boolean;
  close: () => void;
  refresh: () => Promise<unknown>;
}) {
  const [form] = Form.useForm<{ decision: 'PASSED' | 'FAILED'; reason: string }>();
  const { modal } = App.useApp();
  const session = useSession();
  const [baseline] = useState(version);
  const command = useReleaseCommand({
    key: 'dataocean:judgment:' + session.userId + ':' + run.id + ':' + row.id,
    path: path + '/' + run.id + '/results/' + row.id + '/judgment',
    operationCode: 'test-cases.judge',
    parse: (body) => {
      // 核实只使用原题、运行和原页面版本。
      const value = body as Record<string, unknown>;
      return typeof value.expectedVersion === 'number' &&
        (value.decision === 'PASSED' || value.decision === 'FAILED') &&
        typeof value.reason === 'string'
        ? { expectedVersion: value.expectedVersion, decision: value.decision, reason: value.reason }
        : undefined;
    },
    committed: async () => {
      await refresh();
      close();
    },
    conflict: () => void refresh(),
  });
  const administrable =
    judge && canReadEvidence && row.state === 'PENDING_JUDGMENT' && !row.judgment;
  const changed = baseline !== version;

  /**
   * 未提交的裁定内容在明确放弃前留在原窗口。
   */
  function discard() {
    if (command.busy) return;
    if (administrable && form.isFieldsTouched()) {
      modal.confirm({
        title: '放弃本次裁定？',
        content: '已填写的依据将丢失。',
        okText: '放弃修改',
        cancelText: '继续裁定',
        onOk: close,
      });
    } else {
      close();
    }
  }

  return (
    <Modal
      open
      centered
      width={1160}
      title={administrable ? (recovery ? '裁定恢复验证' : '裁定候选结果') : '查看逐题结果'}
      onCancel={discard}
      cancelText={administrable ? '取消' : '关闭'}
      okText={command.pending ? command.action : '确认裁定'}
      okButtonProps={{ disabled: changed && !command.pending }}
      confirmLoading={command.busy}
      footer={!administrable && !command.pending ? (_, { CancelBtn }) => <CancelBtn /> : undefined}
      onOk={() => {
        // 新任务版本不自动重绑到旧证据。
        if (command.pending) command.resolve();
        else if (!changed) form.submit();
      }}
    >
      {/* 题号、题目及本轮证据伴随同一个索引代次。 */}
      <div className="knowledge-validation-heading">
        <strong>{row.question}</strong>
        <small className="muted">
          {run.id} · 题目 r{row.revision}
        </small>
      </div>
      <div
        className={`knowledge-validation-comparison${administrable ? '' : ' knowledge-validation-comparison-readonly'}`}
      >
        <section tabIndex={0} aria-label="预期与判定依据">
          <h3>预期与判定依据</h3>
          <p className={row.mode === 'NUMERIC' ? 'knowledge-validation-count' : undefined}>
            {row.expected}
          </p>
          <h4>依据</h4>
          <p>{row.basis}</p>
        </section>
        <section tabIndex={0} aria-label="完整实际结果">
          <h3>实际结果</h3>
          {row.actualCount !== null && (
            <p className="knowledge-validation-count">{row.actualCount}</p>
          )}
          {row.failureCode && (
            <Alert
              showIcon
              type="warning"
              title={failureLabels[row.failureCode] ?? '未取得可核实的结果'}
            />
          )}
          {row.statement && (
            <details>
              <summary>查询语句</summary>
              <pre>{row.statement}</pre>
            </details>
          )}
          {canReadEvidence &&
            row.evidence.map((fragment) => (
              <div key={fragment.ordinal} className="knowledge-validation-excerpt">
                <h4>
                  {recovery ? '正式片段' : '候选片段'} {fragment.ordinal + 1}
                </h4>
                <p>{fragment.body}</p>
                {fragment.evidence.length > 0 && (
                  <ValidationReferences references={fragment.evidence} />
                )}
              </div>
            ))}
          {row.judgment && (
            <Alert
              type="info"
              title={'人工裁定 · ' + results[row.judgment.decision]}
              description={
                <>
                  {canReadEvidence && row.judgment.reason && <p>{row.judgment.reason}</p>}
                  <small>
                    裁定人 {row.judgment.decidedBy} · {displayTime(row.judgment.decidedAt)}
                  </small>
                </>
              }
            />
          )}
          {row.actualCount === null && row.evidence.length === 0 && !row.failureCode && (
            <p className="muted">
              {canReadEvidence ? '暂无实际结果' : '候选原文仅供获准查看知识文档的人员阅读'}
            </p>
          )}
        </section>
      </div>
      {changed && administrable && (
        <Alert type="warning" showIcon title="任务已变化，请返回查看当前运行后再裁定。" />
      )}
      {command.note && (
        <Alert type={command.pending ? 'warning' : 'error'} showIcon title={command.note} />
      )}
      {administrable && (
        <Form
          form={form}
          layout="vertical"
          disabled={!!command.pending}
          className="knowledge-validation-decision"
          scrollToFirstError={{ focus: true }}
          onFinish={({ decision, reason }) =>
            command.submit({ expectedVersion: baseline, decision, reason: reason.trim() })
          }
        >
          {/* 结论和实际判断依据一起提交。 */}
          <Form.Item
            name="decision"
            label="裁定结论"
            rules={[{ required: true, message: '请选择裁定结论' }]}
          >
            <Radio.Group
              optionType="button"
              options={[
                { label: '通过', value: 'PASSED' },
                { label: '未通过', value: 'FAILED' },
              ]}
            />
          </Form.Item>
          <Form.Item
            name="reason"
            label="裁定依据"
            rules={[{ required: true, whitespace: true, message: '请填写裁定依据' }]}
          >
            <Input.TextArea rows={2} maxLength={300} showCount />
          </Form.Item>
        </Form>
      )}
    </Modal>
  );
}
