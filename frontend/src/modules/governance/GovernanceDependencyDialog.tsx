import { Alert, Button, Modal, Select, Space } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, queryString } from '../../shared/api';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { Page } from '../iam/types';
import type { ColumnRow, GovernanceRow, Target } from './types';

/**
 * 当前选择同时携带稳定列身份及方便审核的人类可读路径。
 */
export type DependencyField = {
  /**
   * 实际字段目标，所属对象用于服务端校验。
   */
  target: Target;
  /**
   * 表限定名与字段名。
   */
  label: string;
};

/**
 * 一条关系的完整连接键和它使用的补充字段。
 */
export type Dependencies = {
  /**
   * 顺序无关审核版本，关系的每一行必须两端齐全。
   */
  pairs: { left?: DependencyField; right?: DependencyField }[];
  /**
   * 指标计算、过滤及关联条件实际引用的补充字段。
   */
  fields: DependencyField[];
};

/**
 * 集中查看和修改同一候选的技术依赖，应用后仍停留在人工作业区。
 */
export function GovernanceDependencyDialog({
  source,
  relation,
  initial,
  close,
  apply,
}: {
  source: string;
  relation: boolean;
  initial: Dependencies;
  close: () => void;
  apply: (selection: Dependencies) => void;
}) {
  useReturnFocus();
  const [selection, setSelection] = useState(initial);
  const [next, setNext] = useState<DependencyField>();
  const invalid = relation
    ? !selection.pairs.length || selection.pairs.some(({ left, right }) => !left || !right)
    : !selection.fields.length;
  return (
    <Modal
      open
      centered
      className="app-dialog"
      width={760}
      title={relation ? '调整连接字段与依赖' : '调整指标依赖'}
      onCancel={close}
      onOk={() => apply(selection)}
      okText="应用至草稿"
      okButtonProps={{ disabled: invalid }}
    >
      {/* 每行是完整连接字段对，读取选择仅限同一数据源。 */}
      {relation && (
        <section className="gov-batch-selection" aria-label="连接字段">
          {selection.pairs.map((pair, index) => (
            <div className="gov-dependency-pair" key={index}>
              <ColumnPicker
                source={source}
                label={'左侧字段 ' + (index + 1)}
                value={pair.left}
                changed={(left) => {
                  // 只改变当前字段对的一端，另一端继续保留。
                  setSelection({
                    ...selection,
                    pairs: selection.pairs.map((item, at) =>
                      at === index ? { ...item, left } : item,
                    ),
                  });
                }}
              />
              <ColumnPicker
                source={source}
                label={'右侧字段 ' + (index + 1)}
                value={pair.right}
                changed={(right) => {
                  // 复合键的其他连接对保持原选择。
                  setSelection({
                    ...selection,
                    pairs: selection.pairs.map((item, at) =>
                      at === index ? { ...item, right } : item,
                    ),
                  });
                }}
              />
              <Button
                type="link"
                aria-label={'移除字段对 ' + (index + 1)}
                onClick={() =>
                  setSelection({
                    ...selection,
                    pairs: selection.pairs.filter((_, at) => at !== index),
                  })
                }
              >
                移除
              </Button>
            </div>
          ))}
          <Space>
            <Button
              disabled={selection.pairs.length >= 12}
              onClick={() => setSelection({ ...selection, pairs: [...selection.pairs, {}] })}
            >
              添加字段对
            </Button>
          </Space>
        </section>
      )}
      {/* 指标依赖或关系的非键字段按实际业务用途独立选择。 */}
      <div className="gov-batch-selection">
        <ColumnPicker
          source={source}
          label={relation ? '其他依赖字段' : '计算或筛选字段'}
          value={next}
          changed={setNext}
        />
        <Space>
          <Button
            disabled={!next || selection.fields.length >= 30}
            onClick={() => {
              // 依赖以稳定字段去重，显示顺序沿用户实际添加顺序。
              if (
                next &&
                !selection.fields.some(({ target }) => target.targetId === next.target.targetId)
              ) {
                setSelection({ ...selection, fields: [...selection.fields, next] });
              }
              setNext(undefined);
            }}
          >
            加入依赖
          </Button>
        </Space>
      </div>
      {selection.fields.length > 0 && (
        <ul className="gov-batch-results" aria-label="依赖字段">
          {selection.fields.map((field) => (
            <li key={field.target.targetId}>
              <div>{field.label}</div>
              <Button
                type="link"
                onClick={() =>
                  setSelection({
                    ...selection,
                    fields: selection.fields.filter((item) => item !== field),
                  })
                }
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

/**
 * 从可治理对象和其成功目录读取字段，保留固定身份供后端再次核验。
 */
function ColumnPicker({
  source,
  label,
  value,
  changed,
}: {
  source: string;
  label: string;
  value?: DependencyField;
  changed: (value?: DependencyField) => void;
}) {
  const [table, setTable] = useState<{ value: string; label: string }>();
  const [objectTerm, setObjectTerm] = useState('');
  const [fieldTerm, setFieldTerm] = useState('');
  const base = '/governance/sources/' + source;
  const objects = useQuery({
    queryKey: ['governance', source, 'dependent-objects', objectTerm],
    queryFn: () =>
      api<{ page: Page<GovernanceRow> }>(
        base + '/objects?' + queryString({ keyword: objectTerm, pageSize: 30 }),
      ),
  });
  const fields = useQuery({
    queryKey: ['governance', source, 'dependent-columns', table?.value, fieldTerm],
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
  return (
    <div className="gov-dependency-field">
      {/* 保留已有字段路径，重选时先从当前源找对象。 */}
      <strong>{label}</strong>
      <Select
        aria-label={label + '所属对象'}
        placeholder="选择对象"
        labelInValue
        showSearch={{ filterOption: false, onSearch: setObjectTerm }}
        value={table}
        loading={objects.isFetching}
        options={objects.data?.page.items.flatMap((row) =>
          row.objectId && row.availability === 'AVAILABLE'
            ? [{ value: row.objectId, label: row.qualifiedName }]
            : [],
        )}
        onChange={(object) => {
          // 对象切换时清空搜索词，现有候选在明确选列前仍保留。
          setTable(object);
          setFieldTerm('');
        }}
      />
      <Select
        aria-label={label}
        placeholder="选择字段"
        disabled={!table}
        labelInValue
        showSearch={{ filterOption: false, onSearch: setFieldTerm }}
        value={value ? { value: value.target.targetId, label: value.label } : undefined}
        loading={fields.isFetching}
        options={fields.data?.items.map((field) => ({
          label: field.name,
          value: field.target.targetId,
        }))}
        onChange={(field) => {
          // 只有服务器实际返回、且属于该对象的列可以加入。
          const item = fields.data?.items.find(({ target }) => target.targetId === field.value);
          if (item && table) changed({ target: item.target, label: table.label + '.' + item.name });
        }}
      />
      {(objects.error || fields.error) && (
        <Alert type="error" title={(objects.error ?? fields.error)?.message} />
      )}
    </div>
  );
}
