'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const content = require('../../main/assets/extensions/snapcast_detector/content.js');

const PAGE = 'https://site.example/watch/1';
const UA = 'Mozilla/5.0 (Android 14; Mobile) Gecko Firefox';

function event() {
  const listeners = [];
  return {
    listeners,
    addListener(listener) {
      listeners.push(listener);
    },
    fire(...args) {
      return listeners.map((listener) => listener(...args));
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
    /** Runs the timers queued so far, not the ones they schedule. */
    runOnce() {
      const due = queue;
      queue = [];
      for (const t of due) t.fn();
    },
    run() {
      while (queue.length) {
        const due = queue;
        queue = [];
        for (const t of due) t.fn();
      }
    },
    delays() {
      return queue.map((t) => t.ms);
    },
  };
}

function fakePort() {
  const port = {
    posted: [],
    onMessage: event(),
    onDisconnect: event(),
    postMessage(message) {
      port.posted.push(JSON.parse(JSON.stringify(message)));
    },
  };
  return port;
}

function fakeRuntime() {
  const runtime = {
    ports: [],
    sent: [],
    failConnect: false,
    replies: {},
    onMessage: event(),
    connectNative(name) {
      if (runtime.failConnect) throw new Error('no native app');
      const port = fakePort();
      port.appName = name;
      runtime.ports.push(port);
      return port;
    },
    sendMessage(message) {
      runtime.sent.push(JSON.parse(JSON.stringify(message)));
      return Promise.resolve(runtime.replies[message.kind]);
    },
  };
  return runtime;
}

/** A minimal element: properties, attributes and children (looked up by tag name). */
function element(tag, props, attrs, children) {
  const kids = children || [];
  const attributes = attrs || {};
  return Object.assign({
    nodeName: tag.toUpperCase(),
    nodeType: 1,
    getAttribute: (name) => (Object.prototype.hasOwnProperty.call(attributes, name) ? attributes[name] : null),
    querySelectorAll: (selector) => kids.filter((k) => k.nodeName === selector.toUpperCase()),
    getElementsByTagName: (name) => kids.filter((k) => k.nodeName === name.toUpperCase()),
  }, props || {});
}

function video(props, attrs, children) {
  return element('video', Object.assign({ currentSrc: '', src: '', duration: NaN, videoWidth: 0, videoHeight: 0, poster: '' }, props), attrs, children);
}

function fakeDocument(videos, init) {
  const options = init || {};
  const listeners = {};
  const metas = options.metas || {};
  return {
    title: options.title || '',
    visibilityState: 'visible',
    videos,
    getElementsByTagName: (name) => (name === 'video' ? videos : []),
    querySelector(selector) {
      const match = /property="([^"]+)"/.exec(selector);
      const key = match && match[1];
      return key && metas[key] ? element('meta', {}, { content: metas[key] }) : null;
    },
    addEventListener(type, listener) {
      (listeners[type] = listeners[type] || []).push(listener);
    },
    dispatch(type, eventObject) {
      for (const listener of listeners[type] || []) listener(eventObject);
    },
  };
}

function fakeWindow(url, isTop) {
  const listeners = {};
  const win = {
    location: { href: url },
    navigator: { userAgent: UA },
    addEventListener(type, listener) {
      (listeners[type] = listeners[type] || []).push(listener);
    },
    dispatch(type, eventObject) {
      for (const listener of listeners[type] || []) listener(eventObject);
    },
  };
  win.top = isTop ? win : {};
  return win;
}

function fakeObserverClass() {
  const instances = [];
  class Observer {
    constructor(callback) {
      this.callback = callback;
      instances.push(this);
    }

    observe(target, options) {
      this.target = target;
      this.options = options;
    }
  }
  return { Observer, instances };
}

function startFrame({ url = PAGE, isTop = true, videos = [], title = 'Watch page', metas } = {}) {
  const timers = fakeTimers();
  const runtime = fakeRuntime();
  runtime.replies['snapcast-hello'] = { messages: [] };
  const doc = fakeDocument(videos, { title, metas });
  const win = fakeWindow(url, isTop);
  const observers = fakeObserverClass();
  let clock = 10000;
  const detector = content.startContent({
    window: win,
    document: doc,
    browser: { runtime },
    MutationObserver: observers.Observer,
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
    now: () => clock,
  });
  return {
    timers,
    runtime,
    doc,
    win,
    observers,
    detector,
    advance(ms) {
      clock += ms;
    },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));
