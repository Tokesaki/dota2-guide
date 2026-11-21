// ============================================================
// 录像深度解析的前端纯函数（坐标换算、颜色、文案），可单测
// ============================================================

import { SPELL_NAMES } from './spellNames.js'

/** 区域颜色：顺序和后端 ReplayAnalysis.ZONES 一致 */
export const ZONE_COLORS = {
  上路: '#5b8def',
  中路: '#f0b232',
  下路: '#8e6cf0',
  己方野区: '#3fae6a',
  敌方野区: '#e35548',
  河道: '#4fc3d9',
  己方基地: '#8a8f98',
  敌方高地: '#c05a8a'
}

/** 游戏坐标 → 画布像素（y 轴向上，所以要翻过来） */
export function toCanvas(x, y, size, min, max) {
  const span = max - min
  return [((x - min) / span) * size, size - ((y - min) / span) * size]
}

/** 按 accountId 找录像里的玩家，匿名玩家退回按英雄 slug 找 */
export function findReplayPlayer(result, player) {
  if (!result || !player) return -1
  const list = result.players
  if (player.accountId) {
    const i = list.findIndex((p) => p.accountId === player.accountId)
    if (i >= 0) return i
  }
  return list.findIndex((p) => p.slug && p.slug === player.hero?.slug)
}

/** 一次死亡的定性：人数劣势 / 单独被抓 / 正常团战 */
export function deathVerdict(d) {
  if (d.allies === 0 && d.enemies >= 2) return { level: 'bad', text: '单独被抓' }
  if (d.enemies >= d.allies + 2) return { level: 'bad', text: '人数劣势' }
  if (d.enemies === 0) return { level: 'info', text: '附近没看到敌人（可能是技能或防御塔）' }
  return { level: 'info', text: '人数相当' }
}

/** 区域占比 → [{zone, pct}]，去掉太小的（< 3%），按占比从大到小 */
export function zoneBars(share, min = 0.03) {
  return Object.entries(share || {})
    .filter(([, v]) => v >= min)
    .sort((a, b) => b[1] - a[1])
    .map(([zone, pct]) => ({ zone, pct }))
}

/** trail 里时间最接近 t 的帧下标（trail 每 2 秒一帧、按时间排好） */
export function frameIndexAt(trail, t) {
  if (!trail?.length) return 0
  let lo = 0
  let hi = trail.length - 1
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (trail[mid][0] < t) lo = mid + 1
    else hi = mid
  }
  return lo
}

// ---------------------------------------------------------------- 第二版：团战 / 死亡细节 / 经济 / 插眼


/** 技能 / 物品内部名 → 中文；查不到（召唤物、野怪的技能）返回空串，页面上就不显示 */
export function spellName(key) {
  if (!key) return ''
  return SPELL_NAMES[key] || ''
}

/**
 * 战斗日志里的单位名 → 能看懂的名字
 * @param name   内部名，如 npc_dota_hero_kez、npc_dota_creep_badguys_ranged、npc_dota_witch_doctor_death_ward
 * @param index  英雄下标（非英雄为 -1）
 * @param heroNames 10 个英雄的中文名（按录像里的顺序）
 * @param slugs     10 个英雄的 slug（认召唤物是谁的）
 */
export function unitName(name, index, heroNames = [], slugs = []) {
  if (index >= 0 && heroNames[index]) return heroNames[index]
  if (!name) return '未知'
  const raw = name.replace(/^npc_dota_/, '')
  if (raw.startsWith('hero_')) return raw.slice(5)
  if (SPELL_NAMES[raw]) return SPELL_NAMES[raw]               // 死亡守卫这类召唤物名字就是技能名
  if (/tower/.test(raw)) return '防御塔'
  if (/roshan/.test(raw)) return '肉山'
  if (/miniboss|tormentor/.test(raw)) return '痛苦魔方'
  if (/fort|barracks|rax/.test(raw)) return '建筑'
  if (/creep|goodguys|badguys|siege/.test(raw)) return '小兵'
  if (/neutral/.test(raw)) return '野怪'
  const owner = slugs.findIndex((s) => s && raw.startsWith(s + '_'))
  if (owner >= 0) return `${heroNames[owner] || slugs[owner]}的召唤物`
  return raw.replace(/_/g, ' ')
}

/** 总结文案里的 {h3} 换成第 3 个英雄的名字 */
export function fillHeroNames(text, heroNames = []) {
  return String(text || '').replace(/\{h(\d+)\}/g, (_, i) => heroNames[Number(i)] || '对位')
}

/**
 * 某人在一场团战里的表现 → 一行字
 * @returns {{ level: 'good'|'warn'|'bad'|'info', text: string }}
 */
export function presenceText(fight, i) {
  const p = fight.presence[i]
  if (!p) return { level: 'info', text: '—' }
  if (p.present) {
    const late = p.arrive - fight.start
    if (late >= 10) return { level: 'warn', text: `晚到 ${late} 秒` }
    return { level: 'good', text: '到场' }
  }
  if (p.dead) return { level: 'info', text: '开打时在等复活' }
  return { level: 'bad', text: `没到：在${p.zone || '别处'}，离 ${Math.round(p.distance / 100) * 100} 码` }
}

/** 团战结果：从 team（2 天辉 / 3 夜魇）这边看，死得少的算赢 */
export function fightOutcome(fight, team) {
  const mine = team === 2 ? fight.radiantLost : fight.direLost
  const theirs = team === 2 ? fight.direLost : fight.radiantLost
  if (mine < theirs) return { level: 'good', text: `赢 ${theirs}:${mine}` }
  if (mine > theirs) return { level: 'bad', text: `输 ${theirs}:${mine}` }
  return { level: 'info', text: `平 ${theirs}:${mine}` }
}

/** 团战里每边第一个倒下的人的下标：{ mine, theirs }（没人死为 -1） */
export function firstDown(fight, teams, team) {
  const mine = fight.deathOrder.find((v) => teams[v] === team)
  const theirs = fight.deathOrder.find((v) => teams[v] !== team)
  return { mine: mine ?? -1, theirs: theirs ?? -1 }
}

/**
 * 折线图的点：values 每分钟一个数 → SVG polyline 的 points 字符串
 * @param box { w, h, max, min }
 */
export function linePoints(values, box) {
  const n = values.length
  if (n < 2) return ''
  const span = box.max - box.min || 1
  return values
    .map((v, m) => `${((m / (n - 1)) * box.w).toFixed(1)},${(box.h - ((v - box.min) / span) * box.h).toFixed(1)}`)
    .join(' ')
}

/** 守卫活了多久（秒）：被拆 / 到时间按 end，到比赛结束还在按 duration */
export function wardLife(w, duration) {
  return (w.end < 0 ? duration : w.end) - Math.max(0, w.start)
}
