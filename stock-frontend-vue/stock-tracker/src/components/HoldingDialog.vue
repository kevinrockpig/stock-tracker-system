<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { createHolding, fetchStocks, updateHolding } from '../api/stocks.js'
import { auth, markLoggedOut, startLogin } from '../utils/auth.js'
import { formatPrice, isoDate } from '../utils/format.js'
import { matchesQuery } from '../utils/groups.js'
import {
  DEFAULT_FEE_RULES,
  FEE_MODES,
  MAX_HOLDING_SHARES,
  closeHoldingForm,
  discountToRatio,
  estimateFee,
  formatShares,
  holdingForm,
  loadFeeSettings,
  ratioToDiscount,
  saveFeeSettings,
} from '../utils/holdings.js'

// 新增 / 修改一筆庫存。全站只有一個（App.vue），由 openHoldingForm() 開啟

const dialogEl = ref(null)
const firstFieldEl = ref(null)

const stock = ref(null) // { symbol, name, closePrice, tradeDate, listedDate }
const stocks = ref([]) // 選股票用；第一次需要時才載入
const stocksError = ref('')
const search = ref('')

const form = ref({})
const saving = ref(false)
const error = ref('')
const saved = ref(null) // 新增成功後顯示結果（從庫存頁開啟時直接關閉）
const needsLogin = ref(false)

const editing = computed(() => holdingForm.lot != null)
const today = () => isoDate(new Date())

function resetForm() {
  const lot = holdingForm.lot
  if (lot) {
    form.value = {
      buyDate: lot.buyDate ?? '',
      price: lot.price,
      lots: Math.floor(lot.shares / 1000),
      odd: lot.shares % 1000,
      feeMode: lot.feeMode,
      discount: lot.feeDiscount == null ? null : ratioToDiscount(lot.feeDiscount),
      minFee: lot.minFee ?? DEFAULT_FEE_RULES.defaultMinFee,
      fee: lot.feeMode === 'actual' ? lot.fee : null,
    }
    return
  }
  const remembered = loadFeeSettings()
  form.value = {
    buyDate: '',
    price: null,
    lots: 1,
    odd: 0,
    feeMode: remembered?.feeMode ?? 'standard',
    discount: remembered?.discount ?? null,
    minFee: remembered?.minFee ?? DEFAULT_FEE_RULES.defaultMinFee,
    fee: null,
  }
}

async function loadStocks() {
  if (stocks.value.length) return
  stocksError.value = ''
  try {
    stocks.value = await fetchStocks()
  } catch (err) {
    stocksError.value = err.message
  }
}

watch(
  () => holdingForm.open,
  async (open) => {
    if (!open) {
      dialogEl.value?.close()
      return
    }
    error.value = ''
    saved.value = null
    search.value = ''
    needsLogin.value = !auth.authenticated
    stock.value = holdingForm.stock
    resetForm()
    dialogEl.value.showModal()
    // 補上收盤價與上市日（從個股頁、庫存頁開啟時只有代號與名稱）；沒有指定股票時載入清單讓使用者挑選
    await loadStocks()
    if (stock.value) stock.value = { ...stock.value, ...stocks.value.find((s) => s.symbol === stock.value.symbol) }
    await nextTick()
    firstFieldEl.value?.focus()
  },
)

// 對話框被 Esc 關閉時同步狀態
function onClose() {
  if (holdingForm.open) closeHoldingForm()
}

const candidates = computed(() => stocks.value.filter((s) => matchesQuery(s, search.value)))
async function pick(s) {
  stock.value = s
  await nextTick()
  firstFieldEl.value?.focus()
}

const shares = computed(() => (Number(form.value.lots) || 0) * 1000 + (Number(form.value.odd) || 0))
const amount = computed(() => (Number(form.value.price) > 0 && shares.value > 0 ? Number(form.value.price) * shares.value : null))
const feePreview = computed(() => {
  const f = form.value
  if (f.feeMode === 'actual') return f.fee === '' || f.fee == null ? null : Number(f.fee)
  const discount = f.feeMode === 'discount' ? discountToRatio(Number(f.discount)) : 1
  if (f.feeMode === 'discount' && !(discount > 0)) return null
  return estimateFee(amount.value, { discount, minFee: f.minFee === '' || f.minFee == null ? null : Number(f.minFee) })
})

