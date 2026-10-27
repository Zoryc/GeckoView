/*
 * Snapcast detector, content script (every frame, from document_start).
 *
 * Every frame scans its <video> elements (their <source> and <track> children included), again when
 * nodes are added or src/poster attributes change (MutationObserver) and when media events fire, and
 * reports http(s) sources as MediaReports (see app.snapcast.core.detection.DetectorProtocol). A report
 * carries the Referer the page would send for that source (its referrer policy applied) and the poster
 * the player shows, even when a player library draws it in a layer of its own instead of `poster`.
 *
 * While the app's video list is open it asks for frame thumbnails: every frame then draws each loaded
 * video once into a small canvas and reports it (never before, so pages pay nothing for it otherwise).
 *
 * Only the top frame talks to the app: it opens runtime.connectNative("snapcast"), says hello, sends
 * page metadata and its own findings, and relays what the background page delivers (network finds and
 * the findings of child frames). Child frames send their findings to the background page.
 *
 * Messages posted before the app has attached its port delegate may be lost, so the top frame keeps a
 * log of everything it sent and replays it once the app answers on the port (the app posts a rescan
 * command right after connecting), after a reconnection and on every later rescan command. The app
 * de-duplicates by URL.
 */
(function (root) {
  'use strict';

  const C = root.SnapcastClassify || (typeof require === 'function' ? require('./classify.js') : null);
  const M = C.MESSAGE;

  const NATIVE_APP = 'snapcast';
  const TEXT_TRACK_KINDS = new Set(['subtitles', 'captions']);
  const MEDIA_EVENTS = ['loadstart', 'loadedmetadata', 'durationchange', 'play', 'emptied'];
  const SCAN_DEBOUNCE_MS = 300;
  const SCAN_MAX_WAIT_MS = 1500;
  const BROADCAST_ECHO_MS = 1500;
  const HELLO_RETRIES = 4;
  /** A frame stops reporting new URLs past this many (pathological pages). */
  const MAX_REPORTED_URLS = 300;
  const DEFAULT_REFERRER_POLICY = 'strict-origin-when-cross-origin';
  const REFERRER_POLICIES = new Set([
    'no-referrer', 'no-referrer-when-downgrade', 'origin', 'origin-when-cross-origin', 'same-origin',
    'strict-origin', 'strict-origin-when-cross-origin', 'unsafe-url',
  ]);
  /** Legacy <meta name="referrer"> keywords the HTML spec still maps to a policy. */
  const LEGACY_META_POLICIES = {
    never: 'no-referrer',
    always: 'unsafe-url',
    'origin-when-crossorigin': 'origin-when-cross-origin',
    default: DEFAULT_REFERRER_POLICY,
  };
  /** Attributes player libraries put a poster URL in (on the <video> or its container). */
  const POSTER_ATTRIBUTES = ['data-poster', 'data-poster-url', 'data-thumbnail', 'data-thumb'];
  /** Layers player libraries draw a poster with (video.js, Plyr, ArtPlayer, Clappr, JW Player, Flowplayer...). */
  const POSTER_LAYERS = '[class*="poster" i], [class*="thumbnail" i], .jw-preview, .fp-splash';
  /** How many ancestors of a <video> are searched for a poster, and how many layers per ancestor. */
  const POSTER_SEARCH_DEPTH = 4;
  const POSTER_MAX_LAYERS = 6;
  /** A poster layer must cover at least this share of the player, so a related video's thumbnail is never taken. */
  const POSTER_MIN_COVER = 0.5;
  const POSTER_CACHE_MS = 5000;
  const posterCache = typeof WeakMap === 'function' ? new WeakMap() : null;
  const THUMBNAIL_WIDTH = 320;
  const THUMBNAIL_QUALITY = 0.7;

  function noop() {}

  function text(value) {
    if (typeof value !== 'string') return undefined;
    const trimmed = value.trim();
    return trimmed ? trimmed : undefined;
  }

  function positive(value) {
    return typeof value === 'number' && Number.isFinite(value) && value > 0 ? value : undefined;
  }

  function positiveInt(value) {
    const number = positive(value);
    return number === undefined ? undefined : Math.round(number);
  }

  /** blob: / mediasource: sources (MediaSource Extensions) cannot be cast; they only enrich network finds. */
  function isBlobUrl(url) {
    return typeof url === 'string' && /^(blob|mediasource):/i.test(url);
  }

  function attribute(element, name) {
    try {
      return typeof element.getAttribute === 'function' ? element.getAttribute(name) : null;
    } catch (ignored) {
      return null;
    }
  }

  function queryAll(node, selector) {
    try {
      return typeof node.querySelectorAll === 'function' ? node.querySelectorAll(selector) : [];
    } catch (ignored) {
      return [];
    }
  }

  function absolute(url, base) {
    const value = text(url);
    if (!value) return undefined;
    try {
      return new URL(value, base).href;
    } catch (ignored) {
      return undefined;
    }
  }

  function httpOnly(url) {
    return C.isHttpUrl(url) ? url : undefined;
  }

  /**
   * The document's referrer policy, as the HTML spec sets it: [headerPolicy] (from its Referrer-Policy response
   * header, else the default), then each <meta name="referrer"> whose whole content (or legacy keyword) is a policy.
   */
  function referrerPolicyOf(doc, headerPolicy) {
    let policy = headerPolicy || DEFAULT_REFERRER_POLICY;
    const metas = queryAll(doc, 'meta[name="referrer" i]');
    for (let i = 0; i < metas.length; i++) {
      const value = String(attribute(metas[i], 'content') || '').trim().toLowerCase();
      const mapped = Object.prototype.hasOwnProperty.call(LEGACY_META_POLICIES, value) ? LEGACY_META_POLICIES[value] : value;
      if (REFERRER_POLICIES.has(mapped)) policy = mapped;
    }
    return policy;
  }

  /** The Referer a document at [documentUrl] sends for [targetUrl] under [policy]; undefined for none. */
  function referrerFor(documentUrl, targetUrl, policy) {
    let from;
    let to;
    try {
      from = new URL(documentUrl);
      to = new URL(targetUrl);
    } catch (ignored) {
      return undefined;
    }
    if (!/^https?:$/.test(from.protocol)) return undefined;
    from.username = '';
    from.password = '';
    from.hash = '';
    const full = from.href;
    const origin = `${from.origin}/`;
    const sameOrigin = from.origin === to.origin;
    const downgrade = from.protocol === 'https:' && to.protocol === 'http:';
    switch (policy) {
      case 'no-referrer': return undefined;
      case 'unsafe-url': return full;
      case 'origin': return origin;
      case 'same-origin': return sameOrigin ? full : undefined;
      case 'strict-origin': return downgrade ? undefined : origin;
      case 'origin-when-cross-origin': return sameOrigin ? full : origin;
      case 'no-referrer-when-downgrade': return downgrade ? undefined : full;
      default: return downgrade ? undefined : (sameOrigin ? full : origin);
    }
  }

  function baseUrlOf(node) {
    try {
      return node.ownerDocument && node.ownerDocument.baseURI ? node.ownerDocument.baseURI : undefined;
    } catch (ignored) {
      return undefined;
    }
  }

  function cssUrl(value) {
    const match = /url\(\s*(['"]?)(.*?)\1\s*\)/i.exec(value || '');
    return match ? match[2] : undefined;
  }

  /** True when an RGBA pixel buffer is (nearly) black: a video that has not painted a real frame yet. */
  function isDark(pixels) {
    let sum = 0;
    let count = 0;
    const step = Math.max(4, Math.floor(pixels.length / 4 / 256) * 4);
    for (let i = 0; i + 2 < pixels.length; i += step) {
      sum += pixels[i] + pixels[i + 1] + pixels[i + 2];
      count += 3;
    }
    return count === 0 || sum / count < 12;
  }

  function rectOf(node) {
    try {
      const rect = typeof node.getBoundingClientRect === 'function' ? node.getBoundingClientRect() : null;
      return rect && rect.width > 0 && rect.height > 0 ? rect : null;
    } catch (ignored) {
      return null;
    }
  }

  /** True when [layer] covers at least POSTER_MIN_COVER of [player] on screen (unknown sizes count as covering). */
  function covers(layer, player) {
    const a = rectOf(layer);
    if (!a || !player) return true;
    const width = Math.min(a.right, player.right) - Math.max(a.left, player.left);
    const height = Math.min(a.bottom, player.bottom) - Math.max(a.top, player.top);
    return width > 0 && height > 0 && width * height >= POSTER_MIN_COVER * player.width * player.height;
  }

  /** The image an element shows: an <img>'s source, else its CSS background image or a lazy-loading attribute. */
  function imageOf(node, base) {
    if (nodeName(node) === 'IMG') {
      return absolute(text(node.currentSrc) || text(node.src) || attribute(node, 'data-src'), base);
    }
    let background;
    try {
      const view = node.ownerDocument && node.ownerDocument.defaultView;
      background = view ? cssUrl(view.getComputedStyle(node).backgroundImage) : undefined;
    } catch (ignored) {
      background = undefined;
    }
    return absolute(background || attribute(node, 'data-src') || attribute(node, 'data-bg'), base);
  }

  /**
   * The poster a player shows for [element]: its poster attribute, else a poster attribute player libraries
   * use (data-poster...) on it or a container, else the image of a poster layer drawn over the player (only a
   * layer covering most of the player counts, so thumbnails of other videos nearby are never taken).
   */
  function posterOf(element) {
    const own = httpOnly(text(element.poster));
    if (own) return own;
    // Scans can be frequent on busy pages: the search below is repeated at most every few seconds per element.
    const time = Date.now();
    const src = text(element.currentSrc) || text(element.src) || '';
    const cached = posterCache && posterCache.get(element);
    if (cached && cached.src === src && time - cached.at < POSTER_CACHE_MS) return cached.url;
    const url = findPosterAround(element);
    if (posterCache) posterCache.set(element, { url, src, at: time });
    return url;
  }

  function findPosterAround(element) {
    const base = baseUrlOf(element);
    let player = rectOf(element);
    let node = element;
    for (let depth = 0; node && depth <= POSTER_SEARCH_DEPTH; depth++, node = node.parentElement) {
      const name = nodeName(node);
      if (name === 'BODY' || name === 'HTML') break;
      for (const attr of POSTER_ATTRIBUTES) {
        const url = httpOnly(absolute(attribute(node, attr), base));
        if (url) return url;
      }
      if (depth === 0) continue;
      player = player || rectOf(node);
      const layers = queryAll(node, POSTER_LAYERS);
      for (let i = 0; i < layers.length && i < POSTER_MAX_LAYERS; i++) {
        const layer = layers[i];
        if (!covers(layer, player)) continue;
        const image = nodeName(layer) === 'IMG' ? layer : (queryAll(layer, 'img')[0] || layer);
        const url = httpOnly(imageOf(image, base));
        if (url) return url;
      }
    }
    return undefined;
  }

  /** Subtitle/caption <track> children of a media element as TrackReports (absolute http(s) URLs only). */
  function trackReports(element) {
    const result = [];
    const tracks = queryAll(element, 'track');
    for (let i = 0; i < tracks.length; i++) {
      const track = tracks[i];
      const kind = (text(track.kind) || text(attribute(track, 'kind')) || 'subtitles').toLowerCase();
      if (!TEXT_TRACK_KINDS.has(kind)) continue;
      const url = httpOnly(text(track.src));
      if (!url || result.some((t) => t.url === url)) continue;
      result.push(C.compact({ url, kind, lang: text(track.srclang), label: text(track.label) }));
    }
    return result;
  }

  /** Candidate sources of a media element: the selected one first, then src and the <source> children. */
  function sourceCandidates(element) {
    const result = [];
    const add = (url, mime) => {
      const value = text(url);
      if (value && !result.some((c) => c.url === value)) result.push({ url: value, mime: text(mime) });
    };
    add(element.currentSrc);
    add(element.src);
    const sources = queryAll(element, 'source');
    for (let i = 0; i < sources.length; i++) add(sources[i].src, sources[i].type);
    return result;
  }

  function elementTitle(element, context) {
    for (const name of ['title', 'aria-label', 'data-title']) {
      const value = text(attribute(element, name));
      if (value) return value;
    }
    // An embedded player's document is usually titled after its video.
    return context.isTop ? undefined : context.frameTitle;
  }

  /**
   * MediaReports for one <video>. Once the element selected a resource (currentSrc) only that one is
   * reported; before that every http(s) candidate is. blob:/MediaSource sources are reported with
   * isBlob so their metadata can enrich the stream found on the network.
   */
  function elementReports(element, context) {
    const tracks = trackReports(element);
    const current = text(element.currentSrc);
    let candidates = sourceCandidates(element);
    if (current) candidates = candidates.filter((c) => c.url === current);
    const base = {
      source: 'dom',
      frameUrl: context.frameUrl,
      pageUrl: context.pageUrl,
      pageTitle: context.pageTitle,
      title: elementTitle(element, context),
      duration: positive(element.duration),
      width: positiveInt(element.videoWidth),
      height: positiveInt(element.videoHeight),
      poster: posterOf(element),
      thumbnail: context.thumbnail,
      tracks,
      timestamp: context.now(),
    };
    const reports = [];
    for (const candidate of candidates) {
      if (isBlobUrl(candidate.url)) {
        reports.push(C.compact(Object.assign({}, base, { url: candidate.url, isBlob: true })));
      } else if (C.isHttpUrl(candidate.url)) {
        // The Referer the page itself sends for this source, following its referrer policy.
        const requestHeaders = C.compact(Object.assign({}, context.requestHeaders, {
          Referer: referrerFor(context.frameUrl, candidate.url, context.referrerPolicy),
        }));
        reports.push(C.compact(Object.assign({}, base, { url: candidate.url, mime: candidate.mime, isBlob: false, requestHeaders })));
      }
    }
    return reports;
  }

  /** What makes a report different from the previous one for the same URL (timestamps excluded). */
  function reportSignature(report) {
    return JSON.stringify([
      report.url,
      report.duration === undefined ? null : Math.round(report.duration),
      report.width || null,
      report.height || null,
      report.poster || null,
      report.title || null,
      (report.tracks || []).map((t) => t.url),
    ]);
  }

  function metaContent(doc, names) {
    for (const name of names) {
      try {
        const element = doc.querySelector(`meta[property="${name}"], meta[name="${name}"]`);
        const value = element ? text(attribute(element, 'content')) : undefined;
        if (value) return value;
      } catch (ignored) {
        // Keep looking.
      }
    }
    return undefined;
  }

  /** The "page" protocol message for the top document. */
  function pageInfo(doc, url) {
    return C.compact({
      type: 'page',
      url,
      title: text(doc.title),
      ogTitle: metaContent(doc, ['og:title', 'twitter:title']),
      ogImage: httpOnly(absolute(metaContent(doc, ['og:image', 'og:image:url', 'twitter:image']), url)),
    });
  }

  function withoutThumbnails(message) {
    if (message.type !== 'media' || !Array.isArray(message.items) || !message.items.some((item) => item && item.thumbnail)) {
      return message;
    }
    return Object.assign({}, message, {
      items: message.items.map((item) => {
        if (!item || !item.thumbnail) return item;
        const copy = Object.assign({}, item);
        delete copy.thumbnail;
        return copy;
      }),
    });
  }

  function countItems(message) {
    return message.type === 'media' && Array.isArray(message.items) ? message.items.length : 1;
  }

  /**
   * The top frame's native-messaging port with reconnection and replay. [options]: hello() builds the
   * hello message, onCommand(command, confirming) receives app commands (confirming is true for the
   * first message on a port, which already triggered a replay); setTimeout/clearTimeout for tests.
   */
  function createNativeLink(runtime, options) {
    const opts = options || {};
    const appName = opts.appName || NATIVE_APP;
    const setTimer = opts.setTimeout || ((fn, ms) => setTimeout(fn, ms));
    const clearTimer = opts.clearTimeout || ((id) => clearTimeout(id));
    const maxLogItems = opts.maxLogItems || 400;
    const maxAttempts = opts.maxAttempts || 8;
    const hello = opts.hello || (() => null);
    const onCommand = opts.onCommand || noop;

    let port = null;
    let confirmed = false;
    let attempts = 0;
    let timer = null;
    let page = null;
    let log = [];
    let logItems = 0;

    function post(target, message) {
      try {
        target.postMessage(message);
        return true;
      } catch (ignored) {
        return false;
      }
    }

    function replay(target) {
      const greeting = hello();
      if (greeting) post(target, greeting);
      if (page) post(target, page);
      for (const message of C.mergeMessages(log, 50)) post(target, message);
    }

    function scheduleReconnect() {
      if (timer !== null || attempts >= maxAttempts) return;
      const delay = Math.min(500 * Math.pow(2, attempts), 15000);
      attempts += 1;
      timer = setTimer(() => {
        timer = null;
        connect();
      }, delay);
    }

    function onPortMessage(source, message) {
      if (source !== port) return;
      attempts = 0;
      const confirming = !confirmed;
      if (confirming) {
        confirmed = true;
        replay(source);
      }
      if (message && typeof message === 'object' && typeof message.type === 'string') {
        try {
          onCommand(message, confirming);
        } catch (ignored) {
          // Commands are best effort.
        }
      }
    }

    function onPortDisconnect(source) {
      if (source !== port) return;
      port = null;
      confirmed = false;
      scheduleReconnect();
    }

    function connect() {
      if (port) return;
      let next = null;
      try {
        next = runtime.connectNative(appName);
      } catch (ignored) {
        next = null;
      }
      if (!next) {
        scheduleReconnect();
        return;
      }
      port = next;
      confirmed = false;
      try {
        next.onMessage.addListener((message) => onPortMessage(next, message));
        next.onDisconnect.addListener(() => onPortDisconnect(next));
      } catch (ignored) {
        // A port without events still delivers what we post.
      }
      replay(next);
    }

    function remember(message) {
      if (message.type === 'hello') return;
      if (message.type === 'page') {
        page = message;
        return;
      }
      // Frame thumbnails are sent once; replays (on every rescan) would only resend the same pictures.
      log.push(withoutThumbnails(message));
      logItems += countItems(message);
      while (logItems > maxLogItems && log.length > 1) logItems -= countItems(log.shift());
    }

    return {
      start: connect,
      send(message) {
        if (!message || typeof message.type !== 'string') return;
        remember(message);
        if (port) post(port, message);
      },
      /** Posts hello, page and every logged message again on the current port. */
      replay() {
        if (port) replay(port);
      },
      /** Connects again now (page restored from the back/forward cache, tab shown again). */
      reconnect() {
        attempts = 0;
        if (port) return;
        if (timer !== null) {
          clearTimer(timer);
          timer = null;
        }
        connect();
      },
      get connected() {
        return port !== null;
      },
      get confirmed() {
        return confirmed;
      },
    };
  }

  function isTopWindow(win) {
    try {
      return win.top === win;
    } catch (ignored) {
      return false;
    }
  }

  function nodeName(node) {
    return node && typeof node.nodeName === 'string' ? node.nodeName.toUpperCase() : '';
  }

  /**
   * Starts detection in one frame. [env]: window, document, browser, MutationObserver; setTimeout,
   * clearTimeout and now are optional (tests).
   */
  function startContent(env) {
    const win = env.window;
    const doc = env.document;
    const browser = env.browser;
    const setTimer = env.setTimeout || ((fn, ms) => win.setTimeout(fn, ms));
    const clearTimer = env.clearTimeout || ((id) => win.clearTimeout(id));
    const now = env.now || (() => Date.now());
    const isTop = isTopWindow(win);
    const sent = new Map();
    let scanTimer = null;
    let firstDirtyAt = 0;
    let drmReported = false;
    let lastPage = null;
    let lastRescanAt = 0;
    let link = null;
    /**
     * Per element: the source last drawn, its picture (null when it can never be read) and the delivery round
     * it was last sent in. A source is drawn once; its picture is sent again after a rescan (the app may have
     * rebuilt its list without it), and an element that plays another source is drawn again.
     */
    const captures = typeof WeakMap === 'function' ? new WeakMap() : new Map();
    let deliveryRound = 0;
    /** Policy from the document's Referrer-Policy response header (asked of the background page). */
    let headerPolicy = null;

    function location() {
      try {
        return String(win.location.href);
      } catch (ignored) {
        return '';
      }
    }

    function userAgent() {
      try {
        return text(win.navigator.userAgent);
      } catch (ignored) {
        return undefined;
      }
    }

    function frameContext() {
      const url = location();
      const title = text(doc.title);
      return {
        isTop,
        frameUrl: url,
        pageUrl: isTop ? url : undefined,
        pageTitle: isTop ? title : undefined,
        frameTitle: title,
        referrerPolicy: referrerPolicyOf(doc, headerPolicy),
        requestHeaders: C.compact({ 'User-Agent': userAgent() }),
        now,
      };
    }

    function sendToBackground(message) {
      try {
        return Promise.resolve(browser.runtime.sendMessage(message)).catch(noop);
      } catch (ignored) {
        return Promise.resolve(undefined);
      }
    }

    function emit(message) {
      if (isTop) {
        link.send(message);
      } else {
        sendToBackground({ kind: M.REPORT, message });
      }
    }

    function isNew(report) {
      const signature = reportSignature(report);
      if (sent.get(report.url) === signature) return false;
      if (!sent.has(report.url) && sent.size >= MAX_REPORTED_URLS) return false;
      sent.set(report.url, signature);
      return true;
    }

    function checkPage() {
      const page = pageInfo(doc, location());
      const key = JSON.stringify(page);
      if (key === lastPage) return;
      lastPage = key;
      link.send(page);
      sendToBackground({ kind: M.TOP, url: page.url, title: page.title });
    }

    function scan() {
      scanTimer = null;
      firstDirtyAt = 0;
      try {
        const context = frameContext();
        const videos = doc.getElementsByTagName('video');
        const items = [];
        for (let i = 0; i < videos.length; i++) {
          for (const report of elementReports(videos[i], context)) {
            if (isNew(report)) items.push(report);
          }
        }
        if (items.length > 0) emit({ type: 'media', items });
        if (isTop) checkPage();
      } catch (ignored) {
        // Next mutation or media event scans again.
      }
    }

    /** Debounced scan: waits for a quiet period, but never longer than SCAN_MAX_WAIT_MS overall. */
    function scheduleScan(delay) {
      const wait = typeof delay === 'number' ? delay : SCAN_DEBOUNCE_MS;
      const time = now();
      if (!firstDirtyAt) firstDirtyAt = time;
      if (scanTimer !== null) {
        if (time - firstDirtyAt >= SCAN_MAX_WAIT_MS) return;
        clearTimer(scanTimer);
      }
      scanTimer = setTimer(scan, wait);
    }

    /**
     * One small JPEG of the current frame of [element]; undefined when it has nothing to show yet (no data, a
     * black frame) or is DRM-protected, so it is tried again later; null when its pixels may never be read
     * (cross-origin video without CORS).
     */
    function frameOf(element) {
      if (!(element.readyState >= 2) || !(element.videoWidth > 0) || !(element.videoHeight > 0) || element.mediaKeys) return undefined;
      try {
        const width = THUMBNAIL_WIDTH;
        const height = Math.max(1, Math.round((width * element.videoHeight) / element.videoWidth));
        const canvas = doc.createElement('canvas');
        canvas.width = width;
        canvas.height = height;
        const context = canvas.getContext('2d');
        context.drawImage(element, 0, 0, width, height);
        if (isDark(context.getImageData(0, 0, width, height).data)) return undefined;
        return canvas.toDataURL('image/jpeg', THUMBNAIL_QUALITY);
      } catch (ignored) {
        // SecurityError (tainted canvas): this source can never be captured.
        return null;
      }
    }

    /** Sends a picture of every loaded <video> of this frame not sent since the last rescan (drawing each source once). */
    function captureThumbnails() {
      try {
        const context = frameContext();
        const videos = doc.getElementsByTagName('video');
        const items = [];
        for (let i = 0; i < videos.length; i++) {
          const element = videos[i];
          const src = text(element.currentSrc) || text(element.src) || '';
          let entry = captures.get(element);
          if (entry && entry.src !== src) entry = undefined;
          if (entry && entry.round === deliveryRound) continue;
          const thumbnail = entry ? entry.thumbnail : frameOf(element);
          if (thumbnail === undefined) continue;
          captures.set(element, { src, thumbnail, round: deliveryRound });
          if (thumbnail) items.push(...elementReports(element, Object.assign({}, context, { thumbnail })));
        }
        if (items.length > 0) emit({ type: 'media', items });
      } catch (ignored) {
        // Thumbnails are best effort.
      }
    }

    /** Forgets what was reported so the next scan reports every element again. */
    function rescan(fromBroadcast) {
      const time = now();
      if (fromBroadcast && isTop && time - lastRescanAt < BROADCAST_ECHO_MS) return;
      lastRescanAt = time;
      sent.clear();
      lastPage = null;
      deliveryRound += 1;
      scheduleScan(50);
    }

    /**
     * A rescan rescans every frame and replays what was sent so far (the app may have reset the list
     * for a navigation that did not replace this document, such as a download link).
     */
    function onCommand(command, confirming) {
      if (command.type === 'thumbnails') {
        captureThumbnails();
        sendToBackground({ kind: M.THUMBNAILS_ALL });
        return;
      }
      if (command.type !== 'rescan') return;
      if (!confirming) link.replay();
      rescan(false);
      sendToBackground({ kind: M.RESCAN_ALL });
    }

    /**
     * Tells the background page this document's top frame is ready and relays what it buffered. The
     * background always answers; no answer means it was not listening yet, so we ask again.
     */
    function sayHello(attempt) {
      const tries = attempt || 0;
      sendToBackground({ kind: M.HELLO, url: location(), title: text(doc.title) }).then((reply) => {
        if (reply && Array.isArray(reply.messages)) {
          for (const message of reply.messages) link.send(message);
        } else if (tries < HELLO_RETRIES) {
          setTimer(() => sayHello(tries + 1), 1000 * (tries + 1));
        }
      }, noop);
    }

    function isMediaNode(node) {
      const name = nodeName(node);
      return name === 'VIDEO' || name === 'SOURCE' || name === 'TRACK';
    }

    function containsVideo(node) {
      try {
        return typeof node.getElementsByTagName === 'function' && node.getElementsByTagName('video').length > 0;
      } catch (ignored) {
        return false;
      }
    }

    function onMutations(records) {
      try {
        for (const record of records) {
          if (record.type === 'attributes') {
            if (isMediaNode(record.target)) {
              scheduleScan();
              return;
            }
            continue;
          }
          if (isTop && nodeName(record.target) === 'TITLE') {
            scheduleScan();
            return;
          }
          const added = record.addedNodes || [];
          for (let i = 0; i < added.length; i++) {
            const node = added[i];
            if (!node || node.nodeType !== 1) continue;
            const name = nodeName(node);
            if (isMediaNode(node) || (isTop && (name === 'TITLE' || name === 'META')) || containsVideo(node)) {
              scheduleScan();
              return;
            }
          }
        }
      } catch (ignored) {
        scheduleScan();
      }
    }

    function onMediaEvent(event) {
      if (nodeName(event && event.target) === 'VIDEO') scheduleScan();
    }

    function onEncrypted() {
      if (drmReported) return;
      drmReported = true;
      emit({ type: 'drm', frameUrl: location() });
    }

    function onRuntimeMessage(message) {
      try {
        if (!message || typeof message.kind !== 'string') return undefined;
        if (message.kind === M.DELIVER && isTop) {
          if (message.message) link.send(message.message);
          return Promise.resolve(true);
        }
        // A (re)started background page knows no tab: the top frame says hello again to flush its buffer.
        if (message.kind === M.HELLO_REQUEST) {
          if (isTop) sayHello();
          return undefined;
        }
        if (message.kind === M.RESCAN) rescan(true);
        if (message.kind === M.THUMBNAILS) captureThumbnails();
      } catch (ignored) {
        // Ignore malformed messages.
      }
      return undefined;
    }

    function listen(target, type, listener, capture) {
      try {
        target.addEventListener(type, listener, capture);
      } catch (ignored) {
        // Optional signal.
      }
    }

    // The document's Referrer-Policy header is only visible to the background page; until it answers (before
    // the first scan, in practice) the default policy applies.
    sendToBackground({ kind: M.POLICY }).then((reply) => {
      const policy = reply && typeof reply.referrerPolicy === 'string' ? reply.referrerPolicy : null;
      if (!policy || !REFERRER_POLICIES.has(policy) || policy === headerPolicy) return;
      headerPolicy = policy;
      sent.clear();
      scheduleScan(50);
    }, noop);

    if (isTop) {
      link = createNativeLink(browser.runtime, {
        appName: NATIVE_APP,
        hello: () => C.compact({ type: 'hello', url: location(), title: text(doc.title) }),
        onCommand,
        setTimeout: setTimer,
        clearTimeout: clearTimer,
      });
      link.start();
      sayHello();
    }

    try {
      browser.runtime.onMessage.addListener(onRuntimeMessage);
    } catch (ignored) {
      // Without it this frame only reports what it finds itself.
    }

    try {
      const Observer = env.MutationObserver || win.MutationObserver;
      new Observer(onMutations).observe(doc, {
        childList: true,
        subtree: true,
        attributes: true,
        attributeFilter: ['src', 'poster', 'data-poster'],
      });
    } catch (ignored) {
      // Media events and load events still trigger scans.
    }

    for (const type of MEDIA_EVENTS) listen(doc, type, onMediaEvent, true);
    listen(doc, 'encrypted', onEncrypted, true);
    listen(doc, 'DOMContentLoaded', () => scheduleScan(50), false);
    listen(win, 'load', () => scheduleScan(50), false);
    listen(win, 'popstate', () => scheduleScan(), false);
    listen(win, 'hashchange', () => scheduleScan(), false);
    listen(win, 'pageshow', (event) => {
      if (!event || !event.persisted) return;
      if (isTop) {
        link.reconnect();
        sayHello();
      }
      rescan(false);
    }, false);
    listen(doc, 'visibilitychange', () => {
      if (isTop && !link.connected && doc.visibilityState === 'visible') link.reconnect();
    }, false);

    scheduleScan(100);

    return { scan, rescan, get link() { return link; }, isTop };
  }

  const api = {
    createNativeLink,
    elementReports,
    trackReports,
    sourceCandidates,
    reportSignature,
    referrerFor,
    referrerPolicyOf,
    posterOf,
    isDark,
    pageInfo,
    startContent,
    isBlobUrl,
  };

  if (typeof module !== 'undefined' && module.exports) {
    module.exports = api;
  } else if (!root.__snapcastDetectorStarted && typeof browser !== 'undefined' && typeof document !== 'undefined') {
    root.__snapcastDetectorStarted = true;
    try {
      startContent({
        window,
        document,
        browser,
        MutationObserver: typeof MutationObserver !== 'undefined' ? MutationObserver : undefined,
      });
    } catch (ignored) {
      // Never break the page.
    }
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
