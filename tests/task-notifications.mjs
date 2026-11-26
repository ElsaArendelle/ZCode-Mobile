import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const script = await readFile(new URL('../app/src/main/assets/inject.js', import.meta.url), 'utf8');
const alerts = [];
const fallbackTimers = [];
const elements = new Map();
let mediaPlays = 0;
class Media {
  constructor(src) { this.src = src; }
  async play() { mediaPlays++; }
}
const cachedCue = new Media('/remote/v4/latest/assets/task-notification-pop-abc.mp3?v=1');
const page = {
  atob, TextDecoder,
  console: { log() {} },
  setTimeout(fn, ms) { if (ms === 350) fallbackTimers.push(fn); },
  CSS: { escape: value => value },
  history: { state: null, back() { this.state = null; } },
  document: {
    querySelector: selector => elements.get(selector),
    querySelectorAll: () => [],
  },
  WebSocket: class {},
  HTMLMediaElement: Media,
  ZCodeNative: { notifyTask: (...args) => alerts.push(args) },
};
page.window = page;
vm.runInNewContext(script, page);

function phase(taskId, phase, title = '同名任务') {
  page.__zcFeed(JSON.stringify({ meta: { taskId, title }, activity: { phase } }));
}
for (const id of ['a', 'b']) {
  phase(id, 'running');
  phase(id, 'completedSuccess');
  phase(id, 'completedSuccess');
}
assert.deepEqual(alerts.map(args => args[4]), ['a', 'b']);
assert.equal(page.__zcOpenAlert('unknown', '', '任务：同名任务'), 'detail');

page.history.state = { zcodeMobilePage: 'chat' };
for (const id of ['b', 'a']) {
  const selector = `[data-testid="task-item-${id}"]`;
  elements.set(selector, { click() { page.history.state = { zcodeMobilePage: 'chat' }; } });
  const open = () => page.__zcOpenAlert('notice-' + id, id, '任务：同名任务');
  assert.equal(open(), 'wait');
  assert.equal(page.history.state, null);
  assert.equal(open(), 'wait');
  assert.equal(open(), 'wait'); // 路由已切到会话，内容仍在加载。
  const session = `[data-session-id="${id}"]`;
  elements.set(session, {});
  assert.equal(open(), 'opened');
  elements.delete(session);
}
// 搜索同名任务时，只接受 data-value 以完整目标 ID 结尾的结果。
page.history.state = null;
let searched = false;
elements.set('[data-testid="sidebar-search-open"]', { click() { searched = true; } });
let selected = '';
page.document.querySelectorAll = () => ['other', 'hidden'].map(id => ({
  getAttribute: () => 'task-result:workspace:' + id,
  click() { selected = id; },
}));
const hidden = () => page.__zcOpenAlert('notice-hidden', 'hidden', '任务：同名任务');
assert.equal(hidden(), 'wait');
assert.equal(searched, true);
assert.equal(hidden(), 'wait');
assert.equal(selected, 'hidden');

function browserResult(title, taskName, id) {
  new page.Notification(title, { body: '任务：' + taskName, data: { taskId: id } });
}
const before = alerts.length;
phase('c', 'running', '在吗');
phase('c', 'completedSuccess', '在吗');
browserResult('任务已完成', '在吗');
phase('c', 'completed', '在吗');
assert.equal(alerts.length, before + 1, '中继、浏览器通知和完成别名只提醒一次');
phase('c', 'running', '在吗');
browserResult('任务已完成', '在吗');
phase('c', 'completedSuccess', '在吗');
assert.equal(alerts.length, before + 2, '下一轮先收到浏览器通知也只提醒一次');
assert.notEqual(alerts[before][3], alerts[before + 1][3], '新一轮不会被 Android 的去重窗口吞掉');
phase('c', 'running', '在吗');
phase('c', 'failed', '在吗');
browserResult('任务出错', '在吗', 'c');
assert.equal(alerts.length, before + 3, '错误通知也合并');

