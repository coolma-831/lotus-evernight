package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.modules.misc.StashFinder.StashFinderModule;
import evernight.client.modules.render.ESP.ESPModule;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Port of {@code ax} (V存储查找增强) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>原模块给 Meteor 内置的 {@code StorageESP} 与 {@code StashFinder} 加一个「排除遗迹」判断：以一个
 * 容器为中心扫一个小立方体，如果里面的<b>装饰性遗迹方块</b>（默认抛光黑石 / 苔石 / 下界砖 / 打蜡铜块）
 * 达到阈值，而附近的箱子/漏斗又很少，就认为这个容器只是遗迹装饰物，既不给它画连通线，也不把它算成
 * 一个 stash。判定本身只有几十行，难点全在「怎么把结论塞进别人的渲染/统计流程」——原插件为此自带
 * 了两个 mixin：
 * <ul>
 *     <li>{@code MixinStorageESP}：{@code @Redirect} {@code StorageESP#onRender} 里的
 *     {@code Renderer3D.line(...)}，命中时直接跳过这条连线（对应事件 {@code J_}）；</li>
 *     <li>{@code MixinStashFinder}：{@code @Redirect} {@code StashFinder#onChunkData} 里
 *     {@code List#contains} 的返回值，命中时把该方块实体当成「不是容器」（对应事件 {@code K_}）。</li>
 * </ul>
 * 这两个可取消事件（{@code J_} / {@code K_}，都继承 {@code v}）是 Lotus 自己的类，Evernight 里不存在。
 *
 * <p><b>Evernight 1.0.3 的对应物与限制</b>（均用 {@code javap -p} 核对过发行 jar）：
 * <ul>
 *     <li>存储透视 = {@code ESPModule}（{@code ClientContext.getModule(ESPModule.class)}）。它<b>没有
 *     tracers</b>（1.0.3 里搜不到任何 tracer 字段/方法），容器是靠
 *     {@code ESPRenderHelper#shouldRenderBlockEntity(BlockEntity)} 过滤后用 shader 重绘方块模型，
 *     而且这个方法、以及 {@code chestsSetting} 等开关都是 {@code protected}，插件模块（不同包、也不同
 *     类层次）既调不到也覆写不了；1.0.3 也没有任何「即将渲染容器」的事件。所以「不给某个容器画线」
 *     没有公开注入口，只能靠插件自带的 mixin（见文件末尾 TODO）。</li>
 *     <li>存储查找器 = {@code StashFinderModule}。计数逻辑写在私有的
 *     {@code onClientChunkCacheReplaceWithPacketDataEvent} 里，计数条件不可注入：{@code javap} 里
 *     public 成员只有 {@code logSetting} / {@code xaeroSetting} / {@code createStash}，另有
 *     {@code protected} 的 {@code registerStash}。同样只能靠 mixin。另外本体已经提供了「忽略天然结构」的
 *     {@code IgnoreNatural}（地牢 / 下界要塞 / 堡垒遗迹 / 远古城市 / 试炼密室），它按结构模板判断，比原模块
 *     的「附近装饰方块多不多」更准，多数情况下用本体开关就够了。</li>
 * </ul>
 *
 * <p><b>因此本文件现在的行为</b>：完整移植判定条件（设置、默认值、半径/阈值语义、5 秒位置缓存都与
 * 原模块一致），并把它暴露成两个静态调用点 {@link #shouldHideFromEsp(BlockEntity)} /
 * {@link #shouldHideFromStashFinder(BlockEntity)}，等将来插件加自己的 mixin 时直接调用；在没有注入点
 * 之前，模块定时（每 20 tick）对已加载的容器跑一遍判定，把「命中遗迹过滤的容器数 / 扫描到的容器数」
 * 显示在模块信息里。这只是诊断用的占位反馈，不代表容器真的被隐藏了——真正的隐藏见 TODO。
 */
public final class StorageFilterModule extends AddonModule {
    /**
     * 扫描间隔（tick）。原模块是「渲染到哪个容器就现算哪个」，Evernight 没有渲染钩子，改成定时扫描
     * 已加载的容器；判定结果另有 5 秒缓存，所以这个间隔只影响信息栏的刷新频率。
     */
    private static final int SCAN_INTERVAL_TICKS = 20;
    /** 与原模块 Guava {@code Cache} 的 {@code expireAfterWrite(5s)} / {@code expireAfterAccess(5s)} 对齐。 */
    private static final long CACHE_EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(5L);
    /** 与原模块 {@code maximumSize(1024)} 对齐。 */
    private static final int CACHE_CAPACITY = 1024;

    /**
     * 当前启用的模块实例，供将来插件自带的 mixin 静态调用（见 {@link #shouldHideFromEsp(BlockEntity)}）。
     * 原模块是被 Meteor 的 {@code EVENT_BUS.post(...)} 直接调用的，Evernight 没有等价物。
     */
    private static StorageFilterModule active;

    private final Setting<Boolean> storageEspSetting;
    private final Setting<Boolean> stashFinderSetting;
    private final Setting<Set<Block>> excludeBlocksSetting;
    private final Setting<Integer> excludeRangeSetting;
    private final Setting<Integer> excludeThresholdSetting;
    private final Setting<Integer> forceContainerCountSetting;

    /** 位置（{@link BlockPos#asLong()}）→ 判定结果；LRU + 5 秒过期，等价原模块的静态 Guava Cache。 */
    private final Map<Long, CachedVerdict> cache = new LinkedHashMap<>(256, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CachedVerdict> eldest) {
            return this.size() > CACHE_CAPACITY;
        }
    };

    /** 复用同一个可变坐标，避免判定时给每个方块 new 一个 BlockPos。 */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private int tickCounter;
    private int scannedCount;
    private int filteredCount;

    public StorageFilterModule(ModuleCategory category) {
        super("lotus", "LotusStorageFilter",
                "Finds containers that are only ruin decoration, so storage ESP and the stash finder can ignore them.",
                category);

        this.storageEspSetting = this.add(new BooleanSetting("StorageEsp", true));
        this.stashFinderSetting = this.add(new BooleanSetting("StashFinder", true));
        this.excludeBlocksSetting = this.add(new RegistrySetSetting<Block>("ExcludeBlocks",
                BuiltInRegistries.BLOCK,
                Blocks.WAXED_COPPER_BLOCK,
                Blocks.WAXED_OXIDIZED_COPPER,
                Blocks.MOSSY_COBBLESTONE,
                Blocks.MOSSY_STONE_BRICKS,
                Blocks.NETHER_BRICKS,
                Blocks.BLACKSTONE,
                Blocks.CRACKED_POLISHED_BLACKSTONE_BRICKS));
        this.excludeRangeSetting = this.add(new NumberSetting<>("ExcludeRange", 3, 1, 5));
        this.excludeThresholdSetting = this.add(new NumberSetting<>("ExcludeThreshold", 5, 1, 10));
        this.forceContainerCountSetting = this.add(new NumberSetting<>("ForceContainerCount", 3, 1, 10));

        // 判定参数一变，旧结论全部作废（原模块也会因为 Guava Cache 的 5 秒过期自然失效）。
        this.excludeBlocksSetting.setChangeCallback(this.cache::clear);
        this.excludeRangeSetting.setChangeCallback(this.cache::clear);
        this.excludeThresholdSetting.setChangeCallback(this.cache::clear);
        this.forceContainerCountSetting.setChangeCallback(this.cache::clear);
    }

    @Override
    public void onEnable() {
        this.cache.clear();
        this.tickCounter = 0;
        this.scannedCount = 0;
        this.filteredCount = 0;
        active = this;
    }

    @Override
    public void onDisable() {
        if (active == this) {
            active = null;
        }
        this.cache.clear();
        this.tickCounter = 0;
        this.scannedCount = 0;
        this.filteredCount = 0;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (!this.storageEspSetting.getValue() && !this.stashFinderSetting.getValue()) {
            return;
        }
        // 目标模块都没开时没必要扫（原模块也是被这两个模块的渲染/统计流程间接驱动的）。
        if (!this.isConsumerActive()) {
            this.scannedCount = 0;
            this.filteredCount = 0;
            return;
        }
        if (++this.tickCounter < SCAN_INTERVAL_TICKS) {
            return;
        }
        this.tickCounter = 0;
        this.rescan();
    }

    /** 模块信息：命中「排除遗迹」的容器数 / 扫描到的容器数（诊断用，见类注释）。 */
    @Override
    public String getInfo() {
        if (!this.isToggled() || this.filteredCount <= 0) {
            return null;
        }
        return String.format(Locale.ROOT, Translations.get("addons.lotus.messages.storageFilterInfo"),
                this.filteredCount, this.scannedCount);
    }

    /**
     * 将来插件自带 mixin 注入 {@code ESPRenderHelper#shouldRenderBlockEntity(BlockEntity)} 时的调用点：
     * 返回 {@code true} 表示「这个容器在遗迹区域里，别画它」。
     *
     * <p>目前没有调用者——单个 Java 文件既不能声明 mixin，也不能改 {@code fabric.mod.json}，见文件末尾
     * TODO。这里先按原模块 {@code J_} 事件的语义（作用于存储透视）保留入口。
     */
    public static boolean shouldHideFromEsp(BlockEntity blockEntity) {
        StorageFilterModule module = active;
        return module != null && module.storageEspSetting.getValue() && module.isExcluded(blockEntity.getBlockPos());
    }

    /**
     * 将来插件自带 mixin 注入 {@code StashFinderModule} 的区块扫描时的调用点：返回 {@code true} 表示
     * 「这个方块实体不算容器」，对应原模块 {@code K_} 事件把 {@code List#contains} 改成 {@code false}。
     */
    public static boolean shouldHideFromStashFinder(BlockEntity blockEntity) {
        StorageFilterModule module = active;
        return module != null && module.stashFinderSetting.getValue() && module.isExcluded(blockEntity.getBlockPos());
    }

    /**
     * 供 {@code StashFinderModuleMixin} 调用：按坐标判断该容器是否应在存储查找器里被忽略
     * （对应原模块 {@code K_} 事件）。
     */
    public static boolean shouldHideFromStashFinderAt(BlockPos position) {
        StorageFilterModule module = active;
        return module != null && module.stashFinderSetting.getValue() && module.isExcluded(position);
    }

    /** 目标能力模块（存储透视 / 存储查找器）是否至少开了一个，尽量只走公开 API。 */
    private boolean isConsumerActive() {
        if (this.storageEspSetting.getValue()) {
            ESPModule esp = ClientContext.getModule(ESPModule.class);
            if (esp != null && esp.isToggled()) {
                return true;
            }
        }
        if (this.stashFinderSetting.getValue()) {
            StashFinderModule stashFinder = ClientContext.getModule(StashFinderModule.class);
            if (stashFinder != null && stashFinder.isToggled()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 重扫已加载的容器。用 {@link ClientContext#worldStateManager} 的方块实体表——那正是
     * {@code ESPModule} 渲染容器时遍历的同一份数据；先拷贝一份，避免边遍历边被本体刷新。
     */
    private void rescan() {
        Level level = this.evernightMinecraft.level;
        if (level == null || ClientContext.worldStateManager == null) {
            return;
        }

        List<BlockEntity> snapshot = new ArrayList<>(ClientContext.worldStateManager.getBlockEntities());
        int filtered = 0;
        int total = 0;
        for (BlockEntity blockEntity : snapshot) {
            // 只关心容器类方块实体（对应原模块收到事件的箱子/木桶/熔炉/发射器/潜影盒……）。
            if (!(blockEntity instanceof Container) || blockEntity.getLevel() != level) {
                continue;
            }
            total++;
            if (this.isExcluded(blockEntity.getBlockPos())) {
                filtered++;
            }
        }
        this.scannedCount = total;
        this.filteredCount = filtered;
    }

    /** 带 5 秒缓存的位置判定（原模块 {@code ax#a(BlockEntity)}）。 */
    private boolean isExcluded(BlockPos pos) {
        long key = pos.asLong();
        long now = System.nanoTime();
        CachedVerdict cached = this.cache.get(key);
        if (cached != null && cached.expiresAtNanos > now) {
            return cached.excluded;
        }
        boolean excluded = this.computeExcluded(pos);
        this.cache.put(key, new CachedVerdict(excluded, now + CACHE_EXPIRY_NANOS));
        return excluded;
    }

    /**
     * 原模块 {@code ax#b(BlockEntity)}：以容器为中心，半径 {@code ExcludeRange - 1} 的立方体里统计
     * 「箱子/陷阱箱/漏斗」与「排除方块」的数量；排除方块达到 {@code ExcludeThreshold} 且容器数少于
     * {@code ForceContainerCount} 时判定为遗迹装饰。
     *
     * <p>与原实现唯一的差别：原模块先把排除方块映射成 {@code Item} 再用物品比较（铜块、苔石等都有
     * 对应物品，结果相同；但若玩家把「没有物品的方块」加进列表，映射会得到 {@code Items.AIR} 从而
     * 误伤所有无物品方块），这里直接比较 {@code Block}，语义更准确。
     */
    private boolean computeExcluded(BlockPos center) {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return false;
        }

        int radius = Math.max(0, this.excludeRangeSetting.getValue() - 1);
        Set<Block> excludedBlocks = this.excludeBlocksSetting.getValue();
        int requiredExcluded = this.excludeThresholdSetting.getValue();
        int forcedContainers = this.forceContainerCountSetting.getValue();

        int excludedFound = 0;
        int containersFound = 0;

        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = center.getY() - radius;
        int maxY = center.getY() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    BlockState state = level.getBlockState(this.cursor.set(x, y, z));
                    Item item = state.getBlock().asItem();
                    if (item == Items.CHEST || item == Items.TRAPPED_CHEST || item == Items.HOPPER) {
                        containersFound++;
                    } else if (excludedBlocks.contains(state.getBlock())) {
                        excludedFound++;
                    }
                }
            }
        }

        return containersFound < forcedContainers && excludedFound >= requiredExcluded;
    }

    /** 一个位置的缓存结论。 */
    private static final class CachedVerdict {
        private final boolean excluded;
        private final long expiresAtNanos;

        private CachedVerdict(boolean excluded, long expiresAtNanos) {
            this.excluded = excluded;
            this.expiresAtNanos = expiresAtNanos;
        }
    }

    // 注入说明（已实现）：
    //  1) 存储透视：插件自带的 ESPRenderHelperMixin 在
    //     ESPRenderHelper#shouldRenderBlockEntity(BlockEntity) 的 HEAD 调用 shouldHideFromEsp，
    //     命中时返回 false，容器不再被重绘/描边（对应原 Lotus 的 MixinStorageESP）。
    //  2) 存储查找器：插件自带的 StashFinderModuleMixin 对 StashFinderModule 私有扫描循环里
    //     WorldStateManager#hasKnownPlacement(BlockPos) 做 @Redirect，命中时返回 true，
    //     该容器不再计入箱子数、也不登记潜影盒（对应原 Lotus 的 MixinStashFinder）。
    //  3) getInfo() 的计数仍然保留，作为「判定命中数」的可见反馈；隐藏效果现在会真实生效。
}
