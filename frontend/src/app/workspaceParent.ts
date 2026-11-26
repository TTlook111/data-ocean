import { queryString } from '../shared/api';
import type { EffectiveVersionOrigin, ReleaseRevisionReturn } from '../modules/knowledge/types';

/**
 * 详情的上级沿用原入口、筛选和焦点，独立打开时使用可确定的模块列表。
 */
export function workspaceParent(
  route: string | undefined,
  location: { pathname: string; search: string; state: unknown },
  permissions: string[],
): { label: string; current: string; url: string; state?: object } | undefined {
  const params = new URLSearchParams(location.search);
  const id = location.pathname.split('/').at(-1);
  const origin = location.state as
    | ({
        listSearch?: string;
        catalogSearch?: string;
        catalogRow?: string;
        releaseReturn?: ReleaseRevisionReturn;
        feedbackOverviewSearch?: string;
        governanceOverviewSearch?: string;
        knowledgeOverviewSearch?: string;
      } & EffectiveVersionOrigin)
    | null;
  // 仅剔除详情专用参数，避免回列表时丢失源、查询和分页。
  function searchWithout(...keys: string[]) {
    const search = new URLSearchParams(params);
    keys.forEach((key) => search.delete(key));
    return search.size ? '?' + search.toString() : '';
  }

  if (route === '/catalog/:id') {
    return {
      label: '元数据目录',
      current: '对象结构',
      url: '/catalog' + searchWithout('snapshotId', 'observation'),
      state: { catalogReturnTo: origin?.catalogRow ?? id },
    };
  }
  if (route === '/feedback/:id') {
    if (
      origin?.feedbackOverviewSearch?.startsWith('?') &&
      permissions.includes('feedback-dashboard.view')
    ) {
      // 从统计下钻进入时回到同一指标、来源、日期和页码。
      return {
        label: '反馈概览',
        current: '事项 #' + id,
        url: '/feedback-dashboard' + origin.feedbackOverviewSearch,
      };
    }
    // 与详情内的底部返回保持同一筛选及事项焦点。
    return {
      label: '问题队列与处理',
      current: '事项 #' + id,
      url: '/feedback' + location.search,
      state: { feedbackFocus: id },
    };
  }
  if (route === '/governance/:id') {
    if (
      origin?.governanceOverviewSearch?.startsWith('?') &&
      permissions.includes('governance-dashboard.view')
    ) {
      // 下钻返回时恢复原截止时间、指标和来源。
      return {
        label: '治理概览',
        current: '表治理',
        url: '/governance-dashboard' + origin.governanceOverviewSearch,
      };
    }
    if (origin?.catalogSearch && permissions.includes('catalog.view')) {
      return {
        label: '元数据目录',
        current: '表治理',
        url: '/catalog' + origin.catalogSearch,
        state: { catalogReturnTo: origin.catalogRow },
      };
    }
    return {
      label: '治理工作台',
      current: '表治理',
      url:
        '/governance' +
        (origin?.listSearch ??
          searchWithout('entry', 'section', 'field', 'candidate', 'candidatePage')),
      state: { returnObject: id },
    };
  }
  if (
    (route === '/knowledge/:id' || route === '/releases/:id') &&
    origin?.knowledgeOverviewSearch?.startsWith('?') &&
    permissions.includes('knowledge-dashboard.view')
  ) {
    // 原修订和验证任务回到同一份期末或期间对账。
    return {
      label: '知识概览',
      current: route === '/knowledge/:id' ? '文档修订' : '发布任务',
      url: '/knowledge-dashboard' + origin.knowledgeOverviewSearch,
    };
  }
  if (route === '/knowledge/plans/:id') {
    // 规划完成或返回时沿用同一来源和原列表筛选。
    return {
      label: '知识文档',
      current: id === 'new' ? '文档规划' : '生成前确认',
      url:
        '/knowledge' + (origin?.listSearch ?? '?' + queryString({ source: params.get('source') })),
    };
  }
  if (route === '/knowledge/:id') {
    const from = origin?.releaseReturn;
    if (from && permissions.includes('releases.view')) {
      return {
        label: from.version ? '生效版本' : from.taskId ? '发布任务' : '发布与版本',
        current: '文档修订',
        url:
          '/releases' +
          (from.version
            ? '/versions/' + from.version.bindingId
            : from.taskId
              ? '/' + from.taskId
              : '') +
          from.search,
        state: from.version
          ? { ...from.version.origin, effectiveVersionFocus: from.focus }
          : { releaseFocus: from.focus },
      };
    }
    return {
      label: '知识文档',
      current: id === 'new' ? '新增文档' : '文档详情',
      url:
        '/knowledge' + (origin?.listSearch ?? '?' + queryString({ source: params.get('source') })),
      state: { focusDocument: id },
    };
  }
  const test = params.get('testId');
  if (route === '/test-cases' && test) {
    return {
      label: '标准题单',
      current: test === 'new' ? '新增标准题' : '标准题详情',
      url: '/test-cases' + searchWithout('testId', 'queuePageNum'),
      state: { ...(location.state as object | null), focusTest: test },
    };
  }
  if (route === '/releases/:id') {
    return {
      label: '发布与版本',
      current: '发布任务',
      url:
        '/releases?' +
        queryString({
          source: params.get('source'),
          documentId: params.get('documentId'),
          keyword: params.get('keyword'),
          status: params.get('status'),
          pageNum: params.get('pageNum'),
          pageSize: params.get('pageSize'),
        }),
      state: { releaseFocus: 'release-build-' + id },
    };
  }
  if (route === '/releases/versions/:id') {
    if (params.has('read') || params.has('recovery')) {
      return {
        label: '生效版本',
        current: params.has('read') ? '正式正文' : '恢复验证',
        url: location.pathname + searchWithout('read', 'recovery'),
        state: origin ?? undefined,
      };
    }
    const from = origin?.publishedVersionReturn;
    if (from) {
      return {
        label: '发布任务',
        current: '生效版本',
        url: '/releases/' + from.taskId + from.search,
        state: { releaseFocus: 'release-current-version' },
      };
    }
    params.set('tab', 'active');
    return {
      label: '发布与版本',
      current: '生效版本',
      url: '/releases?' + params.toString(),
      state: {
        effectiveVersionFocus: 'release-version-' + id,
        effectiveVersionPosition: origin?.effectiveVersionPosition,
      },
    };
  }
}
