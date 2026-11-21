import { useQuery } from '@tanstack/react-query';
import { api, queryString } from '../../shared/api';

/**
 * 创建或调整任务前读取的范围和构建前置。
 */
export type ReleaseCheck = {
  /** 被核对文档的实际归属。 */
  documentId: string;
  /** 确切确认正文名称。 */
  documentTitle: string;
  /** 仍有效的确认修订。 */
  documentRevisionId: string | null;
  /** 确认正文的可读修订号。 */
  documentRevisionNumber: number | null;
  /** 当前生效绑定，首版为空。 */
  baseBindingId: string | null;
  /** 当前生效的正文修订号。 */
  baseRevisionNumber: number | null;
  /** 确认文档的治理依据是否满足保存范围的条件。 */
  documentReady: boolean;
  /** 已审核并适用的题目数量。 */
  reviewedTests: number;
  /** 当前有效的向量配置。 */
  embeddingRevisionId: string | null;
  /** 真实前置阻断。 */
  issues: string[];
};

/**
 * 打开范围核对时重新读取当前确认，缓存仅用于初始展示。
 */
export function useReleaseScopeCheck(source: string | undefined, documentId: string | null) {
  return useQuery({
    queryKey: ['knowledge-release-preflight', source, documentId],
    enabled: Boolean(source && documentId),
    refetchOnMount: 'always',
    queryFn: () =>
      api<ReleaseCheck>(
        '/knowledge/sources/' + source + '/release-preflight?' + queryString({ documentId }),
      ),
  });
}
