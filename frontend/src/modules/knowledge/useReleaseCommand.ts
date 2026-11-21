import { useMutation } from '@tanstack/react-query';
import { useState } from 'react';
import { api, ApiError, mutation, queryString } from '../../shared/api';

/** 当前用户、源和目标下恢复的写入命令。 */
type Attempt<T> = {
  /** 只用于防重和查询原命令。 */
  command: string;
  /** 请求前固定的非秘密输入。 */
  body: T;
};

/** 创造候选和构建共用提交恢复。每种动作保留自己的目标及类型校验。 */
export function useReleaseCommand<T extends object>({
  key,
  path,
  parse,
  committed,
  conflict,
  beforeWrite,
}: {
  /** 按执行者和业务对象划分的本标签页存储位置。 */
  key: string;
  /** 当前动作的真实服务路径。 */
  path: string;
  /** 只恢复当前动作能理解的类型化原始输入。 */
  parse: (body: object) => T | undefined;
  /** 已确认落库后回读当前页面。 */
  committed: (targetId: string) => Promise<unknown>;
  /** 真实依赖或版本改变时刷新当前页面。 */
  conflict: () => void;
  /** 真正发送写入前记录页面操作上下文。 */
  beforeWrite?: () => void;
}) {
  const [pending, setPending] = useState(() => read(key, parse));
  const request = useMutation({
    /** 核实只读取原命令；重试始终沿用原始编号及输入。 */
    mutationFn: async ({ attempt, verify }: { attempt: Attempt<T>; verify: boolean }) => {
      if (verify) {
        // 未查到记录后仍需凭原始编号由用户显式重试。
        try {
          const status = await api<{ state: string; target: { id: string } | null }>(
            `/commands/${attempt.command}?${queryString({ operationCode: 'releases.build' })}`,
          );
          return status.state === 'COMMITTED' && status.target?.id
            ? { state: 'COMMITTED', targetId: status.target.id }
            : { state: 'PENDING' };
        } catch (error) {
          if (error instanceof ApiError && error.code === 404001) return { state: 'NOT_FOUND' };
          throw error;
        }
      }
      const receipt = await mutation<{ targetId: string }>(
        path,
        'POST',
        attempt.body,
        'releases.build',
        attempt.command,
      );
      return { state: 'COMMITTED', targetId: receipt.targetId };
    },
    /** 以实际提交回执推动页面更新。 */
    onSuccess: async (outcome) => {
      if (outcome.state !== 'COMMITTED' || !outcome.targetId) return;
      save(key);
      setPending(undefined);
      await committed(outcome.targetId);
    },
    /** 明确拒绝可结束原操作，网络故障则保留原编号。 */
    onError: (error, { verify }) => {
      if (verify || !(error instanceof ApiError) || error.code >= 500000 || error.code === 409004)
        return;
      save(key);
      setPending(undefined);
      if ([409001, 409301, 409302].includes(error.code)) conflict();
    },
  });

  /** 写入前保存恢复资料，以便刷新或返回原任务时继续核实。 */
  function submit(body: T) {
    if (pending || request.isPending) return;
    const attempt = { command: crypto.randomUUID(), body };
    save(key, attempt);
    setPending(attempt);
    beforeWrite?.();
    request.mutate({ attempt, verify: false });
  }

  const retry = request.data?.state === 'NOT_FOUND';
  const sending = request.isPending && request.variables?.verify === false;
  const action = sending ? '正在提交' : retry ? '重试原提交' : '核实原提交';
  const note = sending
    ? undefined
    : pending
      ? retry
        ? '暂未查到原提交记录，可以使用原操作编号重试。'
        : (request.error?.message ?? '请核实原提交结果。')
      : request.error?.message;

  /** 先核实，再决定是否以原编号重送。 */
  function resolve() {
    if (!pending || request.isPending) return;
    if (retry) beforeWrite?.();
    request.mutate({ attempt: pending, verify: !retry });
  }

  return { pending, busy: request.isPending, sending, action, note, submit, resolve };
}

/** 读取当前对象的原命令，兼容先前候选准备保存的同名字段。 */
function read<T>(key: string, parse: (body: object) => T | undefined): Attempt<T> | undefined {
  try {
    const value = JSON.parse(sessionStorage.getItem(key) ?? 'null') as Record<
      string,
      unknown
    > | null;
    if (typeof value?.command !== 'string') return undefined;
    const body = parse(value.body && typeof value.body === 'object' ? value.body : value);
    return body ? { command: value.command, body } : undefined;
  } catch {
    // 浏览器禁用存储时当前页面仍可在内存核实命令。
    return undefined;
  }
}

/** 仅在本标签页保存非秘密输入，读取服务端结果后删除。 */
function save<T>(key: string, attempt?: Attempt<T>) {
  try {
    if (attempt) sessionStorage.setItem(key, JSON.stringify(attempt));
    else sessionStorage.removeItem(key);
  } catch {
    // 存储不可用时仍保留当前页的原命令。
  }
}
