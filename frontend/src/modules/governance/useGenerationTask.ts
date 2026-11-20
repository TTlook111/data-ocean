import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, ApiError } from '../../shared/api';
import type { Generation } from './types';

/**
 * 批次和单项共有的实际终态。
 */
const terminal = new Set(['SUCCEEDED', 'PARTIAL_FAILED', 'FAILED']);

/**
 * 订阅轻量状态流并按当前权限回读详情。连接失效时以低频查询恢复进度。
 */
export function useGenerationTask(source: string, request?: string) {
  const [connected, setConnected] = useState<string>();
  const path = '/governance/sources/' + source + '/generations/' + request;
  const task = useQuery({
    queryKey: ['governance', source, 'generation', request],
    queryFn: () => api<Generation>(path),
    enabled: Boolean(request),
    refetchInterval: ({ state }) => {
      // 终态和明确失权均停止查询，流式连接可用时只响应事件。
      const inaccessible =
        state.error instanceof ApiError &&
        [401, 403, 404].includes(Math.floor(state.error.code / 1000));
      return inaccessible || terminal.has(state.data?.state ?? '') || connected === path
        ? false
        : 2500;
    },
  });
  const { refetch } = task;
  const active = Boolean(request) && !terminal.has(task.data?.state ?? '');
  useEffect(() => {
    // 任务切换和模态关闭时释放原流，避免迟到进度应用到其他条目。
    if (!active || typeof EventSource === 'undefined') return;
    const stream = new EventSource('/api/v1' + path);
    const updated = () => {
      // 通知只触发授权详情回读，事件内容不落入编辑区。
      setConnected(path);
      void refetch();
    };
    stream.addEventListener('generation.snapshot', updated);
    stream.addEventListener('generation.updated', updated);
    stream.onerror = () => {
      // 立即回读终态或失权，再由查询间隔兜底。
      setConnected(undefined);
      stream.close();
      void refetch();
    };
    return () => {
      stream.close();
    };
  }, [active, path, refetch]);

  return task;
}
