// 分頁標題：「頁面｜網站名稱」
export const SITE_NAME = '股票追蹤系統'

export function setTitle(title) {
  document.title = title ? `${title}｜${SITE_NAME}` : SITE_NAME
}
