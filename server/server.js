'use strict';

const http = require('node:http');
const fs = require('node:fs');
const fsp = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const { spawn } = require('node:child_process');
const { Readable } = require('node:stream');
const { pipeline } = require('node:stream/promises');
const { parseProviderUrl, providerConfig, providerReady } = require('./provider-config');
const { parsePlaylist } = require('./m3u');

const PORT = Number(process.env.PORT || 8090);
const APP_PIN = String(process.env.APP_PIN || '');
const PROVIDER_FILE = String(process.env.PROVIDER_FILE || '/data/provider.url');
// The provider: an Xtream account or a generic M3U playlist (see provider-config.js). It can change at runtime.
let provider = null, XTREAM_BASE = '', XTREAM_USERNAME = '', XTREAM_PASSWORD = '';
let playlist = null, playlistAt = 0, playlistLoading = null;
function applyProvider(next) {
  provider = next;
  const x = next?.type === 'xtream' ? next : {};
  XTREAM_BASE = x.base || ''; XTREAM_USERNAME = x.username || ''; XTREAM_PASSWORD = x.password || '';
  playlist = null; playlistAt = 0; playlistLoading = null;
}
try { applyProvider(providerConfig()); }
catch (error) { console.error(error.message); process.exit(1); }
const isM3u = () => provider?.type === 'm3u';
const SESSION_SECRET = String(process.env.SESSION_SECRET || '');
const SESSION_DAYS = Math.max(1, Number(process.env.SESSION_DAYS || 30));
const COOKIE_SECURE = process.env.COOKIE_SECURE !== 'false';
const PUBLIC_BASE_URL = String(process.env.PUBLIC_BASE_URL || '').replace(/\/$/, '');
const VIDEO_CODEC = String(process.env.STREAM_VIDEO_CODEC || 'copy');
const IDLE_SECONDS = Math.max(30, Number(process.env.STREAM_IDLE_SECONDS || 90));
const FFMPEG = String(process.env.FFMPEG_PATH || 'ffmpeg');
const FFPROBE = String(process.env.FFPROBE_PATH || 'ffprobe');
const WEB_ROOT = path.resolve(__dirname, '..');
const HLS_ROOT = path.resolve(process.env.HLS_ROOT || '/tmp/lumina-auto-hls');
const VOD_ROOT = path.resolve(process.env.VOD_ROOT || '/tmp/lumina-vod-hls');
const streams = new Map();
const vodStreams = new Map();
const vodProcesses = new Set();
const segmentWaiters = [];
let activeSegments = 0;
const subtitleCache = new Map();

if (!APP_PIN || !SESSION_SECRET) {
  console.error('Faltam APP_PIN ou SESSION_SECRET.');
  process.exit(1);
}

const MIME = { '.html':'text/html; charset=utf-8', '.js':'text/javascript; charset=utf-8', '.css':'text/css; charset=utf-8', '.json':'application/json; charset=utf-8', '.webmanifest':'application/manifest+json', '.svg':'image/svg+xml', '.m3u8':'application/vnd.apple.mpegurl', '.ts':'video/mp2t', '.m4s':'video/iso.segment' };
const json = (res, status, body, headers={}) => send(res, status, JSON.stringify(body), { 'content-type': MIME['.json'], 'cache-control':'no-store', ...headers });
function send(res, status, body, headers={}) { res.writeHead(status, { 'x-content-type-options':'nosniff', 'referrer-policy':'same-origin', ...headers }); res.end(body); }
function cookies(req) { return Object.fromEntries(String(req.headers.cookie || '').split(';').map(x=>x.trim()).filter(Boolean).map(x=>{const i=x.indexOf('=');return i<0?[x,'']:[x.slice(0,i),decodeURIComponent(x.slice(i+1))]})); }
function sign(value) { return crypto.createHmac('sha256', SESSION_SECRET).update(value).digest('base64url'); }
function makeSession() { const payload = Buffer.from(JSON.stringify({ exp: Date.now()+SESSION_DAYS*864e5 })).toString('base64url'); return `${payload}.${sign(payload)}`; }
// 4-digit PIN on a public address: failed logins are capped for everyone together (10 an hour, 30 a day).
const loginFailures=[];
function loginLocked(now){while(loginFailures.length&&now-loginFailures[0]>86400000)loginFailures.shift();return loginFailures.filter(at=>now-at<3600000).length>=10||loginFailures.length>=30}
function validSession(req) { const raw=cookies(req).lumina_auto_session||''; const [p,s]=raw.split('.'); if(!p||!s) return false; const expected=sign(p); if(s.length!==expected.length||!crypto.timingSafeEqual(Buffer.from(s),Buffer.from(expected))) return false; try{return JSON.parse(Buffer.from(p,'base64url')).exp>Date.now()}catch{return false} }
function safeEqual(a,b) { const x=Buffer.from(String(a)), y=Buffer.from(String(b)); return x.length===y.length && crypto.timingSafeEqual(x,y); }
async function bodyText(req, max) { const chunks=[]; let size=0; for await(const c of req){size+=c.length; if(size>max) throw Object.assign(new Error('Ficheiro demasiado grande'),{status:413}); chunks.push(c)} return Buffer.concat(chunks).toString('utf8'); }
async function bodyJson(req) { const chunks=[]; for await(const c of req){chunks.push(c); if(chunks.reduce((n,b)=>n+b.length,0)>8192) throw new Error('pedido demasiado grande')} return JSON.parse(Buffer.concat(chunks).toString('utf8')||'{}'); }

