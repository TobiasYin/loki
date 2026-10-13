# ADR-003：Native 仅实现通用 Fetch 流式传输

日期：2026-10-13。状态：已采纳（需求），凭据引用方案有条件。

## 背景

模型协议快速演进，Native 理解协议将重复适配并破坏分层。

## 决定

Pi 构造/解析模型请求与 SSE；Native 处理 HTTP/TLS/headers/body 字节/取消/背压；凭据仅用受端点约束的通用模板。

## 备选方案

Native 按 provider 解析、全量缓冲转文本、WebView 直接请求均不采用。

## 后果与限制

Fetch 兼容层必须验证 SDK 子集；强制 HTTP 路径；凭据占位需 PoC。

## 验证与重开条件

POC-04，Q-03/08/12；不支持的协议行为先在 JS 层修正。

依据与规范见 [官方参考](../references.md)、[需求验收](../requirements.md)、[PoC 计划](../poc-plan.md)。任何与已明确用户需求冲突的范围变化必须单独提出，不能由实现便利替代产品决定。
