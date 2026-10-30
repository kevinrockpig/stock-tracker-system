// 個股頁的圖表顯示設定（區間、均線、副圖、參考價位線），存在瀏覽器的 localStorage：
// - 所有股票共用同一份設定，重新整理或換股票後維持
// - 只存在這台電腦的這個瀏覽器，不會傳到伺服器
// - 讀到不認得的值（例如日後移除某個指標）時略過，整份無法解析時回到預設值
import {
  CHART_RANGES,
  DEFAULT_MA_KEYS,
  DEFAULT_MONTHS,
  DEFAULT_SHOW_LEVELS,
  DEFAULT_SUB_CHART_KEYS,
  MA_LINES,
  SUB_CHARTS,
} from './chartConfig.js'

const STORAGE_KEY = 'stock-tracker:chart-settings:v1'

export function defaultChartSettings() {
  return {
    months: DEFAULT_MONTHS,
    maKeys: [...DEFAULT_MA_KEYS],
    subChartKeys: [...DEFAULT_SUB_CHART_KEYS],
    showLevels: DEFAULT_SHOW_LEVELS,
  }
}

// 只保留設定檔中存在的代碼，並依設定檔的順序排列（副圖由上而下的順序固定）
function pickKnown(saved, options, fallback) {
  if (!Array.isArray(saved)) return [...fallback]
  return options.map((option) => option.key).filter((key) => saved.includes(key))
}

export function loadChartSettings() {
  const defaults = defaultChartSettings()
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY))
    if (!saved || typeof saved !== 'object') return defaults
    return {
      months: CHART_RANGES.some((range) => range.months === saved.months) ? saved.months : defaults.months,
      maKeys: pickKnown(saved.maKeys, MA_LINES, defaults.maKeys),
      subChartKeys: pickKnown(saved.subChartKeys, SUB_CHARTS, defaults.subChartKeys),
      // 舊版存檔沒有這個設定時使用預設值
      showLevels: typeof saved.showLevels === 'boolean' ? saved.showLevels : defaults.showLevels,
    }
  } catch {
    // 內容損毀，或瀏覽器停用儲存空間（例如部分無痕模式）：使用預設值
    return defaults
  }
}

export function saveChartSettings(settings) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(settings))
  } catch {
    // 無法儲存時不影響畫面，只是下次開啟回到預設值
  }
}
