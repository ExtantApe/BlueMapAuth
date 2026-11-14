// BlueMapAuth's bot test on a throwaway Paper 26.1.2 server with the real BlueMap, LuckPerms, HouseRules and Latchkey,
// driven from the console, over HTTP and with mineflayer bots. Built in a temp folder on 127.0.0.1, never a copy of a
// real server, and deleted at the end, pass or fail.
//
// The people, all made up:
//   Kid    in the "family" circle; may enter the overworld only. Away from the game unless the test says otherwise.
//   Mum    in the "family" circle; overworld only. In game.
//   Adult  no circle; may enter the overworld and the End. In game.
//   Guest  no circle; overworld only. In game, in the same world as everyone.
//
//   MK_TEST_JARS        folder with paper-26.1.2.jar, LuckPerms-Bukkit-*.jar, bluemap-*-paper.jar
//   MK_TEST_EULA        an eula.txt the owner already accepted. It's copied, never written.
//   MK_TEST_RUN         where the server is built. Must be under a temp folder.
//   MK_TEST_HOUSERULES  a HouseRules jar with sharesCircle (0.4 or newer, or its offline-circle branch)
//   MK_TEST_LATCHKEY    a Latchkey jar with the map hand-off (0.4 or newer)
//   MK_TEST_JAVA        a Java 25 to run Paper with
//   MK_TEST_MISSING=1   run without HouseRules and LuckPerms instead, to check every missing piece fails closed
import { spawn } from 'node:child_process'
import { createHash, randomBytes } from 'node:crypto'
import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync, appendFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import mineflayer from 'mineflayer'

const must = n => { const v = process.env[n]; if (!v) throw new Error('Set ' + n + ' (see the top of test/live/bluemapauth.mjs)'); return v }
const JAVA = process.env.MK_TEST_JAVA ?? 'java'
const MISSING = process.env.MK_TEST_MISSING === '1'
const JARS = must('MK_TEST_JARS'), EULA = must('MK_TEST_EULA'), HOUSERULES = must('MK_TEST_HOUSERULES'), LATCHKEY = must('MK_TEST_LATCHKEY')
const RUN = path.resolve(must('MK_TEST_RUN'))
if (![os.tmpdir(), '/tmp', '/private/tmp', '/var/folders'].map(p => path.resolve(p)).some(t => RUN.startsWith(t + path.sep)))
  throw new Error('MK_TEST_RUN must be inside a temp folder, not ' + RUN)
if (!/^eula=true$/m.test(readFileSync(EULA, 'utf8'))) throw new Error('That eula.txt isn\'t accepted. Accepting it is the owner\'s call.')
const ROOT = new URL('../../', import.meta.url).pathname
const LIBS = path.join(ROOT, 'build/libs')
const MK = readdirSync(LIBS).filter(f => /^BlueMapAuth-[\d.]+\.jar$/.test(f))
if (MK.length !== 1) throw new Error('Run ./gradlew clean build first: expected one BlueMapAuth jar in build/libs')

const PORT = 25618, LK_WEB = 25692, MAP_WEB = 25694, BLUEMAP = 25695
const PLAY = 'https://play.example.test', MAP = 'https://map.example.test'
const sleep = ms => new Promise(r => setTimeout(r, ms))
const jar = re => { const f = readdirSync(JARS).find(n => re.test(n)); if (!f) throw new Error('No jar matching ' + re); return path.join(JARS, f) }

const results = []
function check(name, ok, detail = '') {
  results.push({ name, ok })
  console.log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail ? '  (' + detail + ')' : ''}`)
  return ok
}

/** The offline-mode uuid Paper gives a name. */
function offlineUuid(name) {
  const h = createHash('md5').update('OfflinePlayer:' + name).digest()
  h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
  const x = h.toString('hex')
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`
}
const KID = offlineUuid('Kid'), MUM = offlineUuid('Mum'), ADULT = offlineUuid('Adult'), GUEST = offlineUuid('Guest')

// --- the server ---