const money = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 2 })

// 與 Java 相同的檢查，先在畫面上提示（存檔時伺服器仍會再檢查一次）
function validate() {
  const f = form.value
  if (!stock.value) return '請選擇股票'
  if (!(Number(f.price) > 0)) return '請填寫成交價（大於 0）'
  const [lots, odd] = [Number(f.lots) || 0, Number(f.odd) || 0]
  if (!Number.isInteger(lots) || lots < 0 || !Number.isInteger(odd) || odd < 0 || odd > 999) {
    return '張數須為 0 以上的整數，零股為 0～999 股'
  }
  if (shares.value <= 0) return '請填寫張數或零股股數'
  if (shares.value > MAX_HOLDING_SHARES) return '股數太大'
  if (f.buyDate && f.buyDate > today()) return '買進日期不能晚於今天'
  if (f.buyDate && stock.value.listedDate && f.buyDate < stock.value.listedDate) {
    return `買進日期不能早於上市（櫃）日 ${stock.value.listedDate}`
  }
  if (f.feeMode === 'discount' && !(Number(f.discount) > 0 && Number(f.discount) <= 10)) {
    return '折數請填 0～10 之間，例如 2.8 折填 2.8、六五折填 6.5'
  }
  if (f.feeMode !== 'actual' && !(f.minFee === '' || f.minFee == null || Number(f.minFee) >= 0)) return '最低手續費不能是負數'
  if (f.feeMode === 'actual' && !(f.fee !== '' && f.fee != null && Number(f.fee) >= 0)) return '請填寫實際手續費（0 以上）'
  return ''
}

function requestBody() {
  const f = form.value
  return {
    symbol: stock.value.symbol,
    buyDate: f.buyDate || null,
    price: Number(f.price),
    shares: shares.value,
    feeMode: f.feeMode,
    feeDiscount: f.feeMode === 'discount' ? discountToRatio(Number(f.discount)) : null,
    minFee: f.feeMode === 'actual' || f.minFee === '' || f.minFee == null ? null : Number(f.minFee),
    fee: f.feeMode === 'actual' ? Number(f.fee) : null,
  }
}

async function submit() {
  error.value = validate()
  if (error.value) return
  saving.value = true
  try {
    const body = requestBody()
    const lot = editing.value ? await updateHolding(holdingForm.lot.id, body) : await createHolding(body)
    saveFeeSettings({
      feeMode: form.value.feeMode,
      discount: form.value.feeMode === 'discount' ? Number(form.value.discount) : loadFeeSettings()?.discount ?? null,
      minFee: body.minFee,
    })
    const onSaved = holdingForm.onSaved
    if (onSaved) {
      closeHoldingForm()
      onSaved(lot)
    } else {
      saved.value = lot
    }
  } catch (err) {
    if (err.status === 401) {
      markLoggedOut()
      needsLogin.value = true
    }
    error.value = err.message
  } finally {
    saving.value = false
  }
}

// 修改內容後清除上一次的錯誤訊息（避免顯示已經改正的錯誤）
watch(form, () => (error.value = ''), { deep: true })
watch(stock, () => (error.value = ''))

const savedSymbol = computed(() => saved.value?.symbol ?? stock.value?.symbol)
</script>

