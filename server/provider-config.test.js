'use strict';

const assert = require('node:assert/strict');
const { test } = require('node:test');
const { parseProviderUrl, providerConfig } = require('./provider-config');
const { parsePlaylist } = require('./m3u');

test('accepts common Xtream URLs with embedded credentials', () => {
  assert.deepEqual(
    parseProviderUrl('https://iptv.example:8443/get.php?username=ana%40tv&password=p%40ss%3Aword&type=m3u_plus'),
    { type: 'xtream', base: 'https://iptv.example:8443', username: 'ana@tv', password: 'p@ss:word' },
  );
  assert.deepEqual(
    parseProviderUrl('http://ana:p%40ss@iptv.example/player_api.php'),
    { type: 'xtream', base: 'http://iptv.example', username: 'ana', password: 'p@ss' },
  );
});

test('accepts .url shortcuts and Xtream M3U playlist stream URLs', () => {
  assert.deepEqual(
    parseProviderUrl('[InternetShortcut]\nURL=https://iptv.example/get.php?username=ana&password=segredo\n'),
    { type: 'xtream', base: 'https://iptv.example', username: 'ana', password: 'segredo' },
  );
  assert.deepEqual(
    parseProviderUrl('#EXTM3U\n#EXTINF:-1,Canal\nhttp://iptv.example/live/ana/segredo/123.ts\n'),
    { type: 'xtream', base: 'http://iptv.example', username: 'ana', password: 'segredo' },
  );
});

test('rejects URLs without credentials and retains separate-field configuration', () => {
  assert.throws(() => parseProviderUrl('https://iptv.example/get.php'), /username e password/);
  assert.deepEqual(providerConfig({ XTREAM_BASE: 'http://iptv.example/', XTREAM_USERNAME: 'ana', XTREAM_PASSWORD: 'segredo' }),
    { type: 'xtream', base: 'http://iptv.example', username: 'ana', password: 'segredo' });
});

test('accepts any other M3U playlist by address or as a file', () => {
  assert.deepEqual(parseProviderUrl('https://lists.example/tv/pt.m3u8'), { type: 'm3u', url: 'https://lists.example/tv/pt.m3u8' });
  assert.deepEqual(parseProviderUrl('URL=https://lists.example/tv.m3u\n'), { type: 'm3u', url: 'https://lists.example/tv.m3u' });
  const playlist = '#EXTM3U\n#EXTINF:-1 group-title="Notícias",RTP 3\nhttps://cdn.example/rtp3/index.m3u8\n';
  assert.deepEqual(parseProviderUrl(playlist, '/data/provider.url'), { type: 'm3u', file: '/data/provider.url' });
  assert.throws(() => parseProviderUrl(playlist), /ficheiro/);
  assert.equal(providerConfig({ XTREAM_BASE: '', IPTV_URL: '', IPTV_URL_FILE: '', PROVIDER_FILE: '/nonexistent/provider.url' }), null);
});

test('reads channels, films and series from a generic playlist', () => {
  const p = parsePlaylist([
    '#EXTM3U',
    '#EXTINF:-1 tvg-id="rtp1.pt" tvg-logo="https://img.example/rtp1.png" group-title="Generalistas",RTP 1',
    'https://cdn.example/rtp1/index.m3u8',
    '#EXTINF:-1 group-title="Filmes, clássicos" tvg-logo="https://img.example/p.jpg",O Pátio, 1954',
    'https://vod.example/files/patio.mkv',
    '#EXTINF:-1 group-title="Séries",Dark S01E02 - Mentiras',
    'https://vod.example/files/dark-s01e02.mp4',
    '#EXTINF:-1 group-title="Séries",Dark S01E01',
    'https://vod.example/files/dark-s01e01.mp4',
    '#EXTGRP:Rádio',
    '#EXTINF:-1,Antena 1',
    'https://radio.example/antena1',
  ].join('\n'));
  assert.deepEqual(p.live.map(x => [x.id, x.name, x.group, x.tvgId]), [[1, 'RTP 1', 'Generalistas', 'rtp1.pt'], [2, 'Antena 1', 'Rádio', '']]);
  assert.deepEqual(p.movies.map(x => [x.name, x.category, x.ext]), [['O Pátio, 1954', 'Filmes, clássicos', 'mkv']]);
  assert.equal(p.series.length, 1);
  assert.equal(p.series[0].name, 'Dark');
  assert.deepEqual(p.series[0].seasons['1'].map(e => [e.num, e.title]), [[1, 'Dark S01E01'], [2, 'Mentiras']]);
  assert.equal(p.episodes.get(1).url, 'https://vod.example/files/dark-s01e02.mp4');
});