const dir = path.join(RUN, 'bluemapauth-26.1.2')
rmSync(dir, { recursive: true, force: true })
for (const p of ['Latchkey', 'LuckPerms', 'HouseRules', 'BlueMapAuth']) mkdirSync(path.join(dir, 'plugins', p), { recursive: true })
copyFileSync(jar(/^paper-26\.1\.2\.jar$/), path.join(dir, 'paper.jar'))
copyFileSync(EULA, path.join(dir, 'eula.txt'))
if (!MISSING) copyFileSync(jar(/^LuckPerms-Bukkit-.*\.jar$/), path.join(dir, 'plugins/LuckPerms.jar'))
copyFileSync(jar(/^bluemap-.*-paper\.jar$/), path.join(dir, 'plugins/BlueMap.jar'))
if (!MISSING) copyFileSync(HOUSERULES, path.join(dir, 'plugins/HouseRules.jar'))
copyFileSync(LATCHKEY, path.join(dir, 'plugins/Latchkey.jar'))
copyFileSync(path.join(LIBS, MK[0]), path.join(dir, 'plugins/BlueMapAuth.jar'))
writeFileSync(path.join(dir, 'plugins/LuckPerms/config.yml'), 'server: global\nstorage-method: yaml\nwatch-files: false\n')
writeFileSync(path.join(dir, 'server.properties'), [
  'server-ip=127.0.0.1', `server-port=${PORT}`, 'online-mode=false', 'white-list=true', 'enforce-whitelist=true',
  'level-type=minecraft\\:flat', 'generate-structures=false', 'spawn-protection=0', 'enable-query=false', 'enable-rcon=false',
  'view-distance=4', 'simulation-distance=4', 'difficulty=peaceful', 'spawn-monsters=false', 'motd=BlueMapAuth test'].join('\n') + '\n')

/** A plugin's shipped config with some lines replaced; each must still be there, so a changed default is noticed. */
function configFrom(src, dest, pairs) {
  let t = readFileSync(src, 'utf8')
  for (const [a, b] of pairs) {
    if (!t.includes(a)) throw new Error(path.basename(dest) + ': the default config no longer has: ' + a)
    t = t.replace(a, b)
  }
  writeFileSync(dest, t)
}
const fromJar = async (j, file) => (await import('node:child_process')).execFileSync('unzip', ['-p', j, file], { encoding: 'utf8' })
writeFileSync(path.join(dir, 'lk.yml'), await fromJar(LATCHKEY, 'config.yml'))
configFrom(path.join(dir, 'lk.yml'), path.join(dir, 'plugins/Latchkey/config.yml'), [
  ['enabled: false', 'enabled: true'], ['port: 25680', 'port: ' + LK_WEB], ['public-url: ""', 'public-url: "' + PLAY + '"'],
  ['map-address: ""', 'map-address: "' + MAP + '"']])
writeFileSync(path.join(dir, 'hr.yml'), await fromJar(HOUSERULES, 'config.yml'))
configFrom(path.join(dir, 'hr.yml'), path.join(dir, 'plugins/HouseRules/config.yml'), [['circles: []', 'circles:\n  - family']])
configFrom(path.join(ROOT, 'src/main/resources/config.yml'), path.join(dir, 'plugins/BlueMapAuth/config.yml'), [
  ['enabled: false', 'enabled: true'], ['port: 8180', 'port: ' + MAP_WEB], ['public-url: ""', 'public-url: "' + MAP + '"'],
  ['bluemap-url: "http://127.0.0.1:8100"', 'bluemap-url: "http://127.0.0.1:' + BLUEMAP + '"'],
  ['sign-in-url: ""', 'sign-in-url: "' + PLAY + '"'], ['world-permission: "multiverse.access.{world}"', 'world-permission: "mktest.world.{world}"'],
  ['per-ip-per-10-minutes: 10', 'per-ip-per-10-minutes: 20']])

