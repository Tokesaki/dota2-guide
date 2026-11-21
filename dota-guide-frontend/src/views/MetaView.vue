<!--
  版本环境页（纯静态）：7.42 对整局节奏影响最大的通用改动 + 对打法的解读
  数据来源：dota2.com 官方 7.42 更新日志的"全局机制 / 线上小兵 / 地图目标"部分
  入口：页头导航"版本环境"，路由 /meta
  出新版本时：改 PATCH 和下面几组列表即可；英雄的打法标签在 sql/hero_style.sql
-->
<script setup>
import { useRouter } from 'vue-router'
import { Flame, Coins, Skull, Map as MapIcon, Swords, Lightbulb } from 'lucide-vue-next'
import { STYLES, STYLE_HINTS } from '../domain/hero'

const router = useRouter()
const PATCH = '7.42'

// 每组：标题、图标、官方改动（facts）、这对打法意味着什么（meaning）
const SECTIONS = [
  {
    key: 'xp',
    icon: Flame,
    title: '经验：人人都有保底，等级差变小',
    facts: [
      '开局号角后所有英雄每分钟固定获得 50 经验，死了也照拿',
      '升到 30 级需要的总经验从 64400 涨到 66600，主要多在 5~18 级',
      '智慧神龛的经验从 200 + 300×次数 降到 200 + 200×次数'
    ],
    meaning: '辅助和被压的人不再掉级太多，前中期双方等级更接近。劣势方也有能力打一架，所以不要指望靠"刷等级"甩开对面。'
  },
  {
    key: 'gold',
    icon: Coins,
    title: '金钱：推塔和杀人都变便宜了',
    facts: [
      '被动工资固定每分钟 90 金，不再随时间增长',
      '击杀赏金改为 100 + 10×等级（原来 125 + 8×等级），前期杀人钱更少',
      '一塔、二塔、近战兵营和肉山的团队金钱都减少了'
    ],
    meaning: '一波推掉塔或者杀一个人带来的领先比以前小，劣势方更容易翻盘。抢到的地图目标要马上转成下一波压力，不能拿完就停。'
  },
  {
    key: 'tormentor',
    icon: Skull,
    title: '痛苦魔方：15 分钟就刷，奖励更多',
    facts: [
      '第一次刷新从 20:00 提前到 15:00',
      '每次必掉"大战斗号角"：一用就在己方每条兵线上召唤一波超级士兵',
      '第 2 次起必掉阿哈利姆魔晶；每人金钱 125 + 50×魔方已阵亡次数',
      '护盾从 3000 加到 4000，反弹伤害从 35% 降到 15% 起步'
    ],
    meaning: '15 分钟左右双方都会围着魔方打第一场正式团战。打赢的一方拿到号角可以三路一起推，所以中期就是"抢魔方 → 推塔"的循环。'
  },
  {
    key: 'map',
    icon: MapIcon,
    title: '兵线和地图：后期推进更猛',
    facts: [
      '30 分钟后攻城车改为每 2.5 分钟一波，旗手每波都有',
      '35 分钟起每波多 1 个远程兵（原来 40 分钟）',
      '把小兵拉出兵线会越走越慢，最慢只剩 25% 移速',
      '肉山每死一次复活时间少 1 分钟，最快 5 分钟；隐身神符多了类似雾的潜行效果',
      '双生之门移到地图上下边缘，中路一塔二塔位置也改了'
    ],
    meaning: '比赛拖到后期，兵线压力会越来越大，带线和守线都更重要。偷偷拉野、拖兵线这类小技巧收益变小。'
  },
  {
    key: 'spell',
    icon: Swords,
    title: '战斗：法术伤害整体削弱，英雄更肉',
    facts: [
      '英雄基础生命值从 120 加到 200；每点力量给的生命值从 22 降到 20',
      '大部分魔法和纯粹伤害技能伤害降低，物品给的技能增强、施法距离也降低',
      '新属性"物品威力"：幻象和部分技能的攻击也能触发黯灭、碎颅锤这类效果（但打折）'
    ],
    meaning: '前期一套技能秒人更难，团战持续时间更长，物理核心和能扛的英雄更吃香。'
  }
]
</script>

<template>
  <div class="meta">
    <div class="banner">
      <h1 class="gradient-title">{{ PATCH }} 版本环境</h1>
      <p>这个版本对整局节奏影响最大的改动，以及它对打法意味着什么</p>
      <p class="meta-line">来源：dota2.com 官方 {{ PATCH }} 更新日志（全局机制 / 兵线 / 地图目标）· "怎么打"是本站的解读</p>
    </div>

    <section class="panel card summary">
      <h2><Lightbulb :size="20" class="h-icon" />一句话总结</h2>
      <p>
        <strong>{{ PATCH }} 是一个"打架版本"。</strong>
        经验有保底、赏金变少、痛苦魔方 15 分钟就刷还掉兵线号角，
        整局节奏从"各自刷钱等后期"变成了"抱团抢地图目标，打赢就推"。
        选英雄时可以优先考虑团战强、能抢魔方和肉山的英雄。
      </p>
      <div class="style-row">
        <button
          v-for="st in STYLES"
          :key="st"
          class="style-chip"
          :class="{ hot: st === '打架' }"
          :title="STYLE_HINTS[st]"
          @click="router.push({ path: '/heroes', query: { style: st } })"
        >{{ st }}</button>
        <span class="hint">点标签去英雄图鉴看这类英雄</span>
      </div>
    </section>

    <section v-for="sec in SECTIONS" :key="sec.key" class="panel card">
      <h2><component :is="sec.icon" :size="20" class="h-icon" />{{ sec.title }}</h2>
      <ul class="facts">
        <li v-for="(f, i) in sec.facts" :key="i">{{ f }}</li>
      </ul>
      <p class="meaning"><strong>怎么打：</strong>{{ sec.meaning }}</p>
    </section>
  </div>
</template>

<style scoped>
.meta {
  max-width: 900px;
  margin: 0 auto;
  padding: 32px 24px;
  display: flex;
  flex-direction: column;
  gap: 24px;
}
.banner {
  text-align: center;
  margin-bottom: 8px;
}
.banner h1 {
  font-size: 32px;
  margin-bottom: 8px;
}
.banner p {
  color: var(--muted);
  font-size: 14px;
}
.meta-line {
  margin-top: 6px;
  font-size: 12px;
  color: var(--muted-2);
}
.panel {
  padding: 24px;
}
.panel h2 {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  color: var(--text);
  margin-bottom: 16px;
}
.h-icon {
  color: var(--gold);
  flex-shrink: 0;
}
.panel p {
  font-size: 14px;
  color: var(--text);
  line-height: 1.7;
}
.panel strong {
  color: var(--gold);
}
.facts {
  margin: 0 0 12px;
  padding-left: 20px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--text);
}
.meaning {
  padding: 10px 14px;
  border-left: 3px solid var(--gold);
  background: var(--gold-tint);
  border-radius: 4px;
}
.style-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 16px;
}
.style-chip {
  font-size: 13px;
  padding: 4px 14px;
  border-radius: 999px;
  border: 1px solid var(--muted);
  color: var(--muted);
  background: transparent;
  cursor: pointer;
}
.style-chip.hot,
.style-chip:hover {
  border-color: var(--gold);
  color: var(--gold);
  background: var(--gold-tint);
}
.hint {
  font-size: 12px;
  color: var(--muted-2);
}
@media (max-width: 600px) {
  .meta {
    padding: 24px 16px;
  }
}
</style>
