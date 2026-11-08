/* 将真实组件库局部挂载到现有 HTML；移除页面时同步清理浮层和监听。 */
(() => {
  'use strict';
  const mounts = new Map();
  const observer = new MutationObserver(() => {
    for (const [host, app] of mounts) {
      if (host.isConnected) continue;
      mounts.delete(host);
      app.unmount();
    }
  });
  observer.observe(document.documentElement, { childList: true, subtree: true });
  function mount(host, component) {
    const app = Vue.createApp(component);
    app.use(ElementPlus, { locale: ElementPlusLocaleZhCn, size: 'large' });
    app.mount(host);
    mounts.set(host, app);
    return host;
  }
  window.PrototypeUI = { mount, mountedCount: () => mounts.size };
})();
