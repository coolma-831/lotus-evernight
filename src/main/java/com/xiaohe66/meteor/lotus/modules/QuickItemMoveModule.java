package com.xiaohe66.meteor.lotus.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.MultiPlayerGameModeHandleInventoryMouseClickEvent;
import evernight.client.events.ui.AbstractContainerScreenEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.events.ui.ScreenChangeEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.mixin.minecraft.gui.AbstractContainerScreenAccessor;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.lwjgl.glfw.GLFW;

/**
 * Port of {@code am} (L快捷物品操作) from Lotus (author xiaohe66), GPL-3.0.
 *
 * <p>容器界面里的快捷物品操作：
 * <ul>
 *     <li>按住「拖拽操作-键位」（默认左 Shift）拖拽鼠标：整组移动经过的物品（逐格 shift 点击）；</li>
 *     <li>按住「同类操作-键位」（默认左 Alt）点击物品：把同类型物品整组移动到另一侧；
 *         按住 Alt 拖到界面外松开：丢弃同类型物品；</li>
 *     <li>按「排序键位」（默认 R）：对当前区域（容器 / 背包，可选快捷栏）排序，同类合并后按
 *         物品注册 id、地图名、潜影盒内容、数量排序。</li>
 * </ul>
 *
 * <p>本移植的事件映射：
 * <ul>
 *     <li>Alt 点击 → {@link MultiPlayerGameModeHandleInventoryMouseClickEvent}（HEAD，可 cancel）：
 *         在 1.21.11 里容器界面的槽位点击最终都会走
 *         {@code MultiPlayerGameMode#handleInventoryMouseClick}，取消它等于不发包、不改菜单。</li>
 *     <li>Shift 拖拽 / Alt 拖到界面外 → {@link AbstractContainerScreenEvent}（每帧一次，带鼠标坐标）
 *         + GLFW 轮询鼠标左键的按下/松开状态。1.21.11 原版在「手上没有物品 + 按住 Shift」时点击只会发一次
 *         QUICK_MOVE，后续拖拽本身什么也不做，所以这里不需要取消原版拖拽，只要把经过的槽位排进队列。</li>
 *     <li>排序按键 → 每 tick 轮询（{@link MinecraftTickEvent}）。{@code InputPressEvent} 的键盘与鼠标
 *         分支都只在 {@code minecraft.screen == null} 时发布（见 Evernight {@code KeyboardHandlerMixin} /
 *         {@code MouseHandlerMixin} 的注入点），容器界面里收不到，所以沿用本体 AutoCraft 模块的做法：
 *         GLFW 状态 + 上升沿检测。</li>
 * </ul>
 *
 * <p>仍需要插件 mixin 的部分（本文件只提供公开静态入口，父级集成 mixin 后即可生效）：
 * <ul>
 *     <li>滚轮移动：{@code AbstractContainerScreen#mouseScrolled} 中调用 {@link #onMouseScroll}。</li>
 * </ul>
 *
 * <p>TODO(port): 收纳袋（Bundle）拖拽未移植。上游在 {@code HandledScreen#mouseDragged}/
 * {@code mouseReleased} 里接管「手上拿着收纳袋时拖拽」的整组取放，并靠取消鼠标按下/拖拽来避免原版进入
 * quick-craft。只挂 {@code mouseDragged}/{@code mouseReleased} 的 HEAD 拦不住按下时已置位的
 * {@code isQuickCrafting}，松开被取消后界面会卡在 quick-craft 状态，因此这个功能需要同时拦截
 * {@code AbstractContainerScreen#mouseClicked} + {@code mouseDragged} + {@code mouseReleased}
 * （或提供 accessor 复位 {@code isQuickCrafting}/{@code quickCraftSlots}）后再实现，不能只加半套。
 *
 * <p>TODO(port): 需要插件 mixin 调用。mixin 目标与建议签名见文件末尾的 STATIC HOOKS 注释。
 */
public final class QuickItemMoveModule extends AddonModule {
    private static final int INVENTORY_SLOT_COUNT = 36;
    private static final int SHULKER_CONTENT_SLOTS = 27;

    /** 排序比较器（上游 {@code e}）：注册 id → 地图名 → 潜影盒内容 → 数量降序。*/
    private static final Comparator<ItemStack> STACK_ORDER;

    static {
        Comparator<ItemStack> byItemId = Comparator.comparingInt(
                stack -> BuiltInRegistries.ITEM.getId(stack.getItem()));
        Comparator<ItemStack> byMapName = (left, right) -> left.getItem() == Items.FILLED_MAP
                && right.getItem() == Items.FILLED_MAP
                        ? left.getHoverName().getString().compareTo(right.getHoverName().getString())
                        : 0;
        Comparator<ItemStack> byShulkerContents = Comparator.comparing(QuickItemMoveModule::shulkerContentsKey);
        Comparator<ItemStack> byCount = (left, right) -> Integer.compare(right.getCount(), left.getCount());
        STACK_ORDER = byItemId.thenComparing(byMapName).thenComparing(byShulkerContents).thenComparing(byCount);
    }

    // ------------------------------------------------------------------
    // 设置（上游 4 个 BoolSetting + 3 个 KeybindSetting + 1 个 IntSetting）
    // ------------------------------------------------------------------

    private final Setting<Integer> operationLimitSetting;
    private final Setting<Boolean> dragMoveSetting;
    private final Setting<String> dragKeySetting;
    private final Setting<Boolean> sameTypeMoveSetting;
    private final Setting<String> sameTypeKeySetting;
    private final Setting<Boolean> sortingSetting;
    private final Setting<String> sortKeySetting;
    private final Setting<Boolean> sortHotbarSetting;
    private final Setting<Boolean> scrollMoveSetting;

