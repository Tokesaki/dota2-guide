<!--
  英雄详情页的"高分局出装"面板
  数据来自后端 /api/review/heroes/{key}/builds：
    - 有足够高分局样本时显示"常出大件"（出场率、胜率、平均成型时间）；
    - 另外附上 OpenDota 全分段的分阶段出装热度（开局 / 前期 / 中期 / 后期）。
  加载失败（后端没开复盘模块、OpenDota 连不上）时整个面板不显示，不影响详情页其它内容。
-->
<script setup>
import { ref, watch } from 'vue'
import { Trophy } from 'lucide-vue-next'
import { getHeroBuilds } from '../api/review'
import { formatClock, pctText, rankTierName } from '../domain/review'

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
  <section v-if="builds && (builds.core.length || builds.phases.length)" class="panel card builds">
    <h2><Trophy :size="18" class="h-icon" />高分局出装</h2>

    <template v-if="builds.core.length">
      <p class="note">{{ rankTierName(builds.minRank) }} 段位最近 {{ builds.sampleSize }} 场的常出大件（出场率 · 平均成型时间）</p>
      <div class="items">
        <div v-for="s in builds.core" :key="s.itemId" class="item" :title="`${s.name}，带这件的胜率 ${pctText(s.winRate)}`">
          <img :src="s.img" :alt="s.name" />
          <span class="name">{{ s.name }}</span>
          <span class="sub">{{ pctText(s.pickRate) }}<template v-if="s.avgTime !== null"> · {{ formatClock(s.avgTime) }}</template></span>
        </div>
      </div>
    </template>
    <p v-else class="note">高分局样本还在采集中（目前 {{ builds.sampleSize }} 场），先看 OpenDota 全分段的出装热度：</p>

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