const log = []
let proc, exited
async function start(done = /Done \(\d/) {
  const from = log.length
  proc = spawn(JAVA, ['-Xms1536M', '-Xmx1536M', '-Dterminal.jline=false', '-Dterminal.ansi=false', '-Dlatchkey.allowOfflineForTesting=true',
    '-jar', 'paper.jar', 'nogui'], { cwd: dir, stdio: ['pipe', 'pipe', 'pipe'] })
  exited = new Promise(r => proc.on('exit', r))
  process.on('exit', () => { if (proc.exitCode === null) proc.kill('SIGKILL') })
  const add = d => { for (const l of d.toString().split('\n')) if (l.trim()) log.push(l) }
  proc.stdout.on('data', add); proc.stderr.on('data', add)
  await waitFor(done, 300000, from)
  await sleep(1500)
}
async function waitFor(re, ms = 15000, from = 0) {
  const end = Date.now() + ms
  while (Date.now() < end) {
    const hit = log.slice(from).find(l => re.test(l))
    if (hit) return hit
    if (proc.exitCode !== null) throw new Error('Server exited while waiting for ' + re + ':\n  ' + log.slice(-5).join('\n  '))
    await sleep(50)
  }
  throw new Error('Timed out waiting for ' + re)
}
const send = c => proc.stdin.write(c + '\n')
async function stop() {
  send('stop')
  const done = await Promise.race([exited.then(() => true), sleep(90000).then(() => false)])
  if (!done) { proc.kill('SIGKILL'); await exited }
}

// --- bots and browsers ---

const bots = {}
async function bot(username) {
  const b = mineflayer.createBot({ host: '127.0.0.1', port: PORT, username, version: '26.1', auth: 'offline', viewDistance: 'tiny',
    hideErrors: true, physicsEnabled: false })
  b.on('error', () => {})
  await new Promise((resolve, reject) => {
    b.once('spawn', resolve)
    b.once('kicked', r => reject(new Error(username + ' kicked: ' + (typeof r === 'string' ? r : JSON.stringify(r)))))
    b.once('end', r => reject(new Error(username + ' disconnected before spawning: ' + r)))
    setTimeout(() => reject(new Error(username + ' spawn timeout')), 60000)
  })
  await sleep(1000)
  bots[username] = b
  return b
}
async function leave(name) { bots[name]?.quit(); delete bots[name]; await sleep(1500) }

class Browser {
  jar = new Map()
  constructor(port) { this.port = port }
  async req(p, { method = 'GET', form, body, headers = {} } = {}) {
    const h = { ...headers }
    if (this.jar.size) h.cookie = [...this.jar].map(([k, v]) => k + '=' + v).join('; ')
    if (form) { body = new URLSearchParams(form).toString(); h['content-type'] = 'application/x-www-form-urlencoded' }
    const r = await fetch(`http://127.0.0.1:${this.port}${p}`, { method, headers: h, body, redirect: 'manual' })
    for (const sc of r.headers.getSetCookie()) {
      const [kv] = sc.split(';'); const i = kv.indexOf('=')
      const k = kv.slice(0, i), v = kv.slice(i + 1)
      if (!v || /Max-Age=0/.test(sc)) this.jar.delete(k); else this.jar.set(k, v)
    }
    return { status: r.status, headers: r.headers, body: await r.text() }
  }
  async csrf(p) { const m = /name="csrf" value="([^"]+)"/.exec((await this.req(p)).body); if (!m) throw new Error('no form on ' + p); return m[1] }
}

/** A Latchkey session file as Latchkey writes it, for a cookie the test made. Like having used an invite. */
function plantSession(uuid, name) {
  const cookie = randomBytes(32).toString('base64url')
  const now = Math.floor(Date.now() / 1000)
  const f = path.join(dir, 'plugins/Latchkey/sessions', createHash('sha256').update(cookie).digest('hex') + '.json')
  mkdirSync(path.dirname(f), { recursive: true, mode: 0o700 })
  writeFileSync(f, JSON.stringify({ uuid, name, expires: now + 3600, since: now }) + '\n', { mode: 0o600 })
  return cookie
}

/** The whole hand-off as a browser does it: Latchkey's welcome view, "Open the map", then the map's hand-off page. */
async function openMap(uuid, name) {
  const lk = new Browser(LK_WEB)
  lk.jar.set('latchkey_session', plantSession(uuid, name))
  const csrf = await lk.csrf('/')
  const r = await lk.req('/map', { method: 'POST', form: { csrf }, headers: { origin: PLAY } })
  const where = r.headers.get('location') ?? ''
  if (r.status !== 303 || !where.startsWith(MAP + '/handoff#')) throw new Error(name + ': Open the map gave ' + r.status + ' ' + where)
  const token = where.slice(where.indexOf('#') + 1)
  const map = new Browser(MAP_WEB)
  const h = await map.req('/handoff', { method: 'POST', body: 'token=' + token, headers: { origin: MAP, 'content-type': 'application/x-www-form-urlencoded' } })
  return { lk, map, token, status: h.status, setCookie: h.headers.get('set-cookie') ?? '' }
}

