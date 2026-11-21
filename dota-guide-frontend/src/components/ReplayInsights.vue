<!--
  录像深度解析的第二部分（在 ReplayPanel 里用）
    part="summary"：这局最该改的 3 件事（后端 ReplayInsights.issues 按规则打分取前 3）
    part="detail"： 团战（到没到、晚几秒、谁先倒）、经济 / 经验 / 补刀曲线（和对位比，标出低效时段）、插眼和排眼
  点团战会 emit('seek', 秒)，让面板的回放跳过去。
-->
<script setup>
import { ref, computed, watch } from 'vue'
import { Target, Swords, TrendingUp, Eye } from 'lucide-vue-next'
import { formatClock } from '../domain/review'
import { fillHeroNames, presenceText, fightOutcome, firstDown, linePoints, wardLife, unitName } from '../domain/replay'

const props = defineProps({
  result: { type: Object, required: true },
  meIndex: { type: Number, required: true },
  names: { type: Array, default: () => [] },
  part: { type: String, default: 'detail' }
})
const emit = defineEmits(['seek'])

const me = computed(() => props.result.players[props.meIndex])
const teams = computed(() => props.result.players.map((p) => p.team))
const slugs = computed(() => props.result.players.map((p) => p.slug))
const who = (i, name) => unitName(name, i, props.names, slugs.value)

// ---------- 总结 ----------
const issues = computed(() => (me.value?.issues || []).map((it) => ({
  ...it,
  title: fillHeroNames(it.title, props.names),
  detail: fillHeroNames(it.detail, props.names)
})))

// ---------- 团战 ----------
const fights = computed(() => (props.result.fights || []).map((f, k) => ({
  k,
  f,
  outcome: fightOutcome(f, me.value.team),
  presence: presenceText(f, props.meIndex),
  first: firstDown(f, teams.value, me.value.team),
  mine: f.presence[props.meIndex]
})))
const fightStats = computed(() => {
  const list = fights.value
  const big = list.filter((x) => x.f.deathOrder.length >= 3)
  const present = big.filter((x) => x.mine?.present).length
  return { total: list.length, big: big.length, present }
})

// ---------- 经济 ----------
const METRICS = [
  { key: 'netWorth', label: '身价' },
  { key: 'xp', label: '经验' },
  { key: 'lastHits', label: '正补' },
  { key: 'gold', label: '总收入' }
]
const metric = ref('netWorth')
const opp = ref(-1)
watch(() => [props.meIndex, props.result], () => { opp.value = me.value?.opponent ?? -1 }, { immediate: true })
const enemies = computed(() => props.result.players
  .map((p, i) => ({ i, team: p.team }))
  .filter((p) => p.team !== me.value?.team))

const W = 600
const H = 180
const series = computed(() => {
  const mine = me.value?.econ?.[metric.value] || []
  const theirs = props.result.players[opp.value]?.econ?.[metric.value] || []
  const max = Math.max(1, ...mine, ...theirs)
  const box = { w: W, h: H, min: 0, max }
  return { mine, theirs, max, mineLine: linePoints(mine, box), theirLine: linePoints(theirs, box) }
})
const minutes = computed(() => series.value.mine.length)
const xOf = (m) => (minutes.value > 1 ? (m / (minutes.value - 1)) * W : 0)
const ticks = computed(() => {
  const out = []
  for (let m = 0; m < minutes.value; m += 5) out.push(m)
  return out
})
const hover = ref(-1)
function onMove(ev) {
  const rect = ev.currentTarget.getBoundingClientRect()
  const m = Math.round(((ev.clientX - rect.left) / rect.width) * (minutes.value - 1))
  hover.value = Math.max(0, Math.min(minutes.value - 1, m))
}
const readout = computed(() => {
  const m = hover.value >= 0 ? hover.value : minutes.value - 1
  const a = series.value.mine[m]
  const b = series.value.theirs[m]
  if (a === undefined) return ''
  const diff = b === undefined ? '' : `（${a - b >= 0 ? '+' : ''}${a - b}）`
  return `${m} 分钟：你 ${a}${b === undefined ? '' : ` · ${props.names[opp.value] || '对面'} ${b}`}${diff}`
})
const lane10 = computed(() => {
  const e = me.value?.econ
  const o = props.result.players[opp.value]?.econ
  if (!e || !o || e.lastHits.length <= 10 || o.lastHits.length <= 10) return null
  return {
    lh: [e.lastHits[10], o.lastHits[10]],
    dn: [e.denies[10], o.denies[10]],
    nw: e.netWorth[10] - o.netWorth[10],
    lv: [e.level[10], o.level[10]]
  }
})

