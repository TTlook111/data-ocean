import { Alert, Button, Modal, Pagination, Select } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api, mutation, queryString } from '../../shared/api';
import { displayTime } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page } from '../iam/types';
import { GovernanceSourceDrawer, type SourcePeek } from './GovernanceSources';
import type { ColumnRow, GovernanceRow, Impact, Target } from './types';

/**
 * 工作台可读的未定位资料变化。
 */
type NoteChange = {
  /**
   * 稳定事项编号。
   */
  id: string;
  /**
   * 确认关联时所用的并发版本。
   */
  version: number;
  /**
   * 原资料变化时间。
   */
  occurredAt: string;
  /**
   * 仅有原文权限才返回资料编号和修订。
   */
  origin?: (NonNullable<Impact['origin']> & { noteTitle?: string }) | null;
};

/**
 * 移交编辑器时保留稳定目标及当前显示文本。
 */
type MappingTarget = {
  /**
   * 已确认同源的表说明或字段编号。
   */
  target: Target;
  /**
   * 当前可读的限定名称。
   */
  name: string;
};

/**
 * 在当前表列表显示需要定位的资料，只向获准人员提供原文与关联动作。
 */
export function GovernanceNoteMapping({
  source,
  open,
}: {
  source: string;
  open: (target: Target) => void;
}) {
  const session = useSession();
  const cache = useQueryClient();
  const [page, setPage] = useState(1);
  const [current, setCurrent] = useState<NoteChange>();
  const [peek, setPeek] = useState<SourcePeek>();
  const base = '/governance/sources/' + source;
  const path = base + '/unmapped-note-changes';
  const changes = useQuery({
    queryKey: ['governance', source, 'unmapped', page],
    queryFn: () => api<Page<NoteChange>>(path + '?' + queryString({ pageNum: page, pageSize: 5 })),
  });
  const canMap = allowed(session, 'governance.edit') && allowed(session, 'business-notes.view');
  if (changes.error) {
    // 附属资料查询失败时独立恢复，主对象表仍可办理。
    return (
      <Alert
        type="error"
        title="待定位资料读取失败"
        action={
          <Button size="small" onClick={() => void changes.refetch()}>
            重试
          </Button>
        }
      />
    );
  }
  if (!changes.data?.items.length && page === 1) return null;
  return (
    <section className="panel gov-unmapped" aria-label="待定位资料">
      {/* 摘要在现有表列表前就地呈现，避免独立工作队列。 */}
      <h2>
        待定位资料 <small>{changes.data?.total ?? 0}</small>
      </h2>
      <ul className="gov-batch-results">
        {changes.data?.items.map((change) => {
          // 资料撤下时仍可以回看实际生效过的历史原文。
          const origin = change.origin;
          const revision = origin?.afterRevision ?? origin?.beforeRevision;
          return (
            <li key={change.id}>
              <div>
                <strong>{origin?.noteTitle ?? '资料变化'}</strong>
                <small>
                  {displayTime(change.occurredAt)}
                  {revision ? ' · 第 ' + revision + ' 版' : ''}
                </small>
              </div>
              {origin?.noteId && revision && (
                <Button
                  type="link"
                  onClick={() => setPeek({ kind: 'note', id: origin.noteId!, revision })}
                >
                  查看原文
                </Button>
              )}
              {canMap && origin && (
                <Button size="small" onClick={() => setCurrent(change)}>
                  关联条目
                </Button>
              )}
            </li>
          );
        })}
      </ul>
      {Boolean(page > 1 || (changes.data?.total ?? 0) > 5) && (
        <Pagination
          size="small"
          current={page}
          pageSize={5}
          total={changes.data?.total ?? 0}
          showSizeChanger={false}
          onChange={setPage}
        />
      )}
      {peek && (
        <GovernanceSourceDrawer source={source} peek={peek} close={() => setPeek(undefined)} />
      )}
      {current && (
        <MappingDialog
          key={current.id}
          base={base}
          change={current}
          close={() => setCurrent(undefined)}
          saved={(target) => {
            // 关联更新工作台和详情，从第一项实际复核继续办理。
            setCurrent(undefined);
            void cache.invalidateQueries({ queryKey: ['governance', source] });
            open(target);
          }}
        />
      )}
    </section>
  );
}

/**
 * 以对象加字段的方式添加同源目标，一次提交全部明确关联。
 */
