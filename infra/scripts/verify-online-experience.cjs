// Run against the disposable batch-two stack. All writes use a new project and keep its audit history.
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { resolve } = require('node:path')
const { createRequire } = require('node:module')
const { chromium } = createRequire(resolve(__dirname,'../../apps/mobile/package.json'))('@playwright/test')
const [admin, h5, api] = ['17439','5189','18429'].map(port=>'http://127.0.0.1:'+port)
const pw = process.env.DEV_OPERATOR_PASSWORD
assert.ok(pw && process.env.EXPERIENCE_ALLOW_CLONE_WRITES === 'true', 'Require an operator credential and explicit disposable-stack writes')
const title = '验收录播 '+randomUUID().slice(0,8)
const mp4 = resolve(__dirname,'../../apps/mobile/src/static/videos/preview/aerial-yellow-river.mp4')
const picture = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jOZkAAAAASUVORK5CYII=','base64')
async function run() {
  const browser = await chromium.launch({headless:true,channel:process.env.PLAYWRIGHT_CHANNEL||'chrome'})
  try {
    const page = await browser.newPage({viewport:{width:1280,height:850}})
    page.setDefaultTimeout(16000)
    await page.goto(admin+'/login')
    await page.locator('input[autocomplete="username"]').fill('dev-operator')
    await page.locator('input[autocomplete="current-password"]').fill(pw)
    await page.locator('button[type="submit"]').click()
    await page.waitForURL(url=>url.pathname!=='/login')
    await page.goto(admin+'/venues')
    await page.getByRole('button',{name:'线上体验项目',exact:true}).click()
    await page.getByRole('button',{name:'新建项目',exact:true}).click()
    const dialog=page.getByRole('dialog')
    const field=label=>dialog.locator('.el-form-item').filter({has:page.locator('.el-form-item__label',{hasText:label})})
    await field('项目标题').locator('input').fill(title)
    await field('项目类型').locator('.el-select').click()
    await page.getByRole('option',{name:'录播演示',exact:true}).click()
    await field('介绍').locator('textarea').fill('浏览器验收录播媒体播放与撤回。')
    async function upload(label,payload) {
      const pending=page.waitForResponse(r=>r.request().method()==='POST'&&new URL(r.url()).pathname.endsWith('/ops/media'))
      await field(label).locator('input[type=file]').setInputFiles(payload)
      const uploaded=await pending; assert.equal(uploaded.status(),201)
      return uploaded.json()
    }
    await upload('封面',{name:'cover.png',mimeType:'image/png',buffer:picture})
    const media=await upload('录播视频',mp4)
    const publicVideo='/api/v1/dev-media/ops/videos/'+media.fileName
    const mediaStatus=async()=> (await fetch(api+publicVideo,{headers:{Range:'bytes=0-31'}})).status
    assert.equal(await mediaStatus(),404,'Draft recording must be private')
    async function submit(label) {
      await dialog.getByRole('button',{name:label,exact:true}).click()
      const pending=page.waitForResponse(r=>r.request().method()==='PUT'&&new URL(r.url()).pathname.includes('/ops/experience-items/'))
      await page.locator('.el-message-box__btns').getByRole('button',{name:'确认',exact:true}).click()
      const response=await pending
      assert.equal(response.status(),200,await response.text())
      return response.json()
    }
    const draft=await submit('保存草稿')
    const live=await submit('发布')
    assert.equal(draft.id,live.id)
    assert.equal(await mediaStatus(),206,'Public recording permits browser byte ranges')
    const app=await browser.newPage({viewport:{width:390,height:844}})
    await app.goto(h5+'/#/pages/experience/recorded?experienceId='+live.id)
    await app.getByTestId('recorded-page').getByText(title,{exact:true}).waitFor()
    const video=app.locator('video')
    await video.waitFor()
    const playback=await video.evaluate(async el=>{
      el.muted=true
      await el.play()
      await new Promise((resolve,reject)=>{
        if(el.currentTime>0.01) return resolve()
        const timeout=setTimeout(()=>reject(new Error('Playback clock stayed at zero')),7000)
        el.addEventListener('timeupdate',()=>{clearTimeout(timeout);resolve()},{once:true})
      })
      return {duration:el.duration, played:el.currentTime}
    })
    assert.ok(playback.duration>0 && playback.played>0,'Browser decoded the uploaded MP4')
    const offline=await submit('下架')
    assert.equal(offline.status,'OFFLINE')
    assert.equal(await mediaStatus(),404,'Withdrawn recording must be private')
    await app.reload()
    await app.getByText('该录播项目暂不可用',{exact:true}).waitFor()
    console.log('Admin create, image/MP4 uploads, publish, real H5 video decode and playback ('+playback.duration.toFixed(2)+' s), withdraw, old link and media 404 passed. Item '+live.id)
  } finally { await browser.close() }
}
run().catch(error=>{console.error(error);process.exitCode=1})
