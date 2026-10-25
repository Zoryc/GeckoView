'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const C = require('../../main/assets/extensions/snapcast_detector/classify.js');

const LARGE = C.LARGE_FILE_BYTES;
const media = (type) => ({ kind: 'media', type });
const playlist = (type) => ({ kind: 'playlist', type });
const subtitle = (format) => ({ kind: 'subtitle', format });
const SEGMENT = { kind: 'segment' };
const NONE = { kind: 'none' };

function classify(...args) {
  return Object.assign({}, C.classify(...args));
}

test('exposes the helpers on globalThis for the extension scripts', () => {
  assert.equal(globalThis.SnapcastClassify, C);
});

test('classifies by URL extension when there is no content type', () => {
  assert.deepEqual(classify('https://e.com/live/master.m3u8'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/dash/manifest.mpd'), playlist('DASH'));
  assert.deepEqual(classify('https://e.com/movie.mp4'), media('MP4'));
  assert.deepEqual(classify('https://e.com/movie.m4v'), media('MP4'));
  assert.deepEqual(classify('https://e.com/clip.webm'), media('WEBM'));
  assert.deepEqual(classify('https://e.com/film.mkv'), media('MKV'));
  assert.deepEqual(classify('https://e.com/film.mov'), media('OTHER'));
  assert.deepEqual(classify('https://e.com/page.html'), NONE);
  assert.deepEqual(classify('https://e.com/'), NONE);
});

test('extension match is case-insensitive and ignores query and fragment', () => {
  assert.deepEqual(classify('https://e.com/Master.M3U8?token=a.mp4'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/MOVIE.MP4#t=10'), media('MP4'));
  assert.deepEqual(classify('https://e.com/video?file=movie.mp4'), NONE);
  assert.deepEqual(classify('https://e.com/v.mp4;jsessionid=1'), media('MP4'));
});

test('recognises query hints for manifests', () => {
  assert.deepEqual(classify('https://e.com/play?id=5&format=m3u8'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/v.ism/manifest(format=m3u8-aapl)'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/v.ism/manifest(format=mpd-time-csf)'), playlist('DASH'));
  assert.deepEqual(classify('https://e.com/get?format=mpd'), playlist('DASH'));
  assert.equal(C.typeFromUrl('https://e.com/get?FORMAT=M3U8'), 'HLS');
  assert.equal(C.typeFromUrl('https://e.com/get#format=m3u8'), null);
});

test('a specific content type wins over the URL', () => {
  assert.deepEqual(classify('https://e.com/playlist.php', 'application/vnd.apple.mpegurl'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/stream', 'application/x-mpegURL; charset=UTF-8'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/a', 'audio/mpegurl'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/a', 'audio/x-mpegurl'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/a', 'application/mpegurl'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/m', 'application/dash+xml'), playlist('DASH'));
  assert.deepEqual(classify('https://e.com/movie.mp4', 'application/vnd.apple.mpegurl'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/file', 'video/mp4'), media('MP4'));
  assert.deepEqual(classify('https://e.com/file', 'video/x-m4v'), media('MP4'));
  assert.deepEqual(classify('https://e.com/file', 'VIDEO/WEBM'), media('WEBM'));
  assert.deepEqual(classify('https://e.com/file', 'video/x-matroska'), media('MKV'));
  assert.deepEqual(classify('https://e.com/file', 'video/matroska'), media('MKV'));
  assert.deepEqual(classify('https://e.com/file', 'video/quicktime'), media('OTHER'));
  assert.deepEqual(classify('https://e.com/file.m3u8', 'text/html'), NONE);
});

test('generic or misconfigured content types fall back to the URL', () => {
  assert.deepEqual(classify('https://e.com/master.m3u8', 'application/octet-stream'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/master.m3u8', 'binary/octet-stream'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/master.m3u8', 'text/plain'), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/manifest.mpd', 'application/xml'), playlist('DASH'));
  assert.deepEqual(classify('https://e.com/manifest.mpd', 'application/force-download'), playlist('DASH'));
  assert.deepEqual(classify('https://e.com/movie.mp4', ''), media('MP4'));
  assert.deepEqual(classify('https://e.com/movie.mp4', '*/*'), media('MP4'));
  assert.deepEqual(classify('https://e.com/subs.vtt', 'text/plain'), subtitle('VTT'));
  assert.deepEqual(classify('https://e.com/blob', 'application/octet-stream'), NONE);
});

test('MPEG-TS is a segment unless large', () => {
  assert.deepEqual(classify('https://e.com/x', 'video/mp2t'), SEGMENT);
  assert.deepEqual(classify('https://e.com/x', 'video/MP2T', null, LARGE), media('TS'));
  assert.deepEqual(classify('https://e.com/seg-1.ts'), SEGMENT);
  assert.deepEqual(classify('https://e.com/movie.TS', null, null, 2 * LARGE), media('TS'));
  assert.deepEqual(classify('https://e.com/movie.ts', null, null, LARGE - 1), SEGMENT);
});

test('adaptive chunk extensions and init segments are segments', () => {
  for (const path of ['v/chunk.m4s', 'a.m4f', 'x.cmfv', 'x.cmfa', 'x.cmft', 'audio/0001.aac', 'seg.fmp4', 'SEG.M4S']) {
    assert.deepEqual(classify(`https://e.com/${path}`), SEGMENT, path);
  }
  assert.deepEqual(classify('https://e.com/dash/init.mp4'), SEGMENT);
  assert.deepEqual(classify('https://e.com/dash/video_init_1080.m4s'), SEGMENT);
  assert.deepEqual(classify('https://e.com/x', 'video/iso.segment'), SEGMENT);
  assert.deepEqual(classify('https://e.com/x', 'audio/iso.segment'), SEGMENT);
});

test('segment-looking progressive URLs are segments unless large', () => {
  assert.deepEqual(classify('https://e.com/hls/segment12.mp4'), SEGMENT);
  assert.deepEqual(classify('https://e.com/v/frag-3.mp4', 'video/mp4'), SEGMENT);
  assert.deepEqual(classify('https://e.com/movie.mp4?range=0-50000'), SEGMENT);
  assert.deepEqual(classify('https://e.com/movie.mp4?bytes=100-200', 'video/mp4'), SEGMENT);
  assert.deepEqual(classify('https://e.com/hls/segment12.mp4', null, null, LARGE), media('MP4'));
});

test('MP4 and WebM fetched by XHR are MSE chunks unless at least 20 MB', () => {
  const twentyMb = 20 * 1024 * 1024;
  assert.deepEqual(classify('https://e.com/v.mp4', 'video/mp4', 'xmlhttprequest', 1000000), SEGMENT);
  assert.deepEqual(classify('https://e.com/v.webm', null, 'xmlhttprequest', null), SEGMENT);
  assert.deepEqual(classify('https://e.com/v.mp4', 'video/mp4', 'XMLHttpRequest', 1000000), SEGMENT);
  assert.deepEqual(classify('https://e.com/v.mp4', 'video/mp4', 'xmlhttprequest', twentyMb), media('MP4'));
  assert.deepEqual(classify('https://e.com/v.mp4', 'video/mp4', 'media', 1000000), media('MP4'));
  assert.deepEqual(classify('https://e.com/v.m3u8', null, 'xmlhttprequest', 500), playlist('HLS'));
  assert.deepEqual(classify('https://e.com/v.mkv', null, 'xmlhttprequest', 500), media('MKV'));
});

test('audio, images and documents are not media', () => {
  assert.deepEqual(classify('https://e.com/song.mp3', 'audio/mpeg'), NONE);
  assert.deepEqual(classify('https://e.com/a.mp4', 'audio/mp4'), NONE);
  assert.deepEqual(classify('https://e.com/p.jpg', 'image/jpeg'), NONE);
  assert.deepEqual(classify('https://e.com/movie.mp4', 'image/png'), NONE);
  assert.deepEqual(classify('https://e.com/', 'text/html', 'main_frame'), NONE);
  assert.deepEqual(classify('https://e.com/app.js', 'application/javascript'), NONE);
});

test('unknown video subtypes are generic streams unless the URL says more', () => {
  assert.deepEqual(classify('https://e.com/stream', 'video/ogg'), media('OTHER'));
  assert.deepEqual(classify('https://e.com/movie.mp4', 'video/x-unknown'), media('MP4'));
  assert.deepEqual(classify('https://e.com/seg-4.ts', 'video/x-unknown'), SEGMENT);
  assert.deepEqual(classify('https://e.com/m.m3u8', 'video/x-unknown'), playlist('HLS'));
});

test('subtitles by content type and URL', () => {
  assert.deepEqual(classify('https://e.com/s', 'text/vtt'), subtitle('VTT'));
  assert.deepEqual(classify('https://e.com/s', 'application/x-subrip'), subtitle('SRT'));
  assert.deepEqual(classify('https://e.com/s', 'text/srt'), subtitle('SRT'));
  assert.deepEqual(classify('https://e.com/s', 'application/srt'), subtitle('SRT'));
  assert.deepEqual(classify('https://e.com/s', 'text/x-srt'), subtitle('SRT'));
  assert.deepEqual(classify('https://e.com/s', 'application/ttml+xml'), subtitle('TTML'));
  assert.deepEqual(classify('https://e.com/en.VTT'), subtitle('VTT'));
  assert.deepEqual(classify('https://e.com/en.webvtt'), subtitle('VTT'));
  assert.deepEqual(classify('https://e.com/en.srt?x=1'), subtitle('SRT'));
  assert.deepEqual(classify('https://e.com/en.ttml'), subtitle('TTML'));
  assert.deepEqual(classify('https://e.com/en.dfxp'), subtitle('TTML'));
});

test('typeFromContentType ignores parameters and case', () => {
  assert.equal(C.typeFromContentType('Application/Vnd.Apple.MpegURL; charset=utf-8'), 'HLS');
  assert.equal(C.typeFromContentType('application/dash+xml;profiles=x'), 'DASH');
  assert.equal(C.typeFromContentType('video/mp4; codecs="avc1"'), 'MP4');
  assert.equal(C.typeFromContentType('video/mp2t'), 'TS');
  assert.equal(C.typeFromContentType('application/octet-stream'), null);
  assert.equal(C.typeFromContentType(null), null);
  assert.equal(C.typeFromContentType(undefined), null);
  assert.equal(C.typeFromContentType('  '), null);
});

test('typeFromUrl maps extensions', () => {
  assert.equal(C.typeFromUrl('https://e.com/a.ts'), 'TS');
  assert.equal(C.typeFromUrl('https://e.com/a.MKV'), 'MKV');
  assert.equal(C.typeFromUrl('https://e.com/a.mov'), 'OTHER');
  assert.equal(C.typeFromUrl('https://e.com/a'), null);
  assert.equal(C.typeFromUrl(null), null);
});

test('subtitle format helpers', () => {
  assert.equal(C.subtitleFormatFromUrl('https://e.com/a.SRT'), 'SRT');
  assert.equal(C.subtitleFormatFromUrl('https://e.com/a.mp4'), null);
  assert.equal(C.subtitleFormatFromContentType('text/vtt;charset=utf-8'), 'VTT');
  assert.equal(C.subtitleFormatFromContentType('text/plain'), null);
});

test('isSegmentUrl patterns', () => {
  const segments = [
    'https://e.com/hls/segment1.ts',
    'https://e.com/hls/seg-12-v1-a1.ts',
    'https://e.com/a/chunk_00012.m4s',
    'https://e.com/a/Frag5',
    'https://e.com/a/fragment-3.mp4',
    'https://e.com/hds/Seg1-Frag12',
    'https://e.com/720/00012.ts',
    'https://e.com/subs/0001.vtt',
    'https://e.com/subs/fileSequence12.webvtt',
    'https://e.com/dash/init.mp4',
    'https://e.com/dash/init',
    'https://r3.googlevideo.com/videoplayback?expire=1&itag=22',
    'https://e.com/movie.mp4?range=0-1000',
    'https://e.com/movie.mp4?a=1&Bytes=0-1000',
    'https://e.com/movie.mp4?bytestart=0',
    'https://e.com/v.m4s',
  ];
  for (const url of segments) assert.equal(C.isSegmentUrl(url), true, url);

  const standalone = [
    'https://e.com/movie.mp4',
    'https://e.com/videos/12345.mp4',
    'https://e.com/hls/chunklist_w123.m3u8',
    'https://e.com/initial-trailer.mp4',
    'https://e.com/subs/en.vtt',
    'https://e.com/videoplayback',
    'https://e.com/master.m3u8?arrange=1',
    'https://e.com/dash/init.json',
    'blob:https://e.com/1234-5678',
  ];
  for (const url of standalone) assert.equal(C.isSegmentUrl(url), false, url);
});

test('isReportable keeps streams and subtitles only', () => {
  assert.equal(C.isReportable(C.classify('https://e.com/a.mp4')), true);
  assert.equal(C.isReportable(C.classify('https://e.com/a.m3u8')), true);
  assert.equal(C.isReportable(C.classify('https://e.com/a.vtt')), true);
  assert.equal(C.isReportable(C.classify('https://e.com/a.m4s')), false);
  assert.equal(C.isReportable(C.classify('https://e.com/a.html')), false);
  assert.equal(C.isReportable(null), false);
});

test('URI helpers', () => {
  assert.deepEqual(C.parseUri('HTTPS://user@Host:8443/a/b.mp4?x=1#frag'), {
    scheme: 'HTTPS',
    authority: 'user@Host:8443',
    path: '/a/b.mp4',
    query: 'x=1',
    fragment: 'frag',
  });
  assert.equal(C.extensionOf('https://e.com/a/B.MP4?x=y.m3u8'), 'mp4');
  assert.equal(C.extensionOf('https://e.com/a/b.'), null);
  assert.equal(C.extensionOf('https://e.com/a/b.mp-4'), null);
  assert.equal(C.extensionOf('data:video/mp4;base64,AAAA'), null);
  assert.equal(C.extensionOf('https://e.com/dir.v2/file'), null);
  assert.equal(C.hostOf('https://User@CDN.Example.com:443/x'), 'cdn.example.com');
  assert.equal(C.hostOf('http://[::1]:8080/x'), '[::1]');
  assert.equal(C.hostOf('ftp://e.com/x'), null);
  assert.equal(C.hostOf('blob:https://e.com/1'), null);
  assert.equal(C.isHttpUrl('HTTPS://e.com'), true);
  assert.equal(C.isHttpUrl('blob:https://e.com/1'), false);
  assert.equal(C.isHttpUrl(undefined), false);
  assert.equal(C.stripFragment('https://e.com/a#b'), 'https://e.com/a');
  assert.equal(C.baseMime(' Video/MP4 ; codecs="x"'), 'video/mp4');
  assert.equal(C.baseMime(''), null);
});

test('header lookup is case-insensitive for arrays and objects', () => {
  const array = [{ name: 'content-TYPE', value: 'video/mp4' }, { name: 'Cookie', value: 'a=1' }, { name: 'X' }];
  assert.equal(C.getHeader(array, 'Content-Type'), 'video/mp4');
  assert.equal(C.getHeader(array, 'cookie'), 'a=1');
  assert.equal(C.getHeader(array, 'x'), null);
  assert.equal(C.getHeader(array, 'missing'), null);
  assert.equal(C.getHeader({ REFERER: 'https://p/' }, 'Referer'), 'https://p/');
  assert.equal(C.getHeader(null, 'Referer'), null);

  const picked = C.pickHeaders(
    [{ name: 'referer', value: ' https://p/ ' }, { name: 'user-agent', value: 'UA' }, { name: 'Origin', value: ' ' }, { name: 'Accept', value: '*/*' }],
    ['Referer', 'User-Agent', 'Origin', 'Cookie'],
  );
  assert.deepEqual(picked, { Referer: 'https://p/', 'User-Agent': 'UA' });
});

test('Content-Range parsing', () => {
  assert.deepEqual(C.parseContentRange('bytes 0-99/1234'), { start: 0, end: 99, total: 1234 });
  assert.deepEqual(C.parseContentRange('BYTES 100-199/*'), { start: 100, end: 199, total: null });
  assert.deepEqual(C.parseContentRange('bytes */5000'), { start: null, end: null, total: 5000 });
  assert.equal(C.parseContentRange('items 0-1/2'), null);
  assert.equal(C.parseContentRange('bytes 0-99'), null);
  assert.equal(C.parseContentRange(null), null);
});

test('size from response headers', () => {
  const headers = (map) => Object.keys(map).map((name) => ({ name, value: map[name] }));
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Length': '1048576' }), 200), 1048576);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Range': 'bytes 0-1/734003200', 'Content-Length': '2' }), 206), 734003200);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Length': '2' }), 206), null);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Range': 'bytes 0-1/*', 'Content-Length': '2' }), 206), null);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Length': '500', 'Content-Encoding': 'gzip' }), 200), null);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Length': '500', 'Content-Encoding': 'identity' }), 200), 500);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Length': 'abc' }), 200), null);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Length': '99999999999999999999' }), 200), null);
  assert.equal(C.sizeFromHeaders(headers({ 'Content-Length': '6442450944' }), 200), 6442450944);
  assert.equal(C.sizeFromHeaders([], 200), null);
  assert.equal(C.sizeFromHeaders(undefined, 200), null);
});

