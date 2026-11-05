<script setup>
import StockList from '../components/StockList.vue'
</script>

<template>
  <section>
    <h1>選股</h1>
    <!-- U1-3 會把觀察名單、策略選股與我的自選合併成一頁；在那之前以分頁切換 -->
    <nav class="tabs" aria-label="選股">
      <RouterLink :to="{ name: 'stocks' }" exact-active-class="active">觀察名單</RouterLink>
      <RouterLink :to="{ name: 'screener' }">策略選股</RouterLink>
    </nav>
    <StockList />
  </section>
</template>
