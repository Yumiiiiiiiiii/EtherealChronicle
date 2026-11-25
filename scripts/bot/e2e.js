// Overworld E2E scenario bot for the LOCAL dev server only (offline mode, must be OP: the orchestrator runs `op <name>`).
// Usage: node e2e.js [--host 127.0.0.1] [--port 25599] [--name EcE2E] [--version 26.1]
// Output: one `RESULT <PASS|FAIL> <step> :: <detail>` line per step. Exit code 0 = all passed, 1 = a step failed, 2 = could not join.
// 判定はチャットではなく座標・インベントリ・死亡/リスポーンの実状態を主に使い、言語（クライアント locale=en_us）依存を抑えています。
const mineflayer = require('mineflayer')

function arg(name, def) { const i = process.argv.indexOf('--' + name); return i > 0 ? process.argv[i + 1] : def }
const host = arg('host', '127.0.0.1')
if (!['127.0.0.1', 'localhost', '::1'].includes(host)) { console.error('refusing non-local host: ' + host); process.exit(2) }
const name = arg('name', 'EcE2E')
const opts = { host, port: Number(arg('port', 25599)), username: name, auth: 'offline', hideErrors: false, brand: 'vanilla', locale: 'en_us', physicsEnabled: false }
const version = arg('version', null)
if (version) opts.version = version

// プロトコル非対応などの致命的エラーでハングしないよう、未処理例外は即座に終了コード 2 で落とす。
process.on('uncaughtException', (e) => { console.log('FATAL ' + e.message); process.exit(2) })
process.on('unhandledRejection', (e) => { console.log('FATAL ' + (e && e.message || e)); process.exit(2) })

const sleep = (ms) => new Promise(r => setTimeout(r, ms))
const chatLog = []
const results = []
let bot

function record(step, ok, detail) {
  results.push(ok)
  console.log(`RESULT ${ok ? 'PASS' : 'FAIL'} ${step} :: ${detail}`)
}

/** cond() が真になるまで待つ。タイムアウトで false。 */
async function waitFor(cond, timeoutMs, pollMs = 250) {
  const end = Date.now() + timeoutMs
  while (Date.now() < end) {
    try { if (cond()) return true } catch (_) { /* 状態未確定は無視して再試行 */ }
    await sleep(pollMs)
  }
  try { return !!cond() } catch (_) { return false }
}
const sawChat = (re, since) => chatLog.slice(since).some(m => re.test(m))
const send = (cmd) => { console.log('SEND ' + cmd); bot.chat(cmd) }
const pos = () => bot.entity.position
const itemCount = () => bot.inventory.items().reduce((n, i) => n + i.count, 0)

/** 1 ステップを実行し、例外は FAIL として記録して続行します。 */
async function step(title, fn) {
  try { await fn() } catch (e) { record(title, false, 'exception: ' + e.message) }
}