<template>
  <dialog ref="dialogEl" class="holding-dialog" aria-labelledby="holding-dialog-title" @close="onClose">
    <div class="head">
      <h2 id="holding-dialog-title">
        {{ editing ? '修改庫存' : '加入庫存' }}<template v-if="stock">：{{ stock.name }} <span class="code">{{ stock.symbol }}</span></template>
      </h2>
      <button type="button" class="close" aria-label="關閉" @click="closeHoldingForm">×</button>
    </div>

    <!-- 未登入（例如登入已逾時） -->
    <div v-if="needsLogin" class="body">
      <p>庫存存在你的帳號中，需要先登入。</p>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions">
        <button type="button" class="primary" @click="startLogin(stock?.symbol)">使用 Google 登入</button>
        <button type="button" @click="closeHoldingForm">取消</button>
      </div>
    </div>

    <!-- 新增完成 -->
    <div v-else-if="saved" class="body">
      <p class="done" role="status">
        已加入：{{ formatShares(saved.shares) }} @ {{ formatPrice(saved.price) }}，手續費 {{ money.format(saved.fee) }} 元，成本
        {{ money.format(saved.cost) }} 元。
      </p>
      <div class="actions">
        <RouterLink :to="{ name: 'holdings', hash: `#holding-${savedSymbol}` }" class="button primary" @click="closeHoldingForm"
          >查看我的庫存</RouterLink
        >
        <button type="button" @click="closeHoldingForm">完成</button>
      </div>
    </div>

    <!-- 選股票（從庫存頁「新增一筆」開啟時） -->
    <div v-else-if="!stock" class="body">
      <label class="field">
        <span>股票</span>
        <input ref="firstFieldEl" v-model="search" type="search" placeholder="搜尋代號或名稱" aria-label="搜尋代號或名稱" />
      </label>
      <p v-if="stocksError" class="error">{{ stocksError }}</p>
      <p v-else-if="!stocks.length" class="hint">載入中…</p>
      <ul v-else class="picker">
        <li v-for="s in candidates" :key="s.symbol">
          <button type="button" @click="pick(s)">
            <span>{{ s.name }} <span class="code">{{ s.symbol }}</span></span>
            <span class="code">{{ formatPrice(s.closePrice) }}</span>
          </button>
        </li>
        <li v-if="!candidates.length" class="hint">沒有符合的股票（目前只能加入觀察名單內的股票）。</li>
      </ul>
    </div>

    <!-- 表單 -->
    <form v-else class="body" novalidate @submit.prevent="submit">
      <div class="row">
        <label class="field">
          <span>成交價（每股）</span>
          <input ref="firstFieldEl" v-model="form.price" type="number" inputmode="decimal" min="0" step="any" required />
        </label>
        <label class="field">
          <span>買進日期<small>（選填）</small></span>
          <input v-model="form.buyDate" type="date" :max="today()" :min="stock.listedDate || undefined" />
        </label>
      </div>
      <p v-if="stock.closePrice != null && !editing" class="hint">
        最新收盤 {{ formatPrice(stock.closePrice) }}（{{ stock.tradeDate }}）
        <button type="button" class="link" @click="form.price = stock.closePrice">帶入</button>
      </p>
      <p class="hint">
        填寫買進日期才會計算已領股利、移動停利線與同期比較，之後若有分割、配股也會自動換算股數。成交價填買進當時的價格。
      </p>

      <div class="row pair">
        <label class="field">
          <span>張數</span>
          <input v-model="form.lots" type="number" inputmode="numeric" min="0" step="1" />
        </label>
        <label class="field">
          <span>零股（股）</span>
          <input v-model="form.odd" type="number" inputmode="numeric" min="0" max="999" step="1" />
        </label>
      </div>
      <p class="hint">共 {{ shares > 0 ? formatShares(shares) : '0 股' }}（1 張 = 1,000 股）</p>

      <fieldset class="fee">
        <legend>手續費</legend>
        <div class="modes" role="radiogroup">
          <label v-for="mode in FEE_MODES" :key="mode.code" :class="{ active: form.feeMode === mode.code }">
            <input v-model="form.feeMode" type="radio" name="fee-mode" :value="mode.code" />
            {{ mode.label }}
          </label>
        </div>
        <div class="row">
          <label v-if="form.feeMode === 'discount'" class="field">
            <span>折數</span>
            <span class="suffix">
              <input v-model="form.discount" type="number" inputmode="decimal" min="0" max="10" step="any" placeholder="2.8" />
              折
            </span>
          </label>
          <label v-if="form.feeMode !== 'actual'" class="field">
            <span>最低手續費（元）</span>
            <input v-model="form.minFee" type="number" inputmode="numeric" min="0" step="any" />
          </label>
          <label v-else class="field">
            <span>實際手續費（元）</span>
            <input v-model="form.fee" type="number" inputmode="numeric" min="0" step="any" />
          </label>
        </div>
      </fieldset>

      <dl v-if="amount" class="preview">
        <div>
          <dt>成交金額</dt>
          <dd>{{ money.format(amount) }}</dd>
        </div>
        <div>
          <dt>手續費{{ form.feeMode === 'actual' ? '' : '（試算）' }}</dt>
          <dd>{{ feePreview == null ? '—' : money.format(feePreview) }}</dd>
        </div>
        <div>
          <dt>買進成本</dt>
          <dd>{{ feePreview == null ? '—' : money.format(amount + feePreview) }}</dd>
        </div>
      </dl>
      <p v-if="form.feeMode !== 'actual'" class="hint">無條件捨去到元，低於最低手續費以最低手續費計；各券商規則不同，存檔後可改為實際金額。</p>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions">
        <button type="submit" class="primary" :disabled="saving">{{ saving ? '儲存中…' : editing ? '儲存修改' : '加入庫存' }}</button>
        <button v-if="!editing && !holdingForm.stock" type="button" @click="stock = null">換一檔</button>
        <button type="button" @click="closeHoldingForm">取消</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
