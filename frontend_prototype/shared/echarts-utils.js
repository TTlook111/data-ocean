(function () {
  function init(dom) {
    if (!dom || !window.echarts) return null;

    let chart = echarts.getInstanceByDom(dom);
    if (!chart) chart = echarts.init(dom);

    if (chart.__protoResizeHandler) {
      window.removeEventListener('resize', chart.__protoResizeHandler);
    }

    const handler = () => chart.resize();
    chart.__protoResizeHandler = handler;
    window.addEventListener('resize', handler);

    return chart;
  }

  function dispose(chart) {
    if (!chart) return;
    if (chart.__protoResizeHandler) {
      window.removeEventListener('resize', chart.__protoResizeHandler);
      chart.__protoResizeHandler = null;
    }
    if (!chart.isDisposed()) chart.dispose();
  }

  window.EChartsUtils = { init, dispose };
})();

