/*
 * Shared, side-effect free helpers of the Snapcast detector, loaded by the background page and by
 * every content script (and by the Node tests).
 *
 * The classification mirrors app.snapcast.core.media.MediaClassifier closely enough to pre-filter
 * traffic in the browser; the app classifies every report again and stays the source of truth.
 */
(function (root) {
  'use strict';

  /** Progressive files at least this big are listed even when they look like segments. */
  const LARGE_FILE_BYTES = 50 * 1024 * 1024;
  /** MP4/WebM fetched by XHR/fetch are MSE chunks unless at least this big. */
  const MSE_FULL_FILE_BYTES = 20 * 1024 * 1024;

  const KIND = Object.freeze({
    MEDIA: 'media',
    PLAYLIST: 'playlist',
    SEGMENT: 'segment',
    SUBTITLE: 'subtitle',
    NONE: 'none',
  });

  /** Message kinds exchanged between content scripts and the background page. */
  const MESSAGE = Object.freeze({
    HELLO: 'snapcast-hello',
    TOP: 'snapcast-top',
    REPORT: 'snapcast-report',
    DELIVER: 'snapcast-deliver',
    RESCAN: 'snapcast-rescan',
    RESCAN_ALL: 'snapcast-rescan-all',
    THUMBNAILS: 'snapcast-thumbnails',
    THUMBNAILS_ALL: 'snapcast-thumbnails-all',
    HELLO_REQUEST: 'snapcast-hello-request',
  });

  const HLS_MIMES = new Set([
    'application/vnd.apple.mpegurl',
    'application/x-mpegurl',
    'audio/mpegurl',
    'audio/x-mpegurl',
    'application/mpegurl',
  ]);
  const DASH_MIME = 'application/dash+xml';
  const GENERIC_MIMES = new Set([
    'application/octet-stream',
    'binary/octet-stream',
    'text/plain',
    'application/xml',
    'text/xml',
    'application/binary',
    'application/unknown',
    'application/x-unknown',
    'application/download',
    'application/x-download',
    'application/force-download',
    'application/mp4',
    'content/unknown',
    'unknown/unknown',
    '*/*',
  ]);
  const SEGMENT_MIMES = new Set(['video/iso.segment', 'audio/iso.segment']);
  const SEGMENT_EXTENSIONS = new Set(['m4s', 'm4f', 'cmfv', 'cmfa', 'cmft', 'aac', 'fmp4']);
  const INIT_SEGMENT_EXTENSIONS = new Set(['mp4', 'm4v', 'm4a', 'm4s', 'm4f', 'cmfv', 'cmfa', 'fmp4', 'webm', 'ts']);
  const SEGMENT_NAME = /(?:^|[^a-z])(?:segment|seg|chunk|fragment|frag)[-_]?\d+|filesequence\d+/;
  const NUMERIC_SEGMENT_NAME = /^\d+\.(?:ts|m4s|m4f|cmfv|cmfa|aac|fmp4|m4a|vtt|webvtt)$/;
  const INIT_SEGMENT_NAME = /^(?:|.*[^a-z])init(?:[^a-z].*)?$/;
  const BYTE_RANGE_PARAMS = new Set(['range', 'bytes', 'bytestart', 'byteend']);
  const HLS_QUERY_HINT = /[?&;(,]format=m3u8/;
  const DASH_QUERY_HINT = /[?&;(,]format=mpd/;
  const SCHEME = /^[A-Za-z][A-Za-z0-9+.-]*$/;
  const EXTENSION = /^[a-z0-9]+$/;
  const VOLATILE_PARAMS = new Set(['range', 'bytestart', 'byteend', 'rbuf', 'rn', '_']);
  const NUMERIC_CACHE_BUSTERS = new Set(['t', 'ts', 'cb']);

  function isString(value) {
    return typeof value === 'string';
  }

  /** Splits a URI reference into its RFC 3986 components (never throws, never encodes). */
  function parseUri(value) {
    let rest = isString(value) ? value.trim() : '';
    let fragment = null;
    const hash = rest.indexOf('#');
    if (hash >= 0) {
      fragment = rest.substring(hash + 1);
      rest = rest.substring(0, hash);
    }
    let query = null;
    const question = rest.indexOf('?');
    if (question >= 0) {
      query = rest.substring(question + 1);
      rest = rest.substring(0, question);
    }
    let scheme = null;
    const colon = rest.indexOf(':');
    if (colon > 0 && SCHEME.test(rest.substring(0, colon))) {
      scheme = rest.substring(0, colon);
      rest = rest.substring(colon + 1);
    }
    let authority = null;
    if (rest.startsWith('//')) {
      let end = rest.indexOf('/', 2);
      if (end < 0) end = rest.length;
      authority = rest.substring(2, end);
      rest = rest.substring(end);
    }
    return { scheme, authority, path: rest, query, fragment };
  }

  function isOpaque(ref) {
    return ref.scheme !== null && ref.authority === null && !ref.path.startsWith('/');
  }

  function lastSegment(ref) {
    const name = ref.path.substring(ref.path.lastIndexOf('/') + 1);
    const semicolon = name.indexOf(';');
    return semicolon >= 0 ? name.substring(0, semicolon) : name;
  }

  function hostPortOf(authority) {
    if (authority === null) return null;
    return authority.substring(authority.lastIndexOf('@') + 1);
  }

  function splitHostPort(hostPort) {
    if (hostPort.startsWith('[')) {
      const end = hostPort.indexOf(']');
      if (end < 0) return { host: hostPort, port: null };
      const after = hostPort.substring(end + 1);
      return { host: hostPort.substring(0, end + 1), port: after.startsWith(':') ? after.substring(1) : null };
    }
    const colon = hostPort.indexOf(':');
    if (colon < 0) return { host: hostPort, port: null };
    return { host: hostPort.substring(0, colon), port: hostPort.substring(colon + 1) };
  }

  function isHttpUrl(url) {
    return isString(url) && /^https?:\/\//i.test(url);
  }

  /** Lower-cased host of an absolute http(s) URL, or null. */
  function hostOf(url) {
    const ref = parseUri(url);
    if (!ref.scheme || !/^https?$/i.test(ref.scheme)) return null;
    const hostPort = hostPortOf(ref.authority);
    if (hostPort === null) return null;
    const host = splitHostPort(hostPort).host.toLowerCase();
    return host || null;
  }

  /** Path extension without dot, lower-cased, ignoring query and fragment; null when none. */
  function extensionOf(url) {
    const ref = parseUri(url);
    if (isOpaque(ref)) return null;
    const name = lastSegment(ref);
    const dot = name.lastIndexOf('.');
    if (dot < 0 || dot === name.length - 1) return null;
    const ext = name.substring(dot + 1).toLowerCase();
    return EXTENSION.test(ext) ? ext : null;
  }

  /** Lower-cased MIME type without parameters, or null when blank. */
  function baseMime(contentType) {
    if (!isString(contentType)) return null;
    const mime = contentType.split(';')[0].trim().toLowerCase();
    return mime || null;
  }

  function typeFromMime(mime) {
    if (HLS_MIMES.has(mime)) return 'HLS';
    switch (mime) {
      case DASH_MIME: return 'DASH';
      case 'video/mp4':
      case 'video/x-m4v': return 'MP4';
      case 'video/webm': return 'WEBM';
      case 'video/x-matroska':
      case 'video/matroska': return 'MKV';
      case 'video/quicktime': return 'OTHER';
      case 'video/mp2t': return 'TS';
      default: return null;
    }
  }

  function subtitleFormatFromMime(mime) {
    switch (mime) {
      case 'text/vtt': return 'VTT';
      case 'application/x-subrip':
      case 'text/srt':
      case 'application/srt':
      case 'text/x-srt': return 'SRT';
      case 'application/ttml+xml': return 'TTML';
      default: return null;
    }
  }

  /** Stream type ("HLS", "DASH", "MP4", ...) implied by the URL extension or query hints, or null. */
  function typeFromUrl(url) {
    switch (extensionOf(url)) {
      case 'm3u8': return 'HLS';
      case 'mpd': return 'DASH';
      case 'mp4':
      case 'm4v': return 'MP4';
      case 'webm': return 'WEBM';
      case 'mkv': return 'MKV';
      case 'mov': return 'OTHER';
      case 'ts': return 'TS';
      default: break;
    }
    if (!isString(url)) return null;
    const lower = url.split('#')[0].toLowerCase();
    if (HLS_QUERY_HINT.test(lower)) return 'HLS';
    if (DASH_QUERY_HINT.test(lower)) return 'DASH';
    return null;
  }

  function typeFromContentType(contentType) {
    const mime = baseMime(contentType);
    return mime ? typeFromMime(mime) : null;
  }

  function subtitleFormatFromUrl(url) {
    switch (extensionOf(url)) {
      case 'vtt':
      case 'webvtt': return 'VTT';
      case 'srt': return 'SRT';
      case 'ttml':
      case 'dfxp': return 'TTML';
      default: return null;
    }
  }

  function subtitleFormatFromContentType(contentType) {
    const mime = baseMime(contentType);
    return mime ? subtitleFormatFromMime(mime) : null;
  }

  function hasByteRangeParam(query) {
    if (!query) return false;
    return query.split('&').some((param) => {
      const eq = param.indexOf('=');
      return eq >= 0 && BYTE_RANGE_PARAMS.has(param.substring(0, eq).toLowerCase());
    });
  }

  /** True when the URL looks like a chunk of an adaptive stream rather than a standalone file. */
  function isSegmentUrl(url) {
    const ref = parseUri(url);
    if (isOpaque(ref)) return false;
    const path = ref.path.toLowerCase();
    const name = lastSegment(ref).toLowerCase();
    const ext = extensionOf(url);
    const dot = name.lastIndexOf('.');
    const stem = dot >= 0 ? name.substring(0, dot) : name;
    return ext === 'ts' ||
      SEGMENT_EXTENSIONS.has(ext) ||
      SEGMENT_NAME.test(path) ||
      NUMERIC_SEGMENT_NAME.test(name) ||
      (INIT_SEGMENT_NAME.test(stem) && (ext === null || INIT_SEGMENT_EXTENSIONS.has(ext))) ||
      (path.endsWith('/videoplayback') && ref.query !== null) ||
      hasByteRangeParam(ref.query);
  }

  const NONE = Object.freeze({ kind: KIND.NONE });
  const SEGMENT = Object.freeze({ kind: KIND.SEGMENT });

  function stream(type) {
    return { kind: type === 'HLS' || type === 'DASH' ? KIND.PLAYLIST : KIND.MEDIA, type };
  }

  function knownSize(value) {
    return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
  }

  function streamOrSegment(type, url, resourceType, contentLength) {
    const size = knownSize(contentLength);
    const large = size !== null && size >= LARGE_FILE_BYTES;
    if (type === 'HLS' || type === 'DASH') return stream(type);
    if (type === 'TS') return large ? stream(type) : SEGMENT;
    const mseChunk = (type === 'MP4' || type === 'WEBM') &&
      isString(resourceType) && resourceType.toLowerCase() === 'xmlhttprequest' &&
      (size === null || size < MSE_FULL_FILE_BYTES);
    if (!large && isSegmentUrl(url)) return SEGMENT;
    if (mseChunk) return SEGMENT;
    return stream(type);
  }

  function classifyByUrl(url, resourceType, contentLength) {
    const subtitle = subtitleFormatFromUrl(url);
    if (subtitle) return { kind: KIND.SUBTITLE, format: subtitle };
    if (SEGMENT_EXTENSIONS.has(extensionOf(url))) return SEGMENT;
    const type = typeFromUrl(url);
    if (type) return streamOrSegment(type, url, resourceType, contentLength);
    if (isSegmentUrl(url)) return SEGMENT;
    return NONE;
  }

  /**
   * Classifies a resource. Returns {kind} where kind is one of KIND; "media" and "playlist" also carry
   * {type} ("MP4", "HLS", ...), "subtitle" carries {format} ("VTT", "SRT", "TTML").
   */
  function classify(url, contentType, resourceType, contentLength) {
    const mime = baseMime(contentType);
    if (mime === null || GENERIC_MIMES.has(mime)) return classifyByUrl(url, resourceType, contentLength);
    const type = typeFromMime(mime);
    if (type) return streamOrSegment(type, url, resourceType, contentLength);
    const subtitle = subtitleFormatFromMime(mime);
    if (subtitle) return { kind: KIND.SUBTITLE, format: subtitle };
    if (SEGMENT_MIMES.has(mime)) return SEGMENT;
    if (mime.startsWith('video/')) {
      const byUrl = classifyByUrl(url, resourceType, contentLength);
      if (byUrl.kind === KIND.MEDIA || byUrl.kind === KIND.PLAYLIST || byUrl.kind === KIND.SEGMENT) return byUrl;
      return streamOrSegment('OTHER', url, resourceType, contentLength);
    }
    return NONE;
  }

  /** True for classification results worth reporting to the app. */
  function isReportable(result) {
    return !!result && (result.kind === KIND.MEDIA || result.kind === KIND.PLAYLIST || result.kind === KIND.SUBTITLE);
  }

  /** Case-insensitive header lookup in a webRequest header array ([{name, value}]) or a plain object. */
  function getHeader(headers, name) {
    if (!headers || !isString(name)) return null;
    const wanted = name.toLowerCase();
    if (Array.isArray(headers)) {
      for (const header of headers) {
        if (header && isString(header.name) && header.name.toLowerCase() === wanted) {
          return isString(header.value) ? header.value : null;
        }
      }
      return null;
    }
    if (typeof headers === 'object') {
      for (const key of Object.keys(headers)) {
        if (key.toLowerCase() === wanted) return isString(headers[key]) ? headers[key] : null;
      }
    }
    return null;
  }

  /** Copies the non-blank headers listed in [names] (canonical spelling kept) into a plain object. */
  function pickHeaders(headers, names) {
    const result = {};
    for (const name of names) {
      const value = getHeader(headers, name);
      if (isString(value) && value.trim()) result[name] = value.trim();
    }
    return result;
  }

  function parseSize(text) {
    if (!isString(text) || !/^\s*\d+\s*$/.test(text)) return null;
    const value = Number(text.trim());
    return Number.isSafeInteger(value) ? value : null;
  }

  /**
   * Parses a Content-Range value such as "bytes 0-99/1234" into {start, end, total}; the range and the
   * total may each be "*" (unknown, null). Returns null for anything else.
   */
  function parseContentRange(value) {
    if (!isString(value)) return null;
    const match = /^\s*bytes\s+(?:(\d+)\s*-\s*(\d+)|\*)\s*\/\s*(\d+|\*)\s*$/i.exec(value);
    if (!match) return null;
    return {
      start: match[1] !== undefined ? parseSize(match[1]) : null,
      end: match[2] !== undefined ? parseSize(match[2]) : null,
      total: match[3] === '*' ? null : parseSize(match[3]),
    };
  }

  /**
   * Total size of the resource in bytes: the Content-Range total, else Content-Length of a complete
   * (non-206), unencoded response. Null when unknown.
   */
  function sizeFromHeaders(headers, statusCode) {
    const range = parseContentRange(getHeader(headers, 'content-range'));
    if (range && range.total !== null) return range.total;
    if (statusCode === 206) return null;
    const encoding = getHeader(headers, 'content-encoding');
    if (encoding && encoding.trim().toLowerCase() !== 'identity') return null;
    return parseSize(getHeader(headers, 'content-length'));
  }

  function filterQuery(query) {
    const kept = query.split('&').filter((param) => {
      if (!param) return false;
      const eq = param.indexOf('=');
      const name = (eq >= 0 ? param.substring(0, eq) : param).toLowerCase();
      const value = eq >= 0 ? param.substring(eq + 1) : '';
      if (VOLATILE_PARAMS.has(name)) return false;
      // LL-HLS delivery directives (_HLS_msn, _HLS_part, _HLS_skip, ...) never select another resource.
      if (name.startsWith('_hls_')) return false;
      if (NUMERIC_CACHE_BUSTERS.has(name)) return !/^\d+$/.test(value);
      return true;
    });
    return kept.length ? kept.join('&') : null;
  }

  /** Same canonical form as UrlUtils.normalizeForDedupe: used to throttle repeated reports. */
  function dedupeKey(url) {
    const ref = parseUri(url);
    const scheme = ref.scheme ? ref.scheme.toLowerCase() : null;
    let authority = null;
    if (ref.authority !== null) {
      const at = ref.authority.lastIndexOf('@');
      const userInfo = at >= 0 ? ref.authority.substring(0, at + 1) : '';
      const hp = splitHostPort(ref.authority.substring(at + 1));
      const dropPort = hp.port === null || hp.port === '' ||
        (scheme === 'http' && hp.port === '80') || (scheme === 'https' && hp.port === '443');
      authority = userInfo + hp.host.toLowerCase() + (dropPort ? '' : ':' + hp.port);
    }
    const isHttp = scheme === 'http' || scheme === 'https';
    const path = isHttp && authority !== null && ref.path === '' ? '/' : ref.path;
    const query = ref.query !== null ? filterQuery(ref.query) : null;
    return (scheme !== null ? scheme + ':' : '') +
      (authority !== null ? '//' + authority : '') +
      path +
      (query !== null ? '?' + query : '');
  }

  /** URL without its fragment. */
  function stripFragment(url) {
    if (!isString(url)) return url;
    const hash = url.indexOf('#');
    return hash >= 0 ? url.substring(0, hash) : url;
  }

  /** Copy of [object] without null/undefined values and non-finite numbers (they do not survive JSON). */
  function compact(object) {
    const result = {};
    for (const key of Object.keys(object)) {
      const value = object[key];
      if (value === null || value === undefined) continue;
      if (typeof value === 'number' && !Number.isFinite(value)) continue;
      result[key] = value;
    }
    return result;
  }

  /**
   * Re-packs a message list for delivery: consecutive "media" messages are merged into messages of at
   * most [maxItems] items; other messages keep their position.
   */
  function mergeMessages(messages, maxItems) {
    const limit = maxItems > 0 ? maxItems : 50;
    const result = [];
    let current = null;
    for (const message of messages) {
      if (!message || !isString(message.type)) continue;
      if (message.type === 'media' && Array.isArray(message.items)) {
        for (const item of message.items) {
          if (!current || current.items.length >= limit) {
            current = { type: 'media', items: [] };
            result.push(current);
          }
          current.items.push(item);
        }
      } else {
        current = null;
        result.push(message);
      }
    }
    return result;
  }

  const api = Object.freeze({
    LARGE_FILE_BYTES,
    MSE_FULL_FILE_BYTES,
    KIND,
    MESSAGE,
    parseUri,
    isHttpUrl,
    hostOf,
    extensionOf,
    baseMime,
    typeFromUrl,
    typeFromContentType,
    subtitleFormatFromUrl,
    subtitleFormatFromContentType,
    isSegmentUrl,
    classify,
    isReportable,
    getHeader,
    pickHeaders,
    parseContentRange,
    sizeFromHeaders,
    dedupeKey,
    stripFragment,
    compact,
    mergeMessages,
  });

  root.SnapcastClassify = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
