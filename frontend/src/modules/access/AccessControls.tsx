import { Alert, App, Button, Drawer, Form, Grid, Input, Modal, Select, Space, Tag } from 'antd';
import type { FormInstance, SelectProps, TableProps } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { useEffect, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { useSearchParams } from 'react-router';
import type { Page, Preview } from '../iam/types';
import { api, mutation, queryString } from '../../shared/api';
import { MoreFilters } from '../../shared/MoreFilters';
import { QueryDateRange } from '../../shared/QueryDateRange';
import { RecordTable } from '../../shared/RecordTable';
import { DetailFooter, readErrorMessage } from '../../shared/Records';
import { useFormClose } from '../../shared/useFormClose';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { OutputRule, PersonOption, SourceOption } from './types';

/**
 * 所有访问子页共用请求前缀与后端状态名称。
 */
export const accessPath = '/access';

/**
 * 用词保留批准结论与实际生效的区别。
 */
export const accessState: Record<string, string> = {
  UNCONFIGURED: '未配置',
  READY: '已配置',
  REVIEW: '需复核',
  DRAFT: '草稿',
  ROUTING: '待分流',
  PENDING: '待审批',
  NEEDS_SUPPLEMENT: '需补充',
  APPROVED: '已批准，待联合生效',
  EFFECTIVE: '已生效',
  REJECTED: '已驳回',
  WITHDRAWN: '已撤回',
  ENDED: '已结束',
  SUSPENDED: '暂停访问',
  PARTIALLY_EFFECTIVE: '部分已生效',
  IN_PROGRESS: '办理中',
};

/**
 * 集中呈现真实状态，文字在没有颜色时仍能理解。
 */
export function AccessStatus({ value }: { value: string }) {
  const color = ['READY', 'EFFECTIVE'].includes(value)
    ? 'success'
    : ['APPROVED', 'IN_PROGRESS'].includes(value)
      ? 'processing'
      : ['PENDING', 'UNCONFIGURED', 'ROUTING', 'REVIEW', 'NEEDS_SUPPLEMENT', 'SUSPENDED'].includes(
            value,
          )
        ? 'warning'
        : value === 'REJECTED'
          ? 'error'
          : 'default';
  return (
    <Tag color={color} className={`access-status access-status-${color}`}>
      {accessState[value] ?? value}
    </Tag>
  );
}

/**
 * 人员名称附稳定登录标识，区分同名账号。
 */
export function personLabel(person?: PersonOption | null) {
  return person ? `${person.displayName}（${person.username}）` : '—';
}

/**
 * 后台表格用两行保留姓名和登录名，长身份不会跨列。
 */
export function AccessPerson({ person }: { person?: PersonOption | null }) {
  if (!person) {
    // 责任尚未配置或此行不对应审批身份。
    return <span>—</span>;
  }
  return (
    <span className="access-person" title={personLabel(person)}>
      <span>{person.displayName}</span>
      <small>{person.username}</small>
    </span>
  );
}

/**
 * 筛选控件与服务端对应参数。
 */
export interface AccessFilter {
  /** 查询参数名称。 */
  name: string;
  /** 输入占位与可访问名称。 */
  placeholder: string;
  /** 需要短占位时保留完整的搜索口径。 */
  ariaLabel?: string;
  /** 状态等字典选项。 */
  options?: { value: string; label: string }[];
  /** 使用可搜索的数据源选项。 */
  kind?: 'source';
  /** 日期范围会由起止两项合并为一个控件。 */
  date?: boolean;
  /** 低频条件在更多筛选中编辑。 */
  inMore?: boolean;
}

/**
 * 由查询表单和列表共用当前 URL 中的业务条件。
 */
export function useAccessList<T>(
  path: string,
  filters: AccessFilter[],
  fixed?: Record<string, string>,
  namespace = accessPath,
  enabled = true,
) {
  const [params, setParams] = useSearchParams();
  const query = Object.fromEntries(
    [...filters.map((field) => field.name), 'pageNum', 'pageSize'].flatMap((name) => {
      // 只将当前入口维护的筛选和分页条件传给后端。
      const value = params.get(name);
      return value ? [[name, value]] : [];
    }),
  );
  const read = useQuery({
    queryKey: ['access', namespace, path, query, fixed],
    queryFn: () => accessList<T>(path, query, fixed, namespace),
    enabled,
  });

  /**
   * 查询回到第一页并保留当前业务页签。
   */
  function search(values: Record<string, string | undefined>) {
    const next = new URLSearchParams(params);
    filters.forEach(({ name }) => {
      // 空值彻底清除原筛选条件。
      const value = values[name]?.trim();
      if (value) next.set(name, value);
      else next.delete(name);
    });
    next.delete('pageNum');
    next.delete('selected');
    setParams(next, { replace: true });
  }

  /**
   * 页码由服务端校正，改变每页条目时翻回首页。
   */
  function changePage(page: number, size: number) {
    const next = new URLSearchParams(params);
    next.set('pageNum', String(size === Number(params.get('pageSize') ?? 10) ? page : 1));
    next.set('pageSize', String(size));
    next.delete('selected');
    setParams(next, { replace: true });
  }

  const pagination = {
    current: read.data?.pageNum ?? 1,
    pageSize: read.data?.pageSize ?? Number(params.get('pageSize') ?? 10),
    total: read.data?.total ?? 0,
    onChange: changePage,
  };
  return {
    query,
    read,
    search,
    pagination,
    filtered: filters.some(({ name }) => Boolean(params.get(name))),
  };
}

/**
 * 列表和审批分组计数使用同一个请求参数口径。
 */
export function accessList<T>(
  path: string,
  query: Record<string, string>,
  fixed?: Record<string, string>,
  namespace = accessPath,
) {
  return api<Page<T>>(
    `${namespace}${path}?${queryString({
      ...query,
      ...fixed,
      timeZone:
        query.dateFrom || query.dateTo
          ? Intl.DateTimeFormat().resolvedOptions().timeZone
          : undefined,
    })}`,
  );
}

/**
 * 访问列表共用查询表单，低频条件可通过浮层展开。
 */
export function AccessToolbar({
  filters,
  query,
  search,
  action,
}: {
  filters: AccessFilter[];
  query: Record<string, string>;
  search: (values: Record<string, string | undefined>) => void;
  action?: ReactNode;
}) {
  const [form] = Form.useForm<Record<string, string | DateRange | undefined>>();
  const [moreOpen, setMoreOpen] = useState(false);
  const dates = filters.filter((item) => item.date);
  const fields = filters.filter((item) => !item.date);
  const hasDates = dates.length > 0;
  const hasMore = hasDates || filters.some((item) => item.inMore);
  const narrow = Grid.useBreakpoint().lg === false;
  // 窄桌面将状态一并收入浮层，给姓名和数据源保留输入空间。
  const moreFields = fields.filter(
    (item) => item.inMore || (hasMore && narrow && item.name === 'state'),
  );
  const moreCount =
    moreFields.filter(({ name }) => Boolean(query[name])).length +
    Number(hasDates && Boolean(query.dateFrom || query.dateTo));
  const trailingStatus =
    !hasMore && !dates.length && fields.at(-1)?.name === 'state' ? fields.at(-1) : undefined;
  const dateRange: [string, string] | undefined =
    query.dateFrom && query.dateTo ? [query.dateFrom, query.dateTo] : undefined;
  const rangeName = dates[0]?.placeholder ?? '';
  const previous = useRef<string | null>(null);
  useEffect(() => {
    // 列表空态清空、浏览器返回时，以 URL 的实际筛选同步输入，不覆盖未提交的编辑。
    const editable = filters.filter((item) => !item.date);
    const appliedDates: [string, string] | undefined =
      query.dateFrom && query.dateTo ? [query.dateFrom, query.dateTo] : undefined;
    const values = {
      ...Object.fromEntries(editable.map(({ name }) => [name, query[name]])),
      dates: appliedDates,
    };
    const appliedKey = JSON.stringify(values);
    if (previous.current === appliedKey) return;
    const hadFilters = previous.current !== null && previous.current !== '{}';
    previous.current = appliedKey;
    form.setFieldsValue(values);
    if (hadFilters && appliedKey === '{}' && editable[0]) form.focusField(editable[0].name);
  }, [filters, form, query]);

  /**
   * 查询前将日期范围拆成公共服务的起点和终点参数。
   */
  function apply() {
    // URL 已带条件时，尚未打开的筛选字段也必须参与查询。
    const values = form.getFieldsValue(true) as Record<string, string | DateRange | undefined>;
    const range = values.dates;
    const strings = Object.fromEntries(
      Object.entries(values).filter(
        (entry): entry is [string, string] => typeof entry[1] === 'string',
      ),
    );
    search({ ...strings, dateFrom: range?.[0], dateTo: range?.[1] });
    setMoreOpen(false);
    if (fields[0]) form.focusField(fields[0].name);
  }

  /**
   * 清空查询字段，焦点回到首个条件。
   */
  function reset() {
    form.setFieldsValue({
      ...Object.fromEntries(fields.map(({ name }) => [name, undefined])),
      dates: undefined,
    });
    setMoreOpen(false);
    search({});
    if (fields[0]) {
      // 与既有后台工具栏保持键盘回到查询起点。
      form.focusField(fields[0].name);
    }
  }

  /**
   * 同一筛选字段在独立位置或末尾分组中沿用相同控件。
   */
  function renderField({ name, placeholder, ariaLabel, options, kind }: AccessFilter) {
    const fieldClass =
      name === 'state'
        ? 'access-status-field'
        : kind || options
          ? 'access-select-field'
          : undefined;
    return (
      <Form.Item key={name} name={name} className={fieldClass}>
        {kind ? (
          <AccessSelect kind={kind} label={placeholder} allowClear />
        ) : options ? (
          <Select
            aria-label={ariaLabel ?? placeholder}
            placeholder={placeholder}
            options={options}
            allowClear
          />
        ) : (
          <Input
            aria-label={ariaLabel ?? placeholder}
            title={ariaLabel}
            placeholder={placeholder}
            allowClear
          />
        )}
      </Form.Item>
    );
  }

  /**
   * 仅提交完整且顺序正确的日期范围，错误时保留待修改的值。
   */
  function validateRange(_: unknown, range?: DateRange) {
    if (range?.some(Boolean) && (!range[0] || !range[1])) {
      // 部分输入保留在原字段，提示补充另一端。
      return Promise.reject(new Error('请选择开始日期和结束日期'));
    }
    if (range?.[0] && range[1] && range[0] > range[1]) {
      // ISO 日期可以直接按字面顺序比较。
      return Promise.reject(new Error('开始日期不能晚于结束日期'));
    }
    return Promise.resolve();
  }

  const dateField = hasDates && (
    <Form.Item name="dates" className="query-more-date" rules={[{ validator: validateRange }]}>
      <AccessDateRange label={rangeName} />
    </Form.Item>
  );

  return (
    <div className={`panel query-panel access-toolbar${hasMore ? ' compact-query' : ''}`}>
      <Form
        form={form}
        layout="inline"
        initialValues={{ ...query, dates: dateRange }}
        onFinish={apply}
        onFinishFailed={() => {
          // 重新打开校验失败的隐藏条件，保留已输入的日期。
          if (hasDates) setMoreOpen(true);
        }}
      >
        {/* 常用条件留在主工具栏，次要字段按原顺序收进浮层。 */}
        {fields
          .filter((field) => field !== trailingStatus && !moreFields.includes(field))
          .map(renderField)}
        {(filters.length > 0 || action) && (
          <div
            className={
              trailingStatus
                ? 'access-query-tail has-status'
                : hasMore
                  ? 'access-query-tail query-tail-actions'
                  : 'access-query-tail'
            }
          >
            {trailingStatus && renderField(trailingStatus)}
            <Space wrap={!hasMore} className="access-query-actions">
              {/* 浮层只控制字段可见性，提交和重置继续使用外层表单。 */}
              {hasMore && (
                <MoreFilters open={moreOpen} setOpen={setMoreOpen} count={moreCount}>
                  {moreFields.map(renderField)}
                  {dateField}
                </MoreFilters>
              )}
              {filters.length > 0 && (
                <>
                  <Button type={hasMore && !action ? 'primary' : 'default'} htmlType="submit">
                    查询
                  </Button>
                  <Button onClick={reset}>重置</Button>
                </>
              )}
              {action}
            </Space>
          </div>
        )}
      </Form>
    </div>
  );
}

/** 日期范围的两个端点在提交前允许分别编辑。 */
type DateRange = [string?, string?];

/**
 * 复用中文日期范围，浮层内采用与原型相近的紧凑双月日历。
 */
function AccessDateRange({
  value,
  onChange,
  label,
}: {
  /** 当前输入的 ISO 业务日期。 */
  value?: DateRange;
  /** 将输入同步到访问列表表单。 */
  onChange?: (value?: DateRange) => void;
  /** 日期的业务语义。 */
  label: string;
}) {
  return (
    <QueryDateRange
      label={label}
      allowEmpty={[true, true]}
      order={false}
      value={value ? [value[0] ? dayjs(value[0]) : null, value[1] ? dayjs(value[1]) : null] : null}
      onChange={(range) => {
        // 清空同时移除两端，已输入的端点以接口日期格式保存。
        onChange?.(
          range ? [range[0]?.format('YYYY-MM-DD'), range[1]?.format('YYYY-MM-DD')] : undefined,
        );
      }}
    />
  );
}

/**
 * 访问与资料回看复用固定底栏，关闭详情后交还原入口焦点。
 */
export function AccessDetailDrawer({
  title,
  size,
  close,
  actions,
  children,
}: {
  /** 当前详情名称，长名称在标题栏内省略。 */
  title: string;
  /** 正常窗口下的阅读宽度，单位为像素。 */
  size: number;
  /** 关闭当前详情。 */
  close: () => void;
  /** 当前账号可执行的详情操作。 */
  actions?: ReactNode;
  /** 可独立滚动的详情正文。 */
  children: ReactNode;
}) {
  useReturnFocus();
  return (
    <Drawer
      open
      className="record-details-drawer access-detail-drawer"
      size={'min(' + size + 'px, calc(100vw - 80px))'}
      closable={{ placement: 'end' }}
      title={
        <span className="cell-text" title={title}>
          {title}
        </span>
      }
      onClose={close}
      footer={<DetailFooter close={close}>{actions}</DetailFooter>}
    >
      {children}
    </Drawer>
  );
}

/**
 * 共用业务筛选、列表面板与真实分页。
 */
export function AccessGrid<T extends object>({
  path,
  namespace,
  columns,
  rowKey,
  filters,
  fixed,
  empty,
  action,
  header,
}: {
  path: string;
  namespace?: string;
  columns: TableProps<T>['columns'];
  rowKey: TableProps<T>['rowKey'];
  filters: AccessFilter[];
  fixed?: Record<string, string>;
  empty: string;
  action?: ReactNode;
  header?: ReactNode;
}) {
  const { query, read, search, pagination, filtered } = useAccessList<T>(
    path,
    filters,
    fixed,
    namespace,
  );
  return (
    <section className="record-page">
      {/* 无筛选和操作时只保留表格面板。 */}
      {(filters.length > 0 || action) && (
        <AccessToolbar filters={filters} query={query} search={search} action={action} />
      )}
      <div className="panel records-panel access-records">
        {header}
        {/* 后台刷新保留已显示的结果和空态操作，首次读取再显示加载遮罩。 */}
        <RecordTable<T>
          columns={columns}
          rowKey={rowKey}
          dataSource={read.data?.items ?? []}
          loading={read.isPending}
          error={read.error}
          retry={() => void read.refetch()}
          filtered={filtered}
          onReset={() => search({})}
          emptyDescription={empty}
          pagination={pagination}
        />
      </div>
    </section>
  );
}

/**
 * 通过服务端搜索获取可选资源，保留同一表单已经加载的标签。
 */
export function AccessSelect({
  kind,
  label,
  value,
  onChange,
  multiple,
  allowClear,
  placement,
  selected = [],
  remember,
  id,
}: {
  kind: 'source' | 'rule' | 'approver';
  label: string;
  value?: string | string[];
  onChange?: (value: string | string[]) => void;
  multiple?: boolean;
  allowClear?: boolean;
  placement?: SelectProps['placement'];
  selected?: { value: string; label: string; content?: string }[];
  remember?: (option: { value: string; label: string; content?: string }) => void;
  id?: string;
}) {
  const [term, setTerm] = useState('');
  const read = useQuery({
    queryKey: ['access', kind, term],
    queryFn: () =>
      api<Page<SourceOption | OutputRule | PersonOption>>(
        `${accessPath}/${kind}-options?${queryString({ keyword: term || undefined, pageSize: 50 })}`,
      ),
  });
  const available =
    read.data?.items.map((row) => ({
      value: row.id,
      label: 'displayName' in row ? personLabel(row) : row.name,
      content: 'content' in row ? row.content : undefined,
    })) ?? [];
  const choices = Array.from(
    new Map([...selected, ...available].map((item) => [item.value, item])).values(),
  );
  return (
    <Select
      id={id}
      aria-label={label}
      placeholder={label}
      mode={multiple ? 'multiple' : undefined}
      allowClear={allowClear}
      placement={placement}
      listHeight={kind === 'rule' ? 200 : undefined}
      showSearch={{ filterOption: false, onSearch: setTerm }}
      value={value}
      onChange={(value: string | string[]) => {
        // 把实际选择的标签保存到当前表单。
        for (const key of typeof value === 'string' ? [value] : (value ?? [])) {
          const option = choices.find((item) => item.value === key);
          if (option) remember?.(option);
        }
        onChange?.(value);
      }}
      options={choices}
      loading={read.isFetching}
      status={read.error ? 'error' : undefined}
      notFoundContent={read.error ? readErrorMessage(read.error) : undefined}
      optionRender={({ data }) => (
        // 选择限制规则时可以直接查看真实正文。
        <span className="access-option">
          <span>{data.label}</span>
          {'content' in data && data.content && <small>{String(data.content)}</small>}
        </span>
      )}
    />
  );
}

/**
 * 受控编辑动作保持输入、核对及同一次提交的命令编号。
 */
export function AccessEditor<T extends object>({
  title,
  reviewTitle,
  successMessage,
  context,
  form,
  initialValues,
  children,
  confirm,
  save,
  close,
  done,
  danger,
  extra,
  prepare,
  preview,
}: {
  title: string;
  reviewTitle?: string;
  /** 操作落地后确认的交付结果。 */
  successMessage: string;
  context?: ReactNode;
  form: FormInstance<T>;
  initialValues?: Partial<T>;
  children: ReactNode;
  confirm: (values: T, preview?: Preview) => ReactNode;
  save: (values: T, command: string, reviewToken?: string) => Promise<unknown>;
  close: () => void;
  done?: () => void;
  danger?: boolean;
  extra?: ReactNode;
  prepare?: () => void;
  preview?: (values: T) => Promise<Preview>;
}) {
  const cache = useQueryClient();
  const { message } = App.useApp();
  const [review, setReview] = useState<{ values: T; command: string; preview?: Preview } | null>(
    null,
  );
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const requestClose = useFormClose(form, close, busy);

  /**
   * 保存已核对的提案，未知回包继续用原命令。
   */
  async function submit() {
    if (!review || busy) return;
    setBusy(true);
    setError(null);
    try {
      // 写入成功后回读当前列表及来源。
      await save(review.values, review.command, review.preview?.reviewToken);
      await cache.invalidateQueries();
      message.success(successMessage);
      done?.();
      close();
    } catch (failure) {
      // 保留输入和命令，以便重试或返回修改。
      setError(failure instanceof Error ? failure.message : '操作未完成');
    } finally {
      setBusy(false);
    }
  }

  /**
   * 后端核对完成后展示实际规则及提交依据。
   */
  async function prepareSubmission(values: T) {
    setBusy(true);
    setError(null);
    try {
      // 核对失败保留表单，返回的签名只用于这一次内容。
      const result = await preview?.(values);
      setReview({ values, command: crypto.randomUUID(), preview: result });
    } catch (failure) {
      // 已变化的源、人员、规则或审批状态在表单反馈。
      setError(failure instanceof Error ? failure.message : '无法核对变更');
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal
      centered
      open
      title={review ? (reviewTitle ?? `核对${title}`) : title}
      width={640}
      mask={{ closable: false }}
      onCancel={busy ? undefined : () => void requestClose()}
      closable={!busy}
      footer={
        <Space>
          <Button
            disabled={busy}
            onClick={() => {
              // 返回修改时保留原表单输入。
              if (review) {
                setReview(null);
                setError(null);
              } else void requestClose();
            }}
          >
            {review ? '返回修改' : '取消'}
          </Button>
          {!review && extra}
          <Button
            type="primary"
            danger={danger}
            loading={busy}
            onClick={() => {
              // 核对和正式提交分别走实际表单或原命令。
              if (review) void submit();
              else {
                prepare?.();
                form.submit();
              }
            }}
          >
            {review ? '确认提交' : '核对'}
          </Button>
        </Space>
      }
    >
      {/* 保持对象摘要与编辑位置一致。 */}
      {context && <div className="access-context">{context}</div>}
      {error && <Alert type="error" showIcon title={error} />}
      <Form
        form={form}
        initialValues={initialValues}
        layout="vertical"
        hidden={Boolean(review)}
        onFinish={(values) => void prepareSubmission(values)}
        onFinishFailed={({ errorFields }) => {
          // 必填或格式错误直接回到第一个需要修正的字段。
          if (errorFields[0]) form.scrollToField(errorFields[0].name, { focus: true });
        }}
      >
        {children}
      </Form>
      {review && (
        <div className="access-review">
          {confirm(review.values, review.preview)}
          {Boolean(review.preview?.affected.length) && (
            <p>
              {review.preview?.affected.map(({ kind, count }) => `${kind} ${count}`).join('，')}
            </p>
          )}
          {review.preview?.warnings.map((warning) => (
            <Alert key={warning} type="warning" title={warning} />
          ))}
        </div>
      )}
    </Modal>
  );
}

/**
 * 访问变更共用请求和原命令核实。
 */
export function saveAccess(
  path: string,
  method: string,
  body: object,
  operation: string,
  command: string,
) {
  return mutation<{ targetId: string }>(`${accessPath}${path}`, method, body, operation, command);
}

/**
 * 从配置接口获取当前系统核对结果。
 */
export function previewAccess(path: string, body: object) {
  return api<Preview>(`${accessPath}${path}/impact-preview`, { method: 'POST', body });
}

/**
 * 多个状态筛选选项沿用同一命名。
 */
export function statusOptions(values: string[]) {
  return values.map((value) => ({ value, label: accessState[value] ?? value }));
}
