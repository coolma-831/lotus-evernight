# Lotus · Evernight 移植版

把 Meteor Client 插件 **Lotus**（作者 [xiaohe66](https://github.com/xiaohe66)，版本 `1.19.7-1.21.11`）按
[evernight-addon-template](https://github.com/Evernight-Client/evernight-addon-template) 的规范移植到
[Evernight](https://github.com/Evernight-Client/Evernight) 客户端。

- 目标客户端：Evernight **1.0.3** · Minecraft **1.21.11** · Fabric Loader 0.20.1 · Java 21
- 插件 id：`lotus`，插件分类：`Lotus`
- 构建产物：`build/libs/lotus-evernight-1.19.7.jar`
- 下载：<https://github.com/coolma-831/lotus-evernight/releases/tag/v1.19.7>

## 说明

- 本仓库是**社区移植**，不是原作者发布的版本。原插件的类名在发行 jar 中被混淆，这里按行为重写为
  Evernight 的 `AddonModule`，类名、设置名与语言键都是新的稳定标识。
- 按移植者要求，**`L集结令`（公屏关键字自动回复）功能已移除**，不会出现在本插件中。
- 玩家可见文本全部在 `assets/lotus/lang/{en_us,zh_cn}.json`，键前缀 `addons.lotus.*`。
- 插件自带 mixin（`lotus.mixins.json`）：容器界面滚轮、ESP 容器过滤、存储查找器过滤，其余功能都通过 Evernight 公开事件实现。

## 模块状态（22 / 24）

| 原模块 | Evernight 模块名 | 状态 |
| --- | --- | --- |
| L自动头盔 | `AutoHelmet` | ✅ |
| L自动签名 | `LotusAutoSign` | ✅ |
| H螺旋扫图 | `SpiralFlight` | ✅ |
| S自动合成 | `LotusAutoCraft` | ✅ |
| A袭击助手 | `LotusRaidHelper` | ✅ |
| V生电辅助 | `LightLevel` | ✅ |
| M自动贴画 | `LotusAutoPainting` | ✅ |
| M地图画复制 | `LotusMapArtCopy` | ✅ |
| A自动附魔 | `LotusAutoEnchant` | ✅ |
| Lotus助手 | `LotusHelper` | ✅ |
| V存储查找增强 | `LotusStorageFilter` | ✅ 自带 mixin 注入 ESP / StashFinder |
| V实体列表 | `LotusEntityList` | ✅ |
| V预览器 | `LotusContainerPreview` | ✅ |
| H刷怪笼激活检测 | `LotusSpawnerFinder` | ✅（记录持久化待补） |
| A刷附魔书 | `LotusEnchantBookFarm` | ✅（需 Baritone） |
| L快捷物品操作 | `LotusQuickItemMove` | ✅（滚轮靠自带 mixin） |
| S自动Kit | `LotusAutoKit` | ✅（需 Baritone） |
| S自动分类 | `LotusAutoSort` | ✅（需 Baritone） |
| A自动村民交易 | `LotusVillagerTrade` | ✅（需 Baritone） |
| A打印机 | `LotusSchematicPrinter` | ✅（需 Litematica） |
| M制图师 | `LotusMapArtPrinter` | ✅（需 Litematica） |
| S自动备货 | `LotusAutoStock` | ✅（需 Litematica，原版未注册） |
| L国际化 | `LotusI18n` | ⏳ 依赖 Meteor 语言系统，Evernight 无对应机制 |
| L集结令 | — | ❌ 按移植者要求移除 |

说明：
- 带 `Lotus` 前缀的模块名是为了避开 Evernight 本体已有的同名模块（`AutoCraft`、`AutoTrade`、`EntityList`、
  `AutoSign` 等）。模块名是配置/预设/指令的稳定键，发布后不要再改。
- 依赖 Baritone 的模块通过 `ClientContext.baritoneBridge` 工作，未安装 Baritone 时启动会提示并停用。
- 依赖 Litematica 的三个模块：构建时由 `build.gradle` 自动下载 Modrinth 上的 `litematica`/`malilib`
  （仅编译期，`libs/*.jar` 不入库）；运行期只有装了 Litematica 才会通过
  `FabricLoader.isModLoaded("litematica")` 注册这三个模块。
- 所有未移植/降级处都在代码里以 `// TODO(port): ...` 标注，不静默删功能。

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

可选依赖：Baritone（`baritone` 或 `baritone-meteor`）用于需要走位的仓库/村民类模块。

## 已知限制

- 未进游戏实测：构建与语言表校验通过，行为需要实机验收。
- 原插件大量依赖专服「3c/简易仓库」的容器布局与展示框约定，仓库类模块需要按同样布局摆放才生效。
- 插件自带 3 个 mixin：容器滚轮（`QuickItemMoveScreenMixin`）、ESP 容器过滤（`ESPRenderHelperMixin`）、
  存储查找器过滤（`StashFinderModuleMixin`）。它们注入的是 Evernight 本体类，本体大版本升级时需要复核。

## 版权与授权

- 原插件 Lotus：© xiaohe66，本仓库仅做协议/API 适配。
- Evernight 与模板：GPL-3.0-only，见 `LICENSE`。
- Meteor Client：GPL-3.0，<https://github.com/MeteorDevelopment/meteor-client>。
- 原插件说明里提到「仅供个人学习或自用修改」，**公开发布前请先确认原作者授权**。