async function saveProvider(input) {
  const target = path.resolve(PROVIDER_FILE);
  const parsed = parseProviderUrl(input, target);
  await fsp.mkdir(path.dirname(target), { recursive: true });
  const temporary = `${target}.${crypto.randomBytes(8).toString('hex')}.tmp`;
  try {
    await fsp.writeFile(temporary, String(input).trim() + '\n', { mode: 0o600, flag: 'wx' });
    await fsp.rename(temporary, target);
  } catch (error) {
    await fsp.rm(temporary, { force: true }).catch(() => {});
    throw error;
  }
  for (const stream of streams.values()) stream.proc.kill('SIGTERM');
  for (const proc of vodProcesses) proc.kill('SIGTERM');
  streams.clear();
  vodStreams.clear();
  subtitleCache.clear();
  imageSources.clear();
  applyProvider(parsed);
}

// Generic M3U: the playlist is fetched (or read from its file) and kept for 30 minutes.
const PLAYLIST_TTL = 30 * 60000, PLAYLIST_MAX = 64 * 1024 * 1024;
async function loadPlaylistText(source) {
  if (source.file) return fsp.readFile(source.file, 'utf8');
  const url = new URL(source.url);
  const headers = { 'user-agent': 'VLC/3.0.20 LibVLC/3.0.20' };
  if (url.username) {
    headers.authorization = 'Basic ' + Buffer.from(`${decodeURIComponent(url.username)}:${decodeURIComponent(url.password)}`).toString('base64');
    url.username = ''; url.password = '';
  }
  const r = await fetch(url, { headers, signal: AbortSignal.timeout(60000) });
  if (!r.ok) throw new Error(`Lista M3U respondeu HTTP ${r.status}`);
  if (Number(r.headers.get('content-length') || 0) > PLAYLIST_MAX) throw new Error('Lista M3U demasiado grande');
  const text = await r.text();
  if (text.length > PLAYLIST_MAX) throw new Error('Lista M3U demasiado grande');
  return text;
}
async function m3u() {
  if (playlist && Date.now() - playlistAt < PLAYLIST_TTL) return playlist;
  if (!playlistLoading) {
    const source = provider;
    playlistLoading = loadPlaylistText(source).then(text => {
      const parsed = parsePlaylist(text);
      if (provider === source) { playlist = parsed; playlistAt = Date.now(); }
      return parsed;
    }).finally(() => { if (provider === source) playlistLoading = null; });
  }
  try { return await playlistLoading; }
  catch (error) { if (playlist) return playlist; throw error; }
}
async function sourceUrl(kind, id, ext) {
  if (!isM3u()) return kind === 'movie' ? movieUrl(id, ext) : kind === 'series' ? episodeUrl(id, ext) : liveUrl(id, ext);
  const p = await m3u();
  const x = kind === 'movie' ? p.movies[Number(id) - 1] : kind === 'series' ? p.episodes.get(Number(id)) : p.live[Number(id) - 1];
  if (!x) throw Object.assign(new Error('Conteúdo inexistente'), { code: 'ENOENT' });
  return x.url;
}
const cleanExt = ext => String(ext || 'mp4').replace(/[^a-z0-9]/gi, '');

function xtreamUrl(action='', extra={}) { const u=new URL(`${XTREAM_BASE}/player_api.php`); u.searchParams.set('username',XTREAM_USERNAME);u.searchParams.set('password',XTREAM_PASSWORD);if(action)u.searchParams.set('action',action);for(const [k,v] of Object.entries(extra))u.searchParams.set(k,String(v));return u; }
async function xtream(action='', extra={}) { const r=await fetch(xtreamUrl(action,extra),{headers:{'user-agent':'VLC/3.0.20 LibVLC/3.0.20'},signal:AbortSignal.timeout(30000)}); if(!r.ok)throw new Error(`Fornecedor respondeu HTTP ${r.status}`); return r.json(); }
const enc = encodeURIComponent;
const imageSources = new Map();
function liveUrl(id,ext='ts'){return `${XTREAM_BASE}/live/${enc(XTREAM_USERNAME)}/${enc(XTREAM_PASSWORD)}/${id}.${ext}`}
function movieUrl(id,ext='mp4'){return `${XTREAM_BASE}/movie/${enc(XTREAM_USERNAME)}/${enc(XTREAM_PASSWORD)}/${id}.${ext}`}
function episodeUrl(id,ext='mp4'){return `${XTREAM_BASE}/series/${enc(XTREAM_USERNAME)}/${enc(XTREAM_PASSWORD)}/${id}.${ext}`}
async function categories(action){const list=await xtream(action).catch(()=>[]);return new Map((Array.isArray(list)?list:[]).map(x=>[String(x.category_id),x.category_name||'']))}
function b64(v){try{return Buffer.from(String(v||''),'base64').toString('utf8')}catch{return String(v||'')}}

