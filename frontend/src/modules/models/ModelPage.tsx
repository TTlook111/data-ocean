import { useState } from 'react';
import {
  App,
  Button,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Tabs,
} from 'antd';
import type { TableProps } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, queryString } from '../../shared/api';
import { applyFormError } from '../../shared/formErrors';
import { RecordTable } from '../../shared/RecordTable';
import { DetailFooter, displayTime, readErrorMessage, Status } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { useFormClose } from '../../shared/useFormClose';
import type { Page } from '../iam/types';
import {
  modelFailureLabels,
  modelNodeInfo,
  modelProtocols,
  type ModelConfig,
  type ModelConnectionTest,
  type ModelInput,
  type ModelNode,
} from './types';

/**
 * 模型配置和独立节点分配沿用原型的两个页签。
 */
export function ModelPage() {
  const session = useSession();
  const { modal, message } = App.useApp();
  const client = useQueryClient();
  const [tab, setTab] = useState('models');
  const [editor, setEditor] = useState<ModelConfig | 'new' | null>(null);
  const [detail, setDetail] = useState<ModelConfig | null>(null);
  const [keyword, setKeyword] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [paging, setPaging] = useState({ pageNum: 1, pageSize: 10 });
  const models = useQuery({
    queryKey: ['models', 'list', paging, keyword],
    queryFn: () => api<Page<ModelConfig>>(`/models?${queryString({ ...paging, keyword })}`),
    enabled: tab === 'models',
  });
  const nodes = useQuery({
    queryKey: ['models', 'nodes'],
    queryFn: () => api<ModelNode[]>('/models/nodes'),
    enabled: tab === 'nodes',
  });
  const options = useQuery({
    queryKey: ['models', 'options'],
    queryFn: () => api<ModelConfig[]>('/models/options'),
    enabled: tab === 'nodes',
  });

  // 以当前详情的配置修订读取记录，避免把旧配置的结果带入新修订。
  const connectionPath = detail ? `/models/${detail.id}/connection-tests/${detail.revisionId}` : '';
  const connection = useQuery({
    queryKey: ['models', 'connection-test', connectionPath],
    queryFn: async () => (await api<ModelConnectionTest | null>(connectionPath)) ?? null,
    enabled: Boolean(connectionPath),
  });
  const testConnection = useMutation({
    mutationFn: (path: string) => api<ModelConnectionTest>(path, { method: 'POST' }),
    onSuccess: (result, path) => {
      // 提交结果只更新本次请求对应的修订。
      client.setQueryData(['models', 'connection-test', path], result);
    },
    onError: (error) => {
      // 权限、并行编辑或服务未启用由上游统一反馈。
      void message.error(readErrorMessage(error));
    },
  });

  /**
   * 启停经确认后提交并发版本，停用仍保留节点关系。
   */
  async function setEnabled(row: ModelConfig) {
    const enabled = !row.enabled;
    const confirmed = await modal.confirm({
      title: `${enabled ? '恢复' : '停用'} ${row.name}`,
      content: enabled ? '关联节点可以继续发起调用。' : '引用此模型的节点将无法发起新调用。',
      okText: enabled ? '恢复' : '停用',
      okButtonProps: { danger: !enabled },
      cancelText: '取消',
    });
    if (!confirmed) return;
    try {
      // 模型表与节点选项读取新状态。
      await api(`/models/${row.id}/state`, {
        method: 'PUT',
        body: { enabled, version: row.version },
      });
      setDetail(null);
      await client.invalidateQueries({ queryKey: ['models'] });
    } catch (error) {
      void message.error(readErrorMessage(error as Error));
    }
  }

  const assignment = useMutation({
    mutationFn: ({ node, modelId }: { node: ModelNode; modelId: string | null }) =>
      api(`/models/nodes/${node.code}`, {
        method: 'PUT',
        body: { modelId, version: node.version },
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['models', 'nodes'] }),
    onError: (error) => {
      void message.error(readErrorMessage(error));
    },
  });

  /**
   * 选择即保存单个节点，清空必需节点先说明后果。
   */
  async function assign(node: ModelNode, modelId?: string) {
    if (
      !modelId &&
      !(await modal.confirm({
        title: `清空「${modelNodeInfo[node.code]?.name ?? node.code}」的模型？`,
        content: '清空后将暂时无法生成建议。',
        okText: '清空',
        cancelText: '取消',
      }))
    )
      return;
    assignment.mutate({ node, modelId: modelId ?? null });
  }

  // 查询条件与结果的同名列对齐，详情动作集中在底栏。
  const modelColumns: TableProps<ModelConfig>['columns'] = [
    { title: '配置名称', dataIndex: 'name', width: 180 },
    { title: '模型类型', width: 96, render: () => '生成' },
    {
      title: '接口格式',
      dataIndex: 'protocol',
      width: 220,
      render: (_, row) => modelProtocols[row.protocol],
    },
    { title: '模型名称', dataIndex: 'modelName', width: 180 },
    {
      title: '状态',
      width: 100,
      render: (_, row) => (
        <Status
          value={row.enabled ? 'ACTIVE' : 'DISABLED'}
          label={row.enabled ? '已启用' : '已停用'}
        />
      ),
    },
    {
      title: '操作',
      width: 106,
      fixed: 'right',
      render: (_, row) => (
        <Button type="link" onClick={() => setDetail(row)}>
          查看详情
        </Button>
      ),
    },
  ];
  const nodeColumns: TableProps<ModelNode>['columns'] = [
    {
      title: '节点',
      width: 170,
      render: (_, node) => (
        <>
          <strong>{modelNodeInfo[node.code]?.name ?? node.code}</strong>
          <div>数据治理</div>
        </>
      ),
    },
    {
      title: '这个节点做什么',
      width: 350,
      render: (_, node) => modelNodeInfo[node.code]?.description ?? '—',
    },
    {
      title: '分配模型',
      width: 280,
      render: (_, node) => (
        <Select
          aria-label={`为${modelNodeInfo[node.code]?.name ?? node.code}分配模型`}
          placeholder="未分配"
          size="middle"
          style={{ width: '100%' }}
          allowClear
          showSearch
          optionFilterProp="label"
          value={node.modelId ?? undefined}
          disabled={!allowed(session, 'models.assign') || assignment.isPending || options.isError}
          loading={options.isPending}
          options={options.data?.map((row) => ({
            value: row.id,
            label: row.enabled ? row.name : `${row.name}（已停用）`,
            disabled: !row.enabled,
          }))}
          onChange={(value) => {
            void assign(node, value);
          }}
        />
      ),
    },
  ];

  return (
    <section className="record-page" aria-label="模型配置">
      {/* 两个工作区共享页签，模型查询和新增仅在列表提供。 */}
      <Tabs
        className="model-tabs"
        activeKey={tab}
        onChange={setTab}
        tabBarExtraContent={
          tab === 'models' && (
            <Space>
              <Input.Search
                aria-label="配置名称"
                placeholder="配置名称"
                value={searchInput}
                onChange={(event) => setSearchInput(event.target.value)}
                allowClear
                onSearch={(value) => {
                  // 换查询条件时保留每页量并回到第一页。
                  setKeyword(value.trim());
                  setPaging({ ...paging, pageNum: 1 });
                }}
              />
              {allowed(session, 'models.create') && (
                <Button type="primary" onClick={() => setEditor('new')}>
                  新增模型
                </Button>
              )}
            </Space>
          )
        }
        items={[
          {
            key: 'models',
            label: '模型列表',
            children: (
              <section className="panel records-panel" aria-label="模型列表">
                {/* 模型配置结果与真实分页保持在同一面板。 */}
                <RecordTable
                  rowKey="id"
                  columns={modelColumns}
                  dataSource={models.data?.items}
                  loading={models.isPending}
                  error={models.error}
                  retry={() => void models.refetch()}
                  pagination={{
                    current: models.data?.pageNum ?? paging.pageNum,
                    pageSize: paging.pageSize,
                    total: models.data?.total,
                    onChange: (pageNum, pageSize) => setPaging({ pageNum, pageSize }),
                  }}
                />
              </section>
            ),
          },
          {
            key: 'nodes',
            label: '节点列表',
            children: (
              <section className="panel records-panel" aria-label="节点列表">
                {/* 节点表直接承接独立分配，没有额外的保存步骤。 */}
                <RecordTable
                  rowKey="code"
                  columns={nodeColumns}
                  dataSource={nodes.data}
                  loading={nodes.isPending}
                  error={nodes.error ?? options.error}
                  retry={() => {
                    // 节点与下拉选项从独立接口回读。
                    void nodes.refetch();
                    void options.refetch();
                  }}
                  pagination={false}
                />
              </section>
            ),
          },
        ]}
      />
      {/* 只显示凭据已设置，编辑表单不会预填秘密。 */}
      <Modal
        title={detail ? `模型详情 · ${detail.name}` : '模型详情'}
        centered
        width={680}
        open={Boolean(detail)}
        onCancel={() => setDetail(null)}
        destroyOnHidden
        footer={
          detail && (
            <DetailFooter close={() => setDetail(null)}>
              {/* 连接测试有独立权限，停用时禁止向提供方发起新请求。 */}
              {allowed(session, 'models.test') && (
                <Button
                  disabled={!detail.enabled}
                  loading={testConnection.isPending}
                  onClick={() => testConnection.mutate(connectionPath)}
                >
                  测试连接
                </Button>
              )}
              {allowed(session, 'models.disable') && (
                <Button danger={detail.enabled} onClick={() => void setEnabled(detail)}>
                  {detail.enabled ? '停用' : '恢复'}
                </Button>
              )}
              {allowed(session, 'models.edit') && (
                <Button
                  type="primary"
                  onClick={() => {
                    setEditor(detail);
                    setDetail(null);
                  }}
                >
                  编辑
                </Button>
              )}
            </DetailFooter>
          )
        }
      >
        {detail && (
          <Descriptions
            column={1}
            items={[
              { key: 'name', label: '配置名称', children: detail.name },
              { key: 'protocol', label: '接口格式', children: modelProtocols[detail.protocol] },
              { key: 'endpoint', label: '服务 URL', children: detail.endpoint },
              { key: 'model', label: '模型名称', children: detail.modelName },
              { key: 'credential', label: '访问凭据', children: '已设置' },
              { key: 'timeout', label: '调用超时', children: `${detail.timeoutSeconds} 秒` },
              { key: 'revision', label: '配置修订', children: `r${detail.revision}` },
              {
                key: 'connection',
                label: '最近连接测试',
                children: (
                  <ModelConnectionStatus
                    testing={
                      testConnection.isPending && testConnection.variables === connectionPath
                    }
                    pending={connection.isPending}
                    error={connection.isError}
                    result={connection.data}
                  />
                ),
              },
            ]}
          />
        )}
      </Modal>
      {/* 区分新建与编辑，提交后销毁暂存凭据的表单。 */}
      {editor && (
        <ModelEditor
          row={editor === 'new' ? undefined : editor}
          close={() => setEditor(null)}
          mayReplace={allowed(session, 'models.credential')}
        />
      )}
    </section>
  );
}

