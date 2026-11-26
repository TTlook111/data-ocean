import { Alert, App, Button, Descriptions, Form, Input, Modal, Space } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../../shared/api';
import { LoadingStatus } from '../../shared/LoadingIndicator';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import type { Preview } from '../iam/types';
import { DatasourceStatus } from './Presentation';
import type { ConnectionCheck, CredentialChange, SourceDetail } from './types';

/**
 * 凭据更换只采集需要测试的账号和密码。
 */
interface ReplacementValues {
  /**
   * 更换后使用的专用只读账号。
   */
  account: string;
  /**
   * 新凭据仅在本次表单提交中使用。
   */
  password: string;
}

/**
 * 更换凭据使用独立操作回执，避免把接收请求显示为已经切换。
 */
export function CredentialDialog({ source, close }: { source: SourceDetail; close: () => void }) {
  const [form] = Form.useForm<ReplacementValues>();
  const [check, setCheck] = useState<ConnectionCheck | null>(null);
  const [review, setReview] = useState<ReviewSubmission | null>(null);
  const [command, setCommand] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const { message } = App.useApp();
  const path = '/datasources/' + source.source.id;
  const result = useQuery({
    queryKey: ['credential-change', source.source.id, command],
    queryFn: () => api<CredentialChange>(path + '/credential-changes/by-command/' + command),
    enabled: Boolean(command),
    refetchInterval: (query) => (query.state.data?.state === 'PENDING_STOP' ? 1200 : false),
  });

  /**
   * 新凭据测试时维持同一个数据库及所选 Schema。
   */
  async function test() {
    setBusy(true);
    try {
      // 连接测试绑定当前配置版本，服务端按该版本拒绝过期输入。
      const { account, password } = await form.validateFields();
      setCheck(
        await api<ConnectionCheck>(path + '/connection-checks', {
          method: 'POST',
          command: crypto.randomUUID(),
          body: {
            configuration: { ...source.configuration, account },
            password,
            runtimePolicy: source.runtimePolicy,
            expectedVersion: source.source.version,
          },
        }),
      );
    } catch (failure) {
      if (failure instanceof Error) message.error(failure.message);
    } finally {
      setBusy(false);
    }
  }

  /**
   * 同一确认同时绑定新测试和当时受影响的原任务。
   */
  async function prepare() {
    if (!check?.canEnable) return;
    try {
      const { account, password } = await form.validateFields();
      const proposal = { account, checkId: check.checkId, credentialProvided: true };
      const expectedVersion = source.source.version;
      const preview = await api<Preview>(path + '/impact-preview', {
        method: 'POST',
        body: { action: 'REPLACE_CREDENTIAL', expectedVersion, proposal },
      });
      setReview({
        title: '核对凭据更换',
        preview,
        path: path + '/credential-changes',
        method: 'POST',
        operation: 'sources.edit',
        body: { expectedVersion, proposal, password },
        confirmText: '确认更换',
        done: (_, originalCommand) => {
          // 提交后清除本次输入，并按原命令继续读取实际停止和切换结果。
          form.resetFields();
          setReview(null);
          setCommand(originalCommand ?? null);
        },
      });
    } catch (failure) {
      if (failure instanceof Error) message.error(failure.message);
    }
  }

  return (
    <>
      <Modal
        centered
        open={!review}
        title={'更换凭据 · ' + source.source.name}
        onCancel={close}
        width={640}
        footer={
          command ? (
            <Button onClick={close}>关闭</Button>
          ) : (
            <Space>
              <Button onClick={close}>取消</Button>
              <Button loading={busy} onClick={() => void test()}>
                {check ? '重新测试' : '测试新凭据'}
              </Button>
              <Button type="primary" disabled={!check?.canEnable} onClick={() => void prepare()}>
                核对更换
              </Button>
            </Space>
          )
        }
      >
        {/* 实际处理状态替代凭据输入，等待时仍然允许关闭并保留后台操作。 */}
        {command ? (
          <Descriptions
            column={1}
            items={[
              {
                key: 'state',
                label: '更换状态',
                children: result.data ? (
                  <DatasourceStatus value={result.data.state} />
                ) : (
                  (result.error?.message ?? <LoadingStatus>正在读取回执</LoadingStatus>)
                ),
              },
              ...(result.data?.blockingTaskId
                ? [{ key: 'task', label: '原采集任务', children: result.data.blockingTaskId }]
                : []),
              ...(result.data?.message
                ? [{ key: 'message', label: '说明', children: result.data.message }]
                : []),
            ]}
          />
        ) : (
          <Form
            form={form}
            layout="vertical"
            initialValues={{ account: source.configuration.account }}
            onValuesChange={() => setCheck(null)}
          >
            <Form.Item name="account" label="数据库只读账号" rules={[{ required: true }]}>
              <Input maxLength={160} />
            </Form.Item>
            <Form.Item name="password" label="数据库密码" rules={[{ required: true }]}>
              <Input.Password autoComplete="new-password" maxLength={1024} />
            </Form.Item>
            {check && !check.canEnable && (
              <Alert
                type="error"
                showIcon
                message={
                  check.items.find((item) => item.status === 'FAIL')?.message ??
                  '测试未达到更换条件'
                }
              />
            )}
            {check?.canEnable && <Alert type="success" showIcon message="新凭据测试通过" />}
          </Form>
        )}
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}