const mapsIn = s => { try { return JSON.parse(s).maps ?? [] } catch { return null } }
const playersIn = s => { try { return (JSON.parse(s).players ?? []).map(p => p.uuid) } catch { return null } }

/**
 * Without HouseRules and LuckPerms (MK_TEST_MISSING=1). Every missing piece answers "no": an away viewer gets no maps
 * (no LuckPerms to read their permissions) and nobody on the map, and an in-game viewer sees only themselves (no
 * HouseRules). Adult is an operator here, so has every world's permission while in game.
 */
async function missingPieces() {
  send('op Adult')
  await sleep(1000)
  await bot('Mum'); await bot('Adult'); await bot('Guest')
  const kid = await openMap(KID, 'Kid')
  check('without LuckPerms or HouseRules, the hand-off still works', kid.status === 204, String(kid.status))
  const kidMaps = mapsIn((await kid.map.req('/settings.json')).body)
  check('an away kid gets no maps: there\'s no LuckPerms to read their permissions', Array.isArray(kidMaps) && kidMaps.length === 0, JSON.stringify(kidMaps))
  check('and no players either', (await kid.map.req('/maps/world/live/players.json')).status === 404)
  const adult = await openMap(ADULT, 'Adult')
  const adultMaps = mapsIn((await adult.map.req('/settings.json')).body)
  check('an operator in game gets the maps they may enter', adultMaps?.length === 3, JSON.stringify(adultMaps))
  const seen = playersIn((await adult.map.req('/maps/world/live/players.json')).body)
  check('but sees only themselves: no HouseRules to say who they may reach', seen?.length === 1 && seen[0] === ADULT, JSON.stringify(seen))
  const status = log.length
  send('bluemapauth status')
  await sleep(800)
  const said = log.slice(status).join('\n')
  check('/bluemapauth status names what\'s missing and what that means', /LuckPerms: missing, so someone who isn't in game sees no maps/.test(said)
    && /HouseRules: missing, so the map shows nobody but the viewer themselves/.test(said))
}

// --- the run ---

