package com.xiaohe66.meteor.lotus.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.MinecraftDisconnectEvent;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.events.ui.ScreenChangeEvent;
import evernight.client.managers.world.WorldStateManager;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.LineMeshRenderer;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import evernight.client.utils.chat.ChatUtils;
import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.entity.TrialSpawnerBlockEntity;
import net.minecraft.world.level.block.entity.trialspawner.TrialSpawnerState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code R_} (H刷怪笼激活检测) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>检测「曾经被玩家接近过」的刷怪笼，用来找地牢、废弃矿井、要塞、下界要塞、堡垒遗迹、林地府邸
 * 里的玩家藏匿点：
 * <ul>
 *     <li>普通刷怪笼：{@code BaseSpawner#spawnDelay} 不等于 20 即视为激活——刷怪笼被 tick 过，说明
 *     有玩家进过它的 16 格范围；可选「空气扰动」启发式：刷怪笼刷怪范围内同时出现 {@code AIR} 与
 *     {@code CAVE_AIR} 时也判定为激活（有人在这里挖过/放过方块），紫晶洞里的方块会让这个启发式
 *     误报，所以默认跳过紫晶洞附近的判定；</li>
 *     <li>试炼刷怪笼：状态不等于 {@code WAITING_FOR_PLAYERS} 即视为激活；</li>
 *     <li>刷怪笼附近被插上火把等发光方块时，单独记成「停用刷怪笼」并用另一种颜色渲染；</li>
 *     <li>检测到之后给聊天栏提示 + 世界方框 / 刷怪范围框 / 追踪线渲染，可选记录坐标。</li>
 * </ul>
 *
 * <p>移植说明：
 * <ul>
 *     <li>Meteor 的 {@code Render3DEvent} → {@link WorldRenderEvent.Main}。方框用
 *     {@link WorldBoxRenderer#fillBox} / {@link WorldBoxRenderer#outlineBox}；追踪线用
 *     {@link LineMeshRenderer#addTracer}，它从屏幕中心视点连到目标，对应原模块从
 *     {@code RenderUtils.center} 连到刷怪笼中心的 {@code renderer.line(...)}。</li>
 *     <li>Meteor 的 {@code ChunkIterator} 遍历所有加载区块 → {@link WorldStateManager#getBlockEntities()}
 *     （本体 NewChunks 的刷怪笼检测也用它），效果等价且少一层区块遍历。</li>
 *     <li>1.21.11 的 {@code BaseSpawner#spawnDelay} / {@code #nextSpawnData} 是 private，没有公开
 *     getter（本体的 {@code BaseSpawnerAccessor} 属于核心 mixin，插件不应依赖），所以这里用
 *     {@code BlockEntity#saveWithoutMetadata} 读 NBT 里的 {@code Delay} / {@code SpawnData}，
 *     语义与原模块直接读字段一致。</li>
 *     <li>{@code ShapeMode} → 本地 {@link RenderMode}（Fill / Outline / Both，复用本体已有的
 *     {@code addons.lotus.names.values.*} 键）；{@code SettingColor} → {@link ColorSetting} +
 *     {@link Color}。</li>
 *     <li>{@code Module#info/warning/error} → {@link ChatUtils#printChat}：Evernight 没有聊天分级，
 *     三种提示都按普通模块消息输出。</li>
 * </ul>
 *
 * <p>刻意保留的上游行为（不是移植遗漏）：
 * <ul>
 *     <li>「聊天反馈」关闭时普通刷怪笼根本不会进入记录集合（上游 {@code b(...)} 一开头就 return
 *     false），因此既不会渲染也不会告警；试炼刷怪笼不经过这段判断。</li>
 *     <li>1×1 刷怪笼方框在上游把线条色传成了全透明（{@code O}），大概率是上游笔误；这里按设置
 *     语义用「刷怪笼线条颜色」画边框，否则 Lines 模式下一个方框都看不见。</li>
 *     <li>试炼刷怪笼只要状态不是 {@code WAITING_FOR_PLAYERS} 就记录，{@code INACTIVE} / {@code
 *     COOLDOWN} 也会命中，与上游一致。</li>
 * </ul>
 *
 * <p>没移植：Meteor 自绘 GUI（记录表格、清除、前往）与文件持久化，见文件末尾 {@code TODO(port)}。
 */
public final class SpawnerFinderModule extends AddonModule {
    /** 普通刷怪笼的刷怪范围半宽：范围框边长与「附近有没有储物方块」的检查半径都用它。 */
    private static final int NORMAL_RANGE = 16;
    /** 试炼刷怪笼的刷怪范围半宽（上游 {@code 14}）。 */
    private static final int TRIAL_RANGE = 14;
    /** 紫晶洞判定：刷怪笼周围这个半径内出现紫晶洞方块就跳过空气扰动检测。 */
    private static final int AMETHYST_SCAN_RADIUS = 5;
    /** 紫晶洞预检只扫描区块前 20 个 section（上游 {@code Math.min(sections.length, 20)}）。 */
    private static final int AMETHYST_SECTION_LIMIT = 20;
    /** 颜色 alpha 不超过它时视为完全透明（上游 {@code > 5} 才渲染）。 */
    private static final int MIN_VISIBLE_ALPHA = 5;
    private static final float LINE_WIDTH = 1.0F;
    /** {@code BaseSpawner} 里「还没有被 tick 过」的延迟值。 */
    private static final int UNTOUCHED_SPAWN_DELAY = 20;

    /** 紫晶洞标志方块（上游 {@code P}）。 */
    private static final Set<Block> AMETHYST_BLOCKS = Set.of(
            Blocks.AMETHYST_BLOCK,
            Blocks.BUDDING_AMETHYST,
            Blocks.CALCITE,
            Blocks.SMOOTH_BASALT,
            Blocks.AMETHYST_CLUSTER,
            Blocks.LARGE_AMETHYST_BUD,
            Blocks.MEDIUM_AMETHYST_BUD,
            Blocks.SMALL_AMETHYST_BUD);

    /** 会让刷怪笼停用的发光方块（上游 {@code Q}）。 */
    private static final Set<Block> LIGHT_BLOCKS = Set.of(
            Blocks.TORCH,
            Blocks.SOUL_TORCH,
            Blocks.REDSTONE_TORCH,
            Blocks.JACK_O_LANTERN,
            Blocks.GLOWSTONE,
            Blocks.SHROOMLIGHT,
            Blocks.OCHRE_FROGLIGHT,
            Blocks.PEARLESCENT_FROGLIGHT,
            Blocks.SEA_LANTERN,
            Blocks.LANTERN,
            Blocks.SOUL_LANTERN,
            Blocks.CAMPFIRE,
            Blocks.SOUL_CAMPFIRE);

    /** 刷怪笼种类 → 生成实体的反查表（上游静态 {@code Map<EntityType<?>, p>}）。 */
    private static final Map<EntityType<?>, SpawnerType> TYPES_BY_ENTITY = new HashMap<>();

    static {
        for (SpawnerType type : SpawnerType.values()) {
            for (EntityType<?> entityType : type.entityTypes) {
                TYPES_BY_ENTITY.put(entityType, type);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // 设置（平铺；顺序大致沿用上游的组顺序：通用 → 结构开关 → 渲染 → 位置记录）
    // ---------------------------------------------------------------------------------------
    private final Setting<Boolean> chatFeedback;
    private final Setting<Boolean> showCoordinates;
    private final Setting<Boolean> hideoutAlert;
    private final Setting<Boolean> reduceHideoutSpam;
    private final Setting<Boolean> airPerturbationDetection;
    private final Setting<Boolean> ignoreAmethystGeode;
    private final Setting<Set<Block>> storageBlocks;
    private final Setting<Boolean> disabledSpawnerDetection;
    private final Setting<Integer> torchScanDistance;

    private final Setting<Boolean> trialSpawnerDetection;
    private final Setting<Boolean> moreStructureToggles;
    private final Setting<Boolean> dungeon;
    private final Setting<Boolean> mineshaft;
    private final Setting<Boolean> bastion;
    private final Setting<Boolean> woodlandMansion;
    private final Setting<Boolean> netherFortress;
    private final Setting<Boolean> stronghold;

    private final Setting<Boolean> reduceRenderSpam;
    private final Setting<Integer> renderDistanceChunks;
    private final Setting<Boolean> removeOutOfRangeCache;
    private final Setting<Boolean> tracerLines;
    private final Setting<Boolean> onlyNearestTracer;
    private final Setting<RenderMode> renderMode;
    private final Setting<Color> spawnerFillColor;
    private final Setting<Color> spawnerLineColor;
    private final Setting<Color> trialSpawnerFillColor;
    private final Setting<Color> trialSpawnerLineColor;
    private final Setting<Color> disabledFillColor;
    private final Setting<Color> disabledLineColor;
    private final Setting<Boolean> rangeRendering;
    private final Setting<Color> rangeFillColor;
    private final Setting<Color> rangeLineColor;
    private final Setting<Color> trialRangeFillColor;
    private final Setting<Color> trialRangeLineColor;

    private final Setting<Boolean> positionLogging;

    // ---------------------------------------------------------------------------------------
    // 运行期状态（原模块的 R/S/T/U/V/W/X）
    // ---------------------------------------------------------------------------------------
    /** 通过空气扰动检测过的刷怪笼（上游 {@code R}），每个刷怪笼只做一次空气/紫晶洞扫描。 */
    private final Set<BlockPos> airCheckedSpawners = new HashSet<>();
    /** 已判定激活的普通刷怪笼（上游 {@code S}）。 */
    private final Set<BlockPos> detectedSpawners = new HashSet<>();
    /** 已判定激活的试炼刷怪笼（上游 {@code T}）。 */
    private final Set<BlockPos> trialSpawners = new HashSet<>();
    /** 附近有发光方块、被玩家停用的刷怪笼（上游 {@code U}，是 {@link #detectedSpawners} 的子集）。 */
    private final Set<BlockPos> disabledSpawners = new HashSet<>();
    /** 刷怪范围内没有储物方块的刷怪笼（上游 {@code V}），开启「减少渲染刷屏」时不画范围框。 */
    private final Set<BlockPos> noStorageSpawners = new HashSet<>();
    /** 追踪线 / 最近刷怪笼要遍历的集合：上游 {@code S ∪ U ∪ T}。 */
    private final List<Set<BlockPos>> trackedGroups = List.of(
            this.detectedSpawners, this.disabledSpawners, this.trialSpawners);
    /** 最近的一个已记录刷怪笼（上游 {@code W}），只在「仅最近刷怪笼追踪线」开启时维护。 */
    private BlockPos nearestSpawner;
    /** 位置记录（上游 {@code X} / {@code Y}）。当前只在内存里，见 {@link #recordPosition}。 */
    private final Set<BlockPos> recordedSpawners = new LinkedHashSet<>();

    /** 本帧的追踪线目标 / 颜色，复用同一个列表避免每帧分配。 */
    private final List<Vec3> tracerTargets = new java.util.ArrayList<>();
    private final List<Color> tracerColors = new java.util.ArrayList<>();

    public SpawnerFinderModule(ModuleCategory category) {
        super("lotus", "LotusSpawnerFinder",
                "Finds spawners that a player has already been near (dungeon, mineshaft and other "
                        + "player hideouts), tracks them and renders them in the world.",
                category);

        // 通用
        this.chatFeedback = this.add(new BooleanSetting("ChatFeedback", true));
        this.showCoordinates = this.add(new BooleanSetting("ShowCoordinates", true));
        this.hideoutAlert = this.add(new BooleanSetting("HideoutAlert", true));
        this.reduceHideoutSpam = this.add(new BooleanSetting("ReduceHideoutSpam", true)
                .dependsOn(this.hideoutAlert));
        this.airPerturbationDetection = this.add(new BooleanSetting("AirPerturbationDetection", false));
        this.ignoreAmethystGeode = this.add(new BooleanSetting("IgnoreAmethystGeode", true)
                .dependsOn(this.airPerturbationDetection));
        this.storageBlocks = this.add(new RegistrySetSetting<>("StorageBlocks",
                BuiltInRegistries.BLOCK,
                Blocks.CHEST, Blocks.BARREL, Blocks.HOPPER, Blocks.DISPENSER));
        this.disabledSpawnerDetection = this.add(new BooleanSetting("DisabledSpawnerDetection", true));
        this.torchScanDistance = this.add(new NumberSetting<>("TorchScanDistance", 1, 1, 10)
                .dependsOn(this.disabledSpawnerDetection));

        // 结构开关
        this.trialSpawnerDetection = this.add(new BooleanSetting("TrialSpawnerDetection", true));
        this.moreStructureToggles = this.add(new BooleanSetting("MoreStructureToggles", false));
        this.dungeon = this.add(new BooleanSetting("Dungeon", true)
                .dependsOn(this.moreStructureToggles));
        this.mineshaft = this.add(new BooleanSetting("Mineshaft", true)
                .dependsOn(this.moreStructureToggles));
        this.bastion = this.add(new BooleanSetting("Bastion", true)
                .dependsOn(this.moreStructureToggles));
        this.woodlandMansion = this.add(new BooleanSetting("WoodlandMansion", true)
                .dependsOn(this.moreStructureToggles));
        this.netherFortress = this.add(new BooleanSetting("NetherFortress", true)
                .dependsOn(this.moreStructureToggles));
        this.stronghold = this.add(new BooleanSetting("Stronghold", true)
                .dependsOn(this.moreStructureToggles));

        // 渲染
        this.reduceRenderSpam = this.add(new BooleanSetting("ReduceRenderSpam", true));
        this.renderDistanceChunks = this.add(new NumberSetting<>("RenderDistanceChunks", 32, 6, 1024));
        this.removeOutOfRangeCache = this.add(new BooleanSetting("RemoveOutOfRangeCache", true));
        this.tracerLines = this.add(new BooleanSetting("TracerLines", true));
        this.onlyNearestTracer = this.add(new BooleanSetting("OnlyNearestTracer", false));
        this.renderMode = this.add(new EnumSetting<>("RenderMode", RenderMode.Both));

        this.spawnerFillColor = this.add(new ColorSetting("SpawnerFillColor", new Color(251, 5, 5, 70)));
        this.spawnerFillColor.addVisibilityPredicate(
                color -> this.renderMode.getValue() != RenderMode.Outline);
        this.spawnerLineColor = this.add(
                new ColorSetting("SpawnerLineColor", new Color(251, 5, 5, 235)));
        this.spawnerLineColor.addVisibilityPredicate(
                color -> this.renderMode.getValue() != RenderMode.Fill || this.tracerLines.getValue());
        this.trialSpawnerFillColor = this.add(
                new ColorSetting("TrialSpawnerFillColor", new Color(255, 100, 0, 70)));
        this.trialSpawnerFillColor.addVisibilityPredicate(
                color -> this.trialSpawnerDetection.getValue()
                        && this.renderMode.getValue() != RenderMode.Outline);
        this.trialSpawnerLineColor = this.add(
                new ColorSetting("TrialSpawnerLineColor", new Color(255, 100, 0, 235)));
        this.trialSpawnerLineColor.addVisibilityPredicate(
                color -> this.trialSpawnerDetection.getValue()
                        && (this.renderMode.getValue() != RenderMode.Fill || this.tracerLines.getValue()));
        this.disabledFillColor = this.add(
                new ColorSetting("DisabledFillColor", new Color(251, 5, 251, 70)));
        this.disabledFillColor.addVisibilityPredicate(
                color -> this.disabledSpawnerDetection.getValue()
                        && this.renderMode.getValue() != RenderMode.Outline);
        this.disabledLineColor = this.add(
                new ColorSetting("DisabledLineColor", new Color(251, 5, 251, 235)));
        this.disabledLineColor.addVisibilityPredicate(
                color -> this.disabledSpawnerDetection.getValue()
                        && this.renderMode.getValue() != RenderMode.Fill);

        this.rangeRendering = this.add(new BooleanSetting("RangeRendering", true));
        this.rangeFillColor = this.add(new ColorSetting("RangeFillColor", new Color(5, 178, 251, 30)));
        this.rangeFillColor.addVisibilityPredicate(
                color -> this.rangeRendering.getValue()
                        && this.renderMode.getValue() != RenderMode.Outline);
        this.rangeLineColor = this.add(new ColorSetting("RangeLineColor", new Color(5, 178, 251, 155)));
        this.rangeLineColor.addVisibilityPredicate(
                color -> this.rangeRendering.getValue()
                        && this.renderMode.getValue() != RenderMode.Fill);
        this.trialRangeFillColor = this.add(
                new ColorSetting("TrialRangeFillColor", new Color(150, 178, 251, 30)));
        this.trialRangeFillColor.addVisibilityPredicate(
                color -> this.trialSpawnerDetection.getValue()
                        && this.rangeRendering.getValue()
                        && this.renderMode.getValue() != RenderMode.Outline);
        this.trialRangeLineColor = this.add(
                new ColorSetting("TrialRangeLineColor", new Color(150, 178, 251, 155)));
        this.trialRangeLineColor.addVisibilityPredicate(
                color -> this.trialSpawnerDetection.getValue()
                        && this.rangeRendering.getValue()
                        && this.renderMode.getValue() != RenderMode.Fill);

        // 位置记录
        this.positionLogging = this.add(new BooleanSetting("PositionLogging", false));
    }

    // ---------------------------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------------------------
    @Override
    public void onEnable() {
        this.clearRuntimeState();

        // TODO(port): 原模块在这里从 spawners.json / spawners.csv 恢复历史坐标（按服务器与维度分
        //  目录），本文件不能改插件入口，所以记录只来自本次游戏会话。
        if (this.positionLogging.getValue() && !this.recordedSpawners.isEmpty()) {
            ChatUtils.printChat(
                    Component.literal(String.format(Locale.ROOT,
                            Translations.get("addons.lotus.messages.spawnerFinderInfo"),
                            this.recordedSpawners.size())),
                    ChatUtils.moduleSignature(this));
        }
    }

    @Override
    public void onDisable() {
        this.clearRuntimeState();
    }

    @Override
    public String getInfo() {
        int tracked = this.detectedSpawners.size() + this.trialSpawners.size();
        if (tracked == 0) {
            return null;
        }
        return String.format(Locale.ROOT, Translations.get("addons.lotus.messages.spawnerFinderInfo"),
                tracked);
    }

    /** 清掉「本次世界/本次连接」的运行期缓存；位置记录不在这里清（原模块 {@code g()} 也是如此）。 */
    private void clearRuntimeState() {
        this.airCheckedSpawners.clear();
        this.detectedSpawners.clear();
        this.trialSpawners.clear();
        this.disabledSpawners.clear();
        this.noStorageSpawners.clear();
        this.nearestSpawner = null;
    }

    // ---------------------------------------------------------------------------------------
    // 扫描（原模块 onPreTick）
    // ---------------------------------------------------------------------------------------
    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null || this.evernightMinecraft.player == null) {
            return;
        }

        List<BlockEntity> blockEntities = ClientContext.worldStateManager.getBlockEntities();
        for (BlockEntity blockEntity : blockEntities) {
            if (blockEntity instanceof SpawnerBlockEntity spawner) {
                this.handleSpawner(level, spawner);
            } else if (blockEntity instanceof TrialSpawnerBlockEntity trialSpawner) {
                this.handleTrialSpawner(trialSpawner);
            }
        }

        if (this.onlyNearestTracer.getValue()) {
            this.nearestSpawner = this.findNearestSpawner();
        }

        if (this.removeOutOfRangeCache.getValue()) {
            this.removeOutOfRangeSpawners(level);
        }
    }

    /**
     * 普通刷怪笼的判定（原模块 {@code a_LevelChunk_SpawnerBlockEntity}）。
     *
     * <p>{@code spawnDelay == 20} 表示刷怪笼还没有被 tick 过，即从来没有玩家进过它的范围；
     * 另外「空气扰动」分支把「刷怪范围里同时有 AIR 与 CAVE_AIR」当作「有人在这里动过方块」的证据。
     */
    private void handleSpawner(ClientLevel level, SpawnerBlockEntity spawner) {
        BlockPos pos = spawner.getBlockPos();
        if (this.isTracked(pos)) {
            return;
        }

        // 见类注释：1.21.11 拿不到 BaseSpawner 的私有字段，改用 NBT（Delay / SpawnData）。
        CompoundTag tag = spawner.saveWithoutMetadata(level.registryAccess());
        int spawnDelay = tag.getShortOr("Delay", (short) UNTOUCHED_SPAWN_DELAY);
        EntityType<?> entityType = readSpawnerEntityType(tag);
        SpawnerType type = entityType == null
                ? SpawnerType.UNKNOWN
                : TYPES_BY_ENTITY.getOrDefault(entityType, SpawnerType.UNKNOWN);

        boolean detected = false;
        if (this.airPerturbationDetection.getValue()
                && (spawnDelay == UNTOUCHED_SPAWN_DELAY || spawnDelay == 0)) {
            if (type.bounds != null
                    && !this.airCheckedSpawners.contains(pos)
                    && !this.hasAmethystGeode(level, pos)
                    && this.hasAirPerturbation(level, pos, type.bounds)) {
                detected = this.reportSpawner(type, entityType, pos);
            }
            this.airCheckedSpawners.add(pos);
        } else if (spawnDelay != UNTOUCHED_SPAWN_DELAY) {
            // 下界里 delay == 0 的刷怪笼是「刚生成、还没 tick 过」的状态，不算玩家接近过。
            if (Level.NETHER.equals(level.dimension()) && spawnDelay == 0) {
                return;
            }
            detected = this.reportSpawner(type, entityType, pos);
        }

        if (detected) {
            this.onSpawnerDetected(level, pos);
        }
    }

    /** 试炼刷怪笼的判定（原模块 {@code a_TrialSpawnerBlockEntity}）。 */
    private void handleTrialSpawner(TrialSpawnerBlockEntity spawner) {
        if (!this.trialSpawnerDetection.getValue()) {
            return;
        }
        BlockPos pos = spawner.getBlockPos();
        if (this.isTracked(pos)) {
            return;
        }
        // 与上游一致：只有 WAITING_FOR_PLAYERS 算「没有激活」，INACTIVE / COOLDOWN 等都会记录。
        if (spawner.getState() == TrialSpawnerState.WAITING_FOR_PLAYERS) {
            return;
        }

        if (this.chatFeedback.getValue()) {
            this.printDetected(Translations.get("addons.lotus.names.types.trialSpawner"), pos);
        }
        this.trialSpawners.add(pos);

        boolean hasStorage = this.hasStorageNear(this.evernightMinecraft.level, pos, TRIAL_RANGE);
        if (!hasStorage && this.reduceRenderSpam.getValue()) {
            this.noStorageSpawners.add(pos);
        }
        this.alertHideout(hasStorage);
        if (this.positionLogging.getValue()) {
            this.recordPosition(pos);
        }
    }

    private boolean isTracked(BlockPos pos) {
        return this.detectedSpawners.contains(pos)
                || this.trialSpawners.contains(pos)
                || this.disabledSpawners.contains(pos)
                || this.noStorageSpawners.contains(pos);
    }

    /** 从刷怪笼 NBT 的 {@code SpawnData.entity.id} 读出要生成的实体类型（上游 {@code a_BaseSpawner}）。 */
    private static EntityType<?> readSpawnerEntityType(CompoundTag tag) {
        String id = tag.getCompoundOrEmpty("SpawnData")
                .getCompoundOrEmpty("entity")
                .getStringOr("id", "");
        if (id.isEmpty()) {
            return null;
        }
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return null;
        }
        return BuiltInRegistries.ENTITY_TYPE.getOptional(identifier).orElse(null);
    }

    /**
     * 报告一个被判定的普通刷怪笼（原模块 {@code b(p, EntityType, BlockPos)}）。
     *
     * @return 是否真的记成「激活」（{@code false} = 结构开关把它过滤掉了）
     */
    private boolean reportSpawner(SpawnerType type, EntityType<?> entityType, BlockPos pos) {
        // 上游行为：聊天反馈关闭时普通刷怪笼不会被记录（见类注释）。
        if (!this.chatFeedback.getValue()) {
            return false;
        }

        String label = this.structureLabel(type, entityType, pos);
        boolean detected = false;
        if (label != null) {
            this.detectedSpawners.add(pos);
            this.printDetected(label.isEmpty() ? SpawnerType.UNKNOWN.label() : label, pos);
            detected = true;
        }

        if (this.positionLogging.getValue()) {
            this.recordPosition(pos);
        }
        return detected;
    }

    /**
     * 结构名（原模块 {@code a_p_EntityType_BlockPos}）。
     *
     * @return {@code null} = 这个刷怪笼不提示，{@code ""} = 用通用「刷怪笼」文案
     */
    private String structureLabel(SpawnerType type, EntityType<?> entityType, BlockPos pos) {
        return switch (type) {
            case DUNGEON -> {
                // 林地府邸的蜘蛛刷怪笼脚下是白桦木板（上游的判据）。
                if (entityType == EntityType.SPIDER
                        && this.evernightMinecraft.level.getBlockState(pos.below())
                        .is(Blocks.BIRCH_PLANKS)
                        && this.woodlandMansion.getValue()) {
                    yield SpawnerType.MANSION.label();
                }
                yield this.dungeon.getValue() ? type.label() : null;
            }
            case MINESHAFT -> this.mineshaft.getValue() ? type.label() : "";
            case STRONGHOLD -> this.stronghold.getValue() ? type.label() : "";
            case FORTRESS -> this.netherFortress.getValue() ? type.label() : "";
            case BASTION -> this.bastion.getValue() ? type.label() : "";
            case UNKNOWN, MANSION -> "";
        };
    }

    private void printDetected(String label, BlockPos pos) {
        String message;
        if (this.showCoordinates.getValue()) {
            message = String.format(Locale.ROOT,
                    Translations.get("addons.lotus.messages.spawnerDetectedAt"),
                    label, pos.toShortString());
        } else {
            message = String.format(Locale.ROOT,
                    Translations.get("addons.lotus.messages.spawnerDetected"), label);
        }
        ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
    }

    /** 检测到之后的附加判定（原模块 {@code b_BlockPos_toVoid}）：停用方块、储物方块、藏匿点提示。 */
    private void onSpawnerDetected(ClientLevel level, BlockPos pos) {
        if (this.disabledSpawnerDetection.getValue()) {
            this.checkLightBlocks(level, pos);
        }

        boolean hasStorage = this.hasStorageNear(level, pos, NORMAL_RANGE);
        if (!hasStorage && this.reduceRenderSpam.getValue()) {
            this.noStorageSpawners.add(pos);
        }
        this.alertHideout(hasStorage);
    }

    /** 藏匿点提示（原模块 {@code a(boolean)}）。 */
    private void alertHideout(boolean hasStorage) {
        if (this.chatFeedback.getValue()
                && this.hideoutAlert.getValue()
                && (hasStorage || !this.reduceHideoutSpam.getValue())) {
            ChatUtils.printChat(
                    Component.literal(Translations.get("addons.lotus.messages.hideoutAlert")),
                    ChatUtils.moduleSignature(this));
        }
    }

    /** 刷怪笼附近有发光方块 → 记为停用刷怪笼（原模块 {@code c_BlockPos_toVoid}）。 */
    private void checkLightBlocks(ClientLevel level, BlockPos pos) {
        int radius = this.torchScanDistance.getValue();
        for (BlockPos current : BlockPos.betweenClosed(
                pos.offset(-radius, -radius, -radius),
                pos.offset(radius, radius, radius))) {
            if (LIGHT_BLOCKS.contains(level.getBlockState(current).getBlock())) {
                this.disabledSpawners.add(pos);
                if (this.chatFeedback.getValue()) {
                    ChatUtils.printChat(
                            Component.literal(Translations.get("addons.lotus.messages.lightBlockNearby")),
                            ChatUtils.moduleSignature(this));
                }
                return;
            }
        }
    }

    /**
     * 刷怪范围内有没有储物方块或箱子矿车（原模块 {@code a_BlockPos_int_toBoolean}）。
     *
     * <p>上游对范围里每个方块都单独做一次实体查询；这里改成对整块 AABB 查一次再比坐标，结果一致但
     * 少了三万多一次实体查询。
     */
    private boolean hasStorageNear(ClientLevel level, BlockPos pos, int radius) {
        Set<Block> blocks = this.storageBlocks.getValue();
        BlockPos min = pos.offset(-radius, -radius, -radius);
        BlockPos max = pos.offset(radius, radius, radius);

        for (BlockPos current : BlockPos.betweenClosed(min, max)) {
            if (blocks.contains(level.getBlockState(current).getBlock())) {
                return true;
            }
        }

        AABB area = new AABB(min.getX(), min.getY(), min.getZ(),
                max.getX() + 1.0, max.getY() + 1.0, max.getZ() + 1.0);
        for (MinecartChest minecart : level.getEntitiesOfClass(MinecartChest.class, area)) {
            BlockPos minecartPos = minecart.blockPosition();
            if (Math.abs(minecartPos.getX() - pos.getX()) <= radius
                    && Math.abs(minecartPos.getY() - pos.getY()) <= radius
                    && Math.abs(minecartPos.getZ() - pos.getZ()) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** 空气扰动：刷怪范围里同时存在 {@code AIR} 与 {@code CAVE_AIR}（原模块 {@code a_BlockPos_j}）。 */
    private boolean hasAirPerturbation(ClientLevel level, BlockPos pos, SpawnerBounds bounds) {
        boolean air = false;
        boolean caveAir = false;
        for (BlockPos current : BlockPos.betweenClosed(
                pos.offset(bounds.minX(), bounds.minY(), bounds.minZ()),
                pos.offset(bounds.maxX(), bounds.maxY(), bounds.maxZ()))) {
            Block block = level.getBlockState(current).getBlock();
            if (block == Blocks.AIR) {
                air = true;
            } else if (block == Blocks.CAVE_AIR) {
                caveAir = true;
            }
            if (air && caveAir) {
                return true;
            }
        }
        return false;
    }

    /**
     * 刷怪笼附近有没有紫晶洞方块（原模块 {@code a_LevelChunk_BlockPos}）。
     *
     * <p>先用区块 section 的 palette（{@link LevelChunkSection#maybeHas}）做粗筛，没有再逐个方块确认。
     *
     * @return {@code true} = 是紫晶洞附近，应该跳过空气扰动检测
     */
    private boolean hasAmethystGeode(ClientLevel level, BlockPos pos) {
        if (!this.ignoreAmethystGeode.getValue()) {
            return false;
        }

        LevelChunkSection[] sections = level.getChunkAt(pos).getSections();
        boolean amethystInChunk = false;
        for (int i = 0; i < Math.min(sections.length, AMETHYST_SECTION_LIMIT); i++) {
            LevelChunkSection section = sections[i];
            if (section != null
                    && !section.hasOnlyAir()
                    && section.maybeHas(state -> AMETHYST_BLOCKS.contains(state.getBlock()))) {
                amethystInChunk = true;
                break;
            }
        }
        if (!amethystInChunk) {
            return false;
        }

        for (BlockPos current : BlockPos.betweenClosed(
                pos.offset(-AMETHYST_SCAN_RADIUS, -AMETHYST_SCAN_RADIUS, -AMETHYST_SCAN_RADIUS),
                pos.offset(AMETHYST_SCAN_RADIUS, AMETHYST_SCAN_RADIUS, AMETHYST_SCAN_RADIUS))) {
            if (AMETHYST_BLOCKS.contains(level.getBlockState(current).getBlock())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 记一个坐标（原模块 {@code e(BlockPos)}）。
     *
     * <p>TODO(port): 原模块同时写进 spawners.json 与 spawners.csv（按服务器与维度分目录），并在
     * 下次启动时恢复。Evernight 插件要落盘需要 {@code AddonContext#dataDirectory()} 或注册一个
     * {@code JsonConfigurable}，属于插件入口文件的工作；本文件不能改入口，所以只保存在内存
     * {@link #recordedSpawners} 里，重启/重连后丢失。
     */
    private void recordPosition(BlockPos pos) {
        this.recordedSpawners.add(pos);
    }

    /** 离玩家最近的一个已记录刷怪笼（原模块 {@code b()}，只比水平距离）。 */
    private BlockPos findNearestSpawner() {
        BlockPos nearest = null;
        double bestDistance = Double.MAX_VALUE;
        int playerX = this.evernightMinecraft.player.getBlockX();
        int playerZ = this.evernightMinecraft.player.getBlockZ();

        for (Set<BlockPos> group : this.trackedGroups) {
            for (BlockPos pos : group) {
                double dx = pos.getX() - playerX;
                double dz = pos.getZ() - playerZ;
                double distance = dx * dx + dz * dz;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest = pos;
                }
            }
        }
        return nearest;
    }

    /** 丢掉所在区块已经卸载的缓存（原模块 {@code a_Set_toVoid}）。 */
    private void removeOutOfRangeSpawners(ClientLevel level) {
        this.airCheckedSpawners.removeIf(pos -> !this.isChunkLoaded(level, pos));
        this.detectedSpawners.removeIf(pos -> !this.isChunkLoaded(level, pos));
        this.disabledSpawners.removeIf(pos -> !this.isChunkLoaded(level, pos));
        this.trialSpawners.removeIf(pos -> !this.isChunkLoaded(level, pos));
        this.noStorageSpawners.removeIf(pos -> !this.isChunkLoaded(level, pos));
    }

    /** 客户端用空区块表示「没加载」，所以这里用 {@code isEmpty()} 判断（原模块比较加载区块集合）。 */
    private boolean isChunkLoaded(ClientLevel level, BlockPos pos) {
        return !level.getChunkAt(pos).isEmpty();
    }

    // ---------------------------------------------------------------------------------------
    // 渲染（原模块 onRender / a(Render3DEvent, Set, ...)）
    // ---------------------------------------------------------------------------------------
    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        if (this.isWorldUnavailable() || this.evernightMinecraft.player == null) {
            return;
        }

        Color normalFill = this.spawnerFillColor.getValue();
        Color normalLine = this.spawnerLineColor.getValue();
        Color rangeFill = this.rangeFillColor.getValue();
        Color rangeLine = this.rangeLineColor.getValue();
        // 上游的提前返回：普通刷怪笼四条颜色都几乎透明时整帧都不画。
        if (alpha(normalFill) <= MIN_VISIBLE_ALPHA
                && alpha(normalLine) <= MIN_VISIBLE_ALPHA
                && alpha(rangeFill) <= MIN_VISIBLE_ALPHA
                && alpha(rangeLine) <= MIN_VISIBLE_ALPHA) {
            return;
        }

        PoseStack poses = event.getPoseStack();
        this.tracerTargets.clear();
        this.tracerColors.clear();

        boolean tracers = this.tracerLines.getValue();
        boolean perBoxTracers = tracers && !this.onlyNearestTracer.getValue();

        this.drawSpawnerSet(poses, this.detectedSpawners, NORMAL_RANGE, true,
                new RenderStyle(normalFill, normalLine, rangeFill, rangeLine),
                perBoxTracers);

        this.drawSpawnerSet(poses, this.trialSpawners, TRIAL_RANGE,
                this.trialSpawnerDetection.getValue(),
                new RenderStyle(this.trialSpawnerFillColor.getValue(),
                        this.trialSpawnerLineColor.getValue(),
                        this.trialRangeFillColor.getValue(),
                        this.trialRangeLineColor.getValue()),
                perBoxTracers);

        // 「仅最近刷怪笼追踪线」：上游还会在最近刷怪笼位置画一个零体积方框（看不见），这里只画线。
        if (tracers && this.onlyNearestTracer.getValue() && this.nearestSpawner != null) {
            this.tracerTargets.add(centerOf(this.nearestSpawner));
            this.tracerColors.add(normalLine);
        }

        if (!this.tracerTargets.isEmpty()) {
            LineMeshRenderer.begin(poses, LINE_WIDTH);
            for (int i = 0; i < this.tracerTargets.size(); i++) {
                LineMeshRenderer.addTracer(this.tracerTargets.get(i), this.tracerColors.get(i));
            }
            LineMeshRenderer.draw();
        }
    }

    /**
     * 画一组刷怪笼（原模块 {@code a(Render3DEvent, Set, int, boolean, SettingColor, ...)}）。
     *
     * @param range         范围框半宽（普通 16、试炼 14）
     * @param rangeAllowed  该类刷怪笼要不要画范围框（试炼刷怪笼还要看它的检测开关）
     */
    private void drawSpawnerSet(PoseStack poses, Set<BlockPos> positions, int range,
            boolean rangeAllowed, RenderStyle style, boolean tracers) {
        for (BlockPos pos : positions) {
            if (!this.isWithinRenderDistance(pos)) {
                continue;
            }

            boolean drawRange = this.rangeRendering.getValue()
                    && rangeAllowed
                    && (!this.reduceRenderSpam.getValue() || !this.noStorageSpawners.contains(pos));
            if (drawRange) {
                this.drawBox(poses,
                        new AABB(
                                pos.getX() - range, pos.getY() - range, pos.getZ() - range,
                                pos.getX() + range + 1.0, pos.getY() + range + 1.0,
                                pos.getZ() + range + 1.0),
                        style.rangeFill(), style.rangeLine());
            }

            boolean disabled = this.disabledSpawners.contains(pos);
            Color fill = disabled ? this.disabledFillColor.getValue() : style.fill();
            Color line = disabled ? this.disabledLineColor.getValue() : style.line();

            if (tracers) {
                this.tracerTargets.add(centerOf(pos));
                this.tracerColors.add(line);
            }
            this.drawBox(poses, new AABB(pos), fill, line);
        }
    }

    /** 按 {@link RenderMode} 画填充 + 边框，对应 Meteor 的 {@code Renderer3D#box(..., ShapeMode)}。 */
    private void drawBox(PoseStack poses, AABB box, Color fill, Color line) {
        RenderMode mode = this.renderMode.getValue();
        if (mode != RenderMode.Outline) {
            WorldBoxRenderer.fillBox(poses, box, fill);
        }
        if (mode != RenderMode.Fill) {
            WorldBoxRenderer.outlineBox(poses, box, line, LINE_WIDTH);
        }
    }

    /** 原模块 {@code d_BlockPos_toBoolean}：只比水平距离，阈值是「渲染距离(区块) × 16」。 */
    private boolean isWithinRenderDistance(BlockPos pos) {
        BlockPos playerPos = new BlockPos(this.evernightMinecraft.player.getBlockX(), pos.getY(),
                this.evernightMinecraft.player.getBlockZ());
        return playerPos.closerThan(pos, this.renderDistanceChunks.getValue() * 16.0);
    }

    private static Vec3 centerOf(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private static int alpha(Color color) {
        return color == null ? 0 : color.getAlpha();
    }

    // ---------------------------------------------------------------------------------------
    // 重新进入世界时清缓存（原模块 onScreenOpen / onGameLeft）
    // ---------------------------------------------------------------------------------------
    @EventHandler
    private void onScreenChange(ScreenChangeEvent.Before event) {
        if (event.getNewScreen() instanceof DisconnectedScreen
                || event.getNewScreen() instanceof LevelLoadingScreen) {
            this.clearRuntimeState();
        }
    }

    @EventHandler
    private void onDisconnect(MinecraftDisconnectEvent event) {
        this.clearRuntimeState();
    }

    // ---------------------------------------------------------------------------------------
    // 内部类型
    // ---------------------------------------------------------------------------------------
    /** 一组刷怪笼的配色（原模块四个 {@code SettingColor} 参数）。 */
    private record RenderStyle(Color fill, Color line, Color rangeFill, Color rangeLine) {
    }

    /**
     * 刷怪笼的生成范围（原模块 {@code j}），坐标相对刷怪笼。
     *
     * <p>这个范围只用于「空气扰动」判断：在刷怪笼实际起作用的范围内找 AIR 与 CAVE_AIR 的混合。
     */
    private record SpawnerBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    }

    /**
     * 刷怪笼种类（原模块 {@code p}）：决定聊天提示里的结构名、空气扰动范围，以及「哪些实体算哪类
     * 刷怪笼」。
     */
    private enum SpawnerType {
        DUNGEON("dungeon", new SpawnerBounds(-2, -1, -2, 1, 2, 1),
                EntityType.ZOMBIE,
                EntityType.ZOMBIE_VILLAGER,
                EntityType.ZOMBIFIED_PIGLIN,
                EntityType.ZOMBIE_HORSE,
                EntityType.ZOMBIE_NAUTILUS,
                EntityType.SKELETON,
                EntityType.WITHER_SKELETON,
                EntityType.SKELETON_HORSE,
                EntityType.SPIDER),
        MINESHAFT("mineshaft", new SpawnerBounds(-1, 0, -1, 1, 1, 1), EntityType.CAVE_SPIDER),
        STRONGHOLD("stronghold", new SpawnerBounds(-3, -2, -3, 3, 3, 3), EntityType.SILVERFISH),
        FORTRESS("netherFortress", null, EntityType.BLAZE),
        BASTION("bastion", null, EntityType.MAGMA_CUBE),
        /** 只在林地府邸里出现的蜘蛛刷怪笼（上游按「脚下是白桦木板」动态判定，不参与实体反查）。 */
        MANSION("woodlandMansion", null),
        UNKNOWN("spawner", null);

        private final String langKey;
        private final SpawnerBounds bounds;
        private final Set<EntityType<?>> entityTypes;

        SpawnerType(String langKey, SpawnerBounds bounds, EntityType<?>... entityTypes) {
            this.langKey = langKey;
            this.bounds = bounds;
            this.entityTypes = Set.of(entityTypes);
        }

        private String label() {
            return Translations.get("addons.lotus.names.types." + this.langKey);
        }
    }

    /** 方框渲染方式，替换 Meteor 的 {@code ShapeMode}（值名复用已存在的语言键）。 */
    public enum RenderMode implements NamedEntry {
        Fill("Fill"),
        Outline("Outline"),
        Both("Both");

        private final String name;

        RenderMode(String name) {
            this.name = name;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String getDisplayName() {
            return Translations.get("addons.lotus.names.values." + this.name);
        }
    }

    // TODO(port): 原模块还有两块依赖 Meteor 的能力没有移植：
    //  1) 自绘 GUI（getWidget：WTable 记录表 + 「清除已记录的坐标」+「前往」按钮调
    //     PathManagers#moveTo）：插件不能改入口、也不能新增文件，所以这里只用 getInfo() 摘要与聊天栏
    //     输出。「清除记录」需要在入口里注册一个指令（context.registerCommand），「前往」需要接
    //     ClientContext.baritoneBridge（未安装本体是 DisabledBaritoneBridge，得先判 isAvailable()）。
    //  2) 持久化：见 recordPosition 的 TODO。两者都依赖插件入口，本单文件移植故意留空。
}