/**
 * 在详情原位展示测试状态、失败原因与完成时间。
 */
function ModelConnectionStatus({
  testing,
  pending,
  error,
  result,
}: {
  testing: boolean;
  pending: boolean;
  error: boolean;
  result?: ModelConnectionTest | null;
}) {
  if (testing) {
    // 测试期间就地标记正在等待提供方。
    return <span role="status">测试中…</span>;
  }
  if (pending || error || !result) {
    // 查询失败与确实未测分别表达。
    return <span role="status">{pending ? '读取中…' : error ? '读取失败' : '未测试'}</span>;
  }
  // 连接测试在模型详情回看，未知响应时提示核对连接入口。
  const failure =
    result.failureCode === 'MODEL_RESULT_UNKNOWN'
      ? '未收到可确认的响应，请检查地址和网络后重试。'
      : (modelFailureLabels[result.failureCode ?? ''] ?? '连接测试失败。');
  return (
    <Space role="status" orientation="vertical" size={2}>
      <span>{result.passed ? '通过' : failure}</span>
      <span>{displayTime(result.testedAt)}</span>
    </Space>
  );
}

/**
 * 新增和编辑使用同一表单及未保存保护。
 */
function ModelEditor({
  row,
  close,
  mayReplace,
}: {
  row?: ModelConfig;
  close: () => void;
  mayReplace: boolean;
}) {
  const [form] = Form.useForm<ModelInput>();
  const { message } = App.useApp();
  const client = useQueryClient();
  const save = useMutation({
    mutationFn: (input: ModelInput) =>
      api<ModelConfig>(row ? `/models/${row.id}` : '/models', {
        method: row ? 'PUT' : 'POST',
        body: { ...input, version: row?.version },
      }),
    onSuccess: () => {
      close();
      void client.invalidateQueries({ queryKey: ['models'] });
    },
    onError: (error) => {
      // 字段错误留在表单，其他失败用统一提示。
      const notice = applyFormError(form, error);
      if (notice) {
        // 合并同一次保存的重复通知，字段错误由表单原位处理。
        void message.error({ key: 'save-model', content: notice });
      }
    },
  });
  const requestClose = useFormClose(form, close, save.isPending);
  return (
    <Modal
      open
      centered
      width={680}
      title={row ? '编辑模型' : '新增模型'}
      onCancel={requestClose}
      onOk={() => form.submit()}
      okText="保存模型"
      cancelText="取消"
      confirmLoading={save.isPending}
      cancelButtonProps={{ disabled: save.isPending }}
      closable={!save.isPending}
      keyboard={!save.isPending}
      styles={{ body: { maxHeight: 'calc(100dvh - 180px)', overflowY: 'auto' } }}
    >
      {/* 当前页面只管理生成模型，表单保留需填写的配置。 */}
      <Form
        form={form}
        layout="vertical"
        initialValues={row ?? { timeoutSeconds: 60 }}
        disabled={save.isPending}
        scrollToFirstError={{ focus: true }}
        onFinish={(input) => {
          // 等待首笔保存结束再接受新的提交。
          if (!save.isPending) save.mutate(input);
        }}
        autoComplete="off"
      >
        <Form.Item
          name="protocol"
          label="接口格式"
          rules={[{ required: true, message: '请选择接口格式' }]}
        >
          <Select
            options={Object.entries(modelProtocols).map(([value, label]) => ({ value, label }))}
          />
        </Form.Item>
        <Form.Item name="name" label="配置名称" rules={[{ required: true, whitespace: true }]}>
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item
          name="endpoint"
          label="服务 URL"
          rules={[
            { required: true, type: 'url', message: '请输入有效的服务地址' },
            { pattern: /^https:\/\//iu, message: '请输入 HTTPS 服务地址' },
          ]}
        >
          <Input maxLength={500} />
        </Form.Item>
        <Form.Item name="modelName" label="模型名称" rules={[{ required: true, whitespace: true }]}>
          <Input maxLength={120} />
        </Form.Item>
        <Form.Item
          name="credential"
          label="访问凭据"
          rules={[{ required: !row, whitespace: true }]}
        >
          <Input.Password
            autoComplete="new-password"
            maxLength={2048}
            disabled={Boolean(row) && !mayReplace}
            placeholder={row ? '留空保留原凭据' : undefined}
          />
        </Form.Item>
        <Form.Item
          name="timeoutSeconds"
          label="调用超时（秒）"
          rules={[{ required: true, type: 'number', min: 1, max: 120 }]}
        >
          <InputNumber min={1} max={120} precision={0} />
        </Form.Item>
      </Form>
    </Modal>
  );
}