async function channels(){
  if(isM3u()){
    const p=await m3u();
    return {channels:p.live.map(x=>{
      if(x.logo)imageSources.set(`live:${x.id}`,x.logo);
      return {streamId:x.id,name:x.name,logo:x.logo?`/api/logo/live/${x.id}`:'',group:x.group,epgChannelId:x.tvgId,url:`/api/stream/${x.id}/index.m3u8`,sourceExtension:x.ext};
    })};
  }
  const [list,cats]=await Promise.all([
    xtream('get_live_streams'),
    categories('get_live_categories')
  ]);

  return {
    channels:(Array.isArray(list)?list:[]).map(x=>{
      const id=Number(x.stream_id);
      const source=String(x.stream_icon||'').trim();

      if(source)imageSources.set(`live:${id}`,source);

      return {
        streamId:id,
        name:x.name||`Canal ${id}`,
        logo:source?`/api/logo/live/${id}`:'',
        group:cats.get(String(x.category_id))||'',
        epgChannelId:x.epg_channel_id||'',
        url:`/api/stream/${id}/index.m3u8`,
        sourceExtension:x.container_extension||'ts'
      };
    })
  };
}
async function movies(){if(isM3u())return {movies:(await m3u()).movies.map(x=>({streamId:x.id,name:x.name,poster:x.poster,rating:0,category:x.category,genre:'',url:`/api/media/movie/${x.id}.${cleanExt(x.ext)}`}))};const [list,cats]=await Promise.all([xtream('get_vod_streams'),categories('get_vod_categories')]);return {movies:(Array.isArray(list)?list:[]).filter(x=>Number(x.is_adult)!==1&&!/adult/i.test(cats.get(String(x.category_id))||'')).map(x=>({streamId:Number(x.stream_id),name:x.name||'',poster:x.stream_icon||'',rating:Number(x.rating_5based||0),category:cats.get(String(x.category_id))||'',genre:String(x.genre||''),url:`/api/media/movie/${x.stream_id}.${String(x.container_extension||'mp4').replace(/[^a-z0-9]/gi,'')}`}))}}
async function series(){if(isM3u())return {series:(await m3u()).series.map(x=>({seriesId:x.id,name:x.name,poster:x.poster,rating:0,category:x.category,genre:'',plot:''}))};const [list,cats]=await Promise.all([xtream('get_series'),categories('get_series_categories')]);return {series:(Array.isArray(list)?list:[]).map(x=>({seriesId:Number(x.series_id),name:x.name||'',poster:x.cover||'',rating:Number(x.rating_5based||0),category:cats.get(String(x.category_id))||'',genre:String(x.genre||''),plot:String(x.plot||'').slice(0,500)}))}}
async function epg(id){if(isM3u())return {streamId:String(id),programmes:[]};const data=await xtream('get_short_epg',{stream_id:id,limit:8});return {streamId:String(id),programmes:(data.epg_listings||[]).map(x=>({start:x.start,end:x.end,title:b64(x.title),description:b64(x.description)}))}}
async function movieInfo(id){if(isM3u()){const x=(await m3u()).movies[Number(id)-1];return {id:Number(id),plot:'',cast:'',director:'',genre:x?.category||'',duration:'',releaseDate:'',rating:'',backdrop:''}}const d=await xtream('get_vod_info',{vod_id:id});const i=d.info||{};return {id:Number(id),plot:i.plot||i.description||'',cast:i.cast||i.actors||'',director:i.director||'',genre:i.genre||'',duration:i.duration||'',releaseDate:i.releasedate||i.release_date||'',rating:String(i.rating||''),backdrop:Array.isArray(i.backdrop_path)?i.backdrop_path[0]||'':i.backdrop_path||i.cover_big||''}}
async function seriesInfo(id){if(isM3u()){const x=(await m3u()).series[Number(id)-1];if(!x)throw Object.assign(new Error('Série inexistente'),{code:'ENOENT'});const seasons={};for(const [season,list] of Object.entries(x.seasons))seasons[season]=list.map(e=>({id:String(e.id),num:e.num,title:e.title,url:`/api/media/series/${e.id}.${cleanExt(e.ext)}`}));return {name:x.name,seasons,plot:'',cast:'',director:'',genre:x.category||'',duration:'',releaseDate:'',rating:'',backdrop:''}}const d=await xtream('get_series_info',{series_id:id});const i=d.info||{}, seasons={};for(const [season,list] of Object.entries(d.episodes||{}))seasons[season]=(Array.isArray(list)?list:[]).map(x=>({id:String(x.id),num:Number(x.episode_num||0),title:x.title||'',url:`/api/media/series/${x.id}.${String(x.container_extension||'mp4').replace(/[^a-z0-9]/gi,'')}`}));return {name:i.name||'',seasons,plot:i.plot||'',cast:i.cast||'',director:i.director||'',genre:i.genre||'',duration:i.duration||'',releaseDate:i.releaseDate||i.release_date||'',rating:String(i.rating||''),backdrop:Array.isArray(i.backdrop_path)?i.backdrop_path[0]||'':i.backdrop_path||''}}

