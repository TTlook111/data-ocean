import { Alert, App, Button, Checkbox, Descriptions, Form, Input, Modal, Select } from 'antd';
import { useEffect, useRef, useState, type ComponentRef } from 'react';
import { api, ApiError, mutation, queryString } from '../../shared/api';
import { applyFormError } from '../../shared/formErrors';
import { useReturnFocus } from '../../shared/useReturnFocus';
import { statusLabels, type IssueDetail } from './types';

/** 后台只开放当前服务端确认的工作动作。 */
export type ResolutionAction = 'result' | 'verify' | 'resolve' | 'explain';

/** 配置明确共用动作权限、行文和接口地址。 */
const actions = {
  result: {
    title: '提交处理结果',
    path: 'results',
    permission: 'feedback.process',
    flag: 'canSubmitResult',
  },
  verify: {
    title: '登记人工复验',
    path: 'verifications',
    permission: 'feedback.verify',
    flag: 'canVerify',
  },
  resolve: {
    title: '验证结案并回告',
    path: 'closures',
    permission: 'feedback.close',
    flag: 'canResolve',
  },
  explain: {
    title: '说明并结案',
    path: 'closures',
    permission: 'feedback.close',
    flag: 'canCloseOther',
  },
} as const;

/** 技术影响范围和回告原因使用与服务端相同的明确编码。 */
export const impactLabels: Record<string, string> = {
  DISPLAY: '展示与表述',
  CONNECTION: '连接与配置',
  QUERY_SAFETY: '查询与安全',
};
const closeReasons = {
  NOT_REPRODUCED: '未复现',
  DEFINITION_CONFIRMED: '按现有口径确认无误',
  CANCELLED: '取消',
  OTHER: '其他',
};

/** 表单仅发送选中动作所需字段，隐藏的回归内容不参与写入。 */
type Values = {
  impact?: string;
  summary?: string;
  checkMethod?: string;
  reference?: string;
  effectiveConfirmed?: boolean;
  regression?: string;
  regressionPassed?: boolean;
  decision?: string;
  testScope?: string;
  expected?: string;
  actual?: string;
  dataBasis?: string;
  reason?: string;
  conclusion?: string;
  basis?: string;
};

/** 结案预览只含受控模板和确切接收人。 */
type Preview = {
  version: number;
  recipientId: string;
  recipientName: string;
  template: string;
  message: string;
};

/** 通用多行文本保持字段校验、焦点和最大长度一致。 */
function TextField({
  name,
  label,
  max = 1000,
  optional = false,
}: {
  name: keyof Values;
  label: string;
  max?: number;
  optional?: boolean;
}) {
  return (
    <Form.Item
      name={name}
      label={label}
      rules={optional ? [] : [{ required: true, whitespace: true, message: `请填写${label}` }]}
    >
      <Input.TextArea rows={2} maxLength={max} />
    </Form.Item>
  );
}

