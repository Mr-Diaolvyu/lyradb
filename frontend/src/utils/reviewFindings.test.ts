// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { defineComponent } from 'vue'
import { describe, expect, it } from 'vitest'
import { buildReviewFindingsMessage } from './reviewFindings'

describe('buildReviewFindingsMessage', () => {
    it('把服务端恶意 HTML 与脚本作为纯文本展示', () => {
        const imagePayload = '<img src=x onerror="window.__xss=true">'
        const scriptPayload = '<script>window.__xss=true</script>'
        const wrapper = mount(defineComponent({
            render: () => buildReviewFindingsMessage([
                { ruleId: 'image', severity: 'HIGH', message: imagePayload },
                { ruleId: 'script', severity: 'MEDIUM', message: scriptPayload },
            ]),
        }))

        expect(wrapper.find('img').exists()).toBe(false)
        expect(wrapper.find('script').exists()).toBe(false)
        expect(wrapper.text()).toContain(imagePayload)
        expect(wrapper.text()).toContain(scriptPayload)
        expect(wrapper.html()).toContain('&lt;img')
        expect(wrapper.html()).toContain('&lt;script&gt;')
    })
})
