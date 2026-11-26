import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Form, Modal, Select, Space, Tag } from 'antd';
import { useRef, useState } from 'react';
import { api, mutation, queryString } from '../../shared/api';
import { LoadingStatus } from '../../shared/LoadingIndicator';
import { ReadState, readErrorMessage } from '../../shared/Records';

/** 文档选择器的确切确认标识。 */
type DocumentChoice = { id: string; title: string; revisionId: string };

/** 标准题建议按本轮实际候选及采纳结果展示。 */
type Task = {
  /** 已受理的实际任务编号。 */
  id: string;
  /** 当前生成状态，采纳不会重启任务。 */
  state: 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  /** 受控失败类别。 */
  failureCode: string | null;
  /** 原结果顺序用于采纳时定位题目。 */
  candidates: Array<{
    /** 可核对的建议题干。 */
    question: string;
    /** 题目需要覆盖的能力或边界。 */
    coverage: 'CAPABILITY' | 'BOUNDARY';
    /** 按现有标准题方式准备预期条件。 */
    mode: 'NUMERIC' | 'EXPLANATION';
    /** 供作者复核的判定提示。 */
    validationHint: string;
    /** 原文档确认章节。 */
    sectionTitles: string[];
    /** 已采纳的实际草稿编号。 */
    testId: string | null;
  }>;
};

/** 前端反馈与当前生成状态对应。 */
const failureReasons: Record<string, string> = {
  TEST_INPUT_UNAVAILABLE: '文档确认、治理依据或模型配置已变化，请重新核对。',
  INVALID_TEST_SUGGESTIONS: '建议引用了无效的文档章节，请重新生成。',
  MODEL_FAILED: '模型未能返回可用建议，请检查调用记录。',
  MODEL_RESULT_UNKNOWN: '暂未取得可确认的模型结果，请先核对调用记录。',
  QUEUE_EXPIRED: '排队已超时，请重新生成。',
};