let crashed = null
try {
  // First start: BlueMap writes its configs and Paper the worlds. Then BlueMap may download the client's resources, on
  // its own web port, bound to this machine only.
  await start()
  await stop()
  const bm = path.join(dir, 'plugins/BlueMap')
  configFrom(path.join(bm, 'core.conf'), path.join(bm, 'core.conf'), [['accept-download: false', 'accept-download: true']])
  const web = readFileSync(path.join(bm, 'webserver.conf'), 'utf8').replace(/^port: .*$/m, 'port: ' + BLUEMAP)
  writeFileSync(path.join(bm, 'webserver.conf'), web + '\nip: "127.0.0.1"\n')

  await start()
  // BlueMap loads its maps after Paper says Done; wait until its web server lists them.
  let blueMaps = null
  for (let i = 0; i < 180 && !(blueMaps?.length >= 3); i++) {
    try { blueMaps = mapsIn(await (await fetch(`http://127.0.0.1:${BLUEMAP}/settings.json`)).text()) } catch {}
    await sleep(1000)
  }
  check('BlueMap lists the three dimensions\' maps', blueMaps?.length >= 3, JSON.stringify(blueMaps))
  send('bluemapauth reload')
  await waitFor(/Serving the map on 127\.0\.0\.1:\d+ .* [1-9]\d* map\(s\) known/, 20000, log.length - 1).catch(() => {})
  const serving = log.filter(l => /Serving the map/.test(l)).pop() ?? ''
  check('BlueMapAuth serves the map, bound to this machine, and knows BlueMap\'s maps', /127\.0\.0\.1:25694 .* [3-9] map\(s\) known/.test(serving), serving.replace(/^.*Serving/, 'Serving'))

  for (const n of ['Kid', 'Mum', 'Adult', 'Guest']) send('whitelist add ' + n)
  if (MISSING) { await missingPieces(); throw 'done' }
  const perms = [[KID, 'houserules.circle.family'], [MUM, 'houserules.circle.family'],
    [KID, 'mktest.world.world'], [MUM, 'mktest.world.world'], [GUEST, 'mktest.world.world'],
    [ADULT, 'mktest.world.world'], [ADULT, 'mktest.world.world_the_end']]
  for (const [u, node] of perms) { send(`lp user ${u} permission set ${node} true`); await sleep(250) }
  await sleep(2000)

  // Which BlueMap map shows which world: ask as the adult, who may see two of them.
  await bot('Mum'); await bot('Adult'); await bot('Guest')

  // --- 1. a visitor who isn't signed in gets nothing ---
  const stranger = new Browser(MAP_WEB)
  const home = await stranger.req('/')
  check('a visitor who isn\'t signed in gets the sign-in page, not the map', home.status === 401 && /Sign in to see the map/.test(home.body) && !/BlueMap/.test(home.body))
  let leaked = false
  for (const p of ['/settings.json', ...blueMaps.map(m => `/maps/${m}/live/players.json`), ...blueMaps.map(m => `/maps/${m}/settings.json`)]) {
    const r = await stranger.req(p)
    if (r.status !== 401 || /uuid|"maps"|hires/.test(r.body)) leaked = true
  }
  check('nor the map list, a map\'s settings or its players', !leaked)

  // --- 2. an away kid: the hand-off, then only their world and their circle (D54) ---
  const kid = await openMap(KID, 'Kid')
  check('the hand-off signs the kid\'s browser in to the map', kid.status === 204, String(kid.status))
  check('with a host-only, HttpOnly, Secure, Strict cookie', /^mapkeeper=/.test(kid.setCookie) && /HttpOnly/.test(kid.setCookie)
    && /Secure/.test(kid.setCookie) && /SameSite=Strict/.test(kid.setCookie) && !/domain=/i.test(kid.setCookie), kid.setCookie.replace(/=[^;]+/, '=…'))
  const again = await new Browser(MAP_WEB).req('/handoff', { method: 'POST', body: 'token=' + kid.token, headers: { origin: MAP, 'content-type': 'application/x-www-form-urlencoded' } })
  check('the same hand-off link doesn\'t work twice', again.status === 403, String(again.status))

  const adult = await openMap(ADULT, 'Adult')
  const adultMaps = mapsIn((await adult.map.req('/settings.json')).body)
  const kidMaps = mapsIn((await kid.map.req('/settings.json')).body)
  const endMaps = adultMaps.filter(m => !kidMaps.includes(m))
  check('the adult sees the overworld\'s and the End\'s maps', adultMaps.length === 2 && endMaps.length === 1, JSON.stringify(adultMaps))
  check('the kid sees only the overworld\'s map', kidMaps.length === 1 && adultMaps.includes(kidMaps[0]), JSON.stringify(kidMaps))
  const over = kidMaps[0], end = endMaps[0]

  let endLeak = []
  for (const p of [`/maps/${end}/settings.json`, `/maps/${end}/live/players.json`, `/maps/${end}/live/markers.json`, `/maps/${end}/textures.json`]) {
    const r = await kid.map.req(p)
    if (r.status !== 404) endLeak.push(p + ' ' + r.status)
  }
  check('the End\'s map isn\'t there for the kid at all (404, like a map that doesn\'t exist)', endLeak.length === 0, endLeak.join(', '))
  check('while the adult gets it', (await adult.map.req(`/maps/${end}/settings.json`)).status === 200)

  const awayKid = playersIn((await kid.map.req(`/maps/${over}/live/players.json`)).body)
  check('an away kid sees Mum, who shares their circle', awayKid?.includes(MUM), JSON.stringify(awayKid))
  check('and not the adult or the guest, though they\'re in the same world (D54)', !awayKid?.includes(ADULT) && !awayKid?.includes(GUEST))

  // --- 3. in game, chat's reach decides (D41) ---
  const inGameAdult = playersIn((await adult.map.req(`/maps/${over}/live/players.json`)).body)
  check('an adult in game sees the players in their world', inGameAdult?.includes(GUEST) && inGameAdult?.includes(MUM), JSON.stringify(inGameAdult))
  await bot('Kid')
  const inGameKid = playersIn((await kid.map.req(`/maps/${over}/live/players.json`)).body)
  check('the kid in game sees what chat lets them reach: everyone in their world', [MUM, ADULT, GUEST, KID].every(u => inGameKid?.includes(u)), JSON.stringify(inGameKid))
  await leave('Kid')
  const awayAgain = playersIn((await kid.map.req(`/maps/${over}/live/players.json`)).body)
  check('back out of the game, only the circle again', awayAgain?.includes(MUM) && !awayAgain?.includes(ADULT) && !awayAgain?.includes(GUEST), JSON.stringify(awayAgain))

  // --- 4. losing the circle, or the world, reaches the map ---
  send(`lp user ${KID} permission unset houserules.circle.family`)
  await sleep(31000) // LuckPerms answers for an away player are kept for 30 seconds
  const noCircle = playersIn((await kid.map.req(`/maps/${over}/live/players.json`)).body)
  check('out of the circle, an away kid sees no one else', noCircle && !noCircle.includes(MUM) && !noCircle.includes(ADULT), JSON.stringify(noCircle))
  send(`lp user ${KID} permission unset mktest.world.world`)
  await sleep(31000)
  const noWorld = mapsIn((await kid.map.req('/settings.json')).body)
  check('without the world\'s permission, its map is gone from the kid\'s list', Array.isArray(noWorld) && noWorld.length === 0, JSON.stringify(noWorld))
  check('and its players too', (await kid.map.req(`/maps/${over}/live/players.json`)).status === 404)

  // --- 5. signing out of Latchkey ends the map ---
  const out = await adult.lk.req('/signout', { method: 'POST', form: { csrf: await adult.lk.csrf('/') }, headers: { origin: PLAY } })
  check('the adult signs out on the invite page', out.status === 303, String(out.status))
  await sleep(31000)
  check('and within 30 seconds the map signs them out too', (await adult.map.req('/settings.json')).status === 401)
  // Taken off the whitelist: Latchkey ends their sessions, so the map ends within 30 seconds; and even a session
  // planted afterwards (which Latchkey would never issue) can't be handed to the map.
  const mum = await openMap(MUM, 'Mum')
  check('Mum, on the whitelist, opens the map', mum.status === 204 && (await mum.map.req('/settings.json')).status === 200, String(mum.status))
  send('whitelist remove Mum')
  await sleep(31000)
  check('taken off the whitelist, her map session ends within 30 seconds', (await mum.map.req('/settings.json')).status === 401)
  const planted = await openMap(MUM, 'Mum').catch(e => ({ status: 'refused before the hand-off' }))
  check('and she can\'t open the map again, even with a session planted on disk', planted.status !== 204, String(planted.status))

  // --- 6. BlueMap's own server stays on this machine ---
  const bound = readFileSync(path.join(bm, 'webserver.conf'), 'utf8')
  check('BlueMap\'s web server is set to 127.0.0.1 only', /\nip: "127\.0\.0\.1"\s*$/.test(bound))
} catch (e) {
  if (e === 'done') e = null
  crashed = e
  if (e)
  console.log('CRASHED  ' + (e.stack ?? e))
} finally {
  for (const n of Object.keys(bots)) bots[n].quit()
  if (proc && proc.exitCode === null) await stop().catch(() => proc.kill('SIGKILL'))
  const problems = log.filter(l => /BlueMapAuth|bluemapauth/.test(l) && /WARN|ERROR|SEVERE|Exception/.test(l))
  if (problems.length) console.log('BlueMapAuth log lines worth a look:\n  ' + problems.slice(0, 10).join('\n  '))
  writeFileSync(path.join(RUN, 'bluemapauth-server.log'), log.join('\n') + '\n')
  rmSync(dir, { recursive: true, force: true })
}
const failed = results.filter(r => !r.ok).length
console.log(`SUMMARY  ${results.length - failed}/${results.length} passed${crashed ? ', CRASHED' : ''}. Server deleted; log in ${path.join(RUN, 'bluemapauth-server.log')}`)
process.exit(failed || crashed ? 1 : 0)
