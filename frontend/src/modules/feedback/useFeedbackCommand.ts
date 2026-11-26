import { useRef, useState } from 'react';
import { ApiError, mutation } from '../../shared/api';

/**
 * 保留尚待核实的操作，让重试始终使用同一地址、权限、内容和编号。
 */
type Attempt = {
  /** 实际写入入口。 */
  path: string;
  /** 回查命令所需的操作权限。 */
  operation: string;
  /** 上次已确认提交的表单快照。 */
  body: object;
  /** 命令的稳定防重编号。 */
  key: string;
};

/**
 * 四种反馈表单共用原编号核实、明确拒绝和权限刷新。
 */
export function useFeedbackCommand(refresh: () => Promise<unknown>) {
  const attempt = useRef<Attempt | undefined>(undefined);
  const [uncertain, setUncertain] = useState(false);

  /**
   * 提交有明确结果后释放原尝试，重新核对表单时也可主动清理。
   */
  function reset() {
    attempt.current = undefined;
    setUncertain(false);
  }

  /**
   * 网络结果不明时保留整次写入，等用户使用原编号重新核查。
   */
  async function send(path: string, body: object, operation: string) {
    const pending = attempt.current ?? { path, body, operation, key: crypto.randomUUID() };
    attempt.current = pending;
    try {
      // 共用传输层负责回查服务端已有的命令回执。
      await mutation(pending.path, 'POST', pending.body, pending.operation, pending.key);
      reset();
      return pending.path;
    } catch (failure) {
      // 业务拒绝可修正，结果未知要保留已经发送的提案。
      if (failure instanceof ApiError) {
        reset();
        if (failure.code === 403003) {
          // 权限变化后立即重新读取当前有权办理的范围。
          void refresh();
        }
      } else {
        // 等待确认期间不允许改写这次命令的内容。
        setUncertain(true);
      }
      throw failure;
    }
  }

  return { uncertain, send, reset };
}