.holding-dialog {
  box-sizing: border-box;
  width: min(480px, calc(100vw - 24px));
  max-height: calc(100vh - 24px);
  padding: 0;
  border: 0;
  border-radius: 8px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  color: #1f2328;
}

.holding-dialog::backdrop {
  background: rgba(0, 0, 0, 0.35);
}

.head {
  position: sticky;
  top: 0;
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 14px 16px 10px;
  border-bottom: 1px solid #e5e7eb;
  background: #fff;
}

h2 {
  margin: 0;
  font-size: 17px;
}

.code {
  color: #888;
  font-size: 12px;
  font-weight: normal;
}

.close {
  padding: 0 8px;
  border: 0;
  background: none;
  font-size: 22px;
  line-height: 1;
}

.body {
  padding: 12px 16px 16px;
}

.row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 8px;
  font-size: 13px;
  color: #444;
}

.field small {
  color: #888;
}

input[type='number'],
input[type='date'],
input[type='search'] {
  box-sizing: border-box;
  width: 100%;
  padding: 6px 8px;
  border: 1px solid #ced4da;
  border-radius: 6px;
  font-size: 15px;
}

.suffix {
  display: flex;
  align-items: center;
  gap: 6px;
}

.hint {
  margin: 4px 0 0;
  color: #666;
  font-size: 12px;
}

.link {
  margin-left: 4px;
  padding: 0;
  border: 0;
  background: none;
  color: #0b57d0;
  font-size: 12px;
}

.fee {
  margin: 12px 0 0;
  padding: 8px 10px 10px;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
}

.fee legend {
  padding: 0 4px;
  font-size: 13px;
  color: #444;
}

.modes {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.modes label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 10px;
  border: 1px solid #ced4da;
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.modes label.active {
  border-color: #1f2328;
  background: #1f2328;
  color: #fff;
}

.modes input {
  margin: 0;
}

.preview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 6px;
  margin: 12px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #f6f8fa;
  font-variant-numeric: tabular-nums;
}

.preview dt {
  color: #666;
  font-size: 12px;
}

.preview dd {
  margin: 0;
  font-weight: 600;
}

.error {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #fdecea;
  color: #8a1c13;
  font-size: 13px;
}

.done {
  margin: 0;
  font-size: 14px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.primary {
  border: 1px solid #0b57d0;
  border-radius: 6px;
  background: #0b57d0;
  color: #fff;
}

.button {
  display: inline-block;
  padding: 6px 14px;
  font-size: 14px;
}

.button:hover {
  text-decoration: none;
}

.picker {
  max-height: 50vh;
  margin: 8px 0 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.picker button {
  display: flex;
  justify-content: space-between;
  width: 100%;
  padding: 8px 6px;
  border: 0;
  border-bottom: 1px solid #f1f3f5;
  background: none;
  text-align: left;
}

.picker button:hover {
  background: #f6f8fa;
}

@media (max-width: 480px) {
  .row:not(.pair) {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
