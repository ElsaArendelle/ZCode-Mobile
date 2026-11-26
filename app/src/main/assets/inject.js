/**
 * ZCode Mobile 注入脚本（只读观察，不改页面任何内容）。
 *
 * 依据桌面 app.asar 逆向结论（renderer 与移动页共用同一套通知管线）：
 * - 页面不用浏览器 Notification API（0 处 new Notification）、不改 document.title、不震动；
 * - 权限/输入请求的页面载体：data-testid="v4-user-input-dialog" 模态；
 * - 待审批未读徽标：v4-workspace-hook-pending-unread / v4-workspace-mcp-pending-unread；
 * - 页面自带提示音：new Audio('task-notification-pop-*.mp3')，localStorage 开关默认开；
 * - 通知文案（zh）：任务已完成 / 任务出错 / 需要你的确认 / 需要你的回复 / 计划等待确认 / 任务等待你的确认 / 任务：{title}。
 *
 * 注入时机：onPageStarted 注入（抢 Audio 钩子），onPageFinished 再注一次（兜 observer），
 * 脚本幂等，重复注入无副作用。
 */
(function () {
  'use strict';

  function bridge() {
    try { return window.ZCodeNative || null; } catch (e) { return null; }
  }

  // 系统通知跨越页面重载；用任务 ID 选择行，并等会话实际挂载后才确认打开。
  var taskNames = window.__zcTaskNames || (window.__zcTaskNames = new Map());
  function idForBody(body) {
    var title = String(body || '').replace(/^任务[：:]\s*/, '').trim();
    var id = '';
    for (var entry of taskNames) {
      if (entry[1] !== title || !title) continue;
      if (id) return ''; // 重名时保留消息详情，避免误开。
      id = entry[0];
    }
    return id;
  }

  // 两条来源共用同一份短期结果记录。重复注入不会重置它。
  var results = window.__zcRecentResults || (window.__zcRecentResults = { entries: [], sequence: 0 });
  var interactions = window.__zcInteractionAlerts || (window.__zcInteractionAlerts = { seen: new Set(), recent: [] });
  function reportInteraction(source, title, body, taskId, requestId) {
    var key = requestId ? JSON.stringify(['interaction', taskId, requestId]) : '';
    if (key) {
      if (interactions.seen.has(key)) return;
      interactions.seen.add(key);
      if (interactions.seen.size > 1024) interactions.seen.delete(interactions.seen.values().next().value);
    }
    var now = Date.now();
    interactions.recent = interactions.recent.filter(e => now - e.at < 10000);
    // 网页通知和旧版弹窗可能没有请求 ID。每条回退提醒只与另一来源配对一次。
    var match = interactions.recent.find(e =>
      !e.sources.has(source) && !(key && e.key) &&
      (taskId && e.taskId ? taskId === e.taskId : body && body === e.body) &&
      (title === e.title || source === 'dialog' || e.sources.has('dialog') ||
        ((source === 'summary' || e.sources.has('summary')) &&
         [title, e.title].includes('计划等待确认') && [title, e.title].includes('需要你的回复'))));
    if (match) {
      match.sources.add(source);
      if (key) match.key = key;
      return;
    }
    interactions.recent.push({ at: now, key, title, body, taskId, sources: new Set([source]) });
    var fallbackKey = source === 'summary' ? 'summary|' + taskId + '|' + now + '|' + ++results.sequence :
      'input|' + source + '|' + taskId + '|' + title + '|' + body;
    bridge()?.notifyTask('input', title, body, key || fallbackKey, taskId);
  }
  function noteInteraction(taskId, request, name) {
    if (!request || typeof request.interactionId !== 'string' || !request.interactionId) return;
    var payload = request.payload || request;
    if (payload.kind !== 'userInput' && payload.kind !== 'permission') return;
    var plan = payload.toolName === 'ExitPlanMode' || payload.schema?.interaction === 'plan_approval' ||
      payload.schema?.toolName === 'ExitPlanMode';
    var title = plan ? '计划等待确认' : payload.kind === 'permission' ? '需要你的确认' : '需要你的回复';
    name = name || taskNames.get(taskId);
    reportInteraction('relay', title, name ? '任务：' + name : '', taskId, request.interactionId);
  }
  function reportResult(source, outcome, title, body, id) {
    var now = Date.now();
    results.entries = results.entries.filter(function (e) { return now - e.at < 10000; });
    var duplicate = results.entries.find(function (e) {
      if (e.outcome !== outcome) return false;
      if (id && e.id) return id === e.id;
      // 网页有时只提供标题。仅与尚未配对的另一来源匹配，避免吞掉同名任务。
      return body && e.body === body && !e.sources.has(source);
    });
    if (duplicate) {
      duplicate.sources.add(source);
      if (id) duplicate.id = id;
      return;
    }
    results.entries.push({ at: now, id: id, outcome: outcome, body: body, sources: new Set([source]) });
    var key = ['result', source, id, outcome, now, ++results.sequence].join('|');
    bridge()?.notifyTask('task', title, body, key, id);
  }
  window.__zcOpenAlert = function (token, id, body) {
    id = id || idForBody(body);
    if (!id) return 'detail';
    var nav = window.__zcAlertNavigation;
    if (!nav || nav.token !== token) nav = window.__zcAlertNavigation = {token: token};
    var chat = history.state && history.state.zcodeMobilePage === 'chat';
    if (chat) {
      var session = document.querySelector('[data-session-id="' + CSS.escape(id) + '"]');
      if (session) return 'opened';
      if (!nav.clicked && !nav.back) {
        nav.back = true;
        history.back();
      }
      return 'wait';
    }
    var row = document.querySelector('[data-testid="' + CSS.escape('task-item-' + id) + '"]');
    if (row && !row.disabled && !nav.clicked) {
      nav.clicked = true;
      row.click();
      return 'wait';
    }
    // 折叠项目和其它列表视图可能没有对应行；使用工作区自己的跨项目搜索。
    var search = document.querySelector('[data-testid="sidebar-search-open"]');
    var title = taskNames.get(id) || String(body || '').replace(/^任务[：:]\s*/, '').trim();
    if (!nav.clicked && search && title && !row) {
      if (!nav.search) { nav.search = true; search.click(); return 'wait'; }
      var input = document.querySelector('[cmdk-input]');
      if (input && !nav.typed) {
        nav.typed = true;
        Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(input, title);
        input.dispatchEvent(new Event('input', {bubbles: true}));
      }
      var results = document.querySelectorAll('[role="option"][data-value^="task-result:"]');
      for (var result of results) {
        if (result.getAttribute('data-value').endsWith(':' + id)) {
          nav.clicked = true;
          result.click();
          break;
        }
      }
    }
    return 'wait';
  };

  /* ---------------- ⓪ Notification 垫片（web 平台通知依赖，越早越好） ----------------
   * 页面 web 模式 showTaskNotification 的实现：
   *   if (!document.hasFocus() && Notification.permission==='granted') new Notification(title,{body})
   * WebView 里 Notification 权限恒为 denied → 页面通知永不触发。
   * 垫片：伪装权限已授予，new Notification 时转交原生（系统通知+官方提示音）。
   * 前台时页面 hasFocus=true 不走此路径（权限请求有 DOM 弹窗，另由 observer 捕获）。 */
  if (!window.__zcNotifShim) {
    window.__zcNotifShim = true;
    try {
      function ZCNotification(title, options) {
        if (!(this instanceof ZCNotification)) return new ZCNotification(title, options);
        this.title = title;
        this.body = (options && options.body) || '';
        var b = null;
        try { b = window.ZCodeNative; } catch (e) {}
        try { console.log('[zc-ntf-ctor] ' + String(title || '') + ' | ' + String(this.body || '')); } catch (e) {}
        if (b) {
          try {
            var id = String(options?.data?.taskId || idForBody(this.body));
            var outcome = title === '任务已完成' ? 'done' : title === '任务出错' ? 'failed' : '';
            if (outcome && !id) {
              // 先给携带 ID 的中继帧一个到达机会，保留点击通知时的任务定位。
              setTimeout(() => reportResult('web', outcome, title, this.body, idForBody(this.body)), 350);
            } else if (outcome) {
              reportResult('web', outcome, title, this.body, id);
            } else if (['需要你的回复', '需要你的确认', '计划等待确认', '任务等待你的确认'].includes(title)) {
              var owner = document.querySelector('[data-session-id]');
              var ownerId = id || owner?.getAttribute('data-session-id') || '';
              setTimeout(() => reportInteraction('web', title, this.body, ownerId, options?.data?.requestId), 350);
            } else {
              b.notifyTask('task', String(title || ''), this.body, 'ntf|' + title + '|' + this.body, id);
            }
          } catch (e) {}
        }
        this.close = function () {};
        this.onclick = null;
      }
      ZCNotification.permission = 'granted';
      ZCNotification.requestPermission = function (cb) {
        if (cb) cb('granted');
        try { return Promise.resolve('granted'); } catch (e) { return undefined; }
      };
      ZCNotification.maxActions = 0;
      Object.defineProperty(window, 'Notification', {
        get: function () { return ZCNotification; },
        configurable: true,
      });
      // 前台抑制破解：页面通知门控是 !document.hasFocus()——用户正开着 App 时
      // (哪怕看的是另一个任务) 通知被跳过。手机使用习惯要求前台也提醒，
      // 恒返回 false 让通知一律触发（可见性走 visibilityState，不受影响）。
      try {
        document.hasFocus = function () { return false; };
      } catch (e) {}
    } catch (e) {}
  }

  /* ---------------- ⓪-b WebSocket 嗅探（任务完成/失败主通道） ----------------
   * 中继任务状态和网页通知可能同时送达，均交给 reportResult：
   * workspace-list-updated 等帧内含 sessions[{sessionId, phase, title}]，
   * phase: completedSuccess / completedInterrupted / error。
   * 维护 sessionId→phase 映射，相位跃迁到完成/失败 → 原生通知。 */
  if (!window.__zcWsHooked) {
    window.__zcWsHooked = true;
    try {
      var OrigWS = window.WebSocket;
      var lastPhase = {};
      var taskVersions = new Map();
      var pendingCounts = new Map();
      var seenTypes = {};

      function noteSummary(taskId, request, counts, name) {
        noteInteraction(taskId, request, name);
        // 有些任务索引只有分类计数。仅在对应计数增加时提醒，优先保留带请求 ID 的详情。
        if (!counts || !(counts.userInputCount || counts.permissionCount)) {
          pendingCounts.delete(taskId);
          return;
        }
        var state = pendingCounts.get(taskId) || {};
        pendingCounts.set(taskId, state);
        for (const [field, kind, title] of [
          ['userInputCount', 'userInput', '需要你的回复'], ['permissionCount', 'permission', '需要你的确认'],
        ]) {
          const count = counts[field] || 0;
          const previous = state[field]?.count || 0;
          if (count === previous) continue;
          const change = state[field] = { count };
          if (count < previous || (request?.interactionId && request.kind === kind)) continue;
          setTimeout(() => {
            if (pendingCounts.get(taskId)?.[field] === change) {
              reportInteraction('summary', title, name ? '任务：' + name : '', taskId, '');
            }
          }, 350);
        }
      }
      function isDone(ph) {
        return ph === 'completedSuccess' || ph === 'completedInterrupted' ||
          ph === 'completed' || ph === 'done';
      }
      function isFail(ph) {
        return ph === 'error' || ph === 'failed';
      }

      function notePhase(taskId, ph, name) {
        if (name) taskNames.set(taskId, name);
        var prev = lastPhase[taskId];
        lastPhase[taskId] = ph;
        if (prev === undefined || prev === ph) return; // 首见只做基线，不打扰
        var body = name ? ('任务：' + name) : '';
        if (isDone(ph) && !isDone(prev)) {
          reportResult('relay', 'done', '任务已完成', body, taskId);
        } else if (isFail(ph) && !isFail(prev)) {
          reportResult('relay', 'failed', '任务出错', body, taskId);
        } else if (ph === 'running' && (isDone(prev) || isFail(prev))) {
          results.entries = results.entries.filter(function (e) { return e.id !== taskId; });
        }
      }

      function scan(obj, depth) {
        if (!obj || depth > 10 || typeof obj !== 'object') return;
        if (Array.isArray(obj)) {
          for (var i = 0; i < obj.length && i < 300; i++) scan(obj[i], depth + 1);
          return;
        }
        // 会话增量不重复携带 sessionId，目标任务由订阅主题指定。
        if (typeof obj.topic === 'string' && obj.topic.startsWith('conversation/') &&
            obj.payload?.kind === 'deltas' && Array.isArray(obj.payload.deltas)) {
          var conversationId = obj.topic.slice('conversation/'.length);
          for (var delta of obj.payload.deltas) {
            if (conversationId && delta?.op === 'state.updated' && Array.isArray(delta.patch?.pendingInteractions)) {
              delta.patch.pendingInteractions.forEach(request => noteInteraction(conversationId, request, delta.patch.meta?.title));
            }
          }
        }
        // 真实 schema：task.meta{taskId,title} + task.activity.phase / task.liveStatus
        var meta = obj.meta;
        var act = obj.activity;
        if (meta && typeof meta.taskId === 'string' &&
            (act && typeof act.phase === 'string' || typeof obj.liveStatus === 'string')) {
          var ph2 = (act && act.phase) || obj.liveStatus;
          var version = Number(act?.lastActivityAt || meta.updatedAt || 0);
          var previousVersion = taskVersions.get(meta.taskId) || 0;
          if (!version || version >= previousVersion) {
            taskVersions.set(meta.taskId, version || previousVersion);
            notePhase(meta.taskId, ph2, meta.title || '');
            noteSummary(meta.taskId, meta.pendingInteraction, act?.pendingInteractions, meta.title);
          }
        }
        // 兼容旧 schema：{sessionId, phase, title}
        var sid = obj.sessionId || obj.session_id;
        if (typeof sid === 'string') {
          if (typeof obj.phase === 'string') {
            if (!taskVersions.has(sid)) notePhase(sid, obj.phase, obj.title || obj.name || '');
            if (!obj.lastActivityAt || obj.lastActivityAt >= (taskVersions.get(sid) || 0)) {
              if (taskVersions.has(sid)) noteInteraction(sid, obj.pendingInteraction, obj.title);
              else noteSummary(sid, obj.pendingInteraction, obj.pendingInteractionSummary, obj.title);
            }
          }
          if (Array.isArray(obj.pendingInteractions)) {
            obj.pendingInteractions.forEach(request => noteInteraction(sid, request, meta?.title));
          }
        }
        for (var k in obj) {
          if (Object.prototype.hasOwnProperty.call(obj, k)) {
            var v = obj[k];
            if (v && typeof v === 'object') scan(v, depth + 1);
          }
        }
      }

      /* 从二进制（base64 解码后）文本中抽出所有顶层 JSON 对象 */
      function extractJsonObjects(text) {
        var out = [];
        var i = 0;
        while (i < text.length && out.length < 6) {
          var start = text.indexOf('{', i);
          if (start < 0) break;
          var depth = 0, inStr = false, esc = false, end = -1;
          for (var j = start; j < text.length; j++) {
            var c = text.charAt(j);
            if (inStr) {
              if (esc) esc = false;
              else if (c === '\\') esc = true;
              else if (c === '"') inStr = false;
            } else if (c === '"') inStr = true;
            else if (c === '{') depth++;
            else if (c === '}') { depth--; if (depth === 0) { end = j; break; } }
          }
          if (end < 0) break;
          out.push(text.slice(start, end + 1));
          i = end + 1;
        }
        return out;
      }

      function decodeB64Text(b64) {
        try {
          var bin = atob(b64);
          var bytes = new Uint8Array(bin.length);
          for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          return new TextDecoder('utf-8').decode(bytes);
        } catch (e) { return ''; }
      }

      function consumeJson(text) {
        var objs = extractJsonObjects(text);
        for (var i = 0; i < objs.length; i++) {
          try { scan(JSON.parse(objs[i]), 0); } catch (e) {}
        }
      }

      var decodeOk = 0, decodeFail = 0;
      function handleFrame(data) {
        if (typeof data !== 'string' || data.length < 16) return;
        // 外层信封（明文 JSON）
        if (data.indexOf('"dataBase64"') !== -1) {
          var m = /"dataBase64":"([A-Za-z0-9+\/=]+)"/.exec(data);
          if (m) {
            var inner = decodeB64Text(m[1]);
            if (inner) {
              decodeOk++;
              consumeJson(inner);
              return;
            }
            decodeFail++;
          }
        }
        // 明文帧（兼容）
        consumeJson(data);
        try {
          var j = JSON.parse(data);
          var zt = j && (j.zcode_type || (j.payload && j.payload.zcode_type));
          if (zt && !seenTypes[zt]) { seenTypes[zt] = 1; console.log('[zc-ws] ' + zt); }
        } catch (e) {}
      }
      // 测试/诊断入口
      window.__zcFeed = handleFrame;
      window.__zcStats = function () { return { ok: decodeOk, fail: decodeFail, tracked: Object.keys(lastPhase).length }; };

      function WrappedWS(url, protocols) {
        var ws = (protocols === undefined) ? new OrigWS(url) : new OrigWS(url, protocols);
        try {
          var origAdd = ws.addEventListener;
          ws.addEventListener = function (type, fn, opts) {
            if (type === 'message' && typeof fn === 'function') {
              var w = function (ev) { try { handleFrame(ev.data); } catch (e) {} return fn.call(this, ev); };
              return origAdd.call(ws, type, w, opts);
            }
            return origAdd.call(ws, type, fn, opts);
          };
          var proto = Object.getPrototypeOf(ws);
          var desc = Object.getOwnPropertyDescriptor(proto, 'onmessage');
          if (desc && desc.set) {
            Object.defineProperty(ws, 'onmessage', {
              configurable: true,
              get: function () { return desc.get.call(ws); },
              set: function (fn) {
                if (typeof fn === 'function') {
                  desc.set.call(ws, function (ev) { try { handleFrame(ev.data); } catch (e) {} return fn.call(this, ev); });
                } else {
                  desc.set.call(ws, fn);
                }
              },
            });
          }
        } catch (e) {}
        return ws;
      }
      WrappedWS.prototype = OrigWS.prototype;
      WrappedWS.CONNECTING = 0; WrappedWS.OPEN = 1; WrappedWS.CLOSING = 2; WrappedWS.CLOSED = 3;
      window.WebSocket = WrappedWS;
    } catch (e) {}
  }

  // 网页会缓存 Audio 并反复 play()。任务提示音由系统通知通道统一播放。
  if (!window.__zcAudioHooked && window.HTMLMediaElement) {
    window.__zcAudioHooked = true;
    var media = window.HTMLMediaElement.prototype;
    var play = media.play;
    media.play = function () {
      var path = (this.currentSrc || this.src || '').split(/[?#]/, 1)[0];
      if (/(?:^|\/)task-notification-pop(?:-[\w-]+)?\.mp3$/i.test(path)) return Promise.resolve();
      return play.apply(this, arguments);
    };
  }

  /* ---------------- ② MutationObserver（已存在则整个脚本跳过） ---------------- */
  if (window.__zcObserver) return;

  var seen = {};
  var seenCount = 0;
  function isDup(key, windowMs) {
    var now = Date.now();
    if (seen[key] && now - seen[key] < (windowMs || 10000)) return true;
    if (seenCount > 300) { seen = {}; seenCount = 0; }
    if (!seen[key]) seenCount++;
    seen[key] = now;
    return false;
  }

  function send(kind, title, body, key, windowMs, taskId) {
    var b = bridge();
    if (!b) return;
    var k = key || (kind + '|' + (title || '') + '|' + (body || ''));
    if (isDup(k, windowMs)) return;
    try { b.notifyTask(String(kind), String(title || ''), String(body || ''), String(k), taskId || ''); } catch (e) {}
  }

  var TID_DIALOG = 'v4-user-input-dialog';
  var TID_BADGES = ['v4-workspace-hook-pending-unread', 'v4-workspace-mcp-pending-unread'];

  /* 页面文案替换（用户要求的页面改动：登录页原生化） */
  var TEXT_REPLACEMENTS = {
    '通过浏览器授权登录': '登录',
    '登录后继续使用 Web 远程控制': '登录 ZCode',
    '请选择与电脑端相同的登录平台，并使用同一账号。': '使用与桌面端相同的账号继续',
  };

  function fixTexts(root) {
    try {
      var t = root.textContent || '';
      var hit = false;
      for (var k in TEXT_REPLACEMENTS) {
        if (t.indexOf(k) !== -1) { hit = true; break; }
      }
      if (!hit) return;
      var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null);
      var n;
      while ((n = walker.nextNode())) {
        var v = n.nodeValue || '';
        for (var key in TEXT_REPLACEMENTS) {
          if (v.indexOf(key) !== -1) v = v.split(key).join(TEXT_REPLACEMENTS[key]);
        }
        if (v !== n.nodeValue) n.nodeValue = v;
      }
    } catch (e) {}
  }

  var NOTIF_TEXTS = [
    '任务已完成', '任务出错', '需要你的确认', '需要你的回复',
    '计划等待确认', '任务等待你的确认',
  ];
  var KICK_TEXTS = ['重新生成二维码', '已被踢出', '已踢出'];

  function textOf(el, max) {
    try {
      var t = (el.textContent || '').replace(/\s+/g, ' ').trim();
      return t.length > max ? t.slice(0, max) : t;
    } catch (e) { return ''; }
  }

  function matchNotif(t) {
    if (!t) return null;
    for (var i = 0; i < NOTIF_TEXTS.length; i++) {
      if (t.indexOf(NOTIF_TEXTS[i]) !== -1) return NOTIF_TEXTS[i];
    }
    // 桌面标题模板「任务：{title}」（toast/横幅形态）
    if (t.length <= 120 && t.slice(0, 3) === '任务：') return t;
    return null;
  }

  /* ---------------- ③ 登录页原生化（用户要求的页面改造） ---------------- */
  var LOGIN_CSS = [
    'section[data-zc-login]{border:none!important;background:transparent!important;',
    'box-shadow:none!important;border-radius:0!important;padding:16px 4px!important;',
    'max-width:20rem!important;animation:zcFade .25s ease}',
    '@keyframes zcFade{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}',
    'section[data-zc-login] .mb-5.flex{flex-direction:column;align-items:center;gap:14px;margin-bottom:26px}',
    'section[data-zc-login] img[data-app-logo]{width:64px!important;height:64px!important}',
    'section[data-zc-login] .min-w-0{display:flex;flex-direction:column;align-items:center;gap:8px;text-align:center}',
    'section[data-zc-login] .min-w-0>.text-ui-xs{display:none}',
    'section[data-zc-login] h1{font-size:21px!important;font-weight:600!important;margin:0!important}',
    'section[data-zc-login] p.mb-5{font-size:13px!important;line-height:1.6!important;',
    'color:#8e8e95!important;text-align:center;margin:0 0 26px!important}',
    'section[data-zc-login] div[role=group]{background:rgba(255,255,255,.06);border-radius:12px;padding:3px}',
    'section[data-zc-login] div[role=group] button{flex:1 1 0!important;height:38px!important;',
    'border-radius:9px!important;font-size:14px!important}',
    'section[data-zc-login] div[role=group] button[aria-pressed=true]{',
    'background:rgba(255,255,255,.16)!important;border-color:rgba(255,255,255,.38)!important;',
    'box-shadow:0 1px 4px rgba(0,0,0,.5);color:#fff!important;font-weight:600!important}',
    'section[data-zc-login] div[role=group] button[aria-pressed=false]{',
    'background:transparent!important;border-color:transparent!important;color:#a6a6ad!important}',
    // 浅色系统（页面浅色主题）适配
    '@media (prefers-color-scheme: light){',
    'section[data-zc-login] p.mb-5{color:#6b6b70!important}',
    'section[data-zc-login] div[role=group]{background:rgba(0,0,0,.05)}',
    'section[data-zc-login] div[role=group] button[aria-pressed=true]{',
    'background:#fff!important;border-color:rgba(0,0,0,.12)!important;',
    'box-shadow:0 1px 4px rgba(0,0,0,.15);color:#1a1a1e!important}',
    'section[data-zc-login] div[role=group] button[aria-pressed=false]{color:#8a8a90!important}',
    '}',
    'section[data-zc-login] div[role=group] ~ button[data-slot=button]{width:100%!important;',
    'height:48px!important;border-radius:14px!important;font-size:16px!important;font-weight:600!important}',
  ].join('');

  function injectLoginStyle() {
    if (document.getElementById('zc-style')) return;
    var s = document.createElement('style');
    s.id = 'zc-style';
    s.textContent = LOGIN_CSS;
    (document.head || document.documentElement).appendChild(s);
  }

  /** 给登录卡片打标记（选择器锚点），幂等；登录页重新挂载时会再次命中。 */
  function beautifyLogin() {
    try {
      var logo = document.querySelector('img[data-app-logo="zcode"]');
      if (!logo) return;
      var sec = logo.closest('section');
      if (!sec || sec.hasAttribute('data-zc-login')) return;
      sec.setAttribute('data-zc-login', '1');
      injectLoginStyle();
    } catch (e) {}
  }

  function handleNode(node) {
    if (!node || node.nodeType !== 1) return;

    // 文案替换（React 重渲染后持续生效）+ 登录页原生化
    fixTexts(node);
    beautifyLogin();

    var tid = null;
    try { tid = node.getAttribute('data-testid'); } catch (e) {}

    if (tid === TID_DIALOG) {
      // 权限 / 输入请求模态（fixed inset-0 z-[100]）
      var owner = node.closest('[data-session-id]') || document.querySelector('[data-session-id]');
      var ownerId = owner?.getAttribute('data-session-id') || '';
      var dialogBody = textOf(node, 200);
      setTimeout(() => reportInteraction('dialog', '需要你的确认', dialogBody, ownerId, ''), 350);
      return;
    }
    if (tid && TID_BADGES.indexOf(tid) !== -1) {
      send('pending', '有任务等待你处理', '', 'badge|' + Math.floor(Date.now() / 8000), 8000);
      return;
    }

    var t = textOf(node, 160);
    if (!t) return;

    // 完成和失败由中继或网页通知上报，此处只检测被踢提示。
    for (var i = 0; i < KICK_TEXTS.length; i++) {
      if (t.indexOf(KICK_TEXTS[i]) !== -1) {
        try { bridge() && bridge().onKicked(); } catch (e) {}
        break;
      }
    }
  }

  function startObserver() {
    if (!document.documentElement || typeof MutationObserver === 'undefined') {
      setTimeout(startObserver, 150);
      return;
    }
    var mo = new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        // 文本节点内容变化（无新增节点）：补一次文案替换
        if (muts[i].type === 'characterData') {
          try { fixTexts(muts[i].target.parentElement || document.documentElement); } catch (e) {}
          continue;
        }
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) {
          try { handleNode(added[j]); } catch (e) {}
        }
      }
    });
    mo.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    window.__zcObserver = mo;
    // 页面已渲染完的部分（observer 注册前插入的节点）补一次替换与美化
    fixTexts(document.documentElement);
    beautifyLogin();
  }

  startObserver();
})();
