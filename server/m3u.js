'use strict';

/*
 * Generic M3U playlists (no Xtream API): channels, films and series are read from the #EXTINF entries.
 * - Films: /movie/ in the address, or a file extension such as .mp4/.mkv without an SxxEyy in the name.
 * - Series: /series/ in the address, or an SxxEyy in the name; episodes are grouped by the name before SxxEyy.
 * - Everything else is a live channel, grouped by group-title (or #EXTGRP).
 * Ids are positions in the playlist, numbered per kind from 1.
 */

const VOD_EXTENSION = /\.(mp4|mkv|avi|mov|m4v|wmv|mpg|mpeg|webm)$/i;
const EPISODE = /^(.*?)[\s._-]*S(\d{1,2})\s*E(\d{1,3})\b/i;

function attributes(line) {
  const out = {};
  for (const m of line.matchAll(/([\w-]+)="([^"]*)"/g)) out[m[1].toLowerCase()] = m[2].trim();
  return out;
}

function extension(address, fallback) {
  try {
    const m = new URL(address).pathname.match(/\.([a-z0-9]{2,5})$/i);
    return m ? m[1].toLowerCase() : fallback;
  } catch { return fallback; }
}

function parsePlaylist(text) {
  const live = [], movies = [], series = [];
  const episodes = new Map();
  const seriesByName = new Map();
  let info = null, group = '';

  for (const raw of String(text || '').replace(/^﻿/, '').split(/\r?\n/)) {
    const line = raw.trim();
    if (!line) continue;
    if (/^#EXTINF/i.test(line)) {
      const attrs = attributes(line);
      const bare = line.replace(/[\w-]+="[^"]*"/g, '');
      const comma = bare.indexOf(',');
      info = { attrs, name: (comma >= 0 ? bare.slice(comma + 1) : '').trim() || attrs['tvg-name'] || '' };
      continue;
    }
    if (/^#EXTGRP:/i.test(line)) { group = line.slice(8).trim(); continue; }
    if (line.startsWith('#') || !/^https?:\/\//i.test(line)) continue;

    const attrs = info?.attrs || {};
    const name = info?.name || attrs['tvg-name'] || line;
    const category = attrs['group-title'] || group || '';
    const logo = attrs['tvg-logo'] || '';
    const path = (() => { try { return new URL(line).pathname; } catch { return ''; } })();
    const episode = name.match(EPISODE);
    const isVodFile = VOD_EXTENSION.test(path);
    info = null; group = '';

    if (/\/series\//i.test(path) || (isVodFile && episode)) {
      const title = (episode ? episode[1] : category || name).replace(/[\s._-]+$/, '').trim() || name;
      let show = seriesByName.get(title.toLowerCase());
      if (!show) {
        show = { id: series.length + 1, name: title, poster: logo, category, seasons: {} };
        series.push(show);
        seriesByName.set(title.toLowerCase(), show);
      }
      if (!show.poster && logo) show.poster = logo;
      const season = episode ? String(Number(episode[2])) : '1';
      const list = (show.seasons[season] ||= []);
      const id = episodes.size + 1;
      const rest = episode ? name.slice(episode[0].length).replace(/^[\s:–-]+/, '').trim() : '';
      episodes.set(id, { url: line, ext: extension(line, 'mp4') });
      list.push({ id, num: episode ? Number(episode[3]) : list.length + 1, title: rest || name, ext: extension(line, 'mp4') });
    } else if (/\/movie\//i.test(path) || isVodFile) {
      movies.push({ id: movies.length + 1, name, poster: logo, category, url: line, ext: extension(line, 'mp4') });
    } else {
      live.push({ id: live.length + 1, name, logo, group: category, tvgId: attrs['tvg-id'] || '', url: line, ext: extension(line, 'ts') });
    }
  }
  for (const show of series) for (const list of Object.values(show.seasons)) list.sort((a, b) => a.num - b.num);
  return { live, movies, series, episodes };
}

module.exports = { parsePlaylist };
