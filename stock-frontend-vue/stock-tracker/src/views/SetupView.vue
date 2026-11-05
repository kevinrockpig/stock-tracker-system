<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { auth, startLogin } from '../utils/auth.js'
import {
  ETF_BENCHMARK,
  FREQUENCIES,
  LOSS_OPTIONS,
  RECOMMENDED_VERSION,
  STRESS_MULTIPLIER,
  VERSIONS,
  allocation,
  planState,
  savePlan,
  suggestedInvest,
  versionStats,
} from '../utils/plan.js'

// 首次設定 / 調整我的計畫：三個問題 → 建議版本與投入金額 → 採用
const router = useRouter()

const capitalWan = ref(100) // 本金（萬元）
const maxLoss = ref(0.35)
const frequency = ref('daily')
const version = ref(RECOMMENDED_VERSION)
const investWan = ref(null) // 投入金額（萬元）；null 為依建議
const saving = ref(false)
const error = ref('')

// 已有計畫時帶入原本的設定（帶入時不把投入金額重設為建議值）
let applying = false
watch(
  () => planState.plan,
  (plan) => {
    if (!plan) return
    applying = true
    nextTick(() => (applying = false))
    capitalWan.value = plan.capital / 10000
    maxLoss.value = Number(plan.maxLoss)
    frequency.value = plan.frequency
    version.value = plan.version
    investWan.value = plan.investAmount / 10000
  },
  { immediate: true },
)

const capital = computed(() => Math.round((Number(capitalWan.value) || 0) * 10000))
const capitalValid = computed(() => capital.value >= 10000 && capital.value <= 1e10)
const suggested = computed(() => suggestedInvest(capital.value, maxLoss.value, version.value, frequency.value))
// 使用者改了本金、虧損、版本或頻率後，投入金額回到建議值
watch([capital, maxLoss, version, frequency], () => {
  if (!applying) investWan.value = null
})
const invest = computed(() => {
  const value = investWan.value == null || investWan.value === '' ? suggested.value : Math.round(Number(investWan.value) * 10000)
  return Math.min(Math.max(value || 0, 0), capital.value)
})
const plan = computed(() => ({ version: version.value, frequency: frequency.value, capital: capital.value, maxLoss: maxLoss.value, investAmount: invest.value }))
const money = computed(() => allocation(plan.value))
const stats = computed(() => versionStats(version.value, frequency.value))
const withinLimit = computed(() => stats.value.stress * (invest.value / (capital.value || 1)) <= maxLoss.value + 1e-9)

const wan = (value) => `${(Math.round(value / 1000) / 10).toLocaleString('zh-TW')} 萬`
const pct = (value, digits = 1) => `${(value * 100).toFixed(digits)}%`