const context = (overrides) => Object.assign({
  isTop: true,
  frameUrl: PAGE,
  pageUrl: PAGE,
  pageTitle: 'Watch page',
  frameTitle: 'Watch page',
  requestHeaders: { Referer: PAGE, 'User-Agent': UA },
  now: () => 42,
}, overrides);

test('elementReports describes the selected http(s) source with element metadata', () => {
  const el = video({
    currentSrc: 'https://cdn.example/movie.mp4',
    src: 'https://cdn.example/movie.mp4',
    duration: 5400.5,
    videoWidth: 1920,
    videoHeight: 1080,
    poster: 'https://cdn.example/poster.jpg',
  }, { 'aria-label': ' Big movie ' }, [
    element('source', { src: 'https://cdn.example/movie.webm', type: 'video/webm' }),
    element('track', { kind: 'subtitles', src: 'https://cdn.example/en.vtt', srclang: 'en', label: 'English' }),
    element('track', { kind: 'chapters', src: 'https://cdn.example/ch.vtt' }),
  ]);
  assert.deepEqual(content.elementReports(el, context()), [{
    source: 'dom',
    frameUrl: PAGE,
    pageUrl: PAGE,
    pageTitle: 'Watch page',
    title: 'Big movie',
    duration: 5400.5,
    width: 1920,
    height: 1080,
    poster: 'https://cdn.example/poster.jpg',
    // Another origin: the page's default policy sends its origin only.
    requestHeaders: { Referer: 'https://site.example/', 'User-Agent': UA },
    tracks: [{ url: 'https://cdn.example/en.vtt', kind: 'subtitles', lang: 'en', label: 'English' }],
    timestamp: 42,
    url: 'https://cdn.example/movie.mp4',
    isBlob: false,
  }]);
});

test('elementReports lists every candidate before a source was selected', () => {
  const el = video({}, {}, [
    element('source', { src: 'https://cdn.example/a.webm', type: 'video/webm' }),
    element('source', { src: 'https://cdn.example/a.mp4', type: 'video/mp4' }),
    element('source', { src: 'data:video/mp4;base64,AAAA' }),
    element('source', { src: '' }),
  ]);
  const reports = content.elementReports(el, context());
  assert.deepEqual(reports.map((r) => [r.url, r.mime]), [
    ['https://cdn.example/a.webm', 'video/webm'],
    ['https://cdn.example/a.mp4', 'video/mp4'],
  ]);
  assert.equal(reports[0].duration, undefined);
  assert.equal(reports[0].width, undefined);
});

test('elementReports flags MediaSource sources and drops non-finite durations', () => {
  const el = video({ currentSrc: 'blob:https://site.example/1234', src: 'blob:https://site.example/1234', duration: Infinity, poster: 'data:image/png;base64,x' }, { title: 'Live show' });
  assert.deepEqual(content.elementReports(el, context()).map((r) => [r.url, r.isBlob, r.duration, r.poster, r.title]), [
    ['blob:https://site.example/1234', true, undefined, undefined, 'Live show'],
  ]);
  assert.equal(content.isBlobUrl('mediasource:https://x/1'), true);
  assert.equal(content.isBlobUrl('https://x/1'), false);
});

test('child frames use their document title as the video title fallback', () => {
  const el = video({ currentSrc: 'https://cdn.example/e.mp4' });
  const [report] = content.elementReports(el, context({ isTop: false, pageUrl: undefined, pageTitle: undefined, frameTitle: 'Embedded film' }));
  assert.equal(report.title, 'Embedded film');
  assert.equal(report.pageUrl, undefined);
  const [top] = content.elementReports(el, context({ frameTitle: 'Watch page' }));
  assert.equal(top.title, undefined);
});

test('trackReports keeps distinct http(s) subtitle and caption tracks', () => {
  const el = video({}, {}, [
    element('track', { kind: 'captions', src: 'https://cdn.example/fr.vtt', srclang: 'fr' }),
    element('track', { kind: '', src: 'https://cdn.example/de.vtt' }, { kind: 'SUBTITLES' }),
    element('track', { kind: 'subtitles', src: 'https://cdn.example/fr.vtt' }),
    element('track', { kind: 'metadata', src: 'https://cdn.example/meta.vtt' }),
    element('track', { kind: 'subtitles', src: 'blob:https://cdn.example/1' }),
  ]);
  assert.deepEqual(content.trackReports(el), [
    { url: 'https://cdn.example/fr.vtt', kind: 'captions', lang: 'fr' },
    { url: 'https://cdn.example/de.vtt', kind: 'subtitles' },
  ]);
});

