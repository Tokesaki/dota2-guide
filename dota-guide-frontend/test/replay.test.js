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
