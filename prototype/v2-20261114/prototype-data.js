/* Local concept data only. IDs preserve context; no API or financial writes. */
(function(){
  'use strict';
  const param=name=>new URLSearchParams(location.search).get(name);
  const experiences={
    'robot-obstacle':{id:'robot-obstacle',title:'机器人避障模拟',img:'_blob/192fc8acf4d61dc58d9c5109a1cad0da.jpg',productTitle:'编程机器人探索套装',productHref:'PdFull.html',productImg:'theme-assets/robot-rover.png',price:'¥1,299 起'},
    'drone-hover':{id:'drone-hover',title:'无人机起降与悬停',img:'_blob/b41e3fb34601d5b9b3c9e40e84d2a489.jpg',productTitle:'教育编程无人机套装',productHref:'PdDroneEdu.html',productImg:'_blob/b41e3fb34601d5b9b3c9e40e84d2a489.jpg',price:'查看商品报价'}
  };
  const common={model:'示例 M6 测绘版',quantity:2,version:'v1',incl:'RTK、五镜头倾斜相机、2 名飞手培训、一年保修'};
  const quotes={
    maker:{...common,id:'maker',name:'示例无人机厂家',seller:'厂家直营',unit:'¥68,000',unitCents:6800000,amount:136000,total:'¥136,000',lead:'12/05',leadDay:5,valid:'11/12',badge:'交期最短',badgeBg:'#e6f0ff',badgeFg:'#1d63c4'},
    agent:{...common,id:'agent',name:'示例行业装备代理',seller:'授权经销',unit:'¥64,500',unitCents:6450000,amount:129000,total:'¥129,000',lead:'12/12',leadDay:12,valid:'11/10',badge:'报价最低',badgeBg:'#fff0e3',badgeFg:'#ba470b'}
  };
  const events={
    'drone-race':{id:'drone-race',title:'青少年无人机编程挑战赛（东营站）',short:'无人机编程挑战赛',kind:'赛事',age:'8–15 岁',minAge:8,maxAge:15,price:120,organizer:'示例青少年科技机构',detail:'ActDetail.html',img:'_blob/248020917b29731ae2eb5c415d406dd0.webp',sessions:[{id:'am',label:'11/15 上午 09:00',left:12},{id:'pm',label:'11/15 下午 14:00',left:0},{id:'next',label:'11/22 上午 09:00',left:30}]},
    'robot-study':{id:'robot-study',title:'机器人研学体验',short:'机器人研学体验',kind:'研学',age:'6–12 岁',minAge:6,maxAge:12,price:168,organizer:'示例研学机构',detail:'ActStudyRobot.html',img:'theme-assets/robot-workshop.png',sessions:[{id:'am',label:'11/08 上午 09:30',left:12},{id:'pm',label:'11/08 下午 14:00',left:6},{id:'next',label:'11/15 上午 09:30',left:20}]}
  };
  const games={
    'robot-maze':{id:'robot-maze',title:'机器人迷宫',subtitle:'转个弯，和小伙伴一起找到出口',tone:'violet',img:'theme-assets/robot-rover.png',avatar:'robot',walls:[6,7,8,13,16,18],stars:[2,10,22],goal:24},
    'drone-route':{id:'drone-route',title:'无人机寻星',subtitle:'规划路线，把沿途星星带回基地',tone:'sky',img:'theme-assets/hero-world.png',avatar:'drone',walls:[6,8,11,13,18],stars:[4,12,20],goal:24}
  };
  window.PrototypeData={param,experiences,quotes,events,games,getExperience:()=>experiences[param('experience')||'robot-obstacle']};
})();
