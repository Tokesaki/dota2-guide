<!--
  英雄详情页的"高分局出装"面板
  数据来自后端 /api/review/heroes/{key}/builds：
    - 有足够高分局样本时显示"常出大件"（出场率、胜率、平均成型时间）；
    - 另外附上 OpenDota 全分段的分阶段出装热度（开局 / 前期 / 中期 / 后期）；
    - 职业比赛：当前版本职业选手的常出大件 + 最近几场的出装明细（点进去能看整场复盘）。
  加载失败（后端没开复盘模块、OpenDota 连不上）时整个面板不显示，不影响详情页其它内容。
-->
<script setup>
import { ref, watch } from 'vue'
import { Trophy } from 'lucide-vue-next'
import { getHeroBuilds } from '../api/review'
import { formatClock, pctText, rankTierName, laneRoleName } from '../domain/review'

const props = defineProps({ heroKey: { type: String, required: true } })
const builds = ref(null)
const failed = ref(false)

watch(
  () => props.heroKey,
  async (key) => {
    builds.value = null
    failed.value = false
    try {
      builds.value = await getHeroBuilds(key)
    } catch {
      failed.value = true
    }
  },
  { immediate: true }
)
</script>

<template>
  <section v-if="builds && (builds.proGames?.length || builds.core.length || builds.phases.length)" class="panel card builds">
    <h2><Trophy :size="18" class="h-icon" />职业比赛出装（{{ builds.patch }}）</h2>
    <template v-if="builds.proGames?.length">
      <p class="note">{{ builds.patch }} 版本职业比赛最近 {{ builds.proGames.length }} 场的常出大件（出场率）</p>
      <div class="items">
        <div v-for="s in builds.proCore" :key="'pro-' + s.itemId" class="item" :title="`${s.name}，带这件的胜率 ${pctText(s.winRate)}`">
          <img :src="s.img" :alt="s.name" />
          <span class="name">{{ s.name }}</span>
          <span class="sub">{{ pctText(s.pickRate) }}</span>
        </div>
      </div>
      <h3 class="sub-title">最近的职业比赛（点击看整场复盘）</h3>
      <router-link
        v-for="g in builds.proGames"
        :key="g.matchId"
        :to="`/review/${g.matchId}`"
        class="pro-game"
      >
        <span class="result" :class="g.win ? 'win' : 'lose'">{{ g.win ? '胜' : '负' }}</span>
        <span class="who">
          <strong>{{ g.player || '职业选手' }}</strong>
          <small>{{ g.league || '职业联赛' }}<template v-if="g.laneRole"> · {{ laneRoleName(g.laneRole) }}</template> · {{ formatClock(g.duration) }}</small>
        </span>
        <span class="kda">{{ g.kills }}/{{ g.deaths }}/{{ g.assists }}</span>
        <span class="mini-items">
          <img v-for="it in g.items" :key="it.id" :src="it.img" :alt="it.name" :title="it.name" />
          <img v-if="g.neutral" class="neutral" :src="g.neutral.img" :alt="g.neutral.name" :title="'中立物品：' + g.neutral.name" />
        </span>
      </router-link>
    </template>
    <p v-else class="note">{{ builds.patch }} 版本暂时没有这个英雄的职业比赛记录。</p>

    <h2 class="second"><Trophy :size="18" class="h-icon" />高分局出装</h2>

    <template v-if="builds.core.length">
      <p class="note">{{ rankTierName(builds.minRank) }} 段位 {{ builds.patch }} 版本 {{ builds.sampleSize }} 场的常出大件（出场率 · 平均成型时间）</p>
      <div class="items">
        <div v-for="s in builds.core" :key="s.itemId" class="item" :title="`${s.name}，带这件的胜率 ${pctText(s.winRate)}`">
          <img :src="s.img" :alt="s.name" />
          <span class="name">{{ s.name }}</span>
          <span class="sub">{{ pctText(s.pickRate) }}<template v-if="s.avgTime !== null"> · {{ formatClock(s.avgTime) }}</template></span>
        </div>
      </div>
    </template>
    <p v-else class="note">{{ builds.patch }} 版本的高分局样本还在采集中（目前 {{ builds.sampleSize }} 场，软件开着时每小时自动攒一批），先看 OpenDota 全分段的出装热度：</p>

    <div v-for="ph in builds.phases" :key="ph.phase" class="phase">
      <h3>{{ ph.label }}</h3>
      <div class="items">
        <div v-for="it in ph.items" :key="it.id" class="item small" :title="it.name">
          <img :src="it.img" :alt="it.name" />
          <span class="name">{{ it.name }}</span>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.builds h2.second {
  margin-top: var(--sp-5, 24px);
}
.sub-title {
  font-size: 13px;
  color: var(--muted);
  font-weight: 500;
  margin: var(--sp-4) 0 var(--sp-2);
}
.pro-game {
  display: flex;
  align-items: center;
  gap: var(--sp-3);
  padding: 8px 4px;
  border-top: 1px solid var(--border);
  color: var(--text);
  text-decoration: none;
  flex-wrap: wrap;
}
.pro-game:hover {
  background: var(--gold-tint);
}
.result {
  font-size: 12px;
  font-weight: 600;
  width: 22px;
  text-align: center;
}
.result.win { color: #4caf50; }
.result.lose { color: #e35548; }
.who {
  display: flex;
  flex-direction: column;
  min-width: 140px;
  flex: 1;
  font-size: 13px;
}
.who small {
  color: var(--muted);
  font-size: 12px;
}
.kda {
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  width: 64px;
}
.mini-items {
  display: flex;
  gap: 3px;
  flex-wrap: wrap;
}
.mini-items img {
  width: 34px;
  height: 25px;
  object-fit: cover;
  border-radius: 3px;
  border: 1px solid var(--border);
}
.mini-items img.neutral {
  border-radius: 50%;
  width: 25px;
  margin-left: 4px;
}
/* 标题样式和详情页其它面板一致（详情页的 .panel h2 是 scoped 的，传不进子组件，这里抄一份） */
.builds {
  padding: 24px;
}
.builds:hover {
  transform: none;
}
.builds h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 18px;
  color: var(--text);
  margin: 0 0 12px;
}
.h-icon {
  color: var(--gold);
  flex-shrink: 0;
}
.note {
  color: var(--muted);
  font-size: 13px;
  margin: 0 0 var(--sp-3);
}
.phase h3 {
  font-size: 13px;
  color: var(--muted);
  font-weight: 500;
  margin: var(--sp-4) 0 var(--sp-2);
}
.items {
  display: flex;
  flex-wrap: wrap;
  gap: var(--sp-3);
}
.item {
  width: 78px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  font-size: 12px;
}
.item img {
  width: 60px;
  height: 44px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid var(--border);
}
.item.small img {
  width: 48px;
  height: 35px;
}
.name {
  margin-top: 4px;
  line-height: 1.3;
}
.sub {
  color: var(--muted-2);
  font-variant-numeric: tabular-nums;
}
</style>
