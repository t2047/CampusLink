"""CampusLink 失物招领 Agent（FastAPI 智能体服务）。

本包是"校园帮"失物招领功能的智能体后端：把用户的自然语言消息解析为受约束、
可校验的意图与字段（规则引擎 rules.py 兜底、LLM 解释器 llm.py 增强），并对
候选物品做多模态匹配重排（matching.py 融合文本/图片/跨模态嵌入与规则相似度），
最终通过 FastAPI 暴露给 Web 后端编排层调用。

包内主要模块：
- main.py          —— FastAPI 入口与 HTTP 路由
- config.py        —— 环境变量驱动的配置（Settings）
- rules.py         —— 受限规则引擎（意图识别 / 字段抽取 / 安全上下文）
- llm.py           —— LLM 意图解析器（fail-closed 校验 + 遥测 + 重试）
- matching.py      —— 候选物品匹配排序（规则 + Embedding + 多模态）
- models.py        —— Pydantic 数据模型
- embeddings.py    —— 文本/视觉嵌入相似度（本地降级 + 预训练服务）
- nlu_eval.py / model_eval.py / matching_eval.py —— 三类评估脚本

本文件仅声明包版本，不做任何初始化工作（避免 import 本包时产生副作用）。
"""

__version__ = "0.6.0"