test('reportSignature ignores timestamps and headers', () => {
  const a = { url: 'https://x/a.mp4', duration: 10.2, timestamp: 1, requestHeaders: { Referer: 'a' } };
  const b = { url: 'https://x/a.mp4', duration: 10.4, timestamp: 2, requestHeaders: { Referer: 'b' } };
  assert.equal(content.reportSignature(a), content.reportSignature(b));
  assert.notEqual(content.reportSignature(a), content.reportSignature(Object.assign({}, b, { width: 640 })));
});

test('pageInfo reads the title and OpenGraph tags', () => {
  const doc = fakeDocument([], { title: ' Page ', metas: { 'og:title': 'OG title', 'og:image': '/img/cover.jpg' } });
  assert.deepEqual(content.pageInfo(doc, PAGE), {
    type: 'page',
    url: PAGE,
    title: 'Page',
    ogTitle: 'OG title',
    ogImage: 'https://site.example/img/cover.jpg',
  });
  const twitter = fakeDocument([], { metas: { 'twitter:image': 'javascript:alert(1)' } });
  assert.deepEqual(content.pageInfo(twitter, PAGE), { type: 'page', url: PAGE });
});

test('native link says hello, then replays everything once the app answers', () => {
  const runtime = fakeRuntime();
  const commands = [];
  const link = content.createNativeLink(runtime, {
    hello: () => ({ type: 'hello', url: PAGE }),
    onCommand: (command, confirming) => commands.push([command, confirming]),
  });
  link.start();
  const port = runtime.ports[0];
  assert.equal(port.appName, 'snapcast');
  assert.deepEqual(port.posted, [{ type: 'hello', url: PAGE }]);

  link.send({ type: 'page', url: PAGE, title: 'A' });
  link.send({ type: 'media', items: [{ url: 'https://x/1.mp4' }] });
  link.send({ type: 'media', items: [{ url: 'https://x/2.mp4' }] });
  link.send({ type: 'drm', frameUrl: PAGE });
  link.send(null);
  assert.equal(port.posted.length, 5);
  assert.equal(link.confirmed, false);

  port.posted.length = 0;
  port.onMessage.fire({ type: 'rescan' });
  assert.equal(link.confirmed, true);
  assert.deepEqual(port.posted, [
    { type: 'hello', url: PAGE },
    { type: 'page', url: PAGE, title: 'A' },
    { type: 'media', items: [{ url: 'https://x/1.mp4' }, { url: 'https://x/2.mp4' }] },
    { type: 'drm', frameUrl: PAGE },
  ]);
  assert.deepEqual(commands, [[{ type: 'rescan' }, true]]);

  port.posted.length = 0;
  port.onMessage.fire({ type: 'rescan' });
  port.onMessage.fire('not a command');
  assert.deepEqual(port.posted, []);
  assert.deepEqual(commands[1], [{ type: 'rescan' }, false]);

  link.replay();
  assert.equal(port.posted.length, 4);
});

test('native link reconnects with back-off after a disconnect and replays', () => {
  const runtime = fakeRuntime();
  const timers = fakeTimers();
  const link = content.createNativeLink(runtime, { hello: () => ({ type: 'hello', url: PAGE }), setTimeout: timers.setTimeout });
  link.start();
  link.send({ type: 'media', items: [{ url: 'https://x/1.mp4' }] });
  runtime.ports[0].onDisconnect.fire();
  assert.equal(link.connected, false);
  link.send({ type: 'media', items: [{ url: 'https://x/2.mp4' }] });
  assert.deepEqual(timers.delays(), [500]);
  timers.run();
  assert.equal(runtime.ports.length, 2);
  assert.equal(link.connected, true);
  assert.deepEqual(runtime.ports[1].posted, [
    { type: 'hello', url: PAGE },
    { type: 'media', items: [{ url: 'https://x/1.mp4' }, { url: 'https://x/2.mp4' }] },
  ]);
  // Events of the old port are ignored.
  runtime.ports[0].onDisconnect.fire();
  runtime.ports[0].onMessage.fire({ type: 'rescan' });
  assert.equal(link.connected, true);
  assert.equal(link.confirmed, false);
});

test('native link gives up after the maximum number of attempts until asked again', () => {
  const runtime = fakeRuntime();
  runtime.failConnect = true;
  const timers = fakeTimers();
  const link = content.createNativeLink(runtime, { setTimeout: timers.setTimeout, clearTimeout: timers.clearTimeout, maxAttempts: 3 });
  link.start();
  const delays = [];
  for (let i = 0; i < 5; i++) {
    delays.push(...timers.delays());
    timers.runOnce();
  }
  assert.deepEqual(delays, [500, 1000, 2000]);
  runtime.failConnect = false;
  link.reconnect();
  assert.equal(link.connected, true);
});

