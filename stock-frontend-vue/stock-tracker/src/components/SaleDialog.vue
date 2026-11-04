<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { createSale, updateSale } from '../api/stocks.js'
import { markLoggedOut } from '../utils/auth.js'
import { formatPrice, isoDate, trendClass } from '../utils/format.js'
import {
  DEFAULT_FEE_RULES,
  FEE_MODES,
  MAX_HOLDING_SHARES,
  discountToRatio,
  estimateFee,
  formatShares,
  loadFeeSettings,
  ratioToDiscount,
  saveFeeSettings,
} from '../utils/holdings.js'

// 新增 / 修改一筆賣出（我的庫存頁）。已實現損益由伺服器依先進先出計算，這裡的成本與損益只是預估
const props = defineProps({
  open: { type: Boolean, required: true },
  // 要賣的股票：{ symbol, name, price, priceDate, lots }（lots 為 API 回傳的買進，依先進先出的順序）
  position: { type: Object, default: null },
  // 修改時的原始賣出（API 的 sale）
  sale: { type: Object, default: null },
  feeRules: { type: Object, default: () => DEFAULT_FEE_RULES },
})
const emit = defineEmits(['close', 'saved'])

const TAX_MODES = [
  { code: 'standard', label: '依稅率' },
  { code: 'actual', label: '實際金額' },
]

const dialogEl = ref(null)
const firstFieldEl = ref(null)
const form = ref({})
const saving = ref(false)
const error = ref('')

const editing = computed(() => props.sale != null)
const today = () => isoDate(new Date())
const isEtf = computed(() => props.position?.symbol.startsWith('00'))
const taxRate = computed(() => (isEtf.value ? props.feeRules.taxEtf : props.feeRules.taxStock))

// 還持有的股數（修改時加回這筆原本扣掉的部分）
const lots = computed(() => {
  const back = new Map((props.sale?.lots ?? []).map((l) => [l.holdingId, l.shares]))
  return (props.position?.lots ?? []).map((l) => ({ ...l, available: l.remainingShares + (back.get(l.id) ?? 0) }))
})
const heldShares = computed(() => lots.value.reduce((sum, l) => sum + l.available, 0))

function splitShares(total) {
  const whole = Math.floor(total)
  return { lots: Math.floor(whole / 1000), odd: whole % 1000 }
}

function resetForm() {
  const sale = props.sale
  if (sale) {
    form.value = {
      sellDate: sale.sellDate,
      price: sale.price,
      ...splitShares(sale.shares),
      feeMode: sale.feeMode,
      discount: sale.feeDiscount == null ? null : ratioToDiscount(sale.feeDiscount),
      minFee: sale.minFee ?? props.feeRules.defaultMinFee,
      fee: sale.feeMode === 'actual' ? sale.fee : null,
      taxMode: sale.taxMode,
      tax: sale.taxMode === 'actual' ? sale.tax : null,
    }
    return
  }
  const remembered = loadFeeSettings()
  form.value = {
    sellDate: today(),
    price: null,
    ...splitShares(heldShares.value), // 預設全部賣出（移動停利出場最常見）
    feeMode: remembered?.feeMode ?? 'standard',
    discount: remembered?.discount ?? null,
    minFee: remembered?.minFee ?? props.feeRules.defaultMinFee,
    fee: null,
    taxMode: 'standard',
    tax: null,
  }
}

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      dialogEl.value?.close()
      return
    }
    error.value = ''
    resetForm()
    dialogEl.value.showModal()
    await nextTick()
    firstFieldEl.value?.focus()
  },
)

function close() {
  emit('close')
}

const shares = computed(() => (Number(form.value.lots) || 0) * 1000 + (Number(form.value.odd) || 0))
const amount = computed(() => (Number(form.value.price) > 0 && shares.value > 0 ? Number(form.value.price) * shares.value : null))
const feePreview = computed(() => {
  const f = form.value
  if (f.feeMode === 'actual') return f.fee === '' || f.fee == null ? null : Number(f.fee)
  const discount = f.feeMode === 'discount' ? discountToRatio(Number(f.discount)) : 1
  if (f.feeMode === 'discount' && !(discount > 0)) return null
  return estimateFee(amount.value, { discount, minFee: f.minFee === '' || f.minFee == null ? null : Number(f.minFee), rules: props.feeRules })
})
const taxPreview = computed(() => {
  const f = form.value
  if (f.taxMode === 'actual') return f.tax === '' || f.tax == null ? null : Number(f.tax)
  // 與手續費相同：先四捨五入到 6 位小數再捨去，避免浮點誤差少算 1 元
  return amount.value == null ? null : Math.floor(Number((amount.value * taxRate.value).toFixed(6)))
})
const proceeds = computed(() => (amount.value == null || feePreview.value == null || taxPreview.value == null ? null : amount.value - feePreview.value - taxPreview.value))

