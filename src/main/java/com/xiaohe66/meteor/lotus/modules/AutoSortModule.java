package com.xiaohe66.meteor.lotus.modules;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
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
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
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
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code U_} (S自动分类) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>上游在「简易仓库」里工作：仓库里每个箱子都挂着一个展示框，框里的物品标识这个箱子装什么。
 * 模块把背包里<b>有价值的盒装物品</b>（潜影盒里只有一种已知物品）送进对应类别的箱子、把<b>散装物品</b>
 * 装进该类别箱子下方摆好的空盒里，并把<b>无法分类</b>的盒子丢进「无法整理」箱；分类顺序是
 * 「先往身上拿 → 再往箱子里放」，最后由「待整理」箱收尾。
 *
 * <p>方块位置关系照抄上游 {@code br#b(int,int)}（<b>没有改</b>）：展示框贴在支撑方块上，
 * 「框方块 +1Y + 朝向反向」是箱子（{@code m.c()}），「箱子 -2Y」是卸货位置（{@code m.d()}，
 * 也就是支撑方块下面一格），「框方块 -2Y」是摆空盒的位置（{@code m.e()}），
 * 「摆盒位 + 朝向×2」是站位（{@code m.f()}）。理解这一段之前不要「顺手修正」这些偏移，
 * 改了整套状态机就对不上了。
 *
 * <p><b>相对上游删掉的部分</b>：Meteor 自绘的分类编辑 GUI（{@code getWidget} 整段：新增/删除分类、
 * 改目标物品、改关联物品、启停、导入/导出配置、打开配置文件夹）全部删除，只保留设置；分类改为在
 * 初始化时从 {@code 散装物品配置} 指向的 JSON 读取（等价于上游「导入配置」按钮），JSON 格式不变。
 * 模块 NBT（{@code toTag/fromTag}）也不再需要，Evernight 只自动保存设置。
 *
 * <p><b>行为差异 / 未移植</b>（同见文件末尾 {@code TODO(port)}）：
 * <ul>
 *     <li>上游的「空盒/待整理/无法整理标识」是 Meteor 的<b>单物品</b>设置；Evernight 1.0.3 没有
 *         单物品设置类型，这里用 {@link RegistrySetSetting}（集合）。集合里任意一个物品都算该标识，
 *         默认值与上游一致；只选一个时行为完全相同。</li>
 *     <li>走位由 {@link BaritoneBridge} 承担：上游 {@code GoalNear(pos, range)} 在 range=0 时就是
 *         {@code GoalBlock}，因此与 {@code pathTo(BlockPos)} 等价；但上游还会关闭 Baritone 的
 *         {@code allowBreak/allowPlace}，桥接层没有这两个开关。</li>
 *     <li>上游监听 Baritone 的 {@code PathEvent}（CANCELED 回退，AT_GOAL 后续），桥接层没有事件；
 *         这里改成每 tick 轮询 {@code isPathingOrCalculating()} + 距离来判断「走到了 / 路断了」。</li>
 *     <li>上游的「快速停止键」（{@code KeybindSetting}，默认 ESC）、「重启延时」与延迟启动逻辑属于
 *         基类 {@code aw}，本模块从未用到，未移植；模块键位请用 Evernight 的模块级键位。</li>
 *     <li>上游的「修改后需重新初始化」靠 GUI 按钮；这里把初始化折成一个「初始化」开关，打开一次
 *         即重新扫描并重新读取 JSON。</li>
 *     <li>盒子内容的判空按「实际没有非空物品」；上游看 {@code CONTAINER} 组件是否存在，组件存在但
 *         27 格全空时会误判成非空，这里不会。</li>
 *     <li>打开容器用发包实现（{@link ContainerInteractionUtils#openContainer(BlockPos, boolean)} 的
 *         「脚到目标 &gt; 3 格就先 Baritone 走过去」分支不适合本仓库的 3 格高箱子，见该方法的注释）。</li>
 * </ul>
 *
 * <p>这个文件只做静态移植，没有在游戏里跑过（仓库模板 AGENTS.md 第 6 条）。
 */
public final class AutoSortModule extends AddonModule {
    private static final int INVENTORY_SIZE = ContainerInventoryMath.INVENTORY_SIZE;
    private static final int HOTBAR_SIZE = ContainerInventoryMath.HOTBAR_SIZE;
    /** 上游 br#b 的第二个参数：展示框扫描盒相对玩家 Y 的高度。*/
    private static final int SCAN_HEIGHT = 4;
    /** 上游 br#a_Vec3_toBlockPos 的 2.030625 = 1.425^2。*/
    private static final double DROP_STAND_RADIUS_SQR = 2.030625D;
    /** 上游 ag#a_m(m)：离站位超过 1 格就要走过去。*/
    private static final double STAND_DISTANCE = 1.0D;
    /** 走位判定「到了」的距离（上游靠 Baritone 的 AT_GOAL，这里用站位距离近似）。*/
    private static final double ARRIVE_DISTANCE = 2.0D;
    /** 上游 aA_#a 的「移动距离超过 1000 格就关闭」。*/
    private static final double MAX_MOVE_DISTANCE = 1000.0D;
    /** 刚发完 pathTo 时 isPathingOrCalculating() 可能短暂为 false，宽限这么多 tick 再判定路断了。*/
    private static final int WALK_GRACE_TICKS = 20;
    /** 兜底：Baritone 卡住时最多等这么久就回落。*/
    private static final int WALK_TIMEOUT_TICKS = 20 * 60;
    /** 上游 ag#a_BlockPos_Consumer 的容器同步超时。*/
    private static final long CONTAINER_SYNC_TIMEOUT_MS = 2000L;
    /** 上游 ag#c(BlockPos, Direction) 的「打开没有反应」判断窗口。*/
    private static final long OPEN_RETRY_WARN_MS = 1000L;
    private static final Gson GSON = new Gson();

    private final Setting<Integer> delaySetting;
    private final Setting<Integer> scanRangeSetting;
    private final Setting<Integer> operationCountSetting;
    private final Setting<Integer> emptyKitCountSetting;
    private final Setting<Set<Item>> emptyKitMarkerSetting;
    private final Setting<Set<Item>> pendingKitMarkerSetting;
    private final Setting<Set<Item>> unsortedKitMarkerSetting;
    private final Setting<String> bulkConfigSetting;
    private final Setting<Boolean> initializeSetting;

    private final Warehouse warehouse = new Warehouse();

    /** 上游 aw.m：当前状态。*/
    private State state = State.NONE;
    /** 上游 aw.o：关掉界面之后要去的状态。*/
    private State closeTarget;
    /** 走位结束后回到的状态（上游 aw.n / aA_#a 的 n）。*/
    private State walkResume;
    private BlockPos walkTarget;
    private int walkTicks;

    /** 上游 z：空盒位置（框里是「空盒标识」）。*/
    private SlotPos emptyBoxSlot;
    /** 上游 A：待整理位置。*/
    private SlotPos pendingSlot;
    /** 上游 B：无法整理位置。*/
    private SlotPos unsortedSlot;
    /** 上游 C：当前要处理的类别位置。*/
    private SlotPos currentSlot;
    /** 上游 D：当前要装的物品（精确到药水/附魔书/地图等组件）。*/
    private ItemKey currentKey;
    /** 上游 E：当前类别允许一起装的关联物品。*/
    private Set<Item> currentRelated;
    /** 上游 F：当前要挖掉的盒子位置。*/
    private SlotPos kitToBreak;
    /** 上游 G：待整理箱已经掏空。*/
    private boolean allDone;
    /** 上游 H：本轮已从待整理箱拿了几次。*/
    private int operations;

    /** 上游 ag.a：延迟倒计时（tick）。*/
    private int delay;
    /** 上游 ag.b：背包扫描的轮转游标。*/
    private int inventoryCursor;
    /** 上游 ag.c：容器扫描的轮转游标。*/
    private int containerCursor;
    /** 上游 ag.d：上一次打开容器是否已经确认「打开了」（CHECK=确认 / OPENING=刚发过交互）。*/
    private OpenState openState = OpenState.CHECK;
    private long lastOpenAt;
    /** 上游 ag.f：上一次交互的容器位置。*/
    private BlockPos lastContainerPos;
    /** 「初始化」开关回调的重入保护。*/
    private boolean initializing;
    private boolean baritoneMissingWarned;

    public AutoSortModule(ModuleCategory category) {
        super("lotus", "LotusAutoSort",
                "Packs valuable items into shulker boxes and sorts loose items in a Lotus warehouse.",
                category);

        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
        this.scanRangeSetting = this.add(new NumberSetting<>("ScanRange", 50, 1, 100));
        this.operationCountSetting = this.add(new NumberSetting<>("OperationCount", 18, 1, 27));
        this.emptyKitCountSetting = this.add(new NumberSetting<>("EmptyKitCount", 2, 1, 9));
        this.emptyKitMarkerSetting = this.add(
                new RegistrySetSetting<Item>("EmptyKitItems", BuiltInRegistries.ITEM, Items.SHULKER_BOX));
        this.pendingKitMarkerSetting = this.add(
                new RegistrySetSetting<Item>("PendingKitItems", BuiltInRegistries.ITEM, Items.WHITE_SHULKER_BOX));
        this.unsortedKitMarkerSetting = this.add(
                new RegistrySetSetting<Item>("UnsortedKitItems", BuiltInRegistries.ITEM, Items.BLUE_SHULKER_BOX));
        this.bulkConfigSetting = this.add(new StringSetting("BulkConfig", "warehouse/mappings.json"));
        this.initializeSetting = this.add(new BooleanSetting("Initialize", false));
        this.initializeSetting.setChangeCallback(this::onInitializeSettingChanged);
    }

    // ---------------------------------------------------------------------------------------------
    // 生命周期
    // ---------------------------------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.state = State.NONE;
        this.closeTarget = null;
        this.clearWalk();
        this.operations = 0;
        this.allDone = false;
        this.currentSlot = null;
        this.currentKey = null;
        this.currentRelated = null;
        this.kitToBreak = null;
        this.delay = 0;
        this.inventoryCursor = 0;
        this.containerCursor = 0;
        this.openState = OpenState.CHECK;
        this.lastContainerPos = null;
        this.baritoneMissingWarned = false;

        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.evernightMinecraft.level == null) {
            this.warning("addons.lotus.messages.worldUnavailable");
            return;
        }
        // 上游 U_#onActivate：没初始化就自动初始化，失败则关掉模块。
        if (!this.warehouse.isInitialized()) {
            this.warning("addons.lotus.messages.autoInitialize");
            if (!this.initialize()) {
                this.warning("addons.lotus.messages.autoInitializeFailed");
                this.disable();
                return;
            }
        }
        this.nextStep();
    }

    @Override
    public void onDisable() {
        this.currentSlot = null;
        this.operations = 0;
        this.state = State.NONE;
        this.closeTarget = null;
        this.clearWalk();
        this.closeContainerScreen();
        // 上游 aA_#onDeactivate：取消 Baritone 走位；它还会把 allowBreak/allowPlace 改回 true，
        // 桥接层没有这两个开关，见文件末尾 TODO(port)。
        BaritoneBridge baritone = ClientContext.baritoneBridge;
        if (baritone != null && baritone.isAvailable()) {
            baritone.cancelPathing();
        }
    }

    /** 上游 U_#a_Boolean：初始化开关的 onChanged。*/
    private void onInitializeSettingChanged() {
        if (!this.initializeSetting.getValue() || this.initializing) {
            return;
        }
        this.initializing = true;
        try {
            // 上游这个设置是个「按一下」的按钮：回调里立刻把值改回 false。
            this.initializeSetting.setValue(false);
            if (this.evernightMinecraft.player == null || this.evernightMinecraft.level == null) {
                this.warning("addons.lotus.messages.worldUnavailable");
                return;
            }
            this.initialize();
        } finally {
            this.initializing = false;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Tick（上游 aw#onTick + ag#d 的延迟门）
    // ---------------------------------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (this.delay > 0) {
            this.delay--;
            return;
        }
        try {
            switch (this.state) {
                case NONE -> {
                }
                case CLOSE_SCREEN -> this.closeScreenStep();
                case WALKING -> this.walkingStep();
                case NEXT -> this.nextStep();
                case TAKE_KIT -> this.takeKitStep();
                case PUT_KIT -> this.putKitStep();
                case PUT_MISC_KIT -> this.putMiscKitStep();
                case PLACE_KIT -> this.placeKitStep();
                case BREAK_KIT -> this.breakKitStep();
                case TAKE_ITEM -> this.takeItemStep();
                case PUT_ITEM -> this.putItemStep();
                case PUT_ITEM2 -> this.putRelatedItemStep();
                case TAKE_EMPTY_KIT -> this.takeEmptyKitStep();
                case PUT_EMPTY_KIT -> this.putEmptyKitStep();
                case PLACE_EMPTY_KIT -> this.placeEmptyKitStep();
            }
        } catch (Throwable throwable) {
            // 上游 aw#onTick 的兜底：打印堆栈、提示、关掉模块。
            throwable.printStackTrace();
            this.warning("addons.lotus.messages.unexpectedError", throwable.getMessage());
            this.disable();
        }
    }

    /** 上游 aw#z / aw#y：关掉当前界面，然后去 closeTarget。*/
    private void closeScreenStep() {
        this.closeContainerScreen();
        State target = this.closeTarget;
        this.closeTarget = null;
        this.state = target != null ? target : State.NONE;
        this.resetDelay();
    }

    /** 上游 aw#a_be：先关界面再去目标状态（不重置延迟）。*/
    private void closeThen(State target) {
        this.closeTarget = target;
        this.state = State.CLOSE_SCREEN;
    }

    /** 上游 aw#c_be：直接切到目标状态并重置延迟。*/
    private void switchTo(State target) {
        this.state = target;
        this.resetDelay();
    }

    /** 上游 U_#x：主调度。*/
    private void nextStep() {
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
            BlockPos target = null;
            if (itemEntity.getY() != player.getBlockY()) {
                target = this.standingPosForDrop(itemEntity.position());
            }
            if (target == null) {
                target = itemEntity.blockPosition();
            }
            if (player.position().distanceTo(Vec3.atCenterOf(target)) > STAND_DISTANCE) {
                this.info("addons.lotus.messages.pickupKit");
            }
            this.walkTo(target, State.NEXT);
            return;
        }

        // 2) 背包里的盒子：空的够数 → 去存空盒；单种类 → 去装箱 / 去无法整理箱；
        //    混合盒里只要有一个已知物品就跳过整轮（上游此处是 break，不是 continue）。
        int emptyCount = 0;
        int lastSlot = -1;
        while (true) {
            BoxContents box = this.findBoxInInventory(candidate -> true);
            if (box == null) {
                break;
            }
            int slot = this.inventoryCursor;
            if (lastSlot == slot) {
                break;
            }
            if (lastSlot == -1) {
                lastSlot = slot;
            }
            if (box.isEmpty()) {
                if (++emptyCount >= this.emptyKitCountSetting.getValue()) {
                    this.state = State.PUT_EMPTY_KIT;
                    return;
                }
                continue;
            }
            if (box.singleType()) {
                if (this.warehouse.isKnown(box.dominantKey())) {
                    this.state = State.PUT_KIT;
                } else {
                    this.state = State.PUT_MISC_KIT;
                }
                return;
            }
            boolean allUnknown = true;
            for (ItemKey key : box.counts().keySet()) {
                if (this.warehouse.isKnown(key)) {
                    allUnknown = false;
                    break;
                }
            }
            if (allUnknown) {
                this.state = State.PUT_MISC_KIT;
                return;
            }
            break;
        }

        // 3) 背包里的散装物品：先看有没有「盒装物品类别」的卸货点，再看普通分类箱。
        Map<ItemKey, Integer> inventoryItems = this.inventoryCounts(
                Set.of(player.getInventory().getSelectedSlot()));
        ItemKey categoryKey = this.warehouse.findNearestCategorySlot(inventoryItems.keySet(), player.position());
        if (categoryKey != null) {
            this.currentSlot = this.warehouse.categorySlot(categoryKey);
            this.currentRelated = this.warehouse.relatedItemsOf(categoryKey.item());
            this.state = State.PUT_ITEM2;
            return;
        }
        ItemKey miscKey = this.warehouse.findNearestMiscSlot(inventoryItems.keySet(), player.position());
        if (miscKey != null) {
            this.currentSlot = this.warehouse.miscSlot(miscKey);
            this.currentKey = miscKey;
            this.state = State.PUT_ITEM;
            return;
        }
        if (this.allDone) {
            this.warning("addons.lotus.messages.sortFinished");
            this.disable();
            return;
        }
        this.operations = 0;
        this.state = State.TAKE_ITEM;
    }

    // ---------------------------------------------------------------------------------------------
    // 各状态（上游 C/D/E/F/G/H 与 aA_/aB_ 里的对应方法）
    // ---------------------------------------------------------------------------------------------

    /** 上游 U_#C：把盒装物品放进对应类别箱子。*/
    private void putKitStep() {
        BoxContents kit = this.findBoxInInventory(
                box -> box.singleType() && this.warehouse.isKnown(box.dominantKey()));
        if (kit == null) {
            this.closeThen(State.NEXT);
            return;
        }
        ItemKey contentKey = kit.dominantKey();
        SlotPos slot = this.warehouse.miscSlot(contentKey);
        if (slot == null) {
            slot = this.warehouse.categorySlot(contentKey);
        }
        if (slot == null) {
            this.closeThen(State.NEXT);
            return;
        }
        SlotPos target = slot;
        if (this.isFar(target)) {
            this.walkTo(target.standPos(), State.PUT_KIT);
            return;
        }
        this.withOpenContainer(target.chestPos(), menu -> {
            if (this.isOpenContainerFull()) {
                // 上游这里用 a_String_toVoid：提示后停在 NONE（不会自动继续）。
                this.warnAndHalt("addons.lotus.messages.containerFull");
            } else {
                this.shiftClickInventorySlot();
                this.resetDelay();
            }
        });
    }

    /** 上游 U_#D：把无法归类的盒装物品放进「无法整理」箱。*/
    private void putMiscKitStep() {
        BoxContents kit = this.findBoxInInventory(box -> !box.isEmpty()
                && (box.singleType() ? !this.warehouse.isKnown(box.dominantKey()) : this.allTypesUnknown(box)));
        if (kit == null) {
            this.closeThen(State.NEXT);
            return;
        }
        SlotPos slot = this.unsortedSlot;
        if (slot == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isFar(slot)) {
            this.walkTo(slot.standPos(), State.PUT_MISC_KIT);
            return;
        }
        this.withOpenContainer(slot.chestPos(), menu -> {
            if (this.isOpenContainerFull()) {
                this.warnAndHalt("addons.lotus.messages.containerFull");
            } else {
                this.shiftClickInventorySlot();
                this.resetDelay();
            }
        });
    }

    /** 上游 U_#E：从「待整理」位置往身上拿东西（先拿散装物品，拿完了拿盒子）。*/
    private void takeKitStep() {
        if (this.pendingSlot == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.operations >= this.operationCountSetting.getValue()) {
            this.closeThen(State.NEXT);
            return;
        }
        if (this.isFar(this.pendingSlot)) {
            this.walkTo(this.pendingSlot.standPos(), State.TAKE_KIT);
            return;
        }
        this.withOpenContainer(this.pendingSlot.belowChestPos(), menu -> {
            ItemStack stack = this.findContainerStack(candidate -> this.warehouse.isKnown(new ItemKey(candidate)));
            if (!stack.isEmpty()) {
                this.info("addons.lotus.messages.takingItem", stack.getHoverName().getString());
                this.shiftClickContainerSlot();
                this.resetDelay();
                this.operations++;
                return;
            }
            BoxContents kit = this.findBoxInContainer(this::isKnownKit);
            if (kit == null) {
                this.allDone = true;
                this.closeThen(State.NEXT);
            } else {
                this.info("addons.lotus.messages.takingKit");
                this.shiftClickContainerSlot();
                this.closeThen(State.PLACE_KIT);
            }
        });
    }

    /** 上游 U_#F：从摆好的盒子里往身上拿散装物品，拿空了就去挖盒子。*/
    private void takeItemStep() {
        if (this.operations >= this.operationCountSetting.getValue()) {
            this.closeThen(State.NEXT);
            return;
        }
        SlotPos slot = this.pendingSlot;
        if (slot == null) {
            this.state = State.NEXT;
            return;
        }
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }
        BlockState kitState = level.getBlockState(slot.kitPos());
        if (kitState.isAir()) {
            this.closeThen(State.PLACE_KIT);
            return;
        }
        if (!(kitState.getBlock() instanceof ShulkerBoxBlock)) {
            this.warnAndHalt("addons.lotus.messages.kitPositionOccupied");
            return;
        }
        if (this.isFar(slot)) {
            this.walkTo(slot.standPos(), State.TAKE_ITEM);
            return;
        }
        this.withOpenContainer(slot.kitPos(), menu -> {
            ItemStack stack = this.findContainerStack(candidate -> this.warehouse.isKnown(new ItemKey(candidate)));
            if (stack.isEmpty()) {
                this.kitToBreak = slot;
                this.closeThen(State.BREAK_KIT);
            } else {
                this.info("addons.lotus.messages.takingItem", stack.getHoverName().getString());
                this.shiftClickContainerSlot();
                this.resetDelay();
                this.operations++;
            }
        });
    }

    /** 上游 U_#G：把该类别的主物品装进摆好的盒子。*/
    private void putItemStep() {
        SlotPos slot = this.currentSlot;
        if (slot == null || this.currentKey == null) {
            this.closeThen(State.NEXT);
            return;
        }
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }
        BlockState kitState = level.getBlockState(slot.kitPos());
        if (kitState.isAir()) {
            this.closeThen(State.PLACE_EMPTY_KIT);
            return;
        }
        if (!(kitState.getBlock() instanceof ShulkerBoxBlock)) {
            this.warnAndHalt("addons.lotus.messages.kitPositionOccupied");
            return;
        }
        if (this.isFar(slot)) {
            this.walkTo(slot.standPos(), State.PUT_ITEM);
            return;
        }
        ItemKey wanted = this.currentKey;
        ItemStack stack = this.findInventoryStack(
                candidate -> this.inventoryCursor != this.selectedSlot() && new ItemKey(candidate).equals(wanted));
        if (stack.isEmpty()) {
            this.closeThen(State.NEXT);
            return;
        }
        this.withOpenContainer(slot.kitPos(), menu -> {
            if (this.isOpenContainerFull()) {
                // 上游 info("[%s]盒子满了") 之后去挖盒子。
                this.info("addons.lotus.messages.kitFull");
                this.kitToBreak = slot;
                this.closeThen(State.BREAK_KIT);
            } else {
                this.shiftClickInventorySlot();
                this.resetDelay();
            }
        });
    }

    /** 上游 U_#H：把同类别里允许一起装的散装物品放进类别箱子。*/
    private void putRelatedItemStep() {
        SlotPos slot = this.currentSlot;
        Set<Item> related = this.currentRelated;
        if (slot == null || related == null) {
            this.closeThen(State.NEXT);
            return;
        }
        if (this.isFar(slot)) {
            this.walkTo(slot.standPos(), State.PUT_ITEM2);
            return;
        }
        ItemStack stack = this.findInventoryStack(
                candidate -> this.inventoryCursor != this.selectedSlot() && related.contains(candidate.getItem()));
        if (stack.isEmpty()) {
            this.closeThen(State.NEXT);
            return;
        }
        this.withOpenContainer(slot.chestPos(), menu -> {
            if (this.isOpenContainerFull()) {
                this.warning("addons.lotus.messages.containerFullWait");
                this.setDelay(this.delaySetting.getValue() * 10);
            } else {
                this.shiftClickInventorySlot();
                this.resetDelay();
            }
        });
    }

    /** 上游 U_#a_m_int + aB_#a_m_int：从空盒箱拿空盒。*/
    private void takeEmptyKitStep() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        int emptyBoxes = 0;
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (ShulkerBoxUtils.isShulkerBoxItem(stack.getItem()) && new BoxContents(stack).isEmpty()) {
                emptyBoxes++;
            }
        }
        if (emptyBoxes >= this.emptyKitCountSetting.getValue()) {
            this.closeThen(State.PLACE_EMPTY_KIT);
            return;
        }
        SlotPos slot = this.emptyBoxSlot;
        if (slot == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isFar(slot)) {
            this.info("addons.lotus.messages.takeEmptyKitDistanceTooFar");
            this.walkTo(slot.standPos(), State.TAKE_EMPTY_KIT);
            return;
        }
        this.withOpenContainer(slot.belowChestPos(), menu -> {
            BoxContents empty = this.findBoxInContainer(BoxContents::isEmpty);
            if (empty == null) {
                this.warning("addons.lotus.messages.emptyKitChestEmpty");
                this.disable();
            } else {
                this.shiftClickContainerSlot();
                this.resetDelay();
            }
        });
    }

    /** 上游 aB_#b_m：把空盒存进空盒箱。*/
    private void putEmptyKitStep() {
        BoxContents empty = this.findBoxInInventory(BoxContents::isEmpty);
        if (empty == null) {
            this.closeThen(State.NEXT);
            return;
        }
        SlotPos slot = this.emptyBoxSlot;
        if (slot == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isFar(slot)) {
            this.info("addons.lotus.messages.storeDistanceTooFar");
            this.walkTo(slot.standPos(), State.PUT_EMPTY_KIT);
            return;
        }
        this.withOpenContainer(slot.chestPos(), menu -> {
            if (this.isOpenContainerFull()) {
                this.warnAndHalt("addons.lotus.messages.storageFull");
            } else {
                this.shiftClickInventorySlot();
                this.closeThen(State.NEXT);
            }
        });
    }

    /** 上游 aB_#c_m：在类别位置摆一个空盒。*/
    private void placeEmptyKitStep() {
        SlotPos slot = this.currentSlot;
        if (slot == null) {
            this.state = State.NEXT;
            return;
        }
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }
        BlockState kitState = level.getBlockState(slot.kitPos());
        if (kitState.getBlock() instanceof ShulkerBoxBlock) {
            this.state = State.PUT_ITEM;
            return;
        }
        if (!kitState.isAir()) {
            this.warnAndHalt("addons.lotus.messages.kitPositionOccupied");
            return;
        }
        BoxContents empty = this.findBoxInInventory(BoxContents::isEmpty);
        if (empty == null) {
            this.state = State.TAKE_EMPTY_KIT;
            return;
        }
        if (this.isFar(slot)) {
            this.walkTo(slot.standPos(), State.PLACE_EMPTY_KIT);
            return;
        }
        int boxSlot = this.inventoryCursor;
        if (!this.placeHeldBlock(slot.kitPos(), boxSlot)) {
            return;
        }
        this.switchTo(State.PUT_ITEM);
    }

    /** 上游 aB_#a_m_Predicate：在待整理位置摆一个「盒装物品」盒子。*/
    private void placeKitStep() {
        SlotPos slot = this.pendingSlot;
        if (slot == null) {
            this.state = State.NEXT;
            return;
        }
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }
        BlockState kitState = level.getBlockState(slot.kitPos());
        if (kitState.getBlock() instanceof ShulkerBoxBlock) {
            this.state = State.TAKE_ITEM;
            return;
        }
        if (!kitState.isAir()) {
            this.warnAndHalt("addons.lotus.messages.kitPositionOccupied");
            return;
        }
        BoxContents kit = this.findBoxInInventory(this::isKnownKit);
        if (kit == null) {
            this.state = State.TAKE_KIT;
            return;
        }
        if (this.isFar(slot)) {
            this.info("addons.lotus.messages.goTakeKit");
            this.walkTo(slot.standPos(), State.PLACE_KIT);
            return;
        }
        int kitSlot = this.inventoryCursor;
        if (!this.placeHeldBlock(slot.kitPos(), kitSlot)) {
            return;
        }
        this.switchTo(State.TAKE_ITEM);
    }

    /** 上游 aB_#d_m：把摆好的盒子挖下来。*/
    private void breakKitStep() {
        SlotPos slot = this.kitToBreak;
        if (slot == null) {
            this.state = State.NEXT;
            return;
        }
        if (this.isFar(slot)) {
            this.info("addons.lotus.messages.breakDistanceTooFar");
            this.walkTo(slot.standPos(), State.BREAK_KIT);
            return;
        }
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }
        BlockPos kitPos = slot.kitPos();
        if (!ShulkerBoxUtils.isShulkerBoxItem(level.getBlockState(kitPos).getBlock().asItem())) {
            this.state = State.NEXT;
            this.setDelay(20);
            return;
        }
        this.selectPickaxe();
        this.breakBlockAt(kitPos);
    }

    // ---------------------------------------------------------------------------------------------
    // 走位（上游 aA_：Baritone GoalNear + PathEvent）
    // ---------------------------------------------------------------------------------------------

    /**
     * 上游 aA_#a / aA_#a_m_String_be：离目标超过 1 格就交给 Baritone 走过去。
     *
     * <p>TODO(port): 上游用的是 {@code GoalNear(pos, range)}，本模块所有调用 range 都是 0，
     * 也就是 {@code GoalBlock}，所以和桥接层的 {@code pathTo(BlockPos)} 等价；但如果以后要移植
     * 带范围的走位（例如「走到附近 3 格」），BaritoneBridge 没有 GoalNear，需要新接口。
     */
    private void walkTo(BlockPos target, State resume) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || target == null) {
            return;
        }
        double distance = player.position().distanceTo(Vec3.atCenterOf(target));
        if (distance > MAX_MOVE_DISTANCE) {
            this.warning("addons.lotus.messages.tooFar");
            this.disable();
            return;
        }
        if (distance <= STAND_DISTANCE) {
            this.state = resume;
            return;
        }
        BaritoneBridge baritone = ClientContext.baritoneBridge;
        if (baritone != null && baritone.isAvailable()) {
            this.walkTarget = target;
            this.walkResume = resume;
            this.walkTicks = 0;
            baritone.pathTo(target);
            this.state = State.WALKING;
        } else {
            // TODO(port): 上游把这个模块绑死在 Baritone 上（没装就加载不了）。Evernight 里
            //  ClientContext.baritoneBridge 可能是 DisabledBaritoneBridge，这里退化为原地尝试，
            //  并把重试放慢到 1 秒一次，避免每 tick 循环。
            if (!this.baritoneMissingWarned) {
                this.baritoneMissingWarned = true;
                this.warning("addons.lotus.messages.baritoneUnavailable");
            }
            this.setDelay(20);
            this.state = resume;
        }
    }

    /**
     * 上游 aA_#onPathEvent：CANCELED 时回落到 n。
     *
     * <p>TODO(port): BaritoneBridge 没有 PathEvent，也就没有 CANCELED / AT_GOAL。这里每 tick 轮询
     * {@code isPathingOrCalculating()} 和到站位的距离来判断「到了」或「路断了」，并加了一个宽限期
     * 与超时兜底；上游的「下一段路」事件（NEXT_SEGMENT）在移植版里不存在。
     */
    private void walkingStep() {
        if (this.walkTarget == null || this.walkResume == null) {
            this.state = State.NEXT;
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        BaritoneBridge baritone = ClientContext.baritoneBridge;
        boolean available = baritone != null && baritone.isAvailable();
        this.walkTicks++;
        double distance = player.position().distanceTo(Vec3.atCenterOf(this.walkTarget));
        boolean arrived = distance <= ARRIVE_DISTANCE;
        boolean pathFinished = !available || !baritone.isPathingOrCalculating();
        if (arrived || this.walkTicks > WALK_TIMEOUT_TICKS
                || (pathFinished && this.walkTicks > WALK_GRACE_TICKS)) {
            if (available) {
                baritone.cancelPathing();
            }
            State resume = this.walkResume;
            this.clearWalk();
            this.state = resume;
        }
    }

    private void clearWalk() {
        this.walkTarget = null;
        this.walkResume = null;
        this.walkTicks = 0;
    }

    /**
     * 上游 br#a_Vec3_toBlockPos：给地上的掉落物找一个和玩家同层的落脚点。
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
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos candidate = center.offset(dx, 0, dz).atY(playerY);
                double offsetX = candidate.getX() + 0.5D - dropPosition.x;
                double offsetZ = candidate.getZ() + 0.5D - dropPosition.z;
                if (offsetX * offsetX + offsetZ * offsetZ > DROP_STAND_RADIUS_SQR) {
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

    /** 上游 br#a_BlockPos_toBoolean：脚下空、头空、下面有碰撞。*/
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
     * 上游 ag#a_BlockPos_Consumer：没开就开；开错了/没同步就重试；同步好了再执行动作。
     */
    private void withOpenContainer(BlockPos position, Consumer<AbstractContainerMenu> action) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu) {
            this.openContainerAt(position);
            return;
        }
        if (!position.equals(this.lastContainerPos)) {
            // 目标换了但旧容器还开着：先关掉，下一 tick 再开新的（上游靠 CLOSE_SCREEN 状态保证）。
            this.closeContainerScreen();
            this.resetDelay();
            return;
        }
        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) {
            this.openState = OpenState.CHECK;
            this.closeContainerScreen();
            this.warning("addons.lotus.messages.wrongContainer");
            this.resetDelay();
            return;
        }
        if (menu.getStateId() <= 0) {
            if (System.currentTimeMillis() - this.lastOpenAt > CONTAINER_SYNC_TIMEOUT_MS) {
                this.warning("addons.lotus.messages.containerNotSynced");
                this.openState = OpenState.CHECK;
                this.lastContainerPos = null;
                this.closeContainerScreen();
            }
            this.resetDelay();
            return;
        }
        this.openState = OpenState.CHECK;
        action.accept(menu);
    }

    /**
     * 上游 ag#c(BlockPos, Direction) + bn#c_BlockPos_toVoid：朝方块发一次右键。
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
            this.warning("addons.lotus.messages.openNoResponse");
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
            this.lastContainerPos = position;
        }
        this.resetDelay();
    }

    /** 上游 bp#a()：不是玩家背包界面就关掉。*/
    private void closeContainerScreen() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || player.containerMenu instanceof InventoryMenu) {
            return;
        }
        ContainerInteractionUtils.closeScreen();
        if (!(player.containerMenu instanceof InventoryMenu)) {
            player.closeContainer();
        }
    }

    /** 上游 bp#g()：当前菜单里属于「容器」的槽位数量。*/
    private int containerSlotCount() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return 0;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int playerSlots = player.getInventory().getNonEquipmentItems().size();
        if (menu instanceof InventoryMenu) {
            return menu.slots.size() - playerSlots - 1;
        }
        return menu.slots.size() - playerSlots;
    }

    /**
     * 背包下标 → 当前菜单槽位。
     *
     * <p>不能用 {@link InventoryUtils#toMenuSlot(int)}：它按玩家背包菜单（主背包 9..44）算，
     * 箱子菜单里玩家的主背包从「容器槽位数」开始。
     */
    private int menuSlotForInventorySlot(int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || inventorySlot < 0 || inventorySlot >= INVENTORY_SIZE) {
            return -1;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int playerSlots = player.getInventory().getNonEquipmentItems().size();
        if (menu instanceof InventoryMenu) {
            return inventorySlot >= HOTBAR_SIZE ? inventorySlot : inventorySlot + 36;
        }
        int containerSlots = menu.slots.size() - playerSlots;
        int mainInventorySize = INVENTORY_SIZE - HOTBAR_SIZE;
        return inventorySlot >= HOTBAR_SIZE
                ? containerSlots + (inventorySlot - HOTBAR_SIZE)
                : containerSlots + mainInventorySize + inventorySlot;
    }

    /** 上游 ag#q()：容器槽位全满。*/
    private boolean isOpenContainerFull() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int slots = this.containerSlotCount();
        for (int slot = 0; slot < slots; slot++) {
            if (menu.getSlot(slot).getItem().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 上游 InvUtils.shiftClick().slot(t())：把背包里刚找到的那一格 shift 进容器。*/
    private void shiftClickInventorySlot() {
        int menuSlot = this.menuSlotForInventorySlot(this.inventoryCursor);
        if (menuSlot >= 0) {
            InventoryUtils.clickSlot(menuSlot, 0, ClickType.QUICK_MOVE);
        }
    }

    /** 上游 InvUtils.shiftClick().slotId(t())：把容器里刚找到的那一格 shift 进背包。*/
    private void shiftClickContainerSlot() {
        InventoryUtils.clickSlot(this.containerCursor, 0, ClickType.QUICK_MOVE);
    }

    private int selectedSlot() {
        LocalPlayer player = this.evernightMinecraft.player;
        return player == null ? -1 : player.getInventory().getSelectedSlot();
    }

    private void resetDelay() {
        this.delay = this.delaySetting.getValue();
    }

    private void setDelay(int ticks) {
        this.delay = Math.max(0, ticks);
    }

    // ---------------------------------------------------------------------------------------------
    // 背包 / 容器扫描（上游 ag#a_Function / b_Function 与 bp 的语义）
    // ---------------------------------------------------------------------------------------------

    private ItemStack findInventoryStack(Predicate<ItemStack> predicate) {
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

    private ItemStack findContainerStack(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int slots = this.containerSlotCount();
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

    private BoxContents findBoxInContainer(Predicate<BoxContents> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int slots = this.containerSlotCount();
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

    /** 上游 bp#a(Set, Set)：背包里（排除指定槽位）每个物品的合计数量。*/
    private Map<ItemKey, Integer> inventoryCounts(Set<Integer> excluded) {
        Map<ItemKey, Integer> counts = new LinkedHashMap<>();
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return counts;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (excluded.contains(slot)) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            counts.merge(new ItemKey(stack), stack.getCount(), Integer::sum);
        }
        return counts;
    }

    /** 上游 by#a_by：盒子里任意一种物品是「已知类别」。*/
    private boolean isKnownKit(BoxContents box) {
        Map<ItemKey, Integer> counts = box.counts();
        if (counts.isEmpty()) {
            return false;
        }
        return this.warehouse.isKnown(counts.keySet().iterator().next());
    }

    private boolean allTypesUnknown(BoxContents box) {
        for (ItemKey key : box.counts().keySet()) {
            if (this.warehouse.isKnown(key)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------------
    // 初始化：扫描仓库、读取散装物品配置（上游 U_#w、bC_、bk、br#b）
    // ---------------------------------------------------------------------------------------------

    /** 上游 U_#w()：重置 + 读配置 + 扫仓库 + 校验三个标识。*/
    private boolean initialize() {
        this.resetWarehouse();
        Map<Item, BulkCategory> categories = this.loadBulkCategories();
        Map<ItemKey, SlotPos> frames = this.scanSlots();
        this.warehouse.rebuild(frames, categories);

        this.emptyBoxSlot = this.takeMarker(frames, this.emptyKitMarkerSetting);
        this.pendingSlot = this.takeMarker(frames, this.pendingKitMarkerSetting);
        this.unsortedSlot = this.takeMarker(frames, this.unsortedKitMarkerSetting);

        if (this.emptyBoxSlot == null) {
            this.warning("addons.lotus.messages.missingEmptyKitFrame", this.markerNames(this.emptyKitMarkerSetting));
            return false;
        }
        if (this.pendingSlot == null) {
            this.warning("addons.lotus.messages.missingPendingKitFrame", this.markerNames(this.pendingKitMarkerSetting));
            return false;
        }
        if (this.unsortedSlot == null) {
            this.warning("addons.lotus.messages.missingMiscKitFrame", this.markerNames(this.unsortedKitMarkerSetting));
            return false;
        }
        this.info("addons.lotus.messages.initializeDone");
        return true;
    }

    /** 上游 U_#J()。*/
    private void resetWarehouse() {
        this.warehouse.reset();
        this.emptyBoxSlot = null;
        this.pendingSlot = null;
        this.unsortedSlot = null;
        this.currentSlot = null;
        this.currentKey = null;
        this.currentRelated = null;
        this.kitToBreak = null;
        this.allDone = false;
        this.operations = 0;
    }

    /**
     * 上游 br#b(int, int)：扫展示框，按「框里物品」建立「箱子 / 卸货点 / 摆盒位 / 站位」。
     *
     * <p>这里逐行照抄原始偏移：{@code 框方块 +1Y + 朝向反向 = 箱子}、
     * {@code 框方块 -2Y = 摆盒位}、{@code 箱子 -2Y = 卸货点}、{@code 摆盒位 + 朝向*2 = 站位}。
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
            ItemStack displayed = frame.getItem();
            ItemKey key = new ItemKey(displayed);
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
            if (kitPos.getY() != playerY) {
                continue;
            }
            BlockPos standPos = kitPos.relative(viewDirection, 2);
            BlockPos belowChestPos = chestPos.offset(0, -2, 0);
            SlotPos slot = new SlotPos(key, framePos, chestPos, belowChestPos, kitPos, standPos);
            SlotPos existing = slots.get(key);
            if (existing == null) {
                slots.put(key, slot);
            } else {
                this.warning("addons.lotus.messages.duplicateFrame", key.displayName());
                if (slot.standPos().getCenter().distanceTo(playerPosition)
                        < existing.standPos().getCenter().distanceTo(playerPosition)) {
                    slots.put(key, slot);
                }
            }
        }
        return slots;
    }

    /** 上游 br#b 的「remove(标识)」：把匹配的展示框从 map 里取走，多个时取最近的。*/
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
                    : playerPosition.distanceTo(candidate.standPos().getCenter());
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

    /**
     * 上游 U_#w() 的「初始化」设置只是单物品；这里用集合，任意一个选中物品都算该标识。
     */
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

    /** 上游 bk#c/bk#b：从 JSON 读散装物品分类（{@code {"目标物品id":["关联物品id", ...]}}）。*/
    private Map<Item, BulkCategory> loadBulkCategories() {
        Map<Item, BulkCategory> categories = new LinkedHashMap<>();
        Path file = this.bulkConfigPath();
        if (!Files.exists(file)) {
            this.warning("addons.lotus.messages.configMissing", file);
            return categories;
        }
        try {
            JsonObject root = GSON.fromJson(Files.readString(file), JsonObject.class);
            if (root == null) {
                this.warning("addons.lotus.messages.configInvalid", file);
                return categories;
            }
            for (String targetId : root.keySet()) {
                Item target = parseBulkItem(targetId);
                if (target == null) {
                    this.warning("addons.lotus.messages.unknownTargetItem", targetId);
                    continue;
                }
                Set<Item> related = new LinkedHashSet<>();
                JsonElement element = root.get(targetId);
                if (element != null && element.isJsonArray()) {
                    for (JsonElement relatedElement : element.getAsJsonArray()) {
                        String relatedId = relatedElement.getAsString();
                        Item relatedItem = parseBulkItem(relatedId);
                        if (relatedItem == null) {
                            this.warning("addons.lotus.messages.unknownRelatedItem", relatedId);
                        } else {
                            related.add(relatedItem);
                        }
                    }
                }
                categories.put(target, new BulkCategory(target, related, true));
            }
        } catch (IOException | RuntimeException failure) {
            this.warning("addons.lotus.messages.configLoadFailed", file);
            return categories;
        }
        this.info("addons.lotus.messages.configLoaded", categories.size());
        return categories;
    }

    /**
     * 上游 bl#e：相对路径基于 {@code <游戏目录>/Lotus}（为了让老仓库配置照旧可用），
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
    // 放方块 / 挖方块（上游 bp#a(slot, Runnable) + bn#d_BlockPos_toBoolean / BlockUtils#breakBlock）
    // ---------------------------------------------------------------------------------------------

    /**
     * 把背包第 slot 格的方块拿在手上，在 position 放下去，然后关掉界面。
     *
     * @return 是否真的执行了放置；容器界面还开着时先关界面并返回 false，让状态下一 tick 重试
     */
    private boolean placeHeldBlock(BlockPos position, int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        if (!(player.containerMenu instanceof InventoryMenu)) {
            // 背包下标 → 菜单槽位的换算只在玩家背包菜单下成立，先关界面再放。
            this.closeContainerScreen();
            this.resetDelay();
            return false;
        }
        this.selectForPlacement(inventorySlot);
        BlockInteractionUtils.placeBlock(position, false, InteractionHand.MAIN_HAND);
        this.closeContainerScreen();
        return true;
    }

    /** 上游 bp#a(int, Runnable)：保证这一格被选中/换到手上。*/
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

    /** 上游 aB_.a：挖盒子前把钻石/下界合金镐换到手上。*/
    private void selectPickaxe() {
        int hotbarSlot = InventoryUtils.findHotbarSlot(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
        if (hotbarSlot >= 0) {
            InventoryUtils.selectHotbarSlot(hotbarSlot);
            return;
        }
        int inventorySlot = InventoryUtils.findInventorySlot(Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE);
        if (inventorySlot >= 0) {
            InventoryUtils.swapIntoSelectedSlot(inventorySlot);
        }
    }

    /**
     * 上游 BlockUtils#breakBlock(pos, true)：朝方块发 START + STOP_DESTROY_BLOCK。
     *
     * <p>TODO(port): 上游用 Meteor 的 BlockUtils#breakBlock 并先由 bt 旋转到方块；这里改成直接发
     * 协议包（不旋转），服务器端会做一次距离/朝向校验，近距离正常可用，但不等价于原实现的完整流程。
     */
    private void breakBlockAt(BlockPos position) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        Direction direction = Direction.getApproximateNearest(
                player.getEyePosition().subtract(Vec3.atCenterOf(position)));
        PacketSender.sendPlayerAction(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, position, direction);
        PacketSender.sendPlayerAction(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, position, direction);
        player.swing(InteractionHand.MAIN_HAND);
    }

    // ---------------------------------------------------------------------------------------------
    // 文本（玩家可见文本全部走语言表）
    // ---------------------------------------------------------------------------------------------

    private void info(String translationKey, Object... args) {
        this.print(translationKey, args);
    }

    /**
     * 上游 Meteor 的 warning 有红色前缀，Evernight 只有一种聊天签名。
     *
     * <p>TODO(port): 若要区分 warning 颜色，需要本体提供带样式的输出接口；目前统一用模块签名。
     */
    private void warning(String translationKey, Object... args) {
        this.print(translationKey, args);
    }

    private void print(String translationKey, Object... args) {
        String pattern = Translations.get(translationKey);
        String message = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
    }

    /** 上游 a_String_toVoid：提示后停在 NONE（模块不会自己继续，需要手动重开）。*/
    private void warnAndHalt(String translationKey, Object... args) {
        this.warning(translationKey, args);
        this.state = State.NONE;
        this.closeTarget = State.NONE;
    }

    private boolean isFar(SlotPos slot) {
        LocalPlayer player = this.evernightMinecraft.player;
        return player == null || player.position().distanceTo(slot.standPos().getCenter()) > STAND_DISTANCE;
    }

    // ---------------------------------------------------------------------------------------------
    // 内部数据结构（上游 g / by / l / m / aC_ / bC_ 的等价物）
    // ---------------------------------------------------------------------------------------------

    private enum State {
        NONE,
        CLOSE_SCREEN,
        WALKING,
        NEXT,
        TAKE_KIT,
        PUT_KIT,
        PUT_MISC_KIT,
        PLACE_KIT,
        BREAK_KIT,
        TAKE_ITEM,
        PUT_ITEM,
        PUT_ITEM2,
        TAKE_EMPTY_KIT,
        PUT_EMPTY_KIT,
        PLACE_EMPTY_KIT
    }

    private enum OpenState {
        CHECK,
        OPENING
    }

    /**
     * 上游 {@code g}：物品身份。包含药水/附魔书/地图/不祥之瓶的组件，避免把不同药水当成同一种。
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

        /** 上游 bm#b_ItemStack_toResourceKey：附魔书取「满级的那一条」附魔。*/
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

        /** 上游 g#e()：消息里显示的名字。*/
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

    /** 上游 {@code by}：潜影盒内容。只读「非空槽位」，因为本模块不关心槽位顺序。*/
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

        /** 上游 by#a()。*/
        private boolean isEmpty() {
            return this.items().isEmpty();
        }

        /** 上游 by#i()。*/
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

        /** 上游 by#d()：只装了一种物品。*/
        private boolean singleType() {
            return this.counts().size() == 1;
        }

        /** 上游 by#b()：最后一种非空物品（单种类时就是它）。*/
        private ItemKey dominantKey() {
            ItemKey key = new ItemKey(Items.AIR);
            for (ItemStack inner : this.items()) {
                key = new ItemKey(inner);
            }
            return key;
        }
    }

    /** 上游 {@code aC_}：一条散装物品分类。*/
    private record BulkCategory(Item target, Set<Item> related, boolean enabled) {
    }

    /**
     * 上游 {@code m}：一个展示框对应的五个位置。
     *
     * <p>字段对应上游访问器：{@code framePos = b()}、{@code chestPos = c()}、
     * {@code belowChestPos = d()}、{@code kitPos = e()}、{@code standPos = f()}。
     * {@code framePos} 在原模块里也没有被读取，留着是为了和 {@code m} 对齐。
     */
    private record SlotPos(ItemKey key, BlockPos framePos, BlockPos chestPos,
                           BlockPos belowChestPos, BlockPos kitPos, BlockPos standPos) {
    }

    /** 上游 {@code bC_}：仓库索引。*/
    private static final class Warehouse {
        private Map<Item, BulkCategory> categories = new HashMap<>();
        private Map<ItemKey, SlotPos> allSlots = new HashMap<>();
        /** 上游 bC_.d：装了盒子的展示框位置。U_ 从不查询它，保留是为了和上游结构一一对应。*/
        private Map<ItemKey, SlotPos> kitSlots = new HashMap<>();
        private Map<ItemKey, SlotPos> bulkSlots = new HashMap<>();
        private Map<Item, SlotPos> itemSlots = new HashMap<>();
        private Map<Item, Item> relatedToTarget = new HashMap<>();

        private void reset() {
            this.categories = new HashMap<>();
            this.allSlots = new HashMap<>();
            this.kitSlots = new HashMap<>();
            this.bulkSlots = new HashMap<>();
            this.itemSlots = new HashMap<>();
            this.relatedToTarget = new HashMap<>();
        }

        /** 上游 bC_#a：拆成「盒装物品位置 / 类别位置 / 普通物品位置」。*/
        private void rebuild(Map<ItemKey, SlotPos> frames, Map<Item, BulkCategory> categories) {
            this.categories = categories;
            this.itemSlots = new HashMap<>();
            this.relatedToTarget = new HashMap<>();
            this.kitSlots = new HashMap<>();
            Map<ItemKey, SlotPos> remaining = new HashMap<>(frames);
            Iterator<Map.Entry<ItemKey, SlotPos>> iterator = remaining.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<ItemKey, SlotPos> entry = iterator.next();
                if (ShulkerBoxUtils.isShulkerBoxItem(entry.getKey().item())) {
                    this.kitSlots.put(entry.getKey(), entry.getValue());
                    iterator.remove();
                }
            }
            for (BulkCategory category : categories.values()) {
                if (!category.enabled()) {
                    continue;
                }
                SlotPos slot = remaining.get(new ItemKey(category.target()));
                if (slot == null) {
                    continue;
                }
                for (Item related : category.related()) {
                    this.itemSlots.put(related, slot);
                    this.relatedToTarget.put(related, category.target());
                }
            }
            this.bulkSlots = new HashMap<>();
            for (Map.Entry<ItemKey, SlotPos> entry : remaining.entrySet()) {
                if (!categories.containsKey(entry.getKey().item())) {
                    this.bulkSlots.put(entry.getKey(), entry.getValue());
                }
            }
            this.allSlots = remaining;
        }

        /** 上游 bC_#a()。*/
        private boolean isInitialized() {
            return !this.allSlots.isEmpty();
        }

        /** 上游 bC_#c(g)：这个物品在仓库里有归宿（普通位置或关联类别）。*/
        private boolean isKnown(ItemKey key) {
            return this.bulkSlots.containsKey(key) || this.itemSlots.containsKey(key.item());
        }

        /** 上游 bC_#b_g：普通物品位置。*/
        private SlotPos miscSlot(ItemKey key) {
            return this.bulkSlots.get(key);
        }

        /** 上游 bC_#a_g_toM：盒装物品类别位置。*/
        private SlotPos categorySlot(ItemKey key) {
            return this.itemSlots.get(key.item());
        }

        /** 上游 bC_#a_Item_toSet：同一个类别的关联物品。*/
        private Set<Item> relatedItemsOf(Item item) {
            Item target = this.relatedToTarget.get(item);
            if (target == null) {
                return Set.of();
            }
            BulkCategory category = this.categories.get(target);
            return category != null ? category.related() : Set.of();
        }

        /** 上游 bC_#b_Set_toG：背包散装物品里，离玩家最近的「盒装物品类别」卸货点。*/
        private ItemKey findNearestCategorySlot(Set<ItemKey> items, Vec3 playerPosition) {
            ItemKey best = null;
            double bestDistance = Double.MAX_VALUE;
            for (ItemKey key : items) {
                SlotPos slot = this.itemSlots.get(key.item());
                if (slot == null) {
                    continue;
                }
                double distance = playerPosition.distanceTo(slot.standPos().getCenter());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = key;
                }
            }
            return best;
        }

        /** 上游 bC_#a_Set_toG：离玩家最近的普通分类箱（按展示框的完整物品 key 匹配）。*/
        private ItemKey findNearestMiscSlot(Set<ItemKey> items, Vec3 playerPosition) {
            ItemKey best = null;
            double bestDistance = Double.MAX_VALUE;
            for (ItemKey key : items) {
                SlotPos slot = this.bulkSlots.get(key);
                if (slot == null) {
                    continue;
                }
                double distance = playerPosition.distanceTo(slot.standPos().getCenter());
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = key;
                }
            }
            return best;
        }
    }

    // TODO(port):
    //  1) Baritone 的全局开关：上游 aA_ 在 onActivate/onDeactivate 里改 allowBreak/allowPlace，
    //     BaritoneBridge 没有对应 setter，这里没改（玩家自己的 Baritone 设置可能影响寻路时的破坏/放置）。
    //  2) Baritone 的 PathEvent：上游在 CANCELED 时把状态回落到打算做的动作，在 AT_GOAL 时由
    //     goal 自然结束；桥接层没有事件，这里靠 walkingStep() 轮询 isPathingOrCalculating() + 距离，
    //     并加了 20 tick 宽限 / 60 秒超时。若以后桥接层暴露 PathEvent，应换回事件驱动。
    //  3) 快速停止键：上游基类 aw 给本模块加了一个默认 ESC 的 KeybindSetting（按下立刻关模块并提示
    //     「已快速关闭」）。Evernight 1.0.3 没有设置级键位，InputPressEvent 也只在没有界面打开时
    //     发布，而本模块一直在容器界面里工作，因此未移植；请用模块自身的键位关闭。
    //  4) 重启延时 / 延迟启动：aw.u（重启延时）、d_be()、e(int)、aw.q 这套只在其它模块用到，
    //     U_ 从未调用，未移植。
    //  5) 分类编辑 GUI：上游 getWidget() 里的「散装物品分类」表格（新增/删除/改目标/改关联/启停）、
    //     「导入配置」「导出配置」「打开配置文件夹」按钮全部删除。现在分类只从 BulkConfig 指向的
    //     JSON 读取（= 原「导入配置」），初始化时重新读取；导出与打开文件夹没有等价的设置入口。
    //  6) 模块 NBT：上游 toTag/fromTag 把分类表存进 modules.nbt；Evernight 只自动保存设置，
    //     分类表的持久化改由上面的 JSON 文件承担。
    //  7) 渲染/消息：上游 warning() 有红色前缀，这里统一用 ChatUtils.moduleSignature(this)。
    //  8) 未在游戏内验证：方块偏移、容器点击与 Baritone 走位都需要人工在仓库里实测一次。
}