function MappingDialog({
  base,
  change,
  close,
  saved,
}: {
  base: string;
  change: NoteChange;
  close: () => void;
  saved: (target: Target) => void;
}) {
  useReturnFocus();
  const [tableTerm, setTableTerm] = useState('');
  const [fieldTerm, setFieldTerm] = useState('');
  const [table, setTable] = useState<{ value: string; label: string }>();
  const [pending, setPending] = useState<{ value: string; label: string }[]>([]);
  const [targets, setTargets] = useState<MappingTarget[]>([]);
  const [command, setCommand] = useState(() => crypto.randomUUID());
  const tables = useQuery({
    queryKey: ['governance', base, 'mapping-objects', tableTerm],
    queryFn: () =>
      api<{ page: Page<GovernanceRow> }>(
        base + '/objects?' + queryString({ keyword: tableTerm, pageSize: 30 }),
      ),
  });
  const fields = useQuery({
    queryKey: ['governance', base, 'mapping-columns', table?.value, fieldTerm],
    enabled: Boolean(table),
    queryFn: () =>
      api<Page<ColumnRow>>(
        base +
          '/objects/' +
          table?.value +
          '/columns?' +
          queryString({ keyword: fieldTerm, pageSize: 30 }),
      ),
  });
  const save = useMutation({
    mutationFn: () =>
      mutation(
        base + '/unmapped-note-changes/' + change.id + '/targets',
        'POST',
        { expectedVersion: change.version, targets: targets.map(({ target }) => target) },
        'governance.edit',
        command,
      ),
    onSuccess: () => saved(targets[0].target),
  });

  /**
   * 把当前选择加入确认清单，已选目标自然去重。
   */
  function add() {
    if (!table) return;
    const merged = new Map(targets.map((item) => [item.target.kind + item.target.targetId, item]));
    for (const field of pending) {
      // 表说明和字段共享同一个显示清单，实际目标仍使用独立类型。
      const isTable = field.value === 'TABLE';
      const target: Target = {
        objectId: table.value,
        kind: isTable ? 'TABLE' : 'COLUMN',
        targetId: isTable ? table.value : field.value,
      };
      merged.set(target.kind + target.targetId, {
        target,
        name: table.label + ' / ' + field.label,
      });
    }
    setTargets([...merged.values()]);
    setPending([]);
    setCommand(crypto.randomUUID());
  }

  return (
    <Modal
      open
      centered
      className="app-dialog"
      width={680}
      title="关联受影响条目"
      onCancel={close}
      okText="确认关联"
      okButtonProps={{ disabled: targets.length === 0 || targets.length > 100 }}
      confirmLoading={save.isPending}
      onOk={() => save.mutate()}
    >
      {/* 保留已选清单，再查找同一数据源下的其他表和字段。 */}
      <div className="gov-batch-selection">
        <strong>{change.origin?.noteTitle}</strong>
        <label htmlFor="gov-map-table">治理对象</label>
        <Select
          id="gov-map-table"
          aria-label="治理对象"
          labelInValue
          value={table}
          placeholder="搜索同源表或视图"
          showSearch={{ filterOption: false, onSearch: setTableTerm }}
          loading={tables.isFetching}
          onChange={(value) => {
            // 未加入清单的字段只属于当前表。
            setTable(value);
            setPending([]);
            setFieldTerm('');
          }}
          options={tables.data?.page.items.flatMap((row) =>
            row.objectId && row.availability === 'AVAILABLE'
              ? [{ value: row.objectId, label: row.qualifiedName }]
              : [],
          )}
        />
        <label htmlFor="gov-map-fields">表说明或字段</label>
        {/* 操作放在下拉区域右侧，打开选项时仍能直接加入所选条目。 */}
        <div className="gov-map-field-actions">
          <Select
            id="gov-map-fields"
            aria-label="表说明或字段"
            mode="multiple"
            labelInValue
            value={pending}
            disabled={!table}
            placeholder="选择受影响条目"
            showSearch={{ filterOption: false, onSearch: setFieldTerm }}
            loading={fields.isFetching}
            onChange={setPending}
            options={[
              { value: 'TABLE', label: '表说明' },
              ...(fields.data?.items.map((field) => ({
                value: field.target.targetId,
                label: field.name,
              })) ?? []),
            ]}
          />
          <Button disabled={!pending.length || save.isPending} onClick={add}>
            加入关联
          </Button>
        </div>
        {(tables.error || fields.error || save.error) && (
          <Alert type="error" title={(tables.error ?? fields.error ?? save.error)?.message} />
        )}
      </div>
      {targets.length > 0 && (
        <ul className="gov-batch-results" aria-label="已选关联条目">
          {targets.map((item) => (
            <li key={item.target.kind + item.target.targetId}>
              <div>{item.name}</div>
              <Button
                type="link"
                disabled={save.isPending}
                aria-label={'移除 ' + item.name}
                onClick={() => {
                  // 修改真实范围后以新命令提交。
                  setTargets(targets.filter((value) => value !== item));
                  setCommand(crypto.randomUUID());
                }}
              >
                移除
              </Button>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  );
}