test('native link keeps a bounded log', () => {
  const runtime = fakeRuntime();
  const link = content.createNativeLink(runtime, { maxLogItems: 3 });
  link.start();
  for (const n of [1, 2, 3, 4, 5]) link.send({ type: 'media', items: [{ url: `https://x/${n}.mp4` }] });
  const port = runtime.ports[0];
  port.posted.length = 0;
  port.onMessage.fire({ type: 'rescan' });
  assert.deepEqual(port.posted, [{ type: 'media', items: [{ url: 'https://x/3.mp4' }, { url: 'https://x/4.mp4' }, { url: 'https://x/5.mp4' }] }]);
});

test('the top frame connects, greets the background, scans and reports page metadata', async () => {
  const el = video({ currentSrc: 'https://cdn.example/a.mp4', duration: 60 });
  const frame = startFrame({ videos: [el], metas: { 'og:image': 'https://site.example/og.jpg' } });
  const port = frame.runtime.ports[0];
  assert.deepEqual(port.posted, [{ type: 'hello', url: PAGE, title: 'Watch page' }]);
  assert.deepEqual(frame.runtime.sent.find((m) => m.kind === 'snapcast-hello'), { kind: 'snapcast-hello', url: PAGE, title: 'Watch page' });
  assert.ok(frame.runtime.sent.some((m) => m.kind === 'snapcast-policy'));
  assert.deepEqual(frame.observers.instances[0].options, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ['src', 'poster', 'data-poster'],
  });

  frame.timers.run();
  const media = port.posted.filter((m) => m.type === 'media');
  assert.equal(media.length, 1);
  assert.deepEqual(media[0].items.map((i) => [i.url, i.duration, i.pageUrl, i.frameUrl]), [['https://cdn.example/a.mp4', 60, PAGE, PAGE]]);
  assert.deepEqual(media[0].items[0].requestHeaders, { Referer: 'https://site.example/', 'User-Agent': UA });
  assert.deepEqual(port.posted.find((m) => m.type === 'page'), { type: 'page', url: PAGE, title: 'Watch page', ogImage: 'https://site.example/og.jpg' });
  assert.deepEqual(frame.runtime.sent.find((m) => m.kind === 'snapcast-top'), { kind: 'snapcast-top', url: PAGE, title: 'Watch page' });

  // Nothing changed: a second scan reports nothing.
  port.posted.length = 0;
  frame.detector.scan();
  assert.deepEqual(port.posted, []);

  // A media event with new metadata reports the element again.
  el.videoWidth = 1280;
  el.videoHeight = 720;
  frame.doc.dispatch('loadedmetadata', { target: el });
  frame.timers.run();
  assert.deepEqual(port.posted.map((m) => m.type), ['media']);
  assert.equal(port.posted[0].items[0].height, 720);
});

test('the top frame relays the buffered reports handed over in the hello reply', async () => {
  const timers = fakeTimers();
  const runtime = fakeRuntime();
  runtime.replies['snapcast-hello'] = { messages: [{ type: 'media', items: [{ url: 'https://cdn.example/n.m3u8', source: 'network' }] }] };
  content.startContent({
    window: fakeWindow(PAGE, true),
    document: fakeDocument([]),
    browser: { runtime },
    MutationObserver: fakeObserverClass().Observer,
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
  });
  await settle();
  assert.deepEqual(runtime.ports[0].posted[1], { type: 'media', items: [{ url: 'https://cdn.example/n.m3u8', source: 'network' }] });
});

test('the top frame greets the background again when it did not answer', async () => {
  const timers = fakeTimers();
  const runtime = fakeRuntime();
  content.startContent({
    window: fakeWindow(PAGE, true),
    document: fakeDocument([]),
    browser: { runtime },
    MutationObserver: fakeObserverClass().Observer,
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
  });
  await settle();
  assert.equal(runtime.sent.filter((m) => m.kind === 'snapcast-hello').length, 1);
  runtime.replies['snapcast-hello'] = { messages: [] };
  timers.runOnce();
  await settle();
  assert.equal(runtime.sent.filter((m) => m.kind === 'snapcast-hello').length, 2);
  timers.run();
  await settle();
  assert.equal(runtime.sent.filter((m) => m.kind === 'snapcast-hello').length, 2);
});