async function main() {
  // --- join ---
  try { bot = mineflayer.createBot(opts) } catch (e) { console.log('ERROR ' + e.message); process.exit(2) }
  bot.on('messagestr', (m) => { chatLog.push(m); console.log('CHAT: ' + m) })
  bot.on('kicked', (r) => { console.log('KICKED: ' + JSON.stringify(r)); process.exit(2) })
  bot.on('error', (e) => console.log('ERROR: ' + e.message))
  // 死亡したら自動でリスポーンします（リスポーン後の状態を検証するため）。
  let deaths = 0
  bot.on('death', () => { deaths++; setTimeout(() => { try { bot.respawn() } catch (_) {} }, 500) })
  // 移動パケットは送らない（ViaProxy 経由の 26.1→26.3 変換で「Invalid move player packet」になり蹴られるため）。位置はサーバーのテレポート命令で動く。
  const rawWrite = bot._client.write.bind(bot._client)
  bot._client.write = (n, d) => { if (/^(position|position_look|look|flying)$/.test(n)) return; return rawWrite(n, d) }
  if (process.env.EC_BOT_DEBUG) {
    bot._client.on('packet', (d, m) => { if (/position|teleport|look|respawn|login/.test(m.name)) console.log('IN  ' + m.name + ' ' + JSON.stringify(d).slice(0, 300)) })
    const w = bot._client.write
    bot._client.write = (n, d) => { console.log('OUT ' + n); return w(n, d) }
  }
  const joined = await new Promise((resolve) => { bot.once('spawn', () => resolve(true)); setTimeout(() => resolve(false), 60000) })
  if (!joined) { console.log('ERROR could not join'); process.exit(2) }
  console.log('SPAWN at ' + pos())
  await sleep(3000)

  // --- 1. 職業を管理コマンドで設定し、初期武器が配布される ---
  await step('class-and-starter-weapon', async () => {
    send(`/ec class ${name} ec:class_warrior`)
    const ok = await waitFor(() => itemCount() > 0, 15000)
    record('class-and-starter-weapon', ok, `inventory items=${itemCount()}`)
  })

  // --- 2. ルミナ港（初回スポーン地点）へ移動できる ---
  await step('travel-lumina', async () => {
    const before = chatLog.length
    send('/ec travel lumina_port')
    const ok = await waitFor(() => sawChat(/Traveled to/i, before), 30000)
    record('travel-lumina', ok, `pos=${pos()}`)
  })

  // --- 3. 遠方拠点へ移動（チャンク未ロードでも成功する） ---
  for (const area of ['ashwood_forest', 'frostpeak_outpost']) {
    await step('travel-' + area, async () => {
      const before = chatLog.length
      send('/ec travel ' + area)
      const ok = await waitFor(() => sawChat(/Traveled to/i, before), 45000)
      record('travel-' + area, ok, `pos=${pos()}`)
    })
  }
  // 拠点（安全地帯）ではダメージが無効のため、死亡検証は危険地帯（アッシュウッド）で行う
  send('/ec travel ashwood_forest'); await sleep(5000)

  // --- 4. 死亡: アイテム・経験値は保持され、ゴールドのみ失う ---
  await step('death-gold-only', async () => {
    send(`/ec currency ${name} 500`); await sleep(1500)
    const itemsBefore = itemCount()
    const before = chatLog.length
    const deathsBefore = deaths
    send(`/kill ${name}`)
    const died = await waitFor(() => deaths > deathsBefore, 10000)
    const lost = await waitFor(() => sawChat(/lost 500 gold/i, before), 10000)
    await sleep(3000)   // リスポーン完了待ち
    const itemsAfter = itemCount()
    record('death-gold-only', died && lost && itemsAfter >= itemsBefore,
      `died=${died} goldMsg=${lost} items ${itemsBefore}->${itemsAfter}`)
  })
  await step('death-no-gold-no-message', async () => {
    const before = chatLog.length
    const deathsBefore = deaths
    send(`/kill ${name}`)
    await waitFor(() => deaths > deathsBefore, 10000)
    await sleep(2500)
    record('death-no-gold-no-message', !sawChat(/lost \d+ gold/i, before), 'no loss message when gold is 0')
  })

  // --- 4b. 入口から遠い場所での入場は、原因が分かるメッセージで拒否される ---
  await step('dungeon-too-far-message', async () => {
    send('/ec travel lumina_port'); await sleep(5000)
    const before = chatLog.length
    send('/ec dungeon enter ec:dungeon_novice_arena')
    const ok = await waitFor(() => sawChat(/too far from the dungeon entrance/i, before), 15000)
    record('dungeon-too-far-message', ok && pos().y < 150, `pos=${pos()}`)
  })

  // --- 5. ダンジョン入退場（空中アリーナへ入り、地上へ戻る） ---
  await step('dungeon-enter-leave', async () => {
    send(`/ec flag ${name} ec:flag_early_trial_unlocked set`); await sleep(1500)
    // 入口チャンクを読み込み済みにするため、ノービスアリーナの入口があるアッシュウッドへ移動してから入場する
    send('/ec travel ashwood_forest'); await sleep(6000)
    const ground = pos().y
    send('/ec dungeon enter ec:dungeon_novice_arena')
    const entered = await waitFor(() => pos().y > 150, 60000)
    const inArena = entered ? `${pos()}` : 'not entered'
    await sleep(2000)
    send('/ec dungeon leave')
    const left = await waitFor(() => pos().y < 150, 60000)
    record('dungeon-enter-leave', entered && left, `ground y=${ground} arena=${inArena} back=${pos()}`)
  })

  bot.quit()
  setTimeout(() => process.exit(results.every(Boolean) ? 0 : 1), 800)
}

main().catch((e) => { console.log('FATAL ' + e.message); process.exit(1) })
setTimeout(() => { console.log('TIMEOUT whole scenario'); process.exit(1) }, 15 * 60 * 1000)
