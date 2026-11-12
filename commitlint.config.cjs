// ---------------------------------------------------------------------------
// ANESOP commitlint 配置（Conventional Commits 1.0.0）
// 与 AGENTS.md「提交、PR 与评审」、docs/branch-protection.md §7 对齐。
// scope 在 AGENTS 的 {m0…m8, web, api, contracts, gateway, db, deploy, docs} 之外
// 增加 repo（仓库级配置/脚本/根文件），避免根配置类提交无 scope 可用。
// ---------------------------------------------------------------------------
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'type-enum': [
      2,
      'always',
      [
        'build',
        'chore',
        'ci',
        'docs',
        'feat',
        'fix',
        'perf',
        'refactor',
        'revert',
        'style',
        'test',
      ],
    ],
    'scope-enum': [
      2,
      'always',
      [
        'm0',
        'm1',
        'm2',
        'm3',
        'm4',
        'm5',
        'm6',
        'm7',
        'm8',
        'web',
        'api',
        'contracts',
        'gateway',
        'db',
        'deploy',
        'docs',
        'repo',
      ],
    ],
    'header-max-length': [2, 'always', 100],
    // 正文/footer 不限行宽：AI trailer（AI-Scope 等）是机器可读的长行，
    // 强制折行会破坏 trailer 解析；header 仍保持 100 字符。
    'body-max-line-length': [0],
    'footer-max-line-length': [0],
    // 中文 subject 不分大小写，关闭该规则避免误报
    'subject-case': [0],
    'subject-empty': [2, 'never'],
    'type-empty': [2, 'never'],
  },
}
