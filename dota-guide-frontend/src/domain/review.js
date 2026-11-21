// ============================================================
// domain/review.js —— 比赛复盘的展示规则
// ============================================================
//
// OpenDota 的原始值（秒数、段位编号、游戏模式编号、百分位小数）→ 界面上显示什么。
// 纯函数，不碰 Vue 和 DOM，测试见 test/review.test.js。

/** Steam64 位 id 和 32 位好友代码之间差这个数 */
const STEAM64_BASE = 76561197960265728n

/**
 * 秒 → "12:05"；负数（开局前买的物品）→ "-0:30"；空值 → "—"。
 */
export function formatClock(sec) {
  if (sec === null || sec === undefined || Number.isNaN(Number(sec))) return '—'
  const n = Math.round(Number(sec))
  const sign = n < 0 ? '-' : ''
  const a = Math.abs(n)
  return `${sign}${Math.floor(a / 60)}:${String(a % 60).padStart(2, '0')}`
}

/** 0.643 → "64%" */
export function pctText(pct) {
  if (pct === null || pct === undefined) return '—'
  return `${Math.round(Number(pct) * 100)}%`
}

/**
 * 百分位 → 颜色档位：前 30% 绿、后 30% 红、中间灰。
 * 和后端 ReviewRules 用同一组阈值（0.3 / 0.7）。
 */
export function pctTone(pct) {
  if (pct === null || pct === undefined) return 'mid'
  if (pct >= 0.7) return 'good'
  if (pct < 0.3) return 'bad'
  return 'mid'
}

const TIER_NAMES = ['', '先锋', '卫士', '中军', '统帅', '传奇', '万古流芳', '超凡入圣', '冠绝一世']

/**
 * OpenDota 的 rank_tier（十位是段位、个位是星数）→ "万古流芳 1"。
 * 80 以上是冠绝一世，没有星数。缺值返回 "未定级"。
 */
export function rankTierName(tier) {
  const n = Number(tier)
  if (!Number.isInteger(n) || n < 10) return '未定级'
  const major = Math.floor(n / 10)
  const star = n % 10
  const name = TIER_NAMES[major]
  if (!name) return '未定级'
  if (major >= 8 || star === 0) return name
  return `${name} ${star}`
}

const GAME_MODES = {
  1: '全英雄选择',
  2: '队长模式',
  3: '随机征召',
  4: '单一征召',
  5: '全英雄随机',
  16: '队长征召',
  18: '技能征召',
  22: '天梯全英雄选择',
  23: '加速模式'
}

export function gameModeName(mode) {
  return GAME_MODES[mode] || `模式 ${mode}`
}

const LANE_ROLES = { 1: '优势路', 2: '中路', 3: '劣势路', 4: '打野' }

export function laneRoleName(role) {
  return LANE_ROLES[role] || '未知'
}

/** 建议的级别 → 前面的小标签 */
export function adviceLabel(level) {
  return { good: '做得好', warn: '可以更好', bad: '要注意', info: '提示' }[level] || '提示'
}

/**
 * 用户输入的比赛 → 比赛 id。
 * 支持纯数字，以及从 OpenDota / Dotabuff / STRATZ 复制来的比赛链接。
 * 认不出来返回 null。
 */
export function parseMatchInput(text) {
  const s = String(text || '').trim()
  if (/^\d{5,12}$/.test(s)) return s
  const m = s.match(/matches\/(\d{5,12})/)
  return m ? m[1] : null
}

/**
 * 用户输入的玩家 → 32 位好友代码（OpenDota 的 account_id）。
 * 支持：好友代码本身、Steam64 位 id（7656119 开头的 17 位数字）、
 *       OpenDota / Dotabuff / STRATZ 的玩家链接。认不出来返回 null。
 */
export function parseAccountInput(text) {
  const s = String(text || '').trim()
  const fromUrl = s.match(/players\/(\d+)/)
  const digits = fromUrl ? fromUrl[1] : /^\d+$/.test(s) ? s : null
  if (!digits) return null
  if (digits.length === 17 && digits.startsWith('7656119')) {
    return String(BigInt(digits) - STEAM64_BASE)
  }
  return digits.length <= 10 ? digits : null
}

/** 把 10 个玩家按阵营分成两队，各自按推测号位排好 */
export function splitTeams(players) {
  const byPos = (a, b) => a.guessedPos - b.guessedPos
  const list = players || []
  return {
    radiant: list.filter((p) => p.radiant).sort(byPos),
    dire: list.filter((p) => !p.radiant).sort(byPos)
  }
}
