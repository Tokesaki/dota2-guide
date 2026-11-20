<!--
  单场复盘页（/review/:matchId）
  上半部分：两队 10 个人的总览表，点一行选中那个人；
  下半部分：选中玩家的详细复盘 —— 号位、百分位、出装（含购买时间）、和高分局对比、建议、图鉴打法。
  默认选中 URL 里 ?account= 指定的玩家（从"最近比赛"点进来时会带上），没有就选第一个。
-->
<script setup>
import { d2ptUrl } from '../domain/heroNamesEn'
import { ref, computed, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft, CircleX, RefreshCw, Lightbulb, Package, BarChart3, BookOpen } from 'lucide-vue-next'
import { getMatchReview, requestParse } from '../api/review'
import {
  formatClock, pctText, pctTone, gameModeName, laneRoleName, adviceLabel, splitTeams
} from '../domain/review'

const props = defineProps({ matchId: { type: String, required: true } })
const route = useRoute()

const data = ref(null)
const loading = ref(true)
const error = ref('')
const selected = ref(null)
const parseMsg = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    data.value = await getMatchReview(props.matchId)
    const acc = Number(route.query.account)
    const players = data.value.players
    selected.value = (acc && players.find((p) => p.accountId === acc)) || players[0]
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function askParse() {
  try {
    const sent = await requestParse(props.matchId)
    parseMsg.value = sent ? '已请求解析，一般几分钟后刷新本页就能看到出装时间和对线数据。' : '30 分钟内已经请求过了，稍后刷新本页即可。'
  } catch (e) {
    parseMsg.value = e.message
  }
}

watch(() => props.matchId, load, { immediate: true })

const teams = computed(() => splitTeams(data.value?.players))
const p = computed(() => selected.value)

function teamWon(radiant) {
  return data.value && data.value.radiantWin === radiant
}

