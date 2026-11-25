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

已完成 **9 / 24**（构建、语言表校验均通过；未进游戏实测）：

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
| V实体列表 | `LotusEntityList` | ⏳ |
| L快捷物品操作 | `LotusQuickItemMove` | ⏳ |
| V预览器 | `LotusContainerPreview` | ⏳ |
| V存储查找增强 | `LotusStorageFilter` | ⏳ |
| H刷怪笼激活检测 | `LotusSpawnerFinder` | ⏳ |
| S自动分类 | `LotusAutoSort` | ⏳ |
| S自动Kit | `LotusAutoKit` | ⏳ |
| A刷附魔书 | `LotusEnchantBookFarm` | ⏳ |
| A自动村民交易 | `LotusVillagerTrade` | ⏳ |
| A打印机 | `LotusSchematicPrinter` | ⏳ |
| M制图师 | `LotusMapArtPrinter` | ⏳ |
| S自动备货 | `LotusAutoStock` | ⏳ |
| L国际化 | `LotusI18n` | ⏳ |
| Lotus助手 | `LotusHelper` | ⏳ |
| L集结令 | — | ❌ 按移植者要求移除 |

说明：
- 带 `Lotus` 前缀的模块名是为了避开 Evernight 本体已有的同名模块（`AutoCraft`、`AutoTrade`、`EntityList` 等），
  模块名是配置/预设/指令的稳定键，发布后不要再改。
- ⏳ 中依赖 Litematica / Baritone 的（`as`、`ac`、`af`、`U_`、`Z_`、`ay`、`az`）与需要 Meteor 自绘 GUI 的
  （`R_`、`U_`）工作量最大，会分阶段补齐；代码里未移植处一律以 `// TODO(port): ...` 标注，不静默删功能。


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
