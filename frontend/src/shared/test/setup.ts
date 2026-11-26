import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterEach } from 'vitest';

// 场景之间卸载页面及浮层，防止账号表单和查询缓存互相干扰。
afterEach(cleanup);

/**
 * 组件测试只验证状态和动作，尺寸变化由真实浏览器验收。
 */
class TestResizeObserver implements ResizeObserver {
  /**
   * 记录观察入口，不在 jsdom 中伪造布局结果。
   */
  observe() {
    // jsdom 没有布局变化，不派发伪造尺寸。
  }
  /**
   * 解除观察由测试场景卸载统一处理。
   */
  unobserve() {
    // 本替身没有需要释放的原生观察对象。
  }
  /**
   * 断开观察不触发尺寸回调。
   */
  disconnect() {
    // 卸载仅结束替身生命周期，不触发业务回调。
  }
}

// 为表格和选择器提供 jsdom 缺失的标准观察接口。
globalThis.ResizeObserver = TestResizeObserver;

/**
 * jsdom 提供组件媒体查询所需的稳定浏览器接口。
 */
Object.defineProperty(window, 'matchMedia', {
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener() {},
    removeListener() {},
    addEventListener() {},
    removeEventListener() {},
    dispatchEvent: () => false,
  }),
});
