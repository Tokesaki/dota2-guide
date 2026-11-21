// ============================================================
// 录像深度解析的前端纯函数（坐标换算、颜色、文案），可单测
// ============================================================

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