function dateText(ts) {
  return new Date(ts * 1000).toLocaleString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="match">
    <router-link to="/review" class="back"><ArrowLeft :size="14" />返回</router-link>

    <div v-if="loading" class="skeleton sk-big"></div>

    <div v-else-if="error" class="status card">
      <p><CircleX :size="32" class="err-icon" /></p>
      <p>{{ error }}</p>
      <button @click="load">重试</button>
    </div>

    <template v-else-if="data">
      <!-- 比赛概况 -->
      <header class="head">
        <h1 class="gradient-title">比赛 {{ data.matchId }}</h1>
        <p class="muted">
          {{ gameModeName(data.gameMode) }} · 时长 {{ formatClock(data.duration) }} · {{ dateText(data.startTime) }} ·
          <span :class="data.radiantWin ? 'rad' : 'dire'">{{ data.radiantWin ? '天辉' : '夜魇' }}胜利</span>
          {{ data.radiantScore }} : {{ data.direScore }}
        </p>
      </header>

      <div v-if="!data.parsed" class="notice card card-sm">
        <p>
          这场比赛 OpenDota 还没解析录像，所以没有出装时间、分路和 10 分钟补刀，号位是按经济粗略推测的。
          {{ data.parseRequested ? '已经自动帮你请求解析了，过几分钟刷新本页。' : '' }}
        </p>
        <div class="notice-actions">
          <button @click="askParse"><RefreshCw :size="14" />请求解析</button>
          <button @click="load">刷新</button>
        </div>
        <p v-if="parseMsg" class="muted">{{ parseMsg }}</p>
      </div>

      <!-- 两队总览 -->
      <div class="teams">
        <section v-for="side in [true, false]" :key="String(side)" class="team card">
          <h2 :class="side ? 'rad' : 'dire'">
            {{ side ? '天辉' : '夜魇' }}<span class="tag">{{ teamWon(side) ? '胜' : '负' }}</span>
          </h2>
          <table>
            <thead>
              <tr><th>英雄</th><th>号位</th><th>K/D/A</th><th>GPM</th><th class="hide-sm">补刀</th><th class="hide-sm">经济</th></tr>
            </thead>
            <tbody>
              <tr
                v-for="pl in side ? teams.radiant : teams.dire"
                :key="pl.index"
                :class="{ active: p && p.index === pl.index }"
                @click="selected = pl"
              >
                <td class="hero-cell">
                  <img v-if="pl.hero.avatar" :src="pl.hero.avatar" alt="" />
                  <span>
                    {{ pl.hero.name }}
                    <small>{{ pl.personaname || '匿名玩家' }}</small>
                  </span>
                </td>
                <td>{{ pl.guessedPos }}</td>
                <td class="num">{{ pl.kills }}/{{ pl.deaths }}/{{ pl.assists }}</td>
                <td class="num">{{ pl.gpm }}</td>
                <td class="num hide-sm">{{ pl.lastHits }}/{{ pl.denies }}</td>
                <td class="num hide-sm">{{ (pl.netWorth / 1000).toFixed(1) }}k</td>
              </tr>
            </tbody>
          </table>
        </section>
      </div>

      <!-- 选中玩家的复盘 -->
      <section v-if="p" class="detail">
        <div class="player-banner card">
          <img v-if="p.hero.avatar" :src="p.hero.avatar" alt="" class="banner-img" />
          <div>
            <h2>
              {{ p.hero.name }}
              <span class="tag" :class="p.win ? 'win' : 'lose'">{{ p.win ? '胜' : '负' }}</span>
            </h2>
            <p class="muted">
              {{ p.personaname || '匿名玩家' }} · 等级 {{ p.level }} ·
              {{ p.posFromLane ? '' : '推测' }} {{ p.guessedPos }} 号位
              <template v-if="p.laneRole">（{{ laneRoleName(p.laneRole) }}）</template>
              <template v-if="p.hero.pos"> · 图鉴推荐 {{ p.hero.pos }}</template>
            </p>
            <p class="muted">
              伤害 {{ p.heroDamage.toLocaleString() }} · 推塔 {{ p.towerDamage.toLocaleString() }}
              <template v-if="p.lastHits10 !== null"> · 10 分钟正补/反补 {{ p.lastHits10 }}/{{ p.denies10 }}</template>
            </p>
          </div>
          <router-link v-if="p.hero.slug" :to="`/heroes/${p.hero.slug}`" class="hero-link">查看图鉴</router-link>
          <a v-if="p.hero.slug" :href="d2ptUrl(p.hero.slug)" target="_blank" rel="noopener" class="hero-link">职业出装（dota2protracker）</a>
        </div>

        <div class="grid">
          <!-- 建议 -->
          <div class="panel card">
            <h3><Lightbulb :size="17" />复盘建议</h3>
            <ul v-if="p.advice.length" class="advice">
              <li v-for="(a, i) in p.advice" :key="i" :class="a.level">
                <span class="lvl">{{ adviceLabel(a.level) }}</span>{{ a.text }}
              </li>
            </ul>
            <p v-else class="muted">这局各项数据都在正常范围内，没有特别要提的。</p>
          </div>

          <!-- 百分位 -->
          <div class="panel card">
            <h3><BarChart3 :size="17" />和全服同英雄玩家比</h3>
            <ul class="bench">
              <li v-for="b in p.benchmarks" :key="b.key">
                <span class="b-label">{{ b.label }}</span>
                <span class="b-bar"><i :class="pctTone(b.pct)" :style="{ width: pctText(b.pct) }"></i></span>
                <span class="b-val" :class="pctTone(b.pct)">超过 {{ pctText(b.pct) }}</span>
              </li>
            </ul>
            <p class="muted small">数据来自 OpenDota：百分比表示这一项超过了多少比例的同英雄玩家。</p>
          </div>

          <!-- 出装 -->
          <div class="panel card wide">
            <h3><Package :size="17" />出装</h3>
            <div class="items">
              <div v-for="it in p.items" :key="it.id + '-' + it.key" class="item" :title="it.name">
                <img :src="it.img" :alt="it.name" />
                <span class="item-name">{{ it.name }}</span>
                <span class="item-time">{{ it.time !== null ? formatClock(it.time) : '' }}</span>
              </div>
            </div>

            <template v-if="p.buildCompare">
              <h4>高分局这个英雄常出（最近 {{ p.buildCompare.sampleSize }} 场样本）</h4>
              <div class="items">
                <div v-for="s in p.buildCompare.common" :key="s.itemId" class="item" :title="s.name">
                  <img :src="s.img" :alt="s.name" />
                  <span class="item-name">{{ s.name }}</span>
                  <span class="item-time">{{ pctText(s.pickRate) }}<template v-if="s.avgTime !== null"> · {{ formatClock(s.avgTime) }}</template></span>
                </div>
              </div>
              <p v-if="p.buildCompare.unusual.length" class="muted small">
                你出了高分局很少出的：{{ p.buildCompare.unusual.map((i) => i.name).join('、') }}
              </p>
            </template>
            <p v-else class="muted small">高分局样本还不够（后端每小时自动采集一批），暂时没法做出装对比。</p>
          </div>

          <!-- 图鉴打法 -->
          <div v-if="p.hero.tips" class="panel card wide">
            <h3><BookOpen :size="17" />图鉴里的{{ p.hero.name }}打法</h3>
            <p>{{ p.hero.tips }}</p>
            <p v-if="p.hero.items" class="muted">推荐装备：{{ p.hero.items }}</p>
            <p v-if="p.hero.counters" class="muted">{{ p.hero.counters }}</p>
          </div>
        </div>
      </section>
    </template>
  </div>
</template>

<style scoped>
.match {
  max-width: 1100px;
  margin: 0 auto;
  padding: var(--sp-6) var(--sp-4) var(--sp-8);
}
.back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--muted);
  text-decoration: none;
  font-size: 14px;
}
.back:hover {
  color: var(--gold);
}
.sk-big {
  height: 480px;
  border-radius: var(--radius);
  margin-top: var(--sp-4);
}
.status {
  text-align: center;
  padding: var(--sp-8);
  margin-top: var(--sp-4);
}
.err-icon {
  color: var(--danger);
}
.head h1 {
  margin: var(--sp-3) 0 var(--sp-1);
  font-size: 28px;
}
.muted {
  color: var(--muted);
  margin: 2px 0;
  line-height: 1.6;
}
.small {
  font-size: 13px;
}
.rad {
  color: var(--agi);
}
.dire {
  color: var(--str);
}
button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 10px;
  border: 1px solid var(--gold-tint-2);
  background: var(--gold-tint);
  color: var(--gold);
  font-size: 13px;
  cursor: pointer;
}
button:hover {
  border-color: var(--gold);
}
.notice {
  padding: var(--sp-3) var(--sp-4);
  margin: var(--sp-4) 0;
  border: 1px solid var(--gold-tint-2);
}
.notice p {
  margin: 0 0 var(--sp-2);
}
.notice-actions {
  display: flex;
  gap: var(--sp-2);
}
.teams {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
  margin: var(--sp-4) 0;
}
.team {
  padding: var(--sp-3) var(--sp-4);
  overflow-x: auto;
}
.team:hover,
.panel:hover,
.player-banner:hover {
  transform: none;
}
.team h2 {
  font-size: 16px;
  margin: 0 0 var(--sp-2);
}
.tag {
  font-size: 12px;
  margin-left: 8px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--card-3);
  color: var(--text);
}
.tag.win {
  color: var(--agi);
}
.tag.lose {
  color: var(--str);
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
}
th {
  text-align: left;
  color: var(--muted-2);
  font-weight: 500;
  padding: 4px 6px;
}
td {
  padding: 5px 6px;
  border-top: 1px solid var(--border);
}
tbody tr {
  cursor: pointer;
}
tbody tr:hover {
  background: var(--card-2);
}
tbody tr.active {
  background: var(--gold-tint);
}
.hero-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.hero-cell img {
  width: 44px;
  height: 25px;
  object-fit: cover;
  border-radius: 4px;
}
.hero-cell small {
  display: block;
  color: var(--muted-2);
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.num {
  font-variant-numeric: tabular-nums;
}
.player-banner {
  display: flex;
  align-items: center;
  gap: var(--sp-4);
  padding: var(--sp-4) var(--sp-5);
}
.player-banner h2 {
  margin: 0;
  font-size: 22px;
}
.banner-img {
  width: 110px;
  height: 62px;
  object-fit: cover;
  border-radius: 8px;
}
.hero-link {
  margin-left: auto;
  color: var(--gold);
  text-decoration: none;
  font-size: 14px;
  white-space: nowrap;
}
.grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
  margin-top: var(--sp-4);
}
.panel {
  padding: var(--sp-4) var(--sp-5);
}
.panel.wide {
  grid-column: 1 / -1;
}
.panel h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 var(--sp-3);
  font-size: 16px;
  color: var(--gold);
}
.panel h4 {
  font-size: 14px;
  margin: var(--sp-4) 0 var(--sp-2);
  color: var(--muted);
  font-weight: 500;
}
.panel p {
  line-height: 1.7;
}
.advice {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--sp-2);
}
.advice li {
  line-height: 1.6;
  padding: 8px 10px;
  border-radius: 10px;
  background: var(--card-2);
  border-left: 3px solid var(--muted);
}
.advice li.good {
  border-left-color: var(--agi);
}
.advice li.warn {
  border-left-color: var(--gold);
}
.advice li.bad {
  border-left-color: var(--danger);
}
.advice li.info {
  border-left-color: var(--int);
}
.lvl {
  font-size: 12px;
  margin-right: 8px;
  color: var(--muted);
}
.bench {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}
.bench li {
  display: grid;
  grid-template-columns: 110px 1fr 80px;
  align-items: center;
  gap: 10px;
  font-size: 13px;
}
.b-bar {
  height: 8px;
  border-radius: 999px;
  background: var(--card-3);
  overflow: hidden;
}
.b-bar i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--muted);
}
.b-bar i.good {
  background: var(--agi);
}
.b-bar i.bad {
  background: var(--danger);
}
.b-val {
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.b-val.good {
  color: var(--agi);
}
.b-val.bad {
  color: var(--danger);
}
.items {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
}
.item {
  width: 84px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  font-size: 12px;
}
.item img {
  width: 64px;
  height: 47px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--border);
}
.item-name {
  margin-top: 4px;
  line-height: 1.3;
}
.item-time {
  color: var(--muted-2);
  font-variant-numeric: tabular-nums;
}
@media (max-width: 760px) {
  .teams,
  .grid {
    grid-template-columns: 1fr;
  }
  .hide-sm {
    display: none;
  }
  .player-banner {
    flex-wrap: wrap;
  }
  .hero-link {
    margin-left: 0;
  }
  .bench li {
    grid-template-columns: 90px 1fr 70px;
  }
}
</style>
