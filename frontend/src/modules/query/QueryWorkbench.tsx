import {
  ArrowUpOutlined,
  DatabaseOutlined,
  EditOutlined,
  LogoutOutlined,
  RightOutlined,
  SearchOutlined,
  StopOutlined,
  AppstoreOutlined,
  BellOutlined,
} from '@ant-design/icons';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, Button, Empty, Input, Select, Spin, type RefSelectProps } from 'antd';
import { useEffect, useId, useLayoutEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { api, ApiError, queryString } from '../../shared/api';
import { ActionMenuButton } from '../../shared/ActionMenuButton';
import type { Session } from '../iam/types';
import { downloadChart, downloadResult } from './download';
import { activeState, explaining } from './QueryAnswer';
import { FeedbackInbox } from './FeedbackNotices';
import { QueryIconButton } from './QueryIconButton';
import { HistoricalQueryTurn, QueryTurn, queryTurnOptions } from './QueryTurn';
import type { Conversation, Explanation, Snapshot, Source, Turn } from './types';
import styles from './query.module.css';

/** 轮次正文分批读取，长期会话不会一次加载全部结果表格。 */
const HISTORY_BATCH = 5;

/**
 * 提交阶段区分数据源准入与问题理解服务配置。
 */
const QUERY_SOURCE_NOT_ADMITTED = 409404;

/**
 * 已获准的来源仍可能缺少可用的问题理解服务。
 */
const QUERY_INTENT_UNAVAILABLE = 409405;

/** SSE 的同轮次已核验答复，其他状态继续用快照定位。 */
type QueryUpdate = Pick<Snapshot, 'id' | 'runVersion' | 'state'> & {
  /** 答复所属的解释修订。 */
  explanationRevision: number | null;
  /** 客户端只把完成状态应用到已有快照。 */
  explanationStatus: string | null;
  /** 服务端已核验的完整答复。 */
  explanationText: string | null;
};

/**
 * 输入区内一次操作失败的反馈。
 */
type QueryMessage = {
  /**
   * 发起操作的来源与轮次，切换后不展示原提示。
   */
  context: string;
  /**
   * 可直接显示的失败原因。
   */
  text: string;
  /**
   * 用于提供对应处理动作的业务码。
   */
  code?: number;
};

/**
 * 恢复本标签页中同一用户同一轮次尚未核实的命令编号。
 */
function readExplanationCommand(storageKey: string) {
  try {
    const command = sessionStorage.getItem(storageKey);
    return command && /^[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(command)
      ? command
      : null;
  } catch {
    // 浏览器禁用存储时，当前页面仍可从内存恢复。
    return null;
  }
}

/**
 * 只存命令编号。收到了受理回执后清除当前轮次的恢复记录。
 */
function saveExplanationCommand(storageKey: string, command?: string) {
  try {
    if (command) {
      // 记录与本轮接口请求相同的命令编号。
      sessionStorage.setItem(storageKey, command);
    } else {
      // 收到当前编号的确认后清理对应恢复记录。
      sessionStorage.removeItem(storageKey);
    }
  } catch {
    // 存储不可用时保留内存中的原编号。
  }
}

/**
 * 桌面问数工作台，使用独立工作区维持原型的历史、来源和对话层次。
 */
export function QueryWorkbench({
  session,
  department,
  onBack,
  onLogout,
  loggingOut,
}: {
  /** 当前登录态提供可操作的问数功能。 */
  session: Session;
  /** 来自登录用户本人的实际主部门。 */
  department?: string;
  /** 从账号菜单进入首个可访问的后台入口。 */
  onBack: () => void;
  /** 注销同一正式登录会话。 */
  onLogout: () => void;
  /** 避免重复提交退出。 */
  loggingOut: boolean;
}) {
  const [params, setParams] = useSearchParams();
  const cache = useQueryClient();
  const [input, setInput] = useState({ context: '', text: '' });
  const [search, setSearch] = useState('');
  const [showSearch, setShowSearch] = useState(false);
  const [showNotices, setShowNotices] = useState(false);
  const [sending, setSending] = useState(false);
  const [pendingQuestion, setPendingQuestion] = useState('');
  const [message, setMessage] = useState<QueryMessage>();
  const [sourcePickerOpen, setSourcePickerOpen] = useState(false);
  const [historyWindow, setHistoryWindow] = useState({ context: '', before: 0, after: 0 });
  const pending = useRef<{ signature: string; key: string } | null>(null);
  const explainCommands = useRef(new Map<string, { key: string; sending: boolean }>());
  const draftRef = useRef<HTMLTextAreaElement>(null);
  const sourcePickerRef = useRef<RefSelectProps>(null);
  const messageId = useId();
  const currentTurnRef = useRef<HTMLDivElement>(null);
  const anchoredTurn = useRef('');
  const mayView = session.permissionCodes.includes('query.view');
  const access = useQuery({
    queryKey: ['workbench-sources'],
    queryFn: () => api<Source[]>('/query/sources'),
    enabled: mayView,
    refetchInterval: 4000,
    refetchOnWindowFocus: 'always',
    retry: false,
  });
  const sources = access.isError ? [] : (access.data ?? []);
  const mayAsk = mayView && session.permissionCodes.includes('query.ask');
  const askSources = sources.filter((item) => mayAsk && !item.askBlockedCode);
  const requestedSource = params.get('source') ?? '';
  const id = params.get('request') ?? '';
  const source =
    requestedSource || (id ? sources[0]?.id : askSources[0]?.id) || sources[0]?.id || '';
  const selectedSource = sources.find((item) => item.id === source);
  const sourceAvailable = mayView && Boolean(selectedSource);
  const canAskSource = mayAsk && sourceAvailable && !selectedSource?.askBlockedCode;
  const context = `${source}/${id}`;
  const blockedCode = mayAsk ? selectedSource?.askBlockedCode : undefined;
  // 已知不能提交时提前说明原因，提交回包仍可处理期间变化和其他错误。
  const sourceMessage =
    blockedCode === QUERY_SOURCE_NOT_ADMITTED
      ? { text: '当前数据源未开通智能问数', code: blockedCode }
      : blockedCode === QUERY_INTENT_UNAVAILABLE
        ? { text: '问数服务暂不可用', code: blockedCode }
        : undefined;
  const visibleMessage = message?.context === context ? message : sourceMessage;
  const sourceNotAdmitted = visibleMessage?.code === QUERY_SOURCE_NOT_ADMITTED;
  const intentUnavailable = visibleMessage?.code === QUERY_INTENT_UNAVAILABLE;
  const canSwitchSource =
    sourceNotAdmitted && sourceAvailable && askSources.some((item) => item.id !== source);
  // 只有服务端明确提供提交资格时才标记分组，历史入口仍使用同一个选择器。
  const sourceOptions = sources.every((item) => item.askBlockedCode !== undefined)
    ? [
        {
          label: '可问数',
          options: askSources.map((item) => ({ label: item.name, value: item.id })),
        },
        {
          label: '仅回看历史',
          options: sources
            .filter((item) => !mayAsk || item.askBlockedCode)
            .map((item) => ({ label: item.name, value: item.id })),
        },
      ].filter((group) => group.options.length > 0)
    : sources.map((item) => ({ label: item.name, value: item.id }));
  const visibleContext = useRef<string | null>(context);
  useEffect(() => {
    if (!requestedSource && source && !id) {
      // 首次优先进入可问数来源，轮询更新时保留当前来源和输入。
      setParams(queryString({ source }), { replace: true });
    }
  }, [id, requestedSource, setParams, source]);
  useLayoutEffect(() => {
    // 浏览器回退及直接改址也会更新晚到回执的适用范围。
    visibleContext.current = context;
    return () => {
      // 原页面卸载后，未完成的请求只能核对原轮次，不能替新页面申请修订。
      visibleContext.current = null;
    };
  }, [context]);
  const draft = input.context === context ? input.text : '';

  // 历史和结果共享数据源资格，已知失权时立即清除旧答案显示。
  const history = useQuery({
    queryKey: ['workbench-history', source],
    queryFn: () => api<Conversation[]>('/query/conversations?' + queryString({ sourceId: source })),
    enabled: sourceAvailable,
    retry: false,
  });
  const detail = useQuery({
    ...queryTurnOptions(source, id),
    enabled: sourceAvailable && Boolean(id),
  });
  const row =
    sourceAvailable && !detail.isError && detail.data?.sourceId === source
      ? detail.data
      : undefined;
  const conversationId = row?.conversationId;
  const contextReady = !id || Boolean(row);
  const turns = useQuery({
    queryKey: ['workbench-turns', conversationId],
    queryFn: () => api<Turn[]>('/query/conversations/' + conversationId + '/requests'),
    enabled: sourceAvailable && Boolean(conversationId),
    retry: false,
  });
  const isActive = activeState(row?.state);
  const streamEligible = sourceAvailable && !detail.isError;
  const streamRevision = row?.explanation?.revision ?? 1;
  const waiting = row?.state === 'WAITING';

  useLayoutEffect(() => {
    if (!id) anchoredTurn.current = '';
    if (!row || turns.isPending || anchoredTurn.current === context) return;
    // 进入会话或选择一轮时定位一次，轮询及加载更早的答复不打断阅读。
    anchoredTurn.current = context;
    currentTurnRef.current?.scrollIntoView?.({ block: 'start', inline: 'nearest' });
  }, [context, id, row, turns.isPending]);

  // 由服务端终态关闭连接，详情先完成时仍保留最后一条正文事件。
  useEffect(() => {
    if (!streamEligible || !id || waiting || typeof EventSource === 'undefined') return;
    const stream = new EventSource('/api/v1/query/requests/' + id + '/events');
    const { queryKey } = queryTurnOptions(source, id);
    const read = () => {
      void cache.invalidateQueries({ queryKey, exact: true });
    };
    /** 只把正文应用到已核验的同源、同代次、同修订快照，避免旧消息恢复失权内容。 */
    const update = (event: Event) => {
      if (visibleContext.current !== context) return;
      if (event instanceof MessageEvent && typeof event.data === 'string') {
        let next: QueryUpdate | null = null;
        try {
          next = JSON.parse(event.data) as QueryUpdate | null;
        } catch {
          // 事件无法解析时仍可从详情恢复。
        }
        const current = cache.getQueryState(queryKey);
        const snapshot = current?.data;
        if (
          next?.id === id &&
          typeof next.state === 'string' &&
          (!snapshot || next.runVersion >= snapshot.runVersion) &&
          (next.explanationRevision ?? 1) >= streamRevision &&
          !activeState(next.state) &&
          next.explanationStatus !== 'QUEUED' &&
          next.explanationStatus !== 'GENERATING'
        ) {
          // 原生 EventSource 会自动重连，正常终态应主动停止。
          stream.close();
        }
        const text = next?.explanationText;
        if (
          next?.id === id &&
          next.explanationStatus === 'COMPLETED' &&
          typeof text === 'string' &&
          text.trim() &&
          current?.status === 'success' &&
          snapshot?.result &&
          snapshot.sourceId === source &&
          snapshot.runVersion === next.runVersion &&
          snapshot.explanation &&
          snapshot.explanation.revision === next.explanationRevision &&
          explaining(snapshot)
        ) {
          // 丢弃事件前发出的旧读取，避免它覆盖已收到的完成状态。
          void cache.cancelQueries({ queryKey, exact: true }, { revert: false });
          cache.setQueryData(queryKey, {
            ...snapshot,
            explanation: { ...snapshot.explanation, status: 'COMPLETED', text },
          });
          return;
        }
      }
      read();
    };
    stream.addEventListener('query.updated', update);
    stream.addEventListener('error', read);
    return () => stream.close();
  }, [cache, context, id, source, streamEligible, streamRevision, waiting]);

  /**
   * 切换源或轮次，保留独立 URL 以便刷新回看。
   */
  function open(nextSource: string, request?: string) {
    visibleContext.current = `${nextSource}/${request ?? ''}`;
    setMessage(undefined);
    setPendingQuestion('');
    setParams(queryString({ source: nextSource, request }));
  }

  /**
   * 将操作失败留在发起它的页面上下文，保留服务端的具体原因。
   */
  function showFailure(error: unknown, fallback: string) {
    if (visibleContext.current === context) {
      // 迟到的失败不覆盖其他数据源或会话里的提示。
      setMessage({
        context,
        text: error instanceof Error ? error.message : fallback,
        code: error instanceof ApiError ? error.code : undefined,
      });
    }
  }

  /**
   * 原命令回包不明时，当前相同内容再次提交仍使用同一业务编号。
   */
  async function send(forceNew = false) {
    const question = forceNew ? row?.question : draft.trim();
    if (!question || !canAskSource || !contextReady || sending || isActive) return;
    const body =
      waiting && !forceNew
        ? { answer: question }
        : {
            sourceId: source,
            question,
            previousId: row?.id,
            conversationId,
            inheritContext: !forceNew,
          };
    const path =
      waiting && !forceNew ? '/query/requests/' + id + '/clarifications' : '/query/requests';
    const signature = path + JSON.stringify(body);
    if (pending.current?.signature !== signature) {
      pending.current = { signature, key: crypto.randomUUID() };
    }
    setMessage(undefined);
    setSending(true);
    setPendingQuestion(waiting && !forceNew ? '' : question);
    try {
      const accepted = await api<{ id: string }>(path, {
        method: 'POST',
        command: pending.current.key,
        body,
      });
      pending.current = null;
      // 已提交仍要刷新列表，只有发送方仍在原轮次时才跟随回执。
      if (visibleContext.current === context) {
        setInput({ context: '', text: '' });
        open(source, accepted.id);
      }
      await cache.invalidateQueries({ queryKey: ['workbench-history', source] });
      await cache.invalidateQueries({ queryKey: ['workbench-turns', conversationId] });
      await cache.invalidateQueries({ queryKey: ['workbench-request', source, accepted.id] });
    } catch (error) {
      showFailure(error, '暂时无法提交，请重试。');
      if (error instanceof ApiError && error.code >= 400000 && error.code < 500000) {
        pending.current = null;
      }
    } finally {
      setSending(false);
      setPendingQuestion('');
    }
  }

  /**
   * 请求停止后回读实际进度，服务端最终决定是否已停止。
   */
  async function stop() {
    try {
      await api('/query/requests/' + id + '/cancel', { method: 'POST' });
      await detail.refetch();
    } catch (error) {
      showFailure(error, '暂时无法确认停止。');
    }
  }

  /**
   * 先确认回包不明的说明命令，必要时为本次点击提交新修订。
   */
  async function explainAgain() {
    const visibleRevision = row?.explanation?.revision;
    const storageKey = `dataocean:query-explanation:${session.userId}:${id}`;
    const pendingCommand = explainCommands.current.get(storageKey);
    if (!visibleRevision || pendingCommand?.sending) {
      // 说明修订尚未读取或该轮已有请求时，等待当前动作完成。
      return;
    }

    // 每个轮次各自保留未核实的命令，切换到其他轮次仍可操作。
    const command = pendingCommand ?? {
      key: readExplanationCommand(storageKey) ?? crypto.randomUUID(),
      sending: false,
    };
    explainCommands.current.set(storageKey, command);
    const path = '/query/requests/' + id + '/explanations';
    let confirmed = false;
    command.sending = true;
    saveExplanationCommand(storageKey, command.key);
    setMessage(undefined);
    try {
      // 服务端返回实际受理修订，并用当前最新状态判断能否再生成。
      const receipt = await api<Explanation>(path, {
        method: 'POST',
        command: command.key,
      });
      const storedCommand = readExplanationCommand(storageKey);
      if (
        receipt.revision <= visibleRevision &&
        !receipt.blockedReason &&
        visibleContext.current === context &&
        (storedCommand === null || storedCommand === command.key)
      ) {
        // 已看到这份旧修订的结果，本次点击需要新编号。此处也保留未确认的新编号。
        command.key = crypto.randomUUID();
        saveExplanationCommand(storageKey, command.key);
        await api<Explanation>(path, { method: 'POST', command: command.key });
      }
      confirmed = true;
    } catch (error) {
      showFailure(error, '暂时无法重试说明。');
    } finally {
      // 用原轮次的快照刷新已受理的进度，回读期间仍阻止重复点击。
      await cache.invalidateQueries({
        queryKey: queryTurnOptions(source, id).queryKey,
        exact: true,
      });
      if (confirmed) {
        // 仅清理本轮已确认的命令。
        explainCommands.current.delete(storageKey);
        if (readExplanationCommand(storageKey) === command.key) {
          // 迟到的回执不能清理重新挂载后写入的其他命令。
          saveExplanationCommand(storageKey);
        }
      }
      command.sending = false;
    }
  }

  /**
   * 重新获取可见数据后下载，失权时同步撤下当前结果。
   */
  async function exportAnswer(request: string, chart = false) {
    try {
      await (chart ? downloadChart(request) : downloadResult(request));
    } catch (error) {
      showFailure(error, '暂时无法导出本次结果。');
      await cache.invalidateQueries({
        queryKey: ['workbench-request', source, request],
        exact: true,
      });
    }
  }

  // 结果状态变动后历史摘要同步，点击历史不重复提交。
  const state = row?.state;
  useEffect(() => {
    if (state && !activeState(state)) {
      void cache.invalidateQueries({ queryKey: ['workbench-history', source] });
    }
  }, [cache, source, state]);

  const visibleConversations = (history.isError ? [] : (history.data ?? [])).filter(
    (conversation) =>
      conversation.title.toLocaleLowerCase().includes(search.trim().toLocaleLowerCase()),
  );
  const day = new Date().toDateString();
  const conversationGroups = [
    {
      label: '今天',
      items: visibleConversations.filter((item) => new Date(item.updatedAt).toDateString() === day),
    },
    {
      label: '最近',
      items: visibleConversations.filter((item) => new Date(item.updatedAt).toDateString() !== day),
    },
  ];
  const title = row
    ? history.data?.find((item) => item.id === conversationId)?.title || row.question
    : '';
  // 轮次接口按倒序返回，从选中轮次的两侧分批读取，再按时间正序呈现。
  const conversationTurns = !turns.isError && sourceAvailable ? (turns.data ?? []) : [];
  const currentTurnIndex = conversationTurns.findIndex((turn) => turn.id === id);
  const loadedWindow =
    historyWindow.context === context ? historyWindow : { context, before: 0, after: 0 };
  const previous = currentTurnIndex < 0 ? [] : conversationTurns.slice(currentTurnIndex + 1);
  const following = currentTurnIndex < 0 ? [] : conversationTurns.slice(0, currentTurnIndex);
  const earlierTurns = previous.slice(0, HISTORY_BATCH + loadedWindow.before).reverse();
  const laterTurns = following.slice(-(HISTORY_BATCH + loadedWindow.after)).reverse();

  /** 历史问答使用自己的原轮次编号查看、跳转和导出。 */
  function renderHistory(items: Turn[]) {
    if (!conversationId) return null;
    return items.map(({ id: request }) => (
      <HistoricalQueryTurn
        key={request}
        id={request}
        source={source}
        conversationId={conversationId}
        onSelect={() => open(source, request)}
        onDownload={() => void exportAnswer(request)}
        onDownloadChart={() => void exportAnswer(request, true)}
      />
    ));
  }

  /** 同一选中轮次内追加历史，切换会话后从附近轮次开始。 */
  function moreHistory(side: 'before' | 'after') {
    setHistoryWindow({ ...loadedWindow, [side]: loadedWindow[side] + HISTORY_BATCH });
  }
  const noAccess = mayView && !access.isPending && !access.isError && !sourceAvailable;
  const unavailableApi = access.error instanceof ApiError && access.error.code === 404001;
  const inaccessible =
    detail.error instanceof ApiError && detail.error.code >= 403000 && detail.error.code < 404000;

  return (
    <div className={styles.workspace}>
      {/* 会话导航沿用既有问数原型，无全局样式覆盖。 */}
      <aside className={styles.sidebar} aria-label="问数会话">
        <div className={styles.brand}>
          <img className={styles.brandMark} src="/dataocean-mark.png" alt="" />
          DataOcean
        </div>
        <Button
          block
          className={styles.newQuestion}
          icon={<EditOutlined aria-hidden />}
          disabled={!canAskSource || isActive || sending}
          onClick={() => {
            open(source);
            draftRef.current?.focus();
          }}
        >
          新建问数
        </Button>
        <Button
          block
          type="text"
          className={styles.finder}
          icon={<SearchOutlined aria-hidden />}
          aria-expanded={showSearch}
          onClick={() => setShowSearch(!showSearch)}
        >
          搜索对话
        </Button>
        {showSearch && (
          <Input
            value={search}
            onChange={(event) => setSearch(event.target.value)}
            allowClear
            placeholder="按问题搜索"
            aria-label="按问题搜索"
            autoFocus
          />
        )}
        <nav className={styles.history} aria-label="最近的问数">
          {sourceAvailable &&
            conversationGroups.map(
              ({ label, items }) =>
                items.length > 0 && (
                  <div key={label} className={styles.historyGroup}>
                    <span className={styles.historyHeading}>{label}</span>
                    {items.map((conversation) => (
                      <button
                        className={styles.historyItem}
                        type="button"
                        key={conversation.id}
                        aria-current={conversationId === conversation.id ? 'page' : undefined}
                        title={conversation.title}
                        onClick={() => open(source, conversation.latestRequestId)}
                      >
                        {conversation.title}
                      </button>
                    ))}
                  </div>
                ),
            )}
        </nav>
        <div className={styles.profile}>
          <ActionMenuButton
            placement="topLeft"
            type="text"
            className={styles.accountMenu}
            label={[session.displayName, department, '用户菜单'].filter(Boolean).join('，')}
            menu={{
              items: [
                { key: 'backend', label: '后台管理', icon: <AppstoreOutlined aria-hidden /> },
                {
                  key: 'logout',
                  label: loggingOut ? '正在退出…' : '退出登录',
                  disabled: loggingOut,
                  icon: <LogoutOutlined aria-hidden />,
                },
              ],
              onClick: ({ key }) => {
                if (key === 'backend') onBack();
                if (key === 'logout') onLogout();
              },
            }}
          >
            <span className={styles.avatar} aria-hidden>
              {session.displayName.slice(0, 1)}
            </span>
            <span className={styles.profileName}>
              <strong>{session.displayName}</strong>
              {department && <small>{department}</small>}
            </span>
          </ActionMenuButton>
        </div>
      </aside>
      {showNotices && (
        <FeedbackInbox
          close={() => setShowNotices(false)}
          open={(nextSource, request) => {
            setShowNotices(false);
            open(nextSource, request);
          }}
        />
      )}
      <main className={styles.main}>
        {/* 来源始终位于对话之外，在途时不会切换实际请求的来源。 */}
        <header className={styles.header}>
          <div className={styles.threadTitle}>
            <h1>问数工作台</h1>
            {title && (
              <>
                <RightOutlined aria-hidden />
                <strong title={title}>{title}</strong>
              </>
            )}
          </div>
          <div className={styles.sourcePicker}>
            <Select<string>
              ref={sourcePickerRef}
              aria-label="数据源"
              className={styles.source}
              placeholder="选择数据源"
              value={sourceAvailable ? source : undefined}
              loading={mayView && access.isPending}
              disabled={!mayView || sending || isActive}
              popupMatchSelectWidth={260}
              open={sourcePickerOpen}
              onOpenChange={setSourcePickerOpen}
              options={sourceOptions}
              onChange={(value) => {
                // 用户明确选择后才切换来源和对话上下文。
                setSourcePickerOpen(false);
                open(value);
              }}
            />
            {/* 独立的站内回告放在顶栏最右，未选数据源或失权时仍可打开。 */}
            <QueryIconButton
              className={styles.noticeTrigger}
              label="反馈回告"
              icon={<BellOutlined aria-hidden />}
              aria-haspopup="dialog"
              onClick={() => setShowNotices(true)}
            />
          </div>
        </header>
        <section className={styles.transcript} aria-label="当前问数" tabIndex={0}>
          <div className={styles.thread}>
            {/* 首次读取时在正文可用空间内提示，侧栏与输入区继续由原布局承接。 */}
            {((mayView && access.isPending) || (sourceAvailable && id && detail.isPending)) && (
              <div className={styles.threadLoading} role="status">
                <div className={styles.threadLoadingIcon} aria-hidden="true">
                  <Spin size="medium" />
                </div>
                {access.isPending ? '正在读取数据源' : '正在读取对话'}
              </div>
            )}
            {!mayView && (
              <div className={styles.welcome}>
                <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前账号暂无问数权限。" />
              </div>
            )}
            {mayView && access.isError && (
              <Alert
                className={styles.sourceError}
                type="error"
                showIcon
                title={unavailableApi ? '当前服务尚未接入问数' : '暂时无法核对数据源'}
                action={
                  <Button size="small" onClick={() => void access.refetch()}>
                    重新读取
                  </Button>
                }
              />
            )}
            {noAccess && (
              <div className={styles.welcome}>
                <Empty
                  image={Empty.PRESENTED_IMAGE_SIMPLE}
                  description={
                    access.data?.length
                      ? '当前数据源已不可访问，请选择其他数据源。'
                      : '当前没有可用的数据源。'
                  }
                />
              </div>
            )}
            {sourceAvailable && !id && !pendingQuestion && (
              <div className={styles.welcomePrompt}>
                <span className={styles.welcomeIcon} aria-hidden>
                  <DatabaseOutlined />
                </span>
                <h2>{canAskSource ? '想查什么数据？' : '查看历史问数'}</h2>
              </div>
            )}
            {row && turns.isError && (
              <Button
                className={styles.historyMore}
                size="small"
                onClick={() => void turns.refetch()}
              >
                重试读取同一对话的历史
              </Button>
            )}
            {row && previous.length > earlierTurns.length && (
              <Button
                className={styles.historyMore}
                size="small"
                onClick={() => moreHistory('before')}
              >
                查看更早问答
              </Button>
            )}
            {row && renderHistory(earlierTurns)}
            {row && (
              <div ref={currentTurnRef} className={styles.currentTurn}>
                <QueryTurn
                  key={row.id}
                  row={row}
                  onDownload={() => void exportAnswer(row.id)}
                  onDownloadChart={() => void exportAnswer(row.id, true)}
                  onRequery={canAskSource && !waiting ? () => void send(true) : undefined}
                  onExplainAgain={mayAsk ? () => void explainAgain() : undefined}
                  onStopExplanation={mayAsk ? () => void stop() : undefined}
                />
              </div>
            )}
            {sourceAvailable && !row && pendingQuestion && (
              <p className={styles.question}>{pendingQuestion}</p>
            )}
            {row && renderHistory(laterTurns)}
            {row && following.length > laterTurns.length && (
              <Button
                className={styles.historyMore}
                size="small"
                onClick={() => moreHistory('after')}
              >
                查看更多后续问答
              </Button>
            )}
            {sourceAvailable && detail.isError && (
              <Alert
                showIcon
                type={inaccessible ? 'warning' : 'error'}
                title={inaccessible ? '当前权限不足，无法读取该轮问数。' : '暂时无法读取该轮问数。'}
                action={
                  inaccessible ? undefined : (
                    <Button size="small" onClick={() => void detail.refetch()}>
                      重试
                    </Button>
                  )
                }
              />
            )}
          </div>
        </section>
        <footer className={styles.footer}>
          {/* 操作提示与输入框对齐，独立于输入和历史回答。 */}
          {visibleMessage && (
            <Alert
              id={messageId}
              className={styles.composerNotice}
              type={sourceNotAdmitted || intentUnavailable ? 'warning' : 'error'}
              showIcon
              title={visibleMessage.text}
              description={
                sourceNotAdmitted
                  ? '如需使用此数据源，请联系管理员开通。'
                  : intentUnavailable
                    ? '请联系管理员检查问数服务与模型配置。'
                    : undefined
              }
              action={
                canSwitchSource && !sending && !isActive ? (
                  <Button
                    size="small"
                    onClick={() => {
                      // 展开既有来源选择器，由用户决定是否切换。
                      sourcePickerRef.current?.focus();
                      setSourcePickerOpen(true);
                    }}
                  >
                    切换数据源
                  </Button>
                ) : undefined
              }
            />
          )}
          <form
            className={styles.composer}
            onSubmit={(event) => {
              event.preventDefault();
              void send();
            }}
          >
            {/* 同一输入框承接新问题和澄清，Enter 提交，Shift+Enter 换行。 */}
            <div className={styles.composerRow}>
              <Input.TextArea
                className={styles.composerInput}
                ref={(instance) => {
                  draftRef.current = instance?.resizableTextArea?.textArea ?? null;
                }}
                aria-label={waiting ? '补充条件' : '输入问题'}
                aria-describedby={visibleMessage ? messageId : undefined}
                value={draft}
                maxLength={600}
                variant="borderless"
                autoSize={{ minRows: 1, maxRows: 5 }}
                placeholder={
                  sourceAvailable && !canAskSource
                    ? '当前来源仅可回看历史'
                    : waiting
                      ? '在这里补充条件…'
                      : '输入你想查的问题…'
                }
                disabled={!canAskSource || !contextReady || sending || isActive}
                onChange={(event) => setInput({ context, text: event.target.value })}
                onKeyDown={(event) => {
                  if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
                    event.preventDefault();
                    void send();
                  }
                }}
              />
              {isActive ? (
                <Button
                  className={styles.composerAction}
                  shape="circle"
                  htmlType="button"
                  aria-label="停止问数"
                  title="停止问数"
                  icon={<StopOutlined />}
                  onClick={() => void stop()}
                  disabled={row?.cancelRequested}
                />
              ) : (
                <Button
                  className={styles.composerAction}
                  shape="circle"
                  type="primary"
                  htmlType="submit"
                  icon={<ArrowUpOutlined />}
                  aria-label="发送问题"
                  title="发送问题"
                  loading={sending}
                  disabled={!canAskSource || !contextReady || !draft.trim()}
                />
              )}
            </div>
          </form>
        </footer>
      </main>
    </div>
  );
}