    // ------------------------------------------------------------------
    // 运行状态（上游 s/t/u/v/w）
    // ------------------------------------------------------------------

    /** 排序点击队列（上游 {@code t}）。每个元素是一整次左键 PICKUP。*/
    private final ArrayDeque<Integer> pendingSortClicks = new ArrayDeque<>();
    /** 待移动/丢弃的槽位（上游 {@code s}）。*/
    private final LinkedHashSet<Integer> pendingMoveSlots = new LinkedHashSet<>();
    /** 本次拖拽已经排过的槽位，避免同一格重复 shift 点击。*/
    private final Set<Integer> dragQueuedSlots = new HashSet<>();

    /** Alt 按下时记住的物品与所在区域，用于「拖到界面外丢弃」（上游 {@code u/v}）。*/
    private ItemStack altPressStack = ItemStack.EMPTY;
    private boolean altPressInContainer;
    private boolean altPressActive;
    /** 本次队列是丢弃而不是 shift 移动（上游 {@code w}）。*/
    private boolean dropMode;

    /** 是否处于 Shift 拖拽会话。*/
    private boolean dragSession;
    private Slot dragStartSlot;
    private boolean dragMoved;

    private boolean wasLeftDown;
    private boolean sortKeyWasDown;
    private float lastMouseX;
    private float lastMouseY;

    /** 自己发起的点击正在执行，期间不响应 Alt 点击事件，避免递归拦截。*/
    private int selfClickDepth;

    public QuickItemMoveModule(ModuleCategory category) {
        super("lotus", "LotusQuickItemMove",
                "Shift-drag to move, Alt-click to move or drop same items, R to sort container items.",
                category);

        this.operationLimitSetting = this.add(new NumberSetting<>("OperationLimit", 2, 1, 9));
        this.dragMoveSetting = this.add(new BooleanSetting("DragMove", true));
        this.dragKeySetting = this.add(new StringSetting("DragKey", "left.shift").dependsOn(this.dragMoveSetting));
        this.sameTypeMoveSetting = this.add(new BooleanSetting("SameTypeMove", true));
        this.sameTypeKeySetting = this.add(
                new StringSetting("SameTypeKey", "left.alt").dependsOn(this.sameTypeMoveSetting));
        this.sortingSetting = this.add(new BooleanSetting("Sorting", true));
        this.sortKeySetting = this.add(new StringSetting("SortKey", "r").dependsOn(this.sortingSetting));
        this.sortHotbarSetting = this.add(new BooleanSetting("SortHotbar", false).dependsOn(this.sortingSetting));
        this.scrollMoveSetting = this.add(new BooleanSetting("ScrollMove", true));
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.resetState();
        this.sortKeyWasDown = this.isBoundKeyDown(this.sortKeySetting.getValue());
        this.wasLeftDown = this.isLeftMouseDown();
    }

    @Override
    public void onDisable() {
        this.resetState();
    }

    @EventHandler
    private void onScreenChange(ScreenChangeEvent.Before event) {
        // 上游 ScreenClosedEvent：换界面时清空所有暂存任务，避免在别的菜单里点旧槽位。
        this.resetState();
    }

    // ------------------------------------------------------------------
    // Tick：排序按键轮询 + 队列执行
    // ------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        this.pollSortKey();
        if (this.isWorldUnavailable()) {
            this.resetState();
            return;
        }

