import { randomUUID } from 'node:crypto'
import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { API, BUYER, CITY, apiLogin } from './support/trade-fixtures'

import { testAdmin as admin } from './support/test-stack'
const operatorPassword = process.env.DEV_OPERATOR_PASSWORD
const secondCity = '370100'
const serviceId = '44444444-4444-4444-4444-444444444444'
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jOZkAAAAASUVORK5CYII=', 'base64')
test.use({ trace: 'off', viewport: {width: 1366, height: 900} })
test.beforeEach(() => {
  test.skip(process.env.E2E_OPS_CONTENT_TEST !== '1' || !operatorPassword,
    '需要测试栈运营口令及 E2E_OPS_CONTENT_TEST=1')
})

async function operator(request: APIRequestContext) {
  const login = await request.post(API+'/api/v1/auth/login', {data:{loginName:'dev-operator', password:operatorPassword}})
  expect(login.status()).toBe(200)
  return {Authorization:'Bearer '+(await login.json()).accessToken}
}
async function signIn(page: Page, path: string) {
  await page.goto(admin+path)
  await page.locator('input[autocomplete="username"]').fill('dev-operator')
  await page.locator('input[autocomplete="current-password"]').fill(operatorPassword!)
  await page.locator('form').getByRole('button',{name:'登录'}).click()
}
async function json(request: APIRequestContext, path: string, headers?: Record<string,string>) {
  const response = await request.get(API+'/api/v1'+path, {headers})
  expect(response.status(),path).toBe(200)
  return response.json()
}
async function upload(request: APIRequestContext, headers: Record<string,string>) {
  const response=await request.post(API+'/api/v1/ops/media',{headers,multipart:{file:{name:'test.png',mimeType:'image/png',buffer:png}}})
  expect(response.status()).toBe(201)
  return response.json() as Promise<{url:string;fileName:string}>
}

test('轮播草稿在运营后台编辑并发布，App 按城市和顺序展示，旧版本被拒，下架后图片也撤回', async({page, request, context}) => {
  const headers=await operator(request)
  const media=await upload(request,headers)
  const slides='/ops/home/carousel'
  const title='轮播验收'+randomUUID().slice(0,8)
  const data={tag:'活动',titleFirst:title,titleSecond:'草稿',descriptionFirst:'运营回归',descriptionSecond:'城市推荐',
    imageUrl:media.url,imageAlt:'测试图片',tone:'SKY',target:'ACTIVITY',actionLabel:'查看活动',status:'DRAFT',sortOrder:0,version:0}
  const draft=await request.post(API+'/api/v1'+slides+'/'+CITY,{headers,data})
  expect(draft.status()).toBe(201)
  const {id}=await draft.json()
  const item=slides+'/'+CITY+'/'+id
  const image=API+'/api/v1/dev-media/ops/covers/'+media.fileName
  const publicSlides=async (city: string) => (await json(request,'/home/carousel?cityCode='+city)).items as {id:string;titleLines:string[]}[]
  expect((await publicSlides(CITY)).some(slide=>slide.id===id)).toBe(false)
  expect((await request.get(image)).status()).toBe(404)

  await signIn(page,'/home-carousel')
  await page.getByRole('row').filter({hasText:title}).getByRole('button',{name:'编辑'}).click()
  const dialog=page.getByRole('dialog')
  await dialog.getByLabel('标题第二行').fill('已正式发布')
  await dialog.locator('.el-form-item').filter({hasText:/^状态/}).locator('.el-select__wrapper').click()
  await page.getByRole('option',{name:'已发布',exact:true}).click()
  const write=page.waitForResponse(r=>r.url().endsWith(item)&&r.request().method()==='PUT')
  await dialog.getByRole('button',{name:'保存',exact:true}).click()
  expect((await write).status()).toBe(200)
  expect((await publicSlides(CITY))[0]).toMatchObject({id,titleLines:[title,'已正式发布']})
  expect((await publicSlides(secondCity)).some(s=>s.id===id)).toBe(false)
  const guest=await request.get(image)
  expect(guest.status()).toBe(200)
  expect(await guest.body()).toEqual(png)
  const app=await context.newPage()
  await app.goto('/#/pages/home/index')
  await expect(app.getByTestId('home-carousel')).toContainText(title)
  expect((await request.put(API+'/api/v1'+item,{headers,data})).status()).toBe(409)
  expect((await request.put(API+'/api/v1'+item,{headers,data:{...data,version:1,status:'OFFLINE'}})).status()).toBe(200)
  expect((await publicSlides(CITY)).some(s=>s.id===id)).toBe(false)
  expect((await request.get(image)).status()).toBe(404)
  const history=await json(request,item+'/changes',headers)
  expect(history.map((h: {status:string})=>h.status)).toEqual(['OFFLINE','PUBLISHED','DRAFT'])
  expect(history[0].operatorName).toBeTruthy()
  expect((await request.get(API+'/api/v1'+slides)).status()).toBe(401)
  const buyer=await apiLogin(request,BUYER)
  expect(buyer).toBeTruthy()
  expect((await request.put(API+'/api/v1'+item,{headers:buyer!.headers,data:{...data,version:2}})).status()).toBe(403)
})

