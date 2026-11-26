import { Descriptions, Drawer, Form, Modal, Select, Space } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, queryString } from '../../shared/api';
import { ReadState } from '../../shared/Records';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { StructureChange } from '../datasource/types';
import type { Page } from '../iam/types';
import type { BusinessNoteExcerpt, Definition, NoteReference } from './types';

/**
 * 保留可回看的固定资料版本或已核实来源。
 */
export type SourcePeek =
  | { kind: 'note'; id: string; revision: number; attachmentId?: string | null; section?: string }
  | { kind: 'change'; id: string };

/**
 * 将实际保存的附件位置转成可阅读的行号、页码或单元格。
 */
export function attachmentLocation(section?: string) {
  const [kind, location, cell] = (section ?? '').split(':');
  switch (kind) {
    case 'PDF_PAGE':
      return '第 ' + location + ' 页';
    case 'DOCX_PARAGRAPH':
      return '第 ' + location + ' 段';
    case 'TEXT_LINE':
      return '第 ' + location + ' 行';
    case 'CSV_LINES':
      return '行 ' + location;
    case 'XLSX_CELL':
      return '工作表 ' + location + ' / ' + cell;
    default:
      return undefined;
  }
}

/**
 * 原样展示 B 批的可披露技术值，空值保持明确。
 */
function sourceValue(value: unknown) {
  if (value === null || value === undefined) return '—';
  return typeof value === 'string' ? value : JSON.stringify(value);
}

/**
 * 同一条目内回看确切来源，关闭后把焦点还给原入口。
 */
export function GovernanceSourceDrawer({
  peek,
  source,
  close,
}: {
  peek: SourcePeek;
  source: string;
  close: () => void;
}) {
  useReturnFocus();
  const note = useQuery({
    queryKey: ['governance-source-note', peek.id, peek.kind === 'note' ? peek.revision : null],
    enabled: peek.kind === 'note',
    queryFn: () =>
      api<BusinessNoteExcerpt>(
        '/business-notes/' +
          peek.id +
          '?' +
          queryString({ revision: peek.kind === 'note' ? peek.revision : null }),
      ),
  });
  const change = useQuery({
    queryKey: ['governance-source-fact', source, peek.id],
    enabled: peek.kind === 'change',
    queryFn: () => api<StructureChange>('/datasources/' + source + '/changes/' + peek.id),
  });
  const query = peek.kind === 'note' ? note : change;

  return (
    <Drawer
      open
      width={640}
      title={peek.kind === 'note' ? '业务说明原文' : '结构变化与核实'}
      onClose={close}
      rootClassName="record-drawer"
    >
      {/* 原件详情继续使用 B 批原文权限。 */}
      <ReadState error={query.error} loading={query.isPending} retry={() => void query.refetch()}>
        {peek.kind === 'note' && note.data && (
          <div className="gov-source-body">
            <h3>{note.data.content.title}</h3>
            <p>第 {note.data.content.revision} 版</p>
            <p className="gov-pre-line">{note.data.content.body}</p>
            {note.data.content.attachments.map((file) => (
              <div key={file.id}>
                <a
                  href={
                    '/api/v1/business-notes/' +
                    peek.id +
                    '/revisions/' +
                    peek.revision +
                    '/files/' +
                    file.id
                  }
                >
                  {file.name}
                </a>
                {file.id === peek.attachmentId && attachmentLocation(peek.section) && (
                  <span className="table-secondary">{attachmentLocation(peek.section)}</span>
                )}
              </div>
            ))}
          </div>
        )}
        {/* 结构事实直接沿用 B 批已登记的两侧证据。 */}
        {peek.kind === 'change' && change.data && (
          <Descriptions
            column={1}
            items={[
              { key: 'target', label: '来源对象', children: change.data.target },
              { key: 'before', label: '变化前', children: sourceValue(change.data.before) },
              { key: 'after', label: '变化后', children: sourceValue(change.data.after) },
              {
                key: 'review',
                label: '事实核实',
                children: change.data.factState === 'CONFIRMED' ? '已确认' : '待核实',
              },
              ...(change.data.latestReview
                ? [
                    {
                      key: 'evidence',
                      label: '核实依据',
                      children: change.data.latestReview.evidenceText,
                    },
                  ]
                : []),
            ]}
          />
        )}
      </ReadState>
    </Drawer>
  );
}