// ---------- 插眼 ----------
const myWards = computed(() => (props.result.wards || []).filter((w) => w.owner === props.meIndex))
const ws = computed(() => me.value?.wards || {})
</script>

<template>
  <!-- ===== 最该改的 3 件事 ===== -->
  <div v-if="part === 'summary'" class="summary">
    <h4><Target :size="15" />这局最该改的 {{ issues.length || '' }} 件事</h4>
    <p v-if="!issues.length" class="muted small">按现在的规则没挑出明显问题（团战到场、被单抓、对线、刷钱效率、插眼都还行）。</p>
    <ol v-else>
      <li v-for="(it, i) in issues" :key="i">
        <strong>{{ it.title }}</strong>
        <p class="detail">{{ it.detail }}</p>
        <p class="advice">{{ it.advice }}</p>
      </li>
    </ol>
  </div>

  <!-- ===== 团战 / 经济 / 插眼 ===== -->
  <div v-else class="detail-wrap">
    <section>
      <h4><Swords :size="15" />团战 {{ fightStats.total }} 场</h4>
      <p class="muted small">
        3 人以上阵亡的大团战 {{ fightStats.big }} 场，你到了 {{ fightStats.present }} 场。
        "到场"= 开打后进过团战中心 2000 码内，或者打出 / 吃到过英雄伤害。点一行跳到开打前的回放。
      </p>
      <div class="fights">
        <button v-for="x in fights" :key="x.k" class="fight" @click="emit('seek', Math.max(0, x.f.start - 3))">
          <span class="clock">{{ formatClock(x.f.start) }}</span>
          <span class="tag" :class="x.outcome.level">{{ x.outcome.text }}</span>
          <span class="tag" :class="x.presence.level">{{ x.presence.text }}</span>
          <span v-if="x.mine?.present" class="muted small">输出 {{ x.mine.dealt }} · 承受 {{ x.mine.taken }}</span>
          <span class="muted small first">
            先倒：<template v-if="x.first.mine >= 0"><b :class="{ me: x.first.mine === meIndex }">{{ names[x.first.mine] }}</b>（己方）</template>
            <template v-if="x.first.theirs >= 0"> {{ names[x.first.theirs] }}（对面）</template>
          </span>
        </button>
      </div>
    </section>

    <section>
      <h4><TrendingUp :size="15" />经济曲线</h4>
      <div class="econ-bar">
        <div class="mode-tabs">
          <button v-for="m in METRICS" :key="m.key" :class="{ active: metric === m.key }" @click="metric = m.key">{{ m.label }}</button>
        </div>
        <label class="muted small">对比
          <select v-model.number="opp">
            <option v-for="e in enemies" :key="e.i" :value="e.i">{{ names[e.i] }}{{ e.i === me.opponent ? '（对线对手）' : '' }}</option>
          </select>
        </label>
      </div>
      <p v-if="me.lane" class="muted small">你前 10 分钟主要在{{ me.lane }}。</p>
      <svg v-if="minutes > 1" class="chart" :viewBox="`-4 -8 ${W + 8} ${H + 28}`" @mousemove="onMove" @mouseleave="hover = -1">
        <rect v-for="(s, i) in me.slow" :key="'s' + i" :x="xOf(s.from)" y="0" :width="xOf(s.to) - xOf(s.from)" :height="H" class="slow" />
        <line v-for="m in ticks" :key="'t' + m" :x1="xOf(m)" :x2="xOf(m)" y1="0" :y2="H" class="grid" />
        <text v-for="m in ticks" :key="'l' + m" :x="xOf(m)" :y="H + 16" class="axis">{{ m }}'</text>
        <text x="2" y="10" class="axis">{{ series.max }}</text>
        <polyline :points="series.theirLine" class="line theirs" />
        <polyline :points="series.mineLine" class="line mine" />
        <line v-if="hover >= 0" :x1="xOf(hover)" :x2="xOf(hover)" y1="0" :y2="H" class="cursor" />
      </svg>
      <p class="readout">
        <span class="key mine"></span>你 <span class="key theirs"></span>{{ names[opp] || '对面' }}
        <span v-if="me.slow?.length" class="key slow-key"></span><span v-if="me.slow?.length">低效时段</span>
        <span class="value">{{ readout }}</span>
      </p>
      <p v-if="lane10" class="muted small">
        10 分钟：正补 {{ lane10.lh[0] }} / {{ lane10.lh[1] }}，反补 {{ lane10.dn[0] }} / {{ lane10.dn[1] }}，
        等级 {{ lane10.lv[0] }} / {{ lane10.lv[1] }}，身价{{ lane10.nw >= 0 ? '领先' : '落后' }} {{ Math.abs(lane10.nw) }}
      </p>
      <ul v-if="me.slow?.length" class="slow-list">
        <li v-for="(s, i) in me.slow" :key="i">
          {{ s.from }}-{{ s.to }} 分钟只赚了 {{ s.gold }}（按你全场平均该有 {{ s.expected }}）
          <template v-if="s.deadSeconds >= 30">，其中阵亡 {{ s.deadSeconds }} 秒</template>
          <template v-else-if="s.zone">，这段主要在{{ s.zone }}</template>
        </li>
      </ul>
    </section>

    <section>
      <h4><Eye :size="15" />插眼和排眼</h4>
      <p class="muted small">
        <template v-if="ws.observers || ws.sentries">
          假眼 {{ ws.observers }} 个<template v-if="ws.observers">，平均活了 {{ formatClock(ws.observerAvgLife) }}（满的是 6:00），被拆 {{ ws.observersDewarded }} 个</template>；
          真眼 {{ ws.sentries }} 个；
        </template>
        <template v-else>这局你没插眼；</template>
        拆掉对面 {{ ws.dewards }} 个眼。
      </p>
      <div v-if="myWards.length" class="wards">
        <div v-for="(w, i) in myWards" :key="i" class="ward">
          <span class="clock">{{ formatClock(w.start) }}</span>
          <span class="tag" :class="w.sentry ? 'info' : 'warn'">{{ w.sentry ? '真眼' : '假眼' }}</span>
          <span>活了 {{ formatClock(wardLife(w, result.duration)) }}</span>
          <span v-if="w.killer" class="bad-text">被 {{ who(w.killerIndex, w.killer) }} 拆了</span>
          <span v-else class="muted small">{{ w.end < 0 ? '到比赛结束还在' : '到时间消失' }}</span>
        </div>
      </div>
      <p class="muted small">地图上切到"眼位"能看到双方每个眼插在哪。</p>
    </section>
  </div>
