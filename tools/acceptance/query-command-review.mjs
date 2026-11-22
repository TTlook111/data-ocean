import assert from 'node:assert/strict';
import { IdentityClient } from './iam-client.mjs';
import { developmentPassword } from './iam-fixtures.mjs';

/**
 * 用已授权的库存验收用户重放提交。原轮次和授权由隔离环境提供。
 */
const base = process.env.DO_QUERY_REVIEW_URL;
const user = process.env.DO_QUERY_REVIEW_USER;
const parentId = process.env.DO_QUERY_REVIEW_PARENT;
assert.ok(base && /^http:\/\/127\.0\.0\.1:\d+$/.test(base) && user && parentId);
const client = new IdentityClient(base);
await client.login(user, developmentPassword);
const parent = await client.ok('/query/requests/' + parentId);
const command = crypto.randomUUID();
const body = {
  sourceId: parent.sourceId,
  question: '请查询采购成本。',
  conversationId: parent.conversationId,
  previousId: parent.id,
};
const submit = (payload) =>
  client.request('/query/requests', { method: 'POST', command, body: payload });

const original = await submit(body);
assert.equal(original.status, 202);
const same = await submit(body);
assert.equal(same.status, 202);
assert.equal(same.data.id, original.data.id);

// 省略原先显式提供的条件是另一种提交，即使它碰巧能解析到同一对话。
const { previousId: _previous, ...implicit } = body;
const changed = await submit(implicit);
assert.equal(changed.status, 409, '原轮次由显式变为隐式必须报告命令冲突');
console.log(JSON.stringify({ sameRequest: same.data.id === original.data.id, changed: changed.status }));
