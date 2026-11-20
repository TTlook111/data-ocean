import { PlusOutlined } from '@ant-design/icons';
import { Alert, App, Button, Tag } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ApiError, mutation, queryString } from '../../shared/api';

/** 只保存核实或重试原提交所需的非秘密命令资料。 */
type PendingPreparation = {
  /** 用于服务端命令查询和防重。 */
  command: string;
  /** 点击时选择的确切文档确认。 */
  documentRevisionId: string;
  /** 点击时核对的完整输入摘要。 */
  inputFingerprint: string;
};

/** 必须先从原命令得到可识别的状态，之后才决定能否重试。 */
type PreparationResult = 'COMMITTED' | 'PENDING' | 'NOT_FOUND';

/** 发布准备的提交和回包不明处理与当前人员、来源及文档绑定。 */
export function ReleasePreparation({
  userId,
  source,
  documentId,
  documentRevisionId,
  inputFingerprint,
  showStatus,
  canPrepare,
  checking,
  beforeSubmit,
  onConflict,
}: {
  /** 当前登录人员的稳定标识。 */
  userId: string;
  /** 当前源和文档限制命令上下文。 */
  source: string;
  /** 命令属于此文档。 */
  documentId: string;
  /** 预检已确认的修订。 */
  documentRevisionId: string | null;
  /** 预检就绪时的固定依据。 */
  inputFingerprint: string | null;
  /** 预检有可展示结果时显示状态。 */
  showStatus: boolean;
  /** 页面可读时仍需单独核对创建和命令查询权限。 */
  canPrepare: boolean;
  /** 前置条件读取中暂不接收新操作。 */
  checking: boolean;
  /** 准备后把候选列表定位到第一页。 */
  beforeSubmit: () => void;
  /** 明确冲突后提示用户走重新核对。 */
  onConflict: () => void;
}) {
  const cache = useQueryClient();
  const { message } = App.useApp();
  const storageKey = `dataocean:prepare-release:${userId}:${source}:${documentId}`;
  const [saved, setPending] = useState(() => readPending(storageKey));
  const pending = canPrepare ? saved : undefined;
  const request = useMutation({
    /** 查询命令状态和提交使用分别明确的请求，查询入口只发 GET。 */
    mutationFn: async ({
      attempt,
      verify,
    }: {
      attempt: PendingPreparation;
      verify: boolean;
    }): Promise<PreparationResult> => {
      if (verify) {
        // 记录可能还不可见。没有查到时只开放携带原编号及原依据的显式重试。
        try {
          const status = await api<{ state: string; target: { id: string } | null }>(
            `/commands/${attempt.command}?${queryString({ operationCode: 'releases.build' })}`,
          );
          return status.state === 'COMMITTED' && status.target?.id ? 'COMMITTED' : 'PENDING';
        } catch (failure) {
          if (failure instanceof ApiError && failure.code === 404001) return 'NOT_FOUND';
          throw failure;
        }
      }
      await mutation<{ targetId: string }>(
        `/knowledge/sources/${source}/releases`,
        'POST',
        {
          documentId,
          documentRevisionId: attempt.documentRevisionId,
          inputFingerprint: attempt.inputFingerprint,
        },
        'releases.build',
        attempt.command,
      );
      return 'COMMITTED';
    },
    /** 取得已提交事实后清理恢复入口并回读已保存列表。 */
    onSuccess: async (outcome) => {
      if (outcome !== 'COMMITTED') return;
      savePending(storageKey, undefined);
      setPending(undefined);
      await cache.invalidateQueries({ queryKey: ['release-candidates', source, documentId] });
      void message.success('发布候选已创建');
    },
    /** 只在收到明确业务拒绝时结束原提交，网络不明则保留命令。 */
    onError: (failure, { verify }) => {
      if (
        verify ||
        !(failure instanceof ApiError) ||
        failure.code >= 500000 ||
        failure.code === 409004
      )
        return;
      savePending(storageKey, undefined);
      setPending(undefined);
      if ([409301, 409302].includes(failure.code)) onConflict();
    },
  });

  /** 开启新命令时先保存原提交依据，刷新也能继续核实。 */
  function create() {
    if (!documentRevisionId || !inputFingerprint || request.isPending) return;
    const attempt = { command: crypto.randomUUID(), documentRevisionId, inputFingerprint };
    savePending(storageKey, attempt);
    setPending(attempt);
    beforeSubmit();
    request.mutate({ attempt, verify: false });
  }

  const retry = pending && request.data === 'NOT_FOUND';
  const sending = request.isPending && request.variables?.verify === false;
  const pendingAction = sending ? '正在提交' : retry ? '重试原提交' : '核实原提交';
  const note =
    sending || !canPrepare
      ? undefined
      : pending
        ? retry
          ? '暂未查到原提交记录，可以使用原操作编号重试。'
          : (request.error?.message ?? '请核实原提交结果。')
        : request.error?.message;

  return (
    <header className="list-toolbar knowledge-preflight-title">
      {/* 状态与当前操作保持一致，提交待核实时收起就绪提示。 */}
      <div className="knowledge-heading-main">
        <h2>发布准备</h2>
        {(pending || showStatus) && (
          <Tag color={pending || !inputFingerprint ? 'orange' : 'green'}>
            {pending
              ? sending
                ? '提交中'
                : '提交待核实'
              : inputFingerprint
                ? '可创建候选'
                : '待处理'}
          </Tag>
        )}
      </div>
      {/* 待核实期间只保留原命令入口，防止刷新或换回文档后另开命令。 */}
      {pending ? (
        <Button
          type="primary"
          aria-label={pendingAction}
          aria-busy={request.isPending}
          loading={request.isPending}
          onClick={() => {
            // 状态未查到时也只能重试原请求及原编号。
            if (retry) beforeSubmit();
            request.mutate({ attempt: pending, verify: !retry });
          }}
        >
          {pendingAction}
        </Button>
      ) : (
        canPrepare &&
        inputFingerprint && (
          <Button
            type="primary"
            aria-label="创建发布候选"
            icon={<PlusOutlined aria-hidden="true" />}
            loading={request.isPending}
            disabled={checking}
            onClick={create}
          >
            创建发布候选
          </Button>
        )
      )}
      {/* 在同一个办理区域说明待核实或明确失败。 */}
      {note && (
        <Alert
          className="knowledge-prepare-feedback"
          type={pending ? 'warning' : 'error'}
          showIcon
          title={note}
        />
      )}
    </header>
  );
}

/** 从本标签页恢复当前对象的原命令；读不到时不借用其他文档的命令。 */
function readPending(key: string): PendingPreparation | undefined {
  try {
    const saved = sessionStorage.getItem(key);
    const parsed = saved ? (JSON.parse(saved) as Partial<PendingPreparation> | null) : null;
    return typeof parsed?.command === 'string' &&
      typeof parsed.documentRevisionId === 'string' &&
      typeof parsed.inputFingerprint === 'string'
      ? {
          command: parsed.command,
          documentRevisionId: parsed.documentRevisionId,
          inputFingerprint: parsed.inputFingerprint,
        }
      : undefined;
  } catch {
    // 浏览器禁用存储时，同页内仍由 React 状态保留原命令。
    return undefined;
  }
}

/** 写入的只有命令和不可变的非秘密版本标识。 */
function savePending(key: string, pending: PendingPreparation | undefined) {
  try {
    if (pending) sessionStorage.setItem(key, JSON.stringify(pending));
    else sessionStorage.removeItem(key);
  } catch {
    // 保留内存中的命令，当前页仍可核实或按原编号重试。
  }
}
