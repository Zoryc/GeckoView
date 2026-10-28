'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { createBackground, topUrlOf } = require('../../main/assets/extensions/snapcast_detector/background.js');

const TAB = 7;
const PAGE = 'https://site.example/watch/1';

function event() {
  const listeners = [];
  return {
    listeners,
    addListener(listener, ...extra) {
      listeners.push({ listener, extra });
    },
  };
}

function fakeTimers() {
  let queue = [];
  let nextId = 1;
  return {
    setTimeout(fn, ms) {
      const id = nextId++;
      queue.push({ id, fn, ms });
      return id;
    },
    clearTimeout(id) {
      queue = queue.filter((t) => t.id !== id);
    },
    /** Runs the timers that are due within [maxMs] (all by default). */
    run(maxMs) {
      const due = queue.filter((t) => maxMs === undefined || t.ms <= maxMs);
      queue = queue.filter((t) => !due.includes(t));
      for (const t of due) t.fn();
    },
    get size() {
      return queue.length;
    },
  };
}

function fakeBrowser() {
  const browser = {
    sent: [],
    respond: () => Promise.resolve(true),
    tabs: {
      onRemoved: event(),
      sendMessage(tabId, message, options) {
        browser.sent.push({ tabId, message, options });
        return browser.respond(tabId, message, options);
      },
    },
    runtime: { onMessage: event() },
    webRequest: {
      onSendHeaders: event(),
      onHeadersReceived: event(),
      onCompleted: event(),
      onErrorOccurred: event(),
    },
  };
  return browser;
}

