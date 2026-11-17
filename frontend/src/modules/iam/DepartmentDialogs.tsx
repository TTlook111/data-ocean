import { Alert, Checkbox, Form, Input, InputNumber, Modal, Select } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { api, queryString } from '../../shared/api';
import { ReviewDialog } from '../../shared/ReviewDialog';
import { useFormClose } from '../../shared/useFormClose';
import { useReturnFocus } from '../../shared/useReturnFocus';
import type { ReviewSubmission } from '../../shared/ReviewDialog';
import type { Department, DepartmentForm, Membership, Page, Person, Preview } from './types';

/**
 * 为父节点和迁移目标展开现有组织树，保留稳定 ID 和实际版本。
 */
export function flattenDepartments(nodes: Department[]): Department[] {
  return nodes.flatMap((node) => [node, ...flattenDepartments(node.children ?? [])]);
}

/**
 * 部门新增和编辑共用完整资料表单，负责人以人员 ID 提交。
 */
export function DepartmentEditor({
  department,
  parentId,
  tree,
  close,
}: {
  department: Department | null;
  parentId?: string;
  tree: Department[];
  close: () => void;
}) {
  useReturnFocus();

  const [form] = Form.useForm<DepartmentForm>();

  const [review, setReview] = useState<ReviewSubmission | null>(null);

  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);

  const requestClose = useFormClose(form, close, busy);

  const [search, setSearch] = useState('');

  const managers = useQuery({
    queryKey: ['department-managers', search],
    queryFn: () =>
      api<Person[]>(
        `/iam/user-options?${queryString({ purpose: 'DEPARTMENT_MANAGER', q: search })}`,
      ),
  });

  const unavailable = new Set(
    department
      ? flattenDepartments([
          tree
            .flatMap((root) => flattenDepartments([root]))
            .find((item) => item.id === department.id)!,
        ]).map((item) => item.id)
      : [],
  );

  /**
   * 新增和编辑分别调用明确契约，核对前统一可空引用和字符串。
   */
  async function prepare(values: DepartmentForm) {
    setBusy(true);
    setError(null);
    try {
      const proposal = {
        ...values,
        name: values.name.trim(),
        code: values.code.trim(),
        parentId: values.parentId ?? null,
        managerUserId: values.managerUserId ?? null,
        note: values.note?.trim() || null,
        sortOrder: values.sortOrder ?? 0,
      };

      const preview = await api<Preview>(
        department
          ? `/iam/departments/${department.id}/impact-preview`
          : '/iam/departments/impact-preview',
        {
          method: 'POST',
          body: department
            ? { action: 'UPDATE', expectedVersion: department.version, proposal }
            : { proposal },
        },
      );
      setReview({
        title: department ? `核对部门：${department.name}` : '核对新增部门',
        preview,
        path: department ? `/iam/departments/${department.id}` : '/iam/departments',
        method: department ? 'PUT' : 'POST',
        operation: department ? 'departments.edit' : 'departments.create',
        body: department ? { ...proposal, expectedVersion: department.version } : proposal,
        done: close,
      });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '核对未完成');
    } finally {
      setBusy(false);
    }
  }

  // 基本资料与上级关系放在同一个表单中，取消不改变组织。
  return (
    <>
      <Modal
        centered
        open
        title={department ? `编辑部门：${department.name}` : '新增部门'}
        onCancel={requestClose}
        onOk={() => form.submit()}
        okText="核对并保存"
        confirmLoading={busy}
        closable={!busy}
        cancelButtonProps={{ disabled: busy }}
        width={620}
        mask={{ closable: false }}
      >
        {(error || managers.error) && (
          <Alert
            type="error"
            title={error ?? managers.error?.message}
            showIcon
            className="form-alert"
          />
        )}
        <Form
          form={form}
          layout="vertical"
          onFinish={prepare}
          scrollToFirstError={{ focus: true }}
          initialValues={{
            ...department,
            parentId: department?.parent?.id ?? parentId ?? null,
            managerUserId: department?.manager?.id ?? null,
            sortOrder: department?.sortOrder ?? 0,
          }}
        >
          <div className="form-grid">
            <Form.Item
              name="name"
              label="部门名称"
              rules={[{ required: true, whitespace: true, message: '请输入部门名称' }, { max: 80 }]}
            >
              <Input autoFocus />
            </Form.Item>
            <Form.Item
              name="code"
              label="部门编号"
              rules={[{ required: true, whitespace: true, message: '请输入部门编号' }, { max: 64 }]}
            >
              <Input />
            </Form.Item>
          </div>
          <Form.Item name="parentId" label="上级部门">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={flattenDepartments(tree).map((item) => ({
                value: item.id,
                label: item.name,
                disabled: unavailable.has(item.id) || item.lifecycle === 'ARCHIVED',
              }))}
            />
          </Form.Item>
          <Form.Item name="managerUserId" label="负责人">
            <Select
              allowClear
              showSearch
              filterOption={false}
              onSearch={setSearch}
              options={[
                ...(managers.data ?? []),
                ...(department?.manager &&
                !managers.data?.some((item) => item.id === department.manager!.id)
                  ? [department.manager]
                  : []),
              ].map((item) => ({
                value: item.id,
                label: `${item.displayName}（${item.username}）`,
              }))}
            />
          </Form.Item>
          <Form.Item
            name="sortOrder"
            label="同级排序"
            rules={[{ required: true, message: '请输入排序值' }]}
          >
            <InputNumber min={-2147483648} max={2147483647} precision={0} />
          </Form.Item>
          <Form.Item name="note" label="说明" rules={[{ max: 300 }]}>
            <Input.TextArea rows={3} maxLength={300} showCount />
          </Form.Item>
        </Form>
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}

