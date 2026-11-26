import { Alert, Button, Modal } from 'antd';
import { useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { api, queryString } from '../../shared/api';
import { displayTime, ReadState } from '../../shared/Records';
import { allowed, useSession } from '../../shared/session';
import { useSourceSelection } from '../datasource/CollectionPage';
import { KnowledgeRecoveryWorkspace } from './KnowledgeRecoveryWorkspace';
import { KnowledgeWithdrawalHistory } from './KnowledgeWithdrawal';
import { RollbackHistory } from './KnowledgeBatchActions';
import { VersionAvailability, type PublishedVersion } from './ReleasePublicationPanel';
import type { EffectiveVersionOrigin, ReleaseRevisionReturn } from './types';
import {
  useVersionUse,
  VersionControlActions,
  VersionPauseSummary,
  VersionRecoverySummary,
} from './VersionUseControls';
import './knowledge.css';

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
 * 独立版本页沿用原型的详情页头、阅读区和底部操作。
 */
export function KnowledgeEffectiveVersionPage() {
  const { id } = useParams();
  const { params, setParams, sources } = useSourceSelection('KNOWLEDGE_RELEASES');
  const session = useSession();
  const location = useLocation();
  const navigate = useNavigate();
  const source = params.get('source');
  const reading = params.get('read') === 'body';
  const recoveryRun = params.get('recovery');
  const origin = location.state as EffectiveVersionOrigin | null;
  const fromTask = origin?.publishedVersionReturn;
  const restoreFocus = origin?.effectiveVersionFocus;
  const sourceName = sources.data?.find((item) => item.id === source)?.name;
  const [previousOpen, setPreviousOpen] = useState(false);
  const contentRef = useRef<HTMLDivElement>(null);
  const path = '/knowledge/sources/' + source + '/bindings/' + id;
  const detail = useQuery({
    queryKey: ['release-binding-detail', path],
    enabled: Boolean(source && id),
    queryFn: () => api<EffectiveDetail>(path),
  });
  const current = detail.data?.current;
  const previous = detail.data?.previous;
  const content = detail.data?.content;
  const previousContent = detail.data?.previousContent;
  const controls = useVersionUse(path, Boolean(content && !reading));
  const bindingId = current?.id;
  const returnedFromReading = useRef(false);

  useEffect(() => {
    // 变更阅读面时从首屏开始，版本数据刷新不会抢走已有焦点。
    if (bindingId && !recoveryRun) {
      if (contentRef.current) {
        contentRef.current.scrollTop = 0;
      }
      const focus = reading
        ? 'effective-version-title'
        : returnedFromReading.current
          ? 'read-formal-version'
          : (restoreFocus ?? 'effective-version-title');
      document.getElementById(focus)?.focus({ preventScroll: true });
      returnedFromReading.current = reading;
    }
  }, [bindingId, reading, recoveryRun, restoreFocus]);

  /**
   * 优先回原任务或原筛选列表，列表焦点恢复到对应版本操作。
   */
  function back() {
    if (fromTask) {
      navigate('/releases/' + fromTask.taskId + fromTask.search, {
        state: { releaseFocus: 'release-current-version' },
      });
      return;
    }
    const search = new URLSearchParams(params);
    search.delete('read');
    search.delete('recovery');
    search.set('tab', 'active');
    navigate(
      { pathname: '/releases', search: search.toString() },
      {
        state: {
          effectiveVersionFocus: 'release-version-' + id,
          effectiveVersionPosition: origin?.effectiveVersionPosition,
        },
      },
    );
  }

  /**
   * 正式正文是同一绑定下的独立阅读面，来源及返回内容不变。
   */
  function readCurrent() {
    const search = new URLSearchParams(params);
    search.set('read', 'body');
    setParams(search, { state: location.state });
  }

  /** 恢复验证可以独立打开和刷新，返回仍沿用原版本上下文。 */
  function readRecovery(id?: string) {
    const search = new URLSearchParams(params);
    if (id) {
      search.set('recovery', id);
    } else {
      search.delete('recovery');
    }
    setParams(search, { state: location.state });
  }

  /**
   * 上一版仍以只读弹窗回看，关闭后归还焦点。
   */
  function closePrevious() {
    setPreviousOpen(false);
    requestAnimationFrame(() => document.getElementById('read-previous-version')?.focus());
  }

  /** 查看该绑定冻结的文档修订，返回时保留当前版本页的来源上下文。 */
  function openRevision(version: PublishedVersion) {
    const releaseReturn: ReleaseRevisionReturn = {
      search: location.search,
      focus: 'effective-version-revision',
      version: { bindingId: version.id, origin },
    };
    navigate(
      '/knowledge/' +
        version.documentId +
        '?' +
        queryString({ source, revisionId: version.documentRevisionId }),
      { state: { releaseReturn } },
    );
  }

  return (
    <main className="record-page" aria-label="生效版本">
      {!source || !id ? (
        <Alert
          type="warning"
          title="请从当前生效列表选择版本"
          action={<Button onClick={back}>返回当前生效</Button>}
        />
      ) : (
        <ReadState
          loading={detail.isPending}
          error={detail.error}
          retry={() => void detail.refetch()}
        >
          {current && source && recoveryRun && !reading ? (
            <KnowledgeRecoveryWorkspace
              key={recoveryRun}
              source={source}
              sourceName={sourceName}
              path={path}
              title={current.title}
              revision={current.revision}
              runId={recoveryRun}
              data={controls.data}
              back={() => readRecovery()}
              openRun={readRecovery}
            />
          ) : (
            current && (
              <article
                className="panel knowledge-build-workspace knowledge-version-workspace"
                aria-label="生效版本详情"
              >
                {/* 和发布任务共用页头、阅读轴与固定底栏。 */}
                <header className="knowledge-build-heading">
                  <div className="knowledge-build-identity">
                    <h1 id="effective-version-title" tabIndex={-1}>
                      {current.title} ·{' '}
                      {reading
                        ? '正式正文 '
                        : current.availability === 'SUPERSEDED'
                          ? '历史 '
                          : '当前 '}
                      r{current.revision}
                    </h1>
                    {sourceName && <small>{sourceName}</small>}
                  </div>
                  <div className="knowledge-build-header-actions">
                    <VersionAvailability version={current} />
                  </div>
                </header>
                <div ref={contentRef} className="knowledge-build-content knowledge-version-content">
                  {reading ? (
                    content ? (
                      <VersionCopy body={content} />
                    ) : (
                      <Alert type="warning" title="当前账号无法查看该版本正文" />
                    )
                  ) : (
                    <>
                      {/* 首屏优先展示正文入口、发布批次和直接上一版。 */}
                      <section className="knowledge-build-section" aria-label="正式版本">
                        <h2>正式版本</h2>
                        {content?.purpose && <p>{content.purpose}</p>}
                        <span className="muted">
                          来源发布 {current.taskId} · {displayTime(current.switchedAt)}
                        </span>
                        {content && (
                          <div>
                            <Button id="read-formal-version" type="primary" onClick={readCurrent}>
                              阅读正式正文
                            </Button>
                          </div>
                        )}
                      </section>
                      <section className="knowledge-build-section" aria-label="索引与治理依据">
                        <h2>索引与治理依据</h2>
                        <dl className="knowledge-publication-preview">
                          <div>
                            <dt>治理对象</dt>
                            <dd>{current.objects.join('、') || '—'}</dd>
                          </div>
                          <div>
                            <dt>构建代次</dt>
                            <dd>{current.buildId}</dd>
                          </div>
                          <div>
                            <dt>向量配置修订</dt>
                            <dd>{current.embeddingRevisionId}</dd>
                          </div>
                        </dl>
                      </section>
                      {controls.data && (
                        <VersionPauseSummary title={current.title} data={controls.data} />
                      )}
                      {content && source && <RollbackHistory path={path} source={source} />}
                      {content && current.availability === 'WITHDRAWN' && (
                        <KnowledgeWithdrawalHistory path={path} />
                      )}
                      <section className="knowledge-build-section" aria-label="直接上一版">
                        <h2>直接上一版</h2>
                        {previous ? (
                          <>
                            <span>
                              r{previous.revision} · 发布 {previous.taskId}
                            </span>
                            {previousContent && (
                              <div>
                                <Button
                                  id="read-previous-version"
                                  onClick={() => setPreviousOpen(true)}
                                >
                                  查看上一版正文
                                </Button>
                              </div>
                            )}
                          </>
                        ) : (
                          <span className="muted">首次发布</span>
                        )}
                      </section>
                      {controls.data && (
                        <VersionRecoverySummary
                          data={controls.data}
                          bindingId={current.id}
                          openRun={readRecovery}
                        />
                      )}
                      {content && controls.error && (
                        <Alert
                          type="warning"
                          showIcon
                          title={controls.error.message}
                          action={<Button onClick={() => void controls.refetch()}>重试</Button>}
                        />
                      )}
                    </>
                  )}
                </div>
                {!reading && (
                  <footer className="knowledge-actions knowledge-build-actions">
                    <VersionControlActions
                      title={current.title}
                      path={path}
                      batch={current.batchId}
                      source={source ?? undefined}
                      data={controls.data}
                      available={Boolean(
                        current.verifiedAt &&
                        !['SUPERSEDED', 'WITHDRAWN'].includes(current.availability),
                      )}
                      openRun={readRecovery}
                    >
                      <Button
                        onClick={() =>
                          navigate('/releases/' + current.taskId + '?' + queryString({ source }))
                        }
                      >
                        查看发布任务
                      </Button>
                      {allowed(session, 'knowledge.view') && (
                        <Button
                          id="effective-version-revision"
                          color="primary"
                          variant="outlined"
                          onClick={() => openRevision(current)}
                        >
                          查看文档修订
                        </Button>
                      )}
                    </VersionControlActions>
                  </footer>
                )}
                {previousOpen && previous && previousContent && (
                  <Modal
                    open
                    centered
                    width={800}
                    title={'上一版正文 · r' + previous.revision}
                    onCancel={closePrevious}
                    footer={<Button onClick={closePrevious}>关闭</Button>}
                  >
                    <VersionCopy body={previousContent} />
                  </Modal>
                )}
              </article>
            )
          )}
        </ReadState>
      )}
    </main>
  );
}

/**
 * 正式和上一版共用同一只读正文排版。
 */
function VersionCopy({ body }: { body: PublishedContent }) {
  return (
    <article className="knowledge-published-copy" aria-label="正式知识正文">
      <h2>{body.title}</h2>
      <p>{body.purpose}</p>
      {body.exclusions && <p>{body.exclusions}</p>}
      {body.sections.map((section, index) => (
        <section key={index}>
          <h3>{section.title}</h3>
          <p>{section.body}</p>
        </section>
      ))}
    </article>
  );
}