test('a hello request from a restarted background makes the top frame say hello again', async () => {
  const frame = startFrame();
  await settle();
  const hellos = () => frame.runtime.sent.filter((m) => m.kind === 'snapcast-hello');
  assert.equal(hellos().length, 1);
  frame.runtime.replies['snapcast-hello'] = { messages: [{ type: 'media', items: [{ url: 'https://cdn.example/late.m3u8', source: 'network' }] }] };
  assert.deepEqual(frame.runtime.onMessage.fire({ kind: 'snapcast-hello-request' }), [undefined]);
  await settle();
  assert.equal(hellos().length, 2);
  assert.deepEqual(hellos()[1], { kind: 'snapcast-hello', url: PAGE, title: 'Watch page' });
  const port = frame.runtime.ports[0];
  assert.deepEqual(port.posted[port.posted.length - 1], { type: 'media', items: [{ url: 'https://cdn.example/late.m3u8', source: 'network' }] });

  // Child frames never say hello.
  const child = startFrame({ url: 'https://player.example/embed', isTop: false });
  child.runtime.onMessage.fire({ kind: 'snapcast-hello-request' });
  await settle();
  assert.equal(child.runtime.sent.some((m) => m.kind === 'snapcast-hello'), false);
});

test('the top frame relays deliveries from the background to the app', () => {
  const frame = startFrame();
  const port = frame.runtime.ports[0];
  const [reply] = frame.runtime.onMessage.fire({ kind: 'snapcast-deliver', message: { type: 'drm', frameUrl: 'https://f/' } });
  assert.ok(reply instanceof Promise);
  assert.deepEqual(port.posted[port.posted.length - 1], { type: 'drm', frameUrl: 'https://f/' });
  assert.deepEqual(frame.runtime.onMessage.fire({ kind: 'other' }), [undefined]);
});

test('an app rescan command rescans this frame and asks the background to rescan the others', () => {
  const el = video({ currentSrc: 'https://cdn.example/a.mp4' });
  const frame = startFrame({ videos: [el] });
  const port = frame.runtime.ports[0];
  frame.timers.run();
  port.posted.length = 0;

  port.onMessage.fire({ type: 'rescan' });
  assert.ok(frame.runtime.sent.some((m) => m.kind === 'snapcast-rescan-all'));
  frame.timers.run();
  let media = port.posted.filter((m) => m.type === 'media');
  // The confirmation replay plus the fresh scan.
  assert.equal(media.length, 2);
  assert.equal(media[1].items[0].url, 'https://cdn.example/a.mp4');

  // Later rescans replay the log as well.
  frame.advance(5000);
  port.posted.length = 0;
  port.onMessage.fire({ type: 'rescan' });
  assert.deepEqual(port.posted.map((m) => m.type), ['hello', 'page', 'media']);
  frame.timers.run();
  media = port.posted.filter((m) => m.type === 'media');
  assert.equal(media.length, 2);

  // The broadcast echo right after is ignored by the top frame.
  port.posted.length = 0;
  frame.runtime.onMessage.fire({ kind: 'snapcast-rescan' });
  frame.timers.run();
  assert.deepEqual(port.posted, []);
  frame.advance(5000);
  frame.runtime.onMessage.fire({ kind: 'snapcast-rescan' });
  frame.timers.run();
  assert.equal(port.posted.filter((m) => m.type === 'media').length, 1);
});

test('child frames report to the background and never connect to the app', () => {
  const el = video({ currentSrc: 'https://cdn.example/embed.mp4' });
  const frame = startFrame({ url: 'https://player.example/embed', isTop: false, videos: [el], title: 'Embed title' });
  assert.equal(frame.runtime.ports.length, 0);
  frame.timers.run();
  const report = frame.runtime.sent.find((m) => m.kind === 'snapcast-report');
  assert.equal(report.message.type, 'media');
  const [item] = report.message.items;
  assert.equal(item.url, 'https://cdn.example/embed.mp4');
  assert.equal(item.frameUrl, 'https://player.example/embed');
  assert.equal(item.pageUrl, undefined);
  assert.equal(item.title, 'Embed title');
  assert.equal(frame.runtime.sent.some((m) => m.kind === 'snapcast-hello'), false);
  assert.deepEqual(frame.runtime.onMessage.fire({ kind: 'snapcast-deliver', message: { type: 'drm' } }), [undefined]);

  frame.runtime.sent.length = 0;
  frame.runtime.onMessage.fire({ kind: 'snapcast-rescan' });
  frame.timers.run();
  assert.equal(frame.runtime.sent.filter((m) => m.kind === 'snapcast-report').length, 1);
});

test('an encrypted event is reported once as DRM', () => {
  const frame = startFrame();
  const port = frame.runtime.ports[0];
  frame.doc.dispatch('encrypted', { target: video({}) });
  frame.doc.dispatch('encrypted', { target: video({}) });
  assert.deepEqual(port.posted.filter((m) => m.type === 'drm'), [{ type: 'drm', frameUrl: PAGE }]);
});

