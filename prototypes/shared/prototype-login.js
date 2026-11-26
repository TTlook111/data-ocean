/* 仅衔接当前标签页的演示登录；跨页只保存随机盐和摘要，不保存密码原文。 */
(() => {
  'use strict';
  const key = 'dataocean-prototype-password';
  const record = () => JSON.parse(sessionStorage.getItem(key) || 'null');
  async function digest(password, salt) {
    const bytes = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(salt + ':' + password));
    return Array.from(new Uint8Array(bytes), byte => byte.toString(16).padStart(2, '0')).join('');
  }
  async function matches(password) {
    const saved = record();
    return saved ? await digest(password, saved.salt) === saved.digest : password === 'Demo2026';
  }
  async function change(password) {
    const salt = Array.from(crypto.getRandomValues(new Uint8Array(16)), byte => byte.toString(16).padStart(2, '0')).join('');
    sessionStorage.setItem(key, JSON.stringify({ salt, digest: await digest(password, salt) }));
  }
  window.PrototypeLogin = { matches, change, hasChanged: () => Boolean(record()) };
})();
