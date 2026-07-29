import js from '@eslint/js'
import vue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

export default tseslint.config(
    {
        ignores: [
            'dist/**',
            'node_modules/**',
            '*.tsbuildinfo',
            'vite.config.js',
            'vite.config.d.ts',
        ],
    },
    js.configs.recommended,
    ...tseslint.configs.recommended,
    ...vue.configs['flat/essential'],
    {
        files: ['**/*.vue'],
        languageOptions: {
            parserOptions: {
                parser: tseslint.parser,
                extraFileExtensions: ['.vue'],
            },
        },
    },
    {
        files: ['src/**/*.{ts,vue}'],
        ignores: ['src/api/index.ts'],
        rules: {
            // 错误对象只能由 API 拦截器做字段级摘要，业务代码不得直接打印。
            'no-console': 'error',
        },
    },
    {
        rules: {
            // TypeScript 与 Vue 编译器已经负责这些检查，避免浏览器全局变量被误报。
            'no-undef': 'off',
            'no-empty': 'off',
            '@typescript-eslint/no-explicit-any': 'off',
            '@typescript-eslint/no-empty-object-type': 'off',
            '@typescript-eslint/no-unused-vars': 'off',
            'vue/multi-word-component-names': 'off',
            'vue/require-default-prop': 'off',
        },
    },
)