function setup(options) {
  const browser = fakeBrowser();
  const timers = fakeTimers();
  let clock = 1000;
  const bg = createBackground(browser, Object.assign({
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
    now: () => clock,
  }, options || {}));
  return {
    browser,
    timers,
    bg,
    advance(ms) {
      clock += ms;
    },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

function headerList(map) {
  return Object.keys(map).map((name) => ({ name, value: map[name] }));
}

let nextRequestId = 1;

/** Simulates one request: onSendHeaders then onHeadersReceived. */
function request(bg, init) {
  const details = Object.assign({
    requestId: String(nextRequestId++),
    tabId: TAB,
    frameId: 0,
    type: 'media',
    documentUrl: PAGE,
    originUrl: PAGE,
    frameAncestors: [],
    statusCode: 200,
  }, init);
  bg.onSendHeaders(Object.assign({}, details, { requestHeaders: headerList(init.requestHeaders || {}) }));
  bg.onHeadersReceived(Object.assign({}, details, { responseHeaders: headerList(init.responseHeaders || {}) }));
  return details;
}

function hello(bg, url, title) {
  return bg.onRuntimeMessage({ kind: 'snapcast-hello', url: url || PAGE, title }, { tab: { id: TAB }, frameId: 0, url: url || PAGE });
}

function deliveredItems(browser) {
  return browser.sent
    .filter((s) => s.message.kind === 'snapcast-deliver' && s.message.message.type === 'media')
    .flatMap((s) => s.message.message.items);
}

test('start registers the webRequest, runtime and tabs listeners', () => {
  const { browser, bg } = setup();
  bg.start();
  const send = browser.webRequest.onSendHeaders.listeners[0];
  assert.deepEqual(send.extra, [{ urls: ['<all_urls>'], types: ['main_frame', 'sub_frame', 'media', 'xmlhttprequest', 'object', 'other'] }, ['requestHeaders']]);
  const received = browser.webRequest.onHeadersReceived.listeners[0];
  assert.deepEqual(received.extra[1], ['responseHeaders']);
  assert.deepEqual(browser.webRequest.onCompleted.listeners[0].extra, [{ urls: ['<all_urls>'], types: ['main_frame'] }]);
  assert.equal(browser.webRequest.onErrorOccurred.listeners.length, 1);
  assert.equal(browser.runtime.onMessage.listeners.length, 1);
  assert.equal(browser.tabs.onRemoved.listeners.length, 1);
});

test('start survives a browser without some APIs', () => {
  const { bg } = setup();
  const minimal = createBackground({ runtime: {}, tabs: {} });
  assert.doesNotThrow(() => minimal.start());
  assert.ok(bg);
});

test('on start the background asks the top frame of every open tab to say hello again', async () => {
  const { bg, browser, timers } = setup();
  browser.tabs.query = (info) => {
    browser.queried = info;
    return Promise.resolve([{ id: 5 }, { id: -1 }, null, { id: TAB }]);
  };
  bg.start();
  await settle();
  assert.deepEqual(browser.queried, {});
  assert.deepEqual(browser.sent, [
    { tabId: 5, message: { kind: 'snapcast-hello-request' }, options: { frameId: 0 } },
    { tabId: TAB, message: { kind: 'snapcast-hello-request' }, options: { frameId: 0 } },
  ]);

  // The hello that answers it makes the tab ready: buffered and later reports reach the app.
  request(bg, { url: 'https://cdn.example/a.m3u8', responseHeaders: { 'Content-Type': 'application/vnd.apple.mpegurl' } });
  timers.run();
  const reply = await hello(bg);
  assert.equal(reply.messages[0].items[0].url, 'https://cdn.example/a.m3u8');
  request(bg, { url: 'https://cdn.example/b.m3u8', responseHeaders: { 'Content-Type': 'application/vnd.apple.mpegurl' } });
  timers.run();
  assert.deepEqual(deliveredItems(browser).map((i) => i.url), ['https://cdn.example/b.m3u8']);
});

test('hello requests survive tabs without a content script and a failing tabs.query', async () => {
  const { bg, browser } = setup();
  browser.tabs.query = () => Promise.resolve([{ id: 1 }, { id: 2 }]);
  browser.respond = (tabId) => (tabId === 1 ? Promise.reject(new Error('Receiving end does not exist')) : Promise.resolve());
  bg.start();
  await settle();
  assert.deepEqual(browser.sent.map((s) => s.tabId), [1, 2]);

  const throwing = setup();
  throwing.browser.tabs.query = () => {
    throw new Error('not implemented');
  };
  assert.doesNotThrow(() => throwing.bg.start());
  const rejecting = setup();
  rejecting.browser.tabs.query = () => Promise.reject(new Error('no tabs'));
  rejecting.bg.start();
  await settle();
  assert.deepEqual(rejecting.browser.sent, []);
});

test('network reports are buffered until the top frame says hello, then handed over in the reply', async () => {
  const { bg, timers, browser } = setup();
  request(bg, {
    url: 'https://cdn.example/v/movie.mp4',
    requestHeaders: { Referer: PAGE, 'User-Agent': 'UA', Cookie: 'sid=1', Accept: '*/*', Authorization: 'Bearer x' },
    responseHeaders: { 'Content-Type': 'video/mp4; codecs="avc1"', 'Content-Length': '734003200' },
  });
  timers.run();
  assert.equal(browser.sent.length, 0);

  const reply = await hello(bg, PAGE, 'Movie page');
  assert.deepEqual(reply.messages, [{
    type: 'media',
    items: [{
      url: 'https://cdn.example/v/movie.mp4',
      source: 'network',
      mime: 'video/mp4',
      size: 734003200,
      status: 200,
      resourceType: 'media',
      frameUrl: PAGE,
      pageUrl: PAGE,
      requestHeaders: { Referer: PAGE, 'User-Agent': 'UA', Cookie: 'sid=1', Authorization: 'Bearer x', Accept: '*/*' },
      cookiesByHost: { 'cdn.example': 'sid=1' },
      timestamp: 1000,
    }],
  }]);
  assert.deepEqual((await hello(bg)).messages, []);
});

test('after hello reports go to frame 0 of the tab in batches', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg, PAGE, 'Title');
  request(bg, { url: 'https://cdn.example/a.m3u8', responseHeaders: { 'Content-Type': 'application/vnd.apple.mpegurl' }, type: 'xmlhttprequest' });
  request(bg, { url: 'https://cdn.example/subs/en.vtt', type: 'xmlhttprequest' });
  assert.equal(browser.sent.length, 0);
  timers.run();
  assert.equal(browser.sent.length, 1);
  const { tabId, message, options } = browser.sent[0];
  assert.equal(tabId, TAB);
  assert.deepEqual(options, { frameId: 0 });
  assert.equal(message.kind, 'snapcast-deliver');
  assert.equal(message.message.type, 'media');
  assert.deepEqual(message.message.items.map((i) => i.url), ['https://cdn.example/a.m3u8', 'https://cdn.example/subs/en.vtt']);
  assert.equal(message.message.items[0].pageTitle, 'Title');
});

