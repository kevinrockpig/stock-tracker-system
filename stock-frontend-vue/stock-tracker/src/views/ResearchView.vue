<script setup>
// 研究：規則從哪裡來（R1、R2 研究的結論白話版）與其他研究頁面的入口；U1-6 會加入版本比較與 R1 的模擬帳戶
const RULES = [
  ['大盤濾網', '0050 收盤在季線（60 日均線）之上才買新股票；跌破時只暫停買進，已持有的照停利線處理。'],
  ['選股訊號', '「創 120 日新高」或「季線上揚時 MACD 黃金交叉」，任一出現就列為候選；候選太多時，優先買近 120 日漲幅較大的。'],
  ['買多少', '每檔約投入策略資金的 10%（每筆最多虧策略資金的 1%），最多同時 10 檔。'],
  ['何時賣', '從買進後的最高收盤價回落 10%，下一個交易日開盤賣出。'],
  ['核心', '另一部分資金買 0050 長期持有，每年初調回原本的比例。'],
]
</script>

<template>
  <section class="research">
    <h1>研究</h1>
    <p class="lead">網站上的規則都來自回測研究。以下是結論的白話版；回測只代表過去，不保證未來。</p>

    <article class="card">
      <h2>目前採用的規則</h2>
      <dl class="rules">
        <template v-for="[term, text] in RULES" :key="term">
          <dt>{{ term }}</dt>
          <dd>{{ text }}</dd>
        </template>
      </dl>
    </article>

    <article class="card">
      <h2>研究結論</h2>
      <ul>
        <li>
          2015～2026 年的回測中，「核心 0050＋策略選股」各半的組合，年化報酬約 23.8%、最大回落 −20.2%；
          同期只買 0050 為 19.5%、−29.8%。報酬差不多，<strong>回落少了約 10 個百分點</strong>。
        </li>
        <li>最有效的是<strong>大盤濾網</strong>：大盤轉弱時不買新股票。跌破季線就全部賣出反而最差，容易被洗出場。</li>
        <li>停利以回落 10% 較好；回落 20%、跌破季線才賣都太寬。</li>
        <li>加入月營收、三大法人買賣超都沒有讓結果穩定變好；「大跌前法人先買避險股」也沒有得到證據支持。</li>
        <li>
          數字偏樂觀：觀察名單是今天的 0050 成分股，事後看都是贏家，策略部分的報酬可能被高估。比較可信的是「降低回落」的部分。
        </li>
      </ul>
    </article>

    <div class="links">
      <RouterLink :to="{ name: 'paper' }" class="card link">
        <strong>交易計畫（往後實測）</strong>
        <span>用事先決定的規則操作模擬帳戶，檢驗回測在之後是否仍然成立</span>
      </RouterLink>
      <RouterLink :to="{ name: 'portfolio' }" class="card link">
        <strong>回測比較（舊方法）</strong>
        <span>先前各策略的投資組合回測；之後會換成新的研究結果</span>
      </RouterLink>
      <RouterLink :to="{ name: 'about' }" class="card link">
        <strong>使用說明與免責聲明</strong>
        <span>資料來源、指標算法、各頁面的說明</span>
      </RouterLink>
    </div>
  </section>
</template>

<style scoped>
.research {
  display: grid;
  gap: 16px;
}

.research h1 {
  margin-bottom: 0;
}

.lead {
  margin: -8px 0 0;
  color: #57606a;
  line-height: 1.6;
}

.card h2 {
  margin: 0 0 12px;
  font-size: 18px;
}

.rules {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: 8px 16px;
  margin: 0;
  line-height: 1.6;
}

.rules dt {
  color: #57606a;
  font-weight: 600;
}

.rules dd {
  margin: 0;
}

ul {
  display: grid;
  gap: 8px;
  margin: 0;
  padding-left: 1.2em;
  line-height: 1.6;
}

.links {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 12px;
}

.link {
  display: grid;
  gap: 4px;
  color: #1f2328;
}

.link:hover {
  border-color: #0b57d0;
  text-decoration: none;
}

.link span {
  color: #57606a;
  font-size: 13px;
  line-height: 1.5;
}

@media (max-width: 560px) {
  .rules {
    grid-template-columns: 1fr;
    gap: 0;
  }

  .rules dd {
    margin-bottom: 10px;
  }
}
</style>