// 先進先出預估：從最早買進的扣（沒有日期的視為最早），只扣賣出日（含）以前買進的
const estimate = computed(() => {
  const date = form.value.sellDate
  if (!date || !(shares.value > 0)) return null
  let need = shares.value
  let cost = 0
  for (const lot of lots.value) {
    if (need <= 0) break
    if (lot.available <= 0 || (lot.buyDate && lot.buyDate > date)) continue
    const taken = Math.min(lot.available, need)
    cost += (lot.cost * taken) / lot.currentShares
    need -= taken
  }
  return { cost, available: need > 0.0001 ? shares.value - need : null }
})
const estimatedPnl = computed(() => (proceeds.value == null || !estimate.value || estimate.value.available != null ? null : proceeds.value - estimate.value.cost))

const money = new Intl.NumberFormat('zh-TW', { maximumFractionDigits: 0 })
const signed = (value) => `${value > 0 ? '+' : ''}${money.format(value)}`

// 與 Java 相同的檢查，先在畫面上提示（存檔時伺服器仍會再檢查一次，包括先進先出是否夠賣）
function validate() {
  const f = form.value
  if (!f.sellDate) return '請填寫賣出日期'
  if (f.sellDate > today()) return '賣出日期不能晚於今天'
  if (!(Number(f.price) > 0)) return '請填寫成交價（大於 0）'
  const [lotCount, odd] = [Number(f.lots) || 0, Number(f.odd) || 0]
  if (!Number.isInteger(lotCount) || lotCount < 0 || !Number.isInteger(odd) || odd < 0 || odd > 999) {
    return '張數須為 0 以上的整數，零股為 0～999 股'
  }
  if (shares.value <= 0) return '請填寫張數或零股股數'
  if (shares.value > MAX_HOLDING_SHARES) return '股數太大'
  if (f.feeMode === 'discount' && !(Number(f.discount) > 0 && Number(f.discount) <= 10)) {
    return '折數請填 0～10 之間，例如 2.8 折填 2.8、六五折填 6.5'
  }
  if (f.feeMode !== 'actual' && !(f.minFee === '' || f.minFee == null || Number(f.minFee) >= 0)) return '最低手續費不能是負數'
  if (f.feeMode === 'actual' && !(f.fee !== '' && f.fee != null && Number(f.fee) >= 0)) return '請填寫實際手續費（0 以上）'
  if (f.taxMode === 'actual' && !(f.tax !== '' && f.tax != null && Number(f.tax) >= 0)) return '請填寫實際證交稅（0 以上）'
  return ''
}

function requestBody() {
  const f = form.value
  return {
    symbol: props.position.symbol,
    sellDate: f.sellDate,
    price: Number(f.price),
    shares: shares.value,
    feeMode: f.feeMode,
    feeDiscount: f.feeMode === 'discount' ? discountToRatio(Number(f.discount)) : null,
    minFee: f.feeMode === 'actual' || f.minFee === '' || f.minFee == null ? null : Number(f.minFee),
    fee: f.feeMode === 'actual' ? Number(f.fee) : null,
    taxMode: f.taxMode,
    tax: f.taxMode === 'actual' ? Number(f.tax) : null,
  }
}

async function submit() {
  error.value = validate()
  if (error.value) return
  saving.value = true
  try {
    const body = requestBody()
    const sale = editing.value ? await updateSale(props.sale.id, body) : await createSale(body)
    saveFeeSettings({
      feeMode: form.value.feeMode,
      discount: form.value.feeMode === 'discount' ? Number(form.value.discount) : loadFeeSettings()?.discount ?? null,
      minFee: body.minFee,
    })
    emit('saved', sale)
  } catch (err) {
    if (err.status === 401) markLoggedOut()
    error.value = err.message
  } finally {
    saving.value = false
  }
}

watch(form, () => (error.value = ''), { deep: true })
</script>

