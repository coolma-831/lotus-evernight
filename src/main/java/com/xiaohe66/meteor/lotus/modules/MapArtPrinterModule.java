package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.integrations.baritone.BaritoneBridge;
import evernight.client.managers.player.RotationManager;
import evernight.client.modules.ModuleCategory;
import evernight.client.modules.render.FreeLook.FreeLookModule;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import evernight.client.utils.world.BlockInteractionUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.data.SchematicHolder;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.awt.Color;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code ac} (M制图师 / Map Art Printer) from Lotus (Meteor Client addon, author xiaohe66, GPL-3.0).
 *
 * <p>自动打印地图画（仅地毯）。整套装置是 Lotus 的「制图平台」：
 * <ul>
 *     <li>地图画区块固定 128x128，<b>投影原点必须是左上角</b>，玩家初始化时要站在区块内。</li>
 *     <li>展示框里挂<b>红石</b>的箱子是补给箱（空地图、玻璃板、铁砧、经验瓶），挂<b>红石块</b>的是成品箱，
 *     挂 16 色地毯的箱子是对应颜色的地毯补给点；制图台与铁砧在区块中心 ±8 格、脚边 -2..+1 格内自动找。</li>
 *     <li>打印：沿每条「路径」（每隔 5 行一条、左右往返的踩点线）逐格铺地毯；铺完一块 128x128 后
 *     回到地图中心拿空地图 → 绘制 → 制图台锁图 → （可选）铁砧按投影名改名 → 放进成品箱，
 *     再取下投影、加载下一个投影继续。</li>
 * </ul>
 *
 * <p>移植说明：
 * <ul>
 *     <li>原模块继承 {@code aA_} → {@code aw} → {@code ag}（Lotus 的状态机基类）。这里把用到的部分内联成
 *     本类的私有实现：{@code m} → {@link #state}、{@code n} → {@link #pendingState}、
 *     {@code o} → {@link #returnState}、{@code d()} → {@link #delayTicks}、{@code l()/c(int)} →
 *     {@link #resetDelay()}/{@link #setDelay(int)}、{@code a_be/b_be/c_be/d_be/e_be} →
 *     {@code closeThenGoTo/closeAndGoTo/changeState/scheduleStart/restartLater}、
 *     {@code t()/v()} → 背包/容器搜索游标、{@code k()} 延迟设置保留为 {@code Delay}、
 *     {@code u()} 重启延时保留为 {@code RestartDelay}。</li>
 *     <li>Meteor 自绘 GUI（{@code getWidget()} 里的 {@code WVerticalList}/{@code WTable}/{@code WButton}
 *     两个「打开目录」按钮）整段删除；Evernight 插件不能注入模块界面，剩余能力全靠平铺设置项。</li>
 *     <li>聊天、物品、方块、旋转全部换成 Evernight 工具：{@code InvUtils} → {@link InventoryUtils}、
 *     {@code bp} 的点击序列 → {@link InventoryUtils#clickSlot}、{@code bn} → {@link BlockInteractionUtils}
 *     / {@link ContainerInteractionUtils}、{@code bt} → {@link RotationManager} + {@link PacketSender}、
 *     {@code bu} 的 FreeLook → {@link FreeLookModule}。</li>
 *     <li>走位：上游硬依赖 Baritone（{@code GoalNear} + {@code PathEvent}），这里换成
 *     {@link BaritoneBridge}（{@code pathTo/isPathingOrCalculating/cancelPathing}），见
 *     {@link #walkTo} 与 {@link #tickWalking()}。</li>
 *     <li>Litematica 是可选依赖：类里只有 {@link #litematicaLoaded()} /
 *     {@link #schematicsBaseDirectory()} / {@link #getSchematicWorld()} / {@link #tickLoadSchematic()} /
 *     {@link #loadSchematic} / {@link #resumeSelectedPlacement} / {@link #removeSelectedPlacement} /
 *     {@link #onSchematicPathChanged()} 会碰到 {@code fi.dy.masa.litematica.*}，且每次调用前都先判
 *     {@code isModLoaded("litematica")}；字段里只存 MC 的 {@link Level}，不存 Litematica 类型，避免类加载期
 *     解析失败。</li>
 * </ul>
 *
 * <p>已知差异 / 未移植（代码里同样标了 {@code TODO(port)}）：
 * <ul>
 *     <li>「A打印机」模块（上游 {@code as}）没有移植：{@code Q()/R()/bu.h()/bu.i()} 这四处只是把待修坐标交给
 *     A打印机并开关它，因此本类里的 {@link #enablePrinterHelper()}/{@link #disablePrinterHelper()} 是空实现；
 *     本模块自己的 {@code BREAK} 状态仍会照上游那样拆掉位置错误的方块。</li>
 *     <li>{@code SORT} 状态：上游用「快捷物品操作」模块的 {@code am.a()/am.b()} 发起并轮询排序，
 *     Evernight 版 {@code QuickItemMoveModule} 没有公开这两个入口且本次只允许改本文件，所以排序跳过。</li>
 *     <li>{@code aw} 的「快速停止键」（默认 ESC，按下即关模块）：Evernight 没有设置级键位，未移植。</li>
 *     <li>{@code ItemFrame#getNearestViewDirection()} 在 1.21.11 已移除，用等价的
 *     {@code HangingEntity#getDirection()}。</li>
 *     <li>改名时上游还会把新名字写进 {@code AnvilScreen} 的输入框；1.21.11 里该字段是 private，
 *     插件不改本体类就只能发 {@code ServerboundRenameItemPacket}（服务端照样会改名）。</li>
 *     <li>「渲染地图画区块」在上游构造时就订阅了渲染事件（不受模块开关限制）；Evernight 的模块事件只在
 *     启用期间派发，且插件入口不在本次改动范围内，所以区块边框只在模块开启时绘制。</li>
 *     <li>Baritone 的 {@code allowBreak/allowPlace} 开关与 {@code PathEvent.CANCELED}：桥接层没有暴露，
 *     这里改成每 tick 轮询 {@code isPathingOrCalculating()} + 距离来恢复待执行状态。</li>
 *     <li>{@code GoalNear(pos, range)} 的「停在 range 格内」语义：{@link BaritoneBridge#pathTo} 只表达
 *     {@code GoalBlock}，range 由本类自己按距离判断（{@link #walkRange}）。</li>
 * </ul>
 */
public final class MapArtPrinterModule extends AddonModule {
    /** 地图画边长（上游 {@code w}）。 */
    private static final int MAP_ART_SIZE = 128;
    /** 玩家背包（含快捷栏）的槽位数，对应上游 {@code bp.h()}。 */
    private static final int INVENTORY_SLOT_COUNT = 36;
    /** 上游 {@code aA_#a}：超过这个距离就认为投影不在这附近，直接关模块。 */
    private static final double MAX_WALK_DISTANCE = 1000.0D;
    /** 上游 {@code bt} 用的旋转优先级 6666。 */
    private static final int ROTATION_PRIORITY = 6666;
    /** 上游 {@code ag#g} 的 2000ms：容器打开后等服务器同步内容的最长时间。 */
    private static final long CONTAINER_SYNC_TIMEOUT_MS = 2000L;
    /** 上游 {@code ag#c} 的 1000ms：同一个容器 1 秒内重复「打开」就认为没反应。 */
    private static final long CONTAINER_OPEN_RETRY_MS = 1000L;
    /** 走位停下的宽限 tick 数（替代上游 {@code PathEvent.CANCELED}）。 */
    private static final int WALK_IDLE_TICKS = 20;
    /** 上游 {@code ad} 的边框颜色：{@code ShapeMode.Both} 的填充 + 线条。 */
    private static final Color AREA_FILL_COLOR = new Color(255, 170, 0, 45);
    private static final Color AREA_LINE_COLOR = new Color(255, 170, 0);
    /** 上游 {@code bq.a}：地图画只用地毯。 */
    private static final List<Item> CARPET_ITEMS = List.of(
            Items.WHITE_CARPET,
            Items.ORANGE_CARPET,
            Items.MAGENTA_CARPET,
            Items.LIGHT_BLUE_CARPET,
            Items.YELLOW_CARPET,
            Items.LIME_CARPET,
            Items.PINK_CARPET,
            Items.GRAY_CARPET,
            Items.LIGHT_GRAY_CARPET,
            Items.CYAN_CARPET,
            Items.PURPLE_CARPET,
            Items.BLUE_CARPET,
            Items.BROWN_CARPET,
            Items.GREEN_CARPET,
            Items.RED_CARPET,
            Items.BLACK_CARPET);

    // ------------------------------------------------------------------
    // 设置
    // ------------------------------------------------------------------

    /** 上游 {@code a}：补货时至少多补的数量。 */
    private final Setting<Integer> extraSupplySetting;
    /** 上游 {@code b}：成品地图画按投影名自动改名。 */
    private final Setting<Boolean> renameSetting;
    /** 上游 {@code f}：画完一张后的暂停时间（tick）。 */
    private final Setting<Integer> pauseTicksSetting;
    /** 上游 {@code g}：投影相对 schematics 目录的路径。 */
    private final Setting<String> schematicPathSetting;
    /** 上游 {@code h}：要打印的投影名，逗号分隔。 */
    private final Setting<String> schematicNamesSetting;
    /** 上游 {@code i}：渲染当前地图画区块的边框。 */
    private final Setting<Boolean> renderAreaSetting;
    /** 上游 {@code l}：一次性「初始化」按钮。 */
    private final Setting<Boolean> initializeSetting;
    /** 上游 {@code ag#k}：每个操作之间的等待 tick。 */
    private final Setting<Integer> delaySetting;
    /** 上游 {@code aw#u}：重启延时（秒）。 */
    private final Setting<Integer> restartDelaySetting;

    // ------------------------------------------------------------------
    // 状态机
    // ------------------------------------------------------------------

    /** 上游 {@code aw#m}：当前状态。 */
    private State state = State.NONE;
    /** 上游 {@code aw#n}：走位结束后要回到的状态。 */
    private State pendingState;
    /** 上游 {@code aw#o}：关界面之后要回到的状态。 */
    private State returnState;
    /** 上游 {@code aw#t} 的定时任务：延迟启动的目标状态。 */
    private State pendingStartStep;
    /** 上游 {@code ag#a}：下一个操作前的等待 tick。 */
    private int delayTicks;
    /** 上游 {@code aw#p}：进入 {@code CLOSE_SCREEN} 后要补上的延迟。 */
    private int pendingDelay;
    /** 上游 {@code aw#d_be} 的倒计时（秒 → tick）。 */
    private int startDelayTicks;

    // ------------------------------------------------------------------
    // 走位
    // ------------------------------------------------------------------

    private BlockPos walkTarget;
    private int walkRange;
    private int walkIdleTicks;
    private boolean baritoneWarned;

    // ------------------------------------------------------------------
    // 背包 / 容器搜索
    // ------------------------------------------------------------------

    /** 上游 {@code ag#b}：背包搜索的轮转游标。 */
    private int inventoryCursor;
    /** 上游 {@code ag#c}：容器搜索的轮转游标。 */
    private int containerCursor;
    /** 上游 {@code ag#t()}：最近一次在背包里找到的槽位。 */
    private int lastInventorySlot = -1;
    /** 上游 {@code ag#v()}：最近一次在容器里找到的槽位。 */
    private int lastContainerSlot = -1;
    /** 上游 {@code ag#f}：正在打开（或已打开）的容器位置。 */
    private BlockPos openedPos;
    /** 上游 {@code ag#e}：上次打开容器的时间。 */
    private long lastOpenAt;
    /** 上游 {@code ag#d} 的简化版：是否已经对 {@link #openedPos} 发过右键。 */
    private boolean openAttempted;

    // ------------------------------------------------------------------
    // 平台（初始化结果）
    // ------------------------------------------------------------------

    /** 上游 {@code A}：地图画区块原点（左上角）。 */
    private BlockPos origin;
    /** 上游 {@code B}：区块中心。 */
    private BlockPos mapCenter;
    /** 上游 {@code C}：补给箱。 */
    private BlockPos supplyChestPos;
    /** 上游 {@code D}：成品箱。 */
    private BlockPos outputChestPos;
    /** 上游 {@code E}：制图台。 */
    private BlockPos cartographyTablePos;
    /** 上游 {@code F}：铁砧。 */
    private BlockPos anvilPos;
    /** 上游 {@code H}：地毯物品 → 该颜色补给箱位置。 */
    private final Map<Item, List<BlockPos>> carpetChests = new HashMap<>();
    /** 上游 {@code I}：打印路径（每条路径是一串踩点位置）。 */
    private final List<List<BlockPos>> paths = new ArrayList<>();
    /** 上游 {@code J}：当前行还缺的地毯 → 数量。 */
    private final Map<Item, Integer> requiredCarpets = new HashMap<>();
    /** 上游 {@code K}：本次需要去补货的地毯 → 数量。 */
    private final Map<Item, Integer> neededCarpets = new HashMap<>();
    /** 上游 {@code O}：位置错误、需要拆掉的方块。 */
    private final List<BlockPos> wrongBlocks = new ArrayList<>();
    /** 上游 {@code G}：当前投影名（也是成品地图的自定义名）。 */
    private String schematicName = "";
    /** 上游 {@code R}：Litematica 的投影世界（用 MC 的 {@link Level} 存，避免类加载期解析 Litematica 类型）。 */
    private Level schematicWorld;

    // ------------------------------------------------------------------
    // 打印进度
    // ------------------------------------------------------------------

    /** 上游 {@code S}：第几条路径 / 第几行。 */
    private int rowIndex;
    /** 上游 {@code T}：路径内的第几个踩点。 */
    private int positionIndex;
    /** 上游 {@code U}：当前踩点。 */
    private BlockPos currentPos;
    /** 上游 {@code P}：当前要拆的方块。 */
    private BlockPos breakTarget;
    /** 上游 {@code N}：当前要去补货的补给点。 */
    private BlockPos restockPos;
    /** 上游 {@code L}：当前要补的地毯。 */
    private Item restockItem;
    /** 上游 {@code M}：还需要补多少个。 */
    private int restockCount;
    /** 排序不可用的提示只打一次。 */
    private boolean sortUnavailableWarned;

    public MapArtPrinterModule(ModuleCategory category) {
        super("lotus", "LotusMapArtPrinter",
                "Prints map art (carpets only) automatically and works with the Lotus cartography platform. "
                        + "The schematic origin must be the top-left corner; the supply chests must provide "
                        + "empty maps, glass panes, anvils and bottles o' enchanting.",
                category);
        this.extraSupplySetting = this.add(new NumberSetting<>("ExtraSupply", 32, 0, 64));
        this.renameSetting = this.add(new BooleanSetting("Rename", true));
        this.pauseTicksSetting = this.add(new NumberSetting<>("PauseTicks", 200, 0, 2000));
        this.schematicPathSetting = this.add(new StringSetting("SchematicPath", "lotus/map"));
        this.schematicPathSetting.setChangeCallback(this::onSchematicPathChanged);
        this.schematicNamesSetting = this.add(new StringSetting("SchematicNames", ""));
        this.renderAreaSetting = this.add(new BooleanSetting("RenderArea", false));
        this.initializeSetting = this.add(new BooleanSetting("Initialize", false));
        this.initializeSetting.setChangeCallback(this::onInitializeSettingChanged);
        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
        this.restartDelaySetting = this.add(new NumberSetting<>("RestartDelay", 2, 1, 200));
    }

    /**
     * 上游把 Litematica 当硬依赖；Evernight 里它是可选依赖，所以所有调用点都要先问这里。
     *
     * <p>本方法（以及类初始化）绝不能引用 {@code fi.dy.masa.litematica.*}，否则没装 Litematica 的客户端
     * 在注册模块时就会 {@code NoClassDefFoundError}。
     */
    public static boolean litematicaLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    // ------------------------------------------------------------------
    // 生命周期（上游 ac#onActivate / onDeactivate）
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        if (this.paths.isEmpty()) {
            this.warn("addons.lotus.messages.autoInitialize");
            this.initializePlatform();
            if (this.paths.isEmpty()) {
                this.warn("addons.lotus.messages.autoInitializeFailed");
                this.disable();
                return;
            }
        }
        this.enableFreeLook();
        this.warn("addons.lotus.messages.delayedStart");
        this.scheduleStart(State.LOAD_SCHEMATIC);
        // TODO(port): 上游 aA_#onActivate 还会把 Baritone 的 allowBreak/allowPlace 设为 false，
        //  BaritoneBridge 没有暴露这两个设置，未做等价处理。
    }

    @Override
    public void onDisable() {
        this.state = State.NONE;
        this.pendingState = null;
        this.returnState = null;
        this.pendingStartStep = null;
        this.startDelayTicks = 0;
        this.delayTicks = 0;
        this.pendingDelay = 0;
        this.resetRuntime();
        this.disableFreeLook();
        this.disablePrinterHelper();
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge != null && bridge.isAvailable()) {
            bridge.cancelPathing();
        }
        // TODO(port): 上游 aA_#onDeactivate 还会把 Baritone 的 allowBreak/allowPlace 恢复成 true，
        //  BaritoneBridge 没有对应开关。
    }

    /** 上游 {@code bu#f()}：开 FreeLook（相机模式）方便观察打印过程。 */
    private void enableFreeLook() {
        FreeLookModule freeLook = ClientContext.getModule(FreeLookModule.class);
        if (freeLook == null || freeLook.isToggled()) {
            return;
        }
        // TODO(port): 上游还会先把 mode 设成 Camera；Evernight 1.0.3 的 FreeLookModule 只有相机距离设置、
        //  没有模式设置，因此只做开关。
        freeLook.enable();
    }

    /** 上游 {@code bu#g()}：关 FreeLook。 */
    private void disableFreeLook() {
        FreeLookModule freeLook = ClientContext.getModule(FreeLookModule.class);
        if (freeLook != null && freeLook.isToggled()) {
            freeLook.disable();
        }
    }

    // ------------------------------------------------------------------
    // tick 主循环（上游 aw#onTick）
    // ------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (this.startDelayTicks > 0) {
            if (--this.startDelayTicks == 0) {
                this.warn("addons.lotus.messages.started");
                this.state = this.pendingStartStep == null ? State.NONE : this.pendingStartStep;
                this.pendingStartStep = null;
            }
            return;
        }
        this.tickWalking();
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }
        try {
            switch (this.state) {
                case NONE -> {
                    // 上游 bf.a 的占位状态，什么都不做。
                }
                case WALKING -> {
                    // 由 tickWalking 负责恢复。
                }
                case CLOSE_SCREEN -> this.tickCloseScreen();
                case LOAD_SCHEMATIC -> this.tickLoadSchematic();
                case READY -> this.startPrinting();
                case CHECK_BACKPACK -> this.tickCheckBackpack();
                case TAKE_ITEM -> this.tickTakeItem();
                case NEXT -> this.tickNext();
                case BREAK -> this.tickBreak();
                case OPEN_PRINTER -> this.tickOpenPrinter();
                case SUPPLY -> this.tickSupply();
                case DRAW -> this.tickDraw();
                case USE -> this.tickUse();
                case LOCK -> this.tickLock();
                case PUT -> this.tickPut();
                case SORT -> this.tickSort();
                default -> {
                    this.warn("addons.lotus.messages.unknownState", this.state.name());
                    this.disable();
                }
            }
        } catch (Throwable throwable) {
            // 上游 aw#onTick 的兜底：打印堆栈、提示、关模块。
            throwable.printStackTrace();
            this.warn("addons.lotus.messages.unexpectedError", describeThrowable(throwable));
            this.disable();
        }
    }

    /** 上游 {@code aw#y()}：关掉容器界面后接着执行原状态。 */
    private void tickCloseScreen() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
            ContainerInteractionUtils.closeScreen();
        }
        this.openedPos = null;
        this.openAttempted = false;
        State next = this.returnState;
        this.returnState = null;
        this.state = next == null ? State.NONE : next;
        if (this.pendingDelay > 0) {
            this.setDelay(this.pendingDelay);
            this.pendingDelay = 0;
        } else {
            this.resetDelay();
        }
    }

    // ------------------------------------------------------------------
    // 载入投影（上游 ac#M / N）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#M()}：沿用已选中的投影，或者加载列表里的下一个投影。 */
    private void tickLoadSchematic() {
        String names = this.schematicNamesSetting.getValue();
        if (names == null || names.isBlank()) {
            this.warn("addons.lotus.messages.mapArtAllDone");
            this.disable();
            return;
        }
        if (!litematicaLoaded()) {
            this.warn("addons.lotus.messages.mapArtLitematicaMissing");
            this.disable();
            return;
        }
        if (this.origin == null) {
            this.warn("addons.lotus.messages.worldUnavailable");
            this.disable();
            return;
        }
        String[] split = names.split(",");

        if (this.resumeSelectedPlacement(split)) {
            this.info("addons.lotus.messages.mapArtContinue");
            this.startPrinting();
            return;
        }

        String next = split[0];
        if (!this.loadSchematic(next)) {
            this.warn("addons.lotus.messages.mapArtLoadFailed", next);
            // 上游这里不重置延迟，加载失败会每 tick 刷一次；这里补一个延迟，避免刷屏。
            this.resetDelay();
            return;
        }
        this.info("addons.lotus.messages.mapArtLoaded", next);
        this.changeState(State.READY);
    }

    /**
     * 上游 {@code ac#M()} 的前半段：选中的投影已经在待打印列表里就继续打印，否则把它禁用掉。
     *
     * <p>只在 {@link #litematicaLoaded()} 为真时调用。
     */
    private boolean resumeSelectedPlacement(String[] names) {
        SchematicPlacement selected = DataManager.getSchematicPlacementManager().getSelectedSchematicPlacement();
        if (selected == null || !selected.isEnabled()) {
            return false;
        }
        Path file = selected.getSchematicFile();
        if (file != null) {
            for (String name : names) {
                if (name.equals(selected.getName())) {
                    this.schematicName = name;
                    return true;
                }
            }
        }
        selected.setEnabled(false);
        return false;
    }

    /** 上游 {@code ac#M()} 的后半段：从 schematics 目录加载 {@code <path>/<name>.litematic} 并放到原点。 */
    private boolean loadSchematic(String name) {
        Path base = this.schematicsBaseDirectory();
        if (base == null) {
            return false;
        }
        Path file = base.resolve(this.schematicPathSetting.getValue() + File.separator + name + ".litematic");
        LitematicaSchematic schematic = SchematicHolder.getInstance().getOrLoad(file);
        if (schematic == null) {
            return false;
        }
        SchematicPlacement placement = SchematicPlacement.createFor(schematic, this.origin, name, true, true);
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        manager.addSchematicPlacement(placement, false);
        manager.setSelectedSchematicPlacement(placement);
        this.schematicName = name;
        return true;
    }

    /** 上游 {@code ac#N()}：把当前投影关掉、从名字列表里去掉、再从 Litematica 里移除。 */
    private void removeSelectedPlacement() {
        if (!litematicaLoaded()) {
            return;
        }
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        SchematicPlacement selected = manager.getSelectedSchematicPlacement();
        if (selected == null || !selected.isEnabled()) {
            return;
        }
        this.warn("addons.lotus.messages.mapArtRemoved", selected.getName());
        selected.setEnabled(false);
        String removedName = selected.getName();
        String remaining = Stream.of(this.schematicNamesSetting.getValue().split(","))
                .filter(name -> !name.equals(removedName))
                .collect(Collectors.joining(","));
        this.schematicNamesSetting.setValue(remaining);
        manager.removeSchematicPlacement(selected);
    }

    /** 上游 {@code ac#W()}：schematics 根目录；没装 Litematica 时为 {@code null}。 */
    private Path schematicsBaseDirectory() {
        if (!litematicaLoaded()) {
            return null;
        }
        return DataManager.getSchematicsBaseDirectory();
    }

    /** 上游 {@code ac#R} 的投影世界（{@code WorldSchematic} 是 {@link Level} 的子类）。 */
    private Level getSchematicWorld() {
        if (!litematicaLoaded()) {
            return null;
        }
        return SchematicWorldHandler.getSchematicWorld();
    }

    // ------------------------------------------------------------------
    // 打印准备（上游 ac#w / Q）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#w()}：重置进度、取投影世界、开 A打印机、算当前行需要什么。 */
    private void startPrinting() {
        this.resetRuntime();
        this.rowIndex = 0;
        this.positionIndex = 0;
        Level schematic = this.getSchematicWorld();
        if (schematic == null) {
            this.warn("addons.lotus.messages.mapArtSchematicWorldMissing");
            this.disable();
            return;
        }
        this.schematicWorld = schematic;
        this.enablePrinterHelper();
        this.checkMaterials();
    }

    /**
     * 上游 {@code ac#Q()} 里的 {@code R() + bu#h()}：把「这一格该摆什么」的坐标表交给 A打印机并开启它。
     *
     * <p>TODO(port): A打印机（上游 {@code as.java}，「A打印机 / 3c 专用」）没有移植到本插件，
     *  这里没有等价物。本模块自己的 {@code BREAK} 状态仍会拆掉位置错误的方块，只是少了
     *  「打印过程中顺手修复周围方块」的辅助。
     */
    private void enablePrinterHelper() {
    }

    /** 上游 {@code bu#i()}：关掉 A打印机。TODO(port): 同 {@link #enablePrinterHelper()}。 */
    private void disablePrinterHelper() {
    }

    /**
     * 上游 {@code ac#K()}：按「每 5 行一块」统计当前行需要的地毯，够了就去补满背包。
     *
     * <p>行里没有任何需要修改的方块时前进到下一块；全部走完就转去画地图。
     */
    private void checkMaterials() {
        Level level = this.evernightMinecraft.level;
        if (level == null || this.schematicWorld == null || this.origin == null) {
            this.warn("addons.lotus.messages.mapArtSchematicWorldMissing");
            this.disable();
            return;
        }
        int rowsPerChunk = 5;
        int startRow = this.rowIndex;

        while (this.rowIndex < this.paths.size()) {
            this.requiredCarpets.clear();
            int from = rowsPerChunk * this.rowIndex;
            int to = Math.min(from + rowsPerChunk - 1, MAP_ART_SIZE - 1);
            from = Math.max(0, Math.min(from, to - 2));

            for (int z = from; z <= to; z++) {
                for (int x = 0; x < MAP_ART_SIZE; x++) {
                    BlockPos pos = this.origin.offset(x, 0, z);
                    BlockState schematicState = this.schematicWorld.getBlockState(pos);
                    if (schematicState.isAir()) {
                        continue;
                    }
                    BlockState worldState = level.getBlockState(pos);
                    Item needed = schematicState.getBlock().asItem();
                    if (needed == worldState.getBlock().asItem()) {
                        continue;
                    }
                    if (worldState.getBlock() == Blocks.VOID_AIR) {
                        this.warn("addons.lotus.messages.mapArtChunkNotLoaded");
                        this.restartLater(State.NEXT);
                        return;
                    }
                    this.requiredCarpets.merge(needed, 1, Integer::sum);
                }
            }

            if (!this.requiredCarpets.isEmpty()) {
                this.info("addons.lotus.messages.mapArtRowStart", this.rowIndex + 1, this.paths.size());
                this.positionIndex = 0;
                this.tickCheckBackpack();
                return;
            }

            this.rowIndex++;
            if (this.rowIndex >= this.paths.size()) {
                if (startRow == 0) {
                    this.finishCarpets();
                } else {
                    this.rowIndex = 0;
                    this.positionIndex = 0;
                }
                return;
            }
        }

        this.finishCarpets();
    }

    /** 上游 {@code ac#S()}：地毯铺完了，开始画地图。 */
    private void finishCarpets() {
        this.info("addons.lotus.messages.mapArtCarpetDone");
        this.disablePrinterHelper();
        this.state = State.SUPPLY;
    }

    /** 上游 {@code ac#J()}：比较背包里的地毯与当前行所需，决定直接打印还是先去补货。 */
    private void tickCheckBackpack() {
        Map<Item, Integer> available = this.countInventoryCarpets();
        this.neededCarpets.clear();

        for (Map.Entry<Item, Integer> entry : this.requiredCarpets.entrySet()) {
            int required = entry.getValue();
            Integer have = available.get(entry.getKey());
            if (have == null) {
                this.neededCarpets.put(entry.getKey(), required);
            } else if (have < required) {
                this.neededCarpets.put(entry.getKey(), required - have);
            }
        }

        if (this.neededCarpets.isEmpty()) {
            this.enablePrinterHelper();
            this.state = State.NEXT;
        } else {
            this.disablePrinterHelper();
            this.restockItem = null;
            this.state = State.TAKE_ITEM;
        }
    }

    // ------------------------------------------------------------------
    // 补货（上游 ac#I）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#I()}：去最近的地毯补给点把缺的地毯补进背包。 */
    private void tickTakeItem() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        if (this.restockItem == null) {
            if (this.neededCarpets.isEmpty()) {
                this.tickCheckBackpack();
                return;
            }
            if (this.rowIndex >= this.paths.size()) {
                this.tickCheckBackpack();
                return;
            }
            List<BlockPos> row = this.paths.get(this.rowIndex);
            int index = Math.max(0, Math.min(this.positionIndex, row.size() - 1));
            Vec3 cell = row.get(index).getCenter();
            Vec3 playerPosition = player.position();
            double best = Double.MAX_VALUE;
            BlockPos bestPos = null;
            Item bestItem = null;

            for (Map.Entry<Item, Integer> entry : this.neededCarpets.entrySet()) {
                List<BlockPos> chests = this.carpetChests.get(entry.getKey());
                if (chests == null) {
                    continue;
                }
                for (BlockPos chest : chests) {
                    Vec3 center = chest.getCenter();
                    double distance = Math.min(cell.distanceTo(center), playerPosition.distanceTo(center));
                    if (distance < best) {
                        best = distance;
                        bestPos = chest;
                        bestItem = entry.getKey();
                    }
                }
            }

            if (bestPos == null) {
                String missing = this.neededCarpets.keySet().stream()
                        .map(MapArtPrinterModule::itemName)
                        .collect(Collectors.joining(","));
                this.idle("addons.lotus.messages.mapArtNoSupplyPoint", missing);
                return;
            }

            this.restockPos = bestPos;
            this.restockItem = bestItem;
            this.restockCount = this.neededCarpets.remove(bestItem) + this.extraSupplySetting.getValue();
        }

        if (this.distanceTo(this.restockPos) > 3.0D) {
            this.walkTo(this.restockPos, 2, State.TAKE_ITEM,
                    "addons.lotus.messages.mapArtGoRestock", itemName(this.restockItem), this.restockCount);
            return;
        }

        // TODO(port): 上游先确认「快捷物品操作」模块没有在排序（am#b() / am#m()）；
        //  Evernight 版 QuickItemMoveModule 没有公开这两个查询，这里只能假定它空闲。
        this.interactContainer(this.restockPos, menu -> {
            if (InventoryUtils.isInventoryFull()) {
                this.warn("addons.lotus.messages.inventoryFull");
                Item junk = this.pickJunkCarpet(this.countInventoryCarpets());
                if (junk != null && junk != Items.AIR) {
                    ItemStack stack = this.findInventoryStack(candidate -> candidate.getItem() == junk);
                    if (!stack.isEmpty()) {
                        this.info("addons.lotus.messages.mapArtDropUselessCarpet", itemName(junk));
                        this.dropInventorySlot(this.lastInventorySlot);
                        this.resetDelay();
                        return;
                    }
                }
                this.warn("addons.lotus.messages.mapArtCannotClean");
                this.closeAndGoTo(State.SORT);
                return;
            }

            Item wanted = this.restockItem;
            ItemStack stack = this.findContainerStack(candidate -> candidate.getItem() == wanted);
            if (stack.isEmpty()) {
                this.warn("addons.lotus.messages.mapArtCannotRestock", itemName(wanted));
                this.restartLater(State.CHECK_BACKPACK);
                return;
            }
            int count = stack.getCount();
            this.shiftClickMenuSlot(this.lastContainerSlot);
            if (this.restockCount <= count) {
                this.restockItem = null;
                this.closeThenGoTo(State.TAKE_ITEM);
            } else {
                this.restockCount -= count;
            }
            this.resetDelay();
        });
    }

    /** 上游 {@code ac#a_Map_toItem}：挑一种「明显多出来」的地毯丢掉给别的物品腾位置。 */
    private Item pickJunkCarpet(Map<Item, Integer> available) {
        for (Map.Entry<Item, Integer> entry : available.entrySet()) {
            Item item = entry.getKey();
            Integer required = this.requiredCarpets.get(item);
            if (required == null) {
                return item;
            }
            if (entry.getValue() > required + 64) {
                return item;
            }
        }
        return Items.AIR;
    }

    // ------------------------------------------------------------------
    // 铺地毯（上游 ac#x / H / P）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#x()}：沿当前路径踩点铺地毯，必要时后退修复或挪位置。 */
    private void tickNext() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (this.schematicWorld == null || this.origin == null) {
            this.warn("addons.lotus.messages.mapArtSchematicWorldMissing");
            this.disable();
            return;
        }
        if (this.rowIndex >= this.paths.size()) {
            this.checkMaterials();
            return;
        }
        List<BlockPos> row = this.paths.get(this.rowIndex);
        // 上游 x() 里「检测到前面出现错误」会 T--，这里把越界情况夹回 0（上游会直接越界抛异常）。
        if (this.positionIndex < 0) {
            this.positionIndex = 0;
        }
        if (this.positionIndex >= row.size()) {
            this.checkMaterials();
            return;
        }

        while (true) {
            this.currentPos = row.get(this.positionIndex);
            List<BlockPos> targets = this.scanAround(this.currentPos);
            List<BlockPos> placements = new ArrayList<>(targets.size());

            for (BlockPos pos : targets) {
                BlockState schematicState = this.schematicWorld.getBlockState(pos);
                if (schematicState.isAir()) {
                    continue;
                }
                BlockState worldState = level.getBlockState(pos);
                Item needed = schematicState.getBlock().asItem();
                if (needed == worldState.getBlock().asItem()) {
                    continue;
                }
                if (!worldState.isAir() && worldState.getBlock() != Blocks.WATER) {
                    this.wrongBlocks.add(pos);
                } else {
                    placements.add(pos);
                }
            }

            if (!this.wrongBlocks.isEmpty()) {
                // 上游 bu#i() + 直接切到 BREAK（不重置延迟）。
                this.disablePrinterHelper();
                this.state = State.BREAK;
                return;
            }

            if (placements.isEmpty()) {
                this.positionIndex++;
                if (this.positionIndex < row.size()) {
                    continue;
                }
                this.checkMaterials();
                return;
            }

            AABB playerBox = player.getBoundingBox();
            if (!playerBox.intersects(new AABB(this.currentPos))) {
                this.walkTo(this.currentPos, 0, State.NEXT);
                return;
            }

            boolean reachable = true;
            BlockPos backward = null;
            for (BlockPos pos : placements) {
                Item needed = this.schematicWorld.getBlockState(pos).getBlock().asItem();
                if (this.findInventoryStack(stack -> stack.getItem() == needed).isEmpty()) {
                    this.info("addons.lotus.messages.mapArtMissingItem");
                    this.checkMaterials();
                    return;
                }
                if (!playerBox.intersects(new AABB(pos))) {
                    reachable = false;
                    if (this.rowIndex % 2 == 0) {
                        if (pos.getX() < this.currentPos.getX() - 2) {
                            backward = pos;
                        }
                    } else if (pos.getX() > this.currentPos.getX() + 2) {
                        backward = pos;
                    }
                }
            }

            if (!reachable) {
                if (backward != null) {
                    this.info("addons.lotus.messages.mapArtGoBackFix");
                    this.positionIndex--;
                }
                return;
            }

            this.info("addons.lotus.messages.mapArtStuckUnder");
            int[] offsets = this.rowIndex % 2 == 0 ? new int[]{-1, -2, 1, 2} : new int[]{1, 1, -2};
            BlockPos moveTo = null;
            for (int offset : offsets) {
                BlockPos candidate = this.currentPos.offset(offset, 0, 0);
                if (this.schematicWorld.getBlockState(candidate).isAir()) {
                    moveTo = candidate;
                    break;
                }
                if (isCarpetItem(level.getBlockState(candidate).getBlock().asItem())) {
                    moveTo = candidate;
                    break;
                }
            }
            if (moveTo == null) {
                this.warn("addons.lotus.messages.mapArtNoMoveSpot");
                return;
            }
            this.walkTo(moveTo, 0, State.NEXT);
            return;
        }
    }

    /** 上游 {@code ac#a_BlockPos_toList}：当前踩点周围要检查的地毯格（左右往返各一套偏移）。 */
    private List<BlockPos> scanAround(BlockPos pos) {
        int radius = 5;
        List<BlockPos> result = new ArrayList<>((2 + radius + 1) * radius);
        if (this.rowIndex % 2 == 0) {
            int minX = Math.max(-2 - radius, this.origin.getX() - pos.getX());
            for (int x = minX; x <= 1; x++) {
                for (int z = -2; z <= 2; z++) {
                    result.add(pos.offset(x, 0, z));
                }
            }
        } else {
            int maxX = Math.min(2 + radius, this.origin.getX() + MAP_ART_SIZE - 1 - pos.getX());
            for (int x = maxX; x >= -1; x--) {
                for (int z = -2; z <= 2; z++) {
                    result.add(pos.offset(x, 0, z));
                }
            }
        }
        return result;
    }

    /** 上游 {@code ac#H()}：拆掉位置错误的方块（返回 {@code OPEN_PRINTER} 前会一直拆）。 */
    private void tickBreak() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (this.schematicWorld == null) {
            this.warn("addons.lotus.messages.mapArtSchematicWorldMissing");
            this.disable();
            return;
        }

        while (true) {
            if (this.breakTarget == null) {
                if (this.wrongBlocks.isEmpty()) {
                    this.changeState(State.OPEN_PRINTER);
                    return;
                }
                this.breakTarget = this.wrongBlocks.remove(0);
            }

            BlockState schematicState = this.schematicWorld.getBlockState(this.breakTarget);
            BlockState worldState = level.getBlockState(this.breakTarget);
            if (schematicState.getBlock().asItem() != worldState.getBlock().asItem() && !worldState.isAir()) {
                if (player.getEyePosition().distanceTo(this.breakTarget.getCenter()) > 4.1D) {
                    BlockPos anchor = this.currentPos == null ? this.breakTarget : this.currentPos;
                    this.walkTo(new BlockPos(this.breakTarget.getX(), anchor.getY(), anchor.getZ()), 1, State.BREAK);
                    return;
                }
                this.disablePrinterHelper();
                this.breakBlockAt(this.breakTarget);
                return;
            }

            this.breakTarget = null;
        }
    }

    /** 上游 {@code ac#P()}：把待修坐标交给 A打印机，然后继续铺地毯。 */
    private void tickOpenPrinter() {
        this.enablePrinterHelper();
        this.changeState(State.NEXT);
    }

    // ------------------------------------------------------------------
    // 补给（上游 ac#G）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#G()}：空地图、玻璃板、铁砧、经验瓶不足时去补给箱拿。 */
    private void tickSupply() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (this.supplyChestPos == null || this.anvilPos == null) {
            this.warn("addons.lotus.messages.worldUnavailable");
            this.disable();
            return;
        }

        Inventory inventory = player.getInventory();
        boolean hasMap = false;
        boolean hasPane = false;
        boolean hasAnvil = false;
        boolean hasBottle = false;
        for (int slot = 0; slot < INVENTORY_SLOT_COUNT; slot++) {
            Item item = inventory.getItem(slot).getItem();
            if (item == Items.GLASS_PANE) {
                hasPane = true;
            } else if (item == Items.MAP) {
                hasMap = true;
            } else if (item == Items.ANVIL) {
                hasAnvil = true;
            } else if (item == Items.EXPERIENCE_BOTTLE) {
                hasBottle = true;
            }
        }

        boolean needAnvil = !hasAnvil && !(level.getBlockState(this.anvilPos).getBlock() instanceof AnvilBlock);
        boolean needXp = !hasBottle && player.experienceLevel < 1;
        if (hasMap && hasPane && !needAnvil && !needXp) {
            this.changeState(State.DRAW);
            return;
        }

        BlockPos target = this.abovePlayer(this.supplyChestPos);
        if (this.walkNeeded(target, 1.0D)) {
            this.info("addons.lotus.messages.mapArtGoSupply");
            this.walkTo(target, 0, State.SUPPLY);
            return;
        }

        boolean needMap = !hasMap;
        boolean needPane = !hasPane;
        this.interactContainer(this.supplyChestPos, menu -> {
            ItemStack stack = this.findContainerStack(candidate -> {
                Item item = candidate.getItem();
                return (needMap && item == Items.MAP)
                        || (needPane && item == Items.GLASS_PANE)
                        || (needAnvil && item == Items.ANVIL)
                        || (needXp && item == Items.EXPERIENCE_BOTTLE);
            });

            if (stack.isEmpty()) {
                List<String> missing = new ArrayList<>();
                if (needMap) {
                    missing.add(itemName(Items.MAP));
                }
                if (needPane) {
                    missing.add(itemName(Items.GLASS_PANE));
                }
                if (needAnvil) {
                    missing.add(itemName(Items.ANVIL));
                }
                if (needXp) {
                    missing.add(itemName(Items.EXPERIENCE_BOTTLE));
                }
                this.warn("addons.lotus.messages.mapArtCannotSupply", String.join(", ", missing));
                this.restartLater(State.SUPPLY);
                return;
            }

            int emptySlot = this.firstEmptyInventorySlot();
            if (emptySlot < 0) {
                ItemStack carpet = this.findInventoryStack(candidate -> isCarpetItem(candidate.getItem()));
                if (!carpet.isEmpty()) {
                    this.info("addons.lotus.messages.mapArtNoSpaceDropCarpet", itemName(carpet.getItem()));
                    this.dropInventorySlot(this.lastInventorySlot);
                    this.resetDelay();
                    return;
                }
                this.warn("addons.lotus.messages.mapArtNoSpaceNoCarpet");
                this.restartLater(State.SUPPLY);
                return;
            }

            this.info("addons.lotus.messages.mapArtSupplyItem", itemName(stack.getItem()));
            if (stack.getItem() == Items.EXPERIENCE_BOTTLE) {
                this.shiftClickMenuSlot(this.lastContainerSlot);
            } else {
                this.moveOneToInventorySlot(this.lastContainerSlot, this.inventorySlotToMenuSlot(emptySlot));
            }
            this.resetDelay();
        });
    }

    // ------------------------------------------------------------------
    // 画地图（上游 ac#F）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#F()}：到地图中心用空地图画一张。 */
    private void tickDraw() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }

        ItemStack filled = this.findInventoryStack(stack -> stack.getItem() == Items.FILLED_MAP);
        if (!filled.isEmpty()) {
            this.warn("addons.lotus.messages.mapArtMapDrawn");
            this.state = State.LOCK;
            return;
        }

        if (!(player.containerMenu instanceof InventoryMenu)) {
            this.closeThenGoTo(State.DRAW);
            return;
        }

        ItemStack emptyMap = this.findInventoryStack(stack -> stack.getItem() == Items.MAP);
        if (emptyMap.isEmpty()) {
            this.state = State.SUPPLY;
            return;
        }

        if (this.walkNeeded(this.mapCenter, 2.0D)) {
            this.warn("addons.lotus.messages.mapArtGoMapCenter");
            this.walkTo(this.mapCenter, 1, State.DRAW);
            return;
        }

        int slot = this.lastInventorySlot;
        if (slot != player.getInventory().getSelectedSlot()) {
            InventoryUtils.swapIntoSelectedSlot(slot);
            this.resetDelay();
            return;
        }

        this.warn("addons.lotus.messages.mapArtDrawing");
        if (this.evernightMinecraft.gameMode != null) {
            this.evernightMinecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
        }
        this.setDelay(this.pauseTicksSetting.getValue());
    }

    // ------------------------------------------------------------------
    // 锁图（上游 ac#D）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#D()}：在制图台用玻璃板把地图锁上。 */
    private void tickLock() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (this.cartographyTablePos == null) {
            this.warn("addons.lotus.messages.mapArtMissingCartography");
            this.disable();
            return;
        }

        if (level.getBlockState(this.cartographyTablePos).getBlock() != Blocks.CARTOGRAPHY_TABLE) {
            this.warn("addons.lotus.messages.mapArtCartographyWrong");
            this.restartLater(State.LOCK);
            return;
        }

        BlockPos target = this.abovePlayer(this.cartographyTablePos);
        if (this.walkNeeded(target, 1.0D)) {
            this.info("addons.lotus.messages.mapArtGoCartography");
            this.walkTo(target, 0, State.LOCK);
            return;
        }

        if (player.containerMenu instanceof InventoryMenu) {
            this.interactContainer(this.cartographyTablePos, opened -> {
            });
            this.resetDelay();
            return;
        }

        if (!(player.containerMenu instanceof CartographyTableMenu table)) {
            return;
        }

        if (table.getSlot(CartographyTableMenu.MAP_SLOT).getItem().isEmpty()) {
            ItemStack filled = this.findInventoryStack(stack -> stack.getItem() == Items.FILLED_MAP);
            if (filled.isEmpty()) {
                this.warn("addons.lotus.messages.mapArtNoDrawnMap");
                this.closeThenGoTo(State.SUPPLY);
                return;
            }
            MapItemSavedData data = MapItem.getSavedData(filled, level);
            if (data != null && data.locked) {
                this.info("addons.lotus.messages.mapArtAlreadyLocked");
                this.closeThenGoTo(State.USE);
                return;
            }
            this.shiftClickInventorySlot(this.lastInventorySlot);
        } else if (table.getSlot(CartographyTableMenu.ADDITIONAL_SLOT).getItem().isEmpty()) {
            ItemStack pane = this.findInventoryStack(stack -> stack.getItem() == Items.GLASS_PANE);
            if (pane.isEmpty()) {
                this.warn("addons.lotus.messages.mapArtNoGlassPane");
                this.closeThenGoTo(State.SUPPLY);
                return;
            }
            this.shiftClickInventorySlot(this.lastInventorySlot);
        } else if (!table.getSlot(CartographyTableMenu.RESULT_SLOT).getItem().isEmpty()) {
            this.info("addons.lotus.messages.mapArtLockMap");
            this.shiftClickMenuSlot(CartographyTableMenu.RESULT_SLOT);
            this.closeThenGoTo(this.renameSetting.getValue() ? State.USE : State.PUT);
        }
        this.resetDelay();
    }

    // ------------------------------------------------------------------
    // 改名（上游 ac#E / a_ItemStack_toBoolean）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#E()}：在铁砧里把成品地图改成投影名，缺经验就先喝一瓶。 */
    private void tickUse() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (!this.renameSetting.getValue()) {
            this.tickPut();
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof AnvilMenu anvil) {
            if (anvil.getSlot(AnvilMenu.INPUT_SLOT).getItem().isEmpty()) {
                ItemStack stack = this.findContainerStack(this::isUnnamedFilledMap);
                if (stack.isEmpty()) {
                    this.closeThenGoTo(State.PUT);
                    return;
                }
                this.shiftClickInventorySlot(this.lastInventorySlot);
            } else {
                ItemStack result = anvil.getSlot(AnvilMenu.RESULT_SLOT).getItem();
                Component customName = result.getCustomName();
                if (customName != null && this.schematicName.equals(customName.getString())) {
                    this.info("addons.lotus.messages.mapArtRenameDone", this.schematicName);
                    this.shiftClickMenuSlot(AnvilMenu.RESULT_SLOT);
                    this.closeThenGoTo(State.PUT);
                    return;
                }
                this.info("addons.lotus.messages.mapArtRenaming", this.schematicName);
                anvil.setItemName(this.schematicName);
                // TODO(port): 上游还会把名字写进 AnvilScreen 的输入框（1.21.11 里该字段是 private，
                //  插件不改本体类就只能发下面的改名包；服务端照样会改名，界面提示可能短暂不同步）。
                PacketSender.send(new ServerboundRenameItemPacket(this.schematicName));
            }
            this.resetDelay();
            return;
        }

        if (player.experienceLevel < 1) {
            ItemStack bottle = this.findInventoryStack(stack -> stack.getItem() == Items.EXPERIENCE_BOTTLE);
            if (bottle.isEmpty()) {
                this.info("addons.lotus.messages.missingXp");
                this.state = State.SUPPLY;
                return;
            }
            // 上游 bp#a(t()) + Rotations.rotate(yaw, 90, () -> gameMode.useItem(...))。
            InventoryUtils.swapIntoSelectedSlot(this.lastInventorySlot);
            ClientContext.rotationManager.requestRotation(new float[]{player.getYRot(), 90.0F}, ROTATION_PRIORITY);
            ClientContext.rotationManager.executeAction(() -> {
                if (this.evernightMinecraft.gameMode != null) {
                    this.evernightMinecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
                }
            });
            this.setDelay(20);
            return;
        }

        if (this.anvilPos == null) {
            this.warn("addons.lotus.messages.mapArtMissingAnvilTable");
            this.disable();
            return;
        }

        BlockPos target = this.abovePlayer(this.anvilPos);
        if (this.walkNeeded(target, 1.0D)) {
            this.info("addons.lotus.messages.mapArtGoAnvil");
            this.walkTo(target, 0, State.USE);
            return;
        }

        if (!(level.getBlockState(this.anvilPos).getBlock() instanceof AnvilBlock)) {
            ItemStack anvilItem = this.findInventoryStack(stack -> stack.getItem() == Items.ANVIL);
            if (anvilItem.isEmpty()) {
                this.info("addons.lotus.messages.mapArtAnvilBrokenNoAnvil");
                this.state = State.SUPPLY;
                return;
            }
            this.info("addons.lotus.messages.replacingAnvil");
            InventoryUtils.swapIntoSelectedSlot(this.lastInventorySlot);
            BlockInteractionUtils.placeBlock(this.anvilPos, true, InteractionHand.MAIN_HAND);
            this.resetDelay();
            return;
        }

        if (menu instanceof InventoryMenu) {
            this.interactContainer(this.anvilPos, opened -> {
            });
            this.resetDelay();
        }
    }

    /** 上游 {@code ac#a_ItemStack_toBoolean}：还没按投影名改过的成品地图。 */
    private boolean isUnnamedFilledMap(ItemStack stack) {
        if (stack.getItem() != Items.FILLED_MAP) {
            return false;
        }
        Component customName = stack.getCustomName();
        return customName == null || !this.schematicName.equals(customName.getString());
    }

    // ------------------------------------------------------------------
    // 放入成品（上游 ac#C）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#C()}：成品地图画进成品箱；没有成品就取下投影加载下一张。 */
    private void tickPut() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (this.outputChestPos == null) {
            this.warn("addons.lotus.messages.mapArtMissingOutputChest");
            this.disable();
            return;
        }

        ItemStack filled = this.findInventoryStack(stack -> stack.getItem() == Items.FILLED_MAP);
        if (filled.isEmpty()) {
            this.removeSelectedPlacement();
            this.state = State.LOAD_SCHEMATIC;
            this.info("addons.lotus.messages.mapArtWaitWater");
            this.setDelay(this.pauseTicksSetting.getValue());
            return;
        }

        MapItemSavedData data = MapItem.getSavedData(filled, level);
        if (data == null || !data.locked) {
            this.state = State.LOCK;
            return;
        }

        if (this.renameSetting.getValue()) {
            Component customName = filled.getCustomName();
            if (customName == null || !this.schematicName.equals(customName.getString())) {
                this.state = State.USE;
                return;
            }
        }

        BlockPos target = this.abovePlayer(this.outputChestPos);
        if (this.walkNeeded(target, 1.0D)) {
            this.info("addons.lotus.messages.mapArtGoPutFinished");
            this.walkTo(target, 0, State.PUT);
            return;
        }

        this.interactContainer(this.outputChestPos, menu -> {
            this.info("addons.lotus.messages.mapArtPutFinished");
            this.shiftClickInventorySlot(this.lastInventorySlot);
            this.resetDelay();
        });
    }

    // ------------------------------------------------------------------
    // 整理背包（上游 ac#O）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code ac#O()}：开背包界面，把「快捷物品操作」模块的排序跑一遍，跑完关界面回到 {@code TAKE_ITEM}。
     *
     * <p>TODO(port): 上游用 {@code am.a()} 发起排序、{@code am.b()} 轮询是否还在排；Evernight 版
     *  {@code QuickItemMoveModule} 没有公开这两个入口（本次只允许改本文件，不能给它加 API），
     *  所以这里直接跳过排序：提示一次后关界面回到 {@code TAKE_ITEM}，不假装排过序。
     */
    private void tickSort() {
        if (!this.sortUnavailableWarned) {
            this.sortUnavailableWarned = true;
            this.warn("addons.lotus.messages.mapArtSortUnavailable");
        }
        ContainerInteractionUtils.closeScreen();
        this.changeState(State.TAKE_ITEM);
    }

    // ------------------------------------------------------------------
    // 初始化（上游 ac#a_Boolean）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#l} 的回调：把设置当成一次性按钮用。 */
    private void onInitializeSettingChanged() {
        if (!Boolean.TRUE.equals(this.initializeSetting.getValue())) {
            return;
        }
        this.initializeSetting.setValue(false);
        this.initializePlatform();
    }

    /**
     * 上游 {@code ac#a_Boolean(true)}：扫描地图画区块里的展示框，识别补给箱 / 成品箱 / 地毯箱、
     * 制图台、铁砧，并生成打印路径。
     */
    private void initializePlatform() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            this.failInitialize("addons.lotus.messages.worldUnavailable");
            return;
        }

        this.resetRuntime();
        BlockPos playerPos = player.blockPosition();
        int originX = Math.floorDiv(playerPos.getX() + 64, MAP_ART_SIZE) * MAP_ART_SIZE - 64;
        int originZ = Math.floorDiv(playerPos.getZ() + 64, MAP_ART_SIZE) * MAP_ART_SIZE - 64;
        this.origin = new BlockPos(originX, playerPos.getY(), originZ);

        int radius = MAP_ART_SIZE;
        AABB area = new AABB(
                playerPos.getX() - radius, playerPos.getY() - 2, playerPos.getZ() - radius,
                playerPos.getX() + radius, playerPos.getY() + 4, playerPos.getZ() + radius);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, area, frame -> !frame.getItem().isEmpty());
        this.carpetChests.clear();

        for (ItemFrame frame : frames) {
            BlockPos framePos = frame.getPos();
            if (framePos == null) {
                continue;
            }
            // 上游 ItemFrame#getNearestViewDirection()；1.21.11 用等价的 HangingEntity#getDirection()。
            BlockPos chestPos = framePos.relative(frame.getDirection().getOpposite());
            Block block = level.getBlockState(chestPos).getBlock();
            if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) {
                continue;
            }
            Item item = frame.getItem().getItem();
            if (item == Items.REDSTONE) {
                this.supplyChestPos = chestPos;
            } else if (item == Items.REDSTONE_BLOCK) {
                this.outputChestPos = chestPos;
            } else if (isCarpetItem(item)) {
                this.carpetChests.computeIfAbsent(item, key -> new ArrayList<>()).add(chestPos);
            }
        }

        if (this.supplyChestPos == null) {
            this.failInitialize("addons.lotus.messages.mapArtMissingSupplyChest");
            return;
        }
        if (this.outputChestPos == null) {
            this.failInitialize("addons.lotus.messages.mapArtMissingOutputChest");
            return;
        }

        this.mapCenter = this.origin.offset(64, 0, 64);
        this.cartographyTablePos = this.findNearbyBlock(block -> block == Blocks.CARTOGRAPHY_TABLE);
        if (this.cartographyTablePos == null) {
            this.failInitialize("addons.lotus.messages.mapArtMissingCartography");
            this.warn("addons.lotus.messages.mapArtCheckAlignment");
            return;
        }

        this.anvilPos = this.findNearbyBlock(block -> block instanceof AnvilBlock);
        if (this.anvilPos == null) {
            this.failInitialize("addons.lotus.messages.mapArtMissingAnvilTable");
            return;
        }

        if (this.carpetChests.size() < CARPET_ITEMS.size()) {
            List<Item> missing = new ArrayList<>(CARPET_ITEMS);
            missing.removeAll(this.carpetChests.keySet());
            this.failInitialize("addons.lotus.messages.mapArtMissingCarpetChests",
                    missing.stream().map(MapArtPrinterModule::itemName).collect(Collectors.joining(",")));
            return;
        }

        this.buildPaths();
        int supplyPoints = this.carpetChests.values().stream().mapToInt(List::size).sum();
        this.warn("addons.lotus.messages.mapArtInitSuccess", supplyPoints, this.paths.size() + 1);
        if (this.renderAreaSetting.getValue()) {
            this.renderAreaSetting.setValue(false);
            this.warn("addons.lotus.messages.mapArtRenderAutoOff");
        }
    }

    /** 上游 {@code ac#a_String_ObjectArr}：初始化失败提示 + 自动打开区块边框。 */
    private void failInitialize(String key, Object... args) {
        this.warn(key, args);
        if (!this.renderAreaSetting.getValue()) {
            this.renderAreaSetting.setValue(true);
            this.warn("addons.lotus.messages.mapArtRenderAutoOn");
        }
    }

    /** 上游 {@code ac#g(Predicate<Block>)}：以区块中心为准找方块。 */
    private BlockPos findNearbyBlock(Predicate<Block> predicate) {
        Level level = this.evernightMinecraft.level;
        if (level == null || this.mapCenter == null) {
            return null;
        }
        for (int y = -2; y <= 1; y++) {
            for (int x = -8; x < 8; x++) {
                for (int z = -8; z < 8; z++) {
                    BlockPos pos = this.mapCenter.offset(x, y, z);
                    if (predicate.test(level.getBlockState(pos).getBlock())) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 上游 {@code ac#a_Boolean} 里的路径生成：每隔 5 行一条踩点线，左右往返（相邻两条相反方向），
     * 最后补上收尾的两条。
     */
    private void buildPaths() {
        this.paths.clear();
        int stepX = 2;
        int rowSpacing = 5;
        int startX = 2;
        int endX = 126;
        int z = 2;

        while (true) {
            List<BlockPos> forward = new ArrayList<>();
            for (int x = startX; x <= endX; x += stepX) {
                forward.add(this.origin.offset(x, 0, z));
            }
            forward.add(this.origin.offset(127, 0, z));
            this.paths.add(forward);
            z += rowSpacing;

            if (z >= MAP_ART_SIZE) {
                if (z < MAP_ART_SIZE + 2) {
                    List<BlockPos> last = new ArrayList<>();
                    for (int x = endX; x >= 0; x -= stepX) {
                        last.add(this.origin.offset(x, 0, endX));
                    }
                    last.add(this.origin.offset(0, 0, z));
                    this.paths.add(last);
                }
                break;
            }

            List<BlockPos> backward = new ArrayList<>();
            for (int x = endX; x >= 0; x -= stepX) {
                backward.add(this.origin.offset(x, 0, z));
            }
            backward.add(this.origin.offset(0, 0, z));
            this.paths.add(backward);
            z += rowSpacing;

            if (z >= MAP_ART_SIZE) {
                if (z < MAP_ART_SIZE + 2) {
                    List<BlockPos> last = new ArrayList<>();
                    for (int x = startX; x < MAP_ART_SIZE; x += stepX) {
                        last.add(this.origin.offset(x, 0, endX));
                    }
                    last.add(this.origin.offset(127, 0, z));
                    this.paths.add(last);
                }
                break;
            }
        }
    }

    // ------------------------------------------------------------------
    // 渲染（上游 ad）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code ad#onRenderWorld}：在玩家所在高度画当前地图画区块的边框。
     *
     * <p>TODO(port): 上游在构造器里就订阅了渲染事件，模块关闭时也会画；Evernight 的模块事件只在启用期间
     *  派发，且插件入口不在本次改动范围内，所以这里只在模块开启时绘制。
     */
    @EventHandler
    private void onRender(WorldRenderEvent.Main event) {
        if (!Boolean.TRUE.equals(this.renderAreaSetting.getValue())) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.evernightMinecraft.level == null) {
            return;
        }
        BlockPos pos = player.blockPosition();
        int minX = Math.floorDiv(pos.getX() + 64, MAP_ART_SIZE) * MAP_ART_SIZE - 64;
        int minZ = Math.floorDiv(pos.getZ() + 64, MAP_ART_SIZE) * MAP_ART_SIZE - 64;
        int y = pos.getY();
        AABB box = new AABB(minX, y, minZ, minX + MAP_ART_SIZE, y + 2, minZ + MAP_ART_SIZE);
        WorldBoxRenderer.fillBox(event.getPoseStack(), box, AREA_FILL_COLOR);
        WorldBoxRenderer.outlineBox(event.getPoseStack(), box, AREA_LINE_COLOR, 2.0F);
    }

    // ------------------------------------------------------------------
    // 投影目录（上游 ac#b_String_toVoid / W）
    // ------------------------------------------------------------------

    /** 上游 {@code ac#b_String_toVoid}：「投影路径」变化时刷新投影名列表。 */
    private void onSchematicPathChanged() {
        Path base = this.schematicsBaseDirectory();
        if (base == null) {
            return;
        }
        File directory = base.resolve(this.schematicPathSetting.getValue()).toFile();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".litematic"));
        if (files == null) {
            return;
        }
        String names = Stream.of(files)
                .map(File::getName)
                .map(name -> {
                    int dot = name.indexOf('.');
                    return dot > 0 ? name.substring(0, dot) : name;
                })
                .sorted()
                .collect(Collectors.joining(","));
        if (!names.isEmpty()) {
            this.info("addons.lotus.messages.mapArtSchematicUpdated", names);
            this.schematicNamesSetting.setValue(names);
        }
    }

    // ------------------------------------------------------------------
    // 走位（上游 aA_#a / onPathEvent）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code aA_#a(BlockPos, int, be)}：超过 1000 格直接关模块；超过 range 格交给 Baritone 走过去；
     * 否则立刻切到目标状态。
     */
    private void walkTo(BlockPos pos, int range, State nextState) {
        this.walkTo(pos, range, nextState, null);
    }

    /**
     * 上游 {@code aA_#a(BlockPos, int, be, String)}：同上，先打一条「前往……」提示。
     *
     * @param messageKey 语言键；{@code null} 表示不提示
     */
    private void walkTo(BlockPos pos, int range, State nextState, String messageKey, Object... args) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || pos == null) {
            return;
        }
        double distance = player.position().distanceTo(pos.getCenter());
        if (distance > MAX_WALK_DISTANCE) {
            this.warn("addons.lotus.messages.tooFar");
            this.disable();
            return;
        }
        if (distance <= range) {
            this.state = nextState;
            return;
        }
        if (messageKey != null) {
            this.info(messageKey, args);
        }

        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            // TODO(port): 上游硬依赖 Baritone（没装就加载不了）；这里没走位能力就原地尝试。
            if (!this.baritoneWarned) {
                this.baritoneWarned = true;
                this.warn("addons.lotus.messages.baritoneUnavailable");
            }
            this.state = nextState;
            this.resetDelay();
            return;
        }

        this.pendingState = nextState;
        this.walkTarget = pos.immutable();
        this.walkRange = range;
        this.walkIdleTicks = 0;
        this.state = State.WALKING;
        bridge.pathTo(this.walkTarget);
    }

    /**
     * 上游 {@code aA_#onPathEvent(PathEvent.CANCELED)} 的轮询替代：走到了或者路断了就恢复待执行状态。
     *
     * <p>TODO(port): 桥接层没有 {@code GoalNear} 与路径事件，range 到站与「路断了」都由这里按距离和
     *  {@code isPathingOrCalculating()} 判断。
     */
    private void tickWalking() {
        if (this.state != State.WALKING) {
            this.walkIdleTicks = 0;
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            this.restorePendingState();
            return;
        }
        if (this.walkTarget != null
                && player.position().distanceTo(this.walkTarget.getCenter()) <= Math.max(this.walkRange, 1.0D)) {
            bridge.cancelPathing();
            this.restorePendingState();
            return;
        }
        if (bridge.isPathingOrCalculating()) {
            this.walkIdleTicks = 0;
            return;
        }
        if (++this.walkIdleTicks < WALK_IDLE_TICKS) {
            return;
        }
        this.walkIdleTicks = 0;
        this.restorePendingState();
    }

    private void restorePendingState() {
        State pending = this.pendingState;
        this.pendingState = null;
        this.walkTarget = null;
        this.state = pending == null ? State.NONE : pending;
    }

    /** 上游 {@code aw#a(BlockPos, double)}：离目标是否超过给定距离。 */
    private boolean walkNeeded(BlockPos pos, double distance) {
        LocalPlayer player = this.evernightMinecraft.player;
        return player != null && pos != null && player.position().distanceTo(pos.getCenter()) > distance;
    }

    /** 同 {@link #walkNeeded}，但只返回距离值。 */
    private double distanceTo(BlockPos pos) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || pos == null) {
            return Double.MAX_VALUE;
        }
        return player.position().distanceTo(pos.getCenter());
    }

    /** 上游 {@code ac#d_BlockPos_toBlockPos}：目标在脚下时改到玩家所在高度（正上方开箱）。 */
    private BlockPos abovePlayer(BlockPos pos) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || pos == null) {
            return pos;
        }
        int playerY = player.getBlockY();
        return pos.getY() < playerY ? new BlockPos(pos.getX(), playerY, pos.getZ()) : pos;
    }

    // ------------------------------------------------------------------
    // 破坏（上游 bt#a_BlockPos_Runnable + BlockUtils#breakBlock）
    // ------------------------------------------------------------------

    /** 上游 {@code BlockUtils.breakBlock(pos, true)}：转过去，然后发开始/停止破坏包。 */
    private void breakBlockAt(BlockPos pos) {
        ClientContext.rotationManager.requestRotation(RotationManager.getRotationsToBlock(pos), ROTATION_PRIORITY);
        ClientContext.rotationManager.executeAction(() -> {
            Direction side = this.breakSide(pos);
            PacketSender.sendPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, pos, side);
            PacketSender.sendPlayerAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, pos, side);
        });
    }

    /** 破坏包的朝向：取「方块中心 → 玩家眼睛」最近的轴，等于玩家看到的那一面。 */
    private Direction breakSide(BlockPos pos) {
        LocalPlayer player = this.evernightMinecraft.player;
        Vec3 center = pos.getCenter();
        Vec3 eye = player != null ? player.getEyePosition() : center;
        return Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
    }

    // ------------------------------------------------------------------
    // 容器交互（上游 ag#a_BlockPos_Direction_Consumer / c / bp#a）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code ag#a_BlockPos_Direction_Consumer}：确保目标容器已经打开且内容已同步，再把菜单交给回调。
     */
    private void interactContainer(BlockPos pos, Consumer<AbstractContainerMenu> action) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || pos == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu || !pos.equals(this.openedPos)) {
            this.tryOpenContainer(pos);
            this.resetDelay();
            return;
        }
        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) {
            this.warn("addons.lotus.messages.wrongContainer");
            this.openAttempted = false;
            ContainerInteractionUtils.closeScreen();
            this.resetDelay();
            return;
        }
        if (menu.getStateId() <= 0) {
            if (System.currentTimeMillis() - this.lastOpenAt > CONTAINER_SYNC_TIMEOUT_MS) {
                this.warn("addons.lotus.messages.containerNotSynced");
                this.openedPos = null;
                ContainerInteractionUtils.closeScreen();
            }
            this.resetDelay();
            return;
        }
        action.accept(menu);
    }

    /** 上游 {@code ag#c}：对着方块右键开容器，并记住「1 秒内没反应就提示」。 */
    private void tryOpenContainer(BlockPos pos) {
        if (pos == null) {
            return;
        }
        if (this.evernightMinecraft.screen != null) {
            ContainerInteractionUtils.closeScreen();
            this.openedPos = null;
            return;
        }
        long now = System.currentTimeMillis();
        if (this.openAttempted && pos.equals(this.openedPos) && now - this.lastOpenAt <= CONTAINER_OPEN_RETRY_MS) {
            this.warn("addons.lotus.messages.openNoResponse");
            return;
        }
        // TODO(port): 上游按「玩家是否站在箱子上方」决定 Direction.UP 还是就近面；
        //  ContainerInteractionUtils#openContainer 自己按玩家位置挑面，这里不再区分。
        ContainerInteractionUtils.openContainer(pos, true);
        this.openAttempted = true;
        this.openedPos = pos.immutable();
        this.lastOpenAt = now;
    }

    // ------------------------------------------------------------------
    // 背包 / 容器搜索（上游 ag#a_Predicate / d_Predicate / p / r / L）
    // ------------------------------------------------------------------

    /** 上游 {@code ag#a_Predicate}：从背包游标开始遍历 36 格，返回匹配物品的副本。 */
    private ItemStack findInventoryStack(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        for (int index = 0; index < INVENTORY_SLOT_COUNT; index++) {
            this.inventoryCursor = (this.inventoryCursor + 1) % INVENTORY_SLOT_COUNT;
            ItemStack stack = inventory.getItem(this.inventoryCursor);
            if (!stack.isEmpty() && predicate.test(stack)) {
                this.lastInventorySlot = this.inventoryCursor;
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 上游 {@code ag#d_Predicate}：从容器游标开始遍历容器槽位，返回匹配物品的副本。 */
    private ItemStack findContainerStack(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int slotCount = this.containerSlotCount(menu);
        if (slotCount <= 0) {
            return ItemStack.EMPTY;
        }
        for (int index = 0; index < slotCount; index++) {
            this.containerCursor = (this.containerCursor + 1) % slotCount;
            ItemStack stack = menu.getSlot(this.containerCursor).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                this.lastContainerSlot = this.containerCursor;
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 上游 {@code bp#g()}：菜单里属于容器（不含玩家背包）的槽位数。 */
    private int containerSlotCount(AbstractContainerMenu menu) {
        int count = menu instanceof InventoryMenu
                ? menu.slots.size() - INVENTORY_SLOT_COUNT - 1
                : menu.slots.size() - INVENTORY_SLOT_COUNT;
        return Math.max(0, count);
    }

    /**
     * 背包下标 → 当前容器菜单的槽位号。
     *
     * <p>{@code InventoryUtils.toMenuSlot} 只对玩家背包菜单成立；容器打开时玩家背包是菜单的最后 36 格
     * （快捷栏再后 9 格），所以这里按菜单槽位总数推算。
     */
    private int inventorySlotToMenuSlot(int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || inventorySlot < 0) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu) {
            return InventoryUtils.toMenuSlot(inventorySlot);
        }
        int containerSlots = Math.max(0, menu.slots.size() - INVENTORY_SLOT_COUNT);
        return inventorySlot < 9
                ? containerSlots + 27 + inventorySlot
                : containerSlots + inventorySlot - 9;
    }

    /** 上游 {@code ag#p()}：第一个空背包槽位，没有则 -1。 */
    private int firstEmptyInventorySlot() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOT_COUNT; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** 上游 {@code ac#L()}：背包里每种地毯的总数量。 */
    private Map<Item, Integer> countInventoryCarpets() {
        Map<Item, Integer> counts = new HashMap<>();
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return counts;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isCarpetItem(stack.getItem())) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return counts;
    }

    /** 上游 {@code InvUtils.shiftClick().slot(index)}：背包下标 → 当前菜单槽位后 shift 点击。 */
    private void shiftClickInventorySlot(int inventorySlot) {
        int menuSlot = this.inventorySlotToMenuSlot(inventorySlot);
        if (menuSlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(menuSlot, 0, ClickType.QUICK_MOVE);
    }

    /** 上游 {@code InvUtils.shiftClick().slotId(id)}：直接对菜单槽位 shift 点击。 */
    private void shiftClickMenuSlot(int menuSlot) {
        if (menuSlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(menuSlot, 0, ClickType.QUICK_MOVE);
    }

    /** 上游 {@code InvUtils.drop().slot(index)}：丢掉整叠。 */
    private void dropInventorySlot(int inventorySlot) {
        int menuSlot = this.inventorySlotToMenuSlot(inventorySlot);
        if (menuSlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(menuSlot, 1, ClickType.THROW);
    }

    /** 上游 {@code bp#d(int, int)}：拿起整叠 → 右键往目标格倒 1 个 → 把剩下的放回原槽。 */
    private void moveOneToInventorySlot(int fromMenuSlot, int toMenuSlot) {
        if (fromMenuSlot < 0 || toMenuSlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(fromMenuSlot, 0, ClickType.PICKUP);
        InventoryUtils.clickSlot(toMenuSlot, 1, ClickType.PICKUP);
        InventoryUtils.clickSlot(fromMenuSlot, 0, ClickType.PICKUP);
    }

    // ------------------------------------------------------------------
    // 状态机小工具（上游 aw#a_be / b_be / c_be / d_be / e_be、ag#l / c）
    // ------------------------------------------------------------------

    /** 上游 {@code aw#c_be}：直接切状态并重置延迟。 */
    private void changeState(State target) {
        this.state = target;
        this.resetDelay();
    }

    /** 上游 {@code aw#a_be}：先关界面，再进入目标状态（不重置延迟）。 */
    private void closeThenGoTo(State target) {
        this.returnState = target;
        this.state = State.CLOSE_SCREEN;
    }

    /** 上游 {@code aw#b_be}：先关界面，再进入目标状态，并补一个延迟。 */
    private void closeAndGoTo(State target) {
        this.closeThenGoTo(target);
        this.resetDelay();
    }

    /** 上游 {@code aw#e_be}：关界面、清空状态，隔「重启延时」秒后从目标状态继续。 */
    private void restartLater(State target) {
        ContainerInteractionUtils.closeScreen();
        this.openedPos = null;
        this.openAttempted = false;
        this.state = State.NONE;
        this.cancelStart();
        this.scheduleStart(target);
    }

    /** 上游 {@code aw#d_be}：按「重启延时」秒数排一个延迟启动。 */
    private void scheduleStart(State target) {
        if (this.evernightMinecraft.gameMode == null) {
            return;
        }
        GameType mode = this.evernightMinecraft.gameMode.getPlayerMode();
        if (mode == GameType.SPECTATOR || mode == GameType.ADVENTURE) {
            return;
        }
        this.pendingStartStep = target;
        this.startDelayTicks = Math.max(1, this.restartDelaySetting.getValue() * 20);
    }

    /** 上游 {@code aw#A()}：取消排好的延迟启动。 */
    private void cancelStart() {
        this.startDelayTicks = 0;
        this.pendingStartStep = null;
    }

    /** 上游 {@code ag#l()}：把延迟重置成设置值。 */
    private void resetDelay() {
        this.delayTicks = this.delaySetting.getValue();
    }

    /** 上游 {@code ag#c(int)}：按指定 tick 数等待。 */
    private void setDelay(int ticks) {
        this.delayTicks = ticks;
    }

    /** 上游 {@code ac#T()}：清空全部与本轮打印有关的状态。 */
    private void resetRuntime() {
        this.requiredCarpets.clear();
        this.neededCarpets.clear();
        this.wrongBlocks.clear();
        this.restockItem = null;
        this.restockPos = null;
        this.restockCount = 0;
        this.breakTarget = null;
        this.currentPos = null;
        this.sortUnavailableWarned = false;
    }

    /** 上游 {@code aw#a_String_toVoid}：提示一句并停在 {@code NONE}。 */
    private void idle(String translationKey, Object... args) {
        this.warn(translationKey, args);
        this.state = State.NONE;
        this.returnState = State.NONE;
    }

    // ------------------------------------------------------------------
    // 文本（上游 Module#info / warning / error）
    // ------------------------------------------------------------------

    /**
     * 所有玩家可见文本都从语言表取，并统一挂模块签名。
     *
     * <p>TODO(port): 上游的 info/warning/error 是三档带颜色的输出；Evernight 1.0.3 只有一种聊天签名。
     */
    private void print(String translationKey, Object... args) {
        String pattern = Translations.get(translationKey);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    /** 上游 {@code Module#info(...)}：普通提示。 */
    private void info(String translationKey, Object... args) {
        this.print(translationKey, args);
    }

    /** 上游 {@code Module#warning(...)}：警告（Evernight 只有一种输出通道，见 {@link #print} 的 TODO）。 */
    private void warn(String translationKey, Object... args) {
        this.print(translationKey, args);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 上游 {@code bq#b(Item)}：是否是地毯。 */
    private static boolean isCarpetItem(Item item) {
        return CARPET_ITEMS.contains(item);
    }

    /** 上游 {@code Names.get(Item)}：物品的显示名。 */
    private static String itemName(Item item) {
        return new ItemStack(item).getHoverName().getString();
    }

    private static String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    /** 上游 {@code bf} 里本模块用到的状态（名字沿用上游语义）。 */
    private enum State {
        /** 空闲。 */
        NONE,
        /** 拆掉位置错误的方块。 */
        BREAK,
        /** 先关界面，再去 {@link #returnState}。 */
        CLOSE_SCREEN,
        /** Baritone 走位中。 */
        WALKING,
        /** 决定下一步铺哪里。 */
        NEXT,
        /** 去补给箱补充空地图 / 玻璃板 / 铁砧 / 经验瓶。 */
        SUPPLY,
        /** 去地毯补给点补货。 */
        TAKE_ITEM,
        /** 铁砧改名。 */
        USE,
        /** 加载下一张投影。 */
        LOAD_SCHEMATIC,
        /** 检查背包地毯是否够当前行。 */
        CHECK_BACKPACK,
        /** 把待修坐标交给 A打印机。 */
        OPEN_PRINTER,
        /** 拿空地图画一张。 */
        DRAW,
        /** 制图台锁图。 */
        LOCK,
        /** 成品放进成品箱。 */
        PUT,
        /** 整理背包（上游用「快捷物品操作」的排序）。 */
        SORT,
        /** 重置进度、取投影世界、开始算材料。 */
        READY
    }
}
