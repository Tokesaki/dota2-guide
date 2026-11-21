// ============================================================
// 物品中文名下载脚本（可选）
//
// 作用：从 dota2.com 官网数据接口拉取简体中文物品名，生成 sql/items_cn.sql，
//       导入后复盘页的物品就显示中文名；不跑这一步，页面显示 OpenDota 的英文名。
//
// 用法（项目根目录下）：
//   node sql/download_items_cn.js
//   mysql -u root -p dota_guide < sql/items_cn.sql
//
// 物品 id 是游戏内 id，和 OpenDota 的 item id 是同一套，所以能直接对上。
// ============================================================
const fs = require('fs')
const path = require('path')

const URL = 'https://www.dota2.com/datafeed/itemlist?language=schinese'
const OUT = path.join(__dirname, 'items_cn.sql')

const esc = (s) => String(s).replace(/'/g, "''")  // 标准 SQL 转义，MySQL 和 H2 都认

async function main() {
const res = await fetch(URL)
if (!res.ok) {
  console.error(`下载失败：HTTP ${res.status}`)
  process.exit(1)
}
const json = await res.json()
// 官网接口的外层结构是 { result: { data: { itemabilities: [...] } } }
const list = json?.result?.data?.itemabilities
if (!Array.isArray(list) || list.length === 0) {
  console.error('官网返回的结构和预期不一样（找不到 result.data.itemabilities），请检查接口')
  process.exit(1)
}

const rows = []
for (const it of list) {
  // name 形如 item_blink，去掉 item_ 前缀就是 OpenDota 用的 key（blink）
  const key = String(it.name || '').replace(/^item_/, '')
  const cn = it.name_loc
  if (!it.id || !key || !cn || key.startsWith('recipe_')) continue
  rows.push(`(${Number(it.id)}, '${esc(key)}', '${esc(cn)}')`)
}

const sql = `-- 由 sql/download_items_cn.js 生成，请勿手改
SET NAMES utf8mb4;
USE dota_guide;
TRUNCATE TABLE item_cn;
INSERT INTO item_cn (item_id, item_key, name_cn) VALUES
${rows.join(',\n')};
`
fs.writeFileSync(OUT, sql, 'utf8')
console.log(`已生成 ${OUT}，共 ${rows.length} 个物品`)
}

main().catch((e) => {
  console.error('下载失败：' + e.message)
  process.exit(1)
})