        Screen screen = this.evernightMinecraft.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)
                || isCreativeClosed(containerScreen)) {
            this.resetState();
            return;
        }
        if (this.evernightMinecraft.player == null) {
            return;
        }

        if (!this.pendingSortClicks.isEmpty() || !this.pendingMoveSlots.isEmpty()) {
            this.processQueue();
        }
    }

    /** 轮询排序键并做上升沿检测（上游 KeybindSetting 的 action）。*/
    private void pollSortKey() {
        boolean down = this.sortingSetting.getValue() && this.isBoundKeyDown(this.sortKeySetting.getValue());
        boolean pressed = down && !this.sortKeyWasDown;
        this.sortKeyWasDown = down;
        if (!pressed) {
            return;
        }
        if (this.isWorldUnavailable()) {
            return;
        }

        Screen screen = this.evernightMinecraft.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)
                || isCreativeClosed(containerScreen)) {
            return;
        }
        // 有输入框获得焦点时不排序，避免在铁砧/搜索栏里打 r 触发排序。
        // TODO(port): 上游的 Keybind 回调只在界面按键时触发，无法区分输入框；这里是行为差异。
        if (containerScreen.getFocused() != null) {
            return;
        }
        this.sortContainer();
    }

    /** 上游 onTick：先执行排序点击，再执行移动/丢弃，二者共享「操作数」配额。*/
    private void processQueue() {
        AbstractContainerMenu menu = this.currentMenu();
        if (menu == null) {
            return;
        }

        int limit = Math.max(1, this.operationLimitSetting.getValue());

        while (!this.pendingSortClicks.isEmpty() && limit > 0) {
            int slotId = this.pendingSortClicks.removeFirst();
            if (slotId < 0 || slotId >= menu.slots.size()) {
                continue;
            }
            this.clickSlot(slotId, 0, ClickType.PICKUP);
            limit--;
        }

        while (limit > 0 && !this.pendingMoveSlots.isEmpty()) {
            int slotId = this.pollMoveSlot();
            if (slotId < 0 || slotId >= menu.slots.size()) {
                continue;
            }

            ItemStack stack = menu.getSlot(slotId).getItem();
            if (stack.isEmpty()) {
                // 上游：警告但不消耗操作数。
                this.warn("moveEmptySlot", slotId);
                continue;
            }

            boolean inContainer = slotId < containerSize(menu);
            if (inContainer) {
                if (!this.canReceive(inContainer, stack)) {
                    this.warn("inventoryFull");
                    this.resetState();
                    return;
                }
            } else if (!this.canReceive(false, stack)) {
                this.warn("containerFull");
                this.resetState();
                return;
            }

            if (this.dropMode) {
                this.clickSlot(slotId, 1, ClickType.THROW);
            } else {
                this.clickSlot(slotId, 0, ClickType.QUICK_MOVE);
            }
            limit--;
        }

        if (this.dropMode && this.pendingMoveSlots.isEmpty()) {
            // 上游 dropMode 会保持到界面关闭；这里在丢弃任务清空后复位，避免之后的移动被当成丢弃。
            this.dropMode = false;
        }
    }

    // ------------------------------------------------------------------
    // 鼠标事件：Alt 点击（可 cancel 的 gameMode 事件）
    // ------------------------------------------------------------------

    @EventHandler
    private void onInventoryClick(MultiPlayerGameModeHandleInventoryMouseClickEvent event) {
        if (this.selfClickDepth > 0 || this.isWorldUnavailable()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        Screen screen = this.evernightMinecraft.screen;
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)
                || isCreativeClosed(containerScreen)) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (event.getContainerId() != menu.containerId) {
            return;
        }
        if (event.getButton() != 0 || event.getClickType() != ClickType.PICKUP) {
            return;
        }
        int slotId = event.getSlotId();
        if (slotId < 0 || slotId >= menu.slots.size()) {
            return;
        }
        // 手上有物品时交给原版：此时 screen 已进入 quick-craft 状态，取消 gameMode 点击会让
        // 之后的拖拽/松开发出 QUICK_CRAFT 包，反而更容易出错。
        if (!menu.getCarried().isEmpty()) {
            return;
        }
        if (!this.sameTypeMoveSetting.getValue() || !this.isSameTypeKeyDown()) {
            return;
        }

        // 只拦截「鼠标正悬停在该槽位上」的真实点击；其它模块合成的点击不应被改成同类移动。
        Slot hovered = slotAt(containerScreen, this.lastMouseX, this.lastMouseY);
        if (hovered == null || hovered.index != slotId) {
            return;
        }
        Slot slot = menu.getSlot(slotId);
        if (!slot.hasItem()) {
            return;
        }

        event.cancel();

        ItemStack pressed = slot.getItem().copy();
        boolean inContainer = slotId < containerSize(menu);

        this.dropMode = false;
        this.pendingMoveSlots.clear();
        this.altPressStack = pressed;
        this.altPressInContainer = inContainer;
        this.altPressActive = true;
        this.queueSameType(inContainer, pressed);
    }

    // ------------------------------------------------------------------
    // 鼠标：Shift 拖拽 + Alt 拖到界面外（每帧渲染事件 + GLFW 鼠标状态）
    // ------------------------------------------------------------------

    @EventHandler
    private void onContainerScreenRender(AbstractContainerScreenEvent event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)
                || screen != this.evernightMinecraft.screen) {
            return;
        }
        this.lastMouseX = event.getMouseX();
        this.lastMouseY = event.getMouseY();
        if (this.isWorldUnavailable()) {
            return;
        }
        if (isCreativeClosed(screen)) {
            this.resetState();
            return;
        }

        boolean leftDown = this.isLeftMouseDown();
        if (!leftDown) {
            if (this.wasLeftDown) {
                this.handleLeftRelease(screen);
            }
            this.wasLeftDown = false;
            return;
        }

        if (!this.wasLeftDown) {
            this.startPress(screen, this.lastMouseX, this.lastMouseY);
        }
        this.wasLeftDown = true;

        if (!this.dragSession || !this.dragMoveSetting.getValue()) {
            return;
        }
        Slot slot = slotAt(screen, this.lastMouseX, this.lastMouseY);
        if (slot == null || !slot.hasItem()) {
            return;
        }
        if (slot == this.dragStartSlot && !this.dragMoved) {
            return;
        }
        this.dragMoved = true;
        if (this.dragQueuedSlots.add(slot.index)) {
            this.pendingMoveSlots.add(slot.index);
        }
    }

    /** 鼠标左键按下：开启 Shift 拖拽会话（鼠标按键事件在容器界面里收不到，只能每帧比较状态）。*/
    private void startPress(AbstractContainerScreen<?> screen, float mouseX, float mouseY) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            this.dragSession = false;
            return;
        }
        if (!player.containerMenu.getCarried().isEmpty()) {
            this.dragSession = false;
            return;
        }
        this.dragSession = this.dragMoveSetting.getValue() && this.isDragKeyDown();
        this.dragStartSlot = this.dragSession ? slotAt(screen, mouseX, mouseY) : null;
        this.dragMoved = false;
        this.dragQueuedSlots.clear();
        if (this.dragStartSlot != null) {
            // 起始格已经被原版的那一次 shift 点击移动过，拖拽回来时不再重复点它。
            this.dragQueuedSlots.add(this.dragStartSlot.index);
        }
    }

    private void handleLeftRelease(AbstractContainerScreen<?> screen) {
        if (this.altPressActive
                && !this.altPressStack.isEmpty()
                && this.sameTypeMoveSetting.getValue()
                && this.isSameTypeKeyDown()
                && slotAt(screen, this.lastMouseX, this.lastMouseY) == null
                && isOutside(screen, this.lastMouseX, this.lastMouseY)) {
            // 上游 onMouseRelease：Alt 拖到容器界面外松开 → 丢弃同类型。
            this.pendingMoveSlots.clear();
            this.pendingSortClicks.clear();
            this.dragQueuedSlots.clear();
            this.dropMode = true;
            this.queueSameType(this.altPressInContainer, this.altPressStack);
            if (this.pendingMoveSlots.isEmpty()) {
                this.dropMode = false;
            }
        }

        this.altPressActive = false;
        this.altPressStack = ItemStack.EMPTY;
        this.dragSession = false;
        this.dragStartSlot = null;
        this.dragMoved = false;
        this.dragQueuedSlots.clear();
    }

    // ------------------------------------------------------------------
    // 同类操作（上游 a(boolean, ItemStack)）
    // ------------------------------------------------------------------

    private void queueSameType(boolean inContainerRegion, ItemStack reference) {
        AbstractContainerMenu menu = this.currentMenu();
        if (menu == null) {
            return;
        }
        int containerSize = containerSize(menu);
        int from = inContainerRegion ? 0 : containerSize;
        int to = Math.min(inContainerRegion ? containerSize : containerSize + INVENTORY_SLOT_COUNT,
                menu.slots.size());

        boolean shulker = isShulkerBox(reference);
        boolean bundle = isBundle(reference);

        if (!shulker && !bundle) {
            Item item = reference.getItem();
            boolean special = item == Items.ENCHANTED_BOOK || item == Items.OMINOUS_BOTTLE;
            ItemKey key = special ? keyOf(reference, false) : null;
            for (int index = from; index < to; index++) {
                Slot slot = menu.getSlot(index);
                ItemStack stack = slot.getItem();
                if (stack.getItem() != item) {
                    continue;
                }
                if (special && !key.equals(keyOf(stack, false))) {
                    continue;
                }
                this.pendingMoveSlots.add(slot.index);
            }
            return;
        }

        Set<ItemKey> referenceContents = containerContents(reference);
        for (int index = from; index < to; index++) {
            Slot slot = menu.getSlot(index);
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            boolean sameKind = shulker ? isShulkerBox(stack) : isBundle(stack);
            if (!sameKind) {
                continue;
            }
            // 上游按「内容集合」比较：同内容的不同颜色潜影盒也会一起移动。
            if (referenceContents.equals(containerContents(stack))) {
                this.pendingMoveSlots.add(slot.index);
            }
        }
    }

    /** 上游 a(ItemStack, boolean)：目标侧是否还有格子能放下这个物品。*/
    private boolean canReceive(boolean toInventory, ItemStack stack) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        if (toInventory) {
            for (int index = 0; index < INVENTORY_SLOT_COUNT; index++) {
                if (canHold(player.getInventory().getItem(index), stack)) {
                    return true;
                }
            }
            return false;
        }

        AbstractContainerMenu menu = player.containerMenu;
        int containerSize = containerSize(menu);
        for (int index = 0; index < containerSize && index < menu.slots.size(); index++) {
            if (canHold(menu.getSlot(index).getItem(), stack)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 排序（上游 a() / a_List_List_List / a_List_toList）
    // ------------------------------------------------------------------

    private void sortContainer() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        AbstractContainerMenu menu = player.containerMenu;
        List<Integer> slotIds = this.sortRegionSlots(menu);
        int size = slotIds.size();
        if (size == 0) {
            return;
        }

        List<ItemStack> current = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            current.add(menu.getSlot(slotIds.get(index)).getItem().copy());
        }

        this.resetQueues();

        List<ItemStack> bundles = new ArrayList<>();
        List<ItemStack> plain = new ArrayList<>(size);
        for (ItemStack stack : current) {
            if (!stack.isEmpty() && isBundle(stack)) {
                bundles.add(stack.copy());
            } else {
                plain.add(stack);
            }
        }

        List<ItemStack> plainTarget = buildTargetStacks(plain);

        if (!bundles.isEmpty()) {
            bundles.sort(STACK_ORDER);
            this.arrangeBundles(slotIds, current, bundles, plainTarget.size());
        }

        List<Integer> plainSlotIds;
        List<ItemStack> plainCurrent;
        if (bundles.isEmpty()) {
            plainSlotIds = slotIds;
            plainCurrent = current;
        } else {
            plainSlotIds = new ArrayList<>(size);
            plainCurrent = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                ItemStack stack = current.get(index);
                if (stack.isEmpty() || !isBundle(stack)) {
                    plainSlotIds.add(slotIds.get(index));
                    plainCurrent.add(stack);
                }
            }
        }

        this.sortPlain(plainSlotIds, plainCurrent, plainTarget);
    }

    /** 上游 c()：本次排序覆盖的槽位。*/
    private List<Integer> sortRegionSlots(AbstractContainerMenu menu) {
        List<Integer> slots = new ArrayList<>();
        int containerSize = containerSize(menu);
        boolean containerScreen = !(menu instanceof InventoryMenu);

        if (containerScreen) {
            for (int index = 0; index < containerSize && index < menu.slots.size(); index++) {
                slots.add(index);
            }
            return slots;
        }

        Set<Integer> locked = lockedSlots();
        for (int index = 9; index < 36; index++) {
            if (locked.contains(index)) {
                continue;
            }
            if (index < menu.slots.size()) {
                slots.add(index);
            }
        }
        if (this.sortHotbarSetting.getValue()) {
            for (int index = 36; index < 45; index++) {
                if (locked.contains(index - 36)) {
                    continue;
                }
                if (index < menu.slots.size()) {
                    slots.add(index);
                }
            }
        }
        return slots;
    }

    /** 上游 bu.c()：LotusHelper（原 ao）里设置的锁定物品栏下标（0-35）。*/
    private static Set<Integer> lockedSlots() {
        LotusHelperModule helper = ClientContext.getModule(LotusHelperModule.class);
        return helper == null ? Set.of() : helper.getLockedSlots();
    }

    /** 上游 a(List, List, List, int)：把潜影盒/收纳袋按排序结果挪到普通物品之后。*/
    private void arrangeBundles(List<Integer> slotIds, List<ItemStack> current, List<ItemStack> target,
            int plainCount) {
        int size = slotIds.size();

        for (int targetIndex = 0; targetIndex < target.size(); targetIndex++) {
            int position = plainCount + targetIndex;
            ItemStack wanted = target.get(targetIndex);
            if (position >= size) {
                return;
            }
            ItemStack actual = current.get(position);
            if (sameItemAndCount(actual, wanted)) {
                continue;
            }

            int source = -1;
            for (int index = 0; index < plainCount && index < size; index++) {
                if (sameItemAndCount(current.get(index), wanted)) {
                    source = index;
                    break;
                }
            }
            if (source < 0) {
                for (int index = size - 1; index > position; index--) {
                    if (sameItemAndCount(current.get(index), wanted)) {
                        source = index;
                        break;
                    }
                }
            }
            if (source < 0) {
                this.warn("sortState3");
                return;
            }

            int sourceSlot = slotIds.get(source);
            int positionSlot = slotIds.get(position);
            ItemStack sourceStack = current.get(source);

            if (actual.isEmpty()) {
                this.pendingSortClicks.add(sourceSlot);
                this.pendingSortClicks.add(positionSlot);
                current.set(source, ItemStack.EMPTY);
                current.set(position, sourceStack);
            } else {
                int emptySlot = this.findEmptySlot(slotIds, current);
                if (emptySlot < 0) {
                    this.warn("sortNoEmptySlot");
                    return;
                }
                this.pendingSortClicks.add(positionSlot);
                this.pendingSortClicks.add(emptySlot);
                this.pendingSortClicks.add(sourceSlot);
                this.pendingSortClicks.add(positionSlot);
                this.pendingSortClicks.add(emptySlot);
                this.pendingSortClicks.add(sourceSlot);
                current.set(source, actual);
                current.set(position, sourceStack);
            }
        }
    }

    /** 上游 a_List_List：在排序区域里找一个空槽，找不到就到整个菜单里找。*/
    private int findEmptySlot(List<Integer> slotIds, List<ItemStack> current) {
        for (int index = 0; index < current.size(); index++) {
            if (current.get(index).isEmpty()) {
                return slotIds.get(index);
            }
        }

        AbstractContainerMenu menu = this.currentMenu();
        if (menu == null) {
            return -1;
        }
        int from;
        int to;
        if (menu instanceof InventoryMenu) {
            from = 9;
            to = 45;
        } else {
            from = 0;
            to = menu.slots.size();
        }
        for (int index = from; index < to && index < menu.slots.size(); index++) {
            if (menu.getSlot(index).getItem().isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    /** 上游 a_List_List_List：把普通物品按目标顺序逐格交换/合并。*/
    private void sortPlain(List<Integer> slotIds, List<ItemStack> current, List<ItemStack> target) {
        int size = slotIds.size();

        for (int targetIndex = 0; targetIndex < target.size(); targetIndex++) {
            ItemStack wanted = target.get(targetIndex);
            ItemStack actual = current.get(targetIndex);
            if (sameItemAndCount(actual, wanted)) {
                continue;
            }

            int source;
            for (source = size - 1; source >= 0; source--) {
                ItemStack candidate = current.get(source);
                if (sameItem(candidate, wanted)) {
                    if (source >= target.size()) {
                        break;
                    }
                    ItemStack sameIndexTarget = target.get(source);
                    if (!sameItem(candidate, sameIndexTarget)) {
                        break;
                    }
                }
            }

            if (source < 0 || source == targetIndex) {
                continue;
            }

            this.pendingSortClicks.add(slotIds.get(source));
            this.pendingSortClicks.add(slotIds.get(targetIndex));

            ItemStack sourceStack = current.get(source);
            current.set(source, ItemStack.EMPTY);
            ItemStack leftover;

            if (canMergeInto(actual, sourceStack)) {
                int room = actual.getMaxStackSize() - actual.getCount() - sourceStack.getCount();
                if (room >= 0) {
                    current.get(targetIndex).grow(sourceStack.getCount());
                    targetIndex--;
                    continue;
                }
                leftover = sourceStack.copyWithCount(-room);
                current.get(targetIndex).setCount(current.get(targetIndex).getMaxStackSize());
            } else {
                leftover = actual;
                current.set(targetIndex, sourceStack);
                targetIndex--;
            }

            while (!leftover.isEmpty()) {
                int next;
                for (next = targetIndex + 1; next < target.size(); next++) {
                    ItemStack nextTarget = target.get(next);
                    if (!sameItem(nextTarget, leftover)) {
                        continue;
                    }
                    ItemStack nextCurrent = current.get(next);
                    if (!sameItem(nextCurrent, nextTarget)) {
                        this.pendingSortClicks.add(slotIds.get(next));
                        current.set(next, leftover);
                        leftover = nextCurrent;
                        break;
                    }
                    if (canMergeInto(nextCurrent, leftover)) {
                        this.pendingSortClicks.add(slotIds.get(next));
                        int room = nextCurrent.getMaxStackSize() - nextCurrent.getCount() - leftover.getCount();
                        if (room < 0) {
                            nextCurrent.setCount(nextCurrent.getMaxStackSize());
                            leftover.setCount(-room);
                        } else {
                            nextCurrent.grow(leftover.getCount());
                            leftover = ItemStack.EMPTY;
                            targetIndex--;
                        }
                        break;
                    }
                }
                if (next >= target.size()) {
                    this.warn("sortState2");
                    break;
                }
            }
        }
    }

    /** 上游 a_List_toList：先按 (物品标识, 潜影盒内容) 合并，再按最大堆叠拆分并排序。*/
    private static List<ItemStack> buildTargetStacks(List<ItemStack> stacks) {
        Map<GroupKey, ItemStack> merged = new LinkedHashMap<>();
        for (ItemStack stack : stacks) {
            if (stack.isEmpty()) {
                continue;
            }
            GroupKey key = new GroupKey(keyOf(stack, true), shulkerContentsKey(stack));
            ItemStack existing = merged.get(key);
            if (existing == null) {
                merged.put(key, stack.copy());
            } else {
                existing.setCount(existing.getCount() + stack.getCount());
            }
        }

        List<ItemStack> result = new ArrayList<>();
        for (ItemStack stack : merged.values()) {
            int max = stack.getMaxStackSize();
            int count;
            for (count = stack.getCount(); count >= max; count -= max) {
                result.add(stack.copyWithCount(max));
            }
            if (count > 0) {
                result.add(stack.copyWithCount(count));
            }
        }
        result.sort(STACK_ORDER);
        return result;
    }

    // ------------------------------------------------------------------
    // 滚轮移动：公开静态入口（需要父级 mixin 调用）
    // ------------------------------------------------------------------

    /**
     * TODO(port): 需要插件 mixin 调用。目标：{@code AbstractContainerScreen#mouseScrolled(DDDD)Z} 的 HEAD。
     *
     * <p>Evernight 的 {@code MouseHandlerOnScrollEvent} 只在没有界面时发布（见
     * {@code MouseHandler#onScroll}），容器界面里收不到，所以只能用这个入口。
     *
     * @return true 表示已处理，mixin 应取消原版滚动
     */
    public static boolean onMouseScroll(AbstractContainerScreen<?> screen, double mouseX, double mouseY,
            double horizontal, double vertical) {
        QuickItemMoveModule module = instance();
        if (module == null || screen == null || isCreativeClosed(screen)) {
            return false;
        }
        if (!module.scrollMoveSetting.getValue() || vertical == 0.0) {
            return false;
        }
        LocalPlayer player = module.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (!menu.getCarried().isEmpty()) {
            return false;
        }

        Slot slot = slotAt(screen, mouseX, mouseY);
        if (slot == null || !slot.hasItem()) {
            return false;
        }

        boolean inContainer = module.isContainerRegion(menu, slot.index);
        boolean inInventory = module.isInventoryRegion(menu, slot.index);
        ItemStack stack = slot.getItem();
        boolean up = vertical > 0.0;
        int source = -1;
        int target = -1;

        if (up) {
            if (inContainer) {
                source = findSameItemSlot(menu, stack, false);
                if (canHold(slot.getItem(), stack)) {
                    target = slot.index;
                }
            } else if (inInventory) {
                source = slot.index;
                target = findMergeOrEmptySlot(menu, stack, true);
            }
        } else if (inContainer) {
            source = slot.index;
            target = findMergeOrEmptySlot(menu, stack, false);
        } else if (inInventory) {
            source = findSameItemSlot(menu, stack, true);
            if (canHold(slot.getItem(), stack)) {
                target = slot.index;
            }
        }

        if (source == -1 || target == -1 || source == target) {
            return false;
        }
        module.swapSlots(source, target);
        return true;
    }

    // ------------------------------------------------------------------
    // 滚轮辅助
    // ------------------------------------------------------------------

    /** 上游 f(int)：容器一侧（非背包界面 = 容器槽，背包界面 = 主背包 9..35）。*/
    private boolean isContainerRegion(AbstractContainerMenu menu, int slotId) {
        if (menu instanceof InventoryMenu) {
            return slotId >= 9 && slotId < 36;
        }
        return slotId < containerSize(menu);
    }

    /** 上游 g(int)：背包一侧（非背包界面 = 背包+快捷栏，背包界面 = 快捷栏 36..44）。*/
    private boolean isInventoryRegion(AbstractContainerMenu menu, int slotId) {
        if (menu instanceof InventoryMenu) {
            return slotId >= 36 && slotId < 45;
        }
        return slotId >= containerSize(menu);
    }

    /** 上游 b(ItemStack, boolean)：优先可合并的同类槽，其次空槽。*/
    private static int findMergeOrEmptySlot(AbstractContainerMenu menu, ItemStack stack, boolean containerSide) {
        for (Slot slot : menu.slots) {
            if (regionMatches(menu, slot.index, containerSide) && slot.hasItem()
                    && canMergeInto(slot.getItem(), stack)) {
                return slot.index;
            }
        }
        for (Slot slot : menu.slots) {
            if (regionMatches(menu, slot.index, containerSide) && !slot.hasItem()) {
                return slot.index;
            }
        }
        return -1;
    }

    /** 上游 c(ItemStack, boolean)：找同物品的槽（不要求数量/可合并）。*/
    private static int findSameItemSlot(AbstractContainerMenu menu, ItemStack stack, boolean containerSide) {
        for (Slot slot : menu.slots) {
            if (regionMatches(menu, slot.index, containerSide) && slot.hasItem()
                    && sameItem(slot.getItem(), stack)) {
                return slot.index;
            }
        }
        return -1;
    }

    private static boolean regionMatches(AbstractContainerMenu menu, int slotId, boolean containerSide) {
        if (menu instanceof InventoryMenu) {
            return containerSide ? slotId >= 9 && slotId < 36 : slotId >= 36 && slotId < 45;
        }
        boolean container = slotId < containerSize(menu);
        return containerSide == container;
    }

    /** 上游 bp.d(a, b)：用三次 PICKUP 交换两个槽位。*/
    private void swapSlots(int first, int second) {
        this.clickSlot(first, 0, ClickType.PICKUP);
        this.clickSlot(second, 1, ClickType.PICKUP);
        this.clickSlot(first, 0, ClickType.PICKUP);
    }

    // ------------------------------------------------------------------
    // 槽位 / 菜单工具
    // ------------------------------------------------------------------

    private AbstractContainerMenu currentMenu() {
        LocalPlayer player = this.evernightMinecraft.player;
        return player == null ? null : player.containerMenu;
    }

    private void clickSlot(int slotId, int button, ClickType clickType) {
        this.selfClickDepth++;
        try {
            InventoryUtils.clickSlot(slotId, button, clickType);
        } finally {
            this.selfClickDepth--;
        }
    }

    private int pollMoveSlot() {
        Iterator<Integer> iterator = this.pendingMoveSlots.iterator();
        if (!iterator.hasNext()) {
            return -1;
        }
        int slot = iterator.next();
        iterator.remove();
        return slot;
    }

    /** 上游 bp.g()：玩家容器区（非背包 36 格）的槽位数量。*/
    private static int containerSize(AbstractContainerMenu menu) {
        int size = menu.slots.size() - INVENTORY_SLOT_COUNT;
        if (menu instanceof InventoryMenu) {
            // InventoryMenu 比普通容器多一个 offhand 槽位（45），容器区只到 8（结果/合成/盔甲）。
            size--;
        }
        return Math.max(0, size);
    }

    private static Slot slotAt(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        if (!(screen instanceof AbstractContainerScreenAccessor accessor)) {
            return null;
        }
        int left = accessor.getX();
        int top = accessor.getY();
        for (Slot slot : screen.getMenu().slots) {
            int slotX = slot.x + left;
            int slotY = slot.y + top;
            if (mouseX >= slotX && mouseX < slotX + 18 && mouseY >= slotY && mouseY < slotY + 18) {
                return slot;
            }
        }
        return null;
    }

    private static boolean isOutside(AbstractContainerScreen<?> screen, double mouseX, double mouseY) {
        if (!(screen instanceof AbstractContainerScreenAccessor accessor)) {
            return false;
        }
        int left = accessor.getX();
        int top = accessor.getY();
        return mouseX < left || mouseY < top
                || mouseX >= left + accessor.getBgWidth()
                || mouseY >= top + accessor.getBgHeight();
    }

    private static boolean isCreativeClosed(AbstractContainerScreen<?> screen) {
        return screen instanceof CreativeModeInventoryScreen creative && !creative.isInventoryOpen();
    }

    // ------------------------------------------------------------------
    // 物品比较（上游 a_ItemStack_ItemStack / b_ItemStack_ItemStack / c / d / 容器内容）
    // ------------------------------------------------------------------

    /**
     * 上游 a_ItemStack_ItemStack：同物品 + 同地图 + 同自定义名 + 同潜影盒内容。
     *
     * <p>TODO(port): 上游的同类判定不比较药水/附魔书组件（只比物品 + 地图 + 自定义名 + 潜影盒内容），
     * 排序时可能把不同药水或不同附魔书当成同一种。这里通过 {@link ItemKey} 一并比较，属于有意的修正。
     */
    private static boolean sameItem(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) {
            return first.isEmpty() && second.isEmpty();
        }
        return keyOf(first, true).equals(keyOf(second, true))
                && shulkerContentsKey(first).equals(shulkerContentsKey(second));
    }

    /** 上游 b_ItemStack_ItemStack：同物品且数量相同。*/
    private static boolean sameItemAndCount(ItemStack first, ItemStack second) {
        return sameItem(first, second) && first.getCount() == second.getCount();
    }

    /** 上游 c(ItemStack, ItemStack)：target 还能不能再放进 source。*/
    private static boolean canMergeInto(ItemStack target, ItemStack source) {
        if (target.isEmpty() || source.isEmpty()) {
            return false;
        }
        return sameItem(target, source) && target.getCount() < target.getMaxStackSize();
    }

    /** 上游 d(ItemStack, ItemStack)：空槽或可以合并。*/
    private static boolean canHold(ItemStack existing, ItemStack incoming) {
        return existing.isEmpty() || canMergeInto(existing, incoming);
    }

    private static boolean isShulkerBox(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.SHULKER_BOXES);
    }

    private static boolean isBundle(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ItemTags.BUNDLES);
    }

    /** 上游 g：物品标识（物品 + 自定义名 + 附魔书附魔 + 药水 + 地图 + 不祥之瓶增幅）。*/
    private static ItemKey keyOf(ItemStack stack, boolean includeCustomName) {
        Item item = stack.getItem();
        String customName = includeCustomName && stack.getCustomName() != null
                ? stack.getCustomName().getString()
                : null;
        ItemEnchantments bookEnchantments = item == Items.ENCHANTED_BOOK
                ? stack.get(DataComponents.STORED_ENCHANTMENTS)
                : null;
        PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
        if (potion != null && PotionContents.EMPTY.equals(potion)) {
            potion = null;
        }
        MapId mapId = stack.get(DataComponents.MAP_ID);
        Integer map = mapId == null ? null : mapId.id();
        OminousBottleAmplifier ominous = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
        return new ItemKey(item, customName, bookEnchantments, potion, map, ominous);
    }

    /** 上游 b_ItemStack_toString：潜影盒内容签名；非潜影盒返回空串。*/
    private static String shulkerContentsKey(ItemStack stack) {
        if (!isShulkerBox(stack)) {
            return "";
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (ItemStack content : contents.nonEmptyItems()) {
            parts.add("%05d#%d".formatted(
                    BuiltInRegistries.ITEM.getId(content.getItem()),
                    content.getComponents().hashCode()));
        }
        parts.sort(null);
        return String.join(",", parts);
    }

    /** 上游 a_ItemStack_toSet：潜影盒 / 收纳袋内部的物品集合。*/
    private static Set<ItemKey> containerContents(ItemStack stack) {
        Set<ItemKey> keys = new HashSet<>();
        if (isShulkerBox(stack)) {
            NonNullList<ItemStack> contents = NonNullList.withSize(SHULKER_CONTENT_SLOTS, ItemStack.EMPTY);
            ItemContainerContents container = stack.get(DataComponents.CONTAINER);
            if (container != null) {
                container.copyInto(contents);
            }
            for (ItemStack content : contents) {
                keys.add(keyOf(content, false));
            }
        } else if (isBundle(stack)) {
            BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
            if (contents != null && !contents.isEmpty()) {
                for (ItemStack content : contents.items()) {
                    keys.add(keyOf(content, false));
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // 按键 / 鼠标状态
    // ------------------------------------------------------------------

    private boolean isDragKeyDown() {
        return this.isBoundKeyDown(this.dragKeySetting.getValue());
    }

    private boolean isSameTypeKeyDown() {
        return this.isBoundKeyDown(this.sameTypeKeySetting.getValue());
    }

    private boolean isLeftMouseDown() {
        Window window = this.evernightMinecraft.getWindow();
        return window != null
                && GLFW.glfwGetMouseButton(window.handle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
    }

    private boolean isBoundKeyDown(String keyName) {
        int code = resolveKeyCode(keyName);
        if (code < 0) {
            return false;
        }
        Window window = this.evernightMinecraft.getWindow();
        return window != null && InputConstants.isKeyDown(window, code);
    }

    /**
     * 把设置里的键名解析为 GLFW 键码。
     *
     * <p>TODO(port): 上游是 Meteor 的 KeybindSetting（带按键捕获 UI）。Evernight 1.0.3 没有设置级键位，
     * 这里和本插件 AutoCraft 模块一样用字符串 + {@code key.keyboard.*} 名称，接受 {@code r}、
     * {@code left.shift}、{@code key.keyboard.left.alt} 三种写法。
     */
    private static int resolveKeyCode(String keyName) {
        if (keyName == null) {
            return -1;
        }
        String normalized = keyName.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return -1;
        }
        String fullName = normalized.startsWith("key.keyboard.")
                ? normalized
                : "key.keyboard." + normalized;
        try {
            return InputConstants.getKey(fullName).getValue();
        } catch (IllegalArgumentException invalidKey) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 状态复位 / 文本
    // ------------------------------------------------------------------

    private void resetQueues() {
        this.pendingSortClicks.clear();
        this.pendingMoveSlots.clear();
        this.dragQueuedSlots.clear();
        this.dropMode = false;
    }

    private void resetState() {
        this.resetQueues();
        this.altPressStack = ItemStack.EMPTY;
        this.altPressInContainer = false;
        this.altPressActive = false;
        this.dragSession = false;
        this.dragStartSlot = null;
        this.dragMoved = false;
        this.wasLeftDown = false;
    }

    private void warn(String key, Object... args) {
        String pattern = Translations.get("addons.lotus.messages." + key);
        String message = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
    }

    private static QuickItemMoveModule instance() {
        QuickItemMoveModule module = ClientContext.getModule(QuickItemMoveModule.class);
        if (module == null || !module.isToggled() || module.isWorldUnavailable()) {
            return null;
        }
        return module;
    }

    // ------------------------------------------------------------------
    // 小型值类型
    // ------------------------------------------------------------------

    /** 上游 g：物品身份（决定排序分组 / 同类判定）。*/
    private record ItemKey(Item item, String customName, ItemEnchantments bookEnchantments,
            PotionContents potion, Integer mapId, OminousBottleAmplifier ominous) {
    }

    /** 上游 an：排序分组键 = 物品身份（含自定义名）+ 潜影盒内容签名。*/
    private record GroupKey(ItemKey item, String contents) {
    }

    /*
     * STATIC HOOKS —— 给父级集成 mixin 用（不要在本文件里新建 mixin）。
     *
     * 1. AbstractContainerScreen#mouseScrolled(DDDD)Z（1.21.11 named）：
     *    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
     *    private void lotus$quickItemMoveScroll(double mouseX, double mouseY, double horizontal,
     *            double vertical, CallbackInfoReturnable<Boolean> cir) {
     *        if (QuickItemMoveModule.onMouseScroll((AbstractContainerScreen<?>) (Object) this,
     *                mouseX, mouseY, horizontal, vertical)) {
     *            cir.setReturnValue(true);
     *        }
     *    }
     *
     * 2. 收纳袋（Bundle）拖拽：未移植，见类注释里的 TODO(port)。它需要同时拦截
     *    AbstractContainerScreen#mouseClicked(Lnet/minecraft/client/input/MouseButtonEvent;Z)Z、
     *    #mouseDragged(Lnet/minecraft/client/input/MouseButtonEvent;DD)Z 与
     *    #mouseReleased(Lnet/minecraft/client/input/MouseButtonEvent;)Z 三个入口，并在点击阶段就取消原版
     *    处理（避免 isQuickCrafting 被置位），本模块不会假装提供只挂一半的入口。
     *
     * 不装 mixin 时：Shift 拖拽、Alt 同类移动/丢弃、R 排序仍然可用；只有滚轮移动不生效。
     */
}