<template>
  <dialog ref="dialogEl" class="sale-dialog" aria-labelledby="sale-dialog-title" @close="close">
    <div class="head">
      <h2 id="sale-dialog-title">
        {{ editing ? '修改賣出' : '賣出' }}<template v-if="position">：{{ position.name }} <span class="code">{{ position.symbol }}</span></template>
      </h2>
      <button type="button" class="close" aria-label="關閉" @click="close">×</button>
    </div>

    <form v-if="position" class="body" novalidate @submit.prevent="submit">
      <div class="row">
        <label class="field">
          <span>賣出日期</span>
          <input ref="firstFieldEl" v-model="form.sellDate" type="date" :max="today()" required />
        </label>
        <label class="field">
          <span>成交價（每股）</span>
          <input v-model="form.price" type="number" inputmode="decimal" min="0" step="any" required />
        </label>
      </div>
      <p v-if="position.price != null && !editing" class="hint">
        最新收盤 {{ formatPrice(position.price) }}（{{ position.priceDate }}）
        <button type="button" class="link" @click="form.price = position.price">帶入</button>
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
      <p class="hint">
        共 {{ shares > 0 ? formatShares(shares) : '0 股' }}；目前持有 {{ formatShares(heldShares) }}
        <button v-if="shares !== Math.floor(heldShares)" type="button" class="link" @click="Object.assign(form, splitShares(heldShares))">全部</button>
      </p>

      <fieldset class="fee">
        <legend>手續費</legend>
        <div class="modes" role="radiogroup" aria-label="手續費方式">
          <label v-for="mode in FEE_MODES" :key="mode.code" :class="{ active: form.feeMode === mode.code }">
            <input v-model="form.feeMode" type="radio" name="sale-fee-mode" :value="mode.code" />
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

      <fieldset class="fee">
        <legend>證交稅</legend>
        <div class="modes" role="radiogroup" aria-label="證交稅方式">
          <label v-for="mode in TAX_MODES" :key="mode.code" :class="{ active: form.taxMode === mode.code }">
            <input v-model="form.taxMode" type="radio" name="sale-tax-mode" :value="mode.code" />
            {{ mode.label }}
          </label>
        </div>
        <p v-if="form.taxMode === 'standard'" class="hint">{{ isEtf ? 'ETF' : '股票' }} {{ Number((taxRate * 100).toFixed(2)) }}%，無條件捨去到元（當沖稅率不適用）</p>
        <label v-else class="field">
          <span>實際證交稅（元）</span>
          <input v-model="form.tax" type="number" inputmode="numeric" min="0" step="any" />
        </label>
      </fieldset>

      <dl v-if="amount" class="preview">
        <div>
          <dt>成交金額</dt>
          <dd>{{ money.format(amount) }}</dd>
        </div>
        <div>
          <dt>手續費 + 稅</dt>
          <dd>{{ feePreview == null || taxPreview == null ? '—' : money.format(feePreview + taxPreview) }}</dd>
        </div>
        <div>
          <dt>拿回金額</dt>
          <dd>{{ proceeds == null ? '—' : money.format(proceeds) }}</dd>
        </div>
        <div>
          <dt>買進成本（預估）</dt>
          <dd>{{ !estimate || estimate.available != null ? '—' : money.format(estimate.cost) }}</dd>
        </div>
        <div class="wide">
          <dt>已實現損益（預估）</dt>
          <dd :class="trendClass(estimatedPnl)">{{ estimatedPnl == null ? '—' : signed(estimatedPnl) }}</dd>
        </div>
      </dl>
      <p v-if="estimate?.available != null" class="warn">
        {{ form.sellDate }} 以前買進的只有約 {{ formatShares(estimate.available) }} 可賣（未計入當天之後才買的）。
      </p>
      <p class="hint">成本依先進先出（與券商相同）從最早買進的扣，沒有買進日期的視為最早；儲存後依實際紀錄計算。</p>

      <p v-if="error" class="error" role="alert">{{ error }}</p>
      <div class="actions">
        <button type="submit" class="primary" :disabled="saving">{{ saving ? '儲存中…' : editing ? '儲存修改' : '記錄賣出' }}</button>
        <button type="button" @click="close">取消</button>
      </div>
    </form>
  </dialog>
</template>

<style scoped>
/* 與 HoldingDialog（買進表單）相同的版面 */
.sale-dialog {
  box-sizing: border-box;
  width: min(480px, calc(100vw - 24px));
  max-height: calc(100vh - 24px);
  padding: 0;
  border: 0;
  border-radius: 8px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.25);
  color: #1f2328;
}

.sale-dialog::backdrop {
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

input[type='number'],
input[type='date'] {
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

.preview .wide {
  grid-column: span 2;
}

.preview dt {
  color: #666;
  font-size: 12px;
}

.preview dd {
  margin: 0;
  font-weight: 600;
}

.warn {
  margin: 8px 0 0;
  color: #b45309;
  font-size: 12px;
}

.error {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: #fdecea;
  color: #8a1c13;
  font-size: 13px;
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

@media (max-width: 480px) {
  .row:not(.pair) {
    grid-template-columns: 1fr;
    gap: 0;
  }
}
</style>