test('segments, non-media, redirects, failures and requests without a tab are not reported', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg);
  request(bg, { url: 'https://cdn.example/seg-1.ts', type: 'xmlhttprequest' });
  request(bg, { url: 'https://cdn.example/chunk.m4s', type: 'xmlhttprequest' });
  request(bg, { url: 'https://cdn.example/mse.mp4', type: 'xmlhttprequest', responseHeaders: { 'Content-Type': 'video/mp4', 'Content-Length': '100000' } });
  request(bg, { url: 'https://cdn.example/app.js', type: 'other', responseHeaders: { 'Content-Type': 'application/javascript' } });
  request(bg, { url: 'https://cdn.example/moved.mp4', statusCode: 302 });
  request(bg, { url: 'https://cdn.example/missing.mp4', statusCode: 404 });
  request(bg, { url: 'https://cdn.example/sw.mp4', tabId: -1 });
  request(bg, { url: 'data:video/mp4;base64,AAAA' });
  timers.run();
  assert.deepEqual(deliveredItems(browser), []);
});

test('redirected requests keep their headers until the final response', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg);
  const details = { requestId: 'r1', tabId: TAB, frameId: 0, type: 'media', documentUrl: PAGE, frameAncestors: [] };
  bg.onSendHeaders(Object.assign({}, details, { url: 'https://a.example/v.mp4', requestHeaders: headerList({ Referer: PAGE }) }));
  bg.onHeadersReceived(Object.assign({}, details, { url: 'https://a.example/v.mp4', statusCode: 302, responseHeaders: [] }));
  assert.equal(bg.requests.has('r1'), true);
  bg.onHeadersReceived(Object.assign({}, details, {
    url: 'https://b.example/v.mp4',
    statusCode: 200,
    responseHeaders: headerList({ 'Content-Type': 'video/mp4' }),
  }));
  assert.equal(bg.requests.has('r1'), false);
  timers.run();
  const [item] = deliveredItems(browser);
  assert.equal(item.url, 'https://b.example/v.mp4');
  assert.deepEqual(item.requestHeaders, { Referer: PAGE });
});

test('a direct media navigation is reported with the media as page', async () => {
  const { bg, timers } = setup();
  request(bg, {
    url: 'https://cdn.example/clip.webm',
    type: 'main_frame',
    documentUrl: undefined,
    responseHeaders: { 'Content-Type': 'video/webm' },
  });
  timers.run();
  const reply = await hello(bg, 'https://cdn.example/clip.webm');
  const [item] = reply.messages[0].items;
  assert.equal(item.url, 'https://cdn.example/clip.webm');
  assert.equal(item.resourceType, 'main_frame');
  assert.equal(item.pageUrl, 'https://cdn.example/clip.webm');
  assert.equal(item.frameUrl, 'https://cdn.example/clip.webm');
  assert.equal(item.pageTitle, undefined);
});

test('a main_frame request resets the tab buffer, cookies and throttling', async () => {
  const { bg, timers } = setup();
  request(bg, { url: 'https://cdn.example/old.mp4', requestHeaders: { Cookie: 'old=1' }, responseHeaders: { 'Content-Type': 'video/mp4' } });
  timers.run();
  assert.equal(bg.tabs.get(TAB).buffer.length, 1);

  request(bg, { url: 'https://site.example/next', type: 'main_frame', requestHeaders: { Cookie: 'n=1' }, responseHeaders: { 'Content-Type': 'text/html' } });
  const tab = bg.tabs.get(TAB);
  assert.equal(tab.buffer.length, 0);
  assert.deepEqual([...tab.cookies.keys()], ['site.example']);

  request(bg, { url: 'https://cdn.example/old.mp4', documentUrl: 'https://site.example/next', responseHeaders: { 'Content-Type': 'video/mp4' } });
  timers.run();
  const reply = await hello(bg, 'https://site.example/next');
  assert.deepEqual(reply.messages[0].items.map((i) => i.url), ['https://cdn.example/old.mp4']);
  assert.deepEqual(reply.messages[0].items[0].cookiesByHost, { 'site.example': 'n=1' });
});

