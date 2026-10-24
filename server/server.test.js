'use strict';

const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const http = require('node:http');
const { test } = require('node:test');

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
      XTREAM_BASE: `http://127.0.0.1:${upstreamPort}`,
      XTREAM_USERNAME: 'user',
      XTREAM_PASSWORD: 'password',
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