test('巡检入口在后台编辑全国默认，城市覆盖场景与横幅，停用后继承默认',async({page,request,context})=>{
  const headers=await operator(request)
  const path='/ops/inspection/portal-content'
  const marker=randomUUID().slice(0,8)
  const read=async(city:string)=>json(request,'/inspection/portal?cityCode='+city)
  const global=(await json(request,path,headers)).find((row:{contentType:string})=>row.contentType==='HERO')
  expect(global).toBeTruthy()
  await signIn(page,'/inspection-services')
  await page.getByRole('tab',{name:'入口配置'}).click()
  await page.getByRole('row').filter({hasText:global.title}).getByRole('button',{name:'编辑'}).click()
  const dialog=page.getByRole('dialog')
  await dialog.getByLabel('标题',{exact:true}).fill('全国巡检'+marker)
  const write=page.waitForResponse(r=>r.url().endsWith(path+'/'+global.id)&&r.request().method()==='PUT')
  await dialog.getByRole('button',{name:'保存',exact:true}).click()
  expect((await write).status()).toBe(200)
  try {
    expect((await read(secondCity)).hero.title).toBe('全国巡检'+marker)
    const media=await upload(request,headers)
    const initial=(await read(CITY)).categories.find((c:{code:string})=>c.code==='POWER')
    for(const [type,code] of [['HERO','DEFAULT'],['SCENE','POWER'],['GUARANTEE','QA_GUARANTEE'],['PROCESS','QA_PROCESS']]){
      const scoped=(await json(request,path+'?cityCode='+CITY,headers)).find((p:{contentType:string;itemCode:string})=>p.contentType===type&&p.itemCode===code)
      const data={cityCode:CITY,contentType:type,itemCode:code,title:'东营巡检'+marker,subtitle:'本城说明',
        highlightedText:null,imageUrl:type==='HERO'?media.url:null,status:'ACTIVE',sortOrder:0,version:scoped?.version??0}
      const target=API+'/api/v1'+path
      const changed=scoped?await request.put(target+'/'+scoped.id,{headers,data}):await request.post(target,{headers,data})
      expect(changed.status()).toBe(scoped?200:201)
      const {id}=await changed.json()
      const version=scoped?scoped.version+1:0
      const view=await read(CITY)
      if(type==='HERO'){
        expect(view.hero.title).toBe(data.title)
        expect((await read(secondCity)).hero.title).toBe('全国巡检'+marker)
        const app=await context.newPage()
        await app.goto('/#/pages/service/inspection/index?cityCode='+CITY)
        await expect(app.getByTestId('inspection-portal')).toContainText(data.title)
        await app.close()
      } else if(type==='SCENE') {
        expect(view.categories.find((c:{code:string})=>c.code===code)).toMatchObject({name:data.title,subtitle:data.subtitle})
        expect((await read(secondCity)).categories.find((c:{code:string})=>c.code===code).name).not.toBe(data.title)
      } else {
        const section=type==='PROCESS'?'processSteps':'guarantees'
        expect(view[section]).toEqual([expect.objectContaining({code,title:data.title,subtitle:data.subtitle})])
        expect((await read(secondCity))[section]).not.toContainEqual(expect.objectContaining({code}))
        const app=await context.newPage()
        await app.goto('/#/pages/service/inspection/index?cityCode='+CITY)
        await expect(app.getByTestId('inspection-portal')).toContainText(data.subtitle)
        await app.close()
      }
      expect((await request.put(target+'/'+id,{headers,data:{...data,version,status:'INACTIVE'}})).status()).toBe(200)
      expect((await request.put(target+'/'+id,{headers,data:{...data,version}})).status()).toBe(409)
      const current=await read(CITY)
      if(type==='HERO') expect(current.hero.title).toBe('全国巡检'+marker)
      else if(type==='SCENE') expect(current.categories.find((c:{code:string})=>c.code===code)).toMatchObject({name:initial.name})
      else expect(current[type==='PROCESS'?'processSteps':'guarantees']).not.toContainEqual(expect.objectContaining({code}))
      expect((await json(request,path+'/'+id+'/changes',headers))[0].status).toBe('INACTIVE')
    }
  }finally{
    expect((await request.put(API+'/api/v1'+path+'/'+global.id,{headers,data:{...global,version:global.version+1}})).status()).toBe(200)
  }
})