test('redirect hops of one navigation do not reset the tab twice', () => {
  const { bg } = setup();
  const nav = { requestId: 'nav', tabId: TAB, frameId: 0, type: 'main_frame', requestHeaders: [] };
  bg.onSendHeaders(Object.assign({}, nav, { url: 'http://site.example/' }));
  bg.onSendHeaders(Object.assign({}, nav, { url: 'https://site.example/', requestHeaders: headerList({ Cookie: 'a=1' }) }));
  const epoch = bg.tabs.get(TAB).epoch;
  bg.onSendHeaders(Object.assign({}, nav, { url: 'https://www.site.example/' }));
  const tab = bg.tabs.get(TAB);
  assert.equal(tab.epoch, epoch);
  assert.deepEqual([...tab.cookies.keys()], ['site.example']);
  assert.deepEqual([...tab.navUrls], ['http://site.example/', 'https://site.example/', 'https://www.site.example/']);
});

test('requests still made by the previous document during a navigation are dropped', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg, 'https://site.example/a');
  request(bg, { url: 'https://site.example/b', type: 'main_frame', responseHeaders: { 'Content-Type': 'text/html' } });
  // The old page's player keeps fetching its file.
  request(bg, { url: 'https://cdn.example/a.mp4', documentUrl: 'https://site.example/a', responseHeaders: { 'Content-Type': 'video/mp4' } });
  // An iframe of the old page.
  request(bg, {
    url: 'https://cdn.example/ad.mp4',
    frameId: 5,
    documentUrl: 'https://ads.example/frame',
    frameAncestors: [{ url: 'https://site.example/a', frameId: 0 }],
    responseHeaders: { 'Content-Type': 'video/mp4' },
  });
  // A request of the new page.
  request(bg, { url: 'https://cdn.example/b.mp4', documentUrl: 'https://site.example/b', responseHeaders: { 'Content-Type': 'video/mp4' } });
  timers.run(1000);
  // The old document is still ready, so it relays what belongs to the new page.
  assert.deepEqual(deliveredItems(browser).map((i) => i.url), ['https://cdn.example/b.mp4']);
  await hello(bg, 'https://site.example/b');
  // After hello the navigation is over: the same URL pattern is reported again.
  request(bg, { url: 'https://cdn.example/a2.mp4', documentUrl: 'https://site.example/a', responseHeaders: { 'Content-Type': 'video/mp4' } });
  assert.equal(bg.tabs.get(TAB).pending.length, 1);
});

test('a navigation ends when its main_frame request completes or fails', () => {
  const { bg } = setup();
  request(bg, { requestId: 'nav1', url: 'https://site.example/file.zip', type: 'main_frame', responseHeaders: { 'Content-Type': 'application/zip' } });
  assert.equal(bg.tabs.get(TAB).navigating, true);
  bg.onMainFrameDone({ requestId: 'other', tabId: TAB });
  assert.equal(bg.tabs.get(TAB).navigating, true);
  bg.onMainFrameDone({ requestId: 'nav1', tabId: TAB });
  assert.equal(bg.tabs.get(TAB).navigating, false);
});

test('a navigation also ends after a timeout', () => {
  const { bg, timers } = setup({ navigationTimeoutMs: 5000 });
  request(bg, { url: 'https://site.example/slow', type: 'main_frame', responseHeaders: { 'Content-Type': 'text/html' } });
  assert.equal(bg.tabs.get(TAB).navigating, true);
  timers.run();
  assert.equal(bg.tabs.get(TAB).navigating, false);
});

test('repeated responses for the same URL are reported once unless they add the size', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg);
  request(bg, { url: 'https://cdn.example/v.mp4?range=x#frag', type: 'object', responseHeaders: { 'Content-Type': 'video/x-flv' } });
  request(bg, { url: 'https://cdn.example/v.mp4', responseHeaders: { 'Content-Type': 'video/mp4' } });
  request(bg, { url: 'https://cdn.example/v.mp4#t=5', responseHeaders: { 'Content-Type': 'video/mp4' } });
  request(bg, { url: 'https://cdn.example/v.mp4', statusCode: 206, responseHeaders: { 'Content-Type': 'video/mp4', 'Content-Range': 'bytes 0-1/5000000' } });
  request(bg, { url: 'https://cdn.example/v.mp4', statusCode: 206, responseHeaders: { 'Content-Type': 'video/mp4', 'Content-Range': 'bytes 2-3/5000000' } });
  timers.run();
  const items = deliveredItems(browser);
  assert.deepEqual(items.map((i) => [i.url, i.size]), [
    ['https://cdn.example/v.mp4', undefined],
    ['https://cdn.example/v.mp4', 5000000],
  ]);
});

