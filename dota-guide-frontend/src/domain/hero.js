// ============================================================
// domain/hero.js —— 英雄的展示规则
// ============================================================
//
// 官网/数据库给的字段值 → 界面上怎么呈现。纯函数，不碰 Vue 和 DOM。
//
// 判据不是"这段代码长不长"，而是**这是不是领域知识**：
// "复杂度 3 显示三颗星""1 号位进度条拉满"都是本教学站对游戏的理解，
// 不是视图规则。视图该管的是 ref 接线和模板结构。
// 搬出来还有个好处：这些换算能被 node --test 直接断言，不用起浏览器。

/**
 * 定位字段 → 标签数组。
 *
 * 官网给的是一个顿号分隔的字符串（"核心、控制、肉盾、先手"），界面要渲染成
 * 一个个小徽章。中英文顿号和逗号都切开 —— 官网换过标点，切开比只认顿号稳。
 * 空值返回空数组（不是 ['']），调用方可以直接 v-for。
 */
export function splitRoles(role) {
  if (!role) return []
  return role
    .split(/[、，,]/)
    .map((s) => s.trim())
    .filter(Boolean)
}

/**
 * 复杂度 1/2/3 → ★ / ★★★。
 *
 * 缺值或越界时按 1 处理：宁可少画一颗星，也不要画出空字符串或者超长的星串。
 */
export function complexityStars(complexity) {
  const n = Number(complexity)
  const safe = Number.isInteger(n) && n >= 1 && n <= 3 ? n : 1
  return '★'.repeat(safe)
}

/**
 * 号位 → "资源优先级"进度条的填充百分比。
 *
 * 1 号位全队吃资源最多，所以画满格（100%）；5 号位最少，20%。
 * 数据里会出现复合值（"4/5号位"），parseInt 取第一个数字即可。
 *
 * 解析不出合法号位（1~5）时返回 0 —— 模板里 v-if 判断为假，整个进度条不渲染，
 * 而不是画一根空条子让人以为"这个英雄不吃资源"。
 */
export function posFillPercent(pos) {
  const n = parseInt(pos)
  return n >= 1 && n <= 5 ? ((6 - n) / 5) * 100 : 0
}

// ------------------------------------------------------------
// 打法标签（7.42 起新增，数据在 sql/hero_style.sql）
// ------------------------------------------------------------

/** 全部打法标签，顺序就是筛选页签的顺序 */
export const STYLES = ['打架', '推进', '带线', '抓人', '发育', '保人']

/** 每个标签一句话说明（页签悬停提示、详情页用） */
export const STYLE_HINTS = {
  打架: '数值撑得起前期高强度打架，不靠刷钱也能参团抢地图目标',
  推进: '拆塔快，能带着队伍或召唤物一波波推进',
  带线: '一个人带边路牵制，逼对面派人来守',
  抓人: '前中期游走找人，靠先手和爆发抓单',
  发育: '需要时间刷钱，装备成型后才发力',
  保人: '有救人、加血、护盾这类保护队友的技能'
}

/** "打架,抓人" → ['打架', '抓人']；没有标签（null/空串）返回空数组 */
export function splitStyles(styles) {
  if (!styles) return []
  return String(styles).split(',').map((s) => s.trim()).filter(Boolean)
}

/**
 * 在 YouTube 上搜这个英雄的高手录像。
 * 用英文内部名（slug）搜：YouTube 上的高分录像标题基本都是英文英雄名，
 * 下划线换成空格（anti_mage 这种）；再带上版本号，优先出当前版本的视频。
 */
export function youtubeSearchUrl(slug, patch = '7.42') {
  const name = String(slug || '').replace(/_/g, ' ').trim()
  const q = `${name} dota 2 ${patch} pro gameplay`
  return 'https://www.youtube.com/results?search_query=' + encodeURIComponent(q)
}
