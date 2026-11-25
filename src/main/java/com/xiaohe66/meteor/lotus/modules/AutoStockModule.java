package com.xiaohe66.meteor.lotus.modules;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import evernight.addon.AddonModule;
import evernight.client.ClientAccess;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.integrations.baritone.BaritoneBridge;
import evernight.client.managers.player.RotationManager;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.ContainerInventoryMath;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.inventory.ShulkerBoxUtils;
import evernight.client.utils.network.PacketSender;
import evernight.client.utils.world.BlockInteractionUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.litematica.materials.MaterialListUtils;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement.RequiredEnabled;
import fi.dy.masa.litematica.selection.Box;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code af} (S自动备货) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>功能与上游一致：读取 Litematica <b>当前激活的投影</b>的材料清单，在 Lotus「简易仓库」里把每一项
 * 材料从对应仓库格位取到身上，装进摆好的潜影盒，再由「活塞 + 按钮」把盒子打落、收进「备货」箱。
 * 缺少的物品（仓库里找不到位置 / 箱子里没有）会被跳过，并且<b>从需求量最少的物品开始备货</b>。
 *
 * <p><b>仓库布局</b>（逐行照抄上游 {@code br#a(int,int)}，<b>没有改</b>）：展示框贴在支撑方块上，
 * 「框方块 +1Y + 朝向反向」是箱子（{@code SlotPos.chestPos}）、「框方块 -2Y」是放盒格
 * （{@code kitPos}，与玩家同层）、放盒格下面一格必须是活塞、放盒格朝展示框正面一格必须是按钮
 * （{@code buttonPos}，同时被上游当作「站到旁边」的距离参照点）、「箱子 -2Y」是卸货点
 * （{@code belowChestPos}）。理解这一段之前不要「顺手修正」这些偏移，整套状态机就对不上了。
 *
 * <p><b>状态机</b>：上游基类 {@code aw} 用「当前状态 + 关界面后要去的状态 + 走位后要去的状态」
 * 驱动；这里改成 {@link State} 枚举，语义一一对应（{@code CLOSE_SCREEN} 对应 {@code bf.c}，
 * {@code WALKING} 对应 {@code bf.g}）。每个 tick 先等延迟（上游 {@code ag#d}），再执行当前状态的
 * 处理函数，异常时打印堆栈并关掉模块（上游 {@code aw#onTick} 的兜底）。
 *
 * <p><b>与上游的差异 / 删掉的东西</b>：
 * <ul>
 *     <li>Meteor 的设置分组与 GUI 全删，只保留 8 个设置；「初始化」仍是一次性开关（打开后自动拨回
 *         false 再执行扫描）。</li>
 *     <li>「空盒标识 / 备货标识」在 Meteor 里是单物品设置；Evernight 1.0.3 没有单物品设置类型，
 *         这里用 {@link RegistrySetSetting}（集合），集合里任意一个物品都算该标识；只选一个时行为
 *         与上游完全相同。</li>
 *     <li>上游从「简易仓库」模块（{@code U_}）的内存表读取「散装物品分类」（目标物品 → 关联物品），
 *         那张表由 Meteor 自绘 GUI 维护。本插件没有该模块，改为读取同一份 JSON 配置
 *         （{@code 散装物品配置}，等价于上游「导入配置」按钮），见 {@link #loadBulkCategories()}。</li>
 *     <li>Litematica 在上游是硬依赖（类级 import，没装直接加载失败）；这里按任务要求做成<b>运行期可选</b>：
 *         所有 Litematica 调用都隔离在 {@link SchematicMaterials} 里，只有
 *         {@link #litematicaLoaded()} 为 true 时才会加载那个类。</li>
 *     <li>上游 {@code bp#a(int, Runnable)} 那套「主手空就换、否则找空快捷栏格中转」的换手逻辑，
 *         这里简化为「快捷栏直接选 / 背包里和当前快捷栏格交换」，见 {@link #selectForPlacement(int)}。</li>
 *     <li>Baritone 的 {@code allowBreak / allowPlace} 在启用期间被上游置为 false，{@code BaritoneBridge}
 *         没有这两个开关，未做等价处理。</li>
 *     <li>上游基类 {@code aw} 的「快速停止键」（默认 ESC，走位途中不生效）与「重启延时」没有移植：
 *         Evernight 1.0.3 没有设置级键位，需要时请给模块本身绑一个开关键。</li>
 *     <li>上游的 warning/error 带彩色前缀；Evernight 只有一种聊天签名，所有提示都走模块签名。</li>
 * </ul>
 *
 * <p>这个文件只做静态移植，没有在游戏里跑过（仓库模板 AGENTS.md 第 6 条）。
 */
public final class AutoStockModule extends AddonModule {
    /** 玩家背包槽位数量（0-8 快捷栏，9-35 主背包），与上游 {@code bp.b} 一致。*/
    private static final int INVENTORY_SIZE = ContainerInventoryMath.INVENTORY_SIZE;
    /** 快捷栏槽位数量。*/
    private static final int HOTBAR_SIZE = ContainerInventoryMath.HOTBAR_SIZE;
    /** 上游 {@code br#a(range, 4)} 的第二个参数：展示框扫描盒相对玩家 Y 的高度。*/
    private static final int SCAN_HEIGHT = 4;
    /** 上游 {@code ag#a_m(m)} / {@code aA_#a(pos, 0, ...)} 的距离阈值：超过 1 格就走过去。*/
    private static final double STAND_DISTANCE = 1.0D;
    /** 走位判定「已经到了」的距离（上游没有这一项，靠 Baritone 的 AT_GOAL）。*/
    private static final double ARRIVE_DISTANCE = 2.0D;
    /** 上游 {@code aA_#a} 的「移动距离超过 1000 格就关闭」。*/
    private static final double MAX_MOVE_DISTANCE = 1000.0D;
    /** 刚发完 pathTo 时 isPathingOrCalculating() 可能短暂为 false，宽限这么多 tick 再判定路断了。*/
    private static final int WALK_GRACE_TICKS = 20;
    /** 兜底：Baritone 卡住时最多等这么久就回落。*/
    private static final int WALK_TIMEOUT_TICKS = 20 * 60;
    /** 同一目标连续这么多次「没靠近」就判定不可达，关掉模块。*/
    private static final int MAX_PATH_ATTEMPTS = 20;
    /** 上游 {@code ag#a_BlockPos_Consumer} 的容器同步超时。*/
    private static final long CONTAINER_SYNC_TIMEOUT_MS = 2000L;
    /** 上游 {@code ag#c(BlockPos, Direction)} 的「打开没有反应」判断窗口。*/
    private static final long OPEN_RETRY_WARN_MS = 1000L;
    /** 上游 {@code L()} 里 {@code this.c(40)}：按完按钮等 40 tick 再继续。*/
    private static final int BREAK_WAIT_TICKS = 40;
    /** 上游 {@code br#a_Vec3_toBlockPos} 的 2.030625 = 1.425^2。*/
    private static final double DROP_STAND_RADIUS_SQR = 2.030625D;

    /** 状态机：对应上游 {@code bf} 里本模块用到的那些常量。*/
    private enum State {
        /** 上游 {@code bf.a}：空闲 / 出错后的占位状态。*/
        NONE,
        /** 上游 {@code bf.c}：先关界面，再切到 {@link #closeTarget}。*/
        CLOSE_SCREEN,
        /** 上游 {@code bf.g}：Baritone 走位中，走完回到 {@link #pendingState}。*/
        WALKING,
        /** 上游 {@code bf.h}：挑下一项材料 / 捡地上的盒子 / 归位身上的东西。*/
        NEXT,
        /** 上游 {@code bf.d}：检查备货箱里已经备好的物品。*/
        CHECK,
        /** 上游 {@code bf.w}：把需要的散装物品放进成品盒。*/
        PUT_ITEM,
        /** 上游 {@code bf.n}：把需要的整盒物品存进备货箱 / 把空盒存回空盒箱。*/
        PUT_KIT,
        /** 上游 {@code bf.t}：从仓库格位取物品（散装或整盒）。*/
        TAKE_ITEM,
        /** 上游 {@code bf.m}：从卸货点整盒整盒地取。*/
        TAKE_KIT,
        /** 上游 {@code bf.p}：把装有目标物品的盒子放到取货格上。*/
        PLACE_KIT,
        /** 上游 {@code bf.q}：按按钮让活塞打落盒子。*/
        BREAK_KIT,
        /** 上游 {@code bf.C}：在成品格摆一个空盒。*/
        PLACE_EMPTY_KIT,
        /** 上游 {@code bf.A}：从空盒箱拿一个空盒。*/
        TAKE_EMPTY_KIT
    }

    /** 上一次开箱交互是否已经「发出去」了（上游 {@code ag.d}）。*/
    private enum OpenState {
        CHECK,
        OPENING
    }

    private final Setting<Integer> delaySetting;
    private final Setting<Integer> scanRangeSetting;
    private final Setting<Integer> operationCountSetting;
    private final Setting<Integer> wholeBoxThresholdSetting;
    private final Setting<Set<Item>> emptyKitItemsSetting;
    private final Setting<Set<Item>> stockKitItemsSetting;
    private final Setting<String> bulkConfigSetting;
    private final Setting<Boolean> initializeSetting;

    /** 上游 {@code aw.m}：当前状态。*/
    private State state = State.NONE;
    /** 上游 {@code aw.o}：关掉界面之后要去的状态。*/
    private State closeTarget;
    /** 上游 {@code aw.n}：走位结束后要回到的状态。*/
    private State pendingState;

    /** 上游 {@code ag.a}：延迟倒计时（tick）。*/
    private int delayTicks;
    /** 上游 {@code ag.b}：背包扫描的轮转游标 / 最近一次找到的背包槽位。*/
    private int inventoryCursor;
    /** 上游 {@code ag.c}：容器扫描的轮转游标 / 最近一次找到的容器槽位。*/
    private int containerCursor;

    /** 上游 {@code ag.d}：上一次开箱是否已经确认「打开了」。*/
    private OpenState openState = OpenState.CHECK;
    private long lastOpenAt;
    /** 上游 {@code ag.f}：上一次交互的容器位置。*/
    private BlockPos lastContainerPos;

    /** 走位轮询：路径停下后的静默 tick 数。*/
    private int pathIdleTicks;
    /** 走位重试计数：同一目标、距离没有明显缩短的连续重试次数。*/
    private int pathAttempts;
    private BlockPos lastPathTarget;
    private double lastPathDistance = Double.MAX_VALUE;
    private long walkStartedAt;

    /** 上游 {@code l}：普通物品格位（展示框物品 → 五个位置）。*/
    private final Map<ItemKey, SlotPos> itemSlots = new HashMap<>();
    /** 上游 {@code v}：散装物品分类的关联物品 → 该类别所在格位。*/
    private final Map<Item, SlotPos> relatedSlots = new HashMap<>();
    /** 上游 {@code w}：空盒箱位置（框里是「空盒标识」）。*/
    private SlotPos emptyBoxSlot;
    /** 上游 {@code x}：备货位置（框里是「备货标识」）。*/
    private SlotPos stockSlot;

    /** 上游 {@code y}：还需要的材料 → 数量，按数量升序（LinkedHashMap 保持顺序）。*/
    private final Map<ItemKey, Integer> required = new LinkedHashMap<>();
    /** 上游 {@code z}：本轮已经从仓库里取了多少（算剩余量用）。*/
    private final Map<ItemKey, Integer> alreadyTaken = new HashMap<>();
    /** 上游 {@code A}：缺位置 / 缺物品而被跳过的材料。*/
    private final Set<ItemKey> skipped = new HashSet<>();

    /** 上游 {@code B}：当前处理的材料。*/
    private ItemKey currentKey;
    /** 上游 {@code C}：当前处理的仓库格位。*/
    private SlotPos currentSlot;
    /** 上游 {@code D}：当前要打落的盒子所在格位。*/
    private SlotPos boxToBreak;

    /** 上游 {@code E}：本轮已取的物品数，达到「操作数」就去放。*/
    private int operations;
    /** 上游 {@code F}：检查备货的轮次（0 = 卸货点，1 = 成品盒，>=2 结束）。*/
    private int checkPhase;
    /** 上游 {@code G}：整盒取货的轮次（0 = 正常，1 = 刚拿了一盒，2 = 放盒取散装）。*/
    private int wholeBoxPhase;

    public AutoStockModule(ModuleCategory category) {
        super("lotus", "LotusAutoStock",
                "Stocks the materials required by the active Litematica schematic from a Lotus warehouse.",
                category);

        // 上游基类 ag 的「延迟」：默认 5，上限 40。
        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
        this.scanRangeSetting = this.add(new NumberSetting<>("ScanRange", 50, 1, 100));
        this.operationCountSetting = this.add(new NumberSetting<>("OperationCount", 9, 1, 18));
        this.wholeBoxThresholdSetting = this.add(new NumberSetting<>("WholeBoxThreshold", 9, 1, 27));
        this.emptyKitItemsSetting = this.add(
                new RegistrySetSetting<Item>("EmptyKitItems", BuiltInRegistries.ITEM, Items.SHULKER_BOX));
        this.stockKitItemsSetting = this.add(
                new RegistrySetSetting<Item>("StockKitItems", BuiltInRegistries.ITEM, Items.GREEN_SHULKER_BOX));
        this.bulkConfigSetting = this.add(new StringSetting("BulkConfig", "warehouse/mappings.json"));
        this.initializeSetting = this.add(new BooleanSetting("Initialize", false));
        this.initializeSetting.setChangeCallback(this::onInitializeSettingChanged);
    }

    /**
     * Litematica 是否已安装。
     *
     * <p>这个模块的行为完全依赖投影材料清单，所以没装时初始化会直接失败并提示；但类本身必须能在
     * 没有 Litematica 的环境里加载（构造器与静态初始化不碰任何 Litematica 类型）。
     */
    public static boolean litematicaLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    // ---------------------------------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.resetPathTracking();
        this.closeTarget = null;
        this.pendingState = null;
        this.openState = OpenState.CHECK;
        this.lastContainerPos = null;
        this.inventoryCursor = 0;
        this.containerCursor = 0;
        this.state = State.NONE;

        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            this.warn("addons.lotus.messages.worldUnavailable");
            return;
        }
        // 上游 af#onActivate：没初始化就自动初始化，失败则关掉模块。
        if (this.stockSlot == null || this.required.isEmpty()) {
            this.warn("addons.lotus.messages.autoInitialize");
            if (!this.initialize()) {
                this.warn("addons.lotus.messages.autoInitializeFailed");
                this.disable();
                return;
            }
        }

        this.currentKey = null;
        this.currentSlot = null;
        this.boxToBreak = null;
        this.operations = 0;
        this.wholeBoxPhase = 0;
        if (this.checkPhase >= 2) {
            this.tickNext();
        } else {
            this.alreadyTaken.clear();
            this.walkTo(this.stockSlot.buttonPos(), State.CHECK, "addons.lotus.messages.checkStock");
        }
    }

    @Override
    public void onDisable() {
        this.currentKey = null;
        this.currentSlot = null;
        this.boxToBreak = null;
        this.closeTarget = null;
        this.pendingState = null;
        this.state = State.NONE;
        this.resetPathTracking();
        // 上游 aA_#onDeactivate：取消 Baritone 走位；它还会把 allowBreak/allowPlace 改回 true，
        // 桥接层没有这两个开关，见类注释。
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge != null && bridge.isAvailable()) {
            bridge.cancelPathing();
        }
        this.closeScreenNow();
    }

    /**
     * 「初始化」设置的回调：上游把它当成一次性的按钮。
     *
     * <p>先把开关拨回 false，再执行初始化；{@code setValue} 会再次回调，所以必须先判断当前值。
     */
    private void onInitializeSettingChanged() {
        if (!Boolean.TRUE.equals(this.initializeSetting.getValue())) {
            return;
        }
        this.initializeSetting.setValue(false);
        if (this.evernightMinecraft.player == null || this.evernightMinecraft.level == null) {
            this.warn("addons.lotus.messages.worldUnavailable");
            return;
        }
        this.initialize();
    }

    // ---------------------------------------------------------------------------------------------
    // tick 主循环（上游 aw#onTick + ag#d）
    // ---------------------------------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (this.state == State.WALKING) {
            this.tickPathing();
            return;
        }
        this.pathIdleTicks = 0;
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }
        try {
            switch (this.state) {
                case NONE -> {
                    // 上游 bf.a 的占位状态，什么都不做。
                }
                case CLOSE_SCREEN -> this.tickCloseScreen();
                case NEXT -> this.tickNext();
                case CHECK -> this.tickCheck();
                case PUT_ITEM -> this.tickPutItem();
                case PUT_KIT -> this.tickPutKit();
                case TAKE_ITEM -> this.tickTakeItem();
                case TAKE_KIT -> this.tickTakeKit();
                case PLACE_KIT -> this.tickPlaceKit();
                case BREAK_KIT -> this.tickBreakKit();
                case PLACE_EMPTY_KIT -> this.tickPlaceEmptyKit();
                case TAKE_EMPTY_KIT -> this.tickTakeEmptyKit();
                case WALKING -> {
                    // 上面已经拦掉，这里不会进来。
                }
            }
        } catch (Throwable throwable) {
            // 上游 aw#onTick 的兜底：打印堆栈、提示、关掉模块。
            throwable.printStackTrace();
            this.warn("addons.lotus.messages.unexpectedError", describeThrowable(throwable));
            this.disable();
        }
    }

    /** 上游 {@code aw#y()}：关掉容器界面后接着执行原状态。*/
    private void tickCloseScreen() {
        this.closeScreenNow();
        State next = this.closeTarget;
        this.closeTarget = null;
        this.state = next == null ? State.NONE : next;
        this.resetDelay();
    }

    /** 上游 {@code aw#a_be(be)}：先关界面，再切到目标状态（延迟在关界面那一步重置）。*/
    private void closeThen(State nextState) {
        this.closeTarget = nextState;
        this.state = State.CLOSE_SCREEN;
    }

    /** 上游 {@code aw#c_be(be)}：直接切状态并重置延迟。*/
    private void switchTo(State nextState) {
        this.state = nextState;
        this.resetDelay();
    }

    /** 上游 {@code aw#a_String_toVoid}：提示后停在 NONE（模块不会自己继续，需要手动重开）。*/
    private void warnAndHalt(String translationKey, Object... args) {
        this.warn(translationKey, args);
        this.state = State.NONE;
        this.closeTarget = State.NONE;
    }

    // ---------------------------------------------------------------------------------------------
    // CHECK（上游 af#w）
    // ---------------------------------------------------------------------------------------------

    /** 上游 {@code af#w}：先看卸货点里有多少需要的物品，再看成品盒里有多少。*/
    private void tickCheck() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null || this.stockSlot == null) {
            return;
        }
        if (this.checkPhase >= 2) {
            this.closeThen(State.NEXT);
            return;
        }
        if (this.isFar(this.stockSlot)) {
            this.pathTo(this.stockSlot, "addons.lotus.messages.checkDistanceTooFar", State.CHECK);
            return;
        }
        BlockPos target;
        if (this.checkPhase == 0) {
            target = this.stockSlot.belowChestPos();
        } else {
            target = this.stockSlot.kitPos();
            BlockState kitState = level.getBlockState(target);
            if (!ShulkerBoxUtils.isShulkerBoxItem(kitState.getBlock().asItem())) {
                this.closeThen(State.NEXT);
                return;
            }
        }
        this.withOpenContainer(target, menu -> {
            Map<ItemKey, Integer> found = new HashMap<>();
            int slots = containerSlotCount(menu, player.getInventory());
            for (int slot = 0; slot < slots; slot++) {
                ItemStack stack = menu.getSlot(slot).getItem();
                if (stack.isEmpty()) {
                    continue;
                }
                if (ShulkerBoxUtils.isShulkerBoxItem(stack.getItem())) {
                    // 上游把成品盒里的东西也算成「已备好」。
                    for (Map.Entry<ItemKey, Integer> entry : new BoxContents(stack).counts().entrySet()) {
                        found.merge(entry.getKey(), entry.getValue(), Integer::sum);
                    }
                } else {
                    found.merge(new ItemKey(stack), stack.getCount(), Integer::sum);
                }
            }
            if (!found.isEmpty()) {
                Iterator<Map.Entry<ItemKey, Integer>> iterator = this.required.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<ItemKey, Integer> entry = iterator.next();
                    Integer available = found.get(entry.getKey());
                    if (available == null) {
                        continue;
                    }
                    int remaining = entry.getValue() - available;
                    if (remaining <= 0) {
                        this.info("addons.lotus.messages.stocked", entry.getKey().displayName());
                        iterator.remove();
                    } else {
                        entry.setValue(remaining);
                        this.info("addons.lotus.messages.stockedPartially",
                                entry.getKey().displayName(), remaining);
                    }
                }
            }
            this.checkPhase++;
            this.closeThen(State.CHECK);
        });
    }

    // ---------------------------------------------------------------------------------------------
    // NEXT（上游 af#x）
    // ---------------------------------------------------------------------------------------------

    /** 上游 {@code af#x}：捡盒子 → 归位身上的东西 → 挑下一项材料 → 结束。*/
    private void tickNext() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }

        // 1) 地上掉着盒子先捡（上游 br#a 的「捡kit」）。
        List<ItemEntity> droppedBoxes = level.getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(5.0D),
                entity -> ShulkerBoxUtils.isShulkerBoxItem(entity.getItem().getItem()));
        if (!droppedBoxes.isEmpty()) {
            ItemEntity itemEntity = droppedBoxes.getFirst();
            BlockPos target = itemEntity.blockPosition();
            if (target.getY() != player.getBlockY()) {
                BlockPos standing = this.standingPosForDrop(itemEntity.position());
                if (standing != null) {
                    target = standing;
                }
            }
            this.closeScreenNow();
            this.walkTo(target, State.PUT_KIT, "addons.lotus.messages.pickupKit");
            return;
        }

        // 2) 操作数用完了：先把身上的需求物品 / 整盒送去备货箱。
        if (this.operations >= this.operationCountSetting.getValue()) {
            this.operations = 0;
            ItemStack neededLoose = this.findInInventory(stack -> !ShulkerBoxUtils.isShulkerBoxItem(stack.getItem())
                    && this.required.containsKey(new ItemKey(stack)));
            if (!neededLoose.isEmpty()) {
                this.closeThen(State.PUT_ITEM);
                return;
            }
            BoxContents neededBox = this.findBoxInInventory(
                    box -> !box.isEmpty() && box.hasAnyKey(this.required.keySet()));
            if (neededBox != null) {
                this.closeThen(State.PUT_KIT);
                return;
            }
        }

        // 3) 还有没备好的材料：按需求从少到多挑一项，找到它的仓库格位。
        if (!this.required.isEmpty() && !this.skipped.containsAll(this.required.keySet())) {
            SlotPos storage = this.findNextStorage();
            if (storage == null) {
                return;
            }
            this.wholeBoxPhase = 0;
            if (storage.equals(this.currentSlot)) {
                // 上游这里是直接赋值（不重置延迟、不走位）。
                this.state = State.TAKE_ITEM;
            } else {
                this.currentSlot = storage;
                this.closeScreenNow();
                this.pathTo(storage, "addons.lotus.messages.takenStock", State.TAKE_ITEM,
                        this.currentKey.displayName(), this.required.getOrDefault(this.currentKey, 0));
            }
            return;
        }

        // 4) 还有东西在身上：先把它们放回去，再判定是否真的备完。
        if (this.operations > 0) {
            this.operations = this.operationCountSetting.getValue();
            return;
        }
        this.warn("addons.lotus.messages.stockFinished");
        this.disable();
    }

    /**
     * 上游 {@code x()} 里挑下一项材料的循环。
     *
     * @return 找到的仓库格位；{@code null} 表示这一轮已经没有可取的项（上游直接 return）
     */
    private SlotPos findNextStorage() {
        for (Map.Entry<ItemKey, Integer> entry : this.required.entrySet()) {
            this.currentKey = entry.getKey();
            if (this.skipped.contains(this.currentKey)) {
                continue;
            }
            if (this.boxesNeeded() <= 0) {
                this.skipped.add(this.currentKey);
                continue;
            }
            SlotPos candidate = this.itemSlots.get(this.currentKey);
            if (candidate == null) {
                candidate = this.relatedSlots.get(this.currentKey.item());
            }
            if (candidate != null) {
                return candidate;
            }
            this.warn("addons.lotus.messages.missingStorageSlot", this.currentKey.displayName());
            this.skipped.add(this.currentKey);
        }
        return null;
    }

    // ---------------------------------------------------------------------------------------------
    // PUT_ITEM（上游 af#C）：把身上的散装需求物品放进成品盒
    // ---------------------------------------------------------------------------------------------

    private void tickPutItem() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null || this.stockSlot == null) {
            return;
        }
        BlockPos kitPos = this.stockSlot.kitPos();
        BlockState kitState = level.getBlockState(kitPos);
        if (kitState.isAir()) {
            this.closeThen(State.PLACE_EMPTY_KIT);
            return;
        }
        if (!(kitState.getBlock() instanceof ShulkerBoxBlock)) {
            this.closeScreenNow();
            this.warnAndHalt("addons.lotus.messages.finishedPositionOccupied");
            return;
        }
        if (this.isFar(this.stockSlot)) {
            this.pathTo(this.stockSlot, "addons.lotus.messages.storeLooseDistanceTooFar", State.PUT_ITEM);
            return;
        }
        ItemStack stack = this.findInInventory(candidate -> !ShulkerBoxUtils.isShulkerBoxItem(candidate.getItem())
                && this.required.containsKey(new ItemKey(candidate)));
        if (stack.isEmpty()) {
            this.closeThen(State.PUT_KIT);
            return;
        }
        int inventorySlot = this.inventoryCursor;
        this.withOpenContainer(kitPos, menu -> {
            if (this.isContainerFull(menu)) {
                this.boxToBreak = this.stockSlot;
                this.closeThen(State.BREAK_KIT);
            } else {
                this.shiftClickInventory(inventorySlot);
                this.resetDelay();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // PUT_KIT（上游 af#D）：把整盒需求物品存进备货箱 / 把空盒存回空盒箱
    // ---------------------------------------------------------------------------------------------

    private void tickPutKit() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null || this.stockSlot == null) {
            return;
        }
        BoxContents neededBox = this.findBoxInInventory(
                box -> !box.isEmpty() && box.hasAnyKey(this.required.keySet()));
        if (neededBox != null) {
            if (this.isFar(this.stockSlot)) {
                this.pathTo(this.stockSlot, "addons.lotus.messages.storeKitDistanceTooFar", State.PUT_KIT);
                return;
            }
            int inventorySlot = this.inventoryCursor;
            // 先把盒子内容读出来再 shift 点击，避免点完之后再读已被移走的格子。
            Map<ItemKey, Integer> contents = new LinkedHashMap<>(neededBox.counts());
            this.withOpenContainer(this.stockSlot.chestPos(), menu -> {
                this.shiftClickInventory(inventorySlot);
                for (Map.Entry<ItemKey, Integer> entry : contents.entrySet()) {
                    this.reduceRequirement(entry.getKey(), entry.getValue());
                }
                this.resetDelay();
            });
            return;
        }

        BoxContents emptyBox = this.findBoxInInventory(BoxContents::isEmpty);
        if (emptyBox == null) {
            this.closeThen(State.CHECK);
            return;
        }
        if (this.emptyBoxSlot == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isFar(this.emptyBoxSlot)) {
            this.pathTo(this.emptyBoxSlot, "addons.lotus.messages.storeEmptyKitDistanceTooFar", State.PUT_KIT);
            return;
        }
        int inventorySlot = this.inventoryCursor;
        this.withOpenContainer(this.emptyBoxSlot.chestPos(), menu -> {
            this.shiftClickInventory(inventorySlot);
            this.resetDelay();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // TAKE_ITEM（上游 af#G / H / I）
    // ---------------------------------------------------------------------------------------------

    /** 上游 {@code af#G}：按剩余量决定「整盒取」还是「从盒子里逐格取」。*/
    private void tickTakeItem() {
        if (this.currentKey == null || this.currentSlot == null) {
            this.state = State.NEXT;
            return;
        }
        int boxes = this.boxesNeeded();
        if (boxes <= 0) {
            this.tickNext();
            return;
        }
        if (this.operations >= this.operationCountSetting.getValue()) {
            this.closeThen(State.PUT_ITEM);
            return;
        }
        if (this.itemSlots.containsKey(this.currentKey)) {
            if (boxes >= this.wholeBoxThresholdSetting.getValue()) {
                this.closeThen(State.TAKE_KIT);
                return;
            }
            this.takeItemFromPlacedKit();
        } else {
            this.takeItemFromContainer();
        }
    }

    /** 上游 {@code af#H}：普通物品格位——从卸货点里一格一格拿。*/
    private void takeItemFromContainer() {
        if (this.isFar(this.currentSlot)) {
            this.pathTo(this.currentSlot, "addons.lotus.messages.takeItemDistanceTooFar", State.TAKE_ITEM);
            return;
        }
        ItemKey key = this.currentKey;
        this.withOpenContainer(this.currentSlot.belowChestPos(), menu -> {
            ItemStack stack = this.findInContainer(candidate -> key.matches(candidate));
            if (stack.isEmpty()) {
                this.warn("addons.lotus.messages.missingMaterial", key.displayName());
                this.skipped.add(key);
                this.tickNext();
            } else {
                this.shiftClickContainer(this.containerCursor);
                this.alreadyTaken.merge(key, stack.getCount(), Integer::sum);
                this.operations++;
                this.resetDelay();
            }
        });
    }

    /** 上游 {@code af#I}：从摆在格位上的成品盒里一格一格拿；盒子空了就打落它。*/
    private void takeItemFromPlacedKit() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        BlockPos kitPos = this.currentSlot.kitPos();
        BlockState kitState = level.getBlockState(kitPos);
        if (!(kitState.getBlock() instanceof ShulkerBoxBlock)) {
            this.closeThen(State.PLACE_KIT);
            return;
        }
        if (this.isFar(this.currentSlot)) {
            this.pathTo(this.currentSlot, "addons.lotus.messages.takeItemDistanceTooFar", State.TAKE_ITEM);
            return;
        }
        ItemKey key = this.currentKey;
        SlotPos slot = this.currentSlot;
        this.withOpenContainer(kitPos, menu -> {
            ItemStack stack = this.findInContainer(candidate -> key.matches(candidate));
            if (stack.isEmpty()) {
                this.info("addons.lotus.messages.boxEmpty");
                this.boxToBreak = slot;
                this.closeThen(State.BREAK_KIT);
            } else {
                this.shiftClickContainer(this.containerCursor);
                this.alreadyTaken.merge(key, stack.getCount(), Integer::sum);
                this.operations++;
                this.resetDelay();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // TAKE_KIT（上游 af#E）：需求的量超过阈值时，从卸货点整盒整盒地取
    // ---------------------------------------------------------------------------------------------

    private void tickTakeKit() {
        if (this.currentKey == null || this.currentSlot == null) {
            this.state = State.NEXT;
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        int boxes = this.boxesNeeded();
        if (boxes <= 0) {
            this.closeThen(State.NEXT);
            return;
        }
        if (this.operations >= this.operationCountSetting.getValue()) {
            this.closeThen(State.PUT_ITEM);
            return;
        }
        if (boxes < this.wholeBoxThresholdSetting.getValue()) {
            BlockState kitState = level.getBlockState(this.currentSlot.kitPos());
            if (!kitState.isAir()) {
                // 格位上已经有盒子了，直接去盒子里取。
                this.closeThen(State.TAKE_ITEM);
                return;
            }
            if (this.wholeBoxPhase >= 2) {
                this.closeThen(State.PLACE_KIT);
                return;
            }
            if (this.wholeBoxPhase == 0) {
                this.wholeBoxPhase = 1;
            }
        }
        if (this.isFar(this.currentSlot)) {
            this.pathTo(this.currentSlot, "addons.lotus.messages.takeStockDistanceTooFar", State.TAKE_KIT);
            return;
        }
        ItemKey key = this.currentKey;
        this.withOpenContainer(this.currentSlot.belowChestPos(), menu -> {
            BoxContents box = this.findBoxInContainer(candidate -> key.equals(candidate.dominantKey()));
            if (box == null) {
                this.warn("addons.lotus.messages.missingMaterial", key.displayName());
                this.skipped.add(key);
                this.closeThen(State.CHECK);
                return;
            }
            // 上游取的是盒子里「第一种」物品的数量；先读再点，理由同上。
            Map.Entry<ItemKey, Integer> first = box.counts().entrySet().iterator().next();
            this.shiftClickContainer(this.containerCursor);
            if (this.wholeBoxPhase == 0) {
                this.alreadyTaken.merge(key, first.getValue(), Integer::sum);
                this.operations++;
            } else if (this.wholeBoxPhase == 1) {
                this.wholeBoxPhase = 2;
            }
            this.resetDelay();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // PLACE_KIT（上游 af#F）：把装有目标物品的盒子放到取货格
    // ---------------------------------------------------------------------------------------------

    private void tickPlaceKit() {
        if (this.currentKey == null || this.currentSlot == null) {
            this.state = State.NEXT;
            return;
        }
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }
        BlockPos kitPos = this.currentSlot.kitPos();
        BlockState kitState = level.getBlockState(kitPos);
        if (!kitState.isAir()) {
            if (kitState.getBlock() instanceof ShulkerBoxBlock) {
                // 上游这里是直接赋值。
                this.state = State.TAKE_ITEM;
                return;
            }
            this.warnAndHalt("addons.lotus.messages.takePositionOccupied");
            return;
        }
        ItemKey key = this.currentKey;
        BoxContents box = this.findBoxInInventory(candidate -> candidate.contains(key));
        if (box == null) {
            this.state = State.TAKE_KIT;
            return;
        }
        if (this.isFar(this.currentSlot)) {
            this.pathTo(this.currentSlot, "addons.lotus.messages.placeKitDistanceTooFar", State.PLACE_KIT);
            return;
        }
        this.info("addons.lotus.messages.placingKit", key.displayName());
        this.placeHeldBlock(kitPos, this.inventoryCursor);
        this.switchTo(State.TAKE_ITEM);
    }

    // ---------------------------------------------------------------------------------------------
    // PLACE_EMPTY_KIT（上游 af#K）：在成品格摆一个空盒
    // ---------------------------------------------------------------------------------------------

    private void tickPlaceEmptyKit() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null || this.stockSlot == null) {
            return;
        }
        BlockPos kitPos = this.stockSlot.kitPos();
        BlockState kitState = level.getBlockState(kitPos);
        if (kitState.getBlock() instanceof ShulkerBoxBlock) {
            // 上游这里是直接赋值。
            this.state = State.PUT_ITEM;
            return;
        }
        if (!kitState.isAir()) {
            this.warnAndHalt("addons.lotus.messages.positionOccupied", this.stockSlot.key().displayName());
            return;
        }
        ItemStack emptyBox = this.findInInventory(stack -> ShulkerBoxUtils.isShulkerBoxItem(stack.getItem())
                && new BoxContents(stack).isEmpty());
        if (emptyBox.isEmpty()) {
            // 上游这里是直接赋值。
            this.state = State.TAKE_EMPTY_KIT;
            return;
        }
        if (this.isFar(this.stockSlot)) {
            // 上游这里调 aA_#a(pos, 0, state) 但不带提示，所以消息传 null。
            this.walkTo(this.stockSlot.buttonPos(), State.PLACE_EMPTY_KIT, null);
            return;
        }
        int inventorySlot = this.inventoryCursor;
        this.info("addons.lotus.messages.placingEmptyKit");
        this.placeHeldBlock(kitPos, inventorySlot);
        this.switchTo(State.PUT_ITEM);
    }

    // ---------------------------------------------------------------------------------------------
    // TAKE_EMPTY_KIT（上游 af#J）：从空盒箱拿一个空盒
    // ---------------------------------------------------------------------------------------------

    private void tickTakeEmptyKit() {
        if (this.emptyBoxSlot == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isFar(this.emptyBoxSlot)) {
            this.pathTo(this.emptyBoxSlot, "addons.lotus.messages.takeEmptyKitDistanceTooFar", State.TAKE_EMPTY_KIT);
            return;
        }
        this.withOpenContainer(this.emptyBoxSlot.belowChestPos(), menu -> {
            ItemStack emptyBox = this.findInContainer(stack -> ShulkerBoxUtils.isShulkerBoxItem(stack.getItem())
                    && new BoxContents(stack).isEmpty());
            if (!emptyBox.isEmpty()) {
                this.shiftClickContainer(this.containerCursor);
                this.closeThen(State.PLACE_EMPTY_KIT);
            } else {
                this.warnAndHalt("addons.lotus.messages.emptyKitChestEmpty");
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // BREAK_KIT（上游 af#L）：按按钮让活塞打落盒子
    // ---------------------------------------------------------------------------------------------

    private void tickBreakKit() {
        if (this.boxToBreak == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isFar(this.boxToBreak)) {
            this.pathTo(this.boxToBreak, "addons.lotus.messages.breakDistanceTooFar", State.BREAK_KIT);
            return;
        }
        BlockPos buttonPos = this.boxToBreak.buttonPos();
        // 逐行照抄上游：命中点是按钮方块中心再往下半格，方向 UP，交互手是主手。
        Vec3 hitPoint = Vec3.atCenterOf(buttonPos).add(0.0D, -0.5D, 0.0D);
        BlockHitResult hitResult = new BlockHitResult(hitPoint, Direction.UP, buttonPos, false);
        PacketSender.sendUseItemOn(InteractionHand.MAIN_HAND, hitResult);
        // 上游：this.m = bf.h（NEXT）; this.c(40)。
        this.state = State.NEXT;
        this.setDelay(BREAK_WAIT_TICKS);
    }

    // ---------------------------------------------------------------------------------------------
    // 走位（上游 aA_ / aw#y 的轮询替代）
    // ---------------------------------------------------------------------------------------------

    /**
     * 上游 {@code aA_#a(BlockPos, int, State, String)}：够近就切状态，否则记下待恢复状态再走位。
     *
     * <p>上游这里有两处传 {@code range = 0}（检查备货、放空盒），也就是「几乎总要寻路、并且每次都发提示」；
     * 这里统一用 1 格容差，站在原地时不重复寻路、也不刷提示。
     *
     * @param messageKey 走位前的提示（{@code null} 表示不提示），与上游一致只在「距离不够」时发
     */
    private void walkTo(BlockPos target, State nextState, String messageKey) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || target == null) {
            return;
        }
        double distance = player.position().distanceTo(target.getCenter());
        if (distance > MAX_MOVE_DISTANCE) {
            this.warn("addons.lotus.messages.tooFar");
            this.disable();
            return;
        }
        if (distance <= STAND_DISTANCE) {
            this.state = nextState;
            this.pendingState = null;
            return;
        }
        if (messageKey != null) {
            this.info(messageKey);
        }
        this.pendingState = nextState;
        this.pathTo(target);
    }

    /** 上游 {@code aA_#a_m_String_be(m, msg, State)}：提示 + 关界面 + 朝格位的站位点出发。*/
    private void pathTo(SlotPos position, String messageKey, State nextState, Object... args) {
        this.info(messageKey, args);
        this.closeScreenNow();
        this.pendingState = nextState;
        this.pathTo(position.buttonPos());
    }

    /**
     * 上游 {@code aA_#a_BlockPos_int_toVoid}：Baritone 走位。
     *
     * <p>TODO(port): 上游监听 Baritone 的 {@code PathEvent.CANCELED} 来恢复状态，{@code BaritoneBridge}
     * 没有事件，这里改成每 tick 轮询 {@code isPathingOrCalculating()}。另外上游没有「重试上限」，
     * 这里加了一个：同一目标连续 {@link #MAX_PATH_ATTEMPTS} 次都没靠近就判定不可达并关掉模块。
     */
    private void pathTo(BlockPos target) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || target == null) {
            return;
        }
        double distance = player.position().distanceTo(target.getCenter());
        if (target.equals(this.lastPathTarget) && distance > this.lastPathDistance - 0.5D) {
            this.pathAttempts++;
        } else {
            this.pathAttempts = 0;
        }
        this.lastPathTarget = target.immutable();
        this.lastPathDistance = distance;
        if (this.pathAttempts > MAX_PATH_ATTEMPTS) {
            this.warn("addons.lotus.messages.pathFailed");
            this.disable();
            return;
        }
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            // TODO(port): 上游把 Baritone 当硬依赖（没装就加载失败）；这里没有走位能力就直接停。
            this.warn("addons.lotus.messages.baritoneUnavailable");
            this.disable();
            return;
        }
        bridge.pathTo(target);
        this.pathIdleTicks = 0;
        this.walkStartedAt = System.currentTimeMillis();
        this.state = State.WALKING;
    }

    /** 上游 {@code aA_#onPathEvent(PathEvent.CANCELED)} 的轮询替代：路径停下就恢复待执行状态。*/
    private void tickPathing() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            this.restorePendingState(State.NEXT);
            return;
        }
        BlockPos target = this.lastPathTarget;
        double distance = target == null ? Double.MAX_VALUE
                : player.position().distanceTo(target.getCenter());
        long elapsedTicks = (System.currentTimeMillis() - this.walkStartedAt) / 50L;
        boolean arrived = distance <= ARRIVE_DISTANCE;
        if (arrived || elapsedTicks > WALK_TIMEOUT_TICKS) {
            if (bridge.isPathingOrCalculating()) {
                bridge.cancelPathing();
            }
            this.restorePendingState(State.NEXT);
            return;
        }
        if (bridge.isPathingOrCalculating()) {
            this.pathIdleTicks = 0;
            return;
        }
        if (++this.pathIdleTicks < WALK_GRACE_TICKS) {
            return;
        }
        this.pathIdleTicks = 0;
        this.restorePendingState(State.NEXT);
    }

    private void restorePendingState(State fallback) {
        State pending = this.pendingState;
        this.pendingState = null;
        this.state = pending == null ? fallback : pending;
    }

    private void resetPathTracking() {
        this.pathIdleTicks = 0;
        this.pathAttempts = 0;
        this.lastPathTarget = null;
        this.lastPathDistance = Double.MAX_VALUE;
        this.walkStartedAt = 0L;
    }

    /**
     * 上游 {@code br#a_Vec3_toBlockPos}：给地上的掉落物找一个和玩家同层的落脚点。
     */
    private BlockPos standingPosForDrop(Vec3 dropPosition) {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return null;
        }
        int playerY = player.getBlockY();
        if (dropPosition.y < playerY - 0.75D || dropPosition.y > playerY + 2.3D) {
            return null;
        }
        Vec3 playerPosition = player.position();
        BlockPos center = BlockPos.containing(dropPosition);
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                BlockPos candidate = center.offset(offsetX, 0, offsetZ).atY(playerY);
                double deltaX = candidate.getX() + 0.5D - dropPosition.x;
                double deltaZ = candidate.getZ() + 0.5D - dropPosition.z;
                if (deltaX * deltaX + deltaZ * deltaZ > DROP_STAND_RADIUS_SQR) {
                    continue;
                }
                if (!this.canStandAt(candidate)) {
                    continue;
                }
                double distance = candidate.getCenter().distanceToSqr(playerPosition);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = candidate;
                }
            }
        }
        return best;
    }

    /** 上游 {@code br#a_BlockPos_toBoolean}：脚下空、头空、下面有碰撞。*/
    private boolean canStandAt(BlockPos position) {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return false;
        }
        if (!level.getBlockState(position).getCollisionShape(level, position).isEmpty()) {
            return false;
        }
        BlockPos above = position.above();
        if (!level.getBlockState(above).getCollisionShape(level, above).isEmpty()) {
            return false;
        }
        BlockPos below = position.below();
        return !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // 容器交互（上游 ag#a_BlockPos_Consumer + bp/bn）
    // ---------------------------------------------------------------------------------------------

    /**
     * 上游 {@code ag#a_BlockPos_Consumer}：没开就开；开错了 / 没同步就重试；同步好了再执行动作。
     */
    private void withOpenContainer(BlockPos position, Consumer<AbstractContainerMenu> action) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || position == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu) {
            this.openContainerAt(position);
            return;
        }
        if (!position.equals(this.lastContainerPos)) {
            // 目标换了但旧容器还开着：先关掉，下一 tick 再开新的（上游靠 CLOSE_SCREEN 状态保证）。
            this.closeScreenNow();
            this.resetDelay();
            return;
        }
        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) {
            this.openState = OpenState.CHECK;
            this.closeScreenNow();
            this.warn("addons.lotus.messages.wrongContainer");
            this.resetDelay();
            return;
        }
        if (menu.getStateId() <= 0) {
            if (System.currentTimeMillis() - this.lastOpenAt > CONTAINER_SYNC_TIMEOUT_MS) {
                this.warn("addons.lotus.messages.containerNotSynced");
                this.openState = OpenState.CHECK;
                this.lastContainerPos = null;
                this.closeScreenNow();
            }
            this.resetDelay();
            return;
        }
        this.openState = OpenState.CHECK;
        action.accept(menu);
    }

    /**
     * 上游 {@code ag#c(BlockPos, Direction)} + {@code bn#c_BlockPos_toVoid}：朝方块发一次右键。
     *
     * <p>这里没有直接用 {@link ContainerInteractionUtils#openContainer(BlockPos, boolean)}：它在
     * 「脚下方块离目标 &gt; 3」时会先调用 Baritone 走过去并 return。仓库的箱子在玩家脚上方 3 格
     * （脚到箱心 &gt; 3，但眼到箱心仍在 4.5 格交互距离内），走它那条分支会导致箱子永远打不开。
     * 关界面仍然走 {@link ContainerInteractionUtils#closeScreen()}。
     */
    private void openContainerAt(BlockPos position) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.openState != OpenState.CHECK && now - this.lastOpenAt <= OPEN_RETRY_WARN_MS) {
            this.warn("addons.lotus.messages.openNoResponse");
        } else {
            Vec3 center = Vec3.atCenterOf(position);
            Vec3 eye = player.getEyePosition();
            double deltaX = eye.x - center.x;
            double deltaY = eye.y - center.y;
            double deltaZ = eye.z - center.z;
            double absoluteX = Math.abs(deltaX);
            double absoluteY = Math.abs(deltaY);
            double absoluteZ = Math.abs(deltaZ);
            Direction face;
            if (absoluteX >= absoluteY && absoluteX >= absoluteZ) {
                face = deltaX >= 0.0D ? Direction.EAST : Direction.WEST;
            } else if (absoluteZ >= absoluteY) {
                face = deltaZ >= 0.0D ? Direction.SOUTH : Direction.NORTH;
            } else {
                face = deltaY >= 0.0D ? Direction.UP : Direction.DOWN;
            }
            Vec3 hitPoint = center.add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
            BlockHitResult hitResult = new BlockHitResult(hitPoint, face, position, false);
            if (ClientContext.rotationManager != null) {
                ClientContext.rotationManager.requestRotation(
                        RotationManager.calculateRotations(eye, hitPoint), 100);
            }
            PacketSender.sendUseItemOn(InteractionHand.MAIN_HAND, hitResult);
            PacketSender.sendSwing(InteractionHand.MAIN_HAND);
            this.openState = OpenState.OPENING;
            this.lastOpenAt = now;
            this.lastContainerPos = position.immutable();
        }
        this.resetDelay();
    }

    /** 上游 {@code bp#a()}：不是玩家背包界面就关掉。*/
    private void closeScreenNow() {
        LocalPlayer player = this.evernightMinecraft.player;
        this.lastContainerPos = null;
        this.openState = OpenState.CHECK;
        if (player == null || player.containerMenu instanceof InventoryMenu) {
            return;
        }
        ContainerInteractionUtils.closeScreen();
        if (!(player.containerMenu instanceof InventoryMenu)) {
            player.closeContainer();
        }
    }

    /** 当前菜单里属于「容器」的槽位数量（上游 {@code bp#g()} 的通用写法）。*/
    private static int containerSlotCount(AbstractContainerMenu menu, Inventory inventory) {
        int count = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != inventory) {
                count++;
            }
        }
        return count;
    }

    /** 上游 {@code ag#q()}：容器的槽位是否已经全满。*/
    private boolean isContainerFull(AbstractContainerMenu menu) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        int slots = containerSlotCount(menu, player.getInventory());
        if (slots <= 0) {
            return false;
        }
        for (int index = 0; index < slots; index++) {
            if (menu.getSlot(index).getItem().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // 背包 / 容器扫描（上游 ag#a_Function / b_Function / d_Predicate / e_Predicate）
    // ---------------------------------------------------------------------------------------------

    /** 上游 {@code ag#a_Predicate}：从轮转游标开始遍历背包，返回匹配物品的副本。*/
    private ItemStack findInInventory(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            this.inventoryCursor = (this.inventoryCursor + 1) % INVENTORY_SIZE;
            ItemStack stack = inventory.getItem(this.inventoryCursor);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 上游 {@code ag#c_Predicate}：从轮转游标开始遍历背包里的潜影盒。*/
    private BoxContents findBoxInInventory(Predicate<BoxContents> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        Inventory inventory = player.getInventory();
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            this.inventoryCursor = (this.inventoryCursor + 1) % INVENTORY_SIZE;
            ItemStack stack = inventory.getItem(this.inventoryCursor);
            if (!ShulkerBoxUtils.isShulkerBoxItem(stack.getItem())) {
                continue;
            }
            BoxContents box = new BoxContents(stack);
            if (predicate.test(box)) {
                return box;
            }
        }
        return null;
    }

    /** 上游 {@code ag#d_Predicate}：从轮转游标开始遍历已打开容器的槽位。*/
    private ItemStack findInContainer(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int slots = containerSlotCount(menu, player.getInventory());
        if (slots <= 0) {
            return ItemStack.EMPTY;
        }
        for (int index = 0; index < slots; index++) {
            this.containerCursor = (this.containerCursor + 1) % slots;
            ItemStack stack = menu.getSlot(this.containerCursor).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 上游 {@code ag#e_Predicate}：从轮转游标开始遍历已打开容器里的潜影盒。*/
    private BoxContents findBoxInContainer(Predicate<BoxContents> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int slots = containerSlotCount(menu, player.getInventory());
        if (slots <= 0) {
            return null;
        }
        for (int index = 0; index < slots; index++) {
            this.containerCursor = (this.containerCursor + 1) % slots;
            ItemStack stack = menu.getSlot(this.containerCursor).getItem();
            if (!ShulkerBoxUtils.isShulkerBoxItem(stack.getItem())) {
                continue;
            }
            BoxContents box = new BoxContents(stack);
            if (predicate.test(box)) {
                return box;
            }
        }
        return null;
    }

    /** 上游 {@code InvUtils.shiftClick().slot(index)}：把背包槽 shift 进容器。*/
    private void shiftClickInventory(int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || inventorySlot < 0) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int menuSlot = menuSlotForInventory(menu, player, inventorySlot);
        if (menuSlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(menu.containerId, menuSlot, 0, ClickType.QUICK_MOVE);
    }

    /** 上游 {@code InvUtils.shiftClick().slotId(id)}：把容器槽 shift 回背包。*/
    private void shiftClickContainer(int containerSlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || containerSlot < 0) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        InventoryUtils.clickSlot(menu.containerId, containerSlot, 0, ClickType.QUICK_MOVE);
    }

    /** 背包槽位 → 当前菜单槽位（不依赖菜单类型，按容器定位）。*/
    private static int menuSlotForInventory(AbstractContainerMenu menu, Player player, int inventorySlot) {
        Inventory inventory = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == inventorySlot) {
                return slot.index;
            }
        }
        return -1;
    }

    /**
     * 把背包第 {@code inventorySlot} 格的方块拿在手上，在 {@code position} 放下去，然后关掉界面。
     *
     * <p>TODO(port): 上游 {@code bp#a(int, Runnable)} 在「主手不空」时会先找一个空快捷栏格中转，
     * 再和主手交换；这里用 {@link InventoryUtils#swapIntoSelectedSlot(int)} 直接换到选中的快捷栏格，
     * 少了一次中转（物品栏满时都能用，只是槽位落点不同）。
     */
    private void placeHeldBlock(BlockPos position, int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || inventorySlot < 0) {
            return;
        }
        if (!(player.containerMenu instanceof InventoryMenu)) {
            // 背包下标 → 菜单槽位的换算只在玩家背包菜单下成立，先关界面再放。
            this.closeScreenNow();
            this.resetDelay();
            return;
        }
        this.selectForPlacement(inventorySlot);
        BlockInteractionUtils.placeBlock(position, false, InteractionHand.MAIN_HAND);
        this.closeScreenNow();
    }

    /** 上游 {@code bp#a(int, Runnable)} 的「保证这一格在手上」。*/
    private void selectForPlacement(int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || inventorySlot < 0) {
            return;
        }
        if (player.getInventory().getSelectedSlot() == inventorySlot) {
            return;
        }
        if (inventorySlot < HOTBAR_SIZE) {
            InventoryUtils.selectHotbarSlot(inventorySlot);
        } else {
            InventoryUtils.swapIntoSelectedSlot(inventorySlot);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 初始化（上游 af#a_Boolean / P / br#a / U_#c）
    // ---------------------------------------------------------------------------------------------

    /**
     * 上游 {@code af#a_Boolean(TRUE)}：重置 → 扫展示框 → 读散装分类 → 取两个标识 → 统计投影材料。
     *
     * @return 是否初始化成功（上游靠 {@code x}/{@code y} 是否为空来判断，这里显式返回）
     */
    private boolean initialize() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            this.warn("addons.lotus.messages.worldUnavailable");
            return false;
        }
        this.resetWarehouse();
        // 上游 a_Boolean 里的 this.F = 0。
        this.checkPhase = 0;

        if (!litematicaLoaded()) {
            this.warn("addons.lotus.messages.litematicaUnavailable");
            return false;
        }

        Map<ItemKey, SlotPos> frames = this.scanSlots();
        Map<Item, BulkCategory> categories = this.loadBulkCategories();

        // 上游 v：每个散装分类的目标物品所在格位，把它的关联物品都指过去。
        this.relatedSlots.clear();
        for (BulkCategory category : categories.values()) {
            if (!category.enabled()) {
                continue;
            }
            SlotPos slot = frames.get(new ItemKey(category.target()));
            if (slot == null) {
                continue;
            }
            for (Item related : category.related()) {
                this.relatedSlots.put(related, slot);
            }
        }

        this.emptyBoxSlot = this.takeMarker(frames, this.emptyKitItemsSetting);
        this.stockSlot = this.takeMarker(frames, this.stockKitItemsSetting);
        if (this.emptyBoxSlot == null) {
            this.warn("addons.lotus.messages.missingEmptyKitFrame", this.markerNames(this.emptyKitItemsSetting));
            return false;
        }
        if (this.stockSlot == null) {
            this.warn("addons.lotus.messages.missingStockKitFrame", this.markerNames(this.stockKitItemsSetting));
            return false;
        }

        // 上游 l：剩下的展示框里，排除「散装分类目标物品」的框。
        this.itemSlots.clear();
        for (Map.Entry<ItemKey, SlotPos> entry : frames.entrySet()) {
            if (!categories.containsKey(entry.getKey().item())) {
                this.itemSlots.put(entry.getKey(), entry.getValue());
            }
        }
        if (this.itemSlots.isEmpty()) {
            this.warn("addons.lotus.messages.missingItemFrame");
            return false;
        }

        if (!SchematicMaterials.hasActivePlacement()) {
            this.warn("addons.lotus.messages.noActiveSchematic");
            return false;
        }
        String placementName = SchematicMaterials.activePlacementName();
        Map<ItemKey, Integer> materials;
        try {
            materials = SchematicMaterials.collectRequiredMaterials(player);
        } catch (RuntimeException exception) {
            this.warn("addons.lotus.messages.initializeError", describeThrowable(exception));
            return false;
        }
        if (materials == null) {
            this.warn("addons.lotus.messages.schematicWorldNotLoaded");
            return false;
        }
        if (materials.isEmpty()) {
            this.warn("addons.lotus.messages.schematicNoMaterials",
                    placementName == null ? "?" : placementName);
            return false;
        }

        // 上游 y：按需求量升序（从需求量最少的物品开始备货）。
        List<Map.Entry<ItemKey, Integer>> sorted = new ArrayList<>(materials.entrySet());
        sorted.sort((left, right) -> Integer.compare(left.getValue(), right.getValue()));
        this.required.clear();
        for (Map.Entry<ItemKey, Integer> entry : sorted) {
            this.required.put(entry.getKey(), entry.getValue());
        }

        List<String> names = new ArrayList<>(this.required.size());
        for (ItemKey key : this.required.keySet()) {
            boolean noSlot = !this.itemSlots.containsKey(key) && !this.relatedSlots.containsKey(key.item());
            names.add(noSlot ? key.displayName() + "(缺位置)" : key.displayName());
        }
        this.info("addons.lotus.messages.schematicMaterials", String.join(", ", names));
        this.info("addons.lotus.messages.initializeDone");
        return true;
    }

    /** 上游 {@code af#P()}：清空仓库索引与本次备货的进度。*/
    private void resetWarehouse() {
        this.itemSlots.clear();
        this.relatedSlots.clear();
        this.required.clear();
        this.alreadyTaken.clear();
        this.skipped.clear();
        this.emptyBoxSlot = null;
        this.stockSlot = null;
        this.currentKey = null;
        this.currentSlot = null;
        this.boxToBreak = null;
    }

    /**
     * 上游 {@code br#a(int, int)}：扫展示框，按「框里物品」建立「箱子 / 卸货点 / 放盒格 / 按钮」。
     *
     * <p>这里逐行照抄原始偏移（含活塞与按钮的判定），并把「框方块 -2Y 的 Y 必须等于玩家 Y」也照抄。
     */
    private Map<ItemKey, SlotPos> scanSlots() {
        Map<ItemKey, SlotPos> slots = new HashMap<>();
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return slots;
        }
        int range = this.scanRangeSetting.getValue();
        BlockPos base = player.blockPosition();
        AABB area = new AABB(
                base.getX() - range, base.getY(), base.getZ() - range,
                base.getX() + range, base.getY() + SCAN_HEIGHT, base.getZ() + range);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, area,
                frame -> !frame.getItem().isEmpty());
        Vec3 playerPosition = player.position();
        int playerY = base.getY();

        for (ItemFrame frame : frames) {
            ItemKey key = new ItemKey(frame.getItem());
            BlockPos framePos = frame.blockPosition();
            BlockPos supportPos = frame.getPos();
            Direction viewDirection = frame.getNearestViewDirection();
            BlockPos chestPos = supportPos.offset(0, 1, 0).relative(viewDirection.getOpposite());
            if (level.getBlockState(chestPos).getBlock() != Blocks.CHEST) {
                continue;
            }
            BlockPos kitPos = framePos.offset(0, -2, 0);
            BlockState kitState = level.getBlockState(kitPos);
            if (!kitState.isAir() && !(kitState.getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }
            BlockPos pistonPos = kitPos.offset(0, -1, 0);
            if (level.getBlockState(pistonPos).getBlock() != Blocks.PISTON || kitPos.getY() != playerY) {
                continue;
            }
            BlockPos buttonPos = kitPos.relative(viewDirection);
            if (!(level.getBlockState(buttonPos).getBlock() instanceof ButtonBlock)) {
                continue;
            }
            SlotPos slot = new SlotPos(key, framePos, chestPos, chestPos.offset(0, -2, 0), kitPos, buttonPos);
            SlotPos existing = slots.get(key);
            if (existing == null) {
                slots.put(key, slot);
            } else {
                this.warn("addons.lotus.messages.duplicateFrame", key.displayName());
                if (slot.buttonPos().getCenter().distanceTo(playerPosition)
                        < existing.buttonPos().getCenter().distanceTo(playerPosition)) {
                    slots.put(key, slot);
                }
            }
        }
        return slots;
    }

    /** 上游 {@code br#a} 里的 {@code map.remove(标识)}：取走匹配的格位（多个时取最近的）。*/
    private SlotPos takeMarker(Map<ItemKey, SlotPos> frames, Setting<Set<Item>> marker) {
        LocalPlayer player = this.evernightMinecraft.player;
        Vec3 playerPosition = player != null ? player.position() : null;
        SlotPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (SlotPos candidate : frames.values()) {
            if (!matchesMarker(candidate.key(), marker)) {
                continue;
            }
            double distance = playerPosition == null
                    ? 0.0D
                    : playerPosition.distanceTo(candidate.buttonPos().getCenter());
            if (best == null || distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
            }
        }
        if (best != null) {
            frames.remove(best.key());
        }
        return best;
    }

    /** 「空盒 / 备货」标识在 Meteor 里是单物品，这里是集合：任意一个选中物品都算。*/
    private static boolean matchesMarker(ItemKey key, Setting<Set<Item>> marker) {
        for (Item item : marker.getValue()) {
            if (new ItemKey(item).equals(key)) {
                return true;
            }
        }
        return false;
    }

    private String markerNames(Setting<Set<Item>> marker) {
        List<String> names = new ArrayList<>();
        for (Item item : marker.getValue()) {
            names.add(new ItemStack(item).getHoverName().getString());
        }
        return names.isEmpty() ? "?" : String.join(", ", names);
    }

    /**
     * 上游 {@code U_#c()} 的等价物：读散装物品分类（{@code {"目标物品id":["关联物品id", ...]}}）。
     *
     * <p>TODO(port): 上游是直接查「简易仓库」模块（{@code U_}）里由 GUI 维护的内存表；
     * 本插件没有那个模块，改为读同一份 JSON 配置（上游「导入配置」按钮读的就是它）。
     * 没配置时关联物品为空，效果与「不做散装分类」相同。
     */
    private Map<Item, BulkCategory> loadBulkCategories() {
        Map<Item, BulkCategory> categories = new LinkedHashMap<>();
        Path file = this.bulkConfigPath();
        if (!Files.exists(file)) {
            this.warn("addons.lotus.messages.configMissing", file);
            return categories;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            for (String targetId : root.keySet()) {
                Item target = parseBulkItem(targetId);
                if (target == null) {
                    this.warn("addons.lotus.messages.unknownTargetItem", targetId);
                    continue;
                }
                Set<Item> related = new LinkedHashSet<>();
                JsonElement element = root.get(targetId);
                if (element != null && element.isJsonArray()) {
                    for (JsonElement relatedElement : element.getAsJsonArray()) {
                        String relatedId = relatedElement.getAsString();
                        Item relatedItem = parseBulkItem(relatedId);
                        if (relatedItem == null) {
                            this.warn("addons.lotus.messages.unknownRelatedItem", relatedId);
                        } else {
                            related.add(relatedItem);
                        }
                    }
                }
                categories.put(target, new BulkCategory(target, related, true));
            }
        } catch (IOException | RuntimeException failure) {
            this.warn("addons.lotus.messages.configLoadFailed", file);
            return categories;
        }
        this.info("addons.lotus.messages.configLoaded", categories.size());
        return categories;
    }

    /**
     * 相对路径基于 {@code <游戏目录>/Lotus}（与「自动分类」读配置的位置一致，让老仓库配置照旧可用），
     * 绝对路径直接用（{@link Path#resolve(String)} 的行为）。
     */
    private Path bulkConfigPath() {
        String value = this.bulkConfigSetting.getValue();
        Path base = this.evernightMinecraft.gameDirectory.toPath().resolve("Lotus");
        return value == null || value.isBlank() ? base.resolve("warehouse/mappings.json") : base.resolve(value);
    }

    private static Item parseBulkItem(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(identifier).map(holder -> holder.value()).orElse(null);
        return item == null || item == Items.AIR ? null : item;
    }

    // ---------------------------------------------------------------------------------------------
    // 数量 / 距离工具
    // ---------------------------------------------------------------------------------------------

    /** 上游 {@code af#N()}：按堆叠上限算出还需要几「堆」。*/
    private int boxesNeeded() {
        int remaining = this.remainingAmount();
        if (remaining <= 0 || this.currentKey == null) {
            return 0;
        }
        int maxStack = this.currentKey.item().getDefaultMaxStackSize();
        if (maxStack <= 0) {
            return 0;
        }
        return (remaining - 1) / maxStack + 1;
    }

    /** 上游 {@code af#O()}：需求数量减去本轮已取数量。*/
    private int remainingAmount() {
        if (this.currentKey == null) {
            return 0;
        }
        return this.required.getOrDefault(this.currentKey, 0)
                - this.alreadyTaken.getOrDefault(this.currentKey, 0);
    }

    /** 上游 {@code af#a_g_int}：把某项的需求量减掉已经存进备货箱的那一盒。*/
    private void reduceRequirement(ItemKey key, int amount) {
        Integer current = this.required.get(key);
        if (current == null) {
            return;
        }
        if (current <= amount) {
            this.required.remove(key);
        } else {
            this.required.put(key, current - amount);
        }
    }

    /** 上游 {@code af#b()} 的等价物：离格位的按钮（站位参照点）超过 1 格。*/
    private boolean isFar(SlotPos position) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || position == null) {
            return true;
        }
        return player.position().distanceTo(position.buttonPos().getCenter()) > STAND_DISTANCE;
    }

    private void resetDelay() {
        this.delayTicks = this.delaySetting.getValue();
    }

    private void setDelay(int ticks) {
        this.delayTicks = Math.max(0, ticks);
    }

    private static String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------------------------------------------
    // 文本（玩家可见文本全部走语言表）
    // ---------------------------------------------------------------------------------------------

    private void info(String translationKey, Object... args) {
        String pattern = Translations.get(translationKey);
        String message = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
    }

    /**
     * 上游 Meteor 的 warning 有红色前缀，Evernight 只有一种聊天签名。
     *
     * <p>TODO(port): 若要区分 warning 颜色，需要本体提供带样式的输出接口；目前统一用模块签名。
     */
    private void warn(String translationKey, Object... args) {
        this.info(translationKey, args);
    }

    // ---------------------------------------------------------------------------------------------
    // 内部数据结构（上游 g / by / m / l / aC_ 的等价物）
    // ---------------------------------------------------------------------------------------------

    /**
     * 上游 {@code g}：物品身份。包含药水 / 附魔书 / 地图 / 不祥之瓶的组件，避免把不同药水当成同一种。
     *
     * <p>上游的 {@code g} 还带一个「是否带自定义名」的开关，但本模块从不用带名字的比较
     * （展示框 key 都用 {@code new g(stack)}），所以这里省略自定义名。
     */
    private static final class ItemKey {
        private final Item item;
        private final ResourceKey<Enchantment> enchantment;
        private final PotionContents potion;
        private final Integer mapId;
        private final OminousBottleAmplifier ominous;

        private ItemKey(Item item) {
            this.item = item;
            this.enchantment = null;
            this.potion = null;
            this.mapId = null;
            this.ominous = null;
        }

        private ItemKey(ItemStack stack) {
            this.item = stack.getItem();
            this.enchantment = this.item == Items.ENCHANTED_BOOK ? dominantEnchantment(stack) : null;
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            this.potion = contents != null && !contents.equals(PotionContents.EMPTY) ? contents : null;
            MapId id = stack.get(DataComponents.MAP_ID);
            this.mapId = id != null ? id.id() : null;
            this.ominous = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
        }

        /** 上游 {@code bm#b_ItemStack_toResourceKey}：附魔书取「满级的那一条」附魔。*/
        private static ResourceKey<Enchantment> dominantEnchantment(ItemStack stack) {
            ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
            for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
                Optional<ResourceKey<Enchantment>> key = entry.getKey().unwrapKey();
                if (key.isPresent() && entry.getIntValue() == entry.getKey().value().getMaxLevel()) {
                    return key.get();
                }
            }
            return null;
        }

        private Item item() {
            return this.item;
        }

        /** 上游 {@code g#b_ItemStack_toBoolean}：是不是同一种物品（含组件）。*/
        private boolean matches(ItemStack stack) {
            if (stack == null || stack.isEmpty() || stack.getItem() != this.item) {
                return false;
            }
            if (this.item == Items.ENCHANTED_BOOK) {
                return Objects.equals(this.enchantment, dominantEnchantment(stack));
            }
            PotionContents contents = stack.get(DataComponents.POTION_CONTENTS);
            PotionContents otherPotion = contents != null && !contents.equals(PotionContents.EMPTY)
                    ? contents : null;
            if (!Objects.equals(this.potion, otherPotion)) {
                return false;
            }
            MapId id = stack.get(DataComponents.MAP_ID);
            Integer otherMapId = id != null ? id.id() : null;
            if (!Objects.equals(this.mapId, otherMapId)) {
                return false;
            }
            return Objects.equals(this.ominous, stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER));
        }

        /** 上游 {@code g#e()}：消息里显示的名字。*/
        private String displayName() {
            if (this.item == Items.ENCHANTED_BOOK && this.enchantment != null) {
                Level level = ClientAccess.evernightMinecraft.level;
                if (level != null) {
                    Enchantment found = level.registryAccess().lookup(Registries.ENCHANTMENT)
                            .map(registry -> registry.getValue(this.enchantment)).orElse(null);
                    if (found != null) {
                        return found.description().getString();
                    }
                }
                return this.enchantment.identifier().getPath();
            }
            return new ItemStack(this.item).getHoverName().getString();
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ItemKey key)) {
                return false;
            }
            return this.item == key.item
                    && Objects.equals(this.enchantment, key.enchantment)
                    && Objects.equals(this.potion, key.potion)
                    && Objects.equals(this.mapId, key.mapId)
                    && Objects.equals(this.ominous, key.ominous);
        }

        @Override
        public int hashCode() {
            return Objects.hash(this.item, this.enchantment, this.potion, this.mapId, this.ominous);
        }
    }

    /** 上游 {@code by}：潜影盒内容。本模块只关心「有哪些物品、各多少」。*/
    private static final class BoxContents {
        private final ItemStack stack;
        private List<ItemStack> items;
        private Map<ItemKey, Integer> counts;

        private BoxContents(ItemStack stack) {
            this.stack = stack;
        }

        private List<ItemStack> items() {
            if (this.items == null) {
                List<ItemStack> found = new ArrayList<>();
                ItemContainerContents contents = this.stack.get(DataComponents.CONTAINER);
                if (contents != null) {
                    for (ItemStack inner : contents.nonEmptyItems()) {
                        if (!inner.isEmpty()) {
                            found.add(inner);
                        }
                    }
                }
                this.items = found;
            }
            return this.items;
        }

        /** 上游 {@code by#a()}。*/
        private boolean isEmpty() {
            return this.items().isEmpty();
        }

        /** 上游 {@code by#i()}。*/
        private Map<ItemKey, Integer> counts() {
            if (this.counts == null) {
                Map<ItemKey, Integer> found = new LinkedHashMap<>();
                for (ItemStack inner : this.items()) {
                    found.merge(new ItemKey(inner), inner.getCount(), Integer::sum);
                }
                this.counts = found;
            }
            return this.counts;
        }

        /** 上游 {@code by#b()}：最后一种非空物品（单种类时就是它）。*/
        private ItemKey dominantKey() {
            ItemKey key = new ItemKey(Items.AIR);
            for (ItemStack inner : this.items()) {
                key = new ItemKey(inner);
            }
            return key;
        }

        /** 盒子里是否有这一种物品（上游 {@code c_Predicate} 里逐项比较的写法）。*/
        private boolean contains(ItemKey wanted) {
            return this.counts().containsKey(wanted);
        }

        /** 盒子里是否有任意一项在给定集合里（上游 {@code D()} 的谓词）。*/
        private boolean hasAnyKey(Set<ItemKey> keys) {
            for (ItemKey key : this.counts().keySet()) {
                if (keys.contains(key)) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * 上游 {@code m}：一个展示框对应的五个位置。
     *
     * <p>字段对应上游访问器：{@code framePos = b()}、{@code chestPos = c()}、
     * {@code belowChestPos = d()}、{@code kitPos = e()}、{@code buttonPos = f()}。
     * {@code framePos} 在原模块里也没有被读取，留着是为了和 {@code m} 对齐。
     */
    private record SlotPos(ItemKey key, BlockPos framePos, BlockPos chestPos,
                           BlockPos belowChestPos, BlockPos kitPos, BlockPos buttonPos) {
    }

    /** 上游 {@code aC_}：一条散装物品分类（只保留本模块用到的字段）。*/
    private record BulkCategory(Item target, Set<Item> related, boolean enabled) {
    }

    /**
     * Litematica 材料清单（上游 {@code af#a_SchematicPlacement}）。
     *
     * <p>Litematica 是<b>运行期可选</b>依赖：这个嵌套类只在 {@link AutoStockModule#litematicaLoaded()}
     * 为 true 时才会被调用 / 加载，所以没装 Litematica 的玩家不会因为缺类而崩（外层类不把任何
     * Litematica 类型放进字段、方法签名或构造器）。
     */
    private static final class SchematicMaterials {
        private SchematicMaterials() {
        }

        /** 当前选中的投影是否存在且启用。*/
        private static boolean hasActivePlacement() {
            SchematicPlacement placement = DataManager.getSchematicPlacementManager()
                    .getSelectedSchematicPlacement();
            return placement != null && placement.isEnabled();
        }

        /** 当前选中投影的名字；没有时返回 {@code null}。*/
        private static String activePlacementName() {
            SchematicPlacement placement = DataManager.getSchematicPlacementManager()
                    .getSelectedSchematicPlacement();
            return placement == null ? null : placement.getName();
        }

        /**
         * 上游 {@code af#a_SchematicPlacement}：遍历当前投影启用的子区域，统计方块并换算成材料清单。
         *
         * <p>TODO(port): 只统计「投影世界里已经加载」的方块（上游也一样），离得很远的投影可能统计不全；
         * 投影特别大时这一步在主线程跑会有卡顿，与上游一致。
         *
         * @return 材料 → 需求量；投影世界未加载时返回 {@code null}
         */
        private static Map<ItemKey, Integer> collectRequiredMaterials(Player player) {
            SchematicPlacement placement = DataManager.getSchematicPlacementManager()
                    .getSelectedSchematicPlacement();
            if (placement == null) {
                return null;
            }
            WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
            if (schematicWorld == null) {
                return null;
            }

            Object2IntOpenHashMap<BlockState> blockCounts = new Object2IntOpenHashMap<>();
            BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
            for (Box box : placement.getSubRegionBoxes(RequiredEnabled.PLACEMENT_ENABLED).values()) {
                BlockPos pos1 = box.getPos1();
                BlockPos pos2 = box.getPos2();
                if (pos1 == null || pos2 == null) {
                    continue;
                }
                int minX = Math.min(pos1.getX(), pos2.getX());
                int minY = Math.min(pos1.getY(), pos2.getY());
                int minZ = Math.min(pos1.getZ(), pos2.getZ());
                int maxX = Math.max(pos1.getX(), pos2.getX());
                int maxY = Math.max(pos1.getY(), pos2.getY());
                int maxZ = Math.max(pos1.getZ(), pos2.getZ());
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        for (int x = minX; x <= maxX; x++) {
                            cursor.set(x, y, z);
                            BlockState state = schematicWorld.getBlockState(cursor);
                            if (state != null && !state.isAir()) {
                                blockCounts.addTo(state, 1);
                            }
                        }
                    }
                }
            }

            List<MaterialListEntry> entries = MaterialListUtils.getMaterialList(
                    blockCounts, blockCounts.clone(), new Object2IntOpenHashMap<BlockState>(), player);
            Map<ItemKey, Integer> materials = new LinkedHashMap<>();
            for (MaterialListEntry entry : entries) {
                materials.merge(new ItemKey(entry.getStack()), entry.getCountTotal(), Integer::sum);
            }
            return materials;
        }
    }
}
