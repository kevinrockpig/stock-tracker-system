<script setup>
import { useRouter } from 'vue-router'
import { auth } from '../utils/auth.js'
import { openHoldingForm } from '../utils/holdings.js'

// 「加入庫存」：已登入時直接開啟表單；未登入時到庫存頁（說明與登入按鈕，登入回來後自動開啟這一檔的表單）。
// 伺服器沒有開放登入時不顯示
const props = defineProps({
  stock: { type: Object, required: true }, // { symbol, name, closePrice?, tradeDate? }
  compact: { type: Boolean, default: false }, // 清單中使用：只顯示「＋」
})

const router = useRouter()

function add() {
  if (auth.authenticated) openHoldingForm({ stock: props.stock })
  else router.push({ name: 'holdings', query: { add: props.stock.symbol } })
}
</script>

<template>
  <button
    v-if="auth.loginEnabled"
    type="button"
    class="add-holding"
    :class="{ compact }"
    :aria-label="`把 ${stock.name} 加入庫存`"
    :title="compact ? '加入庫存' : undefined"
    @click="add"
  >
    <span aria-hidden="true">＋</span><span v-if="!compact">加入庫存</span>
  </button>
</template>

<style scoped>
.add-holding {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  border: 1px solid #0b57d0;
  border-radius: 6px;
  background: #fff;
  color: #0b57d0;
  white-space: nowrap;
}

.add-holding:hover {
  background: #eef4fd;
}

.add-holding.compact {
  padding: 1px 8px;
  font-size: 13px;
}
</style>