/**
 * 批量成员、迁移和生命周期动作保留部门上下文，统一核对后提交。
 */
export function DepartmentAction({
  department,
  action,
  tree,
  member,
  close,
}: {
  department: Department;
  action: string;
  tree: Department[];
  member?: { person: Person; relation: Membership };
  close: () => void;
}) {
  useReturnFocus();

  const [form] = Form.useForm();

  const [review, setReview] = useState<ReviewSubmission | null>(null);

  const [error, setError] = useState<string | null>(null);

  const [busy, setBusy] = useState(false);

  const requestClose = useFormClose(form, close, busy);

  const [search, setSearch] = useState('');

  const choices = useQuery({
    queryKey: ['member-add', department.id, search],
    queryFn: () =>
      api<Person[]>(
        `/iam/user-options?${queryString({ purpose: 'MEMBER_ADD', departmentId: department.id, q: search })}`,
      ),
    enabled: action === 'secondary-members',
  });

  const [selectedPeople, setSelectedPeople] = useState<Person[]>([]);

  const currentNode = flattenDepartments(tree).find((item) => item.id === department.id);

  const excluded = new Set(
    currentNode ? flattenDepartments([currentNode]).map((item) => item.id) : [department.id],
  );

  const destinations = flattenDepartments(tree).filter(
    (item) => item.lifecycle === 'ENABLED' && !excluded.has(item.id),
  );

  const titles: Record<string, string> = {
    'secondary-members': '加入兼任成员',
    'member-transfers': '迁移全部直属成员',
    'child-transfers': '迁移全部直属子部门',
    end: '移出兼任',
    disable: '停用部门',
    enable: '复核并恢复部门',
    archive: '归档部门',
  };

  /**
   * 完整读取直属成员的全部分页，避免把当前页误作整批集合。
   */
  async function allDirectMembers() {
    const people: Person[] = [];
    let pageNum = 1;
    let total = 0;
    do {
      const page = await api<Page<Person>>(
        `/iam/departments/${department.id}/members?${queryString({ includeDescendants: false, pageNum, pageSize: 100 })}`,
      );
      people.push(...page.items);
      total = page.total;
      pageNum++;
    } while (people.length < total);
    return people.map((person) => ({ userId: person.id, version: person.version }));
  }

  /**
   * 构造完整集合及目标版本，预览与提交保持同一份提案。
   */
  async function prepare(values: {
    reason: string;
    destinationId?: string;
    memberIds?: string[];
    reviewedMembers?: boolean;
  }) {
    setBusy(true);
    setError(null);
    try {
      const reason = values.reason.trim();

      const target = destinations.find((item) => item.id === values.destinationId);

      const destination = target ? { id: target.id, version: target.version } : undefined;

      let proposal: object = { reason };

      let code = action.toUpperCase();
      if (action === 'secondary-members') {
        proposal = {
          reason,
          members: selectedPeople.map((person) => ({ userId: person.id, version: person.version })),
        };
        code = 'ADD_SECONDARY';
      } else if (action === 'end') {
        proposal = {
          reason,
          membershipId: member!.relation.id,
          userExpectedVersion: member!.person.version,
        };
        code = 'END_MEMBERSHIP';
      } else if (action === 'member-transfers') {
        proposal = { reason, destination, members: await allDirectMembers() };
        code = 'MOVE_MEMBERS';
      } else if (action === 'child-transfers') {
        proposal = {
          reason,
          destination,
          children: (currentNode?.children ?? [])
            .filter((item) => item.lifecycle !== 'ARCHIVED')
            .map((item) => ({ id: item.id, version: item.version })),
        };
        code = 'MOVE_CHILDREN';
      } else if (action === 'enable') {
        proposal = { reason, reviewedMembers: values.reviewedMembers };
      }

      const preview = await api<Preview>(`/iam/departments/${department.id}/impact-preview`, {
        method: 'POST',
        body: { action: code, expectedVersion: department.version, proposal },
      });

      const body =
        action === 'end'
          ? {
              reason,
              userExpectedVersion: member!.person.version,
              expectedVersion: department.version,
            }
          : { ...proposal, expectedVersion: department.version };

      const operation =
        action === 'secondary-members'
          ? 'departments.add-member'
          : action === 'end'
            ? 'departments.remove-member'
            : action.endsWith('transfers')
              ? 'departments.transfer-members'
              : `departments.${action}`;
      setReview({
        title: `${titles[action]}：${department.name}`,
        preview,
        path: `/iam/departments/${department.id}/${action === 'end' ? `memberships/${member!.relation.id}/end` : action}`,
        method: 'POST',
        operation,
        body,
        done: close,
      });
    } catch (failure) {
      setError(failure instanceof Error ? failure.message : '核对未完成');
    } finally {
      setBusy(false);
    }
  }

  // 迁移显示完整目标与操作原因，实际集合由服务端再次核对。
  return (
    <>
      <Modal
        centered
        open
        title={`${titles[action]}：${department.name}`}
        onCancel={requestClose}
        onOk={() => form.submit()}
        okText="核对并继续"
        confirmLoading={busy}
        closable={!busy}
        cancelButtonProps={{ disabled: busy }}
        mask={{ closable: false }}
      >
        {(error || choices.error) && (
          <Alert
            type="error"
            title={error ?? choices.error?.message}
            showIcon
            className="form-alert"
          />
        )}
        <Form form={form} layout="vertical" onFinish={prepare} scrollToFirstError={{ focus: true }}>
          {action === 'secondary-members' && (
            <Form.Item
              name="memberIds"
              label="加入兼任人员"
              rules={[{ required: true, type: 'array', min: 1, message: '请至少选择一名人员' }]}
            >
              <Select
                mode="multiple"
                showSearch
                filterOption={false}
                onSearch={setSearch}
                onChange={(ids: string[]) =>
                  setSelectedPeople(
                    ids.map((id) =>
                      [...(choices.data ?? []), ...selectedPeople].find(
                        (person) => person.id === id,
                      )!,
                    ),
                  )
                }
                options={[
                  ...(choices.data ?? []),
                  ...selectedPeople.filter(
                    (person) => !choices.data?.some((item) => item.id === person.id),
                  ),
                ].map((person) => ({
                  value: person.id,
                  label: `${person.displayName}（${person.username}）`,
                }))}
              />
            </Form.Item>
          )}
          {action.endsWith('transfers') && (
            <Form.Item
              name="destinationId"
              label="目标部门"
              rules={[{ required: true, message: '请选择原部门子树以外的目标部门' }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                options={destinations.map((item) => ({ value: item.id, label: item.name }))}
              />
            </Form.Item>
          )}
          {member && (
            <Alert
              type="warning"
              title={`将移出 ${member.person.displayName} 在本部门的兼任，其他主兼任和角色保留。`}
              showIcon
              className="form-alert"
            />
          )}
          {action === 'enable' && (
            <Form.Item
              name="reviewedMembers"
              valuePropName="checked"
              rules={[
                {
                  validator: (_, value) =>
                    value ? Promise.resolve() : Promise.reject(new Error('请先完成复核')),
                },
              ]}
            >
              <Checkbox>已复核成员与保留配置，旧来源不自动恢复</Checkbox>
            </Form.Item>
          )}
          <Form.Item
            name="reason"
            label="操作原因"
            rules={[{ required: true, whitespace: true, message: '请填写操作原因' }, { max: 500 }]}
          >
            <Input.TextArea rows={3} maxLength={500} showCount />
          </Form.Item>
        </Form>
      </Modal>
      {review && <ReviewDialog submission={review} close={() => setReview(null)} />}
    </>
  );
}
