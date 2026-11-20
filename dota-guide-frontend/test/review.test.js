// ============================================================
// test/review.test.js —— 比赛复盘页的展示换算
// ============================================================
import { test } from 'node:test'
import assert from 'node:assert/strict'

import {
  formatClock, pctText, pctTone, rankTierName, gameModeName, laneRoleName,
  adviceLabel, parseMatchInput, parseAccountInput, splitTeams
} from '../src/domain/review.js'

test('formatClock：正数、负数、空值', () => {
  assert.equal(formatClock(725), '12:05')
  assert.equal(formatClock(-30), '-0:30')
  assert.equal(formatClock(0), '0:00')
  assert.equal(formatClock(null), '—')
  assert.equal(formatClock(undefined), '—')
})

test('pctText / pctTone：阈值和后端规则一致', () => {
  assert.equal(pctText(0.643), '64%')
  assert.equal(pctText(null), '—')
  assert.equal(pctTone(0.7), 'good')
  assert.equal(pctTone(0.29), 'bad')
  assert.equal(pctTone(0.5), 'mid')
  assert.equal(pctTone(undefined), 'mid')
})

test('rankTierName：段位 + 星数，冠绝没有星数', () => {
  assert.equal(rankTierName(61), '万古流芳 1')
  assert.equal(rankTierName(75), '超凡入圣 5')
  assert.equal(rankTierName(80), '冠绝一世')
  assert.equal(rankTierName(null), '未定级')
  assert.equal(rankTierName(95), '未定级')
})

test('gameModeName / laneRoleName / adviceLabel', () => {
  assert.equal(gameModeName(22), '天梯全英雄选择')
  assert.equal(gameModeName(99), '模式 99')
  assert.equal(laneRoleName(2), '中路')
  assert.equal(laneRoleName(null), '未知')
  assert.equal(adviceLabel('bad'), '要注意')
  assert.equal(adviceLabel('xxx'), '提示')
})

test('parseMatchInput：数字和各家网站的比赛链接', () => {
  assert.equal(parseMatchInput(' 9098052821 '), '9098052821')
  assert.equal(parseMatchInput('https://www.opendota.com/matches/9098052821'), '9098052821')
  assert.equal(parseMatchInput('https://www.dotabuff.com/matches/9098052821/builds'), '9098052821')
  assert.equal(parseMatchInput('abc'), null)
  assert.equal(parseMatchInput(''), null)
})

test('parseAccountInput：好友代码、Steam64、玩家链接', () => {
  assert.equal(parseAccountInput('869293166'), '869293166')
  assert.equal(parseAccountInput('76561198829558894'), '869293166')
  assert.equal(parseAccountInput('https://www.opendota.com/players/869293166'), '869293166')
  assert.equal(parseAccountInput('hello'), null)
  assert.equal(parseAccountInput('123456789012345'), null)
})

test('splitTeams：分阵营并按号位排序', () => {
  const players = [
    { radiant: true, guessedPos: 3 },
    { radiant: false, guessedPos: 1 },
    { radiant: true, guessedPos: 1 }
  ]
  const { radiant, dire } = splitTeams(players)
  assert.deepEqual(radiant.map((p) => p.guessedPos), [1, 3])
  assert.equal(dire.length, 1)
  assert.deepEqual(splitTeams(undefined), { radiant: [], dire: [] })
})
