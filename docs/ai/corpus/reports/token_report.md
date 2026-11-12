# Token 账单与切块质量

- 语料块：158 个
- 总字符：118598；汉字：55386
- 总 tokens（cl100k_base）：101282
- 平均块：750 字符 / 641 tokens
- 最大块：1706 tokens；最小块：73 tokens
- 章节 md 合计：97103 tokens

> cl100k 为 GPT 系分词器，对中文与表格符号偏保守；DeepSeek/Qwen 处理中文为主的文本时通常比该值低 30%–50%。实际投喂前请用目标模型 tokenizer 复核，本表数字可视为上限。

## 块大小分布

- <300 tokens：34 块
- 300-600 tokens：61 块
- 600-1000 tokens：27 块
- 1000-1500 tokens：26 块
- >1500 tokens：10 块
