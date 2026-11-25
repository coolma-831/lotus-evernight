# Lotus · Evernight 移植版

把 Meteor Client 插件 **Lotus**（作者 [xiaohe66](https://github.com/xiaohe66)，版本 `1.19.7-1.21.11`）按
[evernight-addon-template](https://github.com/Evernight-Client/evernight-addon-template) 的规范移植到
[Evernight](https://github.com/Evernight-Client/Evernight) 客户端。

- 目标客户端：Evernight **1.0.3** · Minecraft **1.21.11** · Fabric Loader 0.20.1 · Java 21
- 插件 id：`lotus`
- 构建产物：`build/libs/lotus-evernight-1.19.7.jar`

## 说明

- 本仓库是**社区移植**，不是原作者发布的版本。原插件的类名在发行 jar 中被混淆，这里按行为重写为
  Evernight 的 `AddonModule`，正文里的类名、设置名与语言键都是新的稳定标识。
- 按移植者要求，**`L集结令`（公屏关键字自动回复）功能已移除**，不会出现在本插件中。
- 玩家可见文本全部在 `assets/lotus/lang/{en_us,zh_cn}.json`，键前缀 `addons.lotus.*`。
- 本插件不会修改 Evernight 本体；所有功能通过 `AddonContext` 注册模块。

## 模块状态

| 原模块 | Evernight 模块名 | 状态 |
| --- | --- | --- |
| L自动头盔 | `AutoHelmet` | ✅ 已移植 |
| S自动合成 | `LotusAutoCraft` | 🚧 移植中 |
| A袭击助手 | `LotusRaidHelper` | 🚧 移植中 |
| H螺旋扫图 | `SpiralFlight` | 🚧 移植中 |
| 其余模块 | — | ⏳ 待移植（见下） |

待移植模块清单（原类名）：`aa` M地图画复制、`ab` M自动贴画、`ac` M制图师、`ae` L自动签名、
`ah` V实体列表、`am` L快捷物品操作、`ar` V预览器、`av` V生电辅助、`ax` V存储查找增强、
`R_` H刷怪笼激活检测、`U_` S自动分类、`X_` A自动附魔、`Y_` L自动头盔、`Z_` S自动Kit、
`ay` A刷附魔书、`az` A自动村民交易、`as` A打印机、`af` S自动备货、`al` L国际化。

其中依赖 Litematica / Baritone 的模块、以及带 Meteor 自绘 GUI 的模块工作量较大，会分阶段补齐；
未移植处不会静默删除，代码里以 `// TODO(port): ...` 标注。

## 构建

需要 **JDK 25**（Loom 1.18.4 要求；产物字节码仍是 Java 21）：

```bash
./gradlew build            # Windows: gradlew.bat build
python scripts/check-lang.py
```

产物在 `build/libs/`。首次构建会从 GitHub Release 下载 `libs/evernight-1.0.3.jar`（不提交进仓库）。

## 安装

把 `lotus-evernight-1.19.7.jar` 放进 `.minecraft/mods/`，同时安装 Evernight 1.0.3 与 Fabric Loader 0.20.1。
进入游戏后 `;addons` 应能看到 `lotus`，ClickGUI 的 `Lotus` 分类里有对应模块。

## 版权与授权

- 原插件 Lotus：© xiaohe66，本仓库仅做协议/API 适配。
- Evernight 与模板：GPL-3.0-only，见 `LICENSE`。
- Meteor Client：GPL-3.0，<https://github.com/MeteorDevelopment/meteor-client>。
- 原插件说明里提到「仅供个人学习或自用修改」，**公开发布前请先确认原作者授权**。
