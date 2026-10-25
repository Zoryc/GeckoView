/*
 * Snapcast detector, background page.
 *
 * Watches every tab's network traffic with webRequest:
 *  - onSendHeaders keeps the request headers worth replaying (Referer, User-Agent, Origin, Cookie,
 *    Authorization) by requestId, and the Cookie header per (tab, host);
 *  - onHeadersReceived classifies responses by URL and Content-Type, drops segments and non-media and
 *    turns the rest into MediaReports (see app.snapcast.core.detection.DetectorProtocol).
 *
 * Reports are routed to the tab's top-frame content script, the only script that talks to the app
 * (runtime.connectNative), so every message reaches the app on the right GeckoSession. Reports are
 * buffered while that script is not ready and flushed when it says hello. Content scripts of child
 * frames send their DOM findings here and they are forwarded the same way.
 */
(function (root) {
  'use strict';

  const C = root.SnapcastClassify || (typeof require === 'function' ? require('./classify.js') : null);

  const CAPTURED_HEADERS = ['Referer', 'User-Agent', 'Origin', 'Cookie', 'Authorization'];
  /** Resource types that can carry media; images, scripts, styles and fonts are never looked at. */
  const RESOURCE_TYPES = ['main_frame', 'sub_frame', 'media', 'xmlhttprequest', 'object', 'other'];

  const DEFAULTS = Object.freeze({
    maxRequests: 3000,
    requestTtlMs: 120000,
    maxBufferedMessages: 200,
    maxSeenUrls: 2000,
    maxCookieHosts: 48,
    maxCookieLength: 8192,
    batchDelayMs: 120,
    maxBatchItems: 50,
    maxCookieRefreshes: 10,
    navigationTimeoutMs: 15000,
  });

  function noop() {}

  function isString(value) {
    return typeof value === 'string' && value.length > 0;
  }

  function isObject(value) {
    return value !== null && typeof value === 'object';
  }

  /**
   * URL of the top-level document a request belongs to: the request itself for main_frame loads, the
   * outermost frame ancestor for sub-frames, the document URL for top-frame sub-resources; else null.
   */
  function topUrlOf(details) {
    if (!details) return null;
    if (details.type === 'main_frame') return isString(details.url) ? details.url : null;
    const ancestors = details.frameAncestors;
    if (Array.isArray(ancestors) && ancestors.length > 0) {
      const top = ancestors[ancestors.length - 1];
      return top && isString(top.url) ? top.url : null;
    }
    if (details.frameId === 0 && isString(details.documentUrl)) return details.documentUrl;
    return null;
  }

  function createBackground(browser, options) {
    const opts = Object.assign({}, DEFAULTS, options || {});
    const now = opts.now || (() => Date.now());
    const setTimer = opts.setTimeout || ((fn, ms) => setTimeout(fn, ms));
    const clearTimer = opts.clearTimeout || ((id) => clearTimeout(id));

    /** requestId -> {tabId, headers, at}; Map order is insertion order, so the oldest entry is first. */
    const requests = new Map();
    /** tabId -> per-tab state. */
    const tabs = new Map();

    function newTab() {
      return {
        /** Incremented for every new top-level navigation; stale async results compare against it. */
        epoch: 0,
        /** Incremented for every hello of a top-frame content script. */
        helloSeq: 0,
        /** The top-frame content script said hello and accepts deliveries. */
        ready: false,
        topUrl: null,
        topTitle: null,
        navigating: false,
        navRequestId: null,
        oldTopUrl: null,
        navUrls: new Set(),
        navTimer: null,
        buffer: [],
        pending: [],
        batchTimer: null,
        cookies: new Map(),
        cookiesChanged: false,
        cookieRefreshes: 0,
        seen: new Map(),
        lastPlaylist: null,
      };
    }

    function tabFor(tabId) {
      let tab = tabs.get(tabId);
      if (!tab) {
        tab = newTab();
        tabs.set(tabId, tab);
      }
      return tab;
    }

    function cancelTimers(tab) {
      if (tab.batchTimer !== null) clearTimer(tab.batchTimer);
      if (tab.navTimer !== null) clearTimer(tab.navTimer);
      tab.batchTimer = null;
      tab.navTimer = null;
    }

    function endNavigation(tab) {
      tab.navigating = false;
      tab.navRequestId = null;
      tab.oldTopUrl = null;
      tab.navUrls = new Set();
      if (tab.navTimer !== null) clearTimer(tab.navTimer);
      tab.navTimer = null;
    }

    /** A main_frame request: the tab's buffered reports, cookies and throttling belong to the old page. */
    function startNavigation(tab, details) {
      const sameNavigation = tab.navigating && tab.navRequestId !== null && tab.navRequestId === details.requestId;
      if (!tab.navigating) {
        tab.oldTopUrl = tab.topUrl;
        tab.navUrls = new Set();
      }
      tab.navigating = true;
      tab.navRequestId = details.requestId;
      tab.navUrls.add(C.stripFragment(details.url));
      if (tab.navTimer !== null) clearTimer(tab.navTimer);
      tab.navTimer = setTimer(() => {
        tab.navTimer = null;
        endNavigation(tab);
      }, opts.navigationTimeoutMs);
      if (sameNavigation) return;
      tab.epoch += 1;
      tab.buffer = [];
      tab.pending = [];
      if (tab.batchTimer !== null) clearTimer(tab.batchTimer);
      tab.batchTimer = null;
      tab.cookies = new Map();
      tab.cookiesChanged = false;
      tab.cookieRefreshes = 0;
      tab.seen = new Map();
      tab.lastPlaylist = null;
    }

    function rememberRequest(requestId, tabId, headers) {
      if (requestId === undefined || requestId === null) return;
      requests.delete(requestId);
      requests.set(requestId, { tabId, headers, at: now() });
      const oldest = now() - opts.requestTtlMs;
      for (const [id, entry] of requests) {
        if (requests.size <= opts.maxRequests && entry.at >= oldest) break;
        requests.delete(id);
      }
    }

    function rememberCookie(tab, host, cookie) {
      if (!host || !isString(cookie)) return;
      const value = cookie.length > opts.maxCookieLength ? cookie.substring(0, opts.maxCookieLength) : cookie;
      if (tab.cookies.get(host) === value) return;
      if (!tab.cookies.has(host) && tab.cookies.size >= opts.maxCookieHosts) return;
      tab.cookies.set(host, value);
      tab.cookiesChanged = true;
    }

    function cookiesOf(tab) {
      const result = {};
      for (const [host, value] of tab.cookies) result[host] = value;
      return result;
    }

    /** False when this URL was already reported for the current page and the report would add nothing. */
    function firstSighting(tab, url, size) {
      const key = C.dedupeKey(url);
      if (tab.seen.has(key)) {
        const previous = tab.seen.get(key);
        if (size === null || size === previous) return false;
      } else if (tab.seen.size >= opts.maxSeenUrls) {
        const oldest = tab.seen.keys().next();
        if (!oldest.done) tab.seen.delete(oldest.value);
      }
      tab.seen.set(key, size);
      return true;
    }

    /** Requests still made by the previous document while the next one is loading are not reported. */
    function isLeftover(tab, details, topUrl) {
      if (!tab.navigating || details.type === 'main_frame' || !topUrl || !tab.oldTopUrl) return false;
      const top = C.stripFragment(topUrl);
      return top === C.stripFragment(tab.oldTopUrl) && !tab.navUrls.has(top);
    }

    function bufferMessage(tab, message) {
      tab.buffer.push(message);
      if (tab.buffer.length > opts.maxBufferedMessages) {
        tab.buffer.splice(0, tab.buffer.length - opts.maxBufferedMessages);
      }
    }

    /** Sends one protocol message to the tab's top frame, or buffers it until that frame says hello. */
    function deliver(tabId, tab, message) {
      if (!tab.ready) {
        bufferMessage(tab, message);
        return;
      }
      const epoch = tab.epoch;
      const helloSeq = tab.helloSeq;
      let sending;
      try {
        sending = browser.tabs.sendMessage(tabId, { kind: C.MESSAGE.DELIVER, message }, { frameId: 0 });
      } catch (e) {
        sending = Promise.reject(e);
      }
      Promise.resolve(sending).then(noop, () => {
        const current = tabs.get(tabId);
        if (!current || current.epoch !== epoch) return;
        if (current.helloSeq !== helloSeq) {
          deliver(tabId, current, message);
          return;
        }
        current.ready = false;
        bufferMessage(current, message);
      });
    }

    function flushPending(tabId) {
      const tab = tabs.get(tabId);
      if (!tab) return;
      tab.batchTimer = null;
      const items = tab.pending;
      tab.pending = [];
      if (items.length === 0) return;
      for (const message of C.mergeMessages([{ type: 'media', items }], opts.maxBatchItems)) {
        deliver(tabId, tab, message);
      }
    }

    function queueReport(tabId, tab, report) {
      tab.pending.push(report);
      if (tab.batchTimer === null) tab.batchTimer = setTimer(() => flushPending(tabId), opts.batchDelayMs);
    }

    function networkReport(details, tab, info) {
      const isDocument = details.type === 'main_frame' || details.type === 'sub_frame';
      const frameUrl = isDocument ? details.url : (details.documentUrl || info.topUrl);
      const pageUrl = details.type === 'main_frame'
        ? details.url
        : (info.topUrl || tab.topUrl || details.originUrl || details.documentUrl);
      return C.compact({
        url: details.url,
        source: 'network',
        mime: C.baseMime(info.contentType),
        size: info.size,
        status: typeof details.statusCode === 'number' ? details.statusCode : null,
        resourceType: details.type,
        frameUrl,
        pageUrl,
        pageTitle: details.type === 'main_frame' || tab.navigating ? null : tab.topTitle,
        requestHeaders: Object.assign({}, info.headers),
        cookiesByHost: cookiesOf(tab),
        timestamp: now(),
      });
    }

    /**
     * Segment CDNs often set or need cookies only after the playlist was reported: re-send the latest
     * playlist with the tab's current cookies (the app merges cookiesByHost).
     */
    function refreshCookies(tabId, tab) {
      if (!tab.cookiesChanged || !tab.lastPlaylist || tab.cookieRefreshes >= opts.maxCookieRefreshes) return;
      tab.cookiesChanged = false;
      tab.cookieRefreshes += 1;
      const refreshed = Object.assign({}, tab.lastPlaylist, { cookiesByHost: cookiesOf(tab), timestamp: now() });
      tab.lastPlaylist = refreshed;
      queueReport(tabId, tab, refreshed);
    }

    function onSendHeaders(details) {
      try {
        if (!details || typeof details.tabId !== 'number' || details.tabId < 0) return;
        const tab = tabFor(details.tabId);
        if (details.type === 'main_frame') startNavigation(tab, details);
        const headers = C.pickHeaders(details.requestHeaders, CAPTURED_HEADERS);
        rememberRequest(details.requestId, details.tabId, headers);
        if (headers.Cookie) rememberCookie(tab, C.hostOf(details.url), headers.Cookie);
      } catch (ignored) {
        // A detector failure must never disturb browsing.
      }
    }

    function onHeadersReceived(details) {
      try {
        if (!details || typeof details.tabId !== 'number' || details.tabId < 0) return;
        const status = details.statusCode;
        const redirect = typeof status === 'number' && status >= 300 && status < 400;
        const request = requests.get(details.requestId);
        if (!redirect) requests.delete(details.requestId);
        if (redirect || (typeof status === 'number' && status !== 0 && (status < 200 || status >= 300))) return;
        if (!C.isHttpUrl(details.url)) return;

        const tab = tabFor(details.tabId);
        const contentType = C.getHeader(details.responseHeaders, 'content-type');
        const size = C.sizeFromHeaders(details.responseHeaders, status);
        const result = C.classify(details.url, contentType, details.type, size);
        if (result.kind === C.KIND.SEGMENT) {
          refreshCookies(details.tabId, tab);
          return;
        }
        if (!C.isReportable(result)) return;
        const topUrl = topUrlOf(details);
        if (isLeftover(tab, details, topUrl)) return;
        if (!firstSighting(tab, details.url, size)) return;

        const report = networkReport(details, tab, {
          contentType,
          size,
          topUrl,
          headers: request ? request.headers : {},
        });
        if (result.kind === C.KIND.PLAYLIST) {
          tab.lastPlaylist = report;
          tab.cookiesChanged = false;
        }
        queueReport(details.tabId, tab, report);
      } catch (ignored) {
        // A detector failure must never disturb browsing.
      }
    }

    function onMainFrameDone(details) {
      try {
        if (!details || typeof details.tabId !== 'number' || details.tabId < 0) return;
        requests.delete(details.requestId);
        const tab = tabs.get(details.tabId);
        if (tab && tab.navigating && tab.navRequestId === details.requestId) endNavigation(tab);
      } catch (ignored) {
        // A detector failure must never disturb browsing.
      }
    }

    function onHello(tabId, message, sender) {
      const tab = tabFor(tabId);
      tab.ready = true;
      tab.helloSeq += 1;
      endNavigation(tab);
      tab.topUrl = isString(message.url) ? message.url : (isString(sender.url) ? sender.url : null);
      tab.topTitle = isString(message.title) ? message.title : null;
      const messages = C.mergeMessages(tab.buffer, opts.maxBatchItems);
      tab.buffer = [];
      return { messages };
    }

    function onTop(tabId, message) {
      const tab = tabFor(tabId);
      if (isString(message.url)) tab.topUrl = message.url;
      if (isString(message.title)) tab.topTitle = message.title;
    }

    /** DOM findings of a (child) frame: fill in where they come from, then route them to the top frame. */
    function onFrameReport(tabId, sender, message) {
      if (!isObject(message) || !isString(message.type)) return;
      const tab = tabFor(tabId);
      const senderUrl = isString(sender.url) ? sender.url : null;
      if (message.type === 'media' && Array.isArray(message.items)) {
        const items = message.items.filter(isObject).map((item) => {
          const frameUrl = item.frameUrl || senderUrl;
          return C.compact(Object.assign({}, item, {
            frameUrl,
            pageUrl: item.pageUrl || tab.topUrl || frameUrl,
            pageTitle: item.pageTitle || tab.topTitle,
          }));
        });
        if (items.length > 0) deliver(tabId, tab, { type: 'media', items });
      } else if (message.type === 'drm') {
        deliver(tabId, tab, C.compact({
          type: 'drm',
          keySystem: isString(message.keySystem) ? message.keySystem : null,
          frameUrl: message.frameUrl || senderUrl,
        }));
      }
    }

    function broadcastRescan(tabId) {
      try {
        Promise.resolve(browser.tabs.sendMessage(tabId, { kind: C.MESSAGE.RESCAN })).then(noop, noop);
      } catch (ignored) {
        // A detector failure must never disturb browsing.
      }
    }

    function onRuntimeMessage(message, sender) {
      try {
        if (!isObject(message) || !isString(message.kind)) return undefined;
        if (!sender || !sender.tab || typeof sender.tab.id !== 'number' || sender.tab.id < 0) return undefined;
        const tabId = sender.tab.id;
        const isTopFrame = sender.frameId === 0 || sender.frameId === undefined;
        switch (message.kind) {
          case C.MESSAGE.HELLO:
            return isTopFrame ? Promise.resolve(onHello(tabId, message, sender)) : undefined;
          case C.MESSAGE.TOP:
            if (isTopFrame) onTop(tabId, message);
            return undefined;
          case C.MESSAGE.REPORT:
            onFrameReport(tabId, sender, message.message);
            return undefined;
          case C.MESSAGE.RESCAN_ALL:
            broadcastRescan(tabId);
            return undefined;
          default:
            return undefined;
        }
      } catch (e) {
        return undefined;
      }
    }

    function onTabRemoved(tabId) {
      try {
        const tab = tabs.get(tabId);
        if (tab) cancelTimers(tab);
        tabs.delete(tabId);
        for (const [id, entry] of requests) {
          if (entry.tabId === tabId) requests.delete(id);
        }
      } catch (ignored) {
        // A detector failure must never disturb browsing.
      }
    }

    function addListener(event, listener, ...extra) {
      try {
        if (event && typeof event.addListener === 'function') event.addListener(listener, ...extra);
      } catch (ignored) {
        // A detector failure must never disturb browsing.
      }
    }

    function start() {
      const webRequest = browser.webRequest || {};
      const filter = { urls: ['<all_urls>'], types: RESOURCE_TYPES };
      const mainFrames = { urls: ['<all_urls>'], types: ['main_frame'] };
      addListener(webRequest.onSendHeaders, onSendHeaders, filter, ['requestHeaders']);
      addListener(webRequest.onHeadersReceived, onHeadersReceived, filter, ['responseHeaders']);
      addListener(webRequest.onCompleted, onMainFrameDone, mainFrames);
      addListener(webRequest.onErrorOccurred, onMainFrameDone, mainFrames);
      addListener(browser.runtime && browser.runtime.onMessage, onRuntimeMessage);
      addListener(browser.tabs && browser.tabs.onRemoved, onTabRemoved);
    }

    return {
      start,
      onSendHeaders,
      onHeadersReceived,
      onMainFrameDone,
      onRuntimeMessage,
      onTabRemoved,
      flushPending,
      /** Internal state, exposed for tests. */
      tabs,
      requests,
    };
  }

  const api = { createBackground, topUrlOf, CAPTURED_HEADERS, RESOURCE_TYPES };
  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  } else if (typeof browser !== 'undefined' && browser && browser.webRequest) {
    createBackground(browser).start();
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
