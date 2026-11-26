import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { API, BUYER, apiLogin } from './support/trade-fixtures'
import { testAdmin } from './support/test-stack'

test.use({trace:'off', viewport:{width:1280,height:900}})
test('协议草稿、发布、生效与历史留档经工作台和 App 读取', async ({page, request, context}) => {
  const password = process.env.DEV_OPERATOR_PASSWORD
  test.skip(!password || process.env.E2E_OPS_AGREEMENTS_TEST !== '1', '需要独立运营验收库')
  const base = API + '/api/v1/ops/agreements'
  const doc = 'USER_AGREEMENT'
  const publicUrl = API + '/api/v1/platform/agreements/' + doc
  const login = await request.post(API+'/api/v1/auth/login',{data:{loginName:'dev-operator',password}})
  expect(login.status()).toBe(200)
  const headers = {Authorization:'Bearer '+(await login.json()).accessToken}
  const initial = await request.get(publicUrl)
  const prior = initial.ok() ? await initial.json() : null
  if (!prior) expect(initial.status()).toBe(404)
  const nextDay = new Date((prior?.publishedOn ?? '2010-01-01')+'T12:00:00Z')
  nextDay.setUTCDate(nextDay.getUTCDate()+1)
  const effectiveOn = nextDay.toISOString().slice(0,10)
  expect(nextDay.getTime()).toBeLessThan(Date.now())
  const marker = randomUUID().slice(0,8)
  const version = 'QA-'+marker
  const title = '用户协议验收 '+marker
  const body = '本段仅用于独立副本的发布验收。'+marker
  const apiDraft = {doc,version,title,effectiveOn,revision:0,sections:[{title:'第一节',body}]}
  expect((await request.post(base,{data:apiDraft})).status()).toBe(401)
  const buyer = await apiLogin(request,BUYER)
  expect((await request.post(base,{data:apiDraft,headers:buyer!.headers})).status()).toBe(403)
  await page.goto(testAdmin+'/content')
  await page.locator('input[autocomplete=username]').fill('dev-operator')
  await page.locator('input[autocomplete=current-password]').fill(password!)
  await page.locator('form').getByRole('button',{name:'登录'}).click()
  await page.getByRole('button',{name:'平台协议',exact:true}).click()
  await page.getByRole('button',{name:'新增版本'}).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByLabel('版本号').fill(version)
  await dialog.getByLabel('协议标题').fill(title)
  await dialog.getByPlaceholder('选择北京时间日期').fill(effectiveOn)
  await dialog.getByPlaceholder('选择北京时间日期').press('Enter')
  await dialog.getByRole('button',{name:'添加章节'}).click()
  await dialog.getByLabel('章节标题').fill('第一节')
  await dialog.getByLabel('章节正文').fill(body)
  const creating = page.waitForResponse(r=>r.url().endsWith('/ops/agreements')&&r.request().method()==='POST')
  await dialog.getByRole('button',{name:'保存草稿'}).click()
  const created = await creating
  expect(created.status()).toBe(201)
  const {document} = await created.json()
  expect(document.effectiveOn).toBe(effectiveOn)
  expect(document.status).toBe('DRAFT')
  const whileDraft = await request.get(publicUrl)
  expect(whileDraft.status()).toBe(initial.status())
  if (prior) expect(await whileDraft.json()).toEqual(prior)
  expect((await request.put(base+'/'+document.id,{headers,data:{...apiDraft,revision:5}})).status()).toBe(409)
  const broken = await request.put(base+'/'+document.id,{headers,data:{...apiDraft,title:'无效章节',
    sections:[{title:'第一节',body}, {title:'第二节',body:' '}]}})
  expect(broken.status()).toBe(400)
  const retained = await (await request.get(base+'/'+document.id,{headers})).json()
  expect(retained.document).toEqual(document)
  expect(retained.sections).toEqual(apiDraft.sections)
  await page.getByRole('row').filter({hasText:title}).getByRole('button',{name:'预览'}).click()
  await expect(dialog.getByText(body,{exact:true})).toBeVisible()
  await dialog.getByRole('button',{name:'发布版本'}).click()
  const publishing = page.waitForResponse(r=>r.url().endsWith('/publish')&&r.request().method()==='POST')
  await page.getByRole('button',{name:'确认发布'}).click()
  const published = await publishing
  expect(published.status()).toBe(200)
  expect((await published.json()).history.map((h:{action:string})=>h.action)).toEqual(['PUBLISH','CREATE'])
  expect((await request.get(publicUrl)).status()).toBe(200)
  expect(await (await request.get(publicUrl)).json()).toMatchObject({version,sections:apiDraft.sections})
  expect((await request.put(base+'/'+document.id,{headers,data:{...apiDraft,revision:1}})).status()).toBe(409)

  const mobile = await context.newPage()
  await mobile.goto('/#/pages/mine/agreement?doc='+doc)
  await expect(mobile.getByText(body,{exact:true})).toBeVisible()

  const schedule = async (v:string, date:string) => {
    const response = await request.post(base,{headers,data:{...apiDraft,version:v,effectiveOn:date}})
    expect(response.status()).toBe(201)
    const {document:draft} = await response.json()
    return request.post(base+'/'+draft.id+'/publish',{headers,data:{revision:0}})
  }
  let lastDate = '2120-01-01'
  for (let number = 1; ; number++) {
    const versions = await (await request.get(base,{headers,params:{doc,page:number,size:100}})).json()
    for (const item of versions.items) {
      if (item.status==='SCHEDULED' && item.effectiveOn > lastDate) lastDate = item.effectiveOn
    }
    if (!versions.hasMore) break
  }
  const future = new Date(lastDate+'T12:00:00Z')
  future.setUTCDate(future.getUTCDate()+1)
  const pending = await schedule(version+'-F',future.toISOString().slice(0,10))
  expect(pending.status()).toBe(200)
  expect((await pending.json()).document.status).toBe('SCHEDULED')
  expect((await (await request.get(publicUrl)).json()).version).toBe(version)
  expect((await schedule(version+'-D',effectiveOn)).status()).toBe(409)
  nextDay.setUTCDate(nextDay.getUTCDate()+1)
  expect((await schedule(version+'-2',nextDay.toISOString().slice(0,10))).status()).toBe(200)
  const archived = await (await request.get(base+'/'+document.id,{headers})).json()
  expect(archived.document.status).toBe('ARCHIVED')
  expect(archived.sections).toEqual(apiDraft.sections)
  expect((await (await request.get(publicUrl)).json()).version).toBe(version+'-2')
})
