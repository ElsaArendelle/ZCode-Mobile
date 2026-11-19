// 在已安装 debug APK 的模拟器上验证原生 OAuth 窗口；弹窗先加载公开的 robots.txt，回调响应由 CDP 提供，无需账号授权。
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const adb = process.env.ADB || `${process.env.LOCALAPPDATA}/Android/Sdk/platform-tools/adb.exe`;
const serial = process.env.ANDROID_SERIAL || 'emulator-5554';
const pkg = 'com.autosquare.zcode.mobile';
const port = 9333;
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));
const shell = (...args) => execFileSync(adb, ['-s', serial, ...args], { encoding: 'utf8' }).trim();
async function until(fn, label) {
  for (let i = 0; i < 100; i++) {
    const value = await fn();
    if (value) return value;
    await pause(100);
  }
  throw new Error(`等待超时：${label}`);
}
async function connect(page) {
  const ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = reject; });
  let seq = 0;
  const pending = new Map();
  const client = {
    call(method, params = {}) {
      return new Promise((resolve, reject) => {
        const id = ++seq;
        pending.set(id, { resolve, reject });
        ws.send(JSON.stringify({ id, method, params }));
      });
    },
    async eval(expression) {
      const result = await this.call('Runtime.evaluate', { expression, returnByValue: true });
      if (result.exceptionDetails) throw new Error(result.exceptionDetails.text);
      return result.result.value;
    },
    close: () => ws.close(),
  };
  ws.onmessage = async event => {
    const message = JSON.parse(event.data);
    if (pending.has(message.id)) {
      const request = pending.get(message.id);
      pending.delete(message.id);
      message.error ? request.reject(new Error(message.error.message)) : request.resolve(message.result);
    } else if (message.method === 'Fetch.requestPaused') {
      await client.call('Fetch.fulfillRequest', {
        requestId: message.params.requestId, responseCode: 200,
        responseHeaders: [{ name: 'Content-Type', value: 'text/html; charset=utf-8' }],
        body: Buffer.from('<!doctype html><meta name="viewport" content="width=device-width"><body>OAuth 测试页面</body>').toString('base64'),
      }).catch(() => {});
    }
  };
  return client;
}

shell('shell', 'am', 'force-stop', pkg);
shell('shell', 'am', 'start', '-n', `${pkg}/.MainActivity`);
const pid = await until(() => { try { return shell('shell', 'pidof', pkg); } catch { return null; } }, '应用启动');
shell('forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`);
const pages = async () => { try { return await (await fetch(`http://127.0.0.1:${port}/json`)).json(); } catch { return []; } };
async function attachPage(predicate, label) {
  return until(async () => {
    const page = (await pages()).find(predicate);
    if (!page) return null;
    try { return { page, client: await connect(page) }; } catch { return null; }
  }, label);
}
const { page, client: main } = await attachPage(p => p.type === 'page', '主 WebView');
try {
  await main.call('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
  await main.call('Page.navigate', { url: 'https://zcode.z.ai/remote/v4?sid=oauth-regression-test' });
  await until(() => main.eval("document.readyState === 'complete' && document.body.innerText.includes('测试页面')"), '测试页面');
  await main.eval("window.__oauthRegressionMarker = 'alive'");
  // 第一次授权完成时不能刷新主页面；真实页面仍需继续轮询并存储登录凭据。
  await main.eval("window.testAuthWindow = window.open('https://zcode.z.ai/robots.txt', '_blank'); !!window.testAuthWindow");
  const { page: popupPage, client: popup } = await attachPage(
    p => p.id !== page.id && p.url.endsWith('/robots.txt') && JSON.parse(p.description || '{}').attached, '授权窗口',
  );
  await popup.call('Fetch.enable', { patterns: [{ urlPattern: '*' }] });
  await pause(9000);
  assert.equal(await main.eval('window.testAuthWindow.closed'), false, '等待用户操作时授权窗口提前关闭');
  await popup.call('Page.navigate', { url: 'https://zcode.z.ai/api/v1/oauth/cli/callback/zai_cn?code=test-code&state=test-state' });
  await until(async () => !(await pages()).some(p => p.id === popupPage.id), '授权完成关窗');
  popup.close();
  assert.equal(await main.eval('window.__oauthRegressionMarker'), 'alive', '授权完成后主页面被刷新，轮询状态丢失');
  console.log('PASS 授权返回保留主页面与轮询状态');
  // 第二次授权也必须使用独立窗口，不能覆盖原主页面。
  await main.eval("window.testAuthWindow = window.open('https://zcode.z.ai/robots.txt', '_blank'); !!window.testAuthWindow");
  await pause(1200);
  assert.equal(await main.eval('window.__oauthRegressionMarker'), 'alive', '第二次授权覆盖了主页面');
  assert.ok((await pages()).some(p => p.id !== page.id && JSON.parse(p.description || '{}').attached), '第二次授权没有独立窗口');
  console.log('PASS 重复授权始终使用独立窗口');
} finally {
  main.close();
  shell('shell', 'am', 'force-stop', pkg);
  shell('forward', '--remove', `tcp:${port}`);
}
