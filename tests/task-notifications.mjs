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
await cachedCue.play();
await cachedCue.play();
assert.equal(mediaPlays, 0, '页面复用提示音对象也不会再响');
await new Media('/assets/preview.mp3').play();
assert.equal(mediaPlays, 1, '其它媒体仍按原方式播放');
console.log('完成和失败去重、连续两轮、同名任务、单来源提醒及点击路由通过');