test('a failed delivery buffers the message until the next hello', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg);
  browser.respond = () => Promise.reject(new Error('Receiving end does not exist'));
  request(bg, { url: 'https://cdn.example/a.mp4', responseHeaders: { 'Content-Type': 'video/mp4' } });
  timers.run();
  await settle();
  const tab = bg.tabs.get(TAB);
  assert.equal(tab.ready, false);
  assert.equal(tab.buffer.length, 1);

  request(bg, { url: 'https://cdn.example/b.mp4', responseHeaders: { 'Content-Type': 'video/mp4' } });
  timers.run();
  assert.equal(browser.sent.length, 1);
  const reply = await hello(bg);
  assert.deepEqual(reply.messages[0].items.map((i) => i.url), ['https://cdn.example/a.mp4', 'https://cdn.example/b.mp4']);
});

test('a delivery that fails after a newer hello is sent again', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg);
  let rejectFirst;
  browser.respond = () => new Promise((resolve, reject) => {
    rejectFirst = reject;
  });
  request(bg, { url: 'https://cdn.example/a.mp4', responseHeaders: { 'Content-Type': 'video/mp4' } });
  timers.run();
  await hello(bg);
  browser.respond = () => Promise.resolve(true);
  rejectFirst(new Error('gone'));
  await settle();
  assert.equal(browser.sent.length, 2);
  assert.equal(bg.tabs.get(TAB).ready, true);
  assert.equal(bg.tabs.get(TAB).buffer.length, 0);
});

test('child frame findings are forwarded to the top frame with their origin filled in', async () => {
  const { bg, browser } = setup();
  await hello(bg, PAGE, 'Top title');
  const sender = { tab: { id: TAB }, frameId: 3, url: 'https://player.example/embed/1' };
  bg.onRuntimeMessage({
    kind: 'snapcast-report',
    message: { type: 'media', items: [{ url: 'https://cdn.example/e.mp4', source: 'dom' }, 'junk', { url: 'https://cdn.example/f.mp4', source: 'dom', pageUrl: 'https://x/' }] },
  }, sender);
  bg.onRuntimeMessage({ kind: 'snapcast-report', message: { type: 'drm' } }, sender);
  bg.onRuntimeMessage({ kind: 'snapcast-report', message: { type: 'hello', url: 'https://evil/' } }, sender);
  bg.onRuntimeMessage({ kind: 'snapcast-report', message: null }, sender);

  assert.equal(browser.sent.length, 2);
  assert.deepEqual(browser.sent[0].message.message.items, [
    { url: 'https://cdn.example/e.mp4', source: 'dom', frameUrl: 'https://player.example/embed/1', pageUrl: PAGE, pageTitle: 'Top title' },
    { url: 'https://cdn.example/f.mp4', source: 'dom', frameUrl: 'https://player.example/embed/1', pageUrl: 'https://x/', pageTitle: 'Top title' },
  ]);
  assert.deepEqual(browser.sent[1].message.message, { type: 'drm', frameUrl: 'https://player.example/embed/1' });
  assert.deepEqual(browser.sent[1].options, { frameId: 0 });
});

test('only the top frame may say hello or update the page', () => {
  const { bg } = setup();
  const child = { tab: { id: TAB }, frameId: 2, url: 'https://f.example/' };
  assert.equal(bg.onRuntimeMessage({ kind: 'snapcast-hello', url: 'https://f.example/' }, child), undefined);
  bg.onRuntimeMessage({ kind: 'snapcast-top', url: 'https://f.example/', title: 'Frame' }, child);
  assert.equal(bg.tabs.get(TAB), undefined);
  bg.onRuntimeMessage({ kind: 'snapcast-top', url: PAGE, title: 'Page' }, { tab: { id: TAB }, frameId: 0 });
  assert.equal(bg.tabs.get(TAB).topTitle, 'Page');
  assert.equal(bg.onRuntimeMessage({ kind: 'snapcast-hello' }, { frameId: 0 }), undefined);
  assert.equal(bg.onRuntimeMessage('text', { tab: { id: TAB }, frameId: 0 }), undefined);
});

