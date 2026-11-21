<!--
  录像深度解析面板（单场复盘页里，选中玩家的下方）
  后端 /api/review/matches/{id}/replay：找本机 Dota 录像 → 没有就从 Valve 下载 → clarity 解析
  显示：
    - 小地图：热力图（这个人活着时都待在哪）+ 死亡位置；或者"回放"模式，拖时间轴看 10 个英雄的走位
    - 各阶段在哪些区域花了多少时间、每分钟主要在哪、抱团率
    - 每次死亡：时间、区域、身边队友 / 敌人数量，点一下跳到死前 10 秒的回放
  地图是按坐标画的示意图（三条线、河道、两边基地），不是游戏里的小地图贴图。
-->
<script setup>
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { Film, Play, Pause, Upload, RefreshCw, Flame, Skull } from 'lucide-vue-next'
import { getReplay, startReplay, uploadReplay } from '../api/review'
import { formatClock } from '../domain/review'
import { ZONE_COLORS, toCanvas, findReplayPlayer, deathVerdict, zoneBars, frameIndexAt } from '../domain/replay'

const props = defineProps({
  matchId: { type: String, required: true },
  player: { type: Object, default: null },        // 当前选中的 PlayerReview
  players: { type: Array, default: () => [] }     // 10 个人，用来给回放里的点标英雄名
})

const SIZE = 420
const status = ref(null)
const busy = ref(false)
const mode = ref('heat')          // heat 热力图 / play 回放
const frame = ref(0)
const playing = ref(false)
const canvas = ref(null)
const fileInput = ref(null)
let pollTimer = null
let playTimer = null

const result = computed(() => (status.value?.state === 'done' ? status.value.result : null))
const meIndex = computed(() => findReplayPlayer(result.value, props.player))
const me = computed(() => (meIndex.value >= 0 ? result.value.players[meIndex.value] : null))
const trail = computed(() => result.value?.trail || [])
const currentT = computed(() => trail.value[frame.value]?.[0] ?? 0)

// 回放里每个点的英雄名：按 slug 对上复盘页的 10 个人
const names = computed(() => (result.value?.players || []).map((rp) => {
  const p = props.players.find((x) => (rp.accountId && x.accountId === rp.accountId) || x.hero?.slug === rp.slug)
  return p?.hero?.name || rp.slug || '?'
}))

async function refresh() {
  try {
    status.value = await getReplay(props.matchId)
  } catch (e) {
    status.value = { state: 'error', message: e.message }
  }
  schedulePoll()
}

function schedulePoll() {
  clearTimeout(pollTimer)
  if (status.value?.state === 'running') pollTimer = setTimeout(refresh, 2000)
}

async function start() {
  busy.value = true
  try {
    status.value = await startReplay(props.matchId)
  } catch (e) {
    status.value = { state: 'error', message: e.message }
  } finally {
    busy.value = false
  }
  schedulePoll()
}

async function onFile(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  busy.value = true
  status.value = { state: 'running', message: `正在上传 ${(file.size / 1048576).toFixed(0)} MB 的录像…` }
  try {
    status.value = await uploadReplay(props.matchId, file)
  } catch (e) {
    status.value = { state: 'error', message: e.message }
  } finally {
    busy.value = false
  }
  schedulePoll()
}

// ---------- 画图 ----------
function draw() {
  const c = canvas.value
  const r = result.value
  if (!c || !r) return
  const ctx = c.getContext('2d')
  const dpr = window.devicePixelRatio || 1
  c.width = SIZE * dpr
  c.height = SIZE * dpr
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  const P = (x, y) => toCanvas(x, y, SIZE, r.mapMin, r.mapMax)
  drawMap(ctx, P)
  if (mode.value === 'heat') drawHeat(ctx, P, r)
  else drawFrame(ctx, P, r)
}

/** 示意地图：底色、两边基地、河道、三条线 */
function drawMap(ctx, P) {
  ctx.fillStyle = '#18231b'
  ctx.fillRect(0, 0, SIZE, SIZE)
  const poly = (pts, fill) => {
    ctx.beginPath()
    pts.forEach(([x, y], i) => { const [px, py] = P(x, y); i ? ctx.lineTo(px, py) : ctx.moveTo(px, py) })
    ctx.closePath()
    ctx.fillStyle = fill
    ctx.fill()
  }
  poly([[-8800, -8800], [-4800, -8800], [-4800, -4400], [-8800, -4400]], 'rgba(63,174,106,0.25)')
  poly([[4600, 4000], [8800, 4000], [8800, 8800], [4600, 8800]], 'rgba(227,85,72,0.25)')
  // 河道：x + y ≈ 0 的斜带
  poly([[-8800, 7700], [-8800, 9900], [9900, -8800], [7700, -8800]], 'rgba(79,195,217,0.18)')
  const line = (pts) => {
    ctx.beginPath()
    pts.forEach(([x, y], i) => { const [px, py] = P(x, y); i ? ctx.lineTo(px, py) : ctx.moveTo(px, py) })
    ctx.strokeStyle = 'rgba(214,196,150,0.35)'
    ctx.lineWidth = 7
    ctx.lineJoin = 'round'
    ctx.stroke()
  }
  line([[-6600, -4400], [-6400, 6100], [4600, 6300]])
  line([[-4800, -6600], [6300, -6300], [6500, 4000]])
  line([[-5000, -4600], [4800, 4200]])
}