test('dedupeKey matches UrlUtils.normalizeForDedupe', () => {
  assert.equal(
    C.dedupeKey('HTTPS://CDN.Example.com:443/Path/Video.mp4?b=2&range=0-100&a=1#t=10'),
    'https://cdn.example.com/Path/Video.mp4?b=2&a=1',
  );
  assert.equal(C.dedupeKey('http://example.com:80/v.mp4?_=1700000000&bytestart=0&byteend=99'), 'http://example.com/v.mp4');
  assert.equal(
    C.dedupeKey('https://r1.googlevideo.com/videoplayback?id=x&rn=3&rbuf=0&itag=18'),
    'https://r1.googlevideo.com/videoplayback?id=x&itag=18',
  );
  assert.equal(C.dedupeKey('http://example.com:8080/v.mp4'), 'http://example.com:8080/v.mp4');
  assert.equal(C.dedupeKey('https://e.com/v.m3u8?t=1700000000&ts=12&cb=5&x=1'), 'https://e.com/v.m3u8?x=1');
  assert.equal(C.dedupeKey('https://e.com/v.m3u8?t=abc&cb=x1'), 'https://e.com/v.m3u8?t=abc&cb=x1');
  assert.equal(C.dedupeKey('https://Example.com'), 'https://example.com/');
  assert.equal(C.dedupeKey('https://example.com/?'), 'https://example.com/');
});

test('compact drops empty values and non-finite numbers', () => {
  assert.deepEqual(
    C.compact({ a: 1, b: null, c: undefined, d: NaN, e: Infinity, f: '', g: false, h: 0, i: {} }),
    { a: 1, f: '', g: false, h: 0, i: {} },
  );
});

test('mergeMessages packs consecutive media items and keeps other messages in order', () => {
  const item = (n) => ({ url: `https://e.com/${n}.mp4` });
  const merged = C.mergeMessages([
    { type: 'media', items: [item(1), item(2)] },
    { type: 'media', items: [item(3)] },
    { type: 'drm', frameUrl: 'https://e.com/' },
    { type: 'media', items: [item(4), item(5), item(6)] },
    null,
    { notAMessage: true },
  ], 2);
  assert.deepEqual(merged, [
    { type: 'media', items: [item(1), item(2)] },
    { type: 'media', items: [item(3)] },
    { type: 'drm', frameUrl: 'https://e.com/' },
    { type: 'media', items: [item(4), item(5)] },
    { type: 'media', items: [item(6)] },
  ]);
  assert.deepEqual(C.mergeMessages([], 10), []);
});