async function adopt() {
  if (!capitalValid.value) return
  saving.value = true
  error.value = ''
  try {
    await savePlan(plan.value)
    router.push({ name: 'today' })
  } catch (err) {
    error.value = err.message
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="setup">
    <h1>{{ planState.plan ? '調整我的計畫' : '設定我的計畫' }}</h1>
    <p class="lead">回答三個問題，依回測研究建議資金怎麼分配。之後隨時可以調整。</p>

    <form class="card questions" @submit.prevent="adopt">
      <fieldset>
        <legend><span class="step">1</span>打算投入股市的本金</legend>
        <label class="money-input">
          <input v-model.number="capitalWan" type="number" min="1" max="1000000" step="1" inputmode="numeric" aria-label="本金（萬元）" />
          萬元
        </label>
        <div class="quick">
          <button v-for="v in [30, 50, 100, 300]" :key="v" type="button" :class="{ active: capitalWan === v }" @click="capitalWan = v">{{ v }} 萬</button>
        </div>
        <p v-if="!capitalValid" class="error">請填 1 萬元以上的金額。</p>
      </fieldset>

      <fieldset>
        <legend><span class="step">2</span>最多能承受帳面虧損多少，還能照計畫不賣？</legend>
        <div class="options">
          <label v-for="loss in LOSS_OPTIONS" :key="loss" :class="{ active: maxLoss === loss }">
            <input v-model="maxLoss" type="radio" name="loss" :value="loss" />−{{ Math.round(loss * 100) }}%
          </label>
        </div>
        <p class="hint" v-if="capitalValid">
          例如 −{{ Math.round(maxLoss * 100) }}%：本金 {{ wan(capital) }} 最多跌到 {{ wan(capital * (1 - maxLoss)) }}。
          同期只買 0050 曾經從高點回落 {{ pct(-ETF_BENCHMARK.maxDrawdown, 0) }}。
        </p>
      </fieldset>

      <fieldset>
        <legend><span class="step">3</span>多久看一次？</legend>
        <div class="options wide">
          <label v-for="f in FREQUENCIES" :key="f.code" :class="{ active: frequency === f.code }">
            <input v-model="frequency" type="radio" name="frequency" :value="f.code" />
            <span><strong>{{ f.label }}</strong><span class="hint">{{ f.hint }}</span></span>
          </label>
        </div>
      </fieldset>
    </form>

    <article v-if="capitalValid" class="card result" aria-labelledby="setup-result">
      <h2 id="setup-result">建議的配置</h2>

      <div class="versions" role="radiogroup" aria-label="版本">
        <label v-for="v in VERSIONS" :key="v.code" class="version" :class="{ active: version === v.code }">
          <input v-model="version" type="radio" name="version" :value="v.code" />
          <span class="title">
            {{ v.label }}<span v-if="v.code === RECOMMENDED_VERSION" class="tag">建議</span>
          </span>
          <span class="split">0050 {{ Math.round(v.core * 100) }}%・選股 {{ Math.round((1 - v.core) * 100) }}%</span>
          <span class="numbers">
            回測年化 {{ pct(versionStats(v.code, frequency).cagr) }}・最大回落 {{ pct(versionStats(v.code, frequency).maxDrawdown) }}
          </span>
          <span class="fit">{{ v.fit }}</span>
        </label>
      </div>

      <dl class="allocation">
        <div>
          <dt>投入股市</dt>
          <dd>
            <input v-model.number="investWan" type="number" min="0" :max="capital / 10000" step="1" :placeholder="String(suggested / 10000)" aria-label="投入金額（萬元）" />
            萬元
            <span class="hint">建議 {{ wan(suggested) }}</span>
          </dd>
        </div>
        <div><dt>0050（長期持有）</dt><dd>{{ wan(money.core) }}</dd></div>
        <div><dt>策略選股</dt><dd>{{ wan(money.satellite) }}，每檔約 {{ wan(money.perPosition) }}、最多 10 檔</dd></div>
        <div v-if="money.cash > 0"><dt>保留現金</dt><dd>{{ wan(money.cash) }}</dd></div>
      </dl>

      <p class="risk" :class="{ warn: !withinLimit }">
        <template v-if="withinLimit">
          <span aria-hidden="true">✓</span>
          回測最大回落 {{ pct(stats.maxDrawdown) }}；考慮未來可能更差（壓力假設 × {{ STRESS_MULTIPLIER }} ＝ −{{ pct(stats.stress) }}），
          以投入比例換算約為本金的 −{{ pct(stats.stress * (invest / capital)) }}，在你能承受的 −{{ Math.round(maxLoss * 100) }}% 以內。
        </template>
        <template v-else>
          <span aria-hidden="true">!</span>
          壓力假設下的回落約為本金的 −{{ pct(stats.stress * (invest / capital)) }}，超過你能承受的 −{{ Math.round(maxLoss * 100) }}%。
          建議投入 {{ wan(suggested) }}，其餘保留現金。
        </template>
      </p>
      <p class="note">
        回測期間 2015～2026 年以多頭為主，觀察名單是今天的 0050 成分股（事後看都是贏家），選股部分的報酬可能偏高；
        壓力假設是保守的經驗值，不是預測。這是依規則計算的範例配置，不是投資建議。
        <RouterLink :to="{ name: 'research' }">規則從哪裡來</RouterLink>
      </p>

      <p v-if="error" class="error">儲存失敗：{{ error }}</p>
      <div class="actions">
        <button type="button" class="primary" :disabled="saving" @click="adopt">{{ planState.plan ? '儲存變更' : '採用這個計畫' }}</button>
        <RouterLink :to="{ name: 'today' }">取消</RouterLink>
      </div>
      <p v-if="auth.loginEnabled && !auth.authenticated" class="note">
        計畫會先存在這台電腦的瀏覽器；<button type="button" class="link" @click="startLogin()">登入</button>後會存到你的帳號，換電腦也看得到。
      </p>
    </article>
  </section>
</template>

<style scoped>
.setup {
  display: grid;
  gap: 16px;
}

.setup h1 {
  margin-bottom: 0;
}

.lead {
  margin: -8px 0 0;
  color: #57606a;
}

.questions {
  display: grid;
  gap: 20px;
}

fieldset {
  margin: 0;
  padding: 0;
  border: 0;
}

legend {
  margin-bottom: 10px;
  font-size: 16px;
  font-weight: 600;
}

.step {
  display: inline-grid;
  place-items: center;
  width: 22px;
  height: 22px;
  margin-right: 8px;
  border-radius: 50%;
  background: #1f2328;
  color: #fff;
  font-size: 13px;
}

.money-input input,
.allocation input {
  width: 120px;
  padding: 8px 10px;
  border: 1px solid #ced4da;
  border-radius: 6px;
  font-size: 16px;
}

.quick,
.options {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.quick button,
.options label {
  padding: 8px 14px;
  border: 1px solid #ced4da;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
}

.options input {
  position: absolute;
  opacity: 0;
  pointer-events: none;
}

.options label:has(input:focus-visible),
.version:has(input:focus-visible) {
  outline: 2px solid #0b57d0;
  outline-offset: 2px;
}

.quick button.active,
.options label.active {
  border-color: #1f2328;
  background: #1f2328;
  color: #fff;
}

.options.wide label {
  display: flex;
  flex: 1 1 220px;
}

.options.wide label > span {
  display: grid;
  gap: 2px;
}

.options.wide label.active .hint {
  color: #d0d7de;
}

.hint {
  color: #666;
  font-size: 13px;
}

fieldset > .hint {
  margin: 8px 0 0;
}

.error {
  color: #d1242f;
}

.result h2 {
  margin: 0 0 12px;
  font-size: 18px;
}

.versions {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.version {
  position: relative;
  display: grid;
  align-content: start;
  gap: 4px;
  padding: 12px;
  border: 1px solid #d0d7de;
  border-radius: 8px;
  cursor: pointer;
}

.version input {
  position: absolute;
  opacity: 0;
}

.version.active {
  border: 2px solid #0b57d0;
  padding: 11px;
  background: #f5f9ff;
}

.version .title {
  font-weight: 600;
}

.tag {
  margin-left: 6px;
  padding: 0 6px;
  border-radius: 4px;
  background: #0b57d0;
  color: #fff;
  font-size: 12px;
  font-weight: 400;
}

.split,
.numbers {
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}

.fit {
  color: #57606a;
  font-size: 13px;
  line-height: 1.5;
}

.allocation {
  display: grid;
  gap: 8px;
  margin: 16px 0;
}

.allocation div {
  display: grid;
  grid-template-columns: 140px 1fr;
  align-items: center;
}

.allocation dt {
  color: #57606a;
}

.allocation dd {
  margin: 0;
  font-variant-numeric: tabular-nums;
}

.risk {
  padding: 10px 12px;
  border-radius: 6px;
  background: #eef8f0;
  line-height: 1.6;
}

.risk.warn {
  background: #fff8e1;
}

.note {
  color: #57606a;
  font-size: 13px;
  line-height: 1.6;
}

.actions {
  display: flex;
  gap: 16px;
  align-items: center;
}

.primary {
  padding: 10px 20px;
  border: 0;
  border-radius: 6px;
  background: #0b57d0;
  color: #fff;
  font-size: 15px;
}

.link {
  padding: 0;
  border: 0;
  background: none;
  color: #0b57d0;
  font-size: inherit;
}

@media (max-width: 720px) {
  .versions {
    grid-template-columns: 1fr;
  }

  .allocation div {
    grid-template-columns: 1fr;
    gap: 2px;
  }
}
</style>
