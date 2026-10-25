'use strict';

const fs = require('node:fs');

/*
 * Two kinds of provider:
 * - { type: 'xtream', base, username, password }: an Xtream Codes account (catalogue, films, series and EPG API).
 *   Recognised from get.php / player_api.php links, user:password@host links, .url shortcuts to those, and playlists
 *   whose streams use the Xtream /live|movie|series/<user>/<pass>/<id> layout.
 * - { type: 'm3u', url } or { type: 'm3u', file }: any other M3U playlist, by address or as a saved file.
 */

function xtreamFrom(url) {
  const pathname = url.pathname.replace(/\/$/, '');
  const endpoint = /\/(?:get|player_api)\.php$/i.test(pathname);
  const streamPath = pathname.match(/^(.*)\/(?:live|movie|series)\/([^/]+)\/([^/]+)\/[^/]+$/i);
  const userinfoOnly = !!url.username && pathname === '';
  if (!endpoint && !streamPath && !userinfoOnly) return null;
  const username = url.searchParams.get('username') || decodeURIComponent(url.username) || (streamPath && decodeURIComponent(streamPath[2]));
  const password = url.searchParams.get('password') || decodeURIComponent(url.password) || (streamPath && decodeURIComponent(streamPath[3]));
  if (!username || !password) throw new Error('URL IPTV sem username e password');
  const prefix = endpoint ? pathname.replace(/\/(?:get|player_api)\.php$/i, '') : (streamPath ? streamPath[1] : '');
  return { type: 'xtream', base: url.origin + prefix, username, password };
}

function httpUrl(raw) {
  let url;
  try { url = new URL(raw); }
  catch { throw new Error('URL IPTV inválido'); }
  if (!['http:', 'https:'].includes(url.protocol)) throw new Error('URL IPTV tem de usar HTTP ou HTTPS');
  return url;
}

/**
 * `input` is a link, a .url shortcut or a playlist's text. `file` is where that text was read from, so a playlist
 * that is not Xtream can be used in place.
 */
function parseProviderUrl(input, file) {
  const text = String(input || '').replace(/^﻿/, '');
  const lines = text.split(/\r?\n/).map(line => line.trim());
  const shortcut = lines.find(line => /^URL=/i.test(line));
  const isPlaylist = /^#EXTM3U/i.test(lines[0] || '') || lines.some(line => /^#EXTINF/i.test(line));

  if (isPlaylist && !shortcut) {
    const first = lines.find(line => /^https?:\/\//i.test(line));
    if (!first) throw new Error('A lista M3U não contém endereços HTTP');
    const xtream = xtreamFrom(httpUrl(first));
    if (xtream) return xtream;
    if (!file) throw new Error('Envia a lista M3U como ficheiro ou indica o seu URL');
    return { type: 'm3u', file };
  }

  const raw = shortcut ? shortcut.slice(4).trim() : lines.find(line => /^https?:\/\//i.test(line));
  if (!raw) throw new Error('O ficheiro ou URL IPTV não contém um endereço HTTP válido');
  const url = httpUrl(raw);
  return xtreamFrom(url) || { type: 'm3u', url: url.href };
}

function providerConfig(env = process.env) {
  if (env.IPTV_URL) return parseProviderUrl(env.IPTV_URL);
  if (env.IPTV_URL_FILE) return parseProviderUrl(fs.readFileSync(env.IPTV_URL_FILE, 'utf8'), env.IPTV_URL_FILE);
  const saved = env.PROVIDER_FILE || '/data/provider.url';
  if (fs.existsSync(saved)) return parseProviderUrl(fs.readFileSync(saved, 'utf8'), saved);
  const base = String(env.XTREAM_BASE || '').replace(/\/$/, '');
  if (!base) return null;
  return { type: 'xtream', base, username: String(env.XTREAM_USERNAME || ''), password: String(env.XTREAM_PASSWORD || '') };
}

function providerReady(provider) {
  if (!provider) return false;
  if (provider.type === 'm3u') return !!(provider.url || provider.file);
  return !!(provider.base && provider.username && provider.password);
}

module.exports = { parseProviderUrl, providerConfig, providerReady };
