// 页面走查：用真实浏览器操作前端，经 Java 服务、Python 图像服务、数据库和对象存储走一遍主要功能，每步截图并断言。
// 前提：Java（7302）、图像服务（7303）、中间件和 `npm run dev`（7301）都已启动。
//
//   npm run e2e                                    # 用本机 Chrome（channel=chrome）
//   E2E_BROWSER=/path/to/chromium npm run e2e      # 指定浏览器可执行文件
//
// 环境变量：E2E_BASE（默认 http://127.0.0.1:7301）、E2E_OUT（截图与 summary.json 目录，默认 e2e/output）、
// E2E_IMAGE（上传用图，默认评测集里自绘的白底瓶子图）、E2E_CHAT=0 跳过对话部分（对话会真实调用规划模型）、
// E2E_SCALE（截图像素倍率，默认 2）、E2E_JPEG=1（截图存 JPEG，存证据时用来减小体积）。
// 图像模型（DashScope）没配置时，相关步骤断言的是“明确失败并说明原因”。
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { chromium } from 'playwright-core'

const here = dirname(fileURLToPath(import.meta.url))
const base = process.env.E2E_BASE ?? 'http://127.0.0.1:7301'
const out = resolve(process.env.E2E_OUT ?? resolve(here, 'output'))
const image = resolve(process.env.E2E_IMAGE ?? resolve(here, '../../eval/datasets/agent-behavior-v1/images/bottle-white.png'))
const chat = process.env.E2E_CHAT !== '0'
const scale = Number(process.env.E2E_SCALE ?? 2)
const jpeg = process.env.E2E_JPEG === '1'
mkdirSync(out, { recursive: true })

const steps = []
const started = Date.now()
let shot = 0

function check(condition, what) {
  if (!condition) throw new Error(`断言失败：${what}`)
}

const launch = process.env.E2E_BROWSER ? { executablePath: process.env.E2E_BROWSER } : { channel: 'chrome' }
const browser = await chromium.launch({ headless: true, ...launch })
const context = await browser.newContext({ viewport: { width: 1440, height: 900 }, deviceScaleFactor: scale, acceptDownloads: true })
const page = await context.newPage()
const consoleErrors = []
const httpErrors = []
// 网络错误单独按 URL 记录：登录前读当前用户返回 401 是预期行为，其余 4xx/5xx 都算失败
page.on('console', (msg) => msg.type() === 'error' && !msg.text().startsWith('Failed to load resource') && consoleErrors.push(msg.text()))
page.on('pageerror', (error) => consoleErrors.push(String(error)))
page.on('response', (response) => {
  if (response.status() < 400) return
  const url = new URL(response.url())
  const expected = response.status() === 401 && url.pathname === '/api/auth/me' && !steps.some((s) => s.name === 'login')
  httpErrors.push({ status: response.status(), method: response.request().method(), path: url.pathname, expected })
})

async function step(name, body) {
  const t0 = Date.now()
  try {
    const detail = (await body()) ?? null
    shot += 1
    const file = `${String(shot).padStart(2, '0')}-${name}.${jpeg ? 'jpg' : 'png'}`
    await page.screenshot({ path: resolve(out, file), ...(jpeg ? { type: 'jpeg', quality: 80 } : {}) })
    steps.push({ name, ok: true, ms: Date.now() - t0, screenshot: file, detail })
    console.log(`✓ ${name}${detail ? ' ' + JSON.stringify(detail) : ''}`)
  } catch (error) {
    await page.screenshot({ path: resolve(out, `failed-${name}.png`) }).catch(() => undefined)
    steps.push({ name, ok: false, ms: Date.now() - t0, error: String(error) })
    console.log(`✗ ${name}: ${error}`)
    throw error
  }
}

const revision = async () => Number((await page.getByTestId('revision').innerText()).replace(/\D+/g, ''))
const openTab = (name) => page.getByTestId(`tab-${name}`).click()
const waitRun = async (status = /已完成|失败|已取消/) => {
  await page.getByTestId('run-status').filter({ hasText: status }).waitFor({ timeout: 120_000 })
  // 任务结束后页面还会重新读取会话；“执行”按钮恢复可用表示刷新已完成
  await page.waitForFunction(() => document.querySelector('[data-testid=run-tool]')?.disabled === false)
  return page.getByTestId('run-status').innerText()
}
async function runTool(tool, fill) {
  await openTab('tools')
  await page.getByTestId('tool-select').selectOption(tool)
  if (fill) await fill()
  await page.getByTestId('run-tool').click()
  return waitRun()
}
async function lastTurn(predicate, timeout = 90_000) {
  const deadline = Date.now() + timeout
  while (Date.now() < deadline) {
    const turn = page.getByTestId('turn').last()
    if ((await turn.count()) > 0) {
      const status = await turn.getAttribute('data-status')
      const label = await turn.getByTestId('turn-status').innerText()
      if (predicate(status, label)) return { status, label, steps: await turn.getByTestId('step').count() }
    }
    await page.waitForTimeout(500)
  }
  throw new Error('等待对话结果超时')
}
async function say(text) {
  const before = await page.getByTestId('turn').count()
  await page.getByTestId('chat-input').fill(text)
  await page.getByTestId('chat-send').click()
  await page.getByTestId('turn').nth(before).waitFor({ timeout: 90_000 })
}

