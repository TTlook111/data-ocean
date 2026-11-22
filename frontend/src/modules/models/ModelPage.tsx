import { useState } from 'react';
import { useSearchParams } from 'react-router';
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
import { LoadingStatus } from '../../shared/LoadingIndicator';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, readErrorMessage, Status } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { useFormClose } from '../../shared/useFormClose';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page } from '../iam/types';
import {
  modelFailureLabels,
  modelCapabilities,
  modelNodeInfo,
  modelProtocols,
  protocolCapability,
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
  const [params] = useSearchParams();
  // 从发布准备进入时直接定位相关节点。
  const [tab, setTab] = useState(params.get('tab') === 'nodes' ? 'nodes' : 'models');
  const [editor, setEditor] = useState<ModelConfig | 'new' | null>(null);
  const [detail, setDetail] = useState<ModelConfig | null>(null);
  const [keyword, setKeyword] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [paging, setPaging] = useState({ pageNum: 1, pageSize: 10 });
  const [nodeKeyword, setNodeKeyword] = useState(params.get('node') ?? '');
  const [nodeModule, setNodeModule] = useState<string>();
  const [draftNode, setDraftNode] = useState(params.get('node') ?? '');
  const [draftModule, setDraftModule] = useState<string>();
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
  const nodeOrder = Object.keys(modelNodeInfo);
  const nodeGroups = [
    ...new Set(Object.values(modelNodeInfo).map(({ module }) => module ?? '数据治理')),
  ];
  const visibleNodes = nodes.data
    ?.filter(({ code }) => {
      // 模块和关键词取交集，保留产品流程顺序和真实节点能力。
      const info = modelNodeInfo[code];
      if (nodeModule && (info?.module ?? '数据治理') !== nodeModule) return false;
      return (info?.name + ' ' + info?.description + ' ' + code)
        .toLowerCase()
        .includes(nodeKeyword.trim().toLowerCase());
    })
    .sort((left, right) => nodeOrder.indexOf(left.code) - nodeOrder.indexOf(right.code));

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
      node.capability !== 'RERANK' &&
      !(await modal.confirm({
        title: `清空「${modelNodeInfo[node.code]?.name ?? node.code}」的模型？`,
        content: '清空后，该节点将处于未配置状态。',
        okText: '清空',
        cancelText: '取消',
      }))
    )
      return;
    assignment.mutate({ node, modelId: modelId ?? null });
  }

  // 查询条件与结果的同名列对齐，详情动作集中在底栏。
  const modelColumns: TableProps<ModelConfig>['columns'] = [
    { title: '配置名称', dataIndex: 'name', width: 180, ellipsis: true },
    {
      title: '模型类型',
      width: 108,
      render: (_, row) => modelCapabilities[protocolCapability[row.protocol]],
    },
    {
      title: '接口格式',
      dataIndex: 'protocol',
      width: 220,
      render: (_, row) => modelProtocols[row.protocol],
    },
    { title: '模型名称', dataIndex: 'modelName', width: 180, ellipsis: true },
    {
      title: '状态',
      width: 100,
      render: (_, row) => (
        <Status
          value={row.enabled ? 'ENABLED' : 'DISABLED'}
          label={row.enabled ? '已启用' : '已停用'}
        />
      ),
    },
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
  const nodeColumns: TableProps<ModelNode>['columns'] = [
    {
      title: '节点',
      width: 224,
      render: (_, node) => (
        <>
          {/* 节点名称与所属模块采用统一的双行尺度。 */}
          <span className="model-node-name">{modelNodeInfo[node.code]?.name ?? node.code}</span>
          <span className="table-secondary">{modelNodeInfo[node.code]?.module ?? '数据治理'}</span>
        </>
      ),
    },
    {
      title: '这个节点做什么',
      render: (_, node) => modelNodeInfo[node.code]?.description ?? '—',
    },
    {
      title: '分配模型',
      width: 228,
      className: 'model-node-assignment',
      render: (_, node) => (
        <Select
          aria-label={`为${modelNodeInfo[node.code]?.name ?? node.code}分配模型`}
          placeholder="未分配"
          size="middle"
          className="model-node-picker"
          allowClear
          showSearch
          optionFilterProp="label"
          value={node.modelId ?? undefined}
          disabled={!allowed(session, 'models.assign') || assignment.isPending || options.isError}
          loading={options.isPending}
          options={options.data
            ?.filter((row) => protocolCapability[row.protocol] === node.capability)
            .map((row) => ({
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
      {/* 两个工作区共享顶部页签，筛选栏独立占一行。 */}
      <Tabs
        className="model-tabs"
        activeKey={tab}
        onChange={setTab}
        tabBarExtraContent={
          tab === 'models' &&
          allowed(session, 'models.create') && (
            <Button type="primary" onClick={() => setEditor('new')}>
              新增模型
            </Button>
          )
        }
        items={[
          {
            key: 'models',
            label: '模型列表',
            children: (
              <>
                {/* 配置名称保留真实服务端查询，重置后返回首面。 */}
                <div className="panel query-panel model-query">
                  <Form
                    layout="inline"
                    onFinish={() => {
                      setKeyword(searchInput.trim());
                      setPaging((current) => ({ ...current, pageNum: 1 }));
                    }}
                  >
                    <Form.Item>
                      <Input
                        aria-label="配置名称"
                        placeholder="配置名称"
                        allowClear
                        value={searchInput}
                        onChange={(event) => setSearchInput(event.target.value)}
                      />
                    </Form.Item>
                    <Space>
                      <Button htmlType="submit">查询</Button>
                      <Button
                        onClick={() => {
                          setKeyword('');
                          setSearchInput('');
                          setPaging((current) => ({ ...current, pageNum: 1 }));
                        }}
                      >
                        重置
                      </Button>
                    </Space>
                  </Form>
                </div>
                <section className="panel records-panel" aria-label="模型列表">
                  {/* 模型配置结果与真实分页保持在同一面板。 */}
                  <RecordTable
                    rowKey="id"
                    columns={modelColumns}
                    dataSource={models.data?.items}
                    loading={models.isPending}
                    error={models.error}
                    retry={() => void models.refetch()}
                    filtered={Boolean(keyword)}
                    onReset={() => {
                      // 清空在用关键词和输入框，并从第一页重查。
                      setSearchInput('');
                      setKeyword('');
                      setPaging((current) => ({ ...current, pageNum: 1 }));
                    }}
                    emptyDescription="暂无模型配置"
                    pagination={{
                      current: models.data?.pageNum ?? paging.pageNum,
                      pageSize: paging.pageSize,
                      total: models.data?.total,
                      onChange: (pageNum, pageSize) => setPaging({ pageNum, pageSize }),
                    }}
                  />
                </section>
              </>
            ),
          },
          {
            key: 'nodes',
            label: '节点列表',
            children: (
              <>
                {/* 节点查询沿用原型先编辑条件、再统一提交的交互。 */}
                <div className="panel query-panel model-query model-node-query">
                  <Form
                    layout="inline"
                    onFinish={() => {
                      setNodeKeyword(draftNode);
                      setNodeModule(draftModule);
                    }}
                  >
                    <Form.Item>
                      <Input
                        aria-label="节点名称或用途"
                        placeholder="节点名称或用途"
                        allowClear
                        value={draftNode}
                        onChange={(event) => setDraftNode(event.target.value)}
                      />
                    </Form.Item>
                    <Form.Item className="model-node-module">
                      <Select
                        aria-label="所属模块"
                        placeholder="所属模块"
                        allowClear
                        value={draftModule}
                        onChange={setDraftModule}
                        options={nodeGroups.map((name) => ({ value: name, label: name }))}
                      />
                    </Form.Item>
                    <Space>
                      <Button htmlType="submit">查询</Button>
                      <Button
                        onClick={() => {
                          setDraftNode('');
                          setDraftModule(undefined);
                          setNodeKeyword('');
                          setNodeModule(undefined);
                        }}
                      >
                        重置
                      </Button>
                    </Space>
                  </Form>
                </div>
                <section className="panel records-panel" aria-label="节点列表">
                  {/* 节点表直接承接独立分配，没有额外的保存步骤。 */}
                  <RecordTable
                    rowKey="code"
                    columns={nodeColumns}
                    dataSource={visibleNodes}
                    filtered={Boolean(nodeKeyword || nodeModule)}
                    onReset={() => {
                      // 清空客户端节点筛选，不影响已保存模型分配。
                      setNodeKeyword('');
                      setNodeModule(undefined);
                      setDraftNode('');
                      setDraftModule(undefined);
                    }}
                    loading={nodes.isPending}
                    error={nodes.error ?? options.error}
                    retry={() => {
                      // 节点与下拉选项从独立接口回读。
                      void nodes.refetch();
                      void options.refetch();
                    }}
                    pagination={false}
                    scroll={{ x: 640 }}
                  />
                </section>
              </>
            ),
          },
        ]}
      />
      {/* 只显示凭据已设置，编辑表单不会预填秘密。 */}
      <Modal
        className="app-dialog"
        title={detail ? `模型详情 · ${detail.name}` : '模型详情'}
        centered
        width={680}
        open={Boolean(detail)}
        onCancel={() => setDetail(null)}
        destroyOnHidden
        footer={
          detail && (
            <Space wrap>
              {/* 关闭作为次要入口，配置维护的主动作留在右端。 */}
              <Button onClick={() => setDetail(null)}>关闭</Button>
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
            </Space>
          )
        }
      >
        {detail && (
          <Descriptions
            className="model-detail-values"
            column={1}
            items={[
              { key: 'name', label: '配置名称', children: detail.name },
              {
                key: 'type',
                label: '模型类型',
                children: modelCapabilities[protocolCapability[detail.protocol]],
              },
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
  if (testing || pending) {
    // 测试和读取期间都在结果位置给出明确的等待反馈。
    return <LoadingStatus>{testing ? '测试中' : '读取中'}</LoadingStatus>;
  }
  if (error || !result) {
    // 查询失败与确实未测分别表达。
    return <span role="status">{error ? '读取失败' : '未测试'}</span>;
  }
  // 连接测试在模型详情回看，未知响应时提示核对连接入口。
  const failure =
    result.failureCode === 'MODEL_RESULT_UNKNOWN'
      ? '未收到可确认的响应，请检查地址和网络后重试。'
      : (modelFailureLabels[result.failureCode ?? ''] ?? '连接测试失败。');
  return (
    <Space role="status" orientation="vertical" size={4}>
      {/* 状态与发生时间分行，失败原因保留全文。 */}
      <Status
        value={result.passed ? 'SUCCEEDED' : 'FAILED'}
        label={result.passed ? '通过' : '失败'}
      />
      {!result.passed && <span>{failure}</span>}
      <span className="table-secondary">{displayTime(result.testedAt)}</span>
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
  useReturnFocus();
  const [form] = Form.useForm<ModelInput>();
  const [capability, setCapability] = useState<keyof typeof modelCapabilities>(
    row ? protocolCapability[row.protocol] : 'GENERATION',
  );
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
      className="app-dialog"
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
    >
      {/* 模型能力约束可选协议，凭据与其他接入信息沿用同一表单。 */}
      <Form
        form={form}
        layout="vertical"
        className="form-grid model-editor-form"
        initialValues={row ?? { timeoutSeconds: 60 }}
        disabled={save.isPending}
        scrollToFirstError={{ focus: true }}
        onFinish={(input) => {
          // 等待首笔保存结束再接受新的提交。
          if (!save.isPending) save.mutate(input);
        }}
        autoComplete="off"
      >
        <Form.Item label="模型类型">
          <Select
            aria-label="模型类型"
            value={capability}
            options={Object.entries(modelCapabilities).map(([value, label]) => ({ value, label }))}
            onChange={(value) => {
              // 能力切换时清除旧协议，避免提交不匹配格式。
              setCapability(value);
              form.setFieldValue('protocol', undefined);
            }}
          />
        </Form.Item>
        <Form.Item
          name="protocol"
          label="接口格式"
          rules={[{ required: true, message: '请选择接口格式' }]}
        >
          <Select
            options={(Object.keys(modelProtocols) as (keyof typeof modelProtocols)[])
              .filter((value) => protocolCapability[value] === capability)
              .map((value) => ({ value, label: modelProtocols[value] }))}
          />
        </Form.Item>
        <Form.Item name="name" label="配置名称" rules={[{ required: true, whitespace: true }]}>
          <Input maxLength={100} />
        </Form.Item>
        <Form.Item
          name="endpoint"
          className="form-field-wide"
          label="服务 URL"
          rules={[
            { required: true, type: 'url', message: '请输入有效的服务地址' },
            { pattern: /^https:\/\//iu, message: '请输入 HTTPS 服务地址' },
          ]}
        >
          <Input maxLength={500} />
        </Form.Item>
        <Form.Item
          name="modelName"
          label="模型名称"
          className="form-field-wide"
          rules={[{ required: true, whitespace: true }]}
        >
          <Input maxLength={120} />
        </Form.Item>
        <Form.Item
          name="credential"
          className="form-field-wide"
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