test('mutations adding videos or changing their sources trigger a scan', () => {
  const videos = [];
  const frame = startFrame({ videos });
  const port = frame.runtime.ports[0];
  frame.timers.run();
  port.posted.length = 0;
  const observer = frame.observers.instances[0];

  observer.callback([{ type: 'childList', target: {}, addedNodes: [{ nodeType: 3 }, element('div')] }]);
  frame.timers.run();
  assert.deepEqual(port.posted.filter((m) => m.type === 'media'), []);

  const el = video({ currentSrc: 'https://cdn.example/late.mp4' });
  videos.push(el);
  observer.callback([{ type: 'childList', target: {}, addedNodes: [element('div', {}, {}, [el])] }]);
  frame.timers.run();
  assert.deepEqual(port.posted.filter((m) => m.type === 'media').map((m) => m.items[0].url), ['https://cdn.example/late.mp4']);

  el.currentSrc = 'https://cdn.example/other.mp4';
  observer.callback([{ type: 'attributes', target: el, attributeName: 'src' }]);
  frame.timers.run();
  assert.equal(port.posted.filter((m) => m.type === 'media').length, 2);
});

test('a page restored from the back/forward cache reconnects and greets again', () => {
  const frame = startFrame();
  frame.runtime.ports[0].onDisconnect.fire();
  frame.win.dispatch('pageshow', { persisted: false });
  assert.equal(frame.runtime.ports.length, 1);
  frame.win.dispatch('pageshow', { persisted: true });
  assert.equal(frame.runtime.ports.length, 2);
  assert.deepEqual(frame.runtime.ports[1].posted[0], { type: 'hello', url: PAGE, title: 'Watch page' });
  // The pending retry was cancelled.
  frame.timers.run();
  assert.equal(frame.runtime.ports.length, 2);
  assert.equal(frame.runtime.sent.filter((m) => m.kind === 'snapcast-hello').length, 2);
});

test('referrerFor applies the page referrer policy like the browser', () => {
  const page = 'https://user:pw@site.example/watch/1?a=1#t=5';
  const same = 'https://site.example/v.mp4';
  const other = 'https://cdn.example/v.mp4';
  const insecure = 'http://cdn.example/v.mp4';
  const r = (target, policy) => content.referrerFor(page, target, policy);
  assert.equal(r(same), 'https://site.example/watch/1?a=1');
  assert.equal(r(other), 'https://site.example/');
  assert.equal(r(insecure), undefined);
  assert.equal(r(other, 'no-referrer'), undefined);
  assert.equal(r(other, 'unsafe-url'), 'https://site.example/watch/1?a=1');
  assert.equal(r(same, 'origin'), 'https://site.example/');
  assert.equal(r(other, 'same-origin'), undefined);
  assert.equal(r(other, 'no-referrer-when-downgrade'), 'https://site.example/watch/1?a=1');
  assert.equal(r(insecure, 'strict-origin'), undefined);
  assert.equal(content.referrerFor('about:blank', other), undefined);
});

test('referrerPolicyOf follows the HTML rules for meta referrer over the header policy', () => {
  const docWith = (...contents) => {
    const metas = contents.map((c) => element('meta', {}, { content: c }));
    return { querySelectorAll: (selector) => (selector.startsWith('meta[name="referrer"') ? metas : []) };
  };
  // The last meta whose whole content is a policy wins; a comma list is not one (only the header uses lists).
  assert.equal(content.referrerPolicyOf(docWith('origin', 'bogus, no-referrer')), 'origin');
  assert.equal(content.referrerPolicyOf(docWith('origin', ' No-Referrer ')), 'no-referrer');
  // Legacy keywords.
  assert.equal(content.referrerPolicyOf(docWith('never')), 'no-referrer');
  assert.equal(content.referrerPolicyOf(docWith('always')), 'unsafe-url');
  assert.equal(content.referrerPolicyOf(docWith('origin-when-crossorigin')), 'origin-when-cross-origin');
  assert.equal(content.referrerPolicyOf(docWith('default'), 'same-origin'), 'strict-origin-when-cross-origin');
  // Without a meta, the Referrer-Policy header's policy, else the default.
  assert.equal(content.referrerPolicyOf(docWith(), 'same-origin'), 'same-origin');
  assert.equal(content.referrerPolicyOf({ querySelectorAll: () => [] }), 'strict-origin-when-cross-origin');
  const report = content.elementReports(video({ currentSrc: 'https://cdn.example/a.mp4' }), context({ referrerPolicy: 'no-referrer' }))[0];
  assert.deepEqual(report.requestHeaders, { 'User-Agent': UA });
});

