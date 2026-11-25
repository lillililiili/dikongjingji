import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import { testAdmin as admin, testApi as api } from './support/test-stack'
import { BUYER, apiLogin } from './support/trade-fixtures'

test.use({trace:'off',viewport:{width:1320,height:900}})

test('关卡草稿、发布与撤回在真实后台和 App 生效，已发布版本可通关',async({page,context,request})=>{
  const password=process.env.DEV_OPERATOR_PASSWORD
  test.skip(!password,'需独立测试环境运营口令')
  test.setTimeout(75_000)
  const root=api+'/api/v1/ops/experience/play-games'
  const published=async()=>{
    const response=await request.get(api+'/api/v1/experience/play-games')
    expect(response.status()).toBe(200)
    return response.json() as Promise<{id:string;title:string;board:string}[]>
  }
  const before=await published()
  expect(before.map(g=>g.id)).toEqual(expect.arrayContaining(['robot-maze','drone-route']))
  const title='棋盘回归 '+randomUUID().slice(0,8)
  await page.goto(admin+'/content?tab=games')
  await page.locator('input[autocomplete="username"]').fill('dev-operator')
  await page.locator('input[autocomplete="current-password"]').fill(password!)
  const login=page.waitForResponse(r=>r.url().endsWith('/api/v1/auth/login')&&r.request().method()==='POST')
  await page.locator('form').getByRole('button',{name:'登录'}).click()
  const headers={Authorization:'Bearer '+(await (await login).json()).accessToken}
  await page.getByRole('button',{name:'新增关卡'}).click()
  const dialog=page.getByRole('dialog')
  await dialog.getByLabel('标题',{exact:true}).fill(title)
  await dialog.getByLabel('介绍',{exact:true}).fill('沿第一行收集星星并抵达终点')
  await dialog.getByLabel('技能标签').fill('路线规划')
  await dialog.getByTestId('ops-game-cell-1').click()
  await dialog.getByText('终点',{exact:true}).click()
  await dialog.getByTestId('ops-game-cell-2').click()
  await expect(dialog.getByTestId('ops-game-cell-0')).toBeDisabled()
  const created=page.waitForResponse(r=>r.url().endsWith('/ops/experience/play-games')&&r.request().method()==='POST')
  await dialog.getByRole('button',{name:'保存草稿'}).click()
  const create=await created
  expect(create.status()).toBe(201)
  const game=await create.json()
  expect(game).toMatchObject({title,board:'.SG'+'.'.repeat(22),version:0,published:false,publishedVersion:null})
  const row=page.getByRole('row').filter({hasText:title})
  const id=game.id
  const url=root+'/'+id
  expect((await published()).some(g=>g.id===id)).toBe(false)
  expect((await request.post(url+'/publish',{data:{version:0}})).status()).toBe(401)
  const buyer=await apiLogin(request,BUYER)
  expect(buyer).toBeTruthy()
  expect((await request.post(url+'/publish',{headers:buyer!.headers,data:{version:0}})).status()).toBe(403)
  async function change(action:'publish'|'offline', text:string){
    await row.getByRole('button',{name:text,exact:true}).click()
    const result=page.waitForResponse(r=>r.url().endsWith('/'+id+'/'+action)&&r.request().method()==='POST')
    await page.locator('.el-message-box').getByRole('button',{name:text,exact:true}).click()
    expect((await result).status()).toBe(200)
  }
  await change('publish','发布')
  const publicGame=(await published()).find(g=>g.id===id)
  expect(publicGame).toMatchObject({title,board:game.board})
  expect((await published()).findIndex(g=>g.id===id)).toBeLessThan((await published()).findIndex(g=>g.id==='robot-maze'))
  const app=await context.newPage()
  await app.setViewportSize({width:390,height:844})
  await app.goto('/#/pages/play/index')
  await app.getByTestId('play-game-'+id).click()
  await app.getByTestId('play-move-right').click()
  await app.getByTestId('play-move-right').click()
  await expect(app.getByTestId('play-success')).toBeVisible()
  expect((await request.put(url,{headers,data:{...game,title:'旧草稿'}})).status()).toBe(409)
  const bad='.SGWW'+'WWWWW'+'S....'+'.....'.repeat(2)
  const draft=await request.put(url,{headers,data:{...game,title:title+'草稿',board:bad,version:1}})
  expect(draft.status()).toBe(200)
  const rejected=await request.post(url+'/publish',{headers,data:{version:2}})
  expect(rejected.status()).toBe(422)
  expect(await rejected.json()).toMatchObject({code:'OPS_GAME_BOARD_INVALID',details:{board:expect.stringContaining('星星无法到达')}})
  expect((await published()).find(g=>g.id===id)).toEqual(publicGame)
  await page.reload()
  await expect(row).toContainText('有新草稿')
  const corrected=await request.put(url,{headers,data:{...game,title:title+'第二版',board:game.board,sortOrder:999,version:2}})
  expect(corrected.status()).toBe(200)
  await page.reload()
  await change('publish','发布')
  const newOrder=await published()
  expect(newOrder.find(g=>g.id===id)?.title).toBe(title+'第二版')
  expect(newOrder.findIndex(g=>g.id===id)).toBeGreaterThan(newOrder.findIndex(g=>g.id==='robot-maze'))
  await change('offline','下架')
  expect((await published()).some(g=>g.id===id)).toBe(false)
  await app.goto('/#/pages/play/game?game='+id)
  await app.reload()
  await expect(app.getByTestId('play-game-missing')).toBeVisible()
  const log=await request.get(url+'/changes',{headers})
  expect((await log.json()).map((c:{action:string})=>c.action)).toEqual(['OFFLINE','PUBLISH','EDIT','EDIT','PUBLISH','CREATE'])
  expect((await published()).filter(g=>['robot-maze','drone-route'].includes(g.id))).toEqual(before.filter(g=>['robot-maze','drone-route'].includes(g.id)))
})
