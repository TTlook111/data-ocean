import { useState } from 'react';
import {
  App,
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Tabs,
} from 'antd';
import type { TableProps } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, queryString } from '../../shared/api';
import { applyFormError } from '../../shared/formErrors';
import { RecordTable } from '../../shared/RecordTable';
import { DetailFooter, readErrorMessage, Status } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { useFormClose } from '../../shared/useFormClose';
import type { Page } from '../iam/types';
import { modelProtocols, type ModelConfig, type ModelInput, type ModelNode } from './types';

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
        title: '清空表说明生成的模型？',
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
      render: () => (
        <>
          <strong>表说明生成</strong>
          <div>数据治理</div>
        </>
      ),
    },
    {
      title: '这个节点做什么',
      width: 350,
      render: () => '结合表结构和企业业务说明，提出用途、粒度、范围及更新规则建议。',
    },
    {
      title: '分配模型',
      width: 280,
      render: (_, node) => (
        <Select
          aria-label="为表说明生成分配模型"
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
    <section aria-label="模型配置">
      {/* 新增只在模型列表提供，节点页直接在表格分配。 */}
      <Tabs
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
              <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
                <Input.Search
                  aria-label="配置名称"
                  placeholder="配置名称"
                  allowClear
                  style={{ width: 260 }}
                  onSearch={(value) => {
                    setKeyword(value.trim());
                    setPaging({ ...paging, pageNum: 1 });
                  }}
                />
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
              </Space>
            ),
          },
          {
            key: 'nodes',
            label: '节点列表',
            children: (
              <RecordTable
                rowKey="code"
                columns={nodeColumns}
                dataSource={nodes.data}
                loading={nodes.isPending}
                error={nodes.error ?? options.error}
                retry={() => {
                  void nodes.refetch();
                  void options.refetch();
                }}
                pagination={false}
              />
            ),
          },
        ]}
      />
      {/* 只显示凭据已设置，编辑表单不会预填秘密。 */}
      <Drawer
        title="模型详情"
        open={Boolean(detail)}
        onClose={() => setDetail(null)}
        destroyOnHidden
        footer={
          detail && (
            <DetailFooter close={() => setDetail(null)}>
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
            ]}
          />
        )}
      </Drawer>
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
      if (notice) void message.error(notice);
    },
  });
  const requestClose = useFormClose(form, close, save.isPending);
  return (
    <Drawer
      open
      title={row ? '编辑模型' : '新增模型'}
      onClose={() => void requestClose()}
      footer={
        <DetailFooter close={() => void requestClose()}>
          <Button type="primary" loading={save.isPending} onClick={() => form.submit()}>
            保存模型
          </Button>
        </DetailFooter>
      }
    >
      {/* 模型类型固定为当前已接入的生成能力，接口由管理人员明确选择。 */}
      <Form
        form={form}
        layout="vertical"
        initialValues={row ?? { timeoutSeconds: 60 }}
        onFinish={(input) => save.mutate(input)}
        autoComplete="off"
      >
        <Form.Item label="模型类型">
          <Input value="生成" readOnly />
        </Form.Item>
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
        <Form.Item name="endpoint" label="服务 URL" rules={[{ required: true, type: 'url' }]}>
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
    </Drawer>
  );
}
