/**
 * 本地时区日期（YYYY-MM-DD）。排程按院区本地日期查询，禁止用 `toISOString()` 取 UTC 日期
 * （东八区 00:00–07:59 会落到前一天）。
 */
export function formatLocalDate(value: Date = new Date()): string {
  const year = value.getFullYear()
  const month = String(value.getMonth() + 1).padStart(2, '0')
  const day = String(value.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}
