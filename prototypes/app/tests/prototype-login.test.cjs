const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs'), path = require('node:path'), vm = require('node:vm');
const { webcrypto } = require('node:crypto');
const script = fs.readFileSync(path.join(__dirname, '../../shared/prototype-login.js'), 'utf8');
function load(storage) {
  const context = vm.createContext({ window: {}, sessionStorage: { getItem: key => storage.get(key), setItem: (key, value) => storage.set(key, value) }, crypto: webcrypto, TextEncoder });
  vm.runInContext(script, context);
  return context.window.PrototypeLogin;
}
test('a changed demo password survives page navigation and rejects the old password without storing plaintext', async () => {
  const storage = new Map(), account = load(storage);
  assert.equal(await account.matches('Demo2026'), true);
  assert.equal(await account.matches('Wrong2026'), false);
  await account.change('Changed2026');
  const login = load(storage);
  assert.equal(login.hasChanged(), true);
  assert.equal(await login.matches('Changed2026'), true);
  assert.equal(await login.matches('Demo2026'), false);
  const saved = JSON.stringify([...storage]);
  assert.ok(!saved.includes('Changed2026') && !saved.includes('Demo2026'));
  await login.change('Second2026');
  assert.equal(await load(storage).matches('Changed2026'), false);
  assert.equal(await load(storage).matches('Second2026'), true);
  assert.equal(load(new Map()).hasChanged(), false);
});