/**
 * 为当前字段选择真实资料和固定的正文段落或原件。
 */
export function GovernanceNotePicker({
  source,
  fields,
  apply,
  close,
}: {
  source: string;
  fields: { key: keyof Definition; label: string }[];
  apply: (ref: NoteReference, title: string, revision: number) => void;
  close: () => void;
}) {
  useReturnFocus();
  const [term, setTerm] = useState('');
  const [selected, setSelected] = useState<string>();
  const [position, setPosition] = useState<string>();
  const [field, setField] = useState(fields[0]?.key);
  const list = useQuery({
    queryKey: ['governance-note-options', source, term],
    queryFn: () =>
      api<Page<{ id: string; title: string }>>(
        '/business-notes?' +
          queryString({ sourceId: source, state: 'ACTIVE', keyword: term, pageSize: 100 }),
      ),
  });
  const detail = useQuery({
    queryKey: ['governance-note-option', selected],
    enabled: Boolean(selected),
    queryFn: () => api<BusinessNoteExcerpt>('/business-notes/' + selected),
  });
  const body = detail.data?.content;
  const choices = body
    ? [
        ...Array.from(body.body.matchAll(/[^\r\n]+/g), (match) => ({
          value: 'text:' + match.index,
          label: match[0],
          start: match.index,
          end: match.index + match[0].length,
        })).filter((line) => line.label.trim()),
        ...body.attachments.map((file) => ({
          value: 'file:' + file.id,
          label: file.name,
          fileId: file.id,
        })),
      ]
    : [];

  /**
   * 保留的是确切修订和位置，实际绑定由工作台下一次保存提交。
   */
  function select() {
    const choice = choices.find(({ value }) => value === position);
    if (!choice || !selected || !body || !field) {
      // 无实际来源选择时保持弹窗。
      return;
    }
    const ref: NoteReference = {
      contentPath: field,
      noteId: selected,
      revisionId: body.revisionId,
      ...('fileId' in choice
        ? { attachmentId: choice.fileId, location: { section: 'ATTACHMENT' } }
        : { location: { section: 'BODY', start: choice.start, end: choice.end } }),
    };
    apply(ref, body.title, body.revision);
  }

  return (
    <Modal
      className="app-dialog"
      open
      centered
      width={680}
      title="引用业务说明"
      okText="添加引用"
      cancelText="取消"
      onCancel={close}
      onOk={select}
      okButtonProps={{ disabled: !position || !field }}
    >
      {/* 选择资料和实际出处分别受 B 批的当前权限和归属保护。 */}
      <Form layout="vertical">
        <Form.Item label="支持的业务内容">
          <Select
            aria-label="支持的业务内容"
            value={field}
            onChange={setField}
            options={fields.map(({ key, label }) => ({ value: key, label }))}
          />
        </Form.Item>
        <Form.Item label="企业业务说明">
          <Select
            aria-label="企业业务说明"
            showSearch
            filterOption={false}
            value={selected}
            loading={list.isPending}
            onSearch={setTerm}
            onChange={(value) => {
              // 切换资料时原引用位置失效。
              setSelected(value);
              setPosition(undefined);
            }}
            options={list.data?.items.map((note) => ({ value: note.id, label: note.title }))}
          />
        </Form.Item>
        <Form.Item label="引用位置">
          <Select
            aria-label="引用位置"
            value={position}
            onChange={setPosition}
            loading={Boolean(selected) && detail.isPending}
            options={choices}
            disabled={!body}
          />
        </Form.Item>
        {(list.error || detail.error) && (
          <Space role="alert">{(list.error || detail.error)?.message}</Space>
        )}
      </Form>
    </Modal>
  );
}
