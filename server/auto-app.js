/* Lumina Atlas — the browser app (served as /app.js). Same catalogue, playback and session as Lumina Auto;
   the screens follow the Atlas app: a "watch again" hero and big-number tiles on the home, rows of posters,
   segmented controls for sections and genres, Atlas detail and search sheets. Styles in styles.css. */
(function(){
  'use strict';
  const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];
  const state={view:'home',channels:[],movies:[],series:[],hls:null,liveSection:'Generalistas',genre:{movie:null,series:null},limit:{movie:240,series:240}};
  const store={get(k,f=[]){try{return JSON.parse(localStorage.getItem('lumina_atlas_'+k))??f}catch{return f}},set(k,v){try{localStorage.setItem('lumina_atlas_'+k,JSON.stringify(v))}catch{}}};
  const favs=()=>new Set(store.get('favorites',[]));
  const esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon=id=>`<svg aria-hidden="true"><use href="#i-${id}"/></svg>`;
  const number=n=>Number(n||0).toLocaleString('pt-PT');
  const CHANNEL_SECTIONS=['Generalistas','Desporto','Filmes'];

  // --- Portuguese channel sections (the provider marks them with ████ header rows) ------------------
  function normalise(value){return String(value||'').normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase()}
  function sectionMarker(name){
    const raw=String(name||'');
    if(!/[█■▓▒░▉▊▌]/.test(raw))return {header:false,section:null};
    const value=normalise(raw).replace(/[█■▓▒░▉▊▌]/g,' ').replace(/\s+/g,' ').trim();
    if(value.includes('generalista'))return {header:true,section:'Generalistas'};
    if(value.includes('desporto')||value.includes('sport'))return {header:true,section:'Desporto'};
    if(value.includes('cinema')||value.includes('filme')||value.includes('serie'))return {header:true,section:'Filmes'};
    return {header:true,section:null};
  }
  function organisePortugueseChannels(channels){
    let section=null, previousGroup=null;
    const result=[];
    for(const channel of channels){
      const group=normalise(channel.group||'');
      if(previousGroup!==null&&group!==previousGroup)section=null;
      previousGroup=group;
      const marker=sectionMarker(channel.name||channel.title);
      if(marker.header){section=marker.section;continue}
      if(section)result.push({...channel,_luminaSection:section});
    }
    return result;
  }

  // --- Genres for films and series ------------------------------------------------------------------
  const CONTENT_CATEGORIES=['Ação','Comédia','Drama','Terror','Romance','Guerra','Crime','Thriller/Suspense','Ficção científica','Fantasia','Aventura','Animação/Família','Documentários','Western','Outros'];
  const GENRE_RULES=[
    ['Terror',['terror','horror','slasher','sobrenatural','supernatural','zombie','vampire']],
    ['Romance',['romance','romantic','romantico','romantica']],
    ['Guerra',['guerra','war','militar','military','segunda guerra','world war']],
    ['Ficção científica',['ficcao cientifica','science fiction','sci-fi','scifi','espaco','space']],
    ['Thriller/Suspense',['thriller','suspense','misterio','mystery']],
    ['Crime',['crime','policial','mafia','gangster','detective']],
    ['Animação/Família',['animacao','animation','anime','familia','family','infantil','kids','criancas']],
    ['Documentários',['documentario','documentary','documentaries','biografia','biography']],
    ['Comédia',['comedia','comedy','humor','stand-up','stand up']],
    ['Ação',['acao','action','super-heroi','superhero','artes marciais']],
    ['Aventura',['aventura','adventure']],
    ['Fantasia',['fantasia','fantasy','magia','magic']],
    ['Western',['western','faroeste','cowboy']],
    ['Drama',['drama','dramatico','dramatic']]
  ];
  function contentCategory(item){
    if(item._genre)return item._genre;
    const value=normalise([item.genre,item.category,item.name,item.title].filter(Boolean).join(' '));
    for(const [category,words] of GENRE_RULES)if(words.some(word=>value.includes(word)))return (item._genre=category);
    return (item._genre='Outros');
  }

  // --- Basics ---------------------------------------------------------------------------------------
  async function api(path,opts={}){const r=await fetch(path,{credentials:'same-origin',cache:'no-store',...opts,headers:{'content-type':'application/json',...(opts.headers||{})}});const j=await r.json().catch(()=>({}));if(!r.ok){const e=new Error(j.error||`HTTP ${r.status}`);e.status=r.status;throw e}return j}
  function toast(s){const el=$('#toast');el.textContent=s;el.classList.add('show');clearTimeout(el._t);el._t=setTimeout(()=>el.classList.remove('show'),2600)}
  function busy(v){$('#loading').classList.toggle('hidden',!v);$('#grid').classList.toggle('hidden',v)}
  function setStatus(text,online){$('#status').classList.toggle('online',online);$('#status span').textContent=text}
  function loginScreen(message){
    $('#grid').innerHTML='';$('#tabs').classList.add('hidden');$('#empty').classList.remove('hidden');
    $('#empty .welcome-tile p').textContent=message||'Introduz o PIN privado para entrar.';
    $('#viewTitle').textContent='Bem-vindo';setStatus('Protegida por PIN',false);
  }
  async function bootstrap(){
    busy(true);
    try{
      await api('/api/session');
      const data=await api('/api/catalog');
      state.channels=organisePortugueseChannels(data.channels||[]);
      state.movies=data.movies||[];state.series=data.series||[];
      $('#empty').classList.add('hidden');
      setStatus(`Ligada ao servidor · ${number(state.channels.length)} canais`,true);
      render();
    }catch(e){loginScreen(e.status===401?undefined:e.message)}
    finally{busy(false)}
  }

  function kindFor(x){return x.streamId!==undefined?(String(x.url||'').includes('/stream/')?'live':'movie'):'series'}
  function idOf(x){return x.streamId??x.seriesId??x.id}
  function key(x,k=kindFor(x)){return k+':'+idOf(x)}
  function imageOf(x){return x.logo||x.poster||''}

  function card(x,k){
    const on=favs().has(key(x,k)), title=x.name||'Sem título', image=imageOf(x);
    const sub=k==='live'?'<span class="live-dot">Direto</span>':esc(k==='movie'?(x.genre||x.category||'Filme'):(x.category||x.genre||'Série'));
    return `<article class="card ${k==='live'?'live':''}" tabindex="0" data-kind="${k}" data-id="${esc(idOf(x))}">
      <div class="poster">${image?`<img src="${esc(image)}" alt="" loading="lazy" onerror="this.replaceWith(Object.assign(document.createElement('div'),{className:'fallback',textContent:'L'}))">`:'<div class="fallback">L</div>'}</div>
      <button class="fav ${on?'on':''}" data-fav="${esc(key(x,k))}" aria-label="Favorito">${icon(on?'star-fill':'star')}</button>
      <div class="card-copy"><strong>${esc(title)}</strong><small>${sub}</small></div>
    </article>`;
  }

  // --- Watch again (this browser) -----------------------------------------------------------------
  function remember(entry){
    if(!entry||!entry.url)return;
    const list=store.get('recent',[]).filter(x=>x.url!==entry.url);
    list.unshift({...entry,at:Date.now()});
    store.set('recent',list.slice(0,16));
  }
  function ago(at){const m=Math.round((Date.now()-at)/60000);if(m<1)return 'agora';if(m<60)return `há ${m} min`;const h=Math.round(m/60);return h<24?`há ${h} h`:`há ${Math.round(h/24)} dias`}

  // --- Views ---------------------------------------------------------------------------------------
  const TITLES={home:'Início',live:'Direto',movies:'Filmes',series:'Séries',favorites:'Favoritos'};
  function tabsHTML(items,current,attr){return items.map(([value,label,count])=>`<button class="seg ${value===current?'active':''}" ${attr}="${esc(value)}">${esc(label)}${count!==undefined?`<small>${number(count)}</small>`:''}</button>`).join('')}
  function emptyNote(title,text=''){return `<div class="empty-note"><h2>${esc(title)}</h2>${text?`<p>${esc(text)}</p>`:''}</div>`}

  function renderHome(grid){
    const recent=store.get('recent',[]);
    const last=recent[0];
    const hero=last?`
      <button class="hero" data-replay="0">
        <div class="hero-art ${last.kind==='live'?'':'poster-art'}">${last.image?`<img src="${esc(last.image)}" alt="">`:''}</div>
        <div><small>Voltar a ver · ${esc(ago(last.at))}</small><strong>${esc(last.title)}</strong><span>${esc(last.sub||'')}</span></div>
        <span class="round">${icon('play')}</span>
      </button>`:`
      <button class="hero empty-hero" data-view="live">
        <div><small>Comece pelo direto</small><strong>Televisão<br>portuguesa</strong><span>${number(state.channels.length)} canais em direto</span></div>
        <span class="round">${icon('play')}</span>
      </button>`;
    const stats=`<div class="stats">
      <button class="stat ink" data-view="live"><small>Canais</small><strong>${number(state.channels.length)}</strong></button>
      <button class="stat amber" data-view="movies"><small>Filmes</small><strong>${number(state.movies.length)}</strong></button>
      <button class="stat violet" data-view="series"><small>Séries</small><strong>${number(state.series.length)}</strong></button>
      <button class="stat coral" data-view="favorites"><small>Favoritos</small><strong>${number(favs().size)}</strong></button>
    </div>`;
    const row=(title,view,cards)=>cards?`<section><div class="row-head"><h2>${title}</h2>${view?`<button data-view="${view}">Ver tudo</button>`:''}</div><div class="row">${cards}</div></section>`:'';
    const channels=state.channels.filter(c=>c._luminaSection==='Generalistas').slice(0,16);
    grid.className='home';
    grid.innerHTML=`<div class="home-top">${hero}${stats}</div>
      ${row('Em direto','live',channels.map(c=>card(c,'live')).join(''))}
      ${row('Filmes novos','movies',state.movies.slice(0,20).map(m=>card(m,'movie')).join(''))}
      ${row('Séries','series',state.series.slice(0,20).map(s=>card(s,'series')).join(''))}`;
  }

  function renderLive(grid,tabs){
    const counts=CHANNEL_SECTIONS.map(s=>[s,s,state.channels.filter(c=>c._luminaSection===s).length]).filter(x=>x[2]);
    if(!counts.some(([s])=>s===state.liveSection)&&counts.length)state.liveSection=counts[0][0];
    tabs.innerHTML=tabsHTML(counts,state.liveSection,'data-section');tabs.classList.toggle('hidden',!counts.length);
    const channels=state.channels.filter(c=>c._luminaSection===state.liveSection);
    grid.className='grid live-grid';
    grid.innerHTML=channels.length?channels.map(c=>card(c,'live')).join(''):emptyNote('Sem canais portugueses','Não foram encontrados os separadores da lista portuguesa.');
  }

  function renderContent(grid,tabs,kind){
    const items=kind==='movie'?state.movies:state.series;
    const counts=CONTENT_CATEGORIES.map(c=>[c,c,items.filter(i=>contentCategory(i)===c).length]).filter(x=>x[2]);
    if(!counts.some(([c])=>c===state.genre[kind]))state.genre[kind]=counts[0]?.[0]||null;
    tabs.innerHTML=tabsHTML(counts,state.genre[kind],'data-genre');tabs.classList.toggle('hidden',!counts.length);
    const list=items.filter(i=>contentCategory(i)===state.genre[kind]);
    const shown=list.slice(0,state.limit[kind]);
    grid.className='grid';
    grid.innerHTML=list.length?shown.map(i=>card(i,kind)).join('')+(list.length>shown.length?`<div class="empty-note" style="grid-column:1/-1"><button class="pill soft" data-more="${kind}">Mostrar mais ${number(Math.min(240,list.length-shown.length))}</button></div>`:''):emptyNote('Nada para mostrar');
  }

  function renderFavorites(grid){
    const f=favs();
    const live=state.channels.filter(c=>f.has(key(c,'live')));
    const vod=[...state.movies.map(x=>[x,'movie']),...state.series.map(x=>[x,'series'])].filter(([x,k])=>f.has(key(x,k)));
    grid.className='home';
    grid.innerHTML=!live.length&&!vod.length?emptyNote('Ainda sem favoritos','Toca na estrela de um canal, filme ou série.'):
      `${live.length?`<section><div class="row-head"><h2>Canais</h2></div><div class="grid live-grid">${live.map(c=>card(c,'live')).join('')}</div></section>`:''}
       ${vod.length?`<section><div class="row-head"><h2>Filmes e séries</h2></div><div class="grid">${vod.map(([x,k])=>card(x,k)).join('')}</div></section>`:''}`;
  }

  function render(){
    $('#viewTitle').textContent=TITLES[state.view];
    $$('.dock-btn').forEach(b=>b.classList.toggle('active',b.dataset.view===state.view));
    const grid=$('#grid'), tabs=$('#tabs');
    tabs.classList.add('hidden');
    if(state.view==='home')renderHome(grid);
    else if(state.view==='live')renderLive(grid,tabs);
    else if(state.view==='movies')renderContent(grid,tabs,'movie');
    else if(state.view==='series')renderContent(grid,tabs,'series');
    else renderFavorites(grid);
  }
  function go(view){state.view=view;render();window.scrollTo({top:0})}

  // --- Detail --------------------------------------------------------------------------------------
  function item(kind,id){id=String(id);return kind==='live'?state.channels.find(x=>String(x.streamId)===id):kind==='movie'?state.movies.find(x=>String(x.streamId)===id):state.series.find(x=>String(x.seriesId??x.id)===id)}
  async function openItem(kind,id){
    const x=item(kind,id);if(!x)return;
    if(kind==='live')return play(x.url,x.name,'Em direto','',{kind:'live',image:x.logo,sub:'Em direto'});
    const info=await api(kind==='movie'?`/api/movie/${x.streamId}`:`/api/series/${x.seriesId??x.id}`).catch(()=>({}));
    showDetail(x,kind,info);
  }
  function showDetail(x,kind,info){
    const backdrop=info.backdrop||x.poster||'', on=favs().has(key(x,kind));
    const seasons=Object.entries(info.seasons||{});
    const meta=[info.genre||x.genre||x.category,info.releaseDate&&String(info.releaseDate).slice(0,4),info.duration].filter(Boolean).join(' · ');
    $('#detailBody').innerHTML=`
      <div class="detail-hero ${kind==='series'?'series':''}">
        ${backdrop?`<div class="detail-backdrop" style="background-image:url('${esc(backdrop)}')"></div>`:''}
        <div class="detail-poster">${x.poster?`<img src="${esc(x.poster)}" alt="">`:''}</div>
        <div class="detail-copy">
          <small class="kicker">${esc(meta||(kind==='movie'?'Filme':'Série'))}</small>
          <h2>${esc(x.name)}</h2>
          <p>${esc(info.plot||x.plot||'')}</p>
          <div class="detail-buttons">
            ${kind==='movie'?`<button class="pill ink" data-play="${esc(x.url)}" data-title="${esc(x.name)}" data-sub="${esc(meta||'Filme')}" data-kind="movie" data-image="${esc(x.poster||'')}" data-subtitle="${esc(subtitleUrl(x.url))}">${icon('play')}Reproduzir</button>`:''}
            <button class="pill soft" data-togglefav="${esc(key(x,kind))}">${icon(on?'star-fill':'star')}${on?'Nos favoritos':'Favorito'}</button>
          </div>
        </div>
      </div>
      ${seasons.length?`<div class="episodes"><div class="segmented" id="seasonTabs">${seasons.map(([s],i)=>`<button class="seg ${i===0?'active':''}" data-season="${esc(s)}">Temporada ${esc(s)}</button>`).join('')}</div><div id="episodeList"></div></div>`:''}`;
    if(seasons.length){
      const show=season=>{
        const list=(info.seasons[season]||[]);
        // Providers name episodes "Series (2026) - S01E02 - Title": keep the title.
        const clean=(t,n)=>{const raw=String(t||''),rest=raw.replace(/^.*?\bS\d{1,2}\s*E\d{1,3}\b\s*[-–:]?\s*/i,'').trim();return rest||(rest===raw?raw:`Episódio ${n}`)};
        $('#episodeList').innerHTML=`<div class="episode-list">${list.map((e,i)=>`<button class="episode" data-play="${esc(e.url)}" data-title="${esc(clean(e.title,e.num||i+1)||x.name)}" data-sub="${esc(`${x.name} · T${season} E${e.num||i+1}`)}" data-kind="series" data-image="${esc(x.poster||'')}" data-subtitle="${esc(subtitleUrl(e.url))}"><b>${e.num||i+1}</b><span>${esc(clean(e.title,e.num||i+1)||'Episódio')}</span><i>${icon('play')}</i></button>`).join('')}</div>`;
        $$('#seasonTabs .seg').forEach(b=>b.classList.toggle('active',b.dataset.season===String(season)));
      };
      $('#seasonTabs').onclick=e=>{const b=e.target.closest('[data-season]');if(b)show(b.dataset.season)};
      show(seasons[0][0]);
    }
    $('#detailDialog').showModal();
    $('#detailDialog').scrollTop=0;
  }

  // --- Subtitles, audio focus, player ---------------------------------------------------------------
  function subtitleUrl(mediaUrl){const m=String(mediaUrl||'').match(/^\/api\/media\/(movie|series)\/(\d+)\.([a-z0-9]+)$/i);return m?`/api/subtitles/${m[1]}/${m[2]}.${m[3]}.vtt`:''}
  async function attachSubtitle(video,url){
    video.querySelectorAll('track').forEach(t=>t.remove());
    if(video._subtitleObjectUrl){URL.revokeObjectURL(video._subtitleObjectUrl);video._subtitleObjectUrl=''}
    if(!url)return false;
    try{
      const response=await fetch(url,{credentials:'same-origin',cache:'force-cache'});
      if(!response.ok)return false;
      const contents=await response.text();
      if(!contents.trim().startsWith('WEBVTT'))return false;
      const objectUrl=URL.createObjectURL(new Blob([contents],{type:'text/vtt'}));
      video._subtitleObjectUrl=objectUrl;
      const track=document.createElement('track');
      Object.assign(track,{kind:'subtitles',label:'Português',srclang:'pt',src:objectUrl,default:true});
      video.appendChild(track);
      track.addEventListener('load',()=>{if(track.track)track.track.mode='showing'});
      return true;
    }catch{return false}
  }
  let audioContext=null, playerAudioSource=null;
  function claimAudioFocus(video,title='Lumina Atlas'){
    video.muted=false;video.defaultMuted=false;video.volume=1;
    video.setAttribute('playsinline','');video.setAttribute('autoplay','');
    try{
      const AudioContextClass=window.AudioContext||window.webkitAudioContext;
      if(AudioContextClass){
        if(!audioContext)audioContext=new AudioContextClass();
        if(!playerAudioSource){playerAudioSource=audioContext.createMediaElementSource(video);playerAudioSource.connect(audioContext.destination)}
        if(audioContext.state==='suspended')audioContext.resume().catch(()=>{});
      }
    }catch(error){console.warn('Web Audio indisponível:',error)}
    try{
      if('mediaSession' in navigator){
        navigator.mediaSession.metadata=new MediaMetadata({title:title||'Lumina Atlas',artist:'Lumina Atlas',album:'Lumina Atlas'});
        navigator.mediaSession.playbackState='playing';
        navigator.mediaSession.setActionHandler('play',()=>{video.play().catch(()=>{})});
        navigator.mediaSession.setActionHandler('pause',()=>{video.pause()});
        navigator.mediaSession.setActionHandler('stop',()=>{closePlayer()});
      }
    }catch(error){console.warn('Media Session indisponível:',error)}
  }
  function releaseAudioFocus(){
    try{if('mediaSession' in navigator)navigator.mediaSession.playbackState='none'}catch{}
    try{if(audioContext&&audioContext.state==='running')audioContext.suspend().catch(()=>{})}catch{}
  }
  function play(url,title,subtitle='',captionUrl='',meta={}){
    const video=$('#player');
    clearTimeout(video._startupTimer);
    remember({url,title,sub:meta.sub||subtitle,kind:meta.kind||'movie',image:meta.image||'',caption:captionUrl});
    claimAudioFocus(video,title);attachSubtitle(video,'');
    video._pendingSubtitle=captionUrl;
    $('#subtitleButton').classList.toggle('hidden',!captionUrl);
    $('#subtitleButton').disabled=false;
    $('#subtitleButton').textContent='CC';
    $('#playerTitle').textContent=title;$('#playerSubtitle').textContent=subtitle;$('#playerNotice').classList.add('hidden');
    if(state.hls){state.hls.destroy();state.hls=null}
    video.removeAttribute('src');video.load();
    if($('#detailDialog').open)$('#detailDialog').close();
    $('#playerDialog').showModal();
    const media=String(url).match(/^\/api\/media\/(movie|series)\/(\d+)\.([a-z0-9]+)$/i);
    const compatibleUrl=media?`/api/vod/${media[1]}/${media[2]}.${media[3]}/index.m3u8`:'';
    let fallback=false;
    const notice=$('#playerNotice');
    const start=source=>{
      if(state.hls){state.hls.destroy();state.hls=null}
      video.removeAttribute('src');video.load();
      const hls=/\.m3u8($|\?)/i.test(source);
      if(hls){notice.textContent='A preparar vídeo compatível…';notice.classList.remove('hidden')}
      if(hls&&window.Hls&&Hls.isSupported()&&!video.canPlayType('application/vnd.apple.mpegurl')){
        state.hls=new Hls({enableWorker:true,lowLatencyMode:false});state.hls.loadSource(source);state.hls.attachMedia(video);
        state.hls.on(Hls.Events.MANIFEST_PARSED,()=>video.play().catch(playerError));
        state.hls.on(Hls.Events.ERROR,(_,d)=>{if(d.fatal)playerError()});
      }else{video.src=source;video.play().catch(error=>{if(error.name!=='AbortError')handleError()})}
    };
    const handleError=()=>{
      if(compatibleUrl&&!fallback){fallback=true;start(compatibleUrl)}
      else playerError();
    };
    video.onerror=handleError;
    video.onplaying=()=>{clearTimeout(video._startupTimer);notice.classList.add('hidden')};
    if(compatibleUrl&&/\.(mkv|avi|mpg)$/i.test(url)){fallback=true;start(compatibleUrl)}
    else{start(url);if(compatibleUrl)video._startupTimer=setTimeout(()=>{if(!fallback&&video.readyState<3)handleError()},20000)}
  }
  async function enterFullscreen(){
    const video=$('#player');
    try{
      if(video.requestFullscreen)await video.requestFullscreen();
      else if(video.webkitRequestFullscreen)await video.webkitRequestFullscreen();
      else if(video.webkitEnterFullscreen)video.webkitEnterFullscreen();
      else toast('O browser do Tesla não disponibilizou o modo de ecrã inteiro.');
    }catch{toast('Toca novamente em Ecrã inteiro com o Tesla estacionado.')}
  }
  function playerError(){const n=$('#playerNotice');n.textContent='Não foi possível iniciar este conteúdo.';n.classList.remove('hidden')}
  function closePlayer(){const v=$('#player');clearTimeout(v._startupTimer);v.pause();v.onerror=null;v.onplaying=null;releaseAudioFocus();v.removeAttribute('src');v.load();if(state.hls){state.hls.destroy();state.hls=null}$('#playerDialog').close();if(state.view==='home')render()}

  // --- Favourites, PIN, search, theme ------------------------------------------------------------------
  function toggleFav(k){
    const f=favs();f.has(k)?f.delete(k):f.add(k);store.set('favorites',[...f]);
    const top=window.scrollY;render();window.scrollTo({top});
    if($('#detailDialog').open){const b=$(`#detailDialog [data-togglefav="${CSS.escape(k)}"]`);if(b){const on=f.has(k);b.innerHTML=`${icon(on?'star-fill':'star')}${on?'Nos favoritos':'Favorito'}`}}
  }
  function openPin(){const token=$('#deviceToken');token.value='';$('#settingsDialog').showModal();token.focus()}
  function doSearch(q){
    q=q.trim().toLocaleLowerCase('pt');
    const all=[...state.channels.map(x=>[x,'live']),...state.movies.map(x=>[x,'movie']),...state.series.map(x=>[x,'series'])];
    const r=q?all.filter(([x])=>(x.name||'').toLocaleLowerCase('pt').includes(q)).slice(0,50):[];
    const label={live:'Canal',movie:'Filme',series:'Série'};
    $('#searchResults').innerHTML=r.map(([x,k])=>`<button type="button" class="result" data-kind="${k}" data-id="${esc(idOf(x))}"><span class="result-art ${k==='live'?'logo':''}">${imageOf(x)?`<img src="${esc(imageOf(x))}" alt="" loading="lazy">`:''}</span><span><strong>${esc(x.name)}</strong><span class="tag ${k}">${label[k]}</span></span></button>`).join('');
  }
  function themeIcon(){$('#themeButton use').setAttribute('href',document.documentElement.dataset.theme==='dark'?'#i-sun':'#i-moon')}
  function toggleTheme(){
    const next=document.documentElement.dataset.theme==='dark'?'light':'dark';
    document.documentElement.dataset.theme=next;
    try{localStorage.setItem('lumina_atlas_theme',next)}catch{}
    document.querySelector('meta[name="theme-color"]').content=next==='dark'?'#0E0F0C':'#F2F3EF';
    themeIcon();
  }

  document.addEventListener('click',e=>{
    const fav=e.target.closest('[data-fav]');
    if(fav){e.stopPropagation();toggleFav(fav.dataset.fav);return}
    const v=e.target.closest('[data-view]')?.dataset.view;if(v)return go(v);
    const section=e.target.closest('[data-section]')?.dataset.section;if(section){state.liveSection=section;return render()}
    const genre=e.target.closest('[data-genre]')?.dataset.genre;if(genre){const k=state.view==='movies'?'movie':'series';state.genre[k]=genre;state.limit[k]=240;render();return window.scrollTo({top:0})}
    const more=e.target.closest('[data-more]')?.dataset.more;if(more){state.limit[more]+=240;const top=window.scrollY;render();return window.scrollTo({top})}
    const replay=e.target.closest('[data-replay]');if(replay){const r=store.get('recent',[])[Number(replay.dataset.replay)];if(r)play(r.url,r.title,r.sub,r.caption||'',r);return}
    const c=e.target.closest('.card');if(c)return openItem(c.dataset.kind,c.dataset.id);
    const p=e.target.closest('[data-play]');if(p)return play(p.dataset.play,p.dataset.title,p.dataset.sub||'',p.dataset.subtitle||'',{kind:p.dataset.kind,image:p.dataset.image,sub:p.dataset.sub});
    const tf=e.target.closest('[data-togglefav]')?.dataset.togglefav;if(tf)toggleFav(tf);
  });
  document.addEventListener('keydown',e=>{if(e.key==='Enter'&&e.target.classList?.contains('card'))openItem(e.target.dataset.kind,e.target.dataset.id)});
  $('#theaterButton').onclick=()=>{location.href='/theater'};
  $('#themeButton').onclick=toggleTheme;themeIcon();
  $('#settingsButton').onclick=openPin;$('#emptySetup').onclick=openPin;
  $('#saveSettings').onclick=async e=>{e.preventDefault();try{await api('/api/login',{method:'POST',body:JSON.stringify({pin:$('#deviceToken').value})});$('#settingsDialog').close();bootstrap()}catch(err){toast(err.status===429?'Demasiadas tentativas. Tenta mais tarde.':err.message)}};
  $('#searchButton').onclick=()=>{$('#searchDialog').showModal();$('#searchInput').focus()};
  $('#searchInput').oninput=e=>doSearch(e.target.value);
  $('#searchResults').onclick=e=>{const r=e.target.closest('.result');if(r){$('#searchDialog').close();openItem(r.dataset.kind,r.dataset.id)}};
  $('#detailDialog .detail-close').onclick=()=>$('#detailDialog').close();
  $('#fullscreenButton').onclick=enterFullscreen;$('#playerClose').onclick=closePlayer;
  $('#audioMode').onclick=()=>{$('#playerDialog').classList.toggle('audio-only')};
  $('#subtitleButton').onclick=async()=>{
    const video=$('#player'),button=$('#subtitleButton');
    if(!video._pendingSubtitle)return;
    button.disabled=true;
    const wasPlaying=!video.paused;
    video.pause();
    const loaded=await attachSubtitle(video,video._pendingSubtitle);
    button.textContent=loaded?'CC ✓':'CC';
    button.disabled=false;
    toast(loaded?'Legendas carregadas.':'Não há legendas disponíveis para este vídeo.');
    if(wasPlaying)video.play().catch(()=>{});
  };
  $('#playerDialog').addEventListener('cancel',e=>{e.preventDefault();closePlayer()});
  bootstrap();
})();
