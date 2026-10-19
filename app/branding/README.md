# Loki 应用图标

应用显示名为 `Loki`，Android applicationId 为 `com.tobias.loki`。Kotlin namespace 保留 `dev.thor.agent`；Android 组件类名与安装包标识是独立的。

`loki-icon.png` 是通过内置 image_gen 工具生成的原始图标。深绿与金色的 Loki 双角面具呼应名字，双眼呼应 AI 助手的屏幕感知功能。图标没有文字，便于小尺寸显示。

Android 资源包含 mdpi 至 xxxhdpi 的传统图标，以及标准和圆形入口共用的 adaptive icon。自适应前景按比例留白（左右 9.26%、上 11.83%、下 6.69%），给系统裁切和动画留出余量。原图图案的边界中心略偏上，因此增加顶部留白，将图案本身对齐到图标中心；比例定位避免图标尺寸或屏幕密度变化时偏移。

更改 applicationId 后，Android 会将 Loki 视为新应用；旧 `dev.thor.agent` 的数据和授权不会自动迁移。安装后需重新配置并授予所需权限。

生成提示词：

```text
Use case: logo-brand. Asset type: production Android launcher icon for loki, an AI assistant app for chat, voice, screen perception and device control. Primary request: one original polished icon, an abstract friendly intelligent mask whose two sweeping horns subtly suggest Loki from Norse mythology and whose flowing central negative space suggests an L. Two simple confident eyes communicate perception and intelligence. Style: bold minimal geometric silhouette, premium restrained emerald and warm pale gold, dark midnight green full bleed square background. Crisp clean edges, very subtle dimensional shading, no tiny details. Composition: single centered emblem entirely within the middle 60 percent of the square, generous even safe area for Android adaptive icon cropping. Full bleed background all the way to square edges, no rounded outer corners baked in. No text, no letters, no phone mockup, no border, no watermark, no extra symbols. One finished square icon only.
```
