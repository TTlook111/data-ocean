import {
  Alert,
  App,
  Button,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Tag,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import type { UploadFile } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useEffectEvent, useRef, useState } from 'react';
import { useBlocker, useNavigate } from 'react-router';
import { AccessDetailDrawer, AccessGrid, AccessPerson } from '../access/AccessControls';
import type { PersonOption } from '../access/types';
import { api, mutation } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { ReviewDialog } from '../../shared/ReviewDialog';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import type { Preview } from '../iam/types';
import { SourcePicker, SourceUnavailable, useSourceSelection } from './CollectionPage';
import { DatasourceStatus } from './Presentation';

/**
 * 资料清单使用经授权的稳定归属与当前修订。
 */
interface NoteRow {
  /** 资料编号。 */
  id: string;
  /** 适用的数据源。 */
  sourceId: string;
  /** 资料所绑定的数据源名称。 */
  sourceName: string;
  /** 当前资料标题。 */
  title: string;
  /** 原提交人的名称和登录名。 */
  author: PersonOption;
  /** 当前资料状态。 */
  state: string;
  /** 状态与内容的聚合修改版本。 */
  version: number;
  /** 当前正文修订号。 */
  revision: number;
  /** 当前附件数。 */
  attachmentCount: number;
  /** 所有修订上的真实引用数。 */
  referenceCount: number;
  /** 最后变更时间。 */
  updatedAt: string;
}

/**
 * 原件下载只使用稳定编号和当前指定修订。
 */
interface Attachment {
  /** 附件编号。 */
  id: string;
  /** 原文件名。 */
  name: string;
  /** 文件字节数。 */
  byteSize: number;
}

/**
 * 详情固定读取所选历史版本。
 */
interface NoteDetail {
  /** 当前资料事实。 */
  note: NoteRow;
  /** 某一确切修订的说明和原文件。 */
  content: {
    /** 历史内容修订号。 */
    revision: number;
    /** 本版内容标题。 */
    title: string;
    /** 本版说明。 */
    body: string;
    /** 保存时间。 */
    createdAt: string;
    /** 本版附件，与当前最新修订独立。 */
    attachments: Attachment[];
  };
  /** 可回看的历史修订。 */
  revisions: number[];
  /** 最近一次撤下原因。 */
  withdrawalReason?: string;
}

/**
 * 资料状态动作类型及其原有能力。
 */
const noteActions = {
  WITHDRAW: { label: '撤下资料', permission: 'business-notes.withdraw' },
  RESTORE: { label: '恢复资料', permission: 'business-notes.restore' },
  DELETE: { label: '删除资料', permission: 'business-notes.delete' },
} as const;

/**
 * 状态动作名称源自授权动作清单。
 */
type NoteAction = keyof typeof noteActions;

/**
 * 以源、标题、提交人和当前状态查询资料。
 */