const shared = alerts.length;
for (const id of ['a', 'b']) {
  phase(id, 'running');
  browserResult('任务已完成', '同名任务');
  phase(id, 'done');
}
fallbackTimers.splice(0).forEach(fn => fn());
assert.deepEqual(alerts.slice(shared).map(args => args[4]), ['a', 'b'], '两个同名任务仍各收到一次');
browserResult('任务已完成', '仅收到网页通知的任务');
fallbackTimers.splice(0).forEach(fn => fn());
assert.equal(alerts.length, shared + 3, '网页单独上报时仍有提醒');
const beforeReplay = alerts.length;
phase('mixed', 'running', '在吗');
phase('mixed', 'completedSuccess', '在吗');
page.__zcFeed(JSON.stringify({ sessionId: 'mixed', phase: 'running' }));
page.__zcFeed(JSON.stringify({ sessionId: 'mixed', phase: 'completedSuccess' }));
assert.equal(alerts.length, beforeReplay + 1, '兼容数据流迟到的 running 不会重新开启已结束的一轮');
// 工作区列表订阅任务索引，提问期间 phase 仍为 running。
const inputStart = alerts.length;
function indexRequest(taskId, pendingInteraction, lastActivityAt) {
  const task = { meta: { taskId, title: '同名任务', pendingInteraction },
    activity: { phase: 'running', lastActivityAt } };
  const frame = Buffer.from('\0' + JSON.stringify({ deltas: [{ task }] })).toString('base64');
  page.__zcFeed(JSON.stringify({ type: 'data', payload: { dataBase64: frame } }));
}
const ask = { interactionId: 'q1', kind: 'userInput', toolName: 'AskUserQuestion' };
indexRequest('a', null, 1);
indexRequest('a', ask, 2);
indexRequest('a', ask, 2);
indexRequest('b', ask, 2); // 请求 ID 的作用域为任务。
page.__zcFeed(JSON.stringify({ sessionId: 'a', phase: 'running', pendingInteraction: ask }));
indexRequest('a', { ...ask, interactionId: 'q2' }, 3);
indexRequest('a', { ...ask, interactionId: 'stale' }, 1);
const plan = { interactionId: 'plan', kind: 'userInput', toolName: 'ExitPlanMode' };
page.__zcFeed(JSON.stringify({ sessionId: 'c', phase: 'running', pendingInteraction: plan }));
indexRequest('c', plan, 4);
function snapshot(id, interactionId, payload) {
  page.__zcFeed(JSON.stringify({ sessionId: id, meta: { title: '审批任务' },
    pendingInteractions: [{ interactionId, payload }] }));
}
snapshot('c', 'plan', { kind: 'userInput', schema: { interaction: 'plan_approval' } });
snapshot('d', 'p2', { kind: 'userInput', schema: { interaction: 'plan_approval' } });
snapshot('d', 'p3', { kind: 'permission' });
snapshot('d', 'ignored', { kind: 'workspaceHookReview' });
browserResult('计划等待确认', '审批任务', 'd');
fallbackTimers.splice(0).forEach(fn => fn());
assert.deepEqual(alerts.slice(inputStart).map(a => [a[1], a[4]]), [
  ['需要你的回复', 'a'], ['需要你的回复', 'b'], ['需要你的回复', 'a'],
  ['计划等待确认', 'c'], ['计划等待确认', 'd'], ['需要你的确认', 'd'],
]);
assert.equal(new Set(alerts.slice(inputStart).map(a => a[3])).size, 6);
const livePatch = { topic: 'conversation/c', payload: { kind: 'deltas', deltas: [
  { op: 'state.updated', patch: { pendingInteractions: [
    { interactionId: 'plan-2', payload: { kind: 'userInput', schema: { toolName: 'ExitPlanMode' } } },
  ] } },
] } };
page.__zcFeed(JSON.stringify(livePatch));
page.__zcFeed(JSON.stringify(livePatch));
assert.deepEqual(alerts.slice(inputStart + 6).map(a => [a[1], a[4]]), [['计划等待确认', 'c']]);
const countStart = alerts.length;
function counts(userInputCount, permissionCount) {
  page.__zcFeed(JSON.stringify({ meta: { taskId: 'count-only', title: '计数摘要' },
    activity: { phase: 'running', pendingInteractions: { userInputCount, permissionCount } } }));
}
counts(1, 0);
counts(1, 0);
fallbackTimers.splice(0).forEach(fn => fn());
snapshot('count-only', 'identified-later', { kind: 'userInput', toolName: 'ExitPlanMode' });
counts(0, 0);
counts(1, 0);
counts(1, 1);
fallbackTimers.splice(0).forEach(fn => fn());
assert.deepEqual(alerts.slice(countStart).map(a => a[1]), ['需要你的回复', '需要你的回复', '需要你的确认']);
assert.equal(new Set(alerts.slice(countStart).map(a => a[3])).size, 3);
await cachedCue.play();
await cachedCue.play();
assert.equal(mediaPlays, 0, '页面复用提示音对象也不会再响');
await new Media('/assets/preview.mp3').play();
assert.equal(mediaPlays, 1, '其它媒体仍按原方式播放');
console.log('任务完成、失败、用户提问、计划审批、权限请求、跨来源去重及点击路由通过');
