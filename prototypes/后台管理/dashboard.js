/* 总览仅承载样例筛选和图表交互；原型改动不调用正式统计接口。 */
(() => {
  'use strict';
  const { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } = Vue;
  const savedViews = new Map();
  const number = value => value == null ? '—' : new Intl.NumberFormat('zh-CN').format(value);
  const colors = { blue: '#4c8bf5', teal: '#20a49a', slate: '#7b8ca9', red: '#d47869' };
  const charts = [
    { id: 'query', title: '问数使用趋势', unit: '次', hero: true,
      info: '展示样例请求及其中已交付结果的数量。结果交付不代表业务正确性。',
      series: [{ key: 'requests', name: '问数请求', color: colors.blue, fill: true }, { key: 'delivered', name: '已交付结果', color: colors.teal, dashed: true }],
      metrics: [{ key: 'requests', label: '次问数请求' }, { key: 'delivered', label: '次已交付结果' }] },
    { id: 'collection', title: '采集运行', unit: '次', href: '#collections', destination: '采集记录',
      series: [{ key: 'collectionSuccess', name: '采集成功', color: colors.blue, fill: true }, { key: 'collectionFailure', name: '采集失败', color: colors.red, dashed: true }],
      metrics: [{ key: 'collectionSuccess', label: '次成功' }, { key: 'collectionFailure', label: '次失败' }] },
    { id: 'governance', title: '治理确认', unit: '项', href: '#governance', destination: '治理工作台',
      info: '只统计已确认的表说明与字段。AI 草稿和保存但未确认的内容不计入。',
      series: [{ key: 'fieldConfirmed', name: '字段', color: colors.teal, fill: true }, { key: 'tableConfirmed', name: '表说明', color: colors.slate, dashed: true }],
      metrics: [{ key: 'fieldConfirmed', label: '项字段确认' }, { key: 'tableConfirmed', label: '项表说明确认' }] },
    { id: 'knowledge', title: '可用知识', unit: '篇', href: '#knowledge', destination: '知识文档',
      info: '每日结束时可用于问数的正式文档数量。标题数字为所选期间的期末值，不累计每日数量。',
      series: [{ key: 'availableDocuments', name: '可用正式文档', color: colors.blue, fill: true, step: true }],
      metrics: [{ key: 'availableDocuments', label: '篇期末可用' }] },
    { id: 'feedback', title: '反馈处理', unit: '个', href: '#feedback', destination: '问题队列',
      info: '已解决只统计实际验收解决的问题，其他结案与合并不计入。',
      series: [{ key: 'feedbackNew', name: '新增问题', color: colors.slate }, { key: 'feedbackSolved', name: '已解决', color: colors.teal, fill: true }],
      metrics: [{ key: 'feedbackNew', label: '个新增问题' }, { key: 'feedbackSolved', label: '个已解决' }] }
  ];
  const granularityName = value => ({ day: '按天', week: '按周', month: '按月' })[value];
  function pointTitle(point) { return point.from === point.to ? point.from : point.from + ' — ' + point.to; }
  function distributionOption(definition, view) {
    const entries = definition.series.map(series => ({ name: series.name, value: view.totals[series.key] ?? 0, itemStyle: { color: series.color } }));
    const total = entries.reduce((sum, item) => sum + item.value, 0);
    const base = {
      animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
      animationDuration: 350, animationDurationUpdate: 250,
      textStyle: { fontFamily: 'Segoe UI, Microsoft YaHei UI, Microsoft YaHei, sans-serif', fontSize: 12 },
      tooltip: { trigger: 'item', confine: true, padding: [10, 12], backgroundColor: '#fff', borderColor: '#e5e7eb',
        textStyle: { color: '#1f2937', fontSize: 12 },
        formatter: item => item.name + '：' + number(entries[item.dataIndex].value) + ' ' + definition.unit +
          (definition.kind === 'ring' && total ? '（' + (entries[item.dataIndex].value / total * 100).toFixed(1) + '%）' : '') }
    };
    if (definition.kind === 'ring') return {
      ...base,
      graphic: [
        { type: 'text', left: 'center', top: '39%', silent: true, style: { text: number(total), fill: '#1f2937', font: '600 28px Segoe UI, Microsoft YaHei, sans-serif' } },
        { type: 'text', left: 'center', top: '58%', silent: true, style: { text: definition.centerLabel, fill: '#64748b', font: '12px Microsoft YaHei, sans-serif' } }
      ],
      series: [{ name: definition.title, type: 'pie', radius: ['62%', '81%'], center: ['50%', '49%'],
        stillShowZeroSum: false, label: { show: false }, labelLine: { show: false },
        itemStyle: { borderColor: '#fff', borderWidth: 3, borderRadius: 3 },
        emphasis: { scaleSize: 3 }, data: entries }]
    };
    // 排名使用水平条和直接标值，名称较长时仍能一眼比较用量。
    if (definition.kind === 'rank') return {
      ...base,
      grid: { top: 8, bottom: 12, left: 4, right: 56, containLabel: true },
      xAxis: { type: 'value', show: false, max: Math.ceil(Math.max(...entries.map(item => item.value)) * 1.08) },
      yAxis: { type: 'category', inverse: true, data: entries.map(item => item.name), axisLine: { show: false }, axisTick: { show: false },
        axisLabel: { color: '#5b6575', width: 132, overflow: 'truncate', fontSize: 12, margin: 12 } },
      series: [{ type: 'bar', barMaxWidth: 22, data: entries,
        itemStyle: { borderRadius: [0, 3, 3, 0] },
        label: { show: true, position: 'right', distance: 8, color: '#1f2937', formatter: item => number(item.value) } }]
    };
    // 维护状态允许重叠，点图分别表达数量，不将它们误画为占比。
    return {
      ...base, grid: { top: 24, bottom: 12, left: 0, right: 36, containLabel: true },
      xAxis: { type: 'value', min: 0, minInterval: 1, splitNumber: 3, max: Math.max(5, Math.ceil(Math.max(...entries.map(item => item.value)) * 1.25 / 5) * 5),
        axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#64748b', fontSize: 12 }, splitLine: { lineStyle: { color: '#edf1f5' } } },
      yAxis: { type: 'category', inverse: true, data: entries.map(item => item.name), axisLine: { show: false }, axisTick: { show: false }, axisLabel: { color: '#5b6575', fontSize: 12 } },
      series: [{ type: 'scatter', symbolSize: 12,
        label: { show: true, position: 'right', distance: 10, color: '#1f2937', fontSize: 12, formatter: item => number(item.value[0]) },
        data: entries.map((item, index) => ({ name: item.name, value: [item.value, index], itemStyle: item.itemStyle })) }]
    };
  }
  function chartOption(definition, view) {
    if (definition.kind) return distributionOption(definition, view);
    // 指出最高的已记录用量，标注锚定在该柱最后一个非零分段。
    const stackTotals = definition.stackedBars ? view.points.map(point => definition.series.reduce((sum, series) => sum + (point[series.key] ?? 0), 0)) : [];
    const peak = stackTotals.reduce((largest, total) => Math.max(largest, total), 0);
    const peakIndex = peak ? stackTotals.indexOf(peak) : -1;
    const peakSeries = definition.series.findLastIndex(series => view.points[peakIndex]?.[series.key] > 0);
    return {
      animation: !window.matchMedia('(prefers-reduced-motion: reduce)').matches,
      animationDuration: 350, animationDurationUpdate: 250,
      textStyle: { fontFamily: 'Segoe UI, Microsoft YaHei UI, Microsoft YaHei, sans-serif', fontSize: 12 },
      grid: { top: definition.stackedBars ? 33 : 12, right: 26, bottom: 8, left: 0, containLabel: true },
      tooltip: {
        trigger: 'axis', confine: true, backgroundColor: '#fff', borderColor: '#e5e7eb', borderWidth: 1,
        padding: [10, 12], extraCssText: 'box-shadow:0 6px 22px #18233310;border-radius:8px;',
        textStyle: { color: '#1f2937', fontSize: 12 },
        axisPointer: { type: 'line', lineStyle: { color: '#b8c4d5', width: 1, type: 'dashed' } },
        formatter(items) {
          const point = view.points[items[0]?.dataIndex];
          if (!point) return '';
          return '<div style="margin-bottom:6px;color:#5b6575">' + pointTitle(point) + '</div>' + definition.series.map(series =>
            '<div style="display:flex;align-items:center;gap:16px;justify-content:space-between;margin-top:4px"><span>' + series.name + '</span><strong style="font-weight:600;color:#1f2937">' + (point[series.key] === null ? '无统计' : number(point[series.key]) + ' ' + definition.unit) + '</strong></div>'
          ).join('');
        }
      },
      xAxis: {
        type: 'category', boundaryGap: !!definition.stackedBars,
        data: view.points.map(point => {
          // 有调用但整段缺回执时，用轴标签区分空柱与已知的零用量。
          if (definition.stackedBars && definition.series.every(series => point[series.key] === null)) return point.label + '\n用量未知';
          return view.granularity === 'month' ? point.from.slice(0, 7) : point.label;
        }),
        axisLine: { show: false }, axisTick: { show: false },
        axisLabel: { color: '#64748b', fontSize: 12, margin: 14, hideOverlap: true, showMinLabel: true, showMaxLabel: true,
          // 精确时段可能带完整日期，首尾读数朝图内对齐以保留日期。
          alignMinLabel: definition.onDrill ? 'left' : null, alignMaxLabel: definition.onDrill ? 'right' : null,
          interval: Math.max(0, Math.ceil(view.points.length / (definition.hero ? 11 : 6)) - 1) }
      },
      yAxis: {
        type: 'value', min: 0, minInterval: definition.decimals ? 0.1 : 1, splitNumber: 4,
        axisLine: { show: false }, axisTick: { show: false },
        axisLabel: { color: '#64748b', fontSize: 12, margin: 12, formatter: value => value >= 1000 ? Number((value / 1000).toFixed(1)) + 'k' : value },
        splitLine: { lineStyle: { color: '#edf1f5', width: 1 } }
      },
      // Token 组成共用一根柱，柱高代表输入和输出合计。
      series: definition.series.map((series, index) => definition.stackedBars ? {
        name: series.name, type: 'bar', stack: definition.id, barMaxWidth: 68,
        data: view.points.map(point => point[series.key]),
        // 峰值使用各系列自己的强调色，保留普通柱、图例和用量的对应关系。
        itemStyle: { color: item => item.dataIndex === peakIndex ? (series.peakColor ?? series.color) : series.color,
          borderRadius: index === definition.series.length - 1 ? [5, 5, 0, 0] : 0 },
        label: { show: index === peakSeries, position: 'top', distance: 9, color: definition.peakLabelColor ?? '#214585', fontSize: 15, fontWeight: 650,
          formatter: item => item.dataIndex === peakIndex ? number(peak) : '' }
      } : ({
        name: series.name, type: 'line', data: view.points.map(point => point[series.key]),
        // 离散日统计保留直线；知识存量用阶梯线，缺失日期不连线或补零。
        smooth: false, step: series.step ? 'end' : false, connectNulls: false,
        // 可下钻的时间点保留更大的命中面积。
        showSymbol: !!definition.onDrill || view.points.length === 1, symbol: 'circle', symbolSize: definition.onDrill ? 8 : 6,
        lineStyle: { width: definition.hero ? 2.1 : 1.9, color: series.color, type: series.dashed ? 'dashed' : 'solid' },
        itemStyle: { color: series.color, borderColor: '#fff', borderWidth: 2 },
        areaStyle: series.fill ? { color: series.color, opacity: definition.hero ? 0.075 : 0.055 } : undefined,
        emphasis: { focus: 'series', lineStyle: { width: 2.4 } }
      }))
    };
  }
  const TrendChart = {
    props: ['definition', 'view'],
    setup(props) {
      const plot = ref(), expanded = ref(false), announcement = ref(''), failed = ref(false);
      let instance, observer, resizeFrame = 0, position = -1;
      const announce = index => {
        if (props.definition.kind) {
          const series = props.definition.series[index];
          announcement.value = series.name + ' ' + number(props.view.totals[series.key]) + props.definition.unit;
          return;
        }
        const point = props.view.points[index];
        announcement.value = pointTitle(point) + '，' + props.definition.series.map(series => series.name + ' ' + (point[series.key] === null ? '无统计' : number(point[series.key]) + props.definition.unit)).join('，');
      };
      function paint() {
        if (!instance) return;
        position = -1; announcement.value = '';
        instance.setOption(chartOption(props.definition, props.view), { notMerge: true });
        instance.resize();
      }
      onMounted(() => {
        if (!window.echarts) { failed.value = true; return; }
        instance = echarts.init(plot.value, null, { renderer: 'svg' });
        // 允许核对有来源记录的图表；其他概览仍只展示读数。
        if (props.definition.onDrill) {
          instance.on('click', 'series', ({ dataIndex, seriesIndex }) => {
            const point = props.view.points[dataIndex], series = props.definition.series[seriesIndex];
            // 零次调用不提供空明细入口。
            if (point?.[series?.key] > 0) props.definition.onDrill(point, series.key);
          });
        }
        observer = new ResizeObserver(() => {
          // 尺寸通知内不直接改写图表布局；合并到下一帧，避免窄窗口切换形成观察循环。
          cancelAnimationFrame(resizeFrame);
          resizeFrame = requestAnimationFrame(() => {
            resizeFrame = 0;
            if (instance && (Math.abs(instance.getWidth() - plot.value.clientWidth) > 1 || Math.abs(instance.getHeight() - plot.value.clientHeight) > 1)) instance.resize();
          });
        });
        observer.observe(plot.value); paint();
      });
      watch(() => props.view, () => nextTick(paint));
      // 页面切换由 PrototypeUI 卸载，图表和尺寸监听一起释放，不留后台实例。
      onBeforeUnmount(() => { observer?.disconnect(); cancelAnimationFrame(resizeFrame); instance?.dispose(); instance = null; });
      function keyboard(event) {
        // 使用键盘选中一个时间点后，可查看这个时间段的全部记录。
        if (event.key === 'Enter' && props.definition.onDrill && position >= 0) {
          const point = props.view.points[position];
          // 只为有记录的时段打开明细。
          if (props.definition.series.some(series => point[series.key] > 0)) props.definition.onDrill(point);
          return;
        }
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End', 'Escape'].includes(event.key)) return;
        event.preventDefault();
        if (event.key === 'Escape') { instance?.dispatchAction({ type: 'hideTip' }); instance?.dispatchAction({ type: 'downplay', seriesIndex: 0 }); return; }
        const last = (props.definition.kind ? props.definition.series : props.view.points).length - 1;
        instance?.dispatchAction({ type: 'downplay', seriesIndex: 0 });
        position = event.key === 'Home' ? 0 : event.key === 'End' ? last : position < 0 ? 0 : Math.max(0, Math.min(last, position + (event.key === 'ArrowLeft' ? -1 : 1)));
        if (props.definition.kind) instance?.dispatchAction({ type: 'highlight', seriesIndex: 0, dataIndex: position });
        instance?.dispatchAction({ type: 'showTip', seriesIndex: 0, dataIndex: position }); announce(position);
      }
      return {
        plot, expanded, announcement, failed, number, keyboard, pointTitle,
        summary: computed(() => props.definition.title + '，' + props.view.range.join(' 至 ') + '，' + props.view.sourceName),
        percentage: value => {
          const total = props.definition.series.reduce((sum, item) => sum + (props.view.totals[item.key] || 0), 0);
          return total ? (value / total * 100).toFixed(1) + '%' : '0%';
        },
        hideTip: () => { instance?.dispatchAction({ type: 'hideTip' }); instance?.dispatchAction({ type: 'downplay', seriesIndex: 0 }); }
      };
    },
    template: `<section class="dashboard-chart" :class="{'is-hero':definition.hero,'is-ring':definition.kind==='ring','is-dots':definition.kind==='dots'}" :data-chart-id="definition.id" :aria-labelledby="'dash-title-'+definition.id">
      <header class="dashboard-chart-heading">
        <div><div class="dashboard-chart-title"><h2 :id="'dash-title-'+definition.id">{{definition.title}}</h2>
          <el-tooltip v-if="definition.info" :content="definition.info" placement="top" :trigger="['hover','focus']" popper-class="prototype-ui-popper">
            <span class="dashboard-chart-unit" tabindex="0" :aria-label="definition.info">{{definition.unit}}</span>
          </el-tooltip><span v-else-if="!definition.hideUnit" class="dashboard-chart-unit">{{definition.unit}}</span>
        </div><div v-if="definition.metrics.length" class="dashboard-chart-metrics"><div v-for="metric in definition.metrics" :key="metric.key" class="dashboard-metric"><strong :data-metric="metric.key">{{number(view.totals[metric.key])}}</strong><span>{{metric.label}}</span></div><slot name="metrics-note"></slot></div></div>
        <!-- 期间等仅属于当前图表的操作可以放在图例同行。 -->
        <div class="dashboard-chart-side"><slot name="controls"></slot><div v-if="!definition.kind" class="dashboard-chart-legend" aria-label="图例"><span v-for="series in definition.series" :key="series.key"><i :class="{'is-dashed':series.dashed}" :style="{'--series-color':series.color}" aria-hidden="true"></i>{{series.name}}</span></div>
          <button class="dashboard-data-toggle" type="button" :aria-expanded="expanded" :aria-controls="'dash-data-'+definition.id" @click="expanded=!expanded">{{expanded?'收起数据':'查看数据'}}</button>
        </div>
      </header>
      <div v-if="failed" class="dashboard-chart-error" role="alert">图表资源未加载，请刷新后重试。</div>
      <div v-else ref="plot" class="dashboard-plot" :data-chart="definition.id" tabindex="0" role="group" :aria-roledescription="definition.kind==='rank'?'条形图':definition.kind==='ring'?'环形图':definition.kind==='dots'?'分类点图':definition.stackedBars?'堆叠柱状图':'趋势图'" :aria-label="summary" :aria-describedby="'dash-keyboard-'+definition.id" @keydown="keyboard" @blur="hideTip"></div>
      <p class="sr-only" :id="'dash-keyboard-'+definition.id">左右方向键逐项查看，Home 与 End 跳转首尾，Esc 关闭提示。<template v-if="definition.onDrill">Enter 查看所选时段的调用记录。</template>也可展开查看数据。</p>
      <span class="sr-only" role="status" aria-live="polite">{{announcement}}</span>
      <div v-if="definition.kind==='ring'" class="dashboard-distribution-legend" aria-label="图例与占比"><div v-for="series in definition.series" :key="series.key"><span><i :style="{'--series-color':series.color}" aria-hidden="true"></i>{{series.name}}</span><strong>{{number(view.totals[series.key])}}</strong><small>{{percentage(view.totals[series.key])}}</small></div></div>
      <footer v-if="definition.href" class="dashboard-chart-footer"><a :href="definition.href">{{definition.destination}}<span aria-hidden="true">↗</span></a></footer>
      <div v-if="expanded" :id="'dash-data-'+definition.id" class="dashboard-data" tabindex="0" :aria-label="definition.title+'数据表'">
        <table v-if="definition.kind"><caption class="sr-only">{{summary}}</caption><thead><tr><th scope="col">类别</th><th scope="col">数量（{{definition.unit}}）</th></tr></thead><tbody><tr v-for="series in definition.series" :key="series.key"><th scope="row">{{series.name}}</th><td>{{number(view.totals[series.key])}}</td></tr></tbody></table>
        <!-- 数据表提供与图上数据点相同的明细入口。 -->
        <table v-else><caption class="sr-only">{{summary}}</caption><thead><tr><th scope="col">{{definition.intervalLabel || '统计日期'}}</th><th v-for="series in definition.series" :key="series.key" scope="col">{{series.name}}（{{definition.unit}}）</th></tr></thead><tbody><tr v-for="point in view.points" :key="point.from"><th scope="row">{{pointTitle(point)}}</th><td v-for="series in definition.series" :key="series.key"><button v-if="definition.onDrill && point[series.key] > 0" type="button" class="do-action is-primary" :aria-label="pointTitle(point)+'，查看'+point[series.key]+'次'+series.name+'调用'" @click="definition.onDrill(point, series.key)">{{number(point[series.key])}}</button><template v-else>{{point[series.key]===null?'无统计':number(point[series.key])}}</template></td></tr></tbody></table>
      </div>
    </section>`
  };
  function render(main, ui, key = 'dashboard') {
    const profile = key === 'dashboard' ? { title: '总览', layout: 'overview', charts } : window.ModuleDashboardProfiles[key];
    if (!SourceApprovalDemo.hasAbility(ui.currentUser, key + '.view')) {
      const denied = ui.el('section', 'planned-page'); denied.append(ui.el('p', '', '当前账号没有查看' + profile.title + '的权限。'));
      main.replaceChildren(ui.pageHeading(profile.title), denied); return;
    }
    // 每个看板记住自己的已应用条件，互相切换不覆盖其他页尚需回看的范围。
    if (!savedViews.has(key)) savedViews.set(key, { source: 'all', preset: '30', range: DashboardDemo.presetRange(30) });
    const saved = savedViews.get(key);
    const host = ui.el('div', 'dashboard-page prototype-ui dashboard-layout-' + profile.layout);
    if (profile.accent) host.style.setProperty('--dashboard-accent', profile.accent);
    main.replaceChildren(ui.pageHeading(profile.title), host);
    PrototypeUI.mount(host, {
      components: { TrendChart },
      setup() {
        const source = ref(saved.source), preset = ref(saved.preset), range = ref([...saved.range]);
        const picker = ref(), error = ref('');
        const savedRange = ref([...saved.range]);
        const view = computed(() => DashboardDemo.view({ range: savedRange.value, source: source.value }));
        function persist() { Object.assign(saved, { source: source.value, preset: preset.value, range: [...savedRange.value] }); }
        function choosePreset(value) {
          if (value === 'custom') {
            // 从快捷项打开日历后，把键盘交给日期组件，Esc 才能正常取消。
            nextTick(() => { picker.value?.handleOpen(); host.querySelector('.dashboard-date input')?.focus({ preventScroll: true }); });
            return;
          }
          range.value = DashboardDemo.presetRange(Number(value)); savedRange.value = [...range.value]; error.value = ''; persist();
        }
        function applyRange(value) {
          error.value = DashboardDemo.validateRange(value);
          if (error.value) { range.value = [...savedRange.value]; return; }
          savedRange.value = [...value]; preset.value = 'custom'; persist();
        }
        function resetRange() { preset.value = '30'; choosePreset('30'); }
        function closeCalendar(visible) {
          // 取消日历不改变已经应用的区间或快捷项。
          if (!visible) { range.value = [...savedRange.value]; preset.value = saved.preset; }
        }
        function labelInputs() {
          const inputs = host.querySelectorAll('.dashboard-date input');
          inputs.forEach((input, index) => { input.setAttribute('aria-label', index ? '统计结束日期' : '统计开始日期'); input.setAttribute('aria-describedby', 'dashboard-range-error'); });
        }
        onMounted(labelInputs);
        watch(source, persist);
        return {
          source, preset, range, picker, error, view, choosePreset, applyRange, resetRange, closeCalendar,
          sources: DashboardDemo.sources, hero: charts[0], secondary: charts.slice(1), profile, granularityName,
          disabledDate: value => {
            const local = [value.getFullYear(), String(value.getMonth()+1).padStart(2,'0'), String(value.getDate()).padStart(2,'0')].join('-');
            return local > DashboardDemo.asOf;
          }
        };
      },
      template: `<div class="dashboard-toolbar">
          <div class="dashboard-scope"><el-select v-model="source" class="dashboard-source" aria-label="统计数据源" popper-class="prototype-ui-popper"><el-option label="全部数据源" value="all"></el-option><el-option v-for="item in sources" :key="item.id" :value="item.id" :label="item.name"></el-option></el-select><span class="dashboard-sample">示例数据 · {{granularityName(view.granularity)}}统计</span></div>
          <div class="dashboard-period"><el-radio-group v-model="preset" aria-label="统计时间范围" @change="choosePreset"><el-radio-button value="7">近7天</el-radio-button><el-radio-button value="30">近30天</el-radio-button><el-radio-button value="custom">自定义</el-radio-button></el-radio-group>
            <el-date-picker ref="picker" v-model="range" class="dashboard-date" type="daterange" format="YYYY.MM.DD" value-format="YYYY-MM-DD" range-separator="—" start-placeholder="开始日期" end-placeholder="结束日期" :clearable="false" :disabled-date="disabledDate" :automatic-dropdown="false" popper-class="prototype-ui-popper dashboard-date-popper" @change="applyRange" @visible-change="closeCalendar"></el-date-picker>
          </div>
        </div>
        <p v-if="error || view.partial" id="dashboard-range-error" class="dashboard-range-note" role="status">{{error || '示例数据覆盖 2026.09.17—2026.11.15，其他日期保留空缺。'}}</p><span v-else id="dashboard-range-error" class="sr-only"></span>
        <template v-if="view.count"><template v-if="profile.layout==='overview'"><trend-chart :definition="hero" :view="view"></trend-chart><div class="dashboard-grid"><trend-chart v-for="chart in secondary" :key="chart.id" :definition="chart" :view="view"></trend-chart></div></template>
          <div v-else class="module-dashboard-grid"><trend-chart v-for="chart in profile.charts" :key="chart.id" :definition="chart" :view="view"></trend-chart></div></template>
        <section v-else class="dashboard-empty"><h2>该时段暂无示例统计</h2><p>示例数据覆盖 2026.09.17—2026.11.15</p><el-button @click="resetRange">查看近30天</el-button></section>`
    });
  }
  // 模型运行概览复用趋势、提示、数据表和图表生命周期。
  window.DashboardPage = { render, TrendChart };
})();