/** 核对接收人和模板后才写结案，回包不明继续使用原命令。 */
export function FeedbackResolutionDialog({
  kind,
  detail,
  refresh,
  close,
}: {
  kind: ResolutionAction;
  detail: IssueDetail;
  refresh: () => Promise<IssueDetail | undefined>;
  close: () => void;
}) {
  useReturnFocus();
  const [form] = Form.useForm<Values>();
  const { message } = App.useApp();
  const [version, setVersion] = useState(detail.version);
  const [preview, setPreview] = useState<{ notice: Preview; values: Values }>();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [stale, setStale] = useState(false);
  const [uncertain, setUncertain] = useState(false);
  const inFlight = useRef(false);
  const attempt = useRef<{ key: string; body: object } | undefined>(undefined);
  const confirm = useRef<ComponentRef<typeof Button>>(null);
  const action = actions[kind];
  const enabled = Boolean(detail.resolution?.[action.flag]);
  const closing = kind === 'resolve' || kind === 'explain';
  const outcome = kind === 'resolve' ? 'RESOLVED' : 'CLOSED_OTHER';
  const path = `/feedback/issues/${detail.issue.id}`;
  const impact = Form.useWatch('impact', form);

  useEffect(() => {
    // 进入预览时，把键盘流程移到本次确认。
    if (preview) confirm.current?.focus({ preventScroll: true });
  }, [preview]);

  /** 验证输入和状态，必要时先获取服务端纯预览。 */
  async function submit(values: Values) {
    if (inFlight.current || stale || !enabled) return;
    inFlight.current = true;
    setBusy(true);
    setError(null);
    let writing = false;
    try {
      if (closing && !preview && !uncertain) {
        const notice = await api<Preview>(
          path + '/closure-preview?' + queryString({ outcome, reason: values.reason }),
        );
        if (notice.version !== version) {
          setStale(true);
          return;
        }
        setPreview({ notice, values });
        return;
      }
      const body = preview
        ? {
            ...preview.values,
            outcome,
            expectedVersion: version,
            recipientId: preview.notice.recipientId,
            confirmedTemplate: preview.notice.template,
          }
        : {
            ...values,
            expectedVersion: version,
            ...(kind === 'result' && impact !== 'QUERY_SAFETY'
              ? { regression: undefined, regressionPassed: undefined }
              : {}),
          };
      const request =
        uncertain && attempt.current ? attempt.current : { key: crypto.randomUUID(), body };
      attempt.current = request;
      writing = true;
      await mutation(
        path + '/' + action.path,
        'POST',
        request.body,
        action.permission,
        request.key,
      );
      await refresh();
      message.success(
        closing ? '已结案，回告已接收' : kind === 'verify' ? '已登记复验' : '已提交处理结果',
      );
      close();
    } catch (failure) {
      if (failure instanceof ApiError) {
        setPreview(undefined);
        setUncertain(false);
        if (failure.code === 409001) setStale(true);
        else setError(applyFormError(form, failure));
        if (failure.code === 403003) void refresh();
      } else {
        setUncertain(writing);
        setError(failure instanceof Error ? failure.message : '操作暂时无法完成');
      }
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  }

  /** 另一个操作者已推进版本时，重读后由用户保留草稿继续办理。 */
  async function reload() {
    setBusy(true);
    setError(null);
    try {
      const current = await refresh();
      if (current) {
        setVersion(current.version);
        setStale(false);
      } else setError('暂时无法读取当前事项');
    } finally {
      setBusy(false);
    }
  }

  /** 退回确认前的输入或退出当前表单。 */
  function cancel() {
    if (busy) return;
    if (preview && !uncertain) {
      setPreview(undefined);
      requestAnimationFrame(() => form.scrollToField('conclusion', { focus: true }));
    } else close();
  }

  return (
    <Modal
      centered
      open
      width={640}
      className="operation-dialog"
      mask={{ closable: false }}
      closable={!busy}
      title={preview ? '核对结案与回告' : action.title}
      onCancel={cancel}
      footer={[
        <Button key="cancel" disabled={busy} onClick={cancel}>
          {preview && !uncertain ? '返回修改' : '取消'}
        </Button>,
        <Button
          key="confirm"
          type="primary"
          ref={confirm}
          loading={busy}
          disabled={stale || !enabled}
          onClick={() => {
            if (uncertain || preview) void submit(preview?.values ?? {});
            else form.submit();
          }}
        >
          {uncertain
            ? '核对并重试'
            : preview
              ? '结案并确认回告'
              : closing
                ? '核对结案与回告'
                : action.title}
        </Button>,
      ]}
    >
      {/* 提交冲突与一般失败各自给可执行的处理入口。 */}
      {stale && (
        <Alert
          className="form-alert"
          type="warning"
          showIcon
          title="事项已更新"
          action={
            <Button size="small" loading={busy} onClick={() => void reload()}>
              读取最新版本
            </Button>
          }
        />
      )}
      {!enabled && (
        <Alert className="form-alert" type="warning" showIcon title="当前事项已无法执行此操作" />
      )}
      {error && <Alert className="form-alert" type="error" showIcon title={error} />}
      {preview && (
        <Descriptions
          size="small"
          column={1}
          items={[
            { key: 'state', label: '结案状态', children: statusLabels[outcome] },
            { key: 'recipient', label: '接收人', children: preview.notice.recipientName },
            { key: 'notice', label: '产品内回告', children: preview.notice.message },
            {
              key: 'basis',
              label: '结案依据',
              children: <span className="feedback-text">{preview.values.basis}</span>,
            },
          ]}
        />
      )}
      <Form<Values>
        form={form}
        layout="vertical"
        scrollToFirstError={{ focus: true }}
        hidden={Boolean(preview)}
        disabled={busy || uncertain || !enabled}
        onFinish={(values) => void submit(values)}
      >
        {/* 提交修复确认生效，查询安全影响需要对应真实回归。 */}
        {kind === 'result' && (
          <>
            <Form.Item
              name="impact"
              label="修复影响范围"
              rules={[{ required: true, message: '请选择影响范围' }]}
            >
              <Select
                virtual={false}
                options={Object.entries(impactLabels).map(([value, label]) => ({ value, label }))}
              />
            </Form.Item>
            <TextField name="summary" label="修正说明" />
            <TextField name="checkMethod" label="复查方式与结果" />
            <TextField name="reference" label="相关处理记录" optional max={500} />
            {impact === 'QUERY_SAFETY' && (
              <>
                <TextField name="regression" label="相关场景测试结果" />
                <Form.Item
                  name="regressionPassed"
                  valuePropName="checked"
                  rules={[
                    {
                      validator: async (_, value) => {
                        if (!value) throw new Error('请确认相关场景测试已通过');
                      },
                    },
                  ]}
                >
                  <Checkbox>已核对原问题和受限场景，测试均通过</Checkbox>
                </Form.Item>
              </>
            )}
            <Form.Item
              name="effectiveConfirmed"
              valuePropName="checked"
              rules={[
                {
                  validator: async (_, value) => {
                    if (!value) throw new Error('请确认修复已生效');
                  },
                },
              ]}
            >
              <Checkbox>已确认本次修复生效</Checkbox>
            </Form.Item>
          </>
        )}
        {/* 复验是本人实际登记，使用该登录身份的真实来源权限。 */}
        {kind === 'verify' && (
          <>
            <Form.Item
              name="decision"
              label="复验结论"
              rules={[{ required: true, message: '请选择复验结论' }]}
            >
              <Select
                virtual={false}
                options={[
                  { value: 'PASSED', label: '通过' },
                  { value: 'FAILED', label: '未通过' },
                ]}
              />
            </Form.Item>
            <TextField name="testScope" label="复验场景" />
            <TextField name="expected" label="期望结果" max={500} />
            <TextField name="actual" label="实际结果" />
            <TextField name="dataBasis" label="数据与复验依据" />
          </>
        )}
        {/* 说明结案需要明确原因，所有自由文本只在后台保存。 */}
        {closing && (
          <>
            {kind === 'explain' && (
              <Form.Item
                name="reason"
                label="结案原因"
                rules={[{ required: true, message: '请选择结案原因' }]}
              >
                <Select
                  virtual={false}
                  options={Object.entries(closeReasons).map(([value, label]) => ({ value, label }))}
                />
              </Form.Item>
            )}
            <TextField name="conclusion" label="结案结论" />
            <TextField name="basis" label="结案依据" />
          </>
        )}
      </Form>
    </Modal>
  );
}
