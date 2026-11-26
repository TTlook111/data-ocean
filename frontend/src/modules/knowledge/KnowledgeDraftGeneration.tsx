import { useMutation, useQuery } from '@tanstack/react-query';
import { Alert, Button, Checkbox, Form, Input, Modal, Select, Space } from 'antd';
import { useRef, useState } from 'react';
import { Link, useLocation, useNavigate } from 'react-router';
import { api, mutation, queryString } from '../../shared/api';
import { LoadingStatus } from '../../shared/LoadingIndicator';
import { ReadState, readErrorMessage } from '../../shared/Records';

/**
 * 发起正文时冻结人工核对的文档职责和主动依据。
 */
type Scope = {
  /** 用户选定的名称。 */
  title: string;
  /** 用户选定的用途。 */
  purpose: string;
  /** 提交到原规划的事实序号。 */
  basis: number[];
};

/**
 * 正文任务逐节返回可独立采纳的前后内容。
 */
type Generation = {
  /** 实际后台任务状态。 */
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  /** 只返回受控的任务失败分类。 */
  failureCode: string | null;
  /** 既有文档更新允许逐节采纳。 */
  existingDocumentId: string | null;
  /** 已保存草稿时用于定位文档。 */
  appliedDocumentId: string | null;
  /** 建议与原章节及其实际确认依据。 */
  changes: Array<{
    /** 替换建议对应的原文，新章节为空。 */
    original: { title: string; body: string } | null;
    /** 建议章节内容。 */
    proposed: { title: string; body: string };
    /** 明确采用的主动治理事实。 */
    basis: Array<{ number: number; label: string }>;
  }>;
};

/** 可由使用者处理的任务失败及待核实状态。 */
const failureReasons: Record<string, string> = {
  DRAFT_INPUT_UNAVAILABLE: '来源、治理依据或原文档已变化，请核对后重新生成。',
  INVALID_DRAFT: '正文建议引用了无效的章节或依据，请重新生成。',
  MODEL_FAILED: '模型未能完成正文生成，请查看调用记录。',
  MODEL_RESULT_UNKNOWN: '暂未取得可确认的模型结果，请先核对调用记录。',
  QUEUE_EXPIRED: '本次排队已超时，请重新提交。',
};

/**
 * 规划中的单篇正文生成入口及可恢复的审核状态。
 */
