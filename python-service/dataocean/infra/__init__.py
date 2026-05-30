"""基础设施中性层

存放被多个业务模块（agent / chart / sandbox 等）共享的运行时工具：
LLM 客户端、输出解析器、取消令牌、时间预算、SSE 传输层。

依赖方向约束：infra 只依赖 core，不依赖任何业务模块（agent/chart/sandbox/rag/...），
从而消除此前「业务模块反向 import agent」造成的双向依赖。
"""
