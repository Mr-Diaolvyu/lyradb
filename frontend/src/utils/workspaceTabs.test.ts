import { describe, expect, it } from 'vitest'
import {
  normalizedTableIdentity,
  resolveTabNavigationIndex,
} from './workspaceTabs'

describe('workspaceTabs', () => {
  it('keeps keyboard navigation inside the open tab range', () => {
    expect(resolveTabNavigationIndex('ArrowLeft', 0, 3)).toBe(0)
    expect(resolveTabNavigationIndex('ArrowRight', 2, 3)).toBe(2)
    expect(resolveTabNavigationIndex('Home', 2, 3)).toBe(0)
    expect(resolveTabNavigationIndex('End', 0, 3)).toBe(2)
    expect(resolveTabNavigationIndex('Enter', 1, 3)).toBeNull()
  })

  it('treats case-only table differences as the same open tab', () => {
    expect(normalizedTableIdentity('source-1', 'Project_A', 'Fact_Order'))
      .toBe(normalizedTableIdentity('SOURCE-1', 'project_a', 'fact_order'))
  })
})
