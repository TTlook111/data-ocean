import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

/**
 * 开发与预览共用正式 IAM 后端地址，允许当前机器显式覆盖。
 */
const target = process.env.DATAOCEAN_API_PROXY_URL || 'http://127.0.0.1:18080';

/**
 * 复用 React 构建及组件测试配置，开发代理不依赖启动终端残留的环境变量。
 */
export default defineConfig({
  // React 组件编译与开发热更新。
  plugins: [react()],

  // React 运行时独立输出，业务发布时可单独命中其长期缓存。
  build: {
    // 使用当前 Vite 的 Rolldown 分包配置，业务模块仍按动态导入分包。
    rolldownOptions: {
      // 只显式拆分共用运行时，避免把完整组件库提到首屏。
      output: {
        // React 和调度器始终使用同一组模块。
        codeSplitting: {
          groups: [
            {
              // 产物名明确标识稳定的基础运行时。
              name: 'react-runtime',
              // 同时兼容 Windows 和其他系统的模块路径。
              test: /[\\/]node_modules[\\/](?:react|react-dom|scheduler)[\\/]/,
            },
          ],
        },
      },
    },
  },

  // 本机开发服务及后端接口代理。
  server: {
    // 只监听本机地址。
    host: '127.0.0.1',
    // 前端开发端口。
    port: 5173,
    // 端口占用时停止启动，避免静默切换地址。
    strictPort: true,

    // 业务接口转发到已选定的 Java 后端。
    proxy: {
      // 只代理统一 API 前缀。
      '/api': {
        // 目标地址沿用上方环境覆盖规则。
        target,
        // 转发时使用目标服务的 Host。
        changeOrigin: true,
      },
    },
  },

  // 构建预览沿用开发环境的接口转发方式。
  preview: {
    // 预览服务只在本机可访问。
    host: '127.0.0.1',
    // 预览使用独立端口。
    port: 4173,
    // 预览端口同样禁止自动切换。
    strictPort: true,

    // 预览中的业务请求继续访问同一 Java 后端。
    proxy: {
      // 与开发服务保持相同的 API 前缀。
      '/api': {
        // 复用已确定的后端目标地址。
        target,
        // 保持代理请求的 Host 与目标服务一致。
        changeOrigin: true,
      },
    },
  },

  // 组件测试的浏览器模拟环境及公共初始化。
  test: {
    // 为组件测试提供 DOM 环境。
    environment: 'jsdom',
    // Windows 子进程池启动超时，组件测试在独立线程的 VM 环境运行。
    pool: 'vmThreads',
    // Windows 本机采用已验证的串行工作器配置。
    maxWorkers: process.platform === 'win32' ? 1 : undefined,
    // 测试使用图标包公开的浏览器 ESM 入口，保留命名导出。
    alias: [{ find: /^@ant-design\/icons$/, replacement: '@ant-design/icons/es' }],
    deps: {
      optimizer: {
        client: {
          // 预打包 Ant Design 的浏览器依赖，避免 VM 直接加载混合模块入口。
          enabled: true,
          include: ['antd', '@ant-design/icons/es'],
        },
      },
    },
    // 加载公共匹配器与测试环境准备逻辑。
    setupFiles: ['./src/shared/test/setup.ts'],
    // 每个测试前清空 mock 调用记录。
    clearMocks: true,
  },
});
