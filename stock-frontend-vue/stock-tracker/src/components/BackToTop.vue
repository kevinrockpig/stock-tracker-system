<script setup>
// 捲動超過約一個畫面高時，右下角出現「回到頂端」按鈕（全站共用）
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { scrollMotion } from '../utils/scroll.js'

const visible = ref(false)
let frame = 0

function update() {
  frame = 0
  visible.value = window.scrollY > window.innerHeight * 0.8
}

// 捲動事件很頻繁，每個畫面更新最多計算一次
function onScroll() {
  if (!frame) frame = requestAnimationFrame(update)
}

function toTop() {
  window.scrollTo({ top: 0, behavior: scrollMotion() })
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  update()
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  cancelAnimationFrame(frame)
})
</script>

<template>
  <Transition name="fade">
    <button v-if="visible" type="button" class="back-to-top" aria-label="回到頂端" title="回到頂端" @click="toTop">
      ↑
    </button>
  </Transition>
</template>

<style scoped>
.back-to-top {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 30;
  width: 44px;
  height: 44px;
  padding: 0;
  border: 1px solid #d0d7de;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
  color: #1f2328;
  font-size: 20px;
  line-height: 1;
}

.back-to-top:hover {
  background: #f1f3f5;
}

.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s;
}

.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

@media (max-width: 600px) {
  .back-to-top {
    right: 16px;
    bottom: 16px;
  }
}
</style>
