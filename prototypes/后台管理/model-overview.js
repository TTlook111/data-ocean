/* 模型运行概览使用同一份离线调用记录核对 Token、模型排行与运行结果。 */
(() => {
  'use strict';

  const model = SystemModelsDemo;
  const day = 86400000;
  const { shift, validDay, periodBuckets } = DashboardDemo;
  // 冻结页面统计截止点，当天统计到此时，其余日期包含完整一天。
  const cutoff = model.clock();
  const asOf = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', dateStyle: 'short' }).format(cutoff);
  const saved = { preset: '7', range: [shift(asOf, -6), asOf] };
  let returnFromRecords = false;
  let restoreTable = false;

  // ECharts 与页面控件共用后台主题蓝，输出使用有明度差的浅蓝。
  const theme = getComputedStyle(document.documentElement);
  const primary = theme.getPropertyValue('--do-primary').trim();
  const primaryStrong = theme.getPropertyValue('--do-primary-hover').trim();
  const tokenSeries = [
    { key: 'input', name: '输入 Token', color: primary, peakColor: primaryStrong },
    { key: 'output', name: '输出 Token', color: '#A3C3F5', peakColor: '#82ACEF' }
  ];
  // 辅助图降低成功曲线的饱和度，失败和待核查继续按原状态独立表达。
  const outcomeSeries = [
    { key: 'success', name: '成功', state: '成功', color: '#5876A0', fill: true },
    { key: 'failure', name: '失败', state: '失败', color: '#b74f46', dashed: true },
    { key: 'unconfirmed', name: '待核查', state: '结果待核查', color: '#926916', dashed: true }
  ];

  /**
   * 在同一半开区间按时段和模型汇总。已有调用但全部缺用量时保留空缺。
   */
  function buildView(range) {
    const { granularity, buckets } = periodBuckets(...range);
    // 样例与明细按上海自然日归属。上界不含次日零点，也不超过本次截止点。
    const fromAt = Date.parse(range[0] + 'T00:00:00+08:00');
    const toAt = Math.min(cutoff, Date.parse(range[1] + 'T00:00:00+08:00') + day);
    const allCalls = model.calls.filter(call => call.at >= fromAt && call.at < toAt);
    const reported = allCalls.filter(call => call.tokens !== null);
    const totals = { input: 0, output: 0, tokenTotal: 0, success: 0, failure: 0, unconfirmed: 0 };

    const points = buckets.map(({ from, to }) => {
      // 数字边界交给调用列表，日期标签表达所选自然日或汇总区间。
      const start = Date.parse(from + 'T00:00:00+08:00');
      const end = Math.min(toAt, Date.parse(to + 'T00:00:00+08:00') + day);
      const calls = allCalls.filter(call => call.at >= start && call.at < end);
      const usage = calls.filter(call => call.tokens !== null);
      const point = {
        start, end, from, to, label: from.slice(5).replace('-', '/'),
        input: calls.length && !usage.length ? null : usage.reduce((sum, call) => sum + call.inputTokens, 0),
        output: calls.length && !usage.length ? null : usage.reduce((sum, call) => sum + call.outputTokens, 0)
      };
      // 聚合值与图形读数都来自当前区间内同一批记录。
      totals.input += point.input ?? 0;
      totals.output += point.output ?? 0;
      for (const item of outcomeSeries) {
        point[item.key] = calls.filter(call => call.status === item.state).length;
        totals[item.key] += point[item.key];
      }
      return point;
    });

    const byModel = new Map();
    for (const call of reported) {
      // 以配置标识聚合同一模型，名称取这段时间较新的调用快照。
      const key = 'model-' + call.model;
      if (!byModel.has(key)) byModel.set(key, { key, name: call.snapshot.name });
      totals[key] = (totals[key] ?? 0) + call.tokens;
    }
    // 排名首项与主图同色相，其余条目降低饱和度，保留清楚的条长和直接标值。
    const ranked = [...byModel.values()].sort((a, b) => totals[b.key] - totals[a.key])
      .map((item, index) => ({ ...item, color: index === 0 ? '#6282AE' : '#A0B2CB' }));
    totals.tokenTotal = totals.input + totals.output;
    // 整段都没有用量回执时，标题也显示未知。
    if (allCalls.length && !reported.length) totals.input = totals.output = totals.tokenTotal = null;
    return {
      points, totals, ranked, granularity, recorded: reported.length, calls: allCalls.length,
      range, sourceName: '示例数据'
    };
  }

  /**
   * 三张图共用期间和图表组件。仅结果趋势提供到原调用记录的下钻。
   */
  function render(main, ui) {
    const { ref, computed, nextTick, onMounted } = Vue;
    const host = ui.el('div', 'dashboard-page model-overview prototype-ui');
    main.append(host);
    PrototypeUI.mount(host, {
      components: { TrendChart: DashboardPage.TrendChart },

      /**
       * 保留已选期间，结果下钻沿用调用列表的时段和状态条件。
       */
      setup() {
        const preset = ref(saved.preset), dates = ref([...saved.range]), applied = ref([...saved.range]);
        const picker = ref(), dateError = ref('');
        const view = computed(() => buildView(applied.value));
        const usage = {
          id: 'model-tokens', title: 'Token 消耗趋势', unit: 'Token', hero: true, hideUnit: true,
          stackedBars: true, intervalLabel: '调用时段', series: tokenSeries, peakLabelColor: primaryStrong,
          metrics: [{ key: 'tokenTotal', label: 'Token' }, { key: 'input', label: '输入' }, { key: 'output', label: '输出' }]
        };
        const ranking = computed(() => ({
          id: 'model-usage-rank', title: '模型用量排行', unit: 'Token', kind: 'rank',
          metrics: [], series: view.value.ranked
        }));
        const outcomes = {
          id: 'model-calls', title: '调用结果趋势', unit: '次', intervalLabel: '调用时段',
          series: outcomeSeries, metrics: outcomeSeries.map(item => ({ key: item.key, label: '次' + item.name })),

          /**
           * 鼠标或数据表选择具体状态；键盘选中时段后可查看该段全部调用。
           */
          onDrill(point, key) {
            const params = new URLSearchParams({ from: point.start, to: point.end });
            const state = outcomeSeries.find(item => item.key === key)?.state;
            if (state) params.set('state', state);
            returnFromRecords = true;
            restoreTable = !!host.querySelector('#dash-data-model-calls');
            location.hash = 'diagnostics/calls/' + params;
          }
        };

        /**
         * 成功应用后同步三个图和已保存的返回条件。
         */
        function applyRange(range, option) {
          dates.value = [...range];
          applied.value = [...range];
          preset.value = option;
          dateError.value = '';
          Object.assign(saved, { preset: option, range: [...range] });
        }

        /**
         * 快捷项包含今天，自定义仅打开日期面板等待用户选择。
         */
        function changePreset(value) {
          if (value === 'custom') {
            // 将焦点留给日期组件处理键盘和取消。
            nextTick(() => { picker.value?.handleOpen(); host.querySelector('.model-usage-dates input')?.focus({ preventScroll: true }); });
            return;
          }
          applyRange([shift(asOf, 1 - Number(value)), asOf], value);
        }

        /**
         * 起止日期有效且在截止日内才更新统计。
         */
        function confirmDates(range) {
          if (!Array.isArray(range) || range.length !== 2 || !range.every(validDay) || range[0] > range[1] || range[1] > asOf) {
            // 无效日期时恢复已应用范围，同时保留可见反馈。
            dateError.value = '请选择有效的起止日期。';
            dates.value = [...saved.range];
            return;
          }
          applyRange(range, 'custom');
        }

        /**
         * 关闭面板时恢复已应用条件，包含 Esc 取消的情况。
         */
        function onCalendarChange(visible) {
          if (!visible) {
            // 面板关闭不提交尚未选完的范围。
            dates.value = [...saved.range];
            preset.value = saved.preset;
          }
        }

        /**
         * 按日历显示的本地日期判断是否超过统计截止日。
         */
        function disabledDate(value) {
          const local = [value.getFullYear(), String(value.getMonth() + 1).padStart(2, '0'), String(value.getDate()).padStart(2, '0')].join('-');
          return local > asOf;
        }

        // 为两个日期输入分别补充可访问名称及错误反馈关联。
        onMounted(() => {
          host.querySelectorAll('.model-usage-dates input').forEach((input, index) => {
            input.setAttribute('aria-label', index ? '统计结束日期' : '统计开始日期');
            input.setAttribute('aria-describedby', 'model-usage-date-error');
          });
        });

        return { preset, dates, picker, dateError, view, usage, ranking, outcomes, changePreset, confirmDates, onCalendarChange, disabledDate };
      },
      template: `
        <!-- 首屏主图回答何时用了多少 Token，图例和数字区分输入与输出。 -->
        <trend-chart :definition="usage" :view="view">
          <template #controls>
            <div class="model-usage-controls dashboard-period">
              <span class="dashboard-sample">示例数据</span>
              <div class="model-usage-date-controls">
                <el-radio-group v-model="preset" aria-label="模型运行统计期间" @change="changePreset">
                  <el-radio-button value="7">近 7 天</el-radio-button>
                  <el-radio-button value="10">近 10 天</el-radio-button>
                  <el-radio-button value="custom">自定义</el-radio-button>
                </el-radio-group>
                <el-date-picker ref="picker" v-model="dates" class="dashboard-date model-usage-dates" type="daterange"
                  format="YYYY.MM.DD" value-format="YYYY-MM-DD" range-separator="至" start-placeholder="开始日期" end-placeholder="结束日期"
                  :clearable="false" :disabled-date="disabledDate" :automatic-dropdown="false" popper-class="prototype-ui-popper dashboard-date-popper"
                  @change="confirmDates" @visible-change="onCalendarChange"></el-date-picker>
              </div>
              <span id="model-usage-date-error" class="model-usage-date-error" role="status">{{dateError}}</span>
            </div>
          </template>
          <template #metrics-note>
            <span v-if="view.recorded < view.calls" class="model-usage-coverage">{{view.calls}} 次调用中 {{view.recorded}} 次有用量记录</span>
            <span v-else-if="!view.calls" class="model-usage-coverage" role="status">所选期间暂无示例调用</span>
          </template>
        </trend-chart>
        <!-- 排行找出主要消耗模型，调用结果保留单独的次数轴。 -->
        <div class="dashboard-grid model-overview-grid">
          <trend-chart v-if="view.ranked.length" :definition="ranking" :view="view"></trend-chart>
          <section v-else class="dashboard-chart">
            <h2>模型用量排行</h2><p class="model-usage-coverage">暂无 Token 用量记录</p>
          </section>
          <trend-chart :definition="outcomes" :view="view"></trend-chart>
        </div>
      `
    });

    // 从调用记录返回后把键盘焦点交还给调用图，保留已展开的数据表。
    if (returnFromRecords) {
      returnFromRecords = false;
      requestAnimationFrame(() => {
        if (restoreTable) host.querySelector('[data-chart-id="model-calls"] .dashboard-data-toggle')?.click();
        restoreTable = false;
        host.querySelector('[data-chart="model-calls"]')?.focus();
      });
    }
  }

  window.ModelOverviewPage = { render };
})();
