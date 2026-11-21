// Minimal offline-mode protocol bot for the LOCAL dev server only (no accounts, no auth).
// Usage: node bot.js [--host 127.0.0.1] [--port 25565] [--name EcBot1] [--version 26.1] [--say "/ec help" ...] [--wait 5000]
// Prints every chat/system message as `CHAT: ...` and exits 0 if it spawned, 1 on kick/error, 2 on protocol mismatch.
const mineflayer = require('mineflayer')

function arg(name, def) { const i = process.argv.indexOf('--' + name); return i > 0 ? process.argv[i + 1] : def }
function args(name) { const out = []; process.argv.forEach((a, i) => { if (a === '--' + name) out.push(process.argv[i + 1]) }); return out }

const host = arg('host', '127.0.0.1')
if (!['127.0.0.1', 'localhost', '::1'].includes(host)) { console.error('refusing non-local host: ' + host); process.exit(1) }
const opts = { host, port: Number(arg('port', 25565)), username: arg('name', 'EcBot1'), auth: 'offline', hideErrors: false }
const version = arg('version', null)
if (version) opts.version = version
const say = args('say')
const waitMs = Number(arg('wait', 5000))

const bot = mineflayer.createBot(opts)
let spawned = false
bot.on('login', () => console.log('LOGIN ok, version=' + bot.version))
bot.on('spawn', async () => {
  spawned = true
  console.log('SPAWN at ' + bot.entity.position)
  for (const s of say) { bot.chat(s); await new Promise(r => setTimeout(r, 1500)) }
  setTimeout(() => { bot.quit(); setTimeout(() => process.exit(0), 500) }, waitMs)
})
bot.on('messagestr', (m) => console.log('CHAT: ' + m))
bot.on('kicked', (r) => { console.log('KICKED: ' + JSON.stringify(r)); process.exit(1) })
bot.on('error', (e) => {
  console.log('ERROR: ' + e.message)
  process.exit(/version|unsupported|protocol/i.test(e.message) ? 2 : 1)
})
setTimeout(() => { console.log(spawned ? 'DONE' : 'TIMEOUT before spawn'); process.exit(spawned ? 0 : 1) }, 60000)
