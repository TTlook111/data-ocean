import { DetailDialog } from '../../shared/DetailDialog';
import { useState } from 'react';
import { Button, Descriptions, Tabs } from 'antd';
import type { TableProps } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, Status } from '../../shared/Records';
import type { Page } from '../iam/types';
import { modelFailureLabels, modelNodeInfo, modelProtocols, type ModelConfig } from './types';

/**
 * 调用记录复用配置的字段含义，历史缺项显示未知。
 */
interface ModelCall {
  /**
   * 调用记录 ID。
   */
  id: string;
  /**
   * 实际用于当次调用的业务节点。
   */
  nodeCode: string;
  /**
   * 当次配置名称。
   */
  configName: string | null;
  /**
   * 当次使用的协议，早期联调记录可能未知。
   */
  protocol: ModelConfig['protocol'] | null;
  /**
   * 调用绑定的配置修订号。
   */
  revision: number | null;
  /**
   * 提供方报告的实际模型。
   */
  modelName: string | null;
  /**
   * 当次的已准入服务基址。
   */
  endpoint: string | null;
  /**
   * 调用执行状态。
   */
  state: string;
  /**
   * 已知诊断原因。
   */
  failureCode: string | null;
  /**
   * 实际耗时，毫秒。
   */
  elapsedMs: number | null;
  /**
   * 输入 token 数。
   */
  inputTokens: number | null;
  /**
   * 输出 token 数。
   */
  outputTokens: number | null;
  /**
   * 可披露的上下文裁剪原因编码。
   */
  inputLimits?: string | null;
  /**
   * 调用的 UTC 时间。
   */
  recordedAt: string;
}

/**
 * 诊断只显示限制原因，原始资料和列名沿用治理权限。
 */
const inputLimitLabels: Record<string, string> = {
  COLUMN_LIMIT: '结构列数超过上限',
  INPUT_BUDGET: '部分上下文超出输入预算',
  ATTACHMENT_UNAVAILABLE: '部分附件未能提取文本',
};

/**
 * 早期调用记录中的节点编码也按当次业务职责显示。
 */
function displayCallNode(code: string) {
  const node = code === 'TABLE_EXPLANATION' ? 'table-description' : code;
  return modelNodeInfo[node]?.name ?? code;
}

/**
 * 显示真实调用及使用的历史配置修订。
 */
export function ModelCallsPage() {
  const [paging, setPaging] = useState({ pageNum: 1, pageSize: 10 });
  const [detail, setDetail] = useState<ModelCall | null>(null);
  const calls = useQuery({
    queryKey: ['model-calls', paging],
    queryFn: () => api<Page<ModelCall>>(`/models/calls?${queryString(paging)}`),
  });
  // 调用节点以当次回执为准。
  const columns: TableProps<ModelCall>['columns'] = [
    {
      title: '当次模型 / 修订',
      width: 200,
      ellipsis: true,
      // 历史修订作为副行，当前名称与调用时的名称都保留完整。
      render: (_, row) => (
        <div className="model-call-identity">
          <span className="cell-text" title={row.configName ?? row.modelName ?? undefined}>
            {row.configName ?? row.modelName ?? '未知'}
          </span>
          {row.revision !== null && <small className="table-secondary">r{row.revision}</small>}
        </div>
      ),
    },
    {
      title: '节点',
      width: 130,
      render: (_, row) => displayCallNode(row.nodeCode),
    },
    {
      title: '接口格式',
      width: 180,
      render: (_, row) => (row.protocol ? modelProtocols[row.protocol] : '未知'),
    },
    {
      title: '状态',
      width: 100,
      render: (_, row) => (
        <Status value={row.state} label={row.state === 'SUCCEEDED' ? '成功' : '失败'} />
      ),
    },
    { title: '发生时间', width: 175, render: (_, row) => displayTime(row.recordedAt) },
    {
      title: '操作',
      width: 120,
      fixed: 'right',
      className: 'record-actions-cell',
      render: (_, row) => (
        <Button type="link" onClick={() => setDetail(row)}>
          查看详情
        </Button>
      ),
    },
  ];
  return (
    <section className="record-page" aria-label="调用记录与故障">
      {/* 已接入的调用记录独立成页签，配置变更和全局测试记录仍需业务接入。 */}
      <Tabs
        className="model-call-tabs"
        activeKey="calls"
        items={[
          { key: 'calls', label: '调用记录' },
          { key: 'connection', label: '连接测试', disabled: true },
          { key: 'revisions', label: '配置变更', disabled: true },
        ]}
      />
      {/* 诊断列表使用共用分页和空态。 */}
      <div className="panel records-panel">
        <RecordTable
          rowKey="id"
          columns={columns}
          dataSource={calls.data?.items}
          loading={calls.isPending}
          error={calls.error}
          emptyDescription="暂无模型调用记录"
          retry={() => void calls.refetch()}
          pagination={{
            current: calls.data?.pageNum ?? paging.pageNum,
            pageSize: paging.pageSize,
            total: calls.data?.total,
            onChange: (pageNum, pageSize) => setPaging({ pageNum, pageSize }),
          }}
        />
      </div>
      {/* 详情仅显示当次配置元数据和可披露的调用事实。 */}
      {detail && (
        <DetailDialog title="调用详情" width={720} close={() => setDetail(null)}>
          <Descriptions
            className="model-detail-values"
            column={2}
            layout="vertical"
            colon={false}
            items={[
              { key: 'config', label: '配置名称（当次）', children: detail.configName ?? '未知' },
              { key: 'node', label: '节点', children: displayCallNode(detail.nodeCode) },
              {
                key: 'status',
                label: '调用状态',
                children: (
                  <Status
                    value={detail.state}
                    label={detail.state === 'SUCCEEDED' ? '成功' : '失败'}
                  />
                ),
              },
              {
                key: 'format',
                label: '接口格式',
                children: detail.protocol ? modelProtocols[detail.protocol] : '未知',
              },
              { key: 'model', label: '实际模型', children: detail.modelName ?? '未知' },
              {
                key: 'revision',
                label: '当次配置修订',
                children: detail.revision ? `r${detail.revision}` : '未知',
              },
              { key: 'time', label: '发生时间', children: displayTime(detail.recordedAt) },
              {
                key: 'elapsed',
                label: '耗时',
                children: detail.elapsedMs === null ? '未知' : `${detail.elapsedMs} 毫秒`,
              },
              {
                key: 'usage',
                label: '用量',
                span: 2,
                children:
                  detail.inputTokens === null || detail.outputTokens === null
                    ? '未知'
                    : `输入 ${detail.inputTokens} · 输出 ${detail.outputTokens} token`,
              },
              { key: 'endpoint', label: '服务 URL', children: detail.endpoint ?? '未知', span: 2 },
              ...(detail.inputLimits
                ? [
                    {
                      key: 'input-limits',
                      label: '输入范围',
                      span: 2,
                      children: detail.inputLimits
                        .split(',')
                        .map((code) => inputLimitLabels[code])
                        .filter(Boolean)
                        .join('、'),
                    },
                  ]
                : []),
              ...(detail.failureCode
                ? [
                    {
                      key: 'cause',
                      label: '原因',
                      span: 2,
                      children: modelFailureLabels[detail.failureCode] ?? '模型调用未完成',
                    },
                  ]
                : []),
            ]}
          />
        </DetailDialog>
      )}
    </section>
  );
}
