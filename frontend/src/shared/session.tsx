import { createContext, useContext } from 'react';
import type { Session } from '../modules/iam/types';

/**
 * 登录信息仅控制界面，真实访问仍由各个服务入口核验。
 */
export const SessionContext = createContext<Session | null>(null);

/**
 * 页面在完整登录布局内读取当前可信显示身份。
 */
export function useSession() {
  const session = useContext(SessionContext);
  if (!session) {
    throw new Error('当前页面需要有效登录');
  }
  return session;
}

/**
 * 页面与动作必须同时在当前显示权限集合中。
 */
export function allowed(session: Session, operation: string) {
  return session.permissionCodes.includes(operation);
}
