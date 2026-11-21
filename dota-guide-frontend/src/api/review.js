// ============================================================
// 比赛复盘接口（后端 ReviewController）
// ============================================================
// 和 api/hero.js 一样的拦截器：code 200 直接返回 data，其余变成 Error(message)。
// 超时比图鉴接口长：后端第一次拉某场比赛要去 OpenDota 取数据，慢的时候要十几秒。
import axios from 'axios'

const request = axios.create({
  baseURL: '/api/review',
  timeout: 60000
})

request.interceptors.response.use(
  (response) => {
    const { code, message, data } = response.data
    if (code === 200) return data
    return Promise.reject(new Error(message || '请求失败'))
  },
  (error) => Promise.reject(new Error(error.message || '网络错误'))
)

/** 玩家资料 + 最近 20 场；accountId = Steam 好友代码 */
export function getPlayer(accountId) {
  return request.get(`/players/${accountId}`)
}

/** 单场复盘 */
export function getMatchReview(matchId) {
  return request.get(`/matches/${matchId}`)
}

/** 请求 OpenDota 解析这场录像；返回 true = 已发出请求，false = 30 分钟内已请求过 */
export function requestParse(matchId) {
  return request.post(`/matches/${matchId}/parse`)
}

/** 英雄的高分局出装；key 是 slug 或数字 id */
export function getHeroBuilds(key) {
  return request.get(`/heroes/${key}/builds`)
}

/** 录像深度解析的状态 / 结果：{ state: none|running|done|error, message, result } */
export function getReplay(matchId) {
  return request.get(`/matches/${matchId}/replay`)
}

/** 开始解析：后端先找本机 Dota 录像文件夹，没有就从 Valve 下载 */
export function startReplay(matchId) {
  return request.post(`/matches/${matchId}/replay`)
}

/** 手动上传 .dem（或 Valve 下载的 .dem.bz2）录像文件 */
export function uploadReplay(matchId, file) {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/matches/${matchId}/replay/upload`, form, { timeout: 10 * 60 * 1000 })
}
