(function(){
  'use strict';
  const D=window.PrototypeData;
  const page=decodeURIComponent(location.pathname.split('/').pop());
  const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const money=n=>'¥'+n.toLocaleString('zh-CN');
  const root=()=>document.getElementById('root');
  const shell=(title,back,content,footer='')=>`<div class="v2-page theme-app"><header class="v2-header"><a href="${back}" aria-label="返回">‹</a><h1>${title}</h1><small>概念原型</small></header><main class="v2-content">${content}</main>${footer}</div>`;
  const unavailable=(title,back)=>{root().innerHTML=shell('内容暂不可用',back,`<div class="v2-card"><h2>${title}</h2><p>请从入口重新选择，当前链接未对应到有效的示例内容。</p><a class="v2-button" href="${back}">返回重新选择</a></div>`)};
  const slides=[
    {tag:'热门精选 · 赛事报名',title:'小小飞行家<br>挑战大舞台',desc:'青少年无人机编程挑战赛<br>东营站 · 8–15 岁',button:'查看赛事 · 去报名',href:'ActDetail.html',img:'theme-assets/hero-world.png',alt:'无人机与机器人探索场景',bg:'#e9f5ff'},
    {tag:'周末精选 · 研学报名',title:'把好奇心<br>变成创造力',desc:'机器人研学体验<br>动手编程 · 避障闯关',button:'查看研学 · 去报名',href:'ActStudyRobot.html',img:'theme-assets/robot-workshop.png',alt:'孩子们在明亮课堂探索机器人',bg:'#fff3df'},
    {tag:'趣玩挑战 · 随时开玩',title:'动动脑筋<br>一起闯关',desc:'机器人迷宫 · 无人机寻星<br>虚拟小游戏 · 无需设备',button:'开始趣玩挑战',href:'Games.html',img:'theme-assets/robot-rover.png',alt:'可爱的编程机器人',bg:'#eee6fa'},
    {tag:'体验精选 · 先试再选',title:'先体验<br>再决定',desc:'线上虚拟仿真 · 录播演示<br>也可预约线下场馆',button:'探索体验 · 去预约',href:'ExpCenter.html',img:'theme-assets/hero-world.png',alt:'机器人与无人机的探索乐园',bg:'#e3f7ee'}
  ];
  let carouselIndex=0,carouselTimer=null,userPaused=matchMedia('(prefers-reduced-motion: reduce)').matches;
  function initCarousel(){
    const host=root()?.querySelector('[data-carousel]');
    if(!host||host.dataset.ready)return;
    clearInterval(carouselTimer);host.dataset.ready='true';
    host.innerHTML='<div class="v2-slide-holder"></div><div class="v2-carousel-bar"><span class="v2-carousel-note">运营精选 · 概念内容</span><div class="v2-carousel-controls"><button type="button" data-prev aria-label="上一张">‹</button>'+slides.map((s,i)=>`<button type="button" data-dot="${i}" aria-label="第 ${i+1} 张：${s.tag}" aria-pressed="false"></button>`).join('')+'<button type="button" data-next aria-label="下一张">›</button><button type="button" data-pause></button></div></div>';
    let hover=false,start=null,dragged=false;
    const paint=()=>{
      const s=slides[carouselIndex];
      host.querySelector('.v2-slide-holder').innerHTML=`<a class="v2-slide" data-slide="${carouselIndex}" href="${s.href}" style="--banner-bg:${s.bg}" aria-label="${s.button}：${s.tag}"><img src="${s.img}" alt="${s.alt}" draggable="false"><div class="v2-banner-copy"><small>${s.tag}</small><h2>${s.title}</h2><p>${s.desc}</p><span class="v2-banner-cta">${s.button} <span aria-hidden="true">&nbsp; →</span></span></div></a>`;
      host.querySelectorAll('[data-dot]').forEach(el=>el.setAttribute('aria-pressed',String(Number(el.dataset.dot)===carouselIndex)));
      host.querySelector('[data-pause]').textContent=userPaused?'▶':'Ⅱ';
      host.querySelector('[data-pause]').setAttribute('aria-label',userPaused?'播放轮播':'暂停轮播');
    };
    const move=delta=>{carouselIndex=(carouselIndex+delta+slides.length)%slides.length;paint()};
    host.addEventListener('click',e=>{
      if(dragged){e.preventDefault();dragged=false;return}
      const el=e.target.closest('button');if(!el)return;
      if(el.hasAttribute('data-dot')){carouselIndex=Number(el.dataset.dot);paint()}
      else if(el.hasAttribute('data-prev'))move(-1);
      else if(el.hasAttribute('data-next'))move(1);
      else if(el.hasAttribute('data-pause')){userPaused=!userPaused;paint()}
    });
    host.addEventListener('mouseenter',()=>hover=true);host.addEventListener('mouseleave',()=>hover=false);
    // Native link dragging would swallow pointerup and prevent a carousel swipe.
    host.addEventListener('dragstart',e=>e.preventDefault());
    host.addEventListener('keydown',e=>{if(['ArrowLeft','ArrowRight'].includes(e.key)){e.preventDefault();move(e.key==='ArrowRight'?1:-1)}});
    host.addEventListener('pointerdown',e=>{dragged=false;start={x:e.clientX,y:e.clientY}});
    host.addEventListener('pointerup',e=>{if(!start)return;const dx=e.clientX-start.x,dy=e.clientY-start.y;start=null;if(Math.abs(dx)>45&&Math.abs(dx)>Math.abs(dy)){dragged=true;move(dx<0?1:-1);setTimeout(()=>dragged=false,350)}});
    host.addEventListener('pointercancel',()=>{start=null});
    carouselTimer=setInterval(()=>{if(host.isConnected&&!userPaused&&!hover&&!document.hidden&&!host.contains(document.activeElement)&&!start)move(1)},5500);
    paint();
  }
  function renderGames(){
    root().innerHTML=shell('趣玩挑战','ExpCenter.html',`<section class="v2-game-intro"><span class="v2-tag">PLAY & EXPLORE</span><h2>小小挑战<br>大大好奇心</h2><p>规划路线、收集星星<br>和机器人、无人机一起闯关</p><img src="theme-assets/robot-rover.png" alt="趣玩机器人"></section><p class="v2-note">虚拟小游戏 · 无需登录 · 不连接真实设备</p>${Object.values(D.games).map(g=>`<a class="v2-game-card ${g.tone}" href="GamePlay.html?game=${g.id}"><div class="v2-game-cover"><img src="${g.img}" alt="${g.title}"><span class="v2-tag">${g.avatar==='robot'?'逻辑启蒙 · 避障闯关':'路线规划 · 寻星挑战'}</span></div><div class="v2-game-body"><div><h3>${g.title}</h3><p>${g.subtitle}</p></div><span class="v2-play-pill">开始玩 →</span></div></a>`).join('')}<div class="v2-card"><h3>想把挑战搬到现实？</h3><p>在老师陪伴下，试试真正的机器人编程。</p><a class="v2-button secondary" href="ActStudyRobot.html">看看机器人研学</a></div>`);
  }
  function renderGame(){
    const g=D.games[D.param('game')];if(!g){unavailable('没有找到这个关卡','Games.html');return}
    let pos=0,moves=0,collected=new Set(),started=0,elapsed=0,won=false;
    root().innerHTML=shell(g.title,'Games.html',`<span class="v2-tag">虚拟闯关 · 不连接真实设备</span><div class="v2-score"><div><strong data-score="stars">0 / 3</strong><small>收集星星</small></div><div><strong data-score="moves">0</strong><small>移动步数</small></div><div><strong data-score="time">00:00</strong><small>本局用时</small></div></div><div class="v2-board ${g.tone}" role="group" tabindex="0" aria-label="5 行 5 列游戏地图，可用方向键移动"></div><p class="v2-game-status" role="status" aria-live="polite">收集 3 颗星星，再到达右下角终点。深色格是障碍。</p><div class="v2-pad" aria-label="方向控制"><button data-move="up" aria-label="向上">↑</button><button data-move="left" aria-label="向左">←</button><button data-move="down" aria-label="向下">↓</button><button data-move="right" aria-label="向右">→</button></div><div class="v2-success" hidden tabindex="-1"><h2>挑战成功！</h2><p data-result></p><p>路线由你规划，星星都收集到了。</p></div><p class="v2-note">点方向按钮或使用键盘方向键移动。成绩仅在本局显示，不上传。</p>`,`<footer class="v2-actions"><a class="v2-button secondary" href="Games.html">换个挑战</a><button type="button" class="v2-button violet" data-restart>重新开始</button></footer>`);
    const board=root().querySelector('.v2-board'),status=root().querySelector('[role="status"]');
    const avatar=g.avatar==='robot'?'<img src="theme-assets/robot-rover.png" alt="机器人当前位置">':'<svg viewBox="0 0 60 60" aria-label="无人机当前位置" role="img"><g fill="#e9f5ff" stroke="#4486a4" stroke-width="3"><circle cx="13" cy="13" r="10"/><circle cx="47" cy="13" r="10"/><circle cx="13" cy="47" r="10"/><circle cx="47" cy="47" r="10"/><path d="M14 14l32 32M46 14L14 46"/><rect x="22" y="22" width="16" height="16" rx="5" fill="#ff9f56"/></g></svg>';
    const draw=()=>{
      board.innerHTML=Array.from({length:25},(_,i)=>`<div class="v2-cell${g.walls.includes(i)?' wall':''}${i===g.goal?' goal':''}${i===pos?' current':''}" data-cell="${i}" aria-label="第 ${Math.floor(i/5)+1} 行第 ${i%5+1} 列${g.walls.includes(i)?'，障碍':i===g.goal?'，终点':''}${i===pos?'，当前位置':''}">${i===pos?avatar:g.walls.includes(i)?'◆':g.stars.includes(i)&&!collected.has(i)?'★':i===g.goal?'终点':''}</div>`).join('');
      root().querySelector('[data-score="stars"]').textContent=collected.size+' / 3';root().querySelector('[data-score="moves"]').textContent=moves;
      root().querySelectorAll('[data-move]').forEach(b=>b.disabled=won);
    };
    const clock=()=>{const secs=started?Math.floor(((won?elapsed:performance.now())-started)/1000):0;root().querySelector('[data-score="time"]').textContent=String(Math.floor(secs/60)).padStart(2,'0')+':'+String(secs%60).padStart(2,'0')};
    const timer=setInterval(clock,300);window.addEventListener('pagehide',()=>clearInterval(timer),{once:true});
    const move=dir=>{
      if(won)return;
      const dx={left:-1,right:1,up:0,down:0}[dir],dy={left:0,right:0,up:-1,down:1}[dir];
      const x=pos%5+dx,y=Math.floor(pos/5)+dy,next=y*5+x;
      if(x<0||x>=5||y<0||y>=5){status.textContent='到边界啦，试试其他方向。';return}
      if(g.walls.includes(next)){status.textContent='前方有障碍，换条路线吧！';return}
      if(!started)started=performance.now();pos=next;moves++;
      const fresh=g.stars.includes(pos)&&!collected.has(pos);if(fresh)collected.add(pos);
      status.textContent=fresh?'找到一颗星星！已收集 '+collected.size+' / 3。':'继续规划路线，收集沿途的星星。';
      if(pos===g.goal){
        if(collected.size===g.stars.length){won=true;elapsed=performance.now();clock();const result=root().querySelector('.v2-success');result.hidden=false;result.querySelector('[data-result]').textContent='3 颗星星 · '+moves+' 步 · '+root().querySelector('[data-score="time"]').textContent;status.textContent='全部星星收集完成，成功到达终点！';result.focus({preventScroll:true});result.scrollIntoView({behavior:'auto',block:'nearest'})}
        else status.textContent='到达终点啦，还差 '+(3-collected.size)+' 颗星星。回去找找吧！';
      }
      draw();
    };
    root().querySelectorAll('[data-move]').forEach(b=>b.addEventListener('click',()=>move(b.dataset.move)));
    document.addEventListener('keydown',e=>{const dirs={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right'};if(dirs[e.key]&&!/INPUT|TEXTAREA|SELECT/.test(e.target.tagName)){e.preventDefault();move(dirs[e.key])}});
    root().querySelector('[data-restart]').addEventListener('click',()=>{pos=0;moves=0;collected=new Set();started=0;elapsed=0;won=false;root().querySelector('.v2-success').hidden=true;status.textContent='新的挑战开始！收集 3 颗星星，再到达终点。';draw();clock();board.focus({preventScroll:true});board.scrollIntoView({block:'nearest'})});
    draw();
  }
  function renderCourse(){
    if(D.param('course')!=='flight-basics'){unavailable('没有找到这门试听课程','ExpCenter.html');return}
    let lesson=0,step=0;
    const chapters=[{title:'第 1 课 · 无人机为什么能飞',steps:[['先认识四个旋翼','旋翼转动带动空气向下流动，机体获得向上的推力。'],['让推力与重力平衡','稳定悬停时，向上的总推力与向下的重力大致平衡。'],['推力变化，运动也变化','总推力增大，飞行器开始上升；减小则下降。实际飞行仍需专业指导。']]},{title:'第 2 课 · 起降与悬停',steps:[['起飞前先观察','确认周围没有人员和障碍物，按设备要求完成检查。'],['理解悬停控制','飞控会持续调整旋翼转速，帮助维持姿态和位置。'],['把知识放进模拟任务','进入无人机起降与悬停，体验图形化任务流程。']]}];
    root().innerHTML=shell('无人机飞行原理（试听）','ExpCenter.html',`<span class="v2-tag">图文试学预览 · 录播素材待接入</span><div class="v2-lessons">${chapters.map((c,i)=>`<button data-lesson="${i}" aria-pressed="false">${c.title.split(' · ')[0]} · ${i?'起降与悬停':'飞行原理'}</button>`).join('')}</div><section class="v2-course-stage"><strong data-chapter></strong><img src="_blob/b41e3fb34601d5b9b3c9e40e84d2a489.jpg" alt="无人机课程场景"></section><div class="v2-card"><span class="v2-tag" data-progress></span><h2 data-heading style="margin-top:12px"></h2><p data-copy></p></div><div style="display:flex;gap:8px"><button class="v2-button secondary" data-previous style="flex:1">上一步</button><button class="v2-button" data-next-step style="flex:1">下一步</button></div><p class="v2-note">当前为课程内容与操作预览，未提供录播音视频，不是实机飞行指导。</p>`,`<footer class="v2-actions"><a class="v2-button secondary" href="CourseFly.html">课程详情</a><a class="v2-button" href="ExpSim.html?experience=drone-hover">试试无人机仿真</a></footer>`);
    const draw=()=>{const c=chapters[lesson];root().querySelector('[data-chapter]').textContent=c.title;root().querySelector('[data-progress]').textContent=(step+1)+' / 3';root().querySelector('[data-heading]').textContent=c.steps[step][0];root().querySelector('[data-copy]').textContent=c.steps[step][1];root().querySelectorAll('[data-lesson]').forEach(b=>b.setAttribute('aria-pressed',String(Number(b.dataset.lesson)===lesson)));root().querySelector('[data-previous]').disabled=lesson===0&&step===0;root().querySelector('[data-next-step]').textContent=lesson===1&&step===2?'重新试学':'下一步'};
    root().querySelectorAll('[data-lesson]').forEach(b=>b.addEventListener('click',()=>{lesson=Number(b.dataset.lesson);step=0;draw()}));
    root().querySelector('[data-next-step]').onclick=()=>{step++;if(step===3){step=0;lesson=(lesson+1)%2}draw()};
    root().querySelector('[data-previous]').onclick=()=>{if(step)step--;else if(lesson){lesson--;step=2}draw()};draw();
  }
  function renderQuote(){
    const q=D.quotes[D.param('quote')];
    if(!q||D.param('version')!==q.version){unavailable('报价不存在或版本已变化','QuoteGoods.html');return}
    const back='QuoteGoods.html?quote='+q.id;
    root().innerHTML=shell('确认报价订单',back,`<span class="v2-tag">商品询价 · ${q.version} · 概念数据</span><div class="v2-card"><h2>${q.name}</h2><p>${q.seller}</p><img class="v2-feature-image" src="_blob/b6a30fa51bcba7d4ced06ace8b4d74ed.png" alt="测绘无人机场景示意"><h3 style="margin-top:12px">${q.model}</h3><div class="v2-row"><span>采购数量</span><b>${q.quantity} 台</b></div><div class="v2-row"><span>报价单价</span><b>${q.unit} / 台</b></div><div class="v2-row"><span>预计交期（示例）</span><b>${q.lead}</b></div><div class="v2-row"><span>报价编号</span><b>Q-${q.id.toUpperCase()}-${q.version}</b></div><p>${q.incl}</p></div><div class="v2-card"><div class="v2-row"><span>报价合计</span><b class="v2-price">${money(q.unitCents*q.quantity/100)}</b></div><p class="v2-note">示例报价用于流程演示；真实有效期、交付与价格以商家确认结果为准。</p></div><label class="v2-check"><input type="checkbox" data-agree><span>我已核对商家、规格、数量与报价。本次只演示采购意向确认，不生成真实订单。</span></label><p class="v2-note" data-tip role="status"></p><div class="v2-success" hidden tabindex="-1"><h2>采购意向确认预览</h2><p>${q.name} · ${q.model} × ${q.quantity}</p><p>报价合计 ${q.total} · 未发送至商家</p><p>原型演示到此，不进入支付。</p></div>`,`<footer class="v2-actions"><a class="v2-button secondary" href="${back}">返回比选</a><button class="v2-button" data-confirm>确认意向（演示）</button></footer>`);
    root().querySelector('[data-confirm]').onclick=()=>{const tip=root().querySelector('[data-tip]');if(!root().querySelector('[data-agree]').checked){tip.textContent='请先核对报价并勾选确认。';return}tip.textContent='';const result=root().querySelector('.v2-success');result.hidden=false;root().querySelector('[data-confirm]').disabled=true;result.focus({preventScroll:true});result.scrollIntoView({block:'nearest'})};
  }
  function renderSignup(){
    const event=D.events[D.param('event')];
    if(!event){unavailable('没有找到这场赛事或研学','HomeAct.html');return}
    const sessionId=D.param('session')||event.sessions.find(s=>s.left>0).id;
    if(!event.sessions.some(s=>s.id===sessionId)){unavailable('这个场次不存在',event.detail);return}
    const team=D.param('mode')==='team';let session=sessionId;
    const people=[{id:'xh',name:'示例孩子 A',age:11},{id:'xc',name:'示例孩子 B',age:9},{id:'xm',name:'示例孩子 C',age:6}];
    root().innerHTML=shell(team?'团队报名预约':'填写报名预约',event.detail+'?session='+session,`<img class="v2-feature-image" src="${event.img}" alt="${event.short}"><div><span class="v2-tag">${event.kind} · 适合 ${event.age}</span><h2 style="margin-top:10px">${event.title}</h2><p>主办：${event.organizer}<br>地点：东营低空与机器人体验馆</p></div><div class="v2-card"><h3>选择场次 <small class="v2-note">概念日期与名额</small></h3>${event.sessions.map(s=>`<label class="v2-choice"><input type="radio" name="event-session" value="${s.id}" ${s.id===session?'checked':''}><span>${s.label}<small>${s.left?'余 '+s.left+' 位':'已满 · 可候补'}</small></span></label>`).join('')}</div><form id="signup-form"><div class="v2-card"><h3>${team?'团队资料':'选择参与人'}</h3>${team?'<label class="v2-field">团队名称（演示）<input name="team" required maxlength="40" placeholder="填写学校或机构名称"></label><label class="v2-field">参与人数<input name="count" type="number" min="1" max="60" value="5" required></label>':people.map((p,i)=>`<label class="v2-choice"><input type="checkbox" name="person" value="${p.id}" ${i===0?'checked':''} ${p.age<event.minAge||p.age>event.maxAge?'disabled':''}><span>${p.name} · ${p.age} 岁<small>${p.age<event.minAge||p.age>event.maxAge?'不符合本场年龄条件':'符合年龄条件 · 示例参与人'}</small></span></label>`).join('')}<p class="v2-note">未成年人由监护人代报名并陪同；陪同家长不占名额。</p></div><label class="v2-check" style="margin-top:14px"><input type="checkbox" name="agree"><span>我是参与人的监护人或授权经办人，已核对参与条件、场次及人数。当前仅演示报名意向，不占用真实名额。</span></label><p data-signup-tip role="status" class="v2-note"></p></form><div class="v2-success" hidden tabindex="-1"><h2 data-result-title></h2><p data-result-text></p><p>当前页面演示结果；未发送报名、未产生费用。</p><a class="v2-button secondary" href="${event.detail}">返回${event.kind}详情</a></div>`,`<footer class="v2-actions"><div><small data-count class="v2-note"></small><div data-total class="v2-price"></div></div><button type="submit" form="signup-form" class="v2-button" data-submit>预约报名</button></footer>`);
    const form=root().querySelector('form');
    const count=()=>team?Math.max(0,Math.floor(Number(form.elements.count.value)||0)):form.querySelectorAll('[name="person"]:checked').length;
    const getSession=()=>event.sessions.find(s=>s.id===session);
    const isWait=()=>getSession().left===0||(session===sessionId&&D.param('wait')==='1')||count()>getSession().left;
    const update=()=>{const n=count();root().querySelector('[data-count]').textContent=n+' 人 × '+money(event.price);root().querySelector('[data-total]').textContent=isWait()?'候补意向':money(n*event.price);root().querySelector('[data-submit]').textContent=isWait()?'预约候补（演示）':'预约报名（演示）';root().querySelector('.v2-success').hidden=true;root().querySelector('[data-submit]').disabled=false};
    root().querySelectorAll('[name="event-session"]').forEach(input=>input.onchange=()=>{session=input.value;root().querySelector('.v2-header a').href=event.detail+'?session='+session;update()});
    form.addEventListener('input',update);
    form.onsubmit=e=>{e.preventDefault();const tip=root().querySelector('[data-signup-tip]');if(!count()){tip.textContent='请至少选择 1 位符合条件的参与人。';return}if(!form.elements.agree.checked){tip.textContent='请先核对报名须知并勾选确认。';return}tip.textContent='';const result=root().querySelector('.v2-success');result.hidden=false;result.querySelector('[data-result-title]').textContent=isWait()?'候补意向预览':'报名意向预览';result.querySelector('[data-result-text]').textContent=event.title+' · '+getSession().label+' · '+count()+' 人'+(isWait()?'':' · '+money(count()*event.price));root().querySelector('[data-submit]').disabled=true;result.focus({preventScroll:true});result.scrollIntoView({block:'nearest'})};update();
  }
  const originalEnhance=window.applyLightDesign;
  if(originalEnhance)window.applyLightDesign=function(r){originalEnhance(r);initCarousel()};
  document.addEventListener('DOMContentLoaded',()=>{
    if(page==='Games.html')renderGames();
    else if(page==='GamePlay.html')renderGame();
    else if(page==='CourseTrial.html')renderCourse();
    else if(page==='QuoteConfirm.html')renderQuote();
    else if(page==='EventSignup.html')renderSignup();
    else if(['ExpSim.html','ExpResult.html'].includes(page)&&!D.getExperience())unavailable('没有找到这个仿真任务','ExpCenter.html');
    initCarousel();
  });
})();
