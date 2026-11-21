<!--
  比赛复盘入口页（/review）
  两种进入方式：
    1. 输入 Steam 好友代码 → 列出最近 20 场，点一场进去；
    2. 直接输入比赛 id（或从 OpenDota / Dotabuff 复制的比赛链接）。
  好友代码会记在浏览器 localStorage 里，下次打开自动填上。
-->
<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { Search, CircleX } from 'lucide-vue-next'
import { getPlayer } from '../api/review'
import { formatClock, rankTierName, gameModeName, parseMatchInput, parseAccountInput } from '../domain/review'

const ACCOUNT_KEY = 'review.accountId'

const router = useRouter()
const accountInput = ref('')
const matchInput = ref('')
const profile = ref(null)
const loading = ref(false)
const error = ref('')
const matchError = ref('')

function readSaved() {
  try {
    return localStorage.getItem(ACCOUNT_KEY) || ''
  } catch {
    return ''
  }
}

function save(id) {
  try {
    localStorage.setItem(ACCOUNT_KEY, id)
  } catch {
    // 隐私模式等情况下写不进去，不影响使用
  }
}

async function loadPlayer() {
  const id = parseAccountInput(accountInput.value)
  if (!id) {
    error.value = '认不出这个账号：请填 Steam 好友代码（游戏里个人资料页的那串数字）'
    return
  }
  loading.value = true
  error.value = ''
  try {
    profile.value = await getPlayer(id)
    save(id)
  } catch (e) {
    profile.value = null
    error.value = e.message
  } finally {
    loading.value = false
  }
}

function openMatch() {
  const id = parseMatchInput(matchInput.value)
  if (!id) {
    matchError.value = '认不出这个比赛：填比赛编号，或粘贴 OpenDota / Dotabuff 的比赛链接'
    return
  }
  matchError.value = ''
  router.push({ name: 'match-review', params: { matchId: id } })
}

function goMatch(m) {
  router.push({ name: 'match-review', params: { matchId: m.matchId }, query: { account: profile.value?.accountId } })
}

