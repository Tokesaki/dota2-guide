import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toCanvas, findReplayPlayer, deathVerdict, zoneBars, frameIndexAt, ZONE_COLORS } from '../src/domain/replay.js'

test('toCanvas：左下角是 (0, size)，右上角是 (size, 0)', () => {
  assert.deepEqual(toCanvas(-8800, -8800, 400, -8800, 8800), [0, 400])
  assert.deepEqual(toCanvas(8800, 8800, 400, -8800, 8800), [400, 0])
  assert.deepEqual(toCanvas(0, 0, 400, -8800, 8800), [200, 200])
})

test('findReplayPlayer：优先按账号，匿名按英雄', () => {
  const result = { players: [{ accountId: 1, slug: 'axe' }, { accountId: null, slug: 'lina' }] }
  assert.equal(findReplayPlayer(result, { accountId: 1, hero: { slug: 'axe' } }), 0)
  assert.equal(findReplayPlayer(result, { accountId: null, hero: { slug: 'lina' } }), 1)
  assert.equal(findReplayPlayer(result, { accountId: 9, hero: { slug: 'pudge' } }), -1)
})

test('deathVerdict：单独被抓 / 人数劣势', () => {
  assert.equal(deathVerdict({ allies: 0, enemies: 3 }).text, '单独被抓')
  assert.equal(deathVerdict({ allies: 1, enemies: 3 }).text, '人数劣势')
  assert.equal(deathVerdict({ allies: 3, enemies: 3 }).text, '人数相当')
})

test('zoneBars：去掉小于 3% 的，从大到小', () => {
  const bars = zoneBars({ 上路: 0.5, 中路: 0.02, 河道: 0.48 })
  assert.deepEqual(bars.map((b) => b.zone), ['上路', '河道'])
  for (const b of bars) assert.ok(ZONE_COLORS[b.zone])
})

test('frameIndexAt：二分找最近的帧', () => {
  const trail = [[0], [2], [4], [6]]
  assert.equal(frameIndexAt(trail, 0), 0)
  assert.equal(frameIndexAt(trail, 3), 2)
  assert.equal(frameIndexAt(trail, 99), 3)
})

test('unitName：英雄用中文名，召唤物按技能名 / 主人，其他归类', async () => {
  const { unitName } = await import('../src/domain/replay.js')
  const names = ['斧王', '兽王']
  const slugs = ['axe', 'beastmaster']
  assert.equal(unitName('npc_dota_hero_axe', 0, names, slugs), '斧王')
  assert.equal(unitName('npc_dota_witch_doctor_death_ward', -1, names, slugs), '死亡守卫')
  assert.equal(unitName('npc_dota_beastmaster_boar', -1, names, slugs), '兽王的召唤物')
  assert.equal(unitName('npc_dota_creep_badguys_ranged', -1, names, slugs), '小兵')
  assert.equal(unitName('npc_dota_goodguys_tower2_mid', -1, names, slugs), '防御塔')
  assert.equal(unitName('npc_dota_neutral_alpha_wolf', -1, names, slugs), '野怪')
})

test('spellName / fillHeroNames', async () => {
  const { spellName, fillHeroNames } = await import('../src/domain/replay.js')
  assert.equal(spellName('item_shivas_guard'), '希瓦的守护')
  assert.equal(spellName('eimermole_miners_slam'), '')
  assert.equal(fillHeroNames('对线输给 {h1}', ['a', '斧王']), '对线输给 斧王')
})

test('presenceText / fightOutcome / firstDown', async () => {
  const { presenceText, fightOutcome, firstDown } = await import('../src/domain/replay.js')
  const f = {
    start: 100, radiantLost: 1, direLost: 3, deathOrder: [6, 1, 7, 8],
    presence: [
      { present: true, arrive: 100 },
      { present: true, arrive: 115 },
      { present: false, dead: true },
      { present: false, dead: false, zone: '敌方野区', distance: 6420 }
    ]
  }
  assert.equal(presenceText(f, 0).text, '到场')
  assert.equal(presenceText(f, 1).text, '晚到 15 秒')
  assert.equal(presenceText(f, 2).level, 'info')
  assert.equal(presenceText(f, 3).text, '没到：在敌方野区，离 6400 码')
  assert.equal(fightOutcome(f, 2).level, 'good')
  assert.equal(fightOutcome(f, 3).text, '输 1:3')
  const teams = [2, 2, 2, 2, 2, 3, 3, 3, 3, 3]
  assert.deepEqual(firstDown(f, teams, 2), { mine: 1, theirs: 6 })
})

test('linePoints / wardLife', async () => {
  const { linePoints, wardLife } = await import('../src/domain/replay.js')
  assert.equal(linePoints([0, 50, 100], { w: 100, h: 10, min: 0, max: 100 }), '0.0,10.0 50.0,5.0 100.0,0.0')
  assert.equal(linePoints([1], { w: 100, h: 10, min: 0, max: 1 }), '')
  assert.equal(wardLife({ start: -30, end: 330 }, 2000), 330)
  assert.equal(wardLife({ start: 100, end: -1 }, 2000), 1900)
})
