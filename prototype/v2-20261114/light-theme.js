/* Visual enhancement only. Existing Component state and navigation remain authoritative. */
(function(){
 'use strict';
 const page=decodeURIComponent(location.pathname.split('/').pop()||'HomeFull.html');
 const asset=name=>'theme-assets/'+name+'.png';
 const primaryPages=new Set(['HomeFull.html','Home.html','HomeSvc.html','HomeAct.html','MallHome.html','Mall2.html','MallDrone.html','MallRental.html','MallCourse.html','MallParts.html','Discover.html','Mine.html','MineGuest.html','ExpCenter.html']);
 const palettes=[['#E3EFFF','#377DCE','#3479d01a'],['#EFE6FF','#8254C8','#8052c61a'],['#FFEBD8','#CE681D','#cf6b1b1a'],['#DFF5EB','#21856C','#21856c1a'],['#FFF0C8','#9D7610','#a78a151a']];
 function paintTile(el,index){
  const [bg,ink,shadow]=palettes[index%palettes.length];
  el.classList.add('theme-icon-tile');el.style.position='relative';
  el.style.setProperty('--tile-bg',bg);el.style.setProperty('--tile-ink',ink);el.style.setProperty('--tile-shadow',shadow);
 }
 function decorateImage(img){
  const alt=img.alt||'',src=img.getAttribute('src')||'';
  if(/5d32505e8b9a494f8436e31c3008ef99/.test(src)||/编程机器人探索套装/.test(alt)){
   img.src=asset('robot-rover');img.classList.add('theme-product-image');
   if(parseFloat(img.parentElement.style.height)>=210)img.classList.add('theme-product-detail');
  }else if(/7dea67aa257759d5c4cffcda675bc107/.test(src)||/机器人研学|孩子们和老师/.test(alt)){
   img.src=asset('robot-workshop');img.classList.add('theme-study-photo');
  }
 }
 function enhance(root){
  const app=root.firstElementChild;if(!app)return;
  app.classList.add('theme-app');
  if(['VideoFeed.html','LiveRoom.html','PhotoViewer.html'].includes(page))app.classList.add('theme-media');
  if(app.style.height==='844px'&&!primaryPages.has(page))app.classList.add('theme-viewport-page');
  if(primaryPages.has(page)||(app.style.display==='flex'&&app.style.flexDirection==='column'&&parseFloat(app.style.height)>844)){
   app.classList.add('theme-fluid');
   for(const el of app.children){
    if(el.style.flexGrow==='1')el.classList.add('theme-main');
   }
  }
  for(const el of app.children)if(el.style.flexShrink==='0'&&el.style.borderTop&&el.querySelector('a,button'))el.classList.add('theme-actions');
  const first=app.firstElementChild;
  if(first&&!first.querySelector('img')&&first.style.background)first.classList.add('theme-page-head');
  root.querySelectorAll('nav').forEach(nav=>{
   if(nav.querySelectorAll('a').length===5){
    nav.classList.add('theme-nav');
    for(const link of nav.querySelectorAll('a'))if((link.getAttribute('href')||'').split('?')[0]===page)link.setAttribute('aria-current','page');
   }
  });
  let tileIndex=0;
  for(const el of root.querySelectorAll('[style]')){
   const st=el.style,w=parseFloat(st.width),h=parseFloat(st.height),bg=st.backgroundColor;
   if(parseFloat(st.borderRadius)>=10&&(bg==='rgb(255, 255, 255)'||bg==='white')&&(el.children.length>1||el.querySelector('img')))el.classList.add('theme-card');
   if(bg==='rgb(255, 106, 0)'&&h>=28&&(['BUTTON','A'].includes(el.tagName)||el.parentElement?.tagName==='A'))el.classList.add('theme-primary');
   if(!app.classList.contains('theme-media')&&w>=38&&w<=64&&h>=38&&h<=64&&el.querySelector(':scope > svg')&&bg&&bg!=='transparent')paintTile(el,tileIndex++);
   if(st.display==='flex'&&st.flexDirection!=='column'&&st.overflow==='hidden'&&el.children.length>2){
    const wide=Array.from(el.children).some(c=>parseFloat(c.style.width)>70||c.style.flexShrink==='0');
    if(wide)el.classList.add('theme-scroll-x');
   }
  }
  root.querySelectorAll('img').forEach(decorateImage);
  for(const holder of root.querySelectorAll('span,div')){
   const leaf=Array.from(holder.children).find(c=>c.tagName==='SPAN'&&c.children.length===0&&/商品图（待授权素材）/.test(c.textContent));
   if(!leaf||holder.querySelector('img'))continue;
   const card=holder.closest('a')?.parentElement||holder.parentElement;
   if(/机械臂/.test(card.textContent)||/PdArm|SkuArm/.test(page)){
    holder.classList.add('theme-placeholder-art');const img=document.createElement('img');img.src=asset('robot-arm');img.alt='教学机械臂造型示意';holder.prepend(img);leaf.textContent='造型示意';
   }
  }
  if(['HomeFull.html','Home.html'].includes(page)){
   const hero=root.querySelector('img[alt="无人机飞行场景"]');
   if(hero){hero.src=asset('hero-world');hero.alt='无人机与机器人探索场景插画';hero.parentElement.classList.add('theme-hero');hero.nextElementSibling?.classList.add('theme-hero-copy');const desc=hero.nextElementSibling?.children[2];if(desc&&desc.textContent.includes('虚拟仿真'))desc.innerHTML='<span>虚拟仿真 · 机器人闯关</span><br><span>录播演示</span>';}
   for(const a of root.querySelectorAll('a')){
    const txt=a.textContent.trim();
    if(txt.startsWith('找专业服务'))a.parentElement.classList.add('theme-entry-service');
    if(txt.startsWith('去体验')&&a.style.height==='104px')a.classList.add('theme-entry-experience');
    if(/模拟飞行|机器人闯关|录播演示/.test(txt)&&a.parentElement.style.gridTemplateColumns.includes('3')){
     a.classList.add('theme-slot');a.style.setProperty('--slot-bg',/模拟飞行/.test(txt)?'#e9f1ff':/机器人/.test(txt)?'#eee8ff':'#e1f5ed');
    }
   }
  }
  if(page==='HomeSvc.html'){
   for(const grid of root.querySelectorAll('div'))if(grid.style.display==='grid'&&grid.style.gridTemplateColumns.includes('4')&&grid.querySelectorAll('img').length>=4)grid.classList.add('theme-service-scenes');
  }
  if(page==='Discover.html')root.querySelector('a[href="Topic.html"]')?.classList.add('theme-feature-story');
  if(page==='ExpCenter.html')root.querySelectorAll('a > div > img').forEach(img=>{if(img.parentElement.style.height==='74px'){img.classList.add('theme-experience-cover');img.parentElement.classList.add('theme-experience-media');}});
  if(['MallDrone.html','MallParts.html','VenueOutdoor.html'].includes(page))for(const row of root.querySelectorAll('div'))if(row.style.display==='flex'&&row.style.flexDirection!=='column'&&row.children.length>=3&&Array.from(row.children).every(el=>el.tagName==='BUTTON'))row.classList.add('theme-scroll-x');
  if(page==='Mine.html'||page==='MineGuest.html'){
   for(const div of root.querySelectorAll('div'))if(div.style.display==='flex'&&div.style.gap==='12px'&&div.querySelector('button')&&/常用城市|经办人/.test(div.textContent)){
    div.classList.add('theme-profile');const avatar=div.querySelector('[style*="width: 56px"]');if(avatar)avatar.classList.add('theme-profile-avatar');break;
   }
  }
  // Keep the root light on full-screen media boards, retaining contrast inside actual media.
  document.getElementById('phone')?.classList.remove('dark');
 }
 window.applyLightDesign=enhance;
 function previewTools(){
  const sidebar=document.createElement('aside');sidebar.className='theme-preview';
  sidebar.innerHTML='<div class="eyebrow">低空产业平台 · 原型预览</div><h1>轻盈、多彩<br>探索更有趣</h1><p>浅色界面 / 生动场景 / 清晰操作</p><div class="swatches"><i style="background:#ff6a00"></i><i style="background:#9ddfcb"></i><i style="background:#92bff4"></i><i style="background:#c9aff1"></i><i style="background:#f4d36e"></i></div><nav><a href="HomeFull.html">首页推荐</a><a href="HomeSvc.html">专业服务</a><a href="MallHome.html">商城精选</a><a href="Discover.html">发现内容</a><a href="ExpCenter.html">线上与线下体验</a><a href="Games.html">趣玩挑战</a><a href="HomeAct.html">赛事研学</a><a href="Mine.html">我的</a></nav><a class="open-index" href="页面目录.html">查看全部页面 ›</a><p class="fine">视觉演示原型<br>商品、订单及活动均为示例<br>新增场景与产品图为生成示意素材</p>';
  sidebar.querySelectorAll('nav a').forEach(a=>{if(a.getAttribute('href')===page)a.setAttribute('aria-current','page');});const overview=document.createElement('a');overview.href='优化总览.html';overview.className='open-index';overview.textContent='查看主要页面效果总览 ›';overview.style.display='block';overview.style.marginTop='8px';sidebar.querySelector('.fine').before(overview);document.body.append(sidebar);
  function fitMobile(){document.documentElement.style.setProperty('--phone-height',innerWidth<=600?innerHeight+'px':'844px');}
  fitMobile();window.addEventListener('resize',fitMobile);
 }
 document.addEventListener('DOMContentLoaded',()=>{const root=document.getElementById('root');if(root)enhance(root);previewTools();});
})();