test('a rescan request is broadcast to every frame of the tab', () => {
  const { bg, browser } = setup();
  bg.onRuntimeMessage({ kind: 'snapcast-rescan-all' }, { tab: { id: TAB }, frameId: 0 });
  assert.deepEqual(browser.sent, [{ tabId: TAB, message: { kind: 'snapcast-rescan' }, options: undefined }]);
});

test('a thumbnails request is broadcast to every frame of the tab', () => {
  const { bg, browser } = setup();
  bg.onRuntimeMessage({ kind: 'snapcast-thumbnails-all', generation: 3 }, { tab: { id: TAB }, frameId: 0 });
  assert.deepEqual(browser.sent, [{ tabId: TAB, message: { kind: 'snapcast-thumbnails', generation: 3 }, options: undefined }]);
});

test('a frame learns the Referrer-Policy of its document from the background page', async () => {
  const { bg } = setup();
  bg.onSendHeaders({ requestId: 'm', tabId: TAB, frameId: 0, type: 'main_frame', url: PAGE, requestHeaders: [] });
  bg.onHeadersReceived({
    requestId: 'm', tabId: TAB, frameId: 0, type: 'main_frame', url: PAGE, statusCode: 200,
    responseHeaders: [{ name: 'Content-Type', value: 'text/html' }, { name: 'Referrer-Policy', value: 'no-referrer, bogus, Same-Origin' }],
  });
  const reply = await bg.onRuntimeMessage({ kind: 'snapcast-policy' }, { tab: { id: TAB }, frameId: 0 });
  assert.deepEqual(reply, { referrerPolicy: 'same-origin' });
  const child = await bg.onRuntimeMessage({ kind: 'snapcast-policy' }, { tab: { id: TAB }, frameId: 7 });
  assert.deepEqual(child, { referrerPolicy: null });
});

test('the frame policy map keeps the top frame and the newest frames', () => {
  const { bg } = setup({ maxFramePolicies: 3 });
  const frame = (frameId, policy) => {
    const type = frameId === 0 ? 'main_frame' : 'sub_frame';
    bg.onSendHeaders({ requestId: `f${frameId}`, tabId: TAB, frameId, type, url: PAGE, requestHeaders: [] });
    bg.onHeadersReceived({
      requestId: `f${frameId}`, tabId: TAB, frameId, type, url: PAGE, statusCode: 200,
      responseHeaders: [{ name: 'Content-Type', value: 'text/html' }, { name: 'Referrer-Policy', value: policy }],
    });
  };
  frame(0, 'origin');
  for (let id = 1; id <= 5; id++) frame(id, 'same-origin');
  assert.deepEqual([...bg.tabs.get(TAB).policies.keys()], [0, 4, 5]);
  assert.equal(bg.tabs.get(TAB).policies.get(0), 'origin');
});

test('an over-long cookie header is kept up to its last whole cookie', () => {
  const { bg } = setup({ maxCookieLength: 20 });
  bg.onSendHeaders({
    requestId: 'c', tabId: TAB, type: 'xmlhttprequest', url: 'https://cdn.example/x',
    requestHeaders: [{ name: 'Cookie', value: 'a=1; b=2; c=333333333333' }],
  });
  assert.equal(bg.tabs.get(TAB).cookies.get('cdn.example'), 'a=1; b=2');
});

test('the cookie map keeps the most recently used hosts', () => {
  const { bg } = setup();
  const send = (host, cookie) => bg.onSendHeaders({
    requestId: host, tabId: TAB, type: 'xmlhttprequest', url: `https://${host}/x`, requestHeaders: [{ name: 'Cookie', value: cookie }],
  });
  for (let i = 0; i < 48; i++) send(`ad${i}.example`, `id=${i}`);
  send('ad0.example', 'id=0');
  send('media.example', 'sess=1');
  const hosts = [...bg.tabs.get(TAB).cookies.keys()];
  assert.equal(hosts.length, 48);
  assert.ok(hosts.includes('media.example'));
  assert.ok(hosts.includes('ad0.example'));
  assert.ok(!hosts.includes('ad1.example'));
});