export function BusinessNotesPage() {
  const session = useSession();
  const navigate = useNavigate();
  const { params, setParams, sources, selected } = useSourceSelection('BUSINESS_NOTES');
  const sourceFilters = useRef(new Map<string, string>());
  const [stateAction, setStateAction] = useState<{ row: NoteRow; action: NoteAction }>();
  const detailId = params.get('detail');
  const editing = params.get('edit');
  const note = useQuery({
    queryKey: ['business-note', 'edit', editing],
    queryFn: () => api<NoteDetail>(`/business-notes/${editing}`),
    enabled: Boolean(editing && editing !== 'new'),
  });

  /**
   * 列表、详情和编辑保留同样的筛选及分页。
   */
  function open(field: 'detail' | 'edit', id?: string) {
    const next = new URLSearchParams(params);
    if (id) next.set(field, id);
    else next.delete(field);
    setParams(next, {
      replace: true,
      state: field === 'edit' && id ? { noteSource: selected?.name } : undefined,
    });
  }

  /**
   * 切源恢复该数据源的筛选与页码，资料详情跟随原来源。
   */
  function choose(id: string) {
    if (selected) {
      // 详情与编辑是临时上下文，切源时仅保存查询。
      const previous = new URLSearchParams(params);
      previous.delete('detail');
      previous.delete('edit');
      sourceFilters.current.set(selected.id, previous.toString());
    }
    const next = new URLSearchParams(sourceFilters.current.get(id));
    next.set('source', id);
    setParams(next, { replace: true });
  }

  if (editing) {
    // 新建和编辑使用独立工作区，返回原资料列表。
    const hasPermission = allowed(
      session,
      editing === 'new' ? 'business-notes.submit' : 'business-notes.replace',
    );
    if (!hasPermission) return <Alert type="error" title="当前没有编辑资料的权限" />;
    if (editing === 'new') {
      // 新资料固定归属从列表页选中的当前数据源。
      return (
        <ReadState
          loading={sources.isPending}
          error={sources.error}
          retry={() => void sources.refetch()}
        >
          {selected?.lifecycle === 'ENABLED' ? (
            <NoteEditor
              sourceId={selected.id}
              sourceName={selected.name}
              close={() => open('edit')}
            />
          ) : (
            <Button onClick={() => open('edit')}>返回选择已启用的数据源</Button>
          )}
        </ReadState>
      );
    }
    return (
      <ReadState loading={note.isPending} error={note.error} retry={() => void note.refetch()}>
        {note.data && (
          <NoteEditor key={note.data.note.id} detail={note.data} close={() => open('edit')} />
        )}
      </ReadState>
    );
  }

  return (
    <section className="record-page notes-page">
      <div className="panel ds-toolbar notes-source-context">
        <div className="notes-source-control">
          <span>当前数据源</span>
          <SourcePicker sources={sources.data} selected={selected} choose={choose} />
        </div>
        {selected && (
          <Space size={12}>
            <DatasourceStatus value={selected.lifecycle} />
            {allowed(session, 'sources.view') && (
              <Button
                onClick={() => navigate('/datasources?detail=' + encodeURIComponent(selected.id))}
              >
                数据源详情
              </Button>
            )}
          </Space>
        )}
      </div>
      {sources.isPending || sources.error ? (
        <ReadState
          loading={sources.isPending}
          error={sources.error}
          retry={() => void sources.refetch()}
        >
          <SourceUnavailable />
        </ReadState>
      ) : selected ? (
        <AccessGrid<NoteRow>
          key={selected.id}
          namespace=""
          path="/business-notes"
          fixed={{ sourceId: selected.id }}
          rowKey="id"
          empty="暂无企业业务说明"
          filters={[
            { name: 'keyword', placeholder: '资料标题' },
            { name: 'author', placeholder: '提交人', ariaLabel: '提交人姓名或登录名' },
            {
              name: 'state',
              placeholder: '全部资料状态',
              options: [
                { value: 'ACTIVE', label: '有效' },
                { value: 'WITHDRAWN', label: '已撤下' },
              ],
            },
          ]}
          action={
            selected.lifecycle === 'ENABLED' &&
            allowed(session, 'business-notes.submit') && (
              <Button type="primary" onClick={() => open('edit', 'new')}>
                新增业务说明
              </Button>
            )
          }
          columns={[
            {
              title: '资料标题',
              width: 235,
              render: (_, row) => (
                <div className="access-cell-summary">
                  <Button
                    type="link"
                    className="record-link"
                    title={row.title}
                    onClick={() => open('detail', row.id)}
                  >
                    {row.title}
                  </Button>
                  <small>
                    第 {row.revision} 版
                    {row.attachmentCount ? ` · ${row.attachmentCount} 个附件` : ''}
                  </small>
                </div>
              ),
            },
            {
              title: '提交人',
              width: 130,
              render: (_, row) => <AccessPerson person={row.author} />,
            },
            { title: '更新时间', width: 145, render: (_, row) => displayTime(row.updatedAt) },
            {
              title: '资料状态',
              width: 96,
              render: (_, row) => (
                <Tag color={row.state === 'ACTIVE' ? 'success' : 'default'}>
                  {row.state === 'ACTIVE' ? '有效' : '已撤下'}
                </Tag>
              ),
            },
            {
              title: '操作',
              className: 'record-actions-cell',
              width: 190,
              fixed: 'right',
              render: (_, row) =>
                selected.lifecycle === 'RETIRED' ? (
                  '—'
                ) : (
                  <Space size={8} className="row-actions">
                    {row.state === 'ACTIVE' && allowed(session, 'business-notes.replace') && (
                      <Button type="link" onClick={() => open('edit', row.id)}>
                        编辑
                      </Button>
                    )}
                    {row.state === 'ACTIVE' && allowed(session, 'business-notes.withdraw') && (
                      <Button
                        type="link"
                        onClick={() => setStateAction({ row, action: 'WITHDRAW' })}
                      >
                        撤下
                      </Button>
                    )}
                    {row.state === 'WITHDRAWN' && allowed(session, 'business-notes.restore') && (
                      <Button
                        type="link"
                        onClick={() => setStateAction({ row, action: 'RESTORE' })}
                      >
                        恢复
                      </Button>
                    )}
                    {allowed(session, 'business-notes.delete') &&
                      (row.referenceCount > 0 ? (
                        <Tooltip
                          title={'已被引用 ' + row.referenceCount + ' 次，无法删除'}
                          trigger={['hover', 'focus']}
                        >
                          <span
                            tabIndex={0}
                            aria-label={'删除不可用，已被引用 ' + row.referenceCount + ' 次'}
                          >
                            <Button type="link" danger disabled>
                              删除
                            </Button>
                          </span>
                        </Tooltip>
                      ) : (
                        <Button
                          type="link"
                          danger
                          onClick={() => setStateAction({ row, action: 'DELETE' })}
                        >
                          删除
                        </Button>
                      ))}
                  </Space>
                ),
            },
          ]}
        />
      ) : (
        <SourceUnavailable />
      )}
      {detailId && <NoteDrawer key={detailId} id={detailId} close={() => open('detail')} />}
      {stateAction && <NoteStateDialog {...stateAction} close={() => setStateAction(undefined)} />}
    </section>
  );
}