/** 与原题单共用文档范围和草稿办理入口。 */
export function KnowledgeTestSuggestions({
  source,
  documents,
  close,
  openTest,
}: {
  /** 当前题单的数据源。 */
  source: string;
  /** 当前筛选允许办理的确切文档确认。 */
  documents: DocumentChoice[];
  /** 关闭后保留原题单筛选。 */
  close: () => void;
  /** 打开已有正式草稿。 */
  openTest: (id: string) => void;
}) {
  const cache = useQueryClient();
  const [selected, setSelected] = useState(documents[0]?.id);
  const document = documents.find((item) => item.id === selected);
  const path = '/knowledge/sources/' + source + '/test-cases/suggestions';
  const queryKey = ['knowledge-test-suggestions', source, document?.id, document?.revisionId];
  const generateCommand = useRef<{ documentId: string; revisionId: string; key: string }>(
    undefined,
  );
  const adoptCommand = useRef<{ taskId: string; index: number; key: string }>(undefined);
  const task = useQuery({
    queryKey,
    enabled: Boolean(document),
    queryFn: async () =>
      (await api<Task | null>(
        path +
          '?' +
          queryString({ documentId: document?.id, documentRevisionId: document?.revisionId }),
      )) ?? null,
    refetchInterval: ({ state }) => {
      // 只轮询原后台任务，关闭对话框后仍可按文档确认回看。
      return state.data?.state === 'QUEUED' || state.data?.state === 'RUNNING' ? 1500 : false;
    },
  });
  const generate = useMutation({
    mutationFn: async (target: DocumentChoice) => {
      if (
        generateCommand.current?.documentId !== target.id ||
        generateCommand.current.revisionId !== target.revisionId
      ) {
        // 传输中断后使用原受理命令核对结果。
        generateCommand.current = {
          documentId: target.id,
          revisionId: target.revisionId,
          key: crypto.randomUUID(),
        };
      }
      const receipt = await mutation(
        path,
        'POST',
        {
          documentId: target.id,
          documentRevisionId: target.revisionId,
        },
        'test-cases.edit',
        generateCommand.current.key,
      );
      if (!receipt.targetId) throw new Error('暂未取得标准题建议任务编号，请核对本次提交');
    },
    onSuccess: async () => {
      // 成功收到受理后，其后的新操作可以使用新命令。
      generateCommand.current = undefined;
      adopt.reset();
      await task.refetch();
    },
  });
  const adopt = useMutation({
    mutationFn: ({ taskId, index }: { taskId: string; index: number }) => {
      if (adoptCommand.current?.taskId !== taskId || adoptCommand.current.index !== index) {
        // 精确回查同一题的采纳命令。
        adoptCommand.current = { taskId, index, key: crypto.randomUUID() };
      }
      return mutation(
        `${path}/${taskId}/adoptions`,
        'POST',
        { index },
        'test-cases.edit',
        adoptCommand.current.key,
      );
    },
    onSuccess: async () => {
      // 重新读取任务和题单，草稿入口采用持久化题目编号。
      adoptCommand.current = undefined;
      await Promise.all([
        task.refetch(),
        cache.invalidateQueries({ queryKey: ['knowledge-tests', source] }),
      ]);
    },
  });
  const report = task.data;
  const running = report?.state === 'QUEUED' || report?.state === 'RUNNING';
  const writing = generate.isPending || adopt.isPending;

  return (
    <Modal
      open
      title="生成候选题"
      width={720}
      closable={!writing}
      mask={{ closable: !writing }}
      keyboard={!writing}
      onCancel={close}
      footer={
        <Space>
          <Button onClick={close} disabled={writing}>
            关闭
          </Button>
          <Button
            type="primary"
            loading={generate.isPending}
            disabled={!document || task.isPending || task.isError || running || adopt.isPending}
            onClick={() => document && generate.mutate(document)}
          >
            {report && !running ? '重新生成' : '生成建议'}
          </Button>
        </Space>
      }
    >
      {/* 依据与题单原筛选一致，后端仍核对确切文档修订。 */}
      <Form layout="vertical" className="knowledge-test-suggestion-selection">
        <Form.Item label="依据文档" htmlFor="knowledge-test-suggestion-document">
          <Select
            id="knowledge-test-suggestion-document"
            value={document?.id}
            disabled={writing || documents.length < 2}
            showSearch={{ optionFilterProp: 'label' }}
            options={documents.map(({ id, title }) => ({ value: id, label: title }))}
            onChange={(id) => {
              // 切换依据后恢复该文档的独立候选任务。
              setSelected(id);
              generate.reset();
              adopt.reset();
            }}
          />
        </Form.Item>
      </Form>
      {/* 候选区跟随后台真实状态，原题单无需等待模型执行。 */}
      <ReadState
        loading={Boolean(document && task.isPending)}
        error={task.error}
        retry={() => void task.refetch()}
      >
        <div className="knowledge-test-suggestion-results">
          {generate.error && (
            <Alert type="error" showIcon title={readErrorMessage(generate.error)} />
          )}
          {running && (
            <LoadingStatus>
              {report.state === 'QUEUED' ? '等待生成候选题' : '正在生成候选题'}
            </LoadingStatus>
          )}
          {report?.state === 'FAILED' && (
            <Alert
              type="error"
              showIcon
              title={failureReasons[report.failureCode ?? ''] ?? '候选题生成失败'}
            />
          )}
          {report?.state === 'SUCCEEDED' && report.candidates.length === 0 && (
            <Alert type="info" showIcon title="本次没有新的候选题" />
          )}
          {report?.state === 'SUCCEEDED' &&
            report.candidates.map((candidate, index) => (
              <article className="knowledge-test-suggestion-item" key={candidate.question}>
                {/* 保持题干优先，判定要点和依据供保存前核对。 */}
                <h3>{candidate.question}</h3>
                <div className="knowledge-test-suggestion-meta">
                  <Tag>{candidate.coverage === 'CAPABILITY' ? '关键能力' : '使用边界'}</Tag>
                  <Tag>{candidate.mode === 'NUMERIC' ? '数值题' : '说明题'}</Tag>
                </div>
                <p>{candidate.validationHint}</p>
                <p className="table-secondary">依据章节 {candidate.sectionTitles.join('、')}</p>
                {candidate.testId ? (
                  <Button type="link" onClick={() => openTest(candidate.testId!)}>
                    查看草稿
                  </Button>
                ) : (
                  <Button
                    disabled={writing}
                    loading={adopt.isPending && adopt.variables?.index === index}
                    onClick={() => adopt.mutate({ taskId: report.id, index })}
                  >
                    保存为草稿
                  </Button>
                )}
                {adopt.error && adopt.variables?.index === index && (
                  <Alert type="error" showIcon title={readErrorMessage(adopt.error)} />
                )}
              </article>
            ))}
        </div>
      </ReadState>
    </Modal>
  );
}