export function KnowledgeDraftGeneration({
  endpoint,
  source,
  suggestionIndex,
  suggestion,
  facts,
  taskId,
  mayEdit,
  started,
}: {
  /** 原规划下的正文任务接口。 */
  endpoint: string;
  /** 当前数据源，用于打开草稿。 */
  source: string;
  /** 当前正文使用的建议顺序。 */
  suggestionIndex: number;
  /** 原建议的职责、事实和已有文档身份。 */
  suggestion: Scope & { existingDocumentId: string | null };
  /** 可供作者调整的原规划确切依据。 */
  facts: Array<{ number: number; label: string }>;
  /** 规划回看可恢复的实际任务编号。 */
  taskId?: string;
  /** 草稿采纳及已有正文更新需要编辑权限。 */
  mayEdit: boolean;
  /** 命令受理后在规划上记录实际任务编号。 */
  started: (id: string) => void;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const [scopeOpen, setScopeOpen] = useState(false);
  const [form] = Form.useForm<Scope>();
  const command = useRef<{ payload: string; id: string }>(undefined);
  const generation = useQuery({
    queryKey: ['knowledge-draft-generation', endpoint, taskId],
    enabled: Boolean(taskId),
    queryFn: () => api<Generation>(`${endpoint}/${taskId}`),
    refetchInterval: ({ state }) => {
      // 仅任务未终结时轮询，响应查询不会发起新的模型调用。
      const status = state.data?.state;
      return status === 'QUEUED' || status === 'RUNNING' ? 1500 : false;
    },
  });
  const begin = useMutation({
    mutationFn: async (scope: Scope) => {
      const input = {
        suggestionIndex,
        title: scope.title.trim(),
        purpose: scope.purpose.trim(),
        basis: [...scope.basis].sort((a, b) => a - b),
      };
      const payload = JSON.stringify(input);
      if (command.current?.payload !== payload) {
        // 保留同输入的未知提交命令用于回查。
        command.current = { payload, id: crypto.randomUUID() };
      }
      const receipt = await mutation(
        endpoint,
        'POST',
        input,
        'knowledge.generate',
        command.current.id,
      );
      if (!receipt.targetId) throw new Error('暂未取得正文任务编号，请核对本次提交');
      return receipt.targetId;
    },
    onSuccess: (id) => {
      // 下一次明确发起才启动新命令，刷新可以回看同一任务。
      command.current = undefined;
      setScopeOpen(false);
      started(id);
    },
  });
  const report = generation.data;
  const inProgress = report?.state === 'QUEUED' || report?.state === 'RUNNING';
  const canGenerate = (!suggestion.existingDocumentId || mayEdit) && !report?.appliedDocumentId;
  const documentURL = '/knowledge/' + report?.appliedDocumentId + '?' + queryString({ source });
  const navigation = location.state as { listSearch?: string } | null;
  // 同一结果采纳后仍可回看，历史差异不再显示采纳操作。
  const review = report?.state === 'SUCCEEDED' && (
    <DraftReview
      key={taskId}
      report={report}
      endpoint={`${endpoint}/${taskId}/adoptions`}
      mayEdit={mayEdit && !report.appliedDocumentId}
      openDraft={({ id, version }) => {
        // 只打开本次保存的版本进行编辑，后续人工保存返回阅读视图。
        void generation.refetch();
        navigate('/knowledge/' + id + '?' + queryString({ source }), {
          state: { ...navigation, editVersion: version },
        });
      }}
    />
  );

  return (
    <div className="knowledge-generation">
      {/* 成果、等待和失败都由当前原任务状态决定。 */}
      {taskId && (
        <ReadState
          loading={generation.isPending}
          error={generation.error}
          retry={() => void generation.refetch()}
        >
          {inProgress && (
            <LoadingStatus>
              {report.state === 'QUEUED' ? '等待正文生成' : '正在生成正文'}
            </LoadingStatus>
          )}
          {report?.state === 'FAILED' && (
            <Alert
              type="error"
              showIcon
              title={failureReasons[report.failureCode ?? ''] ?? '正文生成失败'}
            />
          )}
          {report?.appliedDocumentId ? (
            <>
              <Link to={documentURL} state={navigation}>
                打开文档
              </Link>
              <details className="knowledge-generation-record">
                <summary>{report.existingDocumentId ? '查看本次更新差异' : '查看生成记录'}</summary>
                {review}
              </details>
            </>
          ) : (
            review
          )}
        </ReadState>
      )}
      {canGenerate && (!taskId || (report && !inProgress)) && (
        <Button
          type={report?.state === 'SUCCEEDED' ? 'default' : 'primary'}
          onClick={() => setScopeOpen(true)}
        >
          {report
            ? '重新核对范围'
            : suggestion.existingDocumentId
              ? '核对更新范围'
              : '核对生成范围'}
        </Button>
      )}
      {/* 独立确认范围后才发起实际正文请求。 */}
      <Modal
        title={suggestion.existingDocumentId ? '核对更新范围' : '核对生成范围'}
        open={scopeOpen}
        okText={suggestion.existingDocumentId ? '生成更新建议' : '生成正文'}
        confirmLoading={begin.isPending}
        closable={!begin.isPending}
        mask={{ closable: !begin.isPending }}
        keyboard={!begin.isPending}
        cancelButtonProps={{ disabled: begin.isPending }}
        onCancel={() => setScopeOpen(false)}
        onOk={() => form.submit()}
      >
        <Form<Scope>
          form={form}
          layout="vertical"
          initialValues={{ ...suggestion, basis: [...new Set(suggestion.basis)] }}
          onFinish={(values) => begin.mutate(values)}
          disabled={begin.isPending}
        >
          {/* 职责调整使用实际当前建议，原文档名称和用途仍可在正式编辑器修改。 */}
          <Form.Item name="title" label="文档名称" rules={[{ required: true, whitespace: true }]}>
            <Input maxLength={120} readOnly={Boolean(suggestion.existingDocumentId)} />
          </Form.Item>
          <Form.Item
            name="purpose"
            label="本次业务用途"
            rules={[{ required: true, whitespace: true }]}
          >
            <Input.TextArea rows={3} maxLength={400} />
          </Form.Item>
          <Form.Item
            name="basis"
            label="主动治理依据"
            rules={[{ required: true, type: 'array', min: 1, max: 24 }]}
          >
            <Select
              mode="multiple"
              maxCount={24}
              optionFilterProp="label"
              options={facts.map(({ number, label }) => ({ value: number, label }))}
            />
          </Form.Item>
          {begin.error && <Alert type="error" showIcon title={readErrorMessage(begin.error)} />}
        </Form>
      </Modal>
    </div>
  );
}

/**
 * 展示逐节前后正文，更新默认不替换任何人工章节。
 */
function DraftReview({
  report,
  endpoint,
  mayEdit,
  openDraft,
}: {
  /** 已完成的结果及文档身份。 */
  report: Generation;
  /** 采纳当前原任务的写入接口。 */
  endpoint: string;
  /** 当前用户是否能保存草稿。 */
  mayEdit: boolean;
  /** 已保存后打开实际文档。 */
  openDraft: (document: { id: string; version?: number }) => void;
}) {
  const [selected, setSelected] = useState<number[]>([]);
  const command = useRef<{ payload: string; id: string }>(undefined);
  const update = Boolean(report.existingDocumentId);
  const adopt = useMutation({
    mutationFn: async () => {
      const changes = update
        ? [...selected].sort((a, b) => a - b)
        : report.changes.map((_, i) => i);
      const payload = JSON.stringify(changes);
      if (command.current?.payload !== payload) {
        // 同一采纳选择的未知请求保留原命令和内容。
        command.current = { payload, id: crypto.randomUUID() };
      }
      const result = await mutation(
        endpoint,
        'POST',
        { changes },
        'knowledge.edit',
        command.current.id,
      );
      if (!result.targetId) throw new Error('暂未取得草稿编号，请核对本次提交');
      return { id: result.targetId, version: result.version };
    },
    onSuccess: openDraft,
  });

  if (report.changes.length === 0) {
    // 模型明确返回无需改动时没有可采纳的差异。
    return <Alert showIcon type="info" title="本次未生成需要更新的章节" />;
  }

  return (
    <section
      className="knowledge-generation-review"
      aria-label={update ? '正文更新差异' : '生成的正文'}
    >
      {/* 分节展示原文、可采纳正文及真实依据。 */}
      {report.changes.map(({ original, proposed, basis }, index) => (
        <article key={index} className="knowledge-generation-change">
          <h3>{proposed.title}</h3>
          <div className={original ? 'knowledge-generation-comparison' : undefined}>
            {original && (
              <div>
                <strong>原文 · {original.title}</strong>
                <p>{original.body}</p>
              </div>
            )}
            <div>
              {update && <strong>{original ? '建议正文' : '新增章节'}</strong>}
              <p>{proposed.body}</p>
            </div>
          </div>
          <div className="table-secondary">
            治理依据 {basis.map(({ label }) => label).join('、')}
          </div>
          {update && mayEdit && (
            <Checkbox
              checked={selected.includes(index)}
              disabled={adopt.isPending}
              onChange={({ target }) => {
                // 只有作者主动选中的章节进入采纳请求。
                setSelected((ids) =>
                  target.checked ? [...ids, index] : ids.filter((id) => id !== index),
                );
              }}
            >
              采纳「{proposed.title}」
            </Checkbox>
          )}
        </article>
      ))}
      {/* 保留失败原位与所选章节，可按原命令核实或重新保存。 */}
      {adopt.error && <Alert type="error" showIcon title={readErrorMessage(adopt.error)} />}
      {mayEdit && (
        <Space>
          <Button
            type="primary"
            loading={adopt.isPending}
            disabled={update && !selected.length}
            onClick={() => adopt.mutate()}
          >
            {update ? '保存所选更新为草稿' : '采纳并保存草稿'}
          </Button>
        </Space>
      )}
    </section>
  );
}
