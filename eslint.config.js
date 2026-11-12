// ---------------------------------------------------------------------------
// ANESOP ESLint 9 flat config
// 范围：apps/web/src/**/*.{ts,vue}、packages/*/src/**/*.{ts,vue} 与仓库内脚本/配置。
// 规则基线：ESLint 推荐 + typescript-eslint 推荐（non-type-checked，骨架阶段控制成本）
//          + eslint-plugin-vue 推荐 + eslint-config-prettier（格式化交给 Prettier）。
// ---------------------------------------------------------------------------
import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import vueParser from 'vue-eslint-parser'
import prettier from 'eslint-config-prettier/flat'
import globals from 'globals'

export default [
  {
    // 生成物、依赖与本地缓存一律不检查（含 pnpm 仓库内缓存目录）
    ignores: [
      '**/node_modules/**',
      '**/dist/**',
      '**/coverage/**',
      '**/target/**',
      '**/build/**',
      '.m2repo/**',
      '.toolchain/**',
      '.pnpm-*/**',
      '.npm-cache/**',
      '.playwright/**',
      'apps/web/src/api/generated/**',
      '**/*.min.js',
    ],
  },

  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/recommended'],

  {
    // .vue 用 vue-eslint-parser，<script lang="ts"> 内部再交给 TS parser
    files: ['**/*.vue'],
    languageOptions: {
      parser: vueParser,
      parserOptions: {
        parser: tseslint.parser,
        ecmaVersion: 'latest',
        sourceType: 'module',
        extraFileExtensions: ['.vue'],
      },
    },
  },

  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      globals: {
        ...globals.browser,
      },
    },
    rules: {
      // TS 已做未定义与未使用检查，避免 no-undef 在类型/宏上误报
      'no-undef': 'off',
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': [
        'error',
        {
          argsIgnorePattern: '^_',
          varsIgnorePattern: '^_',
          caughtErrorsIgnorePattern: '^_',
          ignoreRestSiblings: true,
        },
      ],
      // 基础质量规则（no-debugger 阻断，no-console 仅告警）
      'no-debugger': 'error',
      'no-console': 'warn',
      eqeqeq: ['error', 'smart'],
      'prefer-const': 'error',
      'no-var': 'error',
      'object-shorthand': 'warn',
      // 骨架阶段允许 App.vue 等单字组件名；新增页面请用多词命名
      'vue/multi-word-component-names': ['error', { ignores: ['App'] }],
      'vue/component-api-style': ['error', ['script-setup', 'composition']],
      'vue/no-v-html': 'error',
    },
  },

  {
    // 脚本与配置文件运行在 Node
    files: ['**/*.{js,cjs,mjs}', '**/*.config.{js,cjs,mjs,ts}'],
    languageOptions: {
      globals: {
        ...globals.node,
      },
    },
    rules: {
      'no-console': 'off',
    },
  },

  // 必须放最后：关闭所有与 Prettier 冲突的格式规则
  prettier,
]