async function proxyLogo(res,id){
  const source=imageSources.get(`live:${id}`);

  if(!source){
    return send(res,404,'Imagem não encontrada',{
      'content-type':'text/plain; charset=utf-8'
    });
  }

  const sourceUrl=new URL(source);

  if(!['http:','https:'].includes(sourceUrl.protocol)){
    return send(res,400,'URL de imagem inválido',{
      'content-type':'text/plain; charset=utf-8'
    });
  }

  const response=await fetch(sourceUrl,{
    redirect:'follow',
    headers:{
      'user-agent':'Mozilla/5.0 LuminaAuto/1.0',
      'accept':'image/avif,image/webp,image/png,image/jpeg,image/*'
    },
    signal:AbortSignal.timeout(15000)
  });

  if(!response.ok){
    throw new Error(`Imagem respondeu HTTP ${response.status}`);
  }

  const contentType=response.headers.get('content-type')||'image/jpeg';

  if(!contentType.toLowerCase().startsWith('image/')){
    throw new Error('O endereço não devolveu uma imagem');
  }

  const data=Buffer.from(await response.arrayBuffer());

  if(data.length>5*1024*1024){
    throw new Error('Imagem demasiado grande');
  }

  return send(res,200,data,{
    'content-type':contentType,
    'content-length':String(data.length),
    'cache-control':'private, max-age=86400'
  });
}

function runProcess(command,args,timeout=45000,maxBytes=12*1024*1024){
  return new Promise((resolve,reject)=>{
    const proc=spawn(command,args,{
      stdio:['ignore','pipe','pipe']
    });

    const stdout=[];
    const stderr=[];
    let stdoutSize=0;
    let settled=false;

    const finishError=message=>{
      if(settled)return;
      settled=true;
      clearTimeout(timer);
      try{proc.kill('SIGKILL')}catch{}
      reject(new Error(message));
    };

    const timer=setTimeout(
      ()=>finishError('Operação de legendas excedeu o tempo limite'),
      timeout
    );

    proc.stdout.on('data',chunk=>{
      stdoutSize+=chunk.length;

      if(stdoutSize>maxBytes){
        return finishError('Legenda demasiado grande');
      }

      stdout.push(chunk);
    });

    proc.stderr.on('data',chunk=>{
      stderr.push(chunk);
    });

    proc.on('error',()=>{
      finishError('Não foi possível executar FFmpeg/FFprobe');
    });

    proc.on('close',code=>{
      if(settled)return;
      settled=true;
      clearTimeout(timer);

      if(code!==0){
        return reject(new Error(
          Buffer.concat(stderr).toString('utf8').slice(-1000) ||
          'Não existem legendas compatíveis'
        ));
      }

      resolve(Buffer.concat(stdout));
    });
  });
}


async function extractSubtitle(kind,id,ext){
  const key=`${kind}:${id}:${ext}`;

  if(subtitleCache.has(key)){
    return subtitleCache.get(key);
  }

  const upstream=await sourceUrl(kind,id,ext);

  const probeBuffer=await runProcess(FFPROBE,[
    '-v','error',
    '-select_streams','s',
    '-show_entries','stream=index,codec_name:stream_tags=language,title',
    '-of','json',
    upstream
  ],30000,1024*1024);

  const probe=JSON.parse(probeBuffer.toString('utf8')||'{}');
  const supported=new Set([
    'subrip','srt','ass','ssa','webvtt',
    'mov_text','text','microdvd','mpl2','jacosub'
  ]);

  const streams=(Array.isArray(probe.streams)?probe.streams:[])
    .filter(stream=>supported.has(
      String(stream.codec_name||'').toLowerCase()
    ));

  if(!streams.length){
    subtitleCache.set(key,null);
    return null;
  }

  const preferred=streams.find(stream=>{
    const language=String(
      stream.tags?.language||''
    ).toLowerCase();

    return ['por','pt','pob','pt-pt','portuguese'].includes(language);
  })||streams[0];

  const output=await runProcess(FFMPEG,[
    '-hide_banner',
    '-loglevel','error',
    '-i',upstream,
    '-map',`0:${preferred.index}`,
    '-c:s','webvtt',
    '-f','webvtt',
    'pipe:1'
  ],60000);

  if(!output.length){
    subtitleCache.set(key,null);
    return null;
  }

  const result={
    data:output,
    language:String(preferred.tags?.language||'pt'),
    title:String(preferred.tags?.title||'Português')
  };

  subtitleCache.set(key,result);
  return result;
}

