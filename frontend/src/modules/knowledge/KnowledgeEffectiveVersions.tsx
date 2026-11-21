import { Button, Form, Input, Modal, Space } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { api, queryString } from '../../shared/api';
import { RecordTable } from '../../shared/RecordTable';
import { displayTime, ReadState } from '../../shared/Records';
import type { Page } from '../iam/types';
import { VersionAvailability, type PublishedVersion } from './ReleasePublicationPanel';

/**
 * 绑定解出的只读正文，使用历史保存的用途、边界与章节。
 */
type PublishedContent = {
  title: string;
  purpose: string;
  exclusions: string;
  sections: { title: string; body: string }[];
};

/**
 * 当前版本与同范围直接上一版的实际发布快照。
 */
type EffectiveDetail = {
  current: PublishedVersion;
  previous: PublishedVersion | null;
  content: PublishedContent | null;
  previousContent: PublishedContent | null;
};

/**
 * 在原型的当前生效页签按同源权威绑定浏览正式版本。
 */
export function KnowledgeEffectiveVersions({
  source,
  params,
  update,
}: {
  source: string;
  params: URLSearchParams;
  update: (values: Record<string, string | number | null>) => void;
}) {
  const [form] = Form.useForm();
  const path = '/knowledge/sources/' + source + '/bindings';
  const picked = params.get('bindingId');
  const scope = params.get('scopeId');
  const keyword = params.get('versionKeyword');
  const object = params.get('versionObject');
  const pageNum = Number(params.get('versionPageNum')) || 1;
  const pageSize = Number(params.get('versionPageSize')) || 10;
  const page = useQuery({
    queryKey: ['release-bindings', source, scope, keyword, object, pageNum, pageSize],
    enabled: !picked,
    queryFn: () =>
      api<Page<PublishedVersion>>(
        path + '?' + queryString({ scopeId: scope, keyword, object, pageNum, pageSize }),
      ),
  });

  /**
   * 重置当前生效的查找条件，原任务列表条件继续独立维护。
   */
  function reset() {
    form.setFieldsValue({ versionKeyword: undefined, versionObject: undefined });
    update({ scopeId: null, versionKeyword: null, versionObject: null, versionPageNum: 1 });
  }

  if (picked) {
    return (
      <VersionDetail
        key={picked}
        source={source}
        path={path + '/' + picked}
        close={() => update({ bindingId: null })}
      />
    );
  }
  return (
    <>
      {/* 维持原型的来源与页签下方查询，再给出真实生效表格。 */}
      <div className="panel query-panel release-task-filters">
        <Form
          form={form}
          layout="inline"
          initialValues={{ versionKeyword: keyword, versionObject: object }}
          onFinish={(values) =>
            update({
              versionKeyword: values.versionKeyword || null,
              versionObject: values.versionObject || null,
              versionPageNum: 1,
            })
          }
        >
          <Form.Item name="versionKeyword">
            <Input aria-label="文档名称" placeholder="文档名称" allowClear />
          </Form.Item>
          <Form.Item name="versionObject">
            <Input aria-label="来源对象" placeholder="来源对象" allowClear />
          </Form.Item>
          <Space>
            <Button htmlType="submit">查询</Button>
            <Button onClick={reset}>重置</Button>
          </Space>
        </Form>
      </div>
      <section className="panel records-panel knowledge-records" aria-label="当前生效列表">
        <RecordTable<PublishedVersion>
          rowKey="id"
          tableLayout="fixed"
          flexibleColumns={[0]}
          dataSource={page.data?.items}
          loading={page.isPending}
          error={page.error}
          retry={() => void page.refetch()}
          emptyDescription="暂无当前生效版本"
          filtered={Boolean(keyword || object || scope)}
          onReset={reset}
          columns={[
            {
              title: '文档 / 来源对象',
              render: (_, row) => (
                <div className="knowledge-candidate-identity">
                  <Button
                    type="link"
                    className="knowledge-record-link"
                    onClick={() => update({ bindingId: row.id })}
                  >
                    {row.title}
                  </Button>
                  <small className="cell-text muted" title={row.objects.join('、')}>
                    {row.objects.join('、') || '—'}
                  </small>
                </div>
              ),
            },
            { title: '生效修订', width: 100, render: (_, row) => 'r' + row.revision },
            { title: '来源发布', width: 160, dataIndex: 'taskId', ellipsis: true },
            {
              title: '当前可用性',
              width: 136,
              render: (_, row) => <VersionAvailability version={row} />,
            },
            {
              title: '操作',
              width: 124,
              render: (_, row) => (
                <Button type="link" onClick={() => update({ bindingId: row.id })}>
                  查看版本
                </Button>
              ),
            },
          ]}
          pagination={{
            current: page.data?.pageNum ?? pageNum,
            pageSize,
            total: page.data?.total,
            onChange: (n, size) => update({ versionPageNum: n, versionPageSize: size }),
          }}
        />
      </section>
    </>
  );
}