/**
 * 使用原始修订核对资料、说明和可下载附件。
 */
function NoteDrawer({ id, close }: { id: string; close: () => void }) {
  const [revision, setRevision] = useState<number>();
  const read = useQuery({
    queryKey: ['business-note', id, revision],
    queryFn: () =>
      api<NoteDetail>(`/business-notes/${id}${revision ? `?revision=${revision}` : ''}`),
  });
  const row = read.data;
  return (
    <AccessDetailDrawer title={row?.content.title ?? '业务说明'} size={640} close={close}>
      <ReadState loading={read.isPending} error={read.error} retry={() => void read.refetch()}>
        {row && (
          <Space orientation="vertical" className="access-notes-detail">
            <Space wrap>
              <Typography.Text type="secondary">{row.note.sourceName}</Typography.Text>
              <Select
                aria-label="资料修订"
                value={row.content.revision}
                onChange={setRevision}
                options={row.revisions.map((rev) => ({
                  value: rev,
                  label: `第 ${rev} 版${rev === row.note.revision ? ' · 当前' : ''}`,
                }))}
              />
            </Space>
            {row.note.state === 'WITHDRAWN' && (
              <Alert type="warning" title="资料已撤下" description={row.withdrawalReason} />
            )}
            {row.content.body && <p className="detail-text">{row.content.body}</p>}
            {row.content.attachments.length > 0 && (
              <div className="access-stack">
                <Typography.Text strong>附件</Typography.Text>
                {row.content.attachments.map((file) => (
                  <a
                    key={file.id}
                    href={`/api/v1/business-notes/${id}/revisions/${row.content.revision}/files/${file.id}`}
                  >
                    {file.name}
                  </a>
                ))}
              </div>
            )}
            {row.note.referenceCount > 0 && (
              <Typography.Text>引用 {row.note.referenceCount} 次</Typography.Text>
            )}
          </Space>
        )}
      </ReadState>
    </AccessDetailDrawer>
  );
}

/**
 * 企业说明直接保存当前正文和完整的附件集合。
 */
