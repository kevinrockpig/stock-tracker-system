<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { fetchNotificationPreview, fetchNotificationSettings, setRiskLevel, updateNotificationSettings } from '../api/stocks.js'
import { markLoggedOut } from '../utils/auth.js'

// 我的庫存頁的「每日通知」：Email 開關、除權息提醒天數、是否附上買進候選、每檔股票的風險設定（接近停利線的提醒範圍）與預覽
const props = defineProps({
  // 庫存頁的持股（symbol、name、exitLine）
  positions: { type: Array, required: true },
})

const settings = ref(null)
const form = reactive({ emailEnabled: false, exDividendDays: 3, entryAlerts: true })
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const status = ref('') // 儲存結果
const riskStatus = ref({}) // 代號 → 該列的儲存結果
const preview = ref(null)
const previewLoading = ref(false)

function handle(err) {
  if (err.status === 401) markLoggedOut()
  else error.value = err.message
}

function applySettings(value) {
  settings.value = value
  Object.assign(form, { emailEnabled: value.emailEnabled, exDividendDays: value.exDividendDays, entryAlerts: value.entryAlerts })
}

onMounted(async () => {
  loading.value = true
  try {
    applySettings(await fetchNotificationSettings())
  } catch (err) {
    handle(err)
  } finally {
    loading.value = false
  }
})

const dirty = computed(
  () =>
    settings.value != null &&
    (form.emailEnabled !== settings.value.emailEnabled ||
      form.exDividendDays !== settings.value.exDividendDays ||
      form.entryAlerts !== settings.value.entryAlerts),
)
const daysValid = computed(
  () => Number.isInteger(form.exDividendDays) && form.exDividendDays >= 0 && form.exDividendDays <= (settings.value?.maxExDividendDays ?? 30),
)
// 修改後清除上一次的結果與預覽（預覽依已儲存的設定產生）
watch(form, () => {
  status.value = ''
  error.value = ''
})

async function save() {
  if (!dirty.value || !daysValid.value) return
  saving.value = true
  error.value = ''
  try {
    applySettings(await updateNotificationSettings({ ...form }))
    status.value = form.emailEnabled ? `已儲存：每個交易日收盤後寄到 ${settings.value.email}。` : '已儲存：Email 通知已關閉。'
    preview.value = null
  } catch (err) {
    handle(err)
  } finally {
    saving.value = false
  }
}

const options = computed(() => settings.value?.riskOptions ?? [])
const riskOf = (symbol) => settings.value?.riskLevels[symbol] ?? 'standard'
// 目前實際的提醒範圍：大盤轉弱時已加上 weakMarketExtra（與寄出的 Email 相同）
const atrOf = (code) => {
  const base = options.value.find((o) => o.code === code)?.atrMultiple
  if (base == null) return null
  return settings.value.marketWeak ? Math.round((base + settings.value.weakMarketExtra) * 10) / 10 : base
}

async function changeRisk(position, code) {
  riskStatus.value = { ...riskStatus.value, [position.symbol]: '儲存中…' }
  try {
    settings.value = await setRiskLevel(position.symbol, code)
    riskStatus.value = { ...riskStatus.value, [position.symbol]: '已儲存' }
    preview.value = null
  } catch (err) {
    riskStatus.value = { ...riskStatus.value, [position.symbol]: '' }
    handle(err)
  }
}

async function loadPreview() {
  previewLoading.value = true
  error.value = ''
  try {
    preview.value = await fetchNotificationPreview()
  } catch (err) {
    handle(err)
  } finally {
    previewLoading.value = false
  }
}
</script>

