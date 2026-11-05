// 我的計畫：首次設定的建議、資金分配與股數計算，以及存放（未登入存瀏覽器、登入後存帳號）
import { reactive } from 'vue'
import { fetchMyPlan, saveMyPlan } from '../api/stocks.js'
import { auth } from './auth.js'

// R1 研究的三個版本（2015-11-23 ～ 2026-11-04 回測，見研究報告 R1 與 TODO.md）；只差核心 0050 的比例，其餘規則相同。
// 數字是回測結果，不是預測：觀察名單有倖存者偏差，策略部分的報酬可能偏高
export const VERSIONS = [
  {
    code: 'core',
    label: '核心為主',
    core: 0.7,
    daily: { cagr: 0.223, maxDrawdown: -0.241 },
    weekly: { cagr: 0.216, maxDrawdown: -0.257 },
    fit: '最省事：大部分資金放 0050。但 0050 本身沒有保護，大跌時回落最大。',
  },
  {
    code: 'balanced',
    label: '均衡',
    core: 0.5,
    daily: { cagr: 0.238, maxDrawdown: -0.202 },
    weekly: { cagr: 0.231, maxDrawdown: -0.226 },
    fit: '報酬與回落較平衡，回測數字的可信度居中。',
  },
  {
    code: 'strategy',
    label: '策略為主',
    core: 0.3,
    daily: { cagr: 0.251, maxDrawdown: -0.174 },
    weekly: { cagr: 0.245, maxDrawdown: -0.215 },
    fit: '回測回落最小，但靠選股的部分最多：數字最可能偏樂觀，也需要較常下單。',
  },
]
export const RECOMMENDED_VERSION = 'balanced'
export const ETF_BENCHMARK = { cagr: 0.195, maxDrawdown: -0.298 } // 同期只買 0050

// 壓力假設：回測期間以多頭為主，未來的回落可能更大，以「回測最大回落 × 1.5」估計（保守的經驗假設，不是預測）
export const STRESS_MULTIPLIER = 1.5

// 部位：每筆最多虧策略資金的 1%、停損距離 10% → 每檔約策略資金的 10%（單檔上限 20%），最多 10 檔（與 Java PlanRules 一致）
export const POSITION_WEIGHT = 0.01 / 0.1
export const SLOTS = 10
export const CORE_SYMBOL = '0050.TW'

export const FREQUENCIES = [
  { code: 'daily', label: '每天', hint: '每個交易日收盤後花幾分鐘看一次' },
  { code: 'weekly', label: '每週', hint: '每週五收盤後看一次，下週一開盤下單' },
]
export const LOSS_OPTIONS = [0.1, 0.15, 0.2, 0.25, 0.3, 0.35, 0.4, 0.5]

export const versionOf = (code) => VERSIONS.find((v) => v.code === code) ?? VERSIONS.find((v) => v.code === RECOMMENDED_VERSION)
export const frequencyLabel = (code) => FREQUENCIES.find((f) => f.code === code)?.label ?? code

// 某個版本在這個操作頻率下的回測數字與壓力假設
export function versionStats(code, frequency) {
  const v = versionOf(code)
  const stats = v[frequency] ?? v.daily
  return { ...stats, stress: Math.abs(stats.maxDrawdown) * STRESS_MULTIPLIER }
}

// 建議投入金額：壓力假設下的回落超過可承受的虧損時，只投入一部分（其餘保留現金），以萬元為單位無條件捨去
export function suggestedInvest(capital, maxLoss, code, frequency) {
  const { stress } = versionStats(code, frequency)
  if (!(capital > 0)) return 0
  if (stress <= maxLoss) return capital
  return Math.floor((capital * maxLoss) / stress / 10000) * 10000
}

export function recommend({ capital, maxLoss, frequency }) {
  return { version: RECOMMENDED_VERSION, frequency, capital, maxLoss, investAmount: suggestedInvest(capital, maxLoss, RECOMMENDED_VERSION, frequency) }
}

// 資金分配：核心 0050、策略資金、每檔金額
export function allocation(plan) {
  const invest = plan.investAmount
  const core = Math.round(invest * versionOf(plan.version).core)
  const satellite = invest - core
  return { invest, core, satellite, cash: plan.capital - invest, perPosition: Math.floor(satellite * POSITION_WEIGHT) }
}

// 以某個價格買進一檔的股數（可買零股，無條件捨去）
export function sharesFor(plan, price) {
  if (!plan || !(price > 0)) return 0
  return Math.floor(allocation(plan).perPosition / price)
}

// ---- 存放 ----

const STORAGE_KEY = 'stock-tracker:plan'

export const planState = reactive({
  loaded: false,
  plan: null,
  source: null, // 'account' | 'browser'
  error: '',
})

function readLocal() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

function writeLocal(plan) {
  try {
    if (plan) localStorage.setItem(STORAGE_KEY, JSON.stringify(plan))
    else localStorage.removeItem(STORAGE_KEY)
  } catch {
    // 瀏覽器停用儲存空間：這次瀏覽仍可使用，只是不會記住
  }
}

const toBody = ({ version, frequency, capital, maxLoss, investAmount }) => ({ version, frequency, capital, maxLoss, investAmount })

// 登入狀態確定後呼叫：登入時以帳號中的為準；帳號還沒有計畫、但瀏覽器有時，存到帳號（並清除瀏覽器的那份）
export async function loadPlan() {
  planState.error = ''
  if (!auth.authenticated) {
    Object.assign(planState, { plan: readLocal(), source: 'browser', loaded: true })
    return
  }
  try {
    let { plan } = await fetchMyPlan()
    const local = readLocal()
    if (!plan && local) {
      plan = await saveMyPlan(toBody(local))
    }
    if (plan) writeLocal(null)
    Object.assign(planState, { plan, source: 'account', loaded: true })
  } catch (err) {
    Object.assign(planState, { plan: readLocal(), source: 'browser', loaded: true, error: err.message })
  }
}

export async function savePlan(plan) {
  const body = toBody(plan)
  if (auth.authenticated) {
    const saved = await saveMyPlan(body)
    Object.assign(planState, { plan: saved, source: 'account' })
    return saved
  }
  writeLocal(body)
  Object.assign(planState, { plan: body, source: 'browser' })
  return body
}
