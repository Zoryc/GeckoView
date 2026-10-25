'use strict';

/*
 * End-to-end flow of the detector scripts with a fake browser that routes messages between the
 * background page and the content scripts of a tab's frames, and a fake native port.
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const { createBackground } = require('../../main/assets/extensions/snapcast_detector/background.js');
const content = require('../../main/assets/extensions/snapcast_detector/content.js');

const TAB = 3;
const PAGE = 'https://site.example/watch';
const FRAME = 'https://player.example/embed/9';

const settle = () => new Promise((resolve) => setImmediate(resolve));
const clone = (value) => (value === undefined ? undefined : JSON.parse(JSON.stringify(value)));

function timers() {
  let queue = [];
  let id = 0;
  return {
    setTimeout(fn) {
      queue.push({ id: ++id, fn });
      return id;
    },
    clearTimeout(timerId) {
      queue = queue.filter((t) => t.id !== timerId);
    },
    run() {
      for (let round = 0; round < 20 && queue.length; round++) {
        const due = queue;
        queue = [];
        for (const t of due) t.fn();
      }
    },
  };
}

function listeners() {
  const list = [];
  return { list, addListener: (fn) => list.push(fn) };
}

function element(tag, props, children) {
  const kids = children || [];
  return Object.assign({
    nodeName: tag.toUpperCase(),
    nodeType: 1,
    getAttribute: () => null,
    querySelectorAll: (selector) => kids.filter((k) => k.nodeName === selector.toUpperCase()),
  }, props);
}

function documentWith(videos, title) {
  return {
    title,
    visibilityState: 'visible',
    getElementsByTagName: (name) => (name === 'video' ? videos : []),
    querySelector: () => null,
    addEventListener() {},
  };
}

function windowAt(url, top) {
  const win = { location: { href: url }, navigator: { userAgent: 'UA' }, addEventListener() {} };
  win.top = top || win;
  return win;
}

/** A tab with a top frame and one child frame, wired to one background page. */
function world() {
  const clock = timers();
  const nativePorts = [];
  const frames = [];
  const backgroundRuntime = { onMessage: listeners() };
  const tabsApi = {
    onRemoved: listeners(),
    sendMessage(tabId, message, options) {
      const targets = frames.filter((f) => !options || options.frameId === undefined || f.frameId === options.frameId);
      if (tabId !== TAB || targets.length === 0) return Promise.reject(new Error('Receiving end does not exist'));
      let reply;
      for (const frame of targets) {
        for (const fn of frame.runtime.onMessage.list) {
          const value = fn(clone(message), { id: 'detector@snapcast.app' });
          if (value !== undefined) reply = value;
        }
      }
      return Promise.resolve(reply);
    },
  };
  const background = createBackground(
    { tabs: tabsApi, runtime: backgroundRuntime, webRequest: {} },
    { setTimeout: clock.setTimeout, clearTimeout: clock.clearTimeout },
  );

  function addFrame(frameId, url, doc, win) {
    const runtime = {
      onMessage: listeners(),
      sendMessage(message) {
        return Promise.resolve(background.onRuntimeMessage(clone(message), { tab: { id: TAB }, frameId, url }));
      },
      connectNative(name) {
        const port = { name, posted: [], onMessage: listeners(), onDisconnect: listeners(), postMessage: (m) => port.posted.push(clone(m)) };
        nativePorts.push(port);
        return port;
      },
    };
    frames.push({ frameId, runtime });
    content.startContent({
      window: win,
      document: doc,
      browser: { runtime },
      MutationObserver: class {
        observe() {}
      },
      setTimeout: clock.setTimeout,
      clearTimeout: clock.clearTimeout,
    });
  }

  return { clock, nativePorts, background, addFrame };
}

function postedMedia(port) {
  return port.posted.filter((m) => m.type === 'media').flatMap((m) => m.items);
}

test('network finds, top-frame and child-frame DOM finds all reach the app on the top frame port', async () => {
  const w = world();

  // The page's HLS playlist is fetched before any content script runs: it is buffered.
  w.background.onSendHeaders({ requestId: '1', tabId: TAB, frameId: 0, type: 'main_frame', url: PAGE, requestHeaders: [] });
  w.background.onHeadersReceived({ requestId: '1', tabId: TAB, frameId: 0, type: 'main_frame', url: PAGE, statusCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'text/html' }] });
  w.background.onSendHeaders({
    requestId: '2',
    tabId: TAB,
    frameId: 0,
    type: 'xmlhttprequest',
    url: 'https://cdn.example/master.m3u8',
    requestHeaders: [{ name: 'Referer', value: PAGE }, { name: 'Cookie', value: 'cdn=1' }],
  });
  w.background.onHeadersReceived({
    requestId: '2',
    tabId: TAB,
    frameId: 0,
    type: 'xmlhttprequest',
    url: 'https://cdn.example/master.m3u8',
    documentUrl: PAGE,
    frameAncestors: [],
    statusCode: 200,
    responseHeaders: [{ name: 'Content-Type', value: 'application/vnd.apple.mpegurl' }],
  });
  w.clock.run();

  const topWindow = windowAt(PAGE);
  const topVideo = element('video', { currentSrc: 'blob:https://site.example/42', src: '', duration: 600, poster: 'https://site.example/p.jpg' });
  w.addFrame(0, PAGE, documentWith([topVideo], 'Watch page'), topWindow);
  const childVideo = element('video', { currentSrc: 'https://cdn.example/embed.mp4', src: '', duration: 30 }, [
    element('track', { kind: 'captions', src: 'https://cdn.example/embed.en.vtt', srclang: 'en' }),
  ]);
  w.addFrame(5, FRAME, documentWith([childVideo], 'Embedded clip'), windowAt(FRAME, topWindow));

  await settle();
  w.clock.run();
  await settle();

  const port = w.nativePorts[0];
  assert.equal(w.nativePorts.length, 1, 'only the top frame connects');
  assert.equal(port.name, 'snapcast');
  assert.deepEqual(port.posted[0], { type: 'hello', url: PAGE, title: 'Watch page' });

  const items = postedMedia(port);
  const network = items.find((i) => i.source === 'network');
  assert.equal(network.url, 'https://cdn.example/master.m3u8');
  assert.deepEqual(network.cookiesByHost, { 'cdn.example': 'cdn=1' });
  assert.equal(network.pageUrl, PAGE);

  const blob = items.find((i) => i.isBlob);
  assert.equal(blob.url, 'blob:https://site.example/42');
  assert.equal(blob.poster, 'https://site.example/p.jpg');

  const embedded = items.find((i) => i.url === 'https://cdn.example/embed.mp4');
  assert.equal(embedded.frameUrl, FRAME);
  assert.equal(embedded.pageUrl, PAGE);
  assert.equal(embedded.title, 'Embedded clip');
  assert.equal(embedded.pageTitle, 'Watch page');
  assert.deepEqual(embedded.tracks, [{ url: 'https://cdn.example/embed.en.vtt', kind: 'captions', lang: 'en' }]);

  assert.deepEqual(port.posted.find((m) => m.type === 'page'), { type: 'page', url: PAGE, title: 'Watch page' });

  // The app answers on the port: the content script replays, and every frame rescans.
  port.posted.length = 0;
  port.onMessage.list.forEach((fn) => fn({ type: 'rescan' }));
  await settle();
  w.clock.run();
  await settle();
  const replayed = postedMedia(port).map((i) => i.url);
  assert.ok(replayed.includes('https://cdn.example/master.m3u8'));
  assert.ok(replayed.includes('https://cdn.example/embed.mp4'));
  assert.equal(port.posted[0].type, 'hello');
});
