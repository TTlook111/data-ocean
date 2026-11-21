import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { Button, Form, Input, Space, Tag } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { api, queryString } from '../../shared/api';
import { ReadState, displayTime } from '../../shared/Records';
import { RecordTable } from '../../shared/RecordTable';
import { allowed, useSession } from '../../shared/session';
import { SourcePicker, SourceUnavailable, useSourceSelection } from '../datasource/CollectionPage';
import type { Page } from '../iam/types';
import { documentStates, type KnowledgeRow } from './types';
import './knowledge.css';

/** 知识文档列表保留当前源、查询和返回位置。 */
export function KnowledgePage() {
  const { params, setParams, sources, selected } = useSourceSelection('KNOWLEDGE');
  const [form] = Form.useForm<{ keyword?: string }>();
  const navigate = useNavigate();
  const location = useLocation();
  const session = useSession();
  const returned = (location.state as { focusDocument?: string } | null)?.focusDocument;
  const restored = useRef<string>(undefined);
  const size = Number(params.get('pageSize') ?? 10);
  const pageNum = Number(params.get('pageNum') ?? 1);
  const keyword = params.get('keyword') ?? '';
  const source = selected?.id;
  const list = useQuery({
    queryKey: ['knowledge-documents', source, keyword, pageNum, size],
    enabled: Boolean(source),
    queryFn: () =>
      api<Page<KnowledgeRow>>(
        '/knowledge/sources/' +
          source +
          '/documents?' +
          queryString({ keyword, pageNum, pageSize: size }),
      ),
  });

  useEffect(() => {
    // 浏览器返回时同步实际查询词，后续输入只留在表单里。
    form.setFieldValue('keyword', keyword);
  }, [form, keyword]);

  useEffect(() => {
    if (!returned || !list.isSuccess || restored.current === location.key) return;
    restored.current = location.key;
    // 当原条目不再匹配筛选时，把焦点交还给查询框。
    (
      document.getElementById('knowledge-open-' + returned) ??
      document.getElementById('knowledge-search')
    )?.focus();
  }, [list.isSuccess, location.key, returned]);

  /** 同一源下打开正文，保留原查询地址。 */
  function open(id: string) {
    navigate('/knowledge/' + id + '?' + queryString({ source }), {
      state: { listSearch: '?' + queryString({ source, keyword, pageNum, pageSize: size }) },
    });
  }

  /** 重置查询条件，保留当前源和每页条数。 */
  function reset(next = source) {
    form.setFieldValue('keyword', '');
    setParams(queryString({ source: next, pageSize: size }));
    form.focusField('keyword');
  }

  return (
    <main className="record-page" aria-label="知识文档">
      {/* 按原型先选来源及办理入口，再在下方查询文档。 */}
      <div className="panel ds-toolbar knowledge-source-toolbar">
        <SourcePicker withLabel sources={sources.data} selected={selected} choose={reset} />
        <Space size={8} wrap>
          {source && allowed(session, 'governance.view') && (
            <Button onClick={() => navigate('/governance?' + queryString({ source }))}>
              查看治理依据
            </Button>
          )}
          {source && allowed(session, 'knowledge.edit') && (
            <Button
              id="knowledge-open-new"
              type="primary"
              icon={<PlusOutlined aria-hidden="true" />}
              disabled={selected.lifecycle !== 'ENABLED'}
              onClick={() => open('new')}
            >
              新增文档
            </Button>
          )}
        </Space>
      </div>
      <div className="panel query-panel knowledge-list-filters">
        <Form
          form={form}
          layout="inline"
          initialValues={{ keyword }}
          onFinish={(input) =>
            setParams(queryString({ source, keyword: input.keyword?.trim(), pageSize: size }))
          }
        >
          <Form.Item name="keyword">
            <Input
              id="knowledge-search"
              aria-label="文档名称"
              placeholder="搜索文档名称"
              prefix={<SearchOutlined aria-hidden="true" />}
              allowClear
              disabled={!source}
            />
          </Form.Item>
          <Space>
            <Button htmlType="submit" disabled={!source}>
              查询
            </Button>
            <Button disabled={!source} onClick={() => reset()}>
              重置
            </Button>
          </Space>
        </Form>
      </div>
      <ReadState
        error={sources.error}
        loading={sources.isPending}
        retry={() => void sources.refetch()}
      >
        {source ? (
          <section className="panel records-panel knowledge-records" aria-label="知识文档列表">
            <RecordTable
              rowKey="id"
              tableLayout="fixed"
              flexibleColumns={[0]}
              dataSource={list.data?.items}
              loading={list.isPending}
              error={list.error}
              retry={() => void list.refetch()}
              emptyDescription="暂无知识文档"
              filtered={Boolean(keyword)}
              onReset={() => reset()}
              columns={[
                {
                  title: '文档名称',
                  dataIndex: 'title',
                  render: (title: string, row) => (
                    <Button
                      id={'knowledge-open-' + row.id}
                      className="knowledge-record-link"
                      type="link"
                      title={title}
                      onClick={() => open(row.id)}
                    >
                      {title}
                    </Button>
                  ),
                },
                {
                  title: '整理状态',
                  width: 112,
                  render: (_, row) => (
                    <Tag
                      color={
                        row.state === 'CONFIRMED'
                          ? 'green'
                          : row.state === 'REVIEW'
                            ? 'orange'
                            : undefined
                      }
                    >
                      {documentStates[row.state]}
                    </Tag>
                  ),
                },
                { title: '最近确认', width: 160, render: (_, row) => displayTime(row.confirmedAt) },
              ]}
              pagination={{
                current: list.data?.pageNum ?? pageNum,
                pageSize: size,
                total: list.data?.total,
                onChange: (pageNum, pageSize) =>
                  setParams(queryString({ source, keyword, pageNum, pageSize })),
              }}
            />
          </section>
        ) : (
          <SourceUnavailable />
        )}
      </ReadState>
    </main>
  );
}