async function serveSubtitle(res,kind,id,ext){
  const subtitle=await extractSubtitle(kind,id,ext);

  if(!subtitle){
    return send(res,404,'Sem legendas compatíveis',{
      'content-type':'text/plain; charset=utf-8',
      'cache-control':'no-store'
    });
  }

  return send(res,200,subtitle.data,{
    'content-type':'text/vtt; charset=utf-8',
    'content-length':String(subtitle.data.length),
    'cache-control':'private, max-age=86400',
    'content-language':subtitle.language
  });
}

async function proxyMedia(req,res,kind,id,ext){const upstream=await sourceUrl(kind,id,ext);const headers={'user-agent':'VLC/3.0.20 LibVLC/3.0.20'};if(req.headers.range)headers.range=req.headers.range;const r=await fetch(upstream,{headers,signal:AbortSignal.timeout(30000)});if(!r.ok&&r.status!==206)throw new Error(`Fornecedor respondeu HTTP ${r.status}`);const out={};for(const h of ['content-type','content-length','content-range']){const v=r.headers.get(h);if(v)out[h]=v}if(r.headers.get('content-range')||r.headers.get('accept-ranges'))out['accept-ranges']='bytes';out['cache-control']='private, max-age=60';out['x-content-type-options']='nosniff';res.writeHead(r.status,out);if(r.body)await pipeline(Readable.fromWeb(r.body),res);else res.end()}

