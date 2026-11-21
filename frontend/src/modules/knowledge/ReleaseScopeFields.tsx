import { Select } from 'antd';
import { useInfiniteQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, queryString } from '../../shared/api';
import type { Page } from '../iam/types';
import type { ReleaseCheck } from './useReleaseScopeCheck';

/**
 * 只从当前来源查找已确认文档，按标题搜索并逐页加载。
 */
export function ReleaseDocumentPicker({
  source,
  documentId,
  title,
  label,
  clearable,
  disabled,
  choose,
}: {
  /** 当前任务或列表所在的数据源。 */
  source: string;
  /** 当前明示选中的文档。 */
  documentId: string | null;
  /** 已有范围或新预检提供的选项标题。 */
  title?: string;
  /** 查找控件的提示与可访问名称。 */
  label: string;
  /** 列表筛选可以清空选择。 */
  clearable?: boolean;
  /** 原提交待核实期间保留本次选择。 */
  disabled?: boolean;
  /** 选中后交给当前页面读取对应范围。 */
  choose: (value: string | null) => void;
}) {
  const [search, setSearch] = useState('');
  const [picked, setPicked] = useState<{ id: string; title: string }>();
  const documents = useInfiniteQuery({
    queryKey: ['knowledge-release-options', source, search],
    initialPageParam: 1,
    queryFn: ({ pageParam }) =>
      api<Page<{ id: string; title: string }>>(
        '/knowledge/sources/' +
          source +
          '/release-preflight/document-options?' +
          queryString({ keyword: search, pageNum: pageParam, pageSize: 20 }),
      ),
    getNextPageParam: ({ pageNum, pageSize, total }) =>
      pageNum * pageSize < total ? pageNum + 1 : undefined,
  });
  const options = documents.data?.pages.flatMap((page) => page.items) ?? [];
  const selectedTitle =
    title ??
    (picked?.id === documentId
      ? picked.title
      : options.find((row) => row.id === documentId)?.title);
  return (
    <Select
      className="release-document-select"
      aria-label={label}
      placeholder={label}
      allowClear={clearable}
      disabled={disabled}
      labelInValue
      value={documentId && selectedTitle ? { value: documentId, label: selectedTitle } : undefined}
      showSearch={{ filterOption: false, onSearch: setSearch }}
      loading={documents.isFetching}
      options={options.map((item) => ({ value: item.id, label: item.title }))}
      onOpenChange={(open) => {
        // 关闭下拉后恢复同源完整选项。
        if (!open) setSearch('');
      }}
      onChange={(value) => {
        // 清空搜索不会丢失用户刚选择的目标。
        if (value && typeof value.label === 'string') {
          setPicked({ id: value.value, title: value.label });
        }
        setSearch('');
        choose(value?.value ?? null);
      }}
      onPopupScroll={(event) => {
        // 接近列表底部时续读下一页。
        const list = event.currentTarget;
        if (
          documents.hasNextPage &&
          !documents.isFetching &&
          list.scrollHeight - list.scrollTop - list.clientHeight < 32
        )
          void documents.fetchNextPage();
      }}
    />
  );
}

/**
 * 范围确认共用目标修订、题单与向量配置摘要。
 */
export function ReleaseScopeSummary({ result }: { result: ReleaseCheck }) {
  return (
    <dl className="knowledge-preflight-summary">
      {/* 正式基线与本次确认修订从实际预检回读。 */}
      <div>
        <dt>发布前 → 目标修订</dt>
        <dd>
          {result.baseBindingId ?? '首次发布'} →{' '}
          {result.documentRevisionNumber ? 'r' + result.documentRevisionNumber : '待确认'}
        </dd>
      </div>
      <div>
        <dt>相关已审核题目</dt>
        <dd>{result.reviewedTests} 道</dd>
      </div>
      <div>
        <dt>知识检索向量化</dt>
        <dd>{result.embeddingRevisionId ? '已分配' : '未就绪'}</dd>
      </div>
    </dl>
  );
}
