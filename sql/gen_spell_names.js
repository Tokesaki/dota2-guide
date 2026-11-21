// ============================================================
// 技能 / 物品内部名 → 中文名，给录像解析的死亡复盘用
//
// 录像的战斗日志里技能和物品都是内部名（largo_catchy_lick、item_shivas_guard），
// 这个脚本把它们翻成中文，生成 dota-guide-frontend/src/domain/spellNames.js。
//
// 数据来源：
//   技能 —— sql/_official_raw/hero_*.json（download_official.js 下载的官网英雄数据，abilities[].name / name_loc）
//   物品 —— sql/items_cn.sql（item_key → name_cn）
//
// 用法（项目根目录下）：node sql/gen_spell_names.js
// ============================================================
const fs = require('fs')
const path = require('path')

const RAW = path.join(__dirname, '_official_raw')
const OUT = path.join(__dirname, '..', 'dota-guide-frontend', 'src', 'domain', 'spellNames.js')

const names = {}
for (const f of fs.readdirSync(RAW).filter((f) => /^hero_\d+\.json$/.test(f)).sort()) {
  const json = JSON.parse(fs.readFileSync(path.join(RAW, f), 'utf8'))
  const hero = json?.result?.data?.heroes?.[0]
  for (const a of hero?.abilities || []) {
    // 没有中文名的（官网还没翻译）用不上，跳过
    if (a.name && a.name_loc && /[一-龥]/.test(a.name_loc)) names[a.name] = a.name_loc
  }
}
const sql = fs.readFileSync(path.join(__dirname, 'items_cn.sql'), 'utf8')
for (const m of sql.matchAll(/\(\d+, '([^']+)', '((?:[^']|'')+)'\)/g)) {
  names['item_' + m[1]] = m[2].replace(/''/g, "'")
}

const keys = Object.keys(names).sort()
const body = keys.map((k) => `  ${JSON.stringify(k)}: ${JSON.stringify(names[k])}`).join(',\n')
fs.writeFileSync(OUT, `// 由 sql/gen_spell_names.js 生成，请勿手改
// 技能 / 物品内部名 → 中文名（录像战斗日志里用的是内部名）
export const SPELL_NAMES = {
${body}
}
`)
console.log(`写出 ${keys.length} 条 → ${OUT}`)