/**
 * 在独立版本区查看可用性与当前、上一版正文。
 */
function VersionDetail({
  source,
  path,
  close,
}: {
  source: string;
  path: string;
  close: () => void;
}) {
  const navigate = useNavigate();
  const detail = useQuery({
    queryKey: ['release-binding-detail', path],
    queryFn: () => api<EffectiveDetail>(path),
  });
  const [reading, read] = useState<{ body: PublishedContent; title: string }>();
  const current = detail.data?.current;
  const previous = detail.data?.previous;
  const content = detail.data?.content;
  const previousContent = detail.data?.previousContent;
  return (
    <ReadState loading={detail.isPending} error={detail.error} retry={() => void detail.refetch()}>
      {current && (
        <article className="panel knowledge-effective-detail" aria-label="生效版本详情">
          {/* 保持原型的版本标题、来源批次及当前可用性。 */}
          <header className="knowledge-validation-heading">
            <h2>
              {current.title} · r{current.revision}
            </h2>
            <Space>
              <VersionAvailability version={current} />
              <Button onClick={close}>返回当前生效</Button>
            </Space>
          </header>
          <section className="knowledge-build-section" aria-label="正式版本">
            <h3>正式版本</h3>
            <div className="knowledge-validation-heading">
              <span>
                来源发布 {current.taskId} · {displayTime(current.switchedAt)}
              </span>
              <Space wrap>
                {content && (
                  <Button
                    type="primary"
                    onClick={() =>
                      read({ body: content, title: '正式正文 · r' + current.revision })
                    }
                  >
                    阅读正式正文
                  </Button>
                )}
                <Button
                  onClick={() =>
                    navigate('/releases/' + current.taskId + '?' + queryString({ source }))
                  }
                >
                  查看发布任务
                </Button>
              </Space>
            </div>
            <details>
              <summary>索引与治理依据</summary>
              <dl className="knowledge-publication-preview">
                <div>
                  <dt>原构建</dt>
                  <dd>{current.buildId}</dd>
                </div>
                <div>
                  <dt>向量配置修订</dt>
                  <dd>{current.embeddingRevisionId}</dd>
                </div>
                <div>
                  <dt>治理对象</dt>
                  <dd>{current.objects.join('、') || '—'}</dd>
                </div>
              </dl>
            </details>
          </section>
          <section className="knowledge-build-section" aria-label="直接上一版">
            <h3>直接上一版</h3>
            {previous ? (
              <div className="knowledge-validation-heading">
                <span>
                  r{previous.revision} · 发布 {previous.taskId}
                </span>
                {previousContent && (
                  <Button
                    onClick={() =>
                      read({ body: previousContent, title: '上一版正文 · r' + previous.revision })
                    }
                  >
                    查看上一版正文
                  </Button>
                )}
              </div>
            ) : (
              <span className="muted">首次发布</span>
            )}
          </section>
          {reading && (
            <Modal
              open
              centered
              width={800}
              title={reading.title}
              onCancel={() => read(undefined)}
              footer={<Button onClick={() => read(undefined)}>关闭</Button>}
            >
              {/* 原版本正文与章节完全从绑定修订读取。 */}
              <article className="knowledge-published-copy">
                <h3>{reading.body.title}</h3>
                <p>{reading.body.purpose}</p>
                <p>{reading.body.exclusions}</p>
                {reading.body.sections.map((section, index) => (
                  <section key={index}>
                    <h4>{section.title}</h4>
                    <p>{section.body}</p>
                  </section>
                ))}
              </article>
            </Modal>
          )}
        </article>
      )}
    </ReadState>
  );
}
