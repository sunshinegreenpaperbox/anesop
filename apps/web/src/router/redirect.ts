/** 只接受站内绝对路径，阻止 `//evil.com` 与登录页自身造成开放重定向/循环。 */
export function safeRedirect(value: unknown): string | null {
  if (typeof value !== 'string') return null
  if (!value.startsWith('/') || value.startsWith('//')) return null
  if (value.startsWith('/login')) return null
  return value
}
