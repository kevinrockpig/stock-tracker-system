// 捲動相關的共用函式

// 使用者在系統設定「減少動態效果」時不使用平滑捲動（無障礙）
export function scrollMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
}

// 用 #id 找段落；網址上的 hash 可能是任意文字，不用 querySelector 以免不合法的選擇器拋出錯誤
export function findHashTarget(hash) {
  if (!hash || hash.length < 2) return null
  try {
    return document.getElementById(decodeURIComponent(hash.slice(1)))
  } catch {
    return null
  }
}