function NoteEditor({
  detail,
  sourceId,
  sourceName,
  close,
}: {
  detail?: NoteDetail;
  sourceId?: string;
  sourceName?: string;
  close: () => void;
}) {
  const [form] = Form.useForm<{ title: string; body?: string }>();
  const { message, modal } = App.useApp();
  const cache = useQueryClient();
  const [files, setFiles] = useState<UploadFile[]>([]);
  const [retained, setRetained] = useState(detail?.content.attachments ?? []);
  const [error, setError] = useState<string>();
  const [busy, setBusy] = useState(false);
  const command = useRef<string | null>(null);
  const saved = useRef(false);
  const title = Form.useWatch('title', form) ?? detail?.content.title ?? '';
  const text = Form.useWatch('body', form) ?? detail?.content.body ?? '';
  const changed =
    title.trim() !== (detail?.content.title ?? '') ||
    text.trim() !== (detail?.content.body ?? '') ||
    files.length > 0 ||
    retained.length !== (detail?.content.attachments.length ?? 0);
  const blocker = useBlocker(() => changed && !saved.current);
  useEffect(() => {
    // 取消、菜单与历史导航保留同一个未保存确认。
    if (blocker.state !== 'blocked') return;
    if (busy) {
      // 已提交的原件上传继续等待明确结果。
      blocker.reset();
      void message.info('资料正在保存');
      return;
    }
    const dialog = modal.confirm({
      centered: true,
      title: '有未保存的修改',
      content: '离开会丢弃本次修改。',
      okText: '放弃修改并离开',
      cancelText: '继续编辑',
      focusable: { autoFocusButton: 'cancel' },
      onOk: () => blocker.proceed(),
      onCancel: () => blocker.reset(),
    });
    return () => dialog.destroy();
  }, [blocker, busy, message, modal]);

  /**
   * 用 multipart 提交当前修订，原文件通过独立部件传输。
   */
  async function submit(values: { title: string; body?: string }) {
    if (!values.body?.trim() && files.length === 0 && retained.length === 0) {
      // 正文与原件均为空时回到业务说明字段。
      setError('请填写业务说明或选择附件。');
      form.focusField('body');
      return;
    }
    const uploads = files.flatMap((file) => (file.originFileObj ? [file.originFileObj] : []));
    const body = new FormData();
    body.set(
      'metadata',
      new Blob(
        [
          JSON.stringify({
            ...values,
            sourceId: detail?.note.sourceId ?? sourceId,
            expectedVersion: detail?.note.version,
            keptAttachmentIds: retained.map((file) => file.id),
          }),
        ],
        { type: 'application/json' },
      ),
    );
    uploads.forEach((file) => body.append('files', file, file.name));
    command.current ??= crypto.randomUUID();
    setBusy(true);
    setError(undefined);
    try {
      // 正常返回及不明回包均由公共原命令机制核实。
      await mutation(
        detail ? `/business-notes/${detail.note.id}/revisions` : '/business-notes',
        'POST',
        body,
        detail ? 'business-notes.replace' : 'business-notes.submit',
        command.current,
      );
      await cache.invalidateQueries();
      saved.current = true;
      message.success('已保存业务说明');
      close();
    } catch (failure) {
      // 校验失败处保留表单及文件。
      setError(failure instanceof Error ? failure.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <section
      className="panel note-editor"
      aria-label={`业务说明 · ${detail?.note.sourceName ?? sourceName ?? ''}`}
    >
      <Form
        form={form}
        className="note-editor-form"
        layout="vertical"
        initialValues={{
          title: detail?.content.title,
          body: detail?.content.body,
        }}
        onValuesChange={() => {
          // 改变提案时为下一次提交重新分配命令。
          command.current = null;
        }}
        onFinish={(values) => void submit(values)}
      >
        <div className="note-editor-body">
          <div className="note-editor-inner">
            {error && <Alert type="error" title={error} showIcon />}
            <Form.Item name="title" label="资料标题" rules={[{ required: true, whitespace: true }]}>
              <Input maxLength={120} autoFocus />
            </Form.Item>
            <Form.Item name="body" label="业务说明">
              <Input.TextArea rows={12} maxLength={5000} showCount />
            </Form.Item>
            <Form.Item
              label="附件"
              extra="PDF、DOCX、XLSX、CSV、TXT、Markdown；单个文件不超过 20 MB"
            >
              <div className="access-stack">
                {retained.map((file) => (
                  <Space key={file.id}>
                    <span>{file.name}</span>
                    <Button
                      type="link"
                      onClick={() => {
                        // 只移除本次保留项，历史原件仍可回看。
                        setRetained((previous) => previous.filter((item) => item.id !== file.id));
                        command.current = null;
                      }}
                    >
                      移除
                    </Button>
                  </Space>
                ))}
                <Upload
                  fileList={files}
                  multiple
                  accept=".txt,.md,.csv,.pdf,.docx,.xlsx"
                  beforeUpload={(file) => {
                    // 让服务端复核格式和容量，选择阶段先反馈明显错误。
                    if (
                      file.size === 0 ||
                      file.size > 20 * 1024 * 1024 ||
                      !/\.(pdf|docx|xlsx|csv|txt|md)$/i.test(file.name)
                    ) {
                      message.error(
                        '请选择 20 MB 以内的 PDF、DOCX、XLSX、CSV、TXT 或 Markdown 文件',
                      );
                      return Upload.LIST_IGNORE;
                    }
                    return false;
                  }}
                  onChange={({ fileList }) => {
                    // 保留本次选择，不自动发出未核实的上传命令。
                    if (retained.length + fileList.length > 5) {
                      message.error('最多选择 5 个附件');
                    }
                    setFiles(fileList.slice(0, 5 - retained.length));
                    command.current = null;
                  }}
                >
                  <Button disabled={retained.length + files.length >= 5}>选择附件</Button>
                </Upload>
              </div>
            </Form.Item>
          </div>
        </div>
        <footer className="note-editor-footer">
          <div className="note-editor-inner access-footer">
            <Space>
              <Button disabled={busy} onClick={close}>
                取消
              </Button>
              <Button
                type="primary"
                htmlType="submit"
                loading={busy}
                disabled={Boolean(detail) && !changed}
              >
                保存
              </Button>
            </Space>
          </div>
        </footer>
      </Form>
    </section>
  );
}

/**
 * 撤下提供原因，恢复和永久删除只显示一次确认。
 */
function NoteStateDialog({
  row,
  action,
  close,
}: {
  row: NoteRow;
  action: NoteAction;
  close: () => void;
}) {
  const { message, modal } = App.useApp();
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [submission, setSubmission] = useState<ReviewSubmission>();
  const config = noteActions[action];

  /**
   * 状态预览读取确切引用情况及目标状态。
   */
  async function preview() {
    setBusy(true);
    const path = `/business-notes/${row.id}`;
    const body = { action, expectedVersion: row.version, reason: reason.trim() };
    try {
      // 由后端签发同一资料版本的核对签名。
      const result = await api<Preview>(`${path}/state-preview`, { method: 'POST', body });
      setSubmission({
        path: `${path}/state`,
        preview: result,
        method: 'POST',
        operation: config.permission,
        body,
        title: config.label,
        context: row.title,
        danger: action !== 'RESTORE',
        confirmText: '确认' + config.label,
        done: close,
      });
    } catch (error) {
      // 引用或版本冲突保持原列表。
      message.error((error as Error).message);
      if (action !== 'WITHDRAW') close();
    } finally {
      setBusy(false);
    }
  }

  /**
   * 恢复和删除进入面板后只显示一次有签名的明确确认。
   */
  const reviewImmediately = useEffectEvent(() => void preview());
  useEffect(() => {
    // 撤下需要等待填写理由；其他操作直接加载当前核对。
    if (action !== 'WITHDRAW') reviewImmediately();
  }, [action]);
  if (submission) {
    return (
      <ReviewDialog
        submission={submission}
        close={action === 'WITHDRAW' ? () => setSubmission(undefined) : close}
      />
    );
  }
  if (action !== 'WITHDRAW') return null;
  return (
    <FormNoteWithdraw
      title={config.label}
      row={row}
      reason={reason}
      setReason={setReason}
      busy={busy}
      close={() => {
        // 用户主动关闭时保留已写原因的放弃确认。
        if (reason.trim())
          void modal.confirm({
            title: '放弃尚未提交的撤下原因？',
            okText: '确认放弃',
            cancelText: '继续编辑',
            onOk: close,
          });
        else close();
      }}
      preview={() => void preview()}
    />
  );
}

/**
 * 撤下原因和核对按钮保持在同一个短表单中。
 */
function FormNoteWithdraw({
  title,
  row,
  reason,
  setReason,
  busy,
  close,
  preview,
}: {
  title: string;
  row: NoteRow;
  reason: string;
  setReason: (value: string) => void;
  busy: boolean;
  close: () => void;
  preview: () => void;
}) {
  return (
    <Modal
      open
      centered
      title={title}
      onCancel={close}
      onOk={preview}
      okText="核对影响"
      okButtonProps={{ disabled: !reason.trim() }}
      confirmLoading={busy}
    >
      <p>{row.title}</p>
      <Form layout="vertical">
        <Form.Item label="撤下原因" required>
          <Input.TextArea
            rows={3}
            value={reason}
            maxLength={500}
            onChange={(event) => setReason(event.target.value)}
          />
        </Form.Item>
      </Form>
    </Modal>
  );
}
