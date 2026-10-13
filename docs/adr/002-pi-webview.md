# ADR-002：Pi/JS 运行在可信本地 WebView

日期：2026-10-13。状态：条件性采纳。

## 背景

用户要求 Pi 负责 Agent 和模型适配，需要兼顾浏览器库与 Android 原生能力。

## 决定

Kotlin 宿主承载本地 Web UI 与 Pi；使用固定版本接口，预留 Runtime Adapter。

## 备选方案

Compose＋隐藏 WebView 增加同步；其他 JS 引擎缺 Web API；嵌入 Node 增加维护；Native 模型适配不满足边界。

## 后果与限制

需桥、依赖裁剪、生命周期与 CSP 审查；不把 README 浏览器支持当实机通过。

## 验证与重开条件

POC-03/04/10；失败重开选择，见 Q-02/12/13。

依据与规范见 [官方参考](../references.md)、[需求验收](../requirements.md)、[PoC 计划](../poc-plan.md)。任何与已明确用户需求冲突的范围变化必须单独提出，不能由实现便利替代产品决定。