function vodKey(kind,id,ext){return `${kind}-${id}-${ext.toLowerCase()}`}
async function probeVod(source){
  return new Promise((resolve,reject)=>{
    const proc=spawn(FFPROBE,['-v','error','-show_entries','format=duration:stream=codec_name,codec_type','-of','json',source],{stdio:['ignore','pipe','ignore']});
    let output='';const timer=setTimeout(()=>proc.kill('SIGKILL'),30000);
    proc.stdout.on('data',data=>{output+=data.toString();if(output.length>16384)proc.kill('SIGKILL')});
    proc.on('error',reject);
    proc.on('close',code=>{clearTimeout(timer);if(code!==0)return reject(new Error('Não foi possível analisar o vídeo'));try{const data=JSON.parse(output);const duration=Number(data.format?.duration);if(!data.streams?.some(stream=>stream.codec_type==='video')||!Number.isFinite(duration)||duration<=0||duration>86400)throw new Error('Duração do vídeo indisponível');resolve(duration)}catch{reject(new Error('Metadados de vídeo inválidos'))}});
  });
}
async function startVod(kind,id,ext,key){
  const source=await sourceUrl(kind,id,ext);
  const duration=await probeVod(source);
  const dir=path.join(VOD_ROOT,key);
  await fsp.rm(dir,{recursive:true,force:true});await fsp.mkdir(dir,{recursive:true});
  const segments=[];let remaining=duration;
  while(remaining>12){segments.push(10);remaining-=10}
  segments.push(remaining);
  const playlist=['#EXTM3U','#EXT-X-VERSION:3',`#EXT-X-TARGETDURATION:${Math.ceil(Math.max(...segments))}`,'#EXT-X-MEDIA-SEQUENCE:0','#EXT-X-PLAYLIST-TYPE:VOD'];
  segments.forEach((seconds,index)=>playlist.push(`#EXTINF:${seconds.toFixed(3)},`,`seg-${String(index).padStart(6,'0')}.ts`));
  playlist.push('#EXT-X-ENDLIST');
  await fsp.writeFile(path.join(dir,'index.m3u8'),playlist.join('\n')+'\n');
  const entry={dir,source,segments,lastTouch:Date.now(),jobs:new Map()};vodStreams.set(key,entry);
  return entry;
}
async function ensureVod(kind,id,ext){
  const key=vodKey(kind,id,ext);let entry=vodStreams.get(key);
  if(entry?.promise)entry=await entry.promise;
  if(entry?.dir){entry.lastTouch=Date.now();return entry}
  const promise=startVod(kind,id,ext,key);vodStreams.set(key,{promise,lastTouch:Date.now()});
  try{return await promise}catch(error){vodStreams.delete(key);throw error}
}
async function segmentSlot(){if(activeSegments<2){activeSegments++;return}await new Promise(resolve=>segmentWaiters.push(resolve))}
function releaseSegmentSlot(){const next=segmentWaiters.shift();if(next)next();else activeSegments--}
async function ensureVodSegment(entry,index){
  if(index>=entry.segments.length)throw Object.assign(new Error('Segmento inválido'),{code:'ENOENT'});
  const file=path.join(entry.dir,`seg-${String(index).padStart(6,'0')}.ts`);
  if(fs.existsSync(file))return file;
  if(entry.jobs.has(index))return entry.jobs.get(index);
  const job=(async()=>{
    await segmentSlot();
    const temp=file+'.tmp';
    try{
      await new Promise((resolve,reject)=>{
        const args=['-hide_banner','-loglevel','error','-nostdin','-ss',String(index*10),'-reconnect','1','-reconnect_streamed','1','-reconnect_delay_max','5','-i',entry.source,'-t',String(entry.segments[index]),'-map','0:v:0','-map','0:a:0?','-sn','-dn','-c:v','libx264','-preset','ultrafast','-crf','25','-vf','scale=w=1280:h=720:force_original_aspect_ratio=decrease:force_divisible_by=2,format=yuv420p','-c:a','aac','-b:a','128k','-ac','2','-f','mpegts','-y',temp];
        const proc=spawn(FFMPEG,args,{stdio:['ignore','ignore','pipe']});vodProcesses.add(proc);
        let stderr='';const timer=setTimeout(()=>proc.kill('SIGKILL'),60000);
        proc.stderr.on('data',data=>stderr=(stderr+data.toString()).slice(-1000));
        proc.on('error',reject);
        proc.on('close',code=>{clearTimeout(timer);vodProcesses.delete(proc);if(code===0)resolve();else reject(new Error('Não foi possível converter este segmento'))});
      });
      if((await fsp.stat(temp)).size===0)throw new Error('Segmento vazio');
      await fsp.rename(temp,file);
      return file;
    }catch(error){await fsp.rm(temp,{force:true}).catch(()=>{});throw error}
    finally{releaseSegmentSlot()}
  })();
  entry.jobs.set(index,job);
  try{return await job}finally{entry.jobs.delete(index)}
}
setInterval(async()=>{const now=Date.now();for(const [key,entry] of vodStreams){if(entry.promise||entry.jobs?.size||now-entry.lastTouch<600000)continue;vodStreams.delete(key);await fsp.rm(entry.dir,{recursive:true,force:true}).catch(()=>{})}},60000).unref();

function streamKey(id){return String(id).replace(/[^0-9]/g,'')}
async function ensureStream(id){const key=streamKey(id);if(!key)throw new Error('Canal inválido');const existing=streams.get(key);if(existing&&existing.proc.exitCode===null){existing.lastTouch=Date.now();return key}let input;if(isM3u())input=await sourceUrl('live',key);else{const meta=(await xtream('get_live_streams')).find(x=>String(x.stream_id)===key);if(!meta)throw new Error('Canal inexistente');input=liveUrl(key,meta.container_extension||'ts')}const dir=path.join(HLS_ROOT,key);await fsp.rm(dir,{recursive:true,force:true});await fsp.mkdir(dir,{recursive:true});const output=path.join(dir,'index.m3u8');const args=['-hide_banner','-loglevel','warning','-nostdin','-reconnect','1','-reconnect_streamed','1','-reconnect_delay_max','5','-i',input,'-map','0:v:0?','-map','0:a:0?','-c:v',VIDEO_CODEC];if(VIDEO_CODEC!=='copy')args.push('-preset','veryfast','-tune','zerolatency','-pix_fmt','yuv420p');args.push('-c:a','aac','-b:a','128k','-ac','2','-f','hls','-hls_time','4','-hls_list_size','8','-hls_flags','delete_segments+append_list+independent_segments','-hls_segment_filename',path.join(dir,'seg-%06d.ts'),output);const proc=spawn(FFMPEG,args,{stdio:['ignore','ignore','pipe']});const entry={proc,lastTouch:Date.now(),stderr:''};streams.set(key,entry);proc.stderr.on('data',d=>entry.stderr=(entry.stderr+d.toString()).slice(-3000));proc.on('exit',()=>setTimeout(()=>streams.get(key)?.proc===proc&&streams.delete(key),1000));const deadline=Date.now()+15000;while(Date.now()<deadline){if(proc.exitCode!==null)throw new Error(entry.stderr||'FFmpeg terminou inesperadamente');if(fs.existsSync(output))return key;await new Promise(r=>setTimeout(r,250))}proc.kill('SIGTERM');throw new Error('O canal demorou demasiado a iniciar')}
setInterval(()=>{const now=Date.now();for(const [key,s] of streams)if(now-s.lastTouch>IDLE_SECONDS*1000){s.proc.kill('SIGTERM');streams.delete(key)}},15000).unref();

