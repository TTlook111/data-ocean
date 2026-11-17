import React from 'react';
import ReactDOM from 'react-dom/client';
import { App as AntApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { Application } from './Application';
import './styles.css';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

// 日历月份、星期和输入解析采用与组件界面一致的中文语言。
dayjs.locale('zh-cn');

/**
 * 缓存只服务当前登录会话，写入失败不自动重试。
 */
const cache = new QueryClient({
  defaultOptions: {
    queries: { retry: false, refetchOnWindowFocus: true },
    mutations: { retry: false },
  },
});

/**
 * 数据路由为独立编辑页提供未保存修改的原生导航拦截。
 */
const router = createBrowserRouter([{ path: '*', element: <Application /> }]);

// 统一主题沿用已确认的桌面尺度，所有业务页面复用同一上下文。
ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <ConfigProvider
      locale={zhCN}
      theme={{
        token: {
          colorPrimary: '#2f63d5',
          colorLink: '#2f63d5',
          colorText: '#1f2937',
          colorTextSecondary: '#5b6575',
          colorTextPlaceholder: '#5b6575',
          colorSuccess: '#216449',
          colorSuccessBg: '#e8f4ee',
          colorSuccessBorder: '#e8f4ee',
          colorSuccessText: '#216449',
          fontSize: 14,
          controlHeight: 40,
          borderRadius: 6,
          fontFamily: '"Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif',
        },
        components: {
          Table: {
            cellPaddingBlock: 12,
            cellPaddingInline: 16,
            headerBg: '#f8fafc',
            headerColor: '#5b6575',
          },
          Menu: { itemHeight: 40 },
        },
      }}
    >
      <AntApp>
        <QueryClientProvider client={cache}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </AntApp>
    </ConfigProvider>
  </React.StrictMode>,
);
