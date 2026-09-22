package com.muxuechat.data.remote

/**
 * 标记：异常的 [Throwable.message] 完全由 App 生成、保证不含任何凭据或上游原文，
 * 因此可以原样展示给用户，不必再经过按 Provider 收敛的兜底文案。
 *
 * 存在的意义是让上层**不需要认识任何厂商专属的异常类型**（AGENT.md §2）：
 * 错误映射只问「这条文案安全吗」，不问「这是不是 xAI 的错误」。
 */
internal interface SafeUserFacingError