async function serveFile(res,file,cache='public, max-age=300'){const stat=await fsp.stat(file);if(!stat.isFile())throw new Error('not file');res.writeHead(200,{'content-type':MIME[path.extname(file)]||'application/octet-stream','content-length':stat.size,'cache-control':cache,'x-content-type-options':'nosniff'});fs.createReadStream(file).pipe(res)}
// Only the page's own files are served (the folder also holds the server code).
const PUBLIC_FILES=new Set(['index.html','styles.css','sw.js','manifest.webmanifest','icon.svg']);
async function route(req,res){const u=new URL(req.url,'http://localhost');
if(u.pathname==='/tesla'){const page=`<!doctype html>
<html lang="pt-PT">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#0E0F0C">
<title>Lumina Atlas — Tesla Theater</title>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Bricolage+Grotesque:opsz,wght@12..96,800&family=Manrope:wght@600..800&display=swap">
<style>
html,body{margin:0;min-height:100%;background:#0E0F0C;color:#EEF0E8;font-family:Manrope,system-ui,sans-serif}
main{min-height:100vh;display:grid;place-items:center;padding:32px;box-sizing:border-box}
section{width:min(620px,100%);display:grid;gap:12px}
.tile{padding:30px 30px 26px;border-radius:32px;background:#2F4A14;color:#E2FBBF}
small{font-weight:600;opacity:.72;font-size:15px}
h1{font-family:'Bricolage Grotesque',Manrope,sans-serif;font-weight:800;font-size:52px;letter-spacing:-1.8px;line-height:.95;margin:12px 0 16px}
p{margin:0;font-size:19px;line-height:1.5;font-weight:600;opacity:.82}
button{height:64px;border:0;border-radius:999px;background:#C8F08F;color:#121410;font:800 19px Manrope,sans-serif;cursor:pointer}
</style>
</head>
<body>
<main><section>
<div class="tile"><small>Lumina Atlas</small>
<h1>Favorito<br>Tesla Theater</h1>
<p id="message">Guarda agora esta página nos favoritos do Tesla. Depois toca em “Ativar Theater”. Nas próximas visitas pelo favorito, o modo Theater abrirá automaticamente.</p></div>
<button id="activate">Ativar Theater</button>
</section></main>
<script>
const key='lumina_atlas_theater_favorite';

if(new URLSearchParams(location.search).has('reset')){
  localStorage.removeItem(key);
}

if(localStorage.getItem(key)==='1'){
  location.replace('/theater');
}

document.getElementById('activate').onclick=()=>{
  localStorage.setItem(key,'1');
  location.href='/theater';
};
</script>
</body>
</html>`;return send(res,200,page,{'content-type':'text/html; charset=utf-8','cache-control':'no-store'})}if(u.pathname==='/theater'){if(!PUBLIC_BASE_URL)return send(res,503,'Configura PUBLIC_BASE_URL para usar Tesla Theater');const destination=`${PUBLIC_BASE_URL}/?theater=1`;const youtube='https://www.youtube.com/redirect?q='+encodeURIComponent(destination);res.writeHead(302,{'location':youtube,'cache-control':'no-store'});return res.end()}if(u.pathname==='/health')return json(res,200,{ok:true,service:'lumina-atlas',streams:streams.size});if(u.pathname==='/api/login'&&req.method==='POST'){if(loginLocked(Date.now()))return json(res,429,{error:'Demasiadas tentativas. Tenta mais tarde.'});const b=await bodyJson(req);if(!safeEqual(b.pin||'',APP_PIN)){loginFailures.push(Date.now());return json(res,401,{error:'PIN incorreto'})}const cookie=`lumina_auto_session=${encodeURIComponent(makeSession())}; Path=/; HttpOnly; ${COOKIE_SECURE?'Secure; ':''}SameSite=Strict; Max-Age=${SESSION_DAYS*86400}`;return json(res,200,{ok:true},{'set-cookie':cookie})}if(u.pathname==='/api/session')return validSession(req)?json(res,200,{ok:true}):json(res,401,{error:'Sessão necessária'});if(u.pathname.startsWith('/api/')&&!validSession(req))return json(res,401,{error:'Sessão necessária'});
  if(u.pathname==='/api/provider'&&req.method==='GET'){
    const host=XTREAM_BASE?new URL(XTREAM_BASE).host:provider?.url?new URL(provider.url).host:provider?.file?'ficheiro':'';
    return json(res,200,{configured:providerReady(provider),type:provider?.type||'',host});
  }
  const providerError=error=>/URL IPTV|username e password|endereço HTTP|M3U/.test(error.message);
  if(u.pathname==='/api/provider/playlist'&&req.method==='POST'){
    if(process.env.IPTV_URL||process.env.IPTV_URL_FILE)return json(res,409,{error:'O fornecedor está definido no ambiente do servidor'});
    const text=await bodyText(req,PLAYLIST_MAX).catch(error=>{if(error.status===413)return null;throw error});
    if(text===null)return json(res,413,{error:'Lista M3U demasiado grande (máximo 64 MB)'});
    try{await saveProvider(text);return json(res,200,{ok:true})}
    catch(error){if(providerError(error))return json(res,400,{error:error.message});throw error}
  }
  if(u.pathname==='/api/provider'&&req.method==='POST'){
    if(process.env.IPTV_URL||process.env.IPTV_URL_FILE)return json(res,409,{error:'O fornecedor está definido no ambiente do servidor'});
    const data=await bodyJson(req);
    try{await saveProvider(data.url);return json(res,200,{ok:true})}
    catch(error){if(providerError(error))return json(res,400,{error:error.message});throw error}
  }
  if(u.pathname.startsWith('/api/')&&!providerReady(provider))return json(res,409,{error:'Configura um URL IPTV nas definições'});
  if(u.pathname==='/api/catalog')return json(res,200,{...(await channels()),...(await movies()),...(await series())});
  let logoMatch=u.pathname.match(/^\/api\/logo\/live\/(\d+)$/);
  if(logoMatch)return proxyLogo(res,logoMatch[1]);
  let m=u.pathname.match(/^\/api\/epg\/(\d+)$/);if(m)return json(res,200,await epg(m[1]));
  m=u.pathname.match(/^\/api\/movie\/(\d+)$/);if(m)return json(res,200,await movieInfo(m[1]));
  m=u.pathname.match(/^\/api\/series\/(\d+)$/);if(m)return json(res,200,await seriesInfo(m[1]));
  m=u.pathname.match(/^\/api\/subtitles\/(movie|series)\/(\d+)\.([a-z0-9]+)\.vtt$/i);if(m)return serveSubtitle(res,m[1],m[2],m[3]);
  m=u.pathname.match(/^\/api\/vod\/(movie|series)\/(\d+)\.([a-z0-9]+)\/index\.m3u8$/i);if(m){const entry=await ensureVod(m[1],m[2],m[3]);return serveFile(res,path.join(entry.dir,'index.m3u8'),'no-store')}
  m=u.pathname.match(/^\/api\/vod\/(movie|series)\/(\d+)\.([a-z0-9]+)\/seg-(\d+)\.ts$/i);if(m){const entry=await ensureVod(m[1],m[2],m[3]);const file=await ensureVodSegment(entry,Number(m[4]));entry.lastTouch=Date.now();return serveFile(res,file,'private, max-age=600')}
  m=u.pathname.match(/^\/api\/media\/(movie|series)\/(\d+)\.([a-z0-9]+)$/i);if(m)return proxyMedia(req,res,m[1],m[2],m[3]);
  m=u.pathname.match(/^\/api\/stream\/(\d+)\/index\.m3u8$/);if(m){const key=await ensureStream(m[1]);streams.get(key).lastTouch=Date.now();return serveFile(res,path.join(HLS_ROOT,key,'index.m3u8'),'no-store')}
  m=u.pathname.match(/^\/api\/stream\/(\d+)\/(seg-\d+\.ts)$/);if(m){const key=streamKey(m[1]);const s=streams.get(key);if(s)s.lastTouch=Date.now();return serveFile(res,path.join(HLS_ROOT,key,m[2]),'public, max-age=30')}
  const requested=u.pathname==='/'?'index.html':u.pathname.slice(1);if(requested==='app.js')return serveFile(res,path.join(__dirname,'auto-app.js'),'no-store');if(!PUBLIC_FILES.has(requested))return send(res,404,'Not found');const file=path.resolve(WEB_ROOT,requested);if(!file.startsWith(WEB_ROOT+path.sep)&&file!==path.join(WEB_ROOT,'index.html'))return send(res,403,'Forbidden');return serveFile(res,file,requested==='index.html'?'no-store':'public, max-age=3600');
}
http.createServer((req,res)=>route(req,res).catch(err=>{console.error(err);if(!res.headersSent)json(res,err.code==='ENOENT'?404:500,{error:err.message||'Erro interno'});else res.destroy()})).listen(PORT,'0.0.0.0',()=>console.log(`Lumina Atlas em http://0.0.0.0:${PORT}`));
process.on('SIGTERM',()=>{for(const s of streams.values())s.proc.kill('SIGTERM');for(const proc of vodProcesses)proc.kill('SIGTERM');process.exit(0)});
