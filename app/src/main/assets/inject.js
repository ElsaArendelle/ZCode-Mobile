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
          try { b.notify('task', String(title || ''), String(this.body), 'ntf|' + String(title) + '|' + String(this.body)); } catch (e) {}
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
   * 页面自身通知管线在移动 web 构建里不触发（实测），改为直接观察 relay WS 数据帧：
   * workspace-list-updated 等帧内含 sessions[{sessionId, phase, title}]，
   * phase: completedSuccess / completedInterrupted / error。
   * 维护 sessionId→phase 映射，相位跃迁到完成/失败 → 原生通知。 */
  if (!window.__zcWsHooked) {
    window.__zcWsHooked = true;
    try {
      var OrigWS = window.WebSocket;
      var lastPhase = {};
      var seenTypes = {};

      function zcSend(title, body, key) {
        try {
          var b = window.ZCodeNative;
          if (b) b.notify('task', String(title || ''), String(body || ''), String(key || ''));
        } catch (e) {}
        try { console.log('[zc-notify] ' + title + ' | ' + body); } catch (e) {}
      }

      function isDone(ph) {
        return ph === 'completedSuccess' || ph === 'completedInterrupted' ||
          ph === 'completed' || ph === 'done';
      }
      function isFail(ph) {
        return ph === 'error' || ph === 'failed';
      }

      function notePhase(taskId, ph, name) {
        var prev = lastPhase[taskId];
        lastPhase[taskId] = ph;
        if (prev === undefined || prev === ph) return; // 首见只做基线，不打扰
        if (isDone(ph)) {
          zcSend('任务已完成', name ? ('任务：' + name) : '', 'ws|' + taskId + '|' + ph);
        } else if (isFail(ph)) {
          zcSend('任务出错', name ? ('任务：' + name) : '', 'ws|' + taskId + '|' + ph);
        }
      }

      function scan(obj, depth) {
        if (!obj || depth > 10 || typeof obj !== 'object') return;
        if (Array.isArray(obj)) {
          for (var i = 0; i < obj.length && i < 300; i++) scan(obj[i], depth + 1);
          return;
        }
        // 真实 schema：task.meta{taskId,title} + task.activity.phase / task.liveStatus
        var meta = obj.meta;
        var act = obj.activity;
        if (meta && typeof meta.taskId === 'string' &&
            (act && typeof act.phase === 'string' || typeof obj.liveStatus === 'string')) {
          var ph2 = (act && act.phase) || obj.liveStatus;
          notePhase(meta.taskId, ph2, meta.title || '');
        }
        // 兼容旧 schema：{sessionId, phase, title}
        var sid = obj.sessionId || obj.session_id;
        if (typeof sid === 'string' && typeof obj.phase === 'string') {
          notePhase(sid, obj.phase, obj.title || obj.name || '');
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

  /* ---------------- ⓪-c window.open 拦截：OAuth 授权页交给原生 Activity ----------------
   * 内嵌弹窗 WebView 与主 WebView 叠层时上层不绘制（白屏），改由独立 Activity 承载。
   * 仅拦截授权域名，其它 window.open 保持原生行为。 */
  if (!window.__zcOpenHooked) {
    window.__zcOpenHooked = true;
    try {
      var zcOrigOpen = window.open;
      window.open = function (url, name, features) {
        try {
          var s = String(url || '');
          var isAuth = /^https:\/\/([a-z0-9-]+\.)*(zchat\.z\.ai|chat\.z\.ai|bigmodel\.cn|dev\.bigmodel\.cn)\//i.test(s) ||
            s.indexOf('/oauth/authorize') !== -1 ||
            s.indexOf('/api/oauth/authorize') !== -1;
          if (isAuth) {
            var b = null;
            try { b = window.ZCodeNative; } catch (e) {}
            if (b && b.openAuth) {
              b.openAuth(s);
              return null;
            }
          }
        } catch (e) {}
        return zcOrigOpen.apply(window, arguments);
      };
    } catch (e) {}
  }

  /* ---------------- ① Audio 钩子（只装一次，越早越好） ---------------- */
  if (!window.__zcAudioHooked) {
    window.__zcAudioHooked = true;
    try {
      var OrigAudio = window.Audio;
      if (typeof OrigAudio === 'function') {
        var Hooked = function (url) {
          try {
            if (String(url || '').indexOf('task-notification-pop') !== -1) {
              window.__zcPopAt = Date.now();
              try {
                var b0 = bridge();
                if (b0) b0.notify('pop', '', '', 'pop|' + Math.floor(Date.now() / 5000));
              } catch (e) {}
            }
          } catch (e) {}
          return new OrigAudio(url);
        };
        Hooked.prototype = OrigAudio.prototype;
        window.Audio = Hooked;
      }
    } catch (e) {}
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

  function send(kind, title, body, key, windowMs) {
    var b = bridge();
    if (!b) return;
    var k = key || (kind + '|' + (title || '') + '|' + (body || ''));
    if (isDup(k, windowMs)) return;
    try { b.notify(String(kind), String(title || ''), String(body || ''), String(k)); } catch (e) {}
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
      send('input', '需要你的确认', textOf(node, 200), 'dialog|' + Math.floor(Date.now() / 8000), 8000);
      return;
    }
    if (tid && TID_BADGES.indexOf(tid) !== -1) {
      send('pending', '有任务等待你处理', '', 'badge|' + Math.floor(Date.now() / 8000), 8000);
      return;
    }

    var t = textOf(node, 160);
    if (!t) return;

    // 任务完成/失败类通知已由 ⓪ Notification 垫片全覆盖（hasFocus 恒 false 后必然触发），
    // 此处不再做文案匹配，避免双重通知。仅保留被踢检测。
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