test('运营上传并绑定巡检交付样图，详情同步显示，冲突与清除受服务版本保护',async({page,request,context})=>{
  const headers=await operator(request)
  const base='/ops/inspection/services/'+serviceId
  const service=await json(request,base,headers)
  const samples=await json(request,base+'/deliverable-images',headers)
  const sample=samples.find((s:{imageUrl:string|null})=>!s.imageUrl)??samples[0]
  expect(sample).toBeTruthy()
  await signIn(page,'/inspection-services/'+serviceId)
  const row=page.getByRole('row').filter({has:page.getByRole('button',{name:'保存样图'}),hasText:sample.title})
  const uploadResponse=page.waitForResponse(r=>r.url().includes('/ops/media')&&r.request().method()==='POST')
  await row.locator('input[type=file]').setInputFiles({name:'deliverable.png',mimeType:'image/png',buffer:png})
  const uploaded=await uploadResponse
  expect(uploaded.status()).toBe(201)
  const {url,fileName}=await uploaded.json()
  const path=base+'/deliverable-images/'+sample.code
  const write=page.waitForResponse(r=>r.url().endsWith(path)&&r.request().method()==='PUT')
  await row.getByRole('button',{name:'保存样图'}).click()
  expect((await write).status()).toBe(204)
  const read=async()=>(await json(request,'/inspection/services/'+serviceId+'?cityCode='+CITY)).deliverables.find((s:{code:string})=>s.code===sample.code)
  expect((await read()).image.url).toBe(url)
  const guest=API+'/api/v1/dev-media/ops/covers/'+fileName
  expect(await (await request.get(guest)).body()).toEqual(png)
  const app=await context.newPage()
  await app.goto('/#/pages/service/inspection/detail?serviceId='+serviceId+'&cityCode='+CITY)
  const cover=app.getByTestId('detail-deliverables').getByTestId('deliverable-sample-'+sample.code)
  await expect(cover).toBeVisible()
  await expect(cover.locator('img')).toHaveAttribute('src',guest)
  await cover.click()
  await expect(app.locator('#u-a-p img').first()).toHaveAttribute('src',guest)
  await app.close()
  expect((await request.put(API+'/api/v1'+path,{headers,data:{imageUrl:sample.imageUrl,version:service.version}})).status()).toBe(409)
  expect((await request.put(API+'/api/v1'+path,{headers,data:{imageUrl:sample.imageUrl,version:service.version+1}})).status()).toBe(204)
  expect((await read()).image?.url??null).toBe(sample.imageUrl)
  expect((await request.get(guest)).status()).toBe(404)
  const changes=await json(request,base+'/changes',headers)
  expect(JSON.stringify(changes)).toContain('更新交付样图')
})

test('已有上线活动缺少报名须知，表单在保存前和接口拒绝后都显示中文字段名', async ({page, request}) => {
  const headers=await operator(request)
  const activityId='a2000000-0000-4000-8000-000000000004'
  const path='/ops/activities/'+activityId
  const before=await json(request,path,headers)
  expect(before.status).toBe('PUBLISHED')
  expect(before.publishMissing).toContain('enrollmentNotice')
  await signIn(page,'/activities/'+activityId)
  await expect(page.getByText('请补全活动资料：报名须知')).toBeVisible()
  await page.getByRole('button',{name:'保存活动信息',exact:true}).click()
  const failed=page.waitForResponse(r=>r.url().endsWith(path)&&r.request().method()==='PUT')
  await page.locator('.el-message-box').getByRole('button',{name:'保存',exact:true}).click()
  expect((await failed).status()).toBe(422)
  await expect(page.locator('.activity-edit__status')).toContainText('上线前还差：报名须知')
  expect((await json(request,path,headers)).version).toBe(before.version)
})