</template>

<style scoped>
h4 { display: flex; align-items: center; gap: 6px; font-size: 14px; margin: 18px 0 8px; color: var(--text); }
.muted { color: var(--muted); font-size: 13px; }
.small { font-size: 12px; }
.summary { border: 1px solid var(--gold); background: var(--gold-tint); border-radius: 8px; padding: 4px 16px 12px; margin-bottom: 16px; }
.summary h4 { color: var(--gold); }
.summary ol { margin: 0; padding-left: 20px; }
.summary li { margin-bottom: 10px; font-size: 14px; }
.summary .detail { margin: 4px 0 0; font-size: 13px; color: var(--muted); }
.summary .advice { margin: 4px 0 0; font-size: 13px; color: var(--text); }
.fights, .wards { display: flex; flex-direction: column; gap: 4px; }
.fight, .ward {
  display: flex; gap: 10px; align-items: center; flex-wrap: wrap;
  width: 100%; text-align: left; padding: 6px 8px; font-size: 13px;
  background: transparent; border: 1px solid var(--border); border-radius: 6px; color: var(--text);
}
.fight { cursor: pointer; }
.fight:hover { border-color: var(--gold); }
.first b.me { color: var(--gold); }
.clock { font-variant-numeric: tabular-nums; min-width: 44px; }
.tag { font-size: 12px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--muted); color: var(--muted); }
.tag.good { border-color: #3fae6a; color: #3fae6a; }
.tag.warn { border-color: #f0b232; color: #f0b232; }
.tag.bad { border-color: #e35548; color: #e35548; }
.bad-text { color: #e35548; }
.econ-bar { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 6px; }
.mode-tabs { display: flex; gap: 6px; }
.mode-tabs button.active { border-color: var(--gold); color: var(--gold); }
select { margin-left: 6px; }
.chart { width: 100%; height: auto; display: block; }
.chart .grid { stroke: var(--border); stroke-width: 1; }
.chart .axis { fill: var(--muted); font-size: 11px; }
.chart .slow { fill: rgba(227, 85, 72, 0.12); }
.chart .line { fill: none; stroke-width: 2.5; stroke-linejoin: round; }
.chart .mine { stroke: #f0b232; }
.chart .theirs { stroke: #8a8f98; }
.chart .cursor { stroke: var(--muted); stroke-dasharray: 3 3; }
.readout { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 12px; color: var(--muted); }
.readout .value { margin-left: auto; color: var(--text); font-variant-numeric: tabular-nums; }
.key { display: inline-block; width: 14px; height: 3px; border-radius: 2px; }
.key.mine { background: #f0b232; }
.key.theirs { background: #8a8f98; margin-left: 8px; }
.key.slow-key { height: 10px; background: rgba(227, 85, 72, 0.25); margin-left: 8px; }
.slow-list { margin: 6px 0 0; padding-left: 18px; font-size: 13px; color: var(--text); }
</style>