function drawHeat(ctx, P, r) {
  if (!me.value) return
  const g = r.grid
  const heat = me.value.heat
  const max = Math.max(1, ...heat)
  const cell = SIZE / g
  for (let row = 0; row < g; row++) {
    for (let col = 0; col < g; col++) {
      const v = heat[row * g + col]
      if (!v) continue
      const a = Math.min(0.9, 0.15 + 0.75 * Math.sqrt(v / max))
      ctx.fillStyle = `rgba(240,178,50,${a})`
      ctx.fillRect(col * cell, SIZE - (row + 1) * cell, cell + 0.5, cell + 0.5)
    }
  }
  // 死亡位置：红叉
  for (const d of me.value.deaths) {
    const [x, y] = P(d.x, d.y)
    ctx.strokeStyle = '#ff4d4d'
    ctx.lineWidth = 2.5
    ctx.beginPath()
    ctx.moveTo(x - 6, y - 6); ctx.lineTo(x + 6, y + 6)
    ctx.moveTo(x + 6, y - 6); ctx.lineTo(x - 6, y + 6)
    ctx.stroke()
  }
}

function drawFrame(ctx, P, r) {
  const f = trail.value[frame.value]
  if (!f) return
  r.players.forEach((p, i) => {
    const alive = f[3 + 3 * i] === 1
    const [x, y] = P(f[1 + 3 * i], f[2 + 3 * i])
    const mine = i === meIndex.value
    ctx.beginPath()
    ctx.arc(x, y, mine ? 8 : 6, 0, Math.PI * 2)
    ctx.fillStyle = !alive ? 'rgba(140,140,140,0.5)' : p.team === 2 ? '#3fae6a' : '#e35548'
    ctx.fill()
    if (mine) {
      ctx.lineWidth = 2.5
      ctx.strokeStyle = '#f0b232'
      ctx.stroke()
    }
    ctx.font = '11px sans-serif'
    ctx.fillStyle = alive ? '#fff' : '#999'
    ctx.fillText(names.value[i].slice(0, 4), x + 9, y + 4)
  })
}

function togglePlay() {
  playing.value = !playing.value
  clearInterval(playTimer)
  if (playing.value) {
    playTimer = setInterval(() => {
      if (frame.value >= trail.value.length - 1) { playing.value = false; clearInterval(playTimer); return }
      frame.value++
    }, 125)   // 每秒 8 帧 = 游戏里 16 秒
  }
}

function jumpToDeath(d) {
  mode.value = 'play'
  frame.value = frameIndexAt(trail.value, Math.max(0, d.t - 10))
}

const phaseRows = computed(() => (me.value?.phases || []).map((ph) => ({ ...ph, bars: zoneBars(ph.share) })))

watch(() => props.matchId, () => { status.value = null; frame.value = 0; refresh() }, { immediate: true })
watch([result, meIndex, mode, frame], () => nextTick(draw))
onBeforeUnmount(() => { clearTimeout(pollTimer); clearInterval(playTimer) })
</script>