test('cookies seen later on segment hosts refresh the last playlist report', async () => {
  const { bg, timers, browser } = setup();
  await hello(bg);
  request(bg, { url: 'https://cdn.example/master.m3u8', type: 'xmlhttprequest', requestHeaders: { Cookie: 'a=1' } });
  request(bg, { url: 'https://seg.example/v/seg-1.ts', type: 'xmlhttprequest', requestHeaders: { Cookie: 'edge=2' } });
  request(bg, { url: 'https://seg.example/v/seg-2.ts', type: 'xmlhttprequest', requestHeaders: { Cookie: 'edge=2' } });
  timers.run();
  const items = deliveredItems(browser);
  assert.equal(items.length, 2);
  assert.deepEqual(items[0].cookiesByHost, { 'cdn.example': 'a=1' });
  assert.equal(items[1].url, 'https://cdn.example/master.m3u8');
  assert.deepEqual(items[1].cookiesByHost, { 'cdn.example': 'a=1', 'seg.example': 'edge=2' });
});

test('closing a tab forgets its state and pending requests', () => {
  const { bg } = setup();
  bg.onSendHeaders({ requestId: 'x', tabId: TAB, type: 'media', url: 'https://cdn.example/a.mp4', requestHeaders: [] });
  bg.onSendHeaders({ requestId: 'y', tabId: 9, type: 'media', url: 'https://cdn.example/b.mp4', requestHeaders: [] });
  bg.onTabRemoved(TAB);
  assert.equal(bg.tabs.has(TAB), false);
  assert.deepEqual([...bg.requests.keys()], ['y']);
});

test('stored request headers are capped by count and age', () => {
  const { bg, advance } = setup({ maxRequests: 3, requestTtlMs: 1000 });
  for (const id of ['1', '2', '3', '4']) {
    bg.onSendHeaders({ requestId: id, tabId: TAB, type: 'media', url: 'https://cdn.example/a.mp4', requestHeaders: [] });
  }
  assert.deepEqual([...bg.requests.keys()], ['2', '3', '4']);
  advance(2000);
  bg.onSendHeaders({ requestId: '5', tabId: TAB, type: 'media', url: 'https://cdn.example/a.mp4', requestHeaders: [] });
  assert.deepEqual([...bg.requests.keys()], ['5']);
});

test('the buffer of a tab is capped', () => {
  const { bg, timers } = setup({ maxBufferedMessages: 2, maxBatchItems: 1 });
  for (const n of [1, 2, 3]) request(bg, { url: `https://cdn.example/${n}.mp4`, responseHeaders: { 'Content-Type': 'video/mp4' } });
  timers.run();
  assert.deepEqual(bg.tabs.get(TAB).buffer.map((m) => m.items[0].url), ['https://cdn.example/2.mp4', 'https://cdn.example/3.mp4']);
});

test('handlers never throw on malformed input', () => {
  const { bg } = setup();
  assert.doesNotThrow(() => {
    bg.onSendHeaders(null);
    bg.onSendHeaders({ tabId: 'x' });
    bg.onHeadersReceived(undefined);
    bg.onHeadersReceived({ tabId: TAB, url: 42, responseHeaders: 'nope' });
    bg.onMainFrameDone(null);
    bg.onRuntimeMessage(null, null);
    bg.onTabRemoved(undefined);
  });
});

test('topUrlOf finds the top document of a request', () => {
  assert.equal(topUrlOf({ type: 'main_frame', url: 'https://a/' }), 'https://a/');
  assert.equal(topUrlOf({ type: 'media', frameId: 0, documentUrl: 'https://a/page', frameAncestors: [] }), 'https://a/page');
  assert.equal(topUrlOf({
    type: 'xmlhttprequest',
    frameId: 4,
    documentUrl: 'https://frame/',
    frameAncestors: [{ url: 'https://mid/', frameId: 2 }, { url: 'https://top/', frameId: 0 }],
  }), 'https://top/');
  assert.equal(topUrlOf({ type: 'media', frameId: 4, documentUrl: 'https://frame/' }), null);
  assert.equal(topUrlOf(null), null);
});