try {
  const username = `ui_${Date.now().toString(36)}`
  await step('login', async () => {
    await page.goto(base)
    await page.getByRole('button', { name: '注册', exact: true }).click()
    await page.locator('input[name=username]').fill(username)
    await page.locator('input[name=password]').fill('walkthrough-pass')
    await page.getByRole('button', { name: '注册并登录' }).click()
    await page.getByTestId('current-user').filter({ hasText: username }).waitFor()
    return { username }
  })

  await step('upload-and-session', async () => {
    await page.getByTestId('upload-input').setInputFiles(image)
    await page.waitForURL(/#\/s\//)
    await page.locator('[data-testid=stage] img.layer').first().waitFor()
    check((await revision()) === 1, '新会话修订号为 1')
    return { revision: 1 }
  })

  await step('tool-flip-document', async () => {
    const status = await runTool('flip_layer', () => page.locator('select[name=direction]').selectOption('horizontal'))
    check(status.includes('已完成'), `翻转完成：${status}`)
    check((await revision()) === 2, '翻转后修订号为 2')
    return { status: status.split('\n')[0], revision: 2 }
  })

  await step('tool-adjust-image-sse', async () => {
    const status = await runTool('adjust_image', async () => {
      const slider = page.locator('input[type=range][name=brightness]')
      await slider.fill('0.4')
    })
    check(status.includes('已完成') && status.includes('100%'), `调色经队列完成：${status}`)
    check((await revision()) === 3, '调色后修订号为 3')
    return { status: status.split('\n').join(' ') }
  })

  await step('undo-redo-history', async () => {
    await page.getByTestId('undo').click()
    await page.waitForFunction(() => document.querySelector('[data-testid=revision]')?.textContent?.includes('2'))
    await page.getByTestId('redo').click()
    await page.waitForFunction(() => document.querySelector('[data-testid=revision]')?.textContent?.includes('3'))
    await openTab('history')
    const rows = await page.getByTestId('history').locator('li').count()
    check(rows >= 3, '历史至少 3 条')
    return { history: rows }
  })

  await step('brush-selection', async () => {
    await page.getByTestId('mode-brush').click()
    const box = await page.getByTestId('selection-overlay').boundingBox()
    await page.mouse.move(box.x + box.width * 0.35, box.y + box.height * 0.3)
    await page.mouse.down()
    for (const [x, y] of [[0.65, 0.3], [0.65, 0.7], [0.35, 0.7], [0.35, 0.32]]) {
      await page.mouse.move(box.x + box.width * x, box.y + box.height * y, { steps: 8 })
    }
    await page.mouse.up()
    await page.getByTestId('selection-mask').waitFor()
    await page.getByTestId('mode-off').click()
    return { selection: true }
  })

  await step('model-tool-without-key', async () => {
    const status = await runTool('replace_region', () => page.locator('input[name=prompt], textarea[name=prompt]').fill('换成红色'))
    check(status.includes('失败') && status.includes('DASHSCOPE_API_KEY'), `未配置图像模型时明确失败：${status}`)
    check((await revision()) === 3, '失败不改画布')
    return { error: status.split('\n').pop() }
  })

  await step('remove-background-layers', async () => {
    await page.getByTestId('clear-selection').click()
    const status = await runTool('remove_background')
    check(status.includes('已完成'), `去背景完成：${status}`)
    await openTab('layers')
    const rows = await page.getByTestId('layer-row').count()
    check(rows >= 1, '图层面板有图层')
    return { layers: rows, revision: await revision() }
  })

  await step('delivery-sizes-wall', async () => {
    const before = await revision()
    const status = await runTool('prepare_delivery_sizes')
    check(status.includes('已完成'), `投放尺寸完成：${status}`)
    check((await revision()) === before, '投放尺寸只进图片墙，修订号不变')
    await openTab('wall')
    const exports = await page.locator('[data-testid=wall] figure[data-kind=export]').count()
    check(exports === 3, `图片墙有 3 张投放图，实际 ${exports}`)
    return { exports }
  })

  if (chat) {
    await step('chat-multi-step-waits', async () => {
      await openTab('chat')
      const before = await revision()
      await say('先水平翻转，再把亮度调高一点')
      const turn = await lastTurn((status, label) => label === '等待确认' || status === 'failed' || status === 'succeeded')
      check(turn.label === '等待确认' && turn.steps >= 2, `多步计划等待确认：${JSON.stringify(turn)}`)
      check((await revision()) === before, '确认前不执行')
      return turn
    })

    await step('chat-confirm-executes', async () => {
      const before = await revision()
      await page.getByTestId('turn').last().getByTestId('confirm').click()
      const turn = await lastTurn((status) => status === 'succeeded' || status === 'failed', 120_000)
      check(turn.status === 'succeeded', `确认后执行完成：${JSON.stringify(turn)}`)
      await page.waitForFunction((rev) => Number(document.querySelector('[data-testid=revision]')?.textContent?.replace(/\D+/g, '')) > rev, before)
      return { ...turn, revision: await revision() }
    })

    await step('chat-cancel', async () => {
      await say('把图片上下翻转，然后透明度改成 60%')
      const waiting = await lastTurn((status, label) => label === '等待确认' || status !== 'queued')
      check(waiting.label === '等待确认', `第二个多步计划等待确认：${JSON.stringify(waiting)}`)
      const before = await revision()
      await page.getByTestId('turn').last().getByTestId('cancel').click()
      const turn = await lastTurn((status) => status === 'canceled')
      check((await revision()) === before, '取消后画布不变')
      return turn
    })

    await step('chat-unrelated', async () => {
      await say('帮我写一首关于春天的诗')
      const turn = await lastTurn((status) => status !== 'queued' && status !== 'running')
      check(turn.steps === 0, `无关请求没有计划：${JSON.stringify(turn)}`)
      await page.getByTestId('turn').last().getByRole('button', { name: '规划详情' }).click()
      const trace = await page.getByTestId('trace').last().innerText()
      return { ...turn, trace: trace.split('\n').slice(0, 6).join(' ') }
    })
  }

  await step('batch', async () => {
    await page.goto(`${base}/#/batch`)
    await page.locator('[data-testid=batch-picker] button').first().waitFor()
    await page.locator('[data-testid=batch-picker] button').first().click()
    await page.getByRole('button', { name: '添加操作' }).click()
    const second = page.getByTestId('batch-op').nth(1)
    await second.locator('input[type=checkbox][value="1:1"]').check()
    await page.getByTestId('formats').locator('input[type=checkbox]').nth(1).check()
    await page.getByTestId('batch-submit').click()
    await page.getByTestId('batch-result').waitFor({ timeout: 120_000 })
    const summary = await page.getByTestId('batch-result').locator('p').first().innerText()
    check(summary.includes('1 / 1 张成功') && summary.includes('共 2 个文件'), `批量结果：${summary}`)
    const [download] = await Promise.all([page.waitForEvent('download'), page.getByTestId('batch-export').click()])
    const path = resolve(out, 'batch.zip')
    await download.saveAs(path)
    return { summary: summary.replace(/\s+/g, ' '), zip: 'batch.zip' }
  })

  await step('generate-without-key', async () => {
    await page.goto(`${base}/#/generate`)
    await page.locator('textarea[name=prompt]').fill('白色背景上的一只运动鞋')
    await page.getByTestId('generate').click()
    await page.locator('.error').filter({ hasText: 'DASHSCOPE_API_KEY' }).waitFor({ timeout: 60_000 })
    return { error: await page.locator('.error').innerText() }
  })

  check(consoleErrors.length === 0, `浏览器控制台没有报错：${consoleErrors.join(' | ')}`)
  const unexpected = httpErrors.filter((e) => !e.expected)
  check(unexpected.length === 0, `没有意外的 HTTP 错误：${JSON.stringify(unexpected)}`)
} finally {
  const summary = {
    base,
    image: image.split('/').slice(-1)[0],
    chat,
    started_at: new Date(started).toISOString(),
    elapsed_ms: Date.now() - started,
    passed: steps.filter((s) => s.ok).length,
    total: steps.length,
    console_errors: consoleErrors,
    http_errors: httpErrors,
    steps,
  }
  writeFileSync(resolve(out, 'summary.json'), JSON.stringify(summary, null, 2))
  await browser.close()
  console.log(`${summary.passed}/${summary.total} 步通过，截图在 ${out}`)
  if (summary.passed !== summary.total || consoleErrors.length || httpErrors.some((e) => !e.expected)) process.exitCode = 1
}