<template>
  <section class="panel card replay">
    <h3><Film :size="17" />录像深度解析</h3>

    <template v-if="!status || status.state === 'none' || status.state === 'error'">
      <p class="muted">
        读这场比赛的录像文件，看你每秒都在地图哪里：各阶段在线上、野区、河道各待了多久，抱团率，每次死前身边有几个队友和敌人，还能拖时间轴看 10 个人的走位。
      </p>
      <p class="muted small">
        会先找你电脑上 Dota 的录像文件夹（在客户端里下载过录像的比赛），没有就从 Valve 服务器下载（约 70 MB，一般只保留最近两周）。
      </p>
      <p v-if="status?.message" :class="status.state === 'error' ? 'err' : 'ok'">{{ status.message }}</p>
      <div class="actions">
        <button :disabled="busy" @click="start"><RefreshCw :size="14" />{{ status?.state === 'error' ? '重试' : '开始解析' }}</button>
        <button :disabled="busy" @click="fileInput.click()"><Upload :size="14" />选择录像文件</button>
        <input ref="fileInput" type="file" accept=".dem,.bz2" hidden @change="onFile" />
      </div>
    </template>

    <p v-else-if="status.state === 'running'" class="running"><span class="spinner"></span>{{ status.message || '解析中…' }}</p>

    <template v-else-if="result">
      <p v-if="!me" class="muted">录像里没找到这名玩家（可能是观战位），在上面换一个人看看。</p>
      <div v-else class="grid">
        <div class="map-col">
          <div class="mode-tabs">
            <button :class="{ active: mode === 'heat' }" @click="mode = 'heat'"><Flame :size="13" />热力图</button>
            <button :class="{ active: mode === 'play' }" @click="mode = 'play'"><Play :size="13" />回放</button>
          </div>
          <canvas ref="canvas" class="map" :style="{ width: SIZE + 'px', height: SIZE + 'px' }"></canvas>
          <div v-if="mode === 'play'" class="player-bar">
            <button class="icon" @click="togglePlay"><Pause v-if="playing" :size="14" /><Play v-else :size="14" /></button>
            <input v-model.number="frame" type="range" min="0" :max="trail.length - 1" />
            <span class="clock">{{ formatClock(currentT) }}</span>
          </div>
          <p class="muted small">示意地图：绿色天辉、红色夜魇，金圈是你；热力图里红叉是死亡位置。区域按坐标估算。</p>
        </div>

        <div class="stats-col">
          <div class="stat-line">
            <span>抱团率</span>
            <strong>{{ Math.round(me.groupRate * 100) }}%</strong>
            <small>活着的时间里，身边 1500 码内有 2 个以上队友的比例</small>
          </div>

          <h4>各阶段待在哪</h4>
          <div v-for="ph in phaseRows" :key="ph.label" class="phase">
            <span class="phase-label">{{ ph.label }}</span>
            <div class="bar">
              <span v-for="b in ph.bars" :key="b.zone" :style="{ width: b.pct * 100 + '%', background: ZONE_COLORS[b.zone] }" :title="`${b.zone} ${Math.round(b.pct * 100)}%`"></span>
            </div>
            <div class="legend">
              <span v-for="b in ph.bars" :key="b.zone"><i :style="{ background: ZONE_COLORS[b.zone] }"></i>{{ b.zone }} {{ Math.round(b.pct * 100) }}%</span>
            </div>
          </div>

          <h4>每分钟主要在哪</h4>
          <div class="minutes">
            <span v-for="(z, m) in me.minuteZones" :key="m" :style="{ background: z ? ZONE_COLORS[z] : 'transparent' }" :title="`${m}:00 ${z || '阵亡'}`"></span>
          </div>

          <h4><Skull :size="14" />死亡 {{ me.deaths.length }} 次</h4>
          <p v-if="!me.deaths.length" class="muted small">这场没死过。</p>
          <button v-for="(d, i) in me.deaths" :key="i" class="death" @click="jumpToDeath(d)">
            <span class="clock">{{ formatClock(d.t) }}</span>
            <span>{{ d.zone }}</span>
            <span>队友 {{ d.allies }} · 敌人 {{ d.enemies }}</span>
            <span class="tag" :class="deathVerdict(d).level">{{ deathVerdict(d).text }}</span>
          </button>
          <p v-if="me.deaths.length" class="muted small">点一条死亡记录，跳到死前 10 秒的回放。</p>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.replay h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 12px;
}
.muted { color: var(--muted); font-size: 13px; }
.small { font-size: 12px; }
.err { color: #e35548; font-size: 13px; }
.ok { color: var(--gold); font-size: 13px; }
.actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; }
.actions button, .mode-tabs button, .icon {
  display: inline-flex; align-items: center; gap: 6px;
}
.running { display: flex; align-items: center; gap: 10px; color: var(--gold); }
.spinner {
  width: 14px; height: 14px; border-radius: 50%;
  border: 2px solid var(--gold); border-top-color: transparent;
  animation: spin 0.8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }
.grid { display: flex; gap: 24px; flex-wrap: wrap; }
.map-col { flex: 0 0 auto; max-width: 100%; }
.stats-col { flex: 1; min-width: 260px; }
.map { display: block; border-radius: 8px; border: 1px solid var(--border); max-width: 100%; }
.mode-tabs { display: flex; gap: 6px; margin-bottom: 8px; }
.mode-tabs button.active { border-color: var(--gold); color: var(--gold); }
.player-bar { display: flex; align-items: center; gap: 8px; margin-top: 8px; }
.player-bar input { flex: 1; }
.clock { font-variant-numeric: tabular-nums; min-width: 44px; }
.stat-line { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
.stat-line strong { font-size: 22px; color: var(--gold); }
.stat-line small { color: var(--muted); font-size: 12px; }
h4 { display: flex; align-items: center; gap: 6px; font-size: 14px; margin: 18px 0 8px; color: var(--text); }
.phase { margin-bottom: 10px; }
.phase-label { font-size: 12px; color: var(--muted); }
.bar { display: flex; height: 14px; border-radius: 4px; overflow: hidden; margin: 4px 0; background: var(--border); }
.legend { display: flex; flex-wrap: wrap; gap: 4px 10px; font-size: 12px; color: var(--muted); }
.legend i { display: inline-block; width: 8px; height: 8px; border-radius: 2px; margin-right: 4px; }
.minutes { display: flex; gap: 1px; height: 16px; }
.minutes span { flex: 1; border-radius: 1px; min-width: 2px; }
.death {
  display: flex; gap: 12px; align-items: center; flex-wrap: wrap;
  width: 100%; text-align: left; padding: 6px 8px; margin-bottom: 4px;
  font-size: 13px; background: transparent; border: 1px solid var(--border); border-radius: 6px;
  color: var(--text); cursor: pointer;
}
.death:hover { border-color: var(--gold); }
.tag { font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--muted); color: var(--muted); }
.tag.bad { border-color: #e35548; color: #e35548; }
</style>
