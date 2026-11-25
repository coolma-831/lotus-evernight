package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.integrations.baritone.BaritoneBridge;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.world.BlockInteractionUtils;
import evernight.client.utils.world.WorldUtils;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code Z_} (S自动Kit) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>在一个「简易仓库」里自动装配 Kit：初始化时扫描玩家附近的展示框，识别出「空盒 / 成品盒 / 杂盒」
 * 三类盒子所在的仓库格位，并读取主手潜影盒作为模板；之后按模板分批取出物品、放进空潜影盒，装满后挖掉
 * 盒子收进成品箱，循环往复。原模块依赖 Meteor 的交互工具（{@code ag}/{@code bn}/{@code bp}）、
 * Baritone 的 {@code GoalNear} 与 {@code PathEvent}，以及「简易仓库」模块（{@code U_}）里的 Kit 定义表。
 *
 * <p><b>Evernight 1.0.3 的对应物</b>（全部用 {@code javap} 核对过发行 jar）：
 * <ul>
 *     <li>走位：{@code ClientContext.baritoneBridge}（{@code BaritoneBridge#isAvailable / pathTo /
 *     cancelPathing / isPathingOrCalculating}）。原来的 {@code GoalNear(pos, 0)} 与 {@code pathTo} 的
 *     {@code GoalBlock} 语义一致（本模块所有路径的容差都是 0）。</li>
 *     <li>开箱/关箱：{@code ContainerInteractionUtils.openContainer(BlockPos, boolean)} /
 *     {@code closeScreen()}；背包与容器点击：{@code InventoryUtils.clickSlot / selectHotbarSlot /
 *     swapWithHotbarSlot / findInventorySlot}。</li>
 *     <li>放盒子：{@code BlockInteractionUtils.placeBlock(BlockPos, boolean, InteractionHand)}；
 *     挖盒子：直接驱动 {@code MultiPlayerGameMode#startDestroyBlock / continueDestroyBlock}。</li>
 *     <li>潜影盒内容：{@code DataComponents.CONTAINER} 的 {@code ItemContainerContents#copyInto}
 *     （不再需要 Meteor 的 {@code ContainerComponentAccessor} mixin）。</li>
 * </ul>
 *
 * <p><b>与原模块的差异（全部留有 TODO(port)）</b>：
 * <ul>
 *     <li>Kit 定义来源：原模块从「简易仓库」模块（{@code U_}）的内存表读取「展示框物品 → 该盒物品集合」，
 *     那张表由 Meteor 自绘 GUI 维护。按要求本文件不含任何 GUI，且「简易仓库」模块尚未移植，因此这里退化为
 *     「展示框上的物品 == 该盒子存放的物品」，只支持一框一物的仓库布局。</li>
 *     <li>Baritone 的 {@code allowBreak / allowPlace} 在模块启用期间被原插件强制关闭；{@code BaritoneBridge}
 *     没有对应设置访问口，未禁用。</li>
 *     <li>原模块用 Meteor {@code Rotations} 给开箱/挖盒/放置加静默转身；这里只使用
 *     {@code openContainer(pos, true)} 自带的旋转，挖盒与放置不额外转身。</li>
 *     <li>原基类的「快速停止键」（Meteor {@code KeybindSetting}）与「重启延时」（本模块从未用到）没有移植：
 *     Evernight 1.0.3 没有设置级键位，需要时请给模块本身绑一个开关键。</li>
 *     <li>{@code PathEvent.CANCELED} 没有桥接事件，改成每 tick 轮询
 *     {@code isPathingOrCalculating()}（到达与取消都会恢复待执行状态）。</li>
 *     <li>上游警告/错误用聊天框红色前缀；Evernight 只有一种聊天签名，所有提示都走模块签名。</li>
 *     <li>模板里空槽（上游会 {@code add(null)}，随后在放物品时 NPE）这里直接跳过，算是一处修正。</li>
 * </ul>
 */
public final class AutoKitModule extends AddonModule {
    /** 玩家背包槽位数量（0-8 快捷栏，9-35 主背包），与上游 {@code bp.b} 一致。*/
    private static final int INVENTORY_SIZE = 36;
    /** 潜影盒 / 单箱 / 末影箱的槽位数量。*/
    private static final int KIT_SLOT_COUNT = 27;
    /** 上游 {@code aA_.a(...)} 里写死的最大步行距离。*/
    private static final double MAX_WALK_DISTANCE = 1000.0D;
    /** 上游 {@code d()}/{@code a()} 的距离阈值（{@code a_m(m)} 里的 1.0）。*/
    private static final double REACH_DISTANCE = 1.0D;
    /** 容器打开后等待服务端同步 stateId 的上限，对应上游 {@code ag} 的 2000ms。*/
    private static final long CONTAINER_SYNC_TIMEOUT_MS = 2000L;
    /** 重复发送开箱包的最小间隔，避免服务端没响应时每 tick 发一次。*/
    private static final long OPEN_THROTTLE_MS = 500L;
    /** 路径停下后等待多少 tick 再恢复待执行状态。*/
    private static final int PATH_IDLE_TICKS = 10;
    /** 同一目标连续重试这么多次仍未靠近就放弃（防止不可达目标死循环）。*/
    private static final int MAX_PATH_ATTEMPTS = 20;
    /** 上游 {@code aB_.a}：能挖潜影盒的镐子。*/
    private static final Set<Item> PICKAXES = Set.of(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);

    /** 上游 {@code bq.a_Item_toBoolean}：17 种潜影盒。*/
    private static final Set<Item> SHULKER_BOXES = Set.of(
            Items.SHULKER_BOX,
            Items.WHITE_SHULKER_BOX,
            Items.ORANGE_SHULKER_BOX,
            Items.MAGENTA_SHULKER_BOX,
            Items.LIGHT_BLUE_SHULKER_BOX,
            Items.YELLOW_SHULKER_BOX,
            Items.LIME_SHULKER_BOX,
            Items.PINK_SHULKER_BOX,
            Items.GRAY_SHULKER_BOX,
            Items.LIGHT_GRAY_SHULKER_BOX,
            Items.CYAN_SHULKER_BOX,
            Items.PURPLE_SHULKER_BOX,
            Items.BLUE_SHULKER_BOX,
            Items.BROWN_SHULKER_BOX,
            Items.GREEN_SHULKER_BOX,
            Items.RED_SHULKER_BOX,
            Items.BLACK_SHULKER_BOX);

    /** 状态机：对应上游 {@code bf} 里本模块用到的那些常量。*/
    private enum State {
        /** 上游 {@code bf.a}：空闲（出错的占位状态）。*/
        NONE,
        /** 上游 {@code bf.g}：Baritone 走位中。*/
        WALKING,
        /** 上游 {@code bf.c}：先关界面再切到待执行状态。*/
        CLOSE_SCREEN,
        /** 上游 {@code bf.h}：检查身上/地上的 Kit 与下一批物品。*/
        NEXT,
        /** 上游 {@code bf.A}：去空盒箱拿空潜影盒。*/
        TAKE_EMPTY_KIT,
        /** 上游 {@code bf.B}：把空潜影盒放到成品格。*/
        PLACE_EMPTY_KIT,
        /** 上游 {@code bf.t}：去 Kit 盒拿模板物品。*/
        TAKE_ITEM,
        /** 上游 {@code bf.p}：把 Kit 盒放到物品格上（空位先放盒子）。*/
        PLACE_KIT,
        /** 上游 {@code bf.m}：从 Kit 盒里拿一个装有所需物品的 Kit。*/
        TAKE_KIT,
        /** 上游 {@code bf.w}：把物品放进成品格的潜影盒。*/
        PUT_ITEM,
        /** 上游 {@code bf.n}：把装好的 Kit 存进成品箱。*/
        PUT_KIT,
        /** 上游 {@code bf.o}：把不匹配模板的 Kit 存进杂盒箱。*/
        PUT_MISC_KIT,
        /** 上游 {@code bf.D}：把空潜影盒存回空盒箱。*/
        PUT_EMPTY_KIT,
        /** 上游 {@code bf.q}：挖掉落地的 Kit 盒。*/
        BREAK_KIT
    }

    /** 模板里的一格：物品签名 + 期望数量。*/
    private record TemplateSlot(KitKey key, int count) {
        /** 比较盒子里的实际内容（数量和模板完全一致才算匹配）。*/
        private boolean matches(ItemStack stack) {
            return !stack.isEmpty() && this.key.matches(stack) && stack.getCount() == this.count;
        }

        /** 上游 {@code a_h_ItemStack}：背包物品只要数量是模板数量的整数倍就能分装。*/
        private boolean isSource(ItemStack stack) {
            return !stack.isEmpty() && this.key.matches(stack)
                    && this.count > 0 && stack.getCount() % this.count == 0;
        }
    }

    /**
     * 物品签名:对应上游 {@code g}。原类还比较自定义名、附魔、药水、地图编号与不祥之瓶等级,
     * 1.21 的 {@link ItemStack#isSameItemSameComponents} 覆盖同一批数据组件。
     */
    private record KitKey(ItemStack stack) {
        private KitKey {
            stack = stack.copyWithCount(1);
        }

        private boolean matches(ItemStack candidate) {
            return !candidate.isEmpty() && ItemStack.isSameItemSameComponents(this.stack, candidate);
        }

        private String describe() {
            return this.stack.getHoverName().getString();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof KitKey key && ItemStack.isSameItemSameComponents(this.stack, key.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(this.stack);
        }
    }

    /**
     * 仓库格位：字段顺序与上游 {@code m} 一致。
     *
     * @param framePos     展示框所在方块
     * @param chestPos     展示框上方的箱子（存成品/杂盒）
     * @param takeChestPos 再往下两格的箱子（取 Kit/空盒）
     * @param kitPos       展示框往下两格的放盒格
     * @param pathPos      走位目标（放盒格朝展示框正面两格）
     */
    private record KitPos(BlockPos framePos, BlockPos chestPos, BlockPos takeChestPos, BlockPos kitPos,
                          BlockPos pathPos) {
    }

    private final Setting<Integer> scanRangeSetting;
    private final Setting<Integer> operationCountSetting;
    private final Setting<Integer> emptyKitCountSetting;
    private final Setting<Set<Item>> emptyKitItemsSetting;
    private final Setting<Set<Item>> finishedKitItemsSetting;
    private final Setting<Set<Item>> miscKitItemsSetting;
    private final Setting<Boolean> initializeSetting;
    private final Setting<Integer> delaySetting;

    private State state = State.NONE;
    /** 上游 {@code aw.n}：走位结束后要恢复的状态。*/
    private State pendingState;
    /** 上游 {@code aw.o}：关界面后要恢复的状态。*/
    private State returnState;
    /** 上游 {@code ag.a}：状态机延迟倒计时（tick）。*/
    private int delayTicks;
    /** 上游 {@code ag.b}：背包搜索的轮转游标。*/
    private int inventoryCursor;
    /** 上游 {@code ag.c}：容器搜索的轮转游标。*/
    private int containerCursor;
    /** 上游 {@code ag.t()}：最近一次在背包里找到的槽位。*/
    private int lastInventorySlot = -1;
    /** 上游 {@code ag.v()}：最近一次在容器里找到的槽位。*/
    private int lastContainerSlot = -1;
    /** 上游 {@code Z_.C}：模板写到第几格了。*/
    private int templateCursor;
    /** 上游 {@code Z_.D}：当前要拿的 Kit 物品签名。*/
    private KitKey takeKey;
    /** 上游 {@code Z_.E}：当前要拿的 Kit 格位。*/
    private KitPos takePos;
    /** 上游 {@code Z_.F}：当前要挖的 Kit 格位。*/
    private KitPos breakPos;
    /** 模板（27 格，空槽为 null）。*/
    private final List<TemplateSlot> template = new ArrayList<>(KIT_SLOT_COUNT);
    /** 上游 {@code Z_.B}：本批还要拿的物品 → 数量。*/
    private final Map<KitKey, Integer> needed = new LinkedHashMap<>();
    /** 展示框物品 → 格位（本文件用「展示框物品即盒内物品」推断，见类注释）。*/
    private final Map<KitKey, KitPos> kitPositions = new HashMap<>();
    private KitPos emptyKitPos;
    private KitPos finishedKitPos;
    private KitPos miscKitPos;
    /** 是否已经初始化过仓库（上游 {@code bC_.a()}）。*/
    private boolean initialized;
    /** 当前正在打开的容器位置（上游 {@code ag.f}）。*/
    private BlockPos openedPos;
    private long openRequestedAt;
    private long lastOpenAttemptAt;
    /** 正在挖的方块，切换状态时清空。*/
    private BlockPos breakTarget;
    /** 走位轮询：路径停下后的静默 tick 数。*/
    private int pathIdleTicks;
    /** 走位重试计数：同一目标、距离没有明显缩短的连续重试次数。*/
    private int pathAttempts;
    private BlockPos lastPathTarget;
    private double lastPathDistance = Double.MAX_VALUE;

    public AutoKitModule(ModuleCategory category) {
        super("lotus", "LotusAutoKit",
                "Assembles kits automatically in a Lotus simple warehouse (Baritone required).",
                category);
        this.scanRangeSetting = this.add(new NumberSetting<>("ScanRange", 50, 1, 100));
        this.operationCountSetting = this.add(new NumberSetting<>("OperationCount", 9, 1, 9));
        this.emptyKitCountSetting = this.add(new NumberSetting<>("EmptyKitCount", 2, 1, 9));
        this.emptyKitItemsSetting = this.add(new RegistrySetSetting<>("EmptyKitItems",
                BuiltInRegistries.ITEM, Items.SHULKER_BOX));
        this.finishedKitItemsSetting = this.add(new RegistrySetSetting<>("FinishedKitItems",
                BuiltInRegistries.ITEM, Items.LIGHT_BLUE_SHULKER_BOX));
        this.miscKitItemsSetting = this.add(new RegistrySetSetting<>("MiscKitItems",
                BuiltInRegistries.ITEM, Items.WHITE_SHULKER_BOX));
        this.initializeSetting = this.add(new BooleanSetting("Initialize", false));
        this.initializeSetting.setChangeCallback(this::onInitializeSettingChanged);
        // 上游基类 ag 的「延迟」：默认 5，上限 40。
        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.resetPathTracking();
        this.openedPos = null;
        this.templateCursor = 0;
        this.takeKey = null;
        this.takePos = null;
        this.breakPos = null;
        this.breakTarget = null;
        this.needed.clear();

        if (!this.initialized) {
            this.warn("addons.lotus.messages.autoInitialize");
            if (!this.initializeWarehouse()) {
                this.warn("addons.lotus.messages.autoInitializeFailed");
                this.disable();
                return;
            }
        }
        this.state = State.NEXT;
    }

    @Override
    public void onDisable() {
        this.templateCursor = 0;
        this.takeKey = null;
        this.takePos = null;
        this.breakPos = null;
        this.breakTarget = null;
        this.pendingState = null;
        this.returnState = null;
        this.needed.clear();
        this.resetPathTracking();

        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge != null && bridge.isAvailable()) {
            bridge.cancelPathing();
        }
        this.closeScreenNow();
        this.state = State.NONE;
        // TODO(port): 上游在模块启用期间把 Baritone 的 allowBreak / allowPlace 设为 false、停用时恢复；
        //  BaritoneBridge 没有暴露这两个设置，未做等价处理。
    }

    /**
     * 「初始化」设置的回调：原模块把它当成一次性的按钮。
     *
     * <p>先把开关拨回 false，再执行初始化；{@code setValue} 会再次回调，所以必须先判断当前值。
     */
    private void onInitializeSettingChanged() {
        if (!Boolean.TRUE.equals(this.initializeSetting.getValue())) {
            return;
        }
        this.initializeSetting.setValue(false);
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.evernightMinecraft.level == null) {
            this.warn("addons.lotus.messages.worldUnavailable");
            return;
        }
        try {
            if (this.initializeWarehouse() && this.isToggled()) {
                this.templateCursor = 0;
                this.takeKey = null;
                this.takePos = null;
                this.state = State.NEXT;
            }
        } catch (RuntimeException exception) {
            this.warn("addons.lotus.messages.initializeError", describeThrowable(exception));
        }
    }

    // ------------------------------------------------------------------
    // tick 主循环
    // ------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (this.state != State.BREAK_KIT) {
            this.breakTarget = null;
        }
        this.tickPathing();
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
                    // 由 tickPathing 负责恢复。
                }
                case CLOSE_SCREEN -> this.tickCloseScreen();
                case NEXT -> this.tickNext();
                case TAKE_EMPTY_KIT -> this.tickTakeEmptyKit();
                case PLACE_EMPTY_KIT -> this.tickPlaceEmptyKit();
                case TAKE_ITEM -> this.tickTakeItem();
                case PLACE_KIT -> this.tickPlaceKit();
                case TAKE_KIT -> this.tickTakeKit();
                case PUT_ITEM -> this.tickPutItem();
                case PUT_KIT -> this.tickPutKit(true);
                case PUT_MISC_KIT -> this.tickPutKit(false);
                case PUT_EMPTY_KIT -> this.tickPutEmptyKit();
                case BREAK_KIT -> this.tickBreakKit();
                default -> {
                    this.warn("addons.lotus.messages.unknownState", this.state.name());
                    this.disable();
                }
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            this.warn("addons.lotus.messages.unexpectedError", describeThrowable(throwable));
            this.disable();
        }
    }

    /** 上游 {@code aw.y()}：关掉容器界面后接着执行原状态。*/
    private void tickCloseScreen() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
            ContainerInteractionUtils.closeScreen();
        }
        this.openedPos = null;
        State next = this.returnState;
        this.returnState = null;
        this.state = next == null ? State.NONE : next;
        this.resetDelay();
    }

    /** 上游 {@code aw.onPathEvent(PathEvent.CANCELED)} 的轮询替代：路径停下就恢复待执行状态。*/
    private void tickPathing() {
        if (this.state != State.WALKING) {
            this.pathIdleTicks = 0;
            return;
        }
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            this.restorePendingState(State.NEXT);
            return;
        }
        if (bridge.isPathingOrCalculating()) {
            this.pathIdleTicks = 0;
            return;
        }
        if (++this.pathIdleTicks < PATH_IDLE_TICKS) {
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

    // ------------------------------------------------------------------
    // NEXT（上游 Z_.x()）
    // ------------------------------------------------------------------

    private void tickNext() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        // 1) 地上 5 格内的 Kit 盒优先捡起来。
        List<ItemEntity> dropped = level.getEntitiesOfClass(ItemEntity.class,
                player.getBoundingBox().inflate(5.0D),
                entity -> isShulkerBox(entity.getItem().getItem()));
        if (!dropped.isEmpty()) {
            ItemEntity item = dropped.get(0);
            BlockPos target = this.pickupTarget(item);
            if (target == null) {
                target = item.blockPosition();
            }
            this.walkTo(target, State.NEXT, "addons.lotus.messages.pickupKit");
            return;
        }

        // 2) 在背包里找一个 Kit 盒（空盒累计到上限就先存回空盒箱）。
        int emptySeen = 0;
        int firstSlot = -1;
        while (true) {
            ItemStack box = this.findInInventory(AutoKitModule::isShulkerBox);
            if (box.isEmpty()) {
                break;
            }
            if (!isContentsEmpty(box)) {
                this.state = this.boxMatchesTemplate(box) ? State.PUT_KIT : State.PUT_MISC_KIT;
                return;
            }
            int slot = this.lastInventorySlot;
            if (firstSlot == slot) {
                break;
            }
            if (firstSlot == -1) {
                firstSlot = slot;
            }
            if (++emptySeen >= this.emptyKitCountSetting.getValue()) {
                this.state = State.PUT_EMPTY_KIT;
                return;
            }
        }

        // 3) 统计本批还需要的物品（每批 OperationCount 格）。
        this.needed.clear();
        int end = Math.min(this.template.size(),
                this.templateCursor + this.operationCountSetting.getValue());
        for (int index = this.templateCursor; index < end; index++) {
            TemplateSlot slot = this.template.get(index);
            if (slot == null) {
                continue;
            }
            this.needed.merge(slot.key(), slot.count(), Integer::sum);
        }
        if (this.needed.isEmpty()) {
            this.finishKit();
            return;
        }

        Map<KitKey, Integer> available = this.countInventoryItems(this.needed.keySet());
        Set<KitKey> enough = new HashSet<>();
        for (Map.Entry<KitKey, Integer> entry : this.needed.entrySet()) {
            Integer have = available.get(entry.getKey());
            if (have == null) {
                continue;
            }
            if (have >= entry.getValue()) {
                enough.add(entry.getKey());
            } else {
                entry.setValue(entry.getValue() - have);
            }
        }
        if (enough.equals(this.needed.keySet())) {
            this.state = State.PUT_ITEM;
        } else {
            this.needed.keySet().removeAll(enough);
            this.state = State.TAKE_ITEM;
        }
    }

    /** 上游 {@code Z_.G()}：Kit 已满，把成品格上的盒子挖掉。*/
    private void finishKit() {
        this.info("addons.lotus.messages.kitFull");
        this.templateCursor = 0;
        if (this.finishedKitPos == null) {
            this.state = State.NEXT;
            return;
        }
        this.breakPos = this.finishedKitPos;
        this.closeThenDelayed(State.BREAK_KIT);
    }

    // ------------------------------------------------------------------
    // TAKE_EMPTY_KIT（上游 aB_.a_m_int）
    // ------------------------------------------------------------------

    private void tickTakeEmptyKit() {
        KitPos position = this.emptyKitPos;
        if (position == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.countEmptyKitBoxes() >= this.emptyKitCountSetting.getValue()) {
            this.closeThen(State.PLACE_EMPTY_KIT);
            return;
        }
        if (this.isTooFar(position)) {
            this.pathTo(position, "addons.lotus.messages.takeEmptyKitDistanceTooFar", State.TAKE_EMPTY_KIT);
            return;
        }
        this.withContainer(position.takeChestPos(), menu -> {
            ItemStack box = this.findInContainer(AutoKitModule::isEmptyKitBox);
            if (box.isEmpty()) {
                this.warn("addons.lotus.messages.emptyKitChestEmpty");
                this.disable();
                return;
            }
            this.shiftClickContainer(this.lastContainerSlot);
            this.resetDelay();
        });
    }

    // ------------------------------------------------------------------
    // PLACE_EMPTY_KIT（上游 aB_.c_m）
    // ------------------------------------------------------------------

    private void tickPlaceEmptyKit() {
        KitPos position = this.finishedKitPos;
        Level level = this.evernightMinecraft.level;
        if (position == null || level == null) {
            this.state = State.NEXT;
            return;
        }
        BlockState kitState = level.getBlockState(position.kitPos());
        if (kitState.getBlock() instanceof ShulkerBoxBlock) {
            this.state = State.PUT_ITEM;
            return;
        }
        if (!kitState.isAir()) {
            this.warn("addons.lotus.messages.finishedPositionOccupied");
            this.state = State.NONE;
            return;
        }
        ItemStack box = this.findInInventory(AutoKitModule::isEmptyKitBox);
        if (box.isEmpty()) {
            this.state = State.TAKE_EMPTY_KIT;
            return;
        }
        if (this.isTooFar(position)) {
            this.walkTo(position.pathPos(), State.PLACE_EMPTY_KIT, null);
            return;
        }
        this.placeHeldKit(position.kitPos());
        this.state = State.PUT_ITEM;
        this.resetDelay();
    }

    // ------------------------------------------------------------------
    // TAKE_ITEM / PLACE_KIT（上游 Z_.E() / aB_.a_m_Predicate）
    // ------------------------------------------------------------------

    private void tickTakeItem() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        if (this.needed.isEmpty()) {
            this.info("addons.lotus.messages.enoughItems");
            this.closeThen(State.PUT_ITEM);
            return;
        }
        Map.Entry<KitKey, KitPos> nearest = this.nearestKitPosition(this.needed.keySet());
        if (nearest == null) {
            this.warn("addons.lotus.messages.missingKitPosition", describeKeys(this.needed.keySet()));
            this.disable();
            return;
        }
        this.takeKey = nearest.getKey();
        this.takePos = nearest.getValue();

        BlockState kitState = level.getBlockState(this.takePos.kitPos());
        if (kitState.isAir()) {
            this.info("addons.lotus.messages.needPlaceKit");
            this.closeThen(State.PLACE_KIT);
            return;
        }
        if (!(kitState.getBlock() instanceof ShulkerBoxBlock)) {
            this.warn("addons.lotus.messages.kitPositionOccupied");
            this.state = State.NONE;
            return;
        }
        if (this.isTooFar(this.takePos)) {
            this.pathTo(this.takePos, "addons.lotus.messages.kitDistanceTooFar", State.TAKE_ITEM,
                    this.takeKey.describe());
            return;
        }
        this.withContainer(this.takePos.kitPos(), menu -> {
            int need = this.needed.getOrDefault(this.takeKey, 0);
            int wanted = Math.min(need, this.takeKey.stack().getMaxStackSize());
            ItemStack found = this.findInContainer(
                    stack -> this.takeKey.matches(stack) && stack.getCount() >= wanted);
            if (found.isEmpty()) {
                found = this.findInContainer(this.takeKey::matches);
            }
            if (found.isEmpty()) {
                this.breakPos = this.takePos;
                this.closeThen(State.BREAK_KIT);
                return;
            }
            this.info("addons.lotus.messages.takingItem", this.takeKey.describe());
            this.shiftClickContainer(this.lastContainerSlot);
            int remaining = this.needed.getOrDefault(this.takeKey, 0) - found.getCount();
            if (remaining <= 0) {
                this.needed.remove(this.takeKey);
            } else {
                this.needed.put(this.takeKey, remaining);
            }
            this.resetDelay();
        });
    }

    private void tickPlaceKit() {
        Level level = this.evernightMinecraft.level;
        if (level == null || this.takePos == null || this.takeKey == null) {
            this.state = State.NEXT;
            return;
        }
        BlockState kitState = level.getBlockState(this.takePos.kitPos());
        if (kitState.getBlock() instanceof ShulkerBoxBlock) {
            this.state = State.TAKE_ITEM;
            return;
        }
        if (!kitState.isAir()) {
            this.warn("addons.lotus.messages.kitPositionOccupied");
            this.state = State.NONE;
            return;
        }
        ItemStack box = this.findInInventory(stack -> isShulkerBox(stack)
                && !isContentsEmpty(stack) && this.boxContains(stack, this.takeKey));
        if (box.isEmpty()) {
            this.state = State.TAKE_KIT;
            return;
        }
        if (this.isTooFar(this.takePos)) {
            this.pathTo(this.takePos, "addons.lotus.messages.goTakeKit", State.PLACE_KIT);
            return;
        }
        this.placeHeldKit(this.takePos.kitPos());
        this.state = State.TAKE_ITEM;
        this.resetDelay();
    }

    private void tickTakeKit() {
        if (this.takePos == null || this.takeKey == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isTooFar(this.takePos)) {
            this.pathTo(this.takePos, "addons.lotus.messages.takeKitDistanceTooFar", State.TAKE_KIT);
            return;
        }
        this.withContainer(this.takePos.takeChestPos(), menu -> {
            ItemStack found = this.findInContainer(stack -> isShulkerBox(stack)
                    && this.boxContains(stack, this.takeKey));
            if (found.isEmpty()) {
                this.warn("addons.lotus.messages.outOfStock");
                this.disable();
                return;
            }
            this.shiftClickContainer(this.lastContainerSlot);
            this.closeThenDelayed(State.TAKE_ITEM);
        });
    }

    // ------------------------------------------------------------------
    // PUT_ITEM（上游 Z_.F()）
    // ------------------------------------------------------------------

    private void tickPutItem() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null || this.finishedKitPos == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.templateCursor >= this.template.size()) {
            this.finishKit();
            return;
        }
        TemplateSlot entry = this.template.get(this.templateCursor);
        if (entry == null) {
            // TODO(port): 上游对模板空槽会 NPE；这里跳过空格继续下一格，属于修正而不是复刻。
            this.templateCursor++;
            this.resetDelay();
            return;
        }
        ItemStack source = this.findInInventory(entry::isSource);
        if (source.isEmpty()) {
            this.info("addons.lotus.messages.missingItem");
            this.closeThen(State.NEXT);
            return;
        }
        BlockState kitState = level.getBlockState(this.finishedKitPos.kitPos());
        if (kitState.isAir()) {
            this.info("addons.lotus.messages.needEmptyKit");
            this.closeThen(State.PLACE_EMPTY_KIT);
            return;
        }
        if (!(kitState.getBlock() instanceof ShulkerBoxBlock)) {
            this.closeScreenNow();
            this.warn("addons.lotus.messages.finishedPositionOccupied");
            this.state = State.NONE;
            return;
        }
        if (this.isTooFar(this.finishedKitPos)) {
            this.pathTo(this.finishedKitPos, "addons.lotus.messages.putDistanceTooFar", State.PUT_ITEM,
                    entry.key().describe());
            return;
        }
        final int templateIndex = this.templateCursor;
        this.withContainer(this.finishedKitPos.kitPos(), menu -> {
            if (this.isContainerFull(menu)) {
                this.finishKit();
                return;
            }
            ItemStack inSlot = menu.getSlot(templateIndex).getItem();
            if (inSlot.isEmpty()) {
                int menuSlot = findMenuSlotForInventory(menu, player, this.lastInventorySlot);
                if (menuSlot < 0) {
                    this.resetDelay();
                    return;
                }
                if (entry.count() == source.getCount()) {
                    InventoryUtils.clickSlot(menu.containerId, menuSlot, 0, ClickType.QUICK_MOVE);
                } else if (entry.count() == 1) {
                    // 上游 bp.b：整叠拿起 → 右键放 1 个 → 余数放回。
                    InventoryUtils.clickSlot(menu.containerId, menuSlot, 0, ClickType.PICKUP);
                    InventoryUtils.clickSlot(menu.containerId, templateIndex, 1, ClickType.PICKUP);
                    InventoryUtils.clickSlot(menu.containerId, menuSlot, 0, ClickType.PICKUP);
                } else {
                    // 上游 bp.c：右键拿一半 → 左键整叠放进盒子。
                    InventoryUtils.clickSlot(menu.containerId, menuSlot, 1, ClickType.PICKUP);
                    InventoryUtils.clickSlot(menu.containerId, templateIndex, 0, ClickType.PICKUP);
                }
                this.info("addons.lotus.messages.inserted", entry.key().describe(), entry.count());
                this.resetDelay();
            } else if (!entry.matches(inSlot)) {
                this.finishKit();
                return;
            }
            this.templateCursor++;
        });
    }

    // ------------------------------------------------------------------
    // PUT_KIT / PUT_MISC_KIT / PUT_EMPTY_KIT（上游 aB_.b_m_Predicate / b_m）
    // ------------------------------------------------------------------

    private void tickPutKit(boolean finishedKit) {
        KitPos position = finishedKit ? this.finishedKitPos : this.miscKitPos;
        if (position == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isTooFar(position)) {
            this.pathTo(position, "addons.lotus.messages.storeDistanceTooFar", this.state);
            return;
        }
        ItemStack box = finishedKit
                ? this.findInInventory(this::boxMatchesTemplate)
                : this.findInInventory(this::isMiscKitBox);
        if (box.isEmpty()) {
            this.warn("addons.lotus.messages.noKitToStore");
            this.state = State.NEXT;
            return;
        }
        this.withContainer(position.chestPos(), menu -> {
            if (this.isContainerFull(menu)) {
                this.warn("addons.lotus.messages.storageFull");
                this.disable();
                return;
            }
            this.shiftClickInventory(this.lastInventorySlot);
            this.closeThenDelayed(State.NEXT);
        });
    }

    private void tickPutEmptyKit() {
        KitPos position = this.emptyKitPos;
        if (position == null) {
            this.state = State.NEXT;
            return;
        }
        ItemStack box = this.findInInventory(AutoKitModule::isEmptyKitBox);
        if (box.isEmpty()) {
            this.state = State.NEXT;
            return;
        }
        if (this.isTooFar(position)) {
            this.pathTo(position, "addons.lotus.messages.storeDistanceTooFar", State.PUT_EMPTY_KIT);
            return;
        }
        this.withContainer(position.chestPos(), menu -> {
            if (this.isContainerFull(menu)) {
                this.warn("addons.lotus.messages.storageFull");
                this.closeScreenNow();
                this.state = State.NONE;
                return;
            }
            this.shiftClickInventory(this.lastInventorySlot);
            this.closeThenDelayed(State.NEXT);
        });
    }

    // ------------------------------------------------------------------
    // BREAK_KIT（上游 aB_.d_m）
    // ------------------------------------------------------------------

    private void tickBreakKit() {
        Level level = this.evernightMinecraft.level;
        LocalPlayer player = this.evernightMinecraft.player;
        if (level == null || player == null || this.breakPos == null) {
            this.state = State.NEXT;
            return;
        }
        BlockPos position = this.breakPos.kitPos();
        if (this.isTooFar(this.breakPos)) {
            this.pathTo(this.breakPos, "addons.lotus.messages.breakDistanceTooFar", State.BREAK_KIT);
            return;
        }
        BlockState blockState = level.getBlockState(position);
        if (!(blockState.getBlock() instanceof ShulkerBoxBlock)) {
            this.state = State.NEXT;
            // 上游 c(20)：盒子没了以后等 20 tick 再继续，避免刚挖完就误判。
            this.delayTicks = 20;
            return;
        }
        this.equipPickaxe();
        if (this.breakTarget == null || !this.breakTarget.equals(position)) {
            this.evernightMinecraft.gameMode.startDestroyBlock(position, Direction.UP);
            this.breakTarget = position.immutable();
        } else {
            this.evernightMinecraft.gameMode.continueDestroyBlock(position, Direction.UP);
        }
        // TODO(port): 上游用 Meteor BlockUtils.breakBlock(pos, true) 并附带 6666 优先级的静默转身；
        //  这里直接驱动原版 MultiPlayerGameMode，不额外发旋转包。
    }

    // ------------------------------------------------------------------
    // 仓库扫描与初始化（上游 w() + bC_.a + br.b）
    // ------------------------------------------------------------------

    private boolean initializeWarehouse() {
        this.resetWarehouse();
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.evernightMinecraft.level == null) {
            this.warn("addons.lotus.messages.worldUnavailable");
            return false;
        }
        this.scanKitFrames();

        if (this.emptyKitPos == null) {
            this.warn("addons.lotus.messages.missingEmptyKitFrame",
                    describeItems(this.emptyKitItemsSetting.getValue()));
            return false;
        }
        if (this.finishedKitPos == null) {
            this.warn("addons.lotus.messages.missingFinishedKitFrame",
                    describeItems(this.finishedKitItemsSetting.getValue()));
            return false;
        }
        if (this.miscKitPos == null) {
            this.warn("addons.lotus.messages.missingMiscKitFrame",
                    describeItems(this.miscKitItemsSetting.getValue()));
            return false;
        }

        ItemStack held = player.getMainHandItem();
        if (!isShulkerBox(held.getItem())) {
            this.warn("addons.lotus.messages.templateNotShulker");
            return false;
        }
        if (isContentsEmpty(held)) {
            this.warn("addons.lotus.messages.templateEmpty");
            return false;
        }

        this.template.clear();
        boolean hasItem = false;
        for (ItemStack stack : readContents(held)) {
            if (stack.isEmpty()) {
                this.template.add(null);
                continue;
            }
            hasItem = true;
            int count = stack.getCount();
            int max = stack.getMaxStackSize();
            if (count < max) {
                int half = max / 2;
                count = count >= half ? half : 1;
            }
            this.template.add(new TemplateSlot(new KitKey(stack), count));
        }
        if (!hasItem) {
            this.warn("addons.lotus.messages.templateUnreadable");
            return false;
        }

        this.initialized = true;
        this.info("addons.lotus.messages.identifiedKits", describeKitFrames());
        this.warn("addons.lotus.messages.initializeDone");
        return true;
    }

    /**
     * 上游 {@code br.b(range, 4)}：扫展示框，找出「展示框 + 上方箱子 + 下方放盒格」的仓库格位。
     *
     * <p>与原版一致要求放盒格在玩家脚下同一层、且是空气或潜影盒；箱子位置先用展示框朝向试，
     * 再退回四向搜索（1.21.11 的 {@code HangingEntity#getDirection()} 语义不再保证是「正面」）。
     */
    private void scanKitFrames() {
        this.kitPositions.clear();
        this.emptyKitPos = null;
        this.finishedKitPos = null;
        this.miscKitPos = null;

        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }
        int range = this.scanRangeSetting.getValue();
        BlockPos origin = player.blockPosition();
        AABB area = new AABB(
                origin.getX() - range, origin.getY(), origin.getZ() - range,
                origin.getX() + range, origin.getY() + 4, origin.getZ() + range);
        List<ItemFrame> frames = level.getEntitiesOfClass(ItemFrame.class, area,
                frame -> !frame.getItem().isEmpty());

        Set<Item> emptyMarkers = this.emptyKitItemsSetting.getValue();
        Set<Item> finishedMarkers = this.finishedKitItemsSetting.getValue();
        Set<Item> miscMarkers = this.miscKitItemsSetting.getValue();
        Map<KitKey, KitPos> found = new HashMap<>();

        for (ItemFrame frame : frames) {
            ItemStack display = frame.getItem();
            KitPos position = this.resolveKitPosition(frame);
            if (position == null) {
                continue;
            }
            Item item = display.getItem();
            if (emptyMarkers.contains(item)) {
                this.emptyKitPos = nearest(position, this.emptyKitPos, player);
            } else if (finishedMarkers.contains(item)) {
                this.finishedKitPos = nearest(position, this.finishedKitPos, player);
            } else if (miscMarkers.contains(item)) {
                this.miscKitPos = nearest(position, this.miscKitPos, player);
            } else {
                // TODO(port): 上游 bC_ 用「简易仓库」模块（U_）的 Kit 定义表把「物品」映射到「展示框物品」，
                //  支持一个盒子放多种相关物品；本文件没有该模块，退化为「展示框物品 == 盒内物品」，只支持一框一物。
                KitKey key = new KitKey(display);
                KitPos previous = found.get(key);
                if (previous == null) {
                    found.put(key, position);
                } else {
                    this.warn("addons.lotus.messages.duplicateFrame", key.describe());
                    if (nearest(position, previous, player) == position) {
                        found.put(key, position);
                    }
                }
            }
        }
        this.kitPositions.putAll(found);
    }

    private KitPos resolveKitPosition(ItemFrame frame) {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return null;
        }
        BlockPos framePos = frame.blockPosition();
        BlockPos chestPos = framePos.above().relative(frame.getDirection().getOpposite());
        if (level.getBlockState(chestPos).getBlock() != Blocks.CHEST) {
            chestPos = null;
            for (Direction direction : Direction.values()) {
                if (direction.getAxis().isVertical()) {
                    continue;
                }
                BlockPos candidate = framePos.above().relative(direction);
                if (level.getBlockState(candidate).getBlock() == Blocks.CHEST) {
                    chestPos = candidate;
                    break;
                }
            }
        }
        if (chestPos == null) {
            return null;
        }
        BlockPos kitPos = framePos.below(2);
        if (kitPos.getY() != player.blockPosition().getY()) {
            return null;
        }
        BlockState kitState = level.getBlockState(kitPos);
        if (!kitState.isAir() && !(kitState.getBlock() instanceof ShulkerBoxBlock)) {
            return null;
        }
        Direction back = horizontalDirectionFrom(framePos, chestPos);
        if (back == null) {
            return null;
        }
        BlockPos pathPos = kitPos.relative(back.getOpposite(), 2);
        return new KitPos(framePos, chestPos, chestPos.below(2), kitPos, pathPos);
    }

    private void resetWarehouse() {
        this.initialized = false;
        this.template.clear();
        this.needed.clear();
        this.kitPositions.clear();
        this.emptyKitPos = null;
        this.finishedKitPos = null;
        this.miscKitPos = null;
        this.takeKey = null;
        this.takePos = null;
        this.breakPos = null;
        this.templateCursor = 0;
    }

    // ------------------------------------------------------------------
    // 走位（上游 aA_ / aw）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code aA_.a(BlockPos, int, be, String)}：够近就切状态，否则记下待恢复状态再走位。
     *
     * @param messageKey 走位前的提示（null 表示不提示），与上游一致只在「距离不够」时发
     */
    private void walkTo(BlockPos target, State nextState, String messageKey) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || target == null) {
            return;
        }
        double distance = player.position().distanceTo(target.getCenter());
        if (distance > MAX_WALK_DISTANCE) {
            this.warn("addons.lotus.messages.tooFar");
            this.disable();
            return;
        }
        if (distance <= REACH_DISTANCE) {
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

    /** 上游 {@code aA_.a_m_String_be(m, msg, be)}：提示 + 朝格位的走位点出发。*/
    private void pathTo(KitPos position, String messageKey, State nextState, Object... args) {
        this.info(messageKey, args);
        this.closeScreenNow();
        this.pendingState = nextState;
        this.pathTo(position.pathPos());
    }

    /**
     * 上游 {@code aA_.a_BlockPos_int_toVoid}：Baritone 走位。
     *
     * <p>路径停下后由 {@link #tickPathing()} 恢复状态，相当于上游的 {@code PathEvent.CANCELED} 回调；
     * 同一目标反复重试且距离不缩短时判定不可达，关闭模块。
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
            // TODO(port): 上游没有 Baritone 检查（Baritone 是硬依赖）；这里没有走位能力就直接停。
            this.warn("addons.lotus.messages.baritoneUnavailable");
            this.disable();
            return;
        }
        bridge.pathTo(target);
        this.pathIdleTicks = 0;
        this.state = State.WALKING;
    }

    private void resetPathTracking() {
        this.pathIdleTicks = 0;
        this.pathAttempts = 0;
        this.lastPathTarget = null;
        this.lastPathDistance = Double.MAX_VALUE;
    }

    /**
     * 上游 {@code br.a_Vec3_toBlockPos} 的简化版：掉落物不在脚下时，在它周围找一个可站立的地面格。
     *
     * @return 走位目标，或 null 表示直接用掉落物所在方块
     */
    private BlockPos pickupTarget(ItemEntity item) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        int playerY = player.blockPosition().getY();
        Vec3 itemPosition = item.position();
        if (itemPosition.y < playerY - 0.75D || itemPosition.y > playerY + 2.3D) {
            return null;
        }
        BlockPos center = item.blockPosition();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 playerPosition = player.position();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                double deltaX = center.getX() + offsetX + 0.5D - itemPosition.x;
                double deltaZ = center.getZ() + offsetZ + 0.5D - itemPosition.z;
                if (deltaX * deltaX + deltaZ * deltaZ > 2.030625D) {
                    continue;
                }
                BlockPos candidate = center.offset(offsetX, 0, offsetZ).atY(playerY);
                if (!this.isWalkableFloor(candidate)) {
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

    private boolean isWalkableFloor(BlockPos position) {
        if (WorldUtils.hasCollision(position) || WorldUtils.hasCollision(position.above())) {
            return false;
        }
        return WorldUtils.hasCollision(position.below());
    }

    // ------------------------------------------------------------------
    // 容器交互
    // ------------------------------------------------------------------

    /**
     * 上游 {@code ag.a_BlockPos_Consumer}：打开并等到菜单同步后执行一次操作。
     *
     * <p>每次调用都先检查当前菜单；没打开就发开箱包（带节流），还没同步就等，同步好才执行。
     */
    private void withContainer(BlockPos position, Consumer<AbstractContainerMenu> action) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || position == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu || !position.equals(this.openedPos)) {
            if (!(menu instanceof InventoryMenu)) {
                // 有别的容器还开着（玩家手动开的，或 openedPos 刚被清空）：openContainer 在 screen != null
                // 时什么也不做，所以先关掉，下一 tick 再正常打开。
                this.openedPos = null;
                ContainerInteractionUtils.closeScreen();
                this.resetDelay();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - this.lastOpenAttemptAt < OPEN_THROTTLE_MS) {
                this.resetDelay();
                return;
            }
            this.lastOpenAttemptAt = now;
            this.openRequestedAt = now;
            this.openedPos = position.immutable();
            ContainerInteractionUtils.openContainer(position, true);
            this.resetDelay();
            return;
        }
        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) {
            // 上游 ag：界面类型不对就关掉重来。
            this.openedPos = null;
            ContainerInteractionUtils.closeScreen();
            this.warn("addons.lotus.messages.wrongContainer");
            this.resetDelay();
            return;
        }
        if (menu.getStateId() <= 0) {
            if (System.currentTimeMillis() - this.openRequestedAt > CONTAINER_SYNC_TIMEOUT_MS) {
                this.warn("addons.lotus.messages.containerNotSynced");
                this.openedPos = null;
                ContainerInteractionUtils.closeScreen();
            }
            this.resetDelay();
            return;
        }
        action.accept(menu);
    }

    /** 上游 {@code bp.a()}：不是玩家背包界面时关掉当前容器。*/
    private void closeScreenNow() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
            ContainerInteractionUtils.closeScreen();
        }
        this.openedPos = null;
    }

    /** 上游 {@code a_be(be)}：先关界面，再切到目标状态。*/
    private void closeThen(State nextState) {
        this.returnState = nextState;
        this.state = State.CLOSE_SCREEN;
    }

    /** 上游 {@code b_be(be)}：关界面、切状态并重置延迟。*/
    private void closeThenDelayed(State nextState) {
        this.returnState = nextState;
        this.state = State.CLOSE_SCREEN;
        this.resetDelay();
    }

    // ------------------------------------------------------------------
    // 背包 / 容器槽位工具（上游 ag / bp 的替代）
    // ------------------------------------------------------------------

    /** 上游 {@code ag.a_Predicate}：从轮转游标开始遍历背包，返回匹配物品的副本。*/
    private ItemStack findInInventory(Predicate<ItemStack> predicate) {
        // TODO(port): 上游会跳过「Lotus助手」里锁定的物品栏（ao 模块的锁定槽位）；本插件没有该模块，
        //  所有 36 个背包槽都参与搜索。
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        Inventory inventory = player.getInventory();
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            this.inventoryCursor = (this.inventoryCursor + 1) % INVENTORY_SIZE;
            ItemStack stack = inventory.getItem(this.inventoryCursor);
            if (!stack.isEmpty() && predicate.test(stack)) {
                this.lastInventorySlot = this.inventoryCursor;
                return stack.copy();
            }
        }
        this.lastInventorySlot = -1;
        return ItemStack.EMPTY;
    }

    /** 上游 {@code ag.d_Predicate}：从轮转游标开始遍历已打开容器的槽位。*/
    private ItemStack findInContainer(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        AbstractContainerMenu menu = player.containerMenu;
        Inventory inventory = player.getInventory();
        int slots = containerSlotCount(menu, inventory);
        if (slots <= 0) {
            this.lastContainerSlot = -1;
            return ItemStack.EMPTY;
        }
        for (int index = 0; index < slots; index++) {
            this.containerCursor = (this.containerCursor + 1) % slots;
            Slot slot = menu.getSlot(this.containerCursor);
            if (slot.container == inventory) {
                continue;
            }
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                this.lastContainerSlot = this.containerCursor;
                return stack.copy();
            }
        }
        this.lastContainerSlot = -1;
        return ItemStack.EMPTY;
    }

    /** 上游 {@code InvUtils.shiftClick().slot(index)}：把背包槽 shift 进容器。*/
    private void shiftClickInventory(int inventoryIndex) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int menuSlot = findMenuSlotForInventory(menu, player, inventoryIndex);
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

    /**
     * 把背包槽里的潜影盒放到世界上。
     *
     * <p>上游 {@code bp.a(slot, runnable)}：快捷栏直接选、背包里先和当前快捷栏格交换，然后调用
     * Meteor 的 {@code BlockUtils.placeBlock}。
     */
    private void placeHeldKit(BlockPos position) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.lastInventorySlot < 0) {
            return;
        }
        int inventorySlot = this.lastInventorySlot;
        if (inventorySlot < 9) {
            InventoryUtils.selectHotbarSlot(inventorySlot);
        } else {
            int menuSlot = findMenuSlotForInventory(player.containerMenu, player, inventorySlot);
            if (menuSlot >= 0) {
                InventoryUtils.swapWithHotbarSlot(menuSlot, InventoryUtils.getSelectedSlot());
            }
        }
        BlockInteractionUtils.placeBlock(position, false, InteractionHand.MAIN_HAND);
    }

    /** 上游 {@code ag.f(predicate)}：把镐子换到主手。*/
    private void equipPickaxe() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        if (PICKAXES.contains(player.getMainHandItem().getItem())) {
            return;
        }
        int inventorySlot = InventoryUtils.findInventorySlot(
                stack -> PICKAXES.contains(stack.getItem()), false);
        if (inventorySlot < 0) {
            return;
        }
        if (inventorySlot < 9) {
            InventoryUtils.selectHotbarSlot(inventorySlot);
        } else {
            int menuSlot = findMenuSlotForInventory(player.containerMenu, player, inventorySlot);
            if (menuSlot >= 0) {
                InventoryUtils.swapWithHotbarSlot(menuSlot, InventoryUtils.getSelectedSlot());
            }
        }
    }

    /** 背包槽位 → 当前菜单槽位（不依赖菜单类型，按容器定位）。*/
    private static int findMenuSlotForInventory(AbstractContainerMenu menu, LocalPlayer player,
                                                int inventoryIndex) {
        Inventory inventory = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return -1;
    }

    /** 当前菜单里属于容器的槽位数量（上游 {@code bp.g()} 的通用写法）。*/
    private static int containerSlotCount(AbstractContainerMenu menu, Inventory inventory) {
        int count = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != inventory) {
                count++;
            }
        }
        return count;
    }

    /** 上游 {@code ag.q()}：容器的槽位是否已经全满。*/
    private boolean isContainerFull(AbstractContainerMenu menu) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        int slots = containerSlotCount(menu, inventory);
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

    private int countEmptyKitBoxes() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            if (isEmptyKitBox(inventory.getItem(index))) {
                count++;
            }
        }
        return count;
    }

    /** 上游 {@code bp.a(Set, Set)}：按物品签名统计背包里已有数量。*/
    private Map<KitKey, Integer> countInventoryItems(Set<KitKey> keys) {
        Map<KitKey, Integer> counts = new HashMap<>();
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || keys.isEmpty()) {
            return counts;
        }
        Inventory inventory = player.getInventory();
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            ItemStack stack = inventory.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }
            for (KitKey key : keys) {
                if (key.matches(stack)) {
                    counts.merge(key, stack.getCount(), Integer::sum);
                    break;
                }
            }
        }
        return counts;
    }

    // ------------------------------------------------------------------
    // 潜影盒内容（上游 by）
    // ------------------------------------------------------------------

    private static boolean isShulkerBox(Item item) {
        return item != null && SHULKER_BOXES.contains(item);
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return stack != null && !stack.isEmpty() && isShulkerBox(stack.getItem());
    }

    /** 读 27 格内容；没有 {@code CONTAINER} 组件时返回 27 个空栈。*/
    private static ItemStack[] readContents(ItemStack box) {
        ItemStack[] stacks = new ItemStack[KIT_SLOT_COUNT];
        Arrays.fill(stacks, ItemStack.EMPTY);
        ItemContainerContents contents = box.get(DataComponents.CONTAINER);
        if (contents != null) {
            NonNullList<ItemStack> list = NonNullList.withSize(KIT_SLOT_COUNT, ItemStack.EMPTY);
            contents.copyInto(list);
            for (int index = 0; index < KIT_SLOT_COUNT; index++) {
                stacks[index] = list.get(index);
            }
        }
        return stacks;
    }

    /** 上游 {@code by.a()}：盒子是否为空（没有物品）。*/
    private static boolean isContentsEmpty(ItemStack box) {
        if (!isShulkerBox(box)) {
            return false;
        }
        for (ItemStack stack : readContents(box)) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isEmptyKitBox(ItemStack stack) {
        return isShulkerBox(stack) && isContentsEmpty(stack);
    }

    private boolean boxContains(ItemStack box, KitKey key) {
        for (ItemStack stack : readContents(box)) {
            if (key.matches(stack)) {
                return true;
            }
        }
        return false;
    }

    /** 上游 {@code by.a_List_toBoolean(A)}：盒子内容与主手模板逐格一致。*/
    private boolean boxMatchesTemplate(ItemStack box) {
        ItemStack[] contents = readContents(box);
        if (contents.length != this.template.size()) {
            return false;
        }
        for (int index = 0; index < contents.length; index++) {
            TemplateSlot slot = this.template.get(index);
            if (slot == null) {
                if (!contents[index].isEmpty()) {
                    return false;
                }
            } else if (!slot.matches(contents[index])) {
                return false;
            }
        }
        return true;
    }

    /** 上游 {@code Z_.D()} 的谓词：非空且不是模板 Kit。*/
    private boolean isMiscKitBox(ItemStack stack) {
        return isShulkerBox(stack) && !isContentsEmpty(stack) && !this.boxMatchesTemplate(stack);
    }

    // ------------------------------------------------------------------
    // 杂项
    // ------------------------------------------------------------------

    private boolean isTooFar(KitPos position) {
        LocalPlayer player = this.evernightMinecraft.player;
        return player == null || player.position().distanceTo(position.pathPos().getCenter()) > REACH_DISTANCE;
    }

    /** 上游 {@code bC_.a_Set_toG}：在还需要的物品里挑离玩家最近的、有展示框的 Kit。*/
    private Map.Entry<KitKey, KitPos> nearestKitPosition(Set<KitKey> keys) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        Vec3 playerPosition = player.position();
        Map.Entry<KitKey, KitPos> best = null;
        double bestDistance = Double.MAX_VALUE;
        for (KitKey key : keys) {
            KitPos position = this.kitPositions.get(key);
            if (position == null) {
                continue;
            }
            double distance = position.pathPos().getCenter().distanceToSqr(playerPosition);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = Map.entry(key, position);
            }
        }
        return best;
    }

    private static KitPos nearest(KitPos candidate, KitPos current, LocalPlayer player) {
        if (current == null) {
            return candidate;
        }
        Vec3 playerPosition = player.position();
        double candidateDistance = candidate.pathPos().getCenter().distanceToSqr(playerPosition);
        double currentDistance = current.pathPos().getCenter().distanceToSqr(playerPosition);
        return candidateDistance < currentDistance ? candidate : current;
    }

    private static Direction horizontalDirectionFrom(BlockPos from, BlockPos to) {
        int deltaX = to.getX() - from.getX();
        int deltaZ = to.getZ() - from.getZ();
        if (Math.abs(deltaX) >= Math.abs(deltaZ)) {
            if (deltaX > 0) {
                return Direction.EAST;
            }
            if (deltaX < 0) {
                return Direction.WEST;
            }
        } else {
            if (deltaZ > 0) {
                return Direction.SOUTH;
            }
            if (deltaZ < 0) {
                return Direction.NORTH;
            }
        }
        return null;
    }

    private void resetDelay() {
        this.delayTicks = this.delaySetting.getValue();
    }

    private static String describeItems(Set<Item> items) {
        if (items == null || items.isEmpty()) {
            return "-";
        }
        List<String> names = new ArrayList<>(items.size());
        for (Item item : items) {
            names.add(item.getName().getString());
        }
        return String.join(", ", names);
    }

    private static String describeKeys(Set<KitKey> keys) {
        if (keys.isEmpty()) {
            return "-";
        }
        List<String> names = new ArrayList<>(keys.size());
        for (KitKey key : keys) {
            names.add(key.describe());
        }
        return String.join(", ", names);
    }

    private String describeKitFrames() {
        if (this.kitPositions.isEmpty()) {
            return "-";
        }
        return describeKeys(this.kitPositions.keySet());
    }

    private static String describeThrowable(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    /**
     * 上游 {@code info()/warning()/error()}。
     *
     * <p>TODO(port): 上游的 warning/error 带红色/黄色前缀；Evernight 1.0.3 只有一种聊天签名。
     */
    private void info(String translationKey, Object... args) {
        String pattern = Translations.get(translationKey);
        String message = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
    }

    private void warn(String translationKey, Object... args) {
        this.info(translationKey, args);
    }
}
