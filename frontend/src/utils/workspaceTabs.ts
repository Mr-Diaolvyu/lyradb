/** 工作区标签的纯函数规则，供桌面式工作区与企业工作区共用。 */
export function resolveTabNavigationIndex(
  key: string,
  currentIndex: number,
  total: number,
): number | null {
  if (total <= 0) return null
  if (key === 'ArrowLeft') return Math.max(0, currentIndex - 1)
  if (key === 'ArrowRight') return Math.min(total - 1, currentIndex + 1)
  if (key === 'Home') return 0
  if (key === 'End') return total - 1
  return null
}

export function normalizedTableIdentity(
  connectionOrSource: string,
  namespace: string | null | undefined,
  table: string,
): string {
  return `${connectionOrSource}\u0000${namespace || ''}\u0000${table}`
    .toLocaleLowerCase()
}