function dateText(ts) {
  const d = new Date(ts * 1000)
  return `${d.getMonth() + 1}月${d.getDate()}日 ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

onMounted(() => {
  const saved = readSaved()
  if (saved) {
    accountInput.value = saved
    loadPlayer()
  }
})
</script>

<template>
  <div class="review">
    <h1 class="gradient-title">比赛复盘</h1>
    <p class="lead">
      用 OpenDota 的数据看一场比赛：你的经济、补刀和全服同英雄玩家比怎么样，出装和高分局差在哪，
      再配上图鉴里的打法建议。需要在游戏设置里打开「公开比赛数据」。
    </p>

    <div class="inputs">
      <form class="input-card card" @submit.prevent="loadPlayer">
        <label for="acc">我的 Steam 好友代码</label>
        <div class="row">
          <input id="acc" v-model="accountInput" placeholder="例如 869293166" inputmode="numeric" />
          <button type="submit" :disabled="loading"><Search :size="15" />查最近比赛</button>
        </div>
        <p v-if="error" class="err"><CircleX :size="14" />{{ error }}</p>
      </form>

      <form class="input-card card" @submit.prevent="openMatch">
        <label for="mid">或者直接输入比赛编号</label>
        <div class="row">
          <input id="mid" v-model="matchInput" placeholder="比赛编号或比赛链接" />
          <button type="submit">复盘这场</button>
        </div>
        <p v-if="matchError" class="err"><CircleX :size="14" />{{ matchError }}</p>
      </form>
    </div>

    <div v-if="loading" class="skeleton sk-list"></div>

    <section v-else-if="profile" class="recent">
      <div class="player-head">
        <img v-if="profile.avatar" :src="profile.avatar" alt="" class="player-avatar" />
        <div>
          <h2>{{ profile.name || profile.accountId }}</h2>
          <p class="muted">{{ rankTierName(profile.rankTier) }} · 最近 {{ profile.matches.length }} 场</p>
        </div>
      </div>

      <ul class="match-list">
        <li v-for="m in profile.matches" :key="m.matchId">
          <button class="match-row card card-sm" :class="m.win ? 'win' : 'lose'" @click="goMatch(m)">
            <img v-if="m.hero.avatar" :src="m.hero.avatar" :alt="m.hero.name" class="hero-img" />
            <span class="hero-name">{{ m.hero.name }}</span>
            <span class="result">{{ m.win ? '胜' : '负' }}</span>
            <span class="kda">{{ m.kills }}/{{ m.deaths }}/{{ m.assists }}</span>
            <span class="muted small">GPM {{ m.gpm }}</span>
            <span class="muted small">{{ formatClock(m.duration) }}</span>
            <span class="muted small hide-sm">{{ gameModeName(m.gameMode) }}</span>
            <span class="muted small hide-sm">{{ dateText(m.startTime) }}</span>
            <span class="parsed" :class="{ off: !m.parsed }">{{ m.parsed ? '已解析' : '未解析' }}</span>
          </button>
        </li>
      </ul>
    </section>
  </div>
</template>

<style scoped>
.review {
  max-width: 1000px;
  margin: 0 auto;
  padding: var(--sp-8) var(--sp-4);
}
h1 {
  font-size: 30px;
  margin: 0 0 var(--sp-2);
}
.lead {
  color: var(--muted);
  line-height: 1.7;
  margin: 0 0 var(--sp-6);
}
.inputs {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: var(--sp-4);
  margin-bottom: var(--sp-6);
}
.input-card {
  padding: var(--sp-4) var(--sp-5);
}
.input-card:hover {
  transform: none;
}
label {
  display: block;
  font-size: 13px;
  color: var(--muted);
  margin-bottom: var(--sp-2);
}
.row {
  display: flex;
  gap: var(--sp-2);
}
input {
  flex: 1;
  min-width: 0;
  padding: 9px 12px;
  border-radius: 10px;
  border: 1px solid var(--border);
  background: var(--card-2);
  color: var(--text);
  font-size: 14px;
}
input:focus {
  outline: none;
  border-color: var(--gold);
}
button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 9px 14px;
  border-radius: 10px;
  border: 1px solid var(--gold-tint-2);
  background: var(--gold-tint);
  color: var(--gold);
  font-size: 14px;
  cursor: pointer;
  white-space: nowrap;
}
button:hover:not(:disabled) {
  color: var(--gold-bright);
  border-color: var(--gold);
}
button:disabled {
  opacity: 0.5;
  cursor: default;
}
.err {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--danger);
  font-size: 13px;
  margin: var(--sp-2) 0 0;
}
.sk-list {
  height: 320px;
  border-radius: var(--radius);
}
.player-head {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  margin-bottom: var(--sp-4);
}
.player-head h2 {
  margin: 0;
  font-size: 20px;
}
.player-avatar {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  border: 2px solid var(--border);
}
.muted {
  color: var(--muted);
  margin: 2px 0 0;
}
.small {
  font-size: 13px;
}
.match-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--sp-2);
}
.match-row {
  width: 100%;
  display: grid;
  grid-template-columns: 56px 1.2fr 32px 80px 80px 56px 1.2fr 1fr 60px;
  align-items: center;
  gap: var(--sp-3);
  padding: var(--sp-2) var(--sp-3);
  color: var(--text);
  text-align: left;
  border: 1px solid var(--border);
  border-left: 3px solid var(--muted);
}
.match-row.win {
  border-left-color: var(--agi);
}
.match-row.lose {
  border-left-color: var(--str);
}
.match-row:hover {
  border-color: var(--gold);
}
.hero-img {
  width: 56px;
  height: 32px;
  object-fit: cover;
  border-radius: 6px;
}
.hero-name {
  font-weight: 600;
}
.win .result {
  color: var(--agi);
}
.lose .result {
  color: var(--str);
}
.kda {
  font-variant-numeric: tabular-nums;
}
.parsed {
  font-size: 12px;
  color: var(--agi);
}
.parsed.off {
  color: var(--muted-2);
}
@media (max-width: 760px) {
  .inputs {
    grid-template-columns: 1fr;
  }
  .match-row {
    grid-template-columns: 48px 1fr 28px 70px;
  }
  .match-row .small,
  .hide-sm,
  .parsed {
    display: none;
  }
}
</style>