<template>
  <p v-if="loading" class="status">讀取中…</p>
  <p v-if="error" class="error" role="alert">{{ error }}</p>

  <template v-if="settings">
    <p class="meta">
      每個交易日收盤、每日更新完成後（約下午 4 點）寄一封 Email 到 <strong>{{ settings.email }}</strong>，沒有提醒的日子也會寄「今日無提醒」。
      內容：大盤（0050 與季線）、跌破或接近移動停利線、即將除權息、交易計畫的買進候選。
    </p>

    <form class="notify-form" @submit.prevent="save">
      <label class="check"><input v-model="form.emailEnabled" type="checkbox" /> 開啟 Email 通知</label>
      <label>
        除權息：
        <input v-model.number="form.exDividendDays" type="number" min="0" :max="settings.maxExDividendDays" step="1" class="days" />
        天內提醒（0 為不提醒）
      </label>
      <label class="check"><input v-model="form.entryAlerts" type="checkbox" /> 附上交易計畫的買進候選（符合條件，非投資建議）</label>
      <p v-if="!daysValid" class="error">天數必須是 0～{{ settings.maxExDividendDays }} 的整數。</p>
      <div class="actions">
        <button type="submit" class="primary" :disabled="!dirty || !daysValid || saving">{{ saving ? '儲存中…' : '儲存' }}</button>
        <span v-if="status" class="saved" role="status">{{ status }}</span>
      </div>
    </form>

    <h3>接近停利線的提醒範圍</h3>
    <p class="meta">
      收盤價距離移動停利線在幾個 ATR（平均每日波動）以內就提醒：波動大的股票提醒範圍自然比較寬。大盤（0050）收盤低於季線時，每檔再加
      {{ settings.weakMarketExtra }} 個 ATR，提早提醒。
      <template v-if="settings.marketWeak">目前大盤收盤低於季線，下表已加上。</template>
      <template v-else-if="settings.marketWeak === false">目前大盤收盤在季線之上。</template>
    </p>
    <p v-if="!positions.length" class="status">還沒有庫存。</p>
    <table v-else class="risk-table">
      <thead>
        <tr>
          <th scope="col">股票</th>
          <th scope="col">風險設定</th>
          <th scope="col">提醒範圍</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="p in positions" :key="p.symbol">
          <td>{{ p.symbol.replace(/\.TWO?$/, '') }} {{ p.name }}</td>
          <td>
            <select
              :value="riskOf(p.symbol)"
              :aria-label="`${p.name} 的風險設定`"
              @change="changeRisk(p, $event.target.value)"
            >
              <option v-for="o in options" :key="o.code" :value="o.code">{{ o.label }}</option>
            </select>
            <span v-if="riskStatus[p.symbol]" class="saved">{{ riskStatus[p.symbol] }}</span>
          </td>
          <td>
            <template v-if="p.exitLine != null">{{ atrOf(riskOf(p.symbol)) }} 個 ATR 內</template>
            <span v-else class="muted">沒有買進日期，沒有停利線</span>
          </td>
        </tr>
      </tbody>
    </table>

    <div class="actions">
      <button type="button" :disabled="previewLoading" @click="loadPreview">{{ previewLoading ? '產生中…' : '預覽今天的通知' }}</button>
      <span class="meta">依已儲存的設定與最新收盤資料產生，不會寄出。</span>
    </div>
    <div v-if="preview" class="preview">
      <p class="subject">主旨：{{ preview.subject }}</p>
      <pre>{{ preview.body }}</pre>
    </div>
  </template>
</template>

<style scoped>
.meta {
  color: #555;
  font-size: 13px;
}

h3 {
  margin: 20px 0 4px;
  font-size: 15px;
}

.notify-form {
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-width: 560px;
  padding: 12px 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #f8f9fa;
  font-size: 14px;
}

.check {
  display: flex;
  align-items: center;
  gap: 6px;
}

.days {
  width: 4em;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px 12px;
  margin: 10px 0;
}

.primary {
  border: 1px solid #0b57d0;
  border-radius: 6px;
  background: #0b57d0;
  color: #fff;
}

.primary:disabled {
  opacity: 0.5;
}

.saved {
  margin-left: 6px;
  color: #137333;
  font-size: 13px;
}

.error {
  color: #b42318;
  font-size: 13px;
}

.muted {
  color: #777;
}

.risk-table {
  border-collapse: collapse;
  font-size: 14px;
}

.risk-table th,
.risk-table td {
  padding: 6px 10px;
  border-bottom: 1px solid #e5e7eb;
  text-align: left;
}

/* 股票與風險設定不換行（手機上名稱不被拆開），只有「提醒範圍」換行 */
.risk-table th:nth-child(-n + 2),
.risk-table td:nth-child(-n + 2) {
  white-space: nowrap;
}

.preview {
  max-width: 720px;
  padding: 10px 14px;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  background: #fff;
}

.subject {
  margin: 0 0 6px;
  font-weight: 600;
}

.preview pre {
  margin: 0;
  font-family: inherit;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

@media (max-width: 600px) {
  .risk-table th,
  .risk-table td {
    padding: 6px 4px;
  }
}
</style>