/** A node tree for posterOf: parentElement links, class-selector lookups and on-screen boxes. */
function playerTree({ layerRect, layerStyle, layerTag = 'div', layerAttrs = {}, containerAttrs = {} }) {
  const rect = (left, top, width, height) => ({ left, top, width, height, right: left + width, bottom: top + height });
  const styles = new Map();
  const view = { getComputedStyle: (node) => styles.get(node) || { backgroundImage: 'none' } };
  const ownerDocument = { baseURI: 'https://embed.example/player/1', defaultView: view };
  const layer = element(layerTag, { ownerDocument, getBoundingClientRect: () => layerRect }, layerAttrs);
  if (layerStyle) styles.set(layer, { backgroundImage: layerStyle });
  const el = video({ ownerDocument, getBoundingClientRect: () => rect(0, 0, 640, 360) });
  const container = element('div', { ownerDocument }, containerAttrs);
  container.querySelectorAll = (selector) => (selector.includes('poster') ? [layer] : []);
  el.parentElement = container;
  container.parentElement = element('body');
  return { el, rect };
}

test('posterOf finds posters that player libraries draw outside the poster attribute', () => {
  const full = { left: 0, top: 0, width: 640, height: 360, right: 640, bottom: 360 };
  const layered = playerTree({ layerRect: full, layerStyle: 'url("/art/poster.jpg")' });
  assert.equal(content.posterOf(layered.el), 'https://embed.example/art/poster.jpg');

  const img = playerTree({ layerRect: full, layerTag: 'img' });
  img.el.parentElement.querySelectorAll = (selector) => (selector.includes('poster')
    ? [element('img', { currentSrc: 'https://img.example/p.webp', getBoundingClientRect: () => full })]
    : []);
  assert.equal(content.posterOf(img.el), 'https://img.example/p.webp');

  const attr = playerTree({ layerRect: full, containerAttrs: { 'data-poster': 'thumbs/9.jpg' } });
  assert.equal(content.posterOf(attr.el), 'https://embed.example/player/thumbs/9.jpg');

  // A small thumbnail next to the player (a related video) is not its poster.
  const small = { left: 650, top: 0, width: 120, height: 68, right: 770, bottom: 68 };
  assert.equal(content.posterOf(playerTree({ layerRect: small, layerStyle: 'url(/other.jpg)' }).el), undefined);

  // The poster attribute wins.
  const own = playerTree({ layerRect: full, layerStyle: 'url(/layer.jpg)' });
  own.el.poster = 'https://embed.example/own.jpg';
  assert.equal(content.posterOf(own.el), 'https://embed.example/own.jpg');
});

test('isDark tells a black frame from a picture', () => {
  assert.equal(content.isDark(new Uint8ClampedArray(320 * 180 * 4)), true);
  assert.equal(content.isDark(new Uint8ClampedArray(320 * 180 * 4).fill(128)), false);
});

function fakeCanvasDocument(videos, drawn) {
  const doc = fakeDocument(videos);
  doc.createElement = (tag) => {
    assert.equal(tag, 'canvas');
    const canvas = {
      getContext: () => ({
        drawImage: (source, x, y, w, h) => drawn.push([source, w, h]),
        getImageData: (x, y, w, h) => ({ data: new Uint8ClampedArray(w * h * 4).fill(drawn.dark ? 0 : 200) }),
      }),
      toDataURL: (type, quality) => `data:${type};base64,${canvas.width}x${canvas.height}@${quality}`,
    };
    return canvas;
  };
  return doc;
}

function startCanvasFrame(videos, drawn) {
  const runtime = fakeRuntime();
  runtime.replies['snapcast-hello'] = { messages: [] };
  const timers = fakeTimers();
  content.startContent({
    window: fakeWindow(PAGE, true),
    document: fakeCanvasDocument(videos, drawn),
    browser: { runtime },
    MutationObserver: fakeObserverClass().Observer,
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
    now: () => 1,
  });
  timers.run();
  return { runtime, port: runtime.ports[0] };
}

