'use strict';

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const http = require('node:http');
const { test } = require('node:test');
const fs = require('node:fs/promises');
const os = require('node:os');
const path = require('node:path');

function listen(server) {
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));
}

async function freePort() {
  const server = http.createServer();
  const port = await listen(server);
  await new Promise(resolve => server.close(resolve));
  return port;
}

test('media proxy preserves ranges and survives an interrupted upstream', async t => {
  const upstream = http.createServer((req, res) => {
    if (req.url.endsWith('/2.mp4')) {
      res.writeHead(200, { 'content-type': 'video/mp4', 'content-length': 100 });
      res.write('partial');
      setTimeout(() => res.destroy(), 20);
      return;
    }
    assert.equal(req.headers.range, 'bytes=2-5');
    res.writeHead(206, {
      'content-type': 'video/mp4',
      'content-range': 'bytes 2-5/10',
      'content-length': 4,
    });
    res.end('2345');
  });
  const upstreamPort = await listen(upstream);
  t.after(() => new Promise(resolve => upstream.close(resolve)));

  const port = await freePort();
  const child = spawn(process.execPath, ['server/server.js'], {
    cwd: require('node:path').resolve(__dirname, '..'),
    env: {
      ...process.env,
      PORT: String(port),
      APP_PIN: '1234',
      SESSION_SECRET: 'test-only-secret',
      IPTV_URL: `http://127.0.0.1:${upstreamPort}/get.php?username=user&password=password&type=m3u_plus`,
      XTREAM_BASE: '',
      XTREAM_USERNAME: '',
      XTREAM_PASSWORD: '',
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  t.after(() => { child.kill(); });

  const base = `http://127.0.0.1:${port}`;
  let ready = false;
  for (let attempt = 0; attempt < 40; attempt++) {
    try {
      ready = (await fetch(`${base}/health`)).ok;
      if (ready) break;
    } catch {}
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(ready, `server did not start: ${stderr}`);
  assert.equal((await fetch(`${base}/tv`)).status, 404);
  assert.equal((await fetch(`${base}/s25`)).status, 404);
  assert.equal((await fetch(`${base}/theater`, { redirect: 'manual' })).status, 503);
  assert.equal((await fetch(`${base}/api/media/movie/1.mp4`)).status, 401);

  const login = await fetch(`${base}/api/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ pin: '1234' }),
  });
  assert.equal(login.status, 200);
  assert.match(login.headers.get('set-cookie'), /; Secure; SameSite=Strict;/);
  const cookie = login.headers.get('set-cookie').split(';')[0];
  const headers = { cookie, range: 'bytes=2-5' };
  const partial = await fetch(`${base}/api/media/movie/1.mp4`, { headers });
  assert.equal(partial.status, 206);
  assert.equal(partial.headers.get('content-range'), 'bytes 2-5/10');
  assert.equal(await partial.text(), '2345');

  try {
    const interrupted = await fetch(`${base}/api/media/movie/2.mp4`, { headers: { cookie } });
    await interrupted.arrayBuffer();
  } catch {}
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(child.exitCode, null, `server exited after upstream interruption: ${stderr}`);
  assert.equal((await fetch(`${base}/health`)).status, 200);
});

test('authenticated setup accepts an embedded-credential URL and loads its catalogue', async t => {
  const upstream = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://localhost');
    assert.equal(url.searchParams.get('username'), 'viewer');
    assert.equal(url.searchParams.get('password'), 'secret');
    const result = url.searchParams.get('action') === 'get_live_streams'
      ? [{ stream_id: 5, name: 'Canal de teste', container_extension: 'ts' }]
      : [];
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify(result));
  });
  const upstreamPort = await listen(upstream);
  t.after(() => new Promise(resolve => upstream.close(resolve)));
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'lumina-provider-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const configFile = path.join(dir, 'provider.url');
  const port = await freePort();
  const child = spawn(process.execPath, ['server/server.js'], {
    cwd: path.resolve(__dirname, '..'),
    env: {
      ...process.env,
      PORT: String(port), APP_PIN: '1234', SESSION_SECRET: 'test-only-secret',
      PROVIDER_FILE: configFile, IPTV_URL: '', IPTV_URL_FILE: '',
      XTREAM_BASE: '', XTREAM_USERNAME: '', XTREAM_PASSWORD: '',
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  t.after(() => child.kill());
  const base = `http://127.0.0.1:${port}`;
  let ready = false;
  for (let attempt = 0; attempt < 40; attempt++) {
    try { ready = (await fetch(`${base}/health`)).ok; if (ready) break; }
    catch {}
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(ready, `server did not start: ${stderr}`);
  assert.equal((await fetch(`${base}/api/provider`, { method: 'POST' })).status, 401);
  const login = await fetch(`${base}/api/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pin: '1234' }),
  });
  const cookie = login.headers.get('set-cookie').split(';')[0];
  assert.equal((await fetch(`${base}/api/catalog`, { headers: { cookie } })).status, 409);
  const provider = await fetch(`${base}/api/provider`, {
    method: 'POST', headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({ url: `URL=http://127.0.0.1:${upstreamPort}/get.php?username=viewer&password=secret&type=m3u_plus` }),
  });
  assert.equal(provider.status, 200, await provider.text());
  assert.equal((await fs.stat(configFile)).mode & 0o777, 0o600);
  const status = await (await fetch(`${base}/api/provider`, { headers: { cookie } })).json();
  assert.equal(status.configured, true);
  assert.equal(JSON.stringify(status).includes('secret'), false);
  const catalog = await (await fetch(`${base}/api/catalog`, { headers: { cookie } })).json();
  assert.equal(catalog.channels[0].name, 'Canal de teste');
});

test('a generic M3U playlist URL feeds the catalogue and the media proxy', async t => {
  const upstream = http.createServer((req, res) => {
    if (req.url === '/list.m3u') {
      res.writeHead(200, { 'content-type': 'audio/x-mpegurl' });
      res.end([
        '#EXTM3U',
        `#EXTINF:-1 group-title="Notícias",Canal M3U`,
        `http://127.0.0.1:${upstream.address().port}/live/canal.m3u8`,
        `#EXTINF:-1 group-title="Cinema",Filme M3U`,
        `http://127.0.0.1:${upstream.address().port}/files/filme.mp4`,
      ].join('\n'));
      return;
    }
    if (req.url === '/files/filme.mp4') { res.writeHead(200, { 'content-type': 'video/mp4', 'content-length': 4 }); res.end('mp4!'); return; }
    res.writeHead(404); res.end();
  });
  const upstreamPort = await listen(upstream);
  t.after(() => new Promise(resolve => upstream.close(resolve)));
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'lumina-m3u-'));
  t.after(() => fs.rm(dir, { recursive: true, force: true }));
  const port = await freePort();
  const child = spawn(process.execPath, ['server/server.js'], {
    cwd: path.resolve(__dirname, '..'),
    env: {
      ...process.env,
      PORT: String(port), APP_PIN: '1234', SESSION_SECRET: 'test-only-secret',
      PROVIDER_FILE: path.join(dir, 'provider.url'), IPTV_URL: '', IPTV_URL_FILE: '',
      XTREAM_BASE: '', XTREAM_USERNAME: '', XTREAM_PASSWORD: '',
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  });
  let stderr = '';
  child.stderr.on('data', chunk => { stderr += chunk; });
  t.after(() => child.kill());
  const base = `http://127.0.0.1:${port}`;
  let ready = false;
  for (let attempt = 0; attempt < 40; attempt++) {
    try { ready = (await fetch(`${base}/health`)).ok; if (ready) break; }
    catch {}
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  assert.ok(ready, `server did not start: ${stderr}`);
  const login = await fetch(`${base}/api/login`, {
    method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ pin: '1234' }),
  });
  const cookie = login.headers.get('set-cookie').split(';')[0];
  const saved = await fetch(`${base}/api/provider`, {
    method: 'POST', headers: { cookie, 'content-type': 'application/json' },
    body: JSON.stringify({ url: `http://127.0.0.1:${upstreamPort}/list.m3u` }),
  });
  assert.equal(saved.status, 200, await saved.text());
  const status = await (await fetch(`${base}/api/provider`, { headers: { cookie } })).json();
  assert.deepEqual([status.configured, status.type], [true, 'm3u']);
  const catalog = await (await fetch(`${base}/api/catalog`, { headers: { cookie } })).json();
  assert.equal(catalog.channels[0].name, 'Canal M3U');
  assert.equal(catalog.channels[0].group, 'Notícias');
  assert.equal(catalog.movies[0].url, '/api/media/movie/1.mp4');
  const media = await fetch(`${base}${catalog.movies[0].url}`, { headers: { cookie } });
  assert.equal(await media.text(), 'mp4!');

  // A whole playlist file can be uploaded too.
  const upload = await fetch(`${base}/api/provider/playlist`, {
    method: 'POST', headers: { cookie, 'content-type': 'text/plain' },
    body: `#EXTM3U\n#EXTINF:-1 group-title="Locais",Canal do ficheiro\nhttp://127.0.0.1:${upstreamPort}/live/x.m3u8\n`,
  });
  assert.equal(upload.status, 200, await upload.text());
  const fromFile = await (await fetch(`${base}/api/catalog`, { headers: { cookie } })).json();
  assert.equal(fromFile.channels[0].name, 'Canal do ficheiro');
});