test('a thumbnails command captures each loaded video once and asks the other frames', () => {
  const drawn = [];
  const playing = video({ currentSrc: 'blob:https://site.example/1', readyState: 4, videoWidth: 1920, videoHeight: 1080 });
  const notLoaded = video({ currentSrc: 'https://cdn.example/b.mp4', readyState: 1, videoWidth: 0, videoHeight: 0 });
  const drm = video({ currentSrc: 'blob:https://site.example/2', readyState: 4, videoWidth: 640, videoHeight: 360, mediaKeys: {} });
  const { runtime, port } = startCanvasFrame([playing, notLoaded, drm], drawn);
  port.onMessage.fire({ type: 'rescan' });
  port.posted.length = 0;

  port.onMessage.fire({ type: 'thumbnails' });
  const items = port.posted.filter((m) => m.type === 'media').flatMap((m) => m.items);
  assert.deepEqual(items.map((i) => [i.url, i.isBlob, i.thumbnail]), [
    ['blob:https://site.example/1', true, 'data:image/jpeg;base64,320x180@0.7'],
  ]);
  assert.deepEqual(drawn.map(([source, w, h]) => [source === playing, w, h]), [[true, 320, 180]]);
  assert.ok(runtime.sent.some((m) => m.kind === 'snapcast-thumbnails-all'));

  // Captured once; replays on later rescans do not carry the picture again.
  port.posted.length = 0;
  port.onMessage.fire({ type: 'thumbnails' });
  port.onMessage.fire({ type: 'rescan' });
  assert.equal(drawn.length, 1);
  assert.ok(port.posted.filter((m) => m.type === 'media').flatMap((m) => m.items).every((i) => !i.thumbnail));
});

test('a black frame is not captured and is tried again on the next request', () => {
  const drawn = [];
  drawn.dark = true;
  const el = video({ currentSrc: 'https://cdn.example/a.mp4', readyState: 2, videoWidth: 640, videoHeight: 360 });
  const { runtime, port } = startCanvasFrame([el], drawn);
  port.onMessage.fire({ type: 'thumbnails' });
  assert.equal(port.posted.filter((m) => m.type === 'media' && m.items.some((i) => i.thumbnail)).length, 0);
  drawn.dark = false;
  runtime.onMessage.fire({ kind: 'snapcast-thumbnails' });
  const thumbs = port.posted.filter((m) => m.type === 'media').flatMap((m) => m.items).filter((i) => i.thumbnail);
  assert.deepEqual(thumbs.map((i) => i.url), ['https://cdn.example/a.mp4']);
  assert.equal(drawn.length, 2);
});

test('a picture is sent again after a rescan without drawing it again, and redrawn for a new source', () => {
  const drawn = [];
  const el = video({ currentSrc: 'https://cdn.example/a.mp4', readyState: 4, videoWidth: 640, videoHeight: 360 });
  const { port } = startCanvasFrame([el], drawn);
  const thumbs = () => port.posted.filter((m) => m.type === 'media').flatMap((m) => m.items).filter((i) => i.thumbnail);

  port.onMessage.fire({ type: 'thumbnails' });
  assert.deepEqual(thumbs().map((i) => i.url), ['https://cdn.example/a.mp4']);

  // The app reset its list (e.g. a download link): the rescan replays reports without pictures, the next
  // request gets the stored picture back.
  port.posted.length = 0;
  port.onMessage.fire({ type: 'rescan' });
  port.onMessage.fire({ type: 'thumbnails' });
  assert.deepEqual(thumbs().map((i) => i.url), ['https://cdn.example/a.mp4']);
  assert.equal(drawn.length, 1);

  // The element now plays another video: it is drawn again.
  port.posted.length = 0;
  el.currentSrc = 'https://cdn.example/b.mp4';
  port.onMessage.fire({ type: 'thumbnails' });
  assert.deepEqual(thumbs().map((i) => i.url), ['https://cdn.example/b.mp4']);
  assert.equal(drawn.length, 2);
});

test('the Referrer-Policy header of the document applies to DOM reports', async () => {
  const runtime = fakeRuntime();
  runtime.replies['snapcast-hello'] = { messages: [] };
  runtime.replies['snapcast-policy'] = { referrerPolicy: 'same-origin' };
  const timers = fakeTimers();
  const el = video({ currentSrc: 'https://cdn.example/a.mp4' });
  content.startContent({
    window: fakeWindow(PAGE, true),
    document: fakeDocument([el]),
    browser: { runtime },
    MutationObserver: fakeObserverClass().Observer,
    setTimeout: timers.setTimeout,
    clearTimeout: timers.clearTimeout,
    now: () => 1,
  });
  await settle();
  timers.run();
  const items = runtime.ports[0].posted.filter((m) => m.type === 'media').flatMap((m) => m.items);
  // same-origin: no Referer for a cross-origin source.
  assert.deepEqual(items.at(-1).requestHeaders, { 'User-Agent': UA });
});
