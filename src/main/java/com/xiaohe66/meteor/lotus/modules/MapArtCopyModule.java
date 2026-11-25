package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.InventoryUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;

/**
 * Port of {@code aa} (M地图画复制) from Lotus (author xiaohe66, GPL-3.0).
 *
 * <p>复制收纳袋中的地图画：把待复制袋里的地图画逐张取到背包，与空地图在玩家 2x2 合成格里
 * 合成（结果是原图 + 副本共 2 张），再把 2 张一份的地图画分别放进两个已复制袋。
 *
 * <p>使用前提（上游 {@code aa.E()} 的检查）：背包里正好 3 个收纳袋（1 个待复制袋 +
 * 2 个已复制袋），空地图数量足够，且至少有 1 个空背包槽位。
 *
 * <p>移植自 Meteor 基类 {@code aw}/{@code ag} 的行为已就地重写：
 * <ul>
 *     <li>状态机（{@code aw.m}）、延迟计时（{@code ag.a}）、异常兜底（{@code aw.onTick}）
 *         都在本类里实现；状态只保留本模块用到的 NEXT / USE。</li>
 *     <li>{@code SlotUtils.indexToId(int)} 换成 {@link InventoryUtils#toMenuSlot(int)}，
 *         {@code InvUtils.shiftClick().slotId(0)} 换成
 *         {@link InventoryUtils#clickSlot(int, int, ClickType)} + {@link ClickType#QUICK_MOVE}。</li>
 *     <li>{@code bp.a()} / {@code bp.d(...)} 的关界面与「拿整叠→右键倒 1 个→放回」点击序列
 *         用 {@link ContainerInteractionUtils#closeScreen()} 与
 *         {@link InventoryUtils#clickSlot(int, int, int, ClickType)} 重写。</li>
 * </ul>
 *
 * <p>未移植（上游基类 {@code aw} 提供的、本模块用不到的部件）：
 * <ul>
 *     <li>「重启延时」设置：{@code aw.d_be(...)} 专用，{@code aa} 从未调用。</li>
 *     <li>「快速停止键」（Meteor {@code KeybindSetting}，默认 ESC，按下即关闭模块）：
 *         Evernight 1.0.3 没有设置级键位，见类内 {@code TODO(port)}。</li>
 *     <li>{@code b_ItemStack_toString(ItemStack)}：上游私有静态方法，无人调用（死代码）。</li>
 * </ul>
 */
public final class MapArtCopyModule extends AddonModule {
    /** 背包槽位数（上游 {@code ag.n()}/{@code ag.o()} 固定遍历 0..35）。 */
    private static final int INVENTORY_SIZE = 36;
    /** 上游要求正好 3 个收纳袋：1 个待复制袋 + 2 个已复制袋。 */
    private static final int BUNDLE_COUNT = 3;
    /** 收纳袋容量（上游 {@code aa.f(int)} 用它判断「已复制袋已满」）。 */
    private static final int BUNDLE_CAPACITY = 64;

    private final Setting<Integer> delaySetting;

    /** 上游 {@code aw.m}：状态机当前状态。 */
    private State state = State.NONE;
    /** 上游 {@code ag.a}：距离下次执行还有多少 tick。 */
    private int delay;

    /** 上游 {@code aa.d}：待复制袋所在的背包槽位。 */
    private int sourceBundleSlot = -1;
    /** 上游 {@code aa.e}：已复制袋 A 所在的背包槽位。 */
    private int targetBundleSlotA = -1;
    /** 上游 {@code aa.f}：已复制袋 B 所在的背包槽位。 */
    private int targetBundleSlotB = -1;

    /** 上游 {@code ag.b}：背包搜索的轮转游标。 */
    private int inventoryCursor;
    /** 上游 {@code ag.b} 命中时的槽位，等同 {@code ag.t()} 的返回值。 */
    private int lastInventorySlot = -1;

    public MapArtCopyModule(ModuleCategory category) {
        super("lotus", "LotusMapArtCopy",
                "Copies map art stored in bundles: one bundle to copy, two already-copied bundles "
                        + "and enough empty maps are required.",
                category);
        this.delaySetting = this.add(new NumberSetting<>("Delay", 3, 0, 40));
    }

    // TODO(port): 上游基类 aw 还给本模块（aa.a() 返回 true）加了一个「快速停止键」
    //  KeybindSetting（默认 ESC，按下即关闭模块并提示「已快速关闭」）。Evernight 1.0.3 没有
    //  设置级键位，InputPressEvent 又只在没有界面打开时发布，而本模块正是在背包界面里工作，
    //  因此未移植该快捷键；请用模块自身的键位关闭。若以后本体提供屏幕内可用的按键事件再补。

    @Override
    public void onEnable() {
        this.reset();
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    /**
     * 上游 {@code aw.onTick(TickEvent.Pre)} + {@code aa} 注册的两个状态处理器。
     *
     * <p>上游在 {@code onActivate()} 里立即执行一次检查；这里把检查放到第一个 tick
     * （{@link State#NONE}），这样在菜单里开启模块也不会在 {@code onEnable()} 里自关，
     * 延迟一 tick 对行为没有影响。
     */
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
                case NONE -> this.checkSetup();
                case NEXT -> this.nextStep();
                case USE -> this.useStep();
            }
        } catch (Throwable throwable) {
            // 上游 aw.onTick 的兜底：打印堆栈、提示、关闭模块。
            throwable.printStackTrace();
            this.message("addons.lotus.messages.unexpectedError", throwable.getMessage());
            this.toggle();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // 检查阶段（上游 aa.w()）
    // ---------------------------------------------------------------------------------------------

    /** 上游 {@code aa.w()}：校验背包与收纳袋布局，通过后进入复制循环。 */
    private void checkSetup() {
        if (!this.detectBundles()) {
            this.toggle();
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        Inventory inventory = player.getInventory();
        int emptyMaps = 0;
        int singleMapArt = 0;
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() == Items.MAP) {
                emptyMaps += stack.getCount();
            } else if (stack.getItem() == Items.FILLED_MAP) {
                if (stack.getCount() == 1) {
                    singleMapArt++;
                } else if (stack.getCount() > 2) {
                    this.message("addons.lotus.messages.mapArtStackTooLarge");
                    this.toggle();
                    return;
                }
            }
        }

        int toCopy = this.countFilledMapsInBundle(this.sourceBundleSlot) + singleMapArt;
        if (toCopy == 0) {
            this.message("addons.lotus.messages.noMapArtToCopy");
            this.toggle();
        } else if (emptyMaps < toCopy) {
            this.message("addons.lotus.messages.notEnoughEmptyMaps", toCopy, emptyMaps);
            this.toggle();
        } else if (this.firstEmptySlot() < 0) {
            this.message("addons.lotus.messages.needEmptySlotForCopy");
            this.toggle();
        } else {
            this.message("addons.lotus.messages.checkDone", toCopy);
            if (player.containerMenu instanceof InventoryMenu) {
                // 上游此处直接赋值状态、不重置延迟（进入时延迟本来就是 0）。
                this.state = State.NEXT;
            } else {
                // 上游 bp.a()：先把非背包容器关掉，否则下面的点击会落在容器的槽位上。
                ContainerInteractionUtils.closeScreen();
                this.changeState(State.NEXT);
            }
        }
    }

    /**
     * 上游 {@code aa.E()}：找出 3 个收纳袋并判断哪一个是待复制袋。
     *
     * <p>已复制袋里装着同一张地图的副本，所以两个已复制袋的内容集合一定有交集，
     * 交集最大的那一对就是已复制袋，剩下的一个是待复制袋。若三个袋子两两无交集，
     * 则退化为「只有 1 个袋子有东西、另外 2 个是空的」的情况。
     */
    private boolean detectBundles() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }

        Inventory inventory = player.getInventory();
        List<Integer> bundleSlots = new ArrayList<>();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).getItem() instanceof BundleItem) {
                bundleSlots.add(slot);
            }
        }
        if (bundleSlots.size() != BUNDLE_COUNT) {
            this.message("addons.lotus.messages.needThreeBundles", bundleSlots.size());
            return false;
        }

        int first = bundleSlots.get(0);
        int second = bundleSlots.get(1);
        int third = bundleSlots.get(2);
        Set<Object> firstContents = this.bundleContentsSet(inventory.getItem(first));
        Set<Object> secondContents = this.bundleContentsSet(inventory.getItem(second));
        Set<Object> thirdContents = this.bundleContentsSet(inventory.getItem(third));

        int firstSecond = intersectionSize(firstContents, secondContents);
        int firstThird = intersectionSize(firstContents, thirdContents);
        int secondThird = intersectionSize(secondContents, thirdContents);

        if (firstSecond <= 0 && firstThird <= 0 && secondThird <= 0) {
            boolean firstEmpty = firstContents.isEmpty();
            boolean secondEmpty = secondContents.isEmpty();
            boolean thirdEmpty = thirdContents.isEmpty();
            int emptyCount = (firstEmpty ? 1 : 0) + (secondEmpty ? 1 : 0) + (thirdEmpty ? 1 : 0);
            if (emptyCount != 2) {
                this.message("addons.lotus.messages.cannotDetectBundles");
                return false;
            }
            if (!firstEmpty) {
                this.sourceBundleSlot = first;
                this.targetBundleSlotA = second;
                this.targetBundleSlotB = third;
            } else if (!secondEmpty) {
                this.sourceBundleSlot = second;
                this.targetBundleSlotA = first;
                this.targetBundleSlotB = third;
            } else {
                this.sourceBundleSlot = third;
                this.targetBundleSlotA = first;
                this.targetBundleSlotB = second;
            }
            return true;
        }

        if (firstSecond >= firstThird && firstSecond >= secondThird) {
            this.targetBundleSlotA = first;
            this.targetBundleSlotB = second;
            this.sourceBundleSlot = third;
        } else if (firstThird >= secondThird) {
            this.targetBundleSlotA = first;
            this.targetBundleSlotB = third;
            this.sourceBundleSlot = second;
        } else {
            this.targetBundleSlotA = second;
            this.targetBundleSlotB = third;
            this.sourceBundleSlot = first;
        }
        return true;
    }

    /**
     * 上游 {@code aa.a_ItemStack_toSet(ItemStack)}：地图画用地图 id 标识，其它物品用物品类型标识。
     */
    private Set<Object> bundleContentsSet(ItemStack bundle) {
        Set<Object> contents = new HashSet<>();
        BundleContents bundleContents = bundle.get(DataComponents.BUNDLE_CONTENTS);
        if (bundleContents == null) {
            return contents;
        }
        for (ItemStack inner : bundleContents.items()) {
            contents.add(inner.getItem() == Items.FILLED_MAP
                    ? inner.get(DataComponents.MAP_ID)
                    : inner.getItem());
        }
        return contents;
    }

    /** 上游 {@code aa.a_Set_Set(Set,Set)}：两个内容集合的交集大小。 */
    private static int intersectionSize(Set<Object> first, Set<Object> second) {
        int size = 0;
        for (Object value : first) {
            if (second.contains(value)) {
                size++;
            }
        }
        return size;
    }

    // ---------------------------------------------------------------------------------------------
    // 状态 NEXT（上游 aa.x()）
    // ---------------------------------------------------------------------------------------------

    private void nextStep() {
        ItemStack copyStack = this.findInventoryStack(
                stack -> stack.getItem() == Items.FILLED_MAP && stack.getCount() == 2);
        if (!copyStack.isEmpty()) {
            // 2 张一份的地图画：拆成 1 + 1 放进两个已复制袋。
            this.copyIntoBothBundles(this.lastInventorySlot);
            return;
        }

        ItemStack sourceStack = this.findInventoryStack(
                stack -> stack.getItem() == Items.FILLED_MAP && stack.getCount() == 1);
        if (!sourceStack.isEmpty()) {
            // 有单张地图画，转到玩家合成格去复制（上游直接赋值，不重置延迟）。
            this.state = State.USE;
            return;
        }

        int remaining = this.countFilledMapsInBundle(this.sourceBundleSlot);
        if (remaining <= 0) {
            this.message("addons.lotus.messages.copyFinished");
            this.finish();
            return;
        }

        int emptySlot = this.firstEmptySlot();
        if (emptySlot < 0) {
            this.message("addons.lotus.messages.noSpaceInterrupted");
            this.finish();
            return;
        }
        // 从待复制袋里取出 1 张地图画放到空槽位，下一轮交给 USE 状态复制。
        this.takeOneFromBundle(emptySlot);
    }

    /**
     * 上游 {@code aa.f(int)}：把 2 张一份的地图画拆开，分别放进两个已复制袋。
     *
     * <p>点击序列：右键拿起一半（2 张里拿 1 张）→ 点已复制袋 A → 拿起剩下 1 张 →
     * 点已复制袋 B。
     */
    private void copyIntoBothBundles(int inventorySlot) {
        if (!this.ensureInventoryMenu()) {
            return;
        }
        if (this.countItemsInBundle(this.targetBundleSlotA) >= BUNDLE_CAPACITY
                || this.countItemsInBundle(this.targetBundleSlotB) >= BUNDLE_CAPACITY) {
            this.message("addons.lotus.messages.bagsFullInterrupted");
            this.finish();
            return;
        }

        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || inventorySlot < 0 || this.targetBundleSlotA < 0
                || this.targetBundleSlotB < 0) {
            return;
        }
        int containerId = player.containerMenu.containerId;
        int stackSlot = InventoryUtils.toMenuSlot(inventorySlot);
        InventoryUtils.clickSlot(containerId, stackSlot, 1, ClickType.PICKUP);
        InventoryUtils.clickSlot(containerId, InventoryUtils.toMenuSlot(this.targetBundleSlotA),
                0, ClickType.PICKUP);
        InventoryUtils.clickSlot(containerId, stackSlot, 0, ClickType.PICKUP);
        InventoryUtils.clickSlot(containerId, InventoryUtils.toMenuSlot(this.targetBundleSlotB),
                0, ClickType.PICKUP);
        this.resetDelay();
    }

    /**
     * 上游 {@code aa.g(int)} / {@code bp.d(int,int)}：从待复制袋里取出 1 张地图画。
     *
     * <p>点击序列：拿起整个收纳袋 → 右键空槽位把袋里 1 个物品倒出来 → 把收纳袋放回原槽位。
     */
    private void takeOneFromBundle(int emptySlot) {
        if (!this.ensureInventoryMenu()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.sourceBundleSlot < 0) {
            return;
        }
        int containerId = player.containerMenu.containerId;
        int bundleSlot = InventoryUtils.toMenuSlot(this.sourceBundleSlot);
        int targetSlot = InventoryUtils.toMenuSlot(emptySlot);
        InventoryUtils.clickSlot(containerId, bundleSlot, 0, ClickType.PICKUP);
        InventoryUtils.clickSlot(containerId, targetSlot, 1, ClickType.PICKUP);
        InventoryUtils.clickSlot(containerId, bundleSlot, 0, ClickType.PICKUP);
        this.resetDelay();
    }

    // ---------------------------------------------------------------------------------------------
    // 状态 USE（上游 aa.C()）：在玩家 2x2 合成格里复制一张地图画
    // ---------------------------------------------------------------------------------------------

    private void useStep() {
        if (!this.ensureInventoryMenu()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        InventoryMenu menu = player.inventoryMenu;
        int containerId = menu.containerId;

        if (!menu.getSlot(InventoryMenu.RESULT_SLOT).getItem().isEmpty()) {
            // 合成结果已就绪（原图 + 副本共 2 张）：shift-click 取到背包形成 2 张一份，
            // 交给 NEXT 状态拆进两个已复制袋。
            InventoryUtils.clickSlot(containerId, InventoryMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE);
        } else if (menu.getSlot(InventoryMenu.CRAFT_SLOT_START).getItem().isEmpty()) {
            ItemStack sourceStack = this.findInventoryStack(
                    stack -> stack.getItem() == Items.FILLED_MAP && stack.getCount() == 1);
            if (sourceStack.isEmpty()) {
                this.state = State.NEXT;
                return;
            }
            this.moveIntoSlot(this.lastInventorySlot, InventoryMenu.CRAFT_SLOT_START, containerId);
        } else if (menu.getSlot(InventoryMenu.CRAFT_SLOT_START + 1).getItem().isEmpty()) {
            ItemStack emptyMap = this.findInventoryStack(stack -> stack.getItem() == Items.MAP);
            if (emptyMap.isEmpty()) {
                this.message("addons.lotus.messages.noEmptyMapInterrupted");
                this.finish();
                return;
            }
            this.moveIntoSlot(this.lastInventorySlot, InventoryMenu.CRAFT_SLOT_START + 1, containerId);
        }
        this.resetDelay();
    }

    /** 上游 {@code aa.a(int,int)}：把背包槽位里的整叠物品 PICKUP 到合成格。 */
    private void moveIntoSlot(int inventorySlot, int menuSlot, int containerId) {
        if (inventorySlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(containerId, InventoryUtils.toMenuSlot(inventorySlot),
                0, ClickType.PICKUP);
        InventoryUtils.clickSlot(containerId, menuSlot, 0, ClickType.PICKUP);
    }

    /**
     * 上游 {@code aa.D()}：保证当前打开的是玩家背包界面。
     *
     * <p>背包菜单是打开状态时，若界面不是 {@link InventoryScreen} 就（重新）打开它；否则关闭
     * 外来容器并重置延迟，返回 {@code false} 让调用方等下一轮。
     */
    private boolean ensureInventoryMenu() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        if (player.containerMenu instanceof InventoryMenu) {
            if (!(this.evernightMinecraft.screen instanceof InventoryScreen)) {
                this.evernightMinecraft.setScreen(new InventoryScreen(player));
            }
            return true;
        }
        // 上游 bp.a()：关闭非背包容器（LocalPlayer#closeContainer 会自己发关闭包）。
        ContainerInteractionUtils.closeScreen();
        this.resetDelay();
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // 背包查找（上游 ag.a_Predicate / ag.p）
    // ---------------------------------------------------------------------------------------------

    /**
     * 上游 {@code ag.a_Predicate(Predicate)}：从轮转游标开始遍历 36 个背包槽，返回命中物品的副本，
     * 同时把命中槽位记录到 {@link #lastInventorySlot}（上游 {@code ag.t()}）。
     */
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
                this.lastInventorySlot = this.inventoryCursor;
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 上游 {@code ag.p()}：第一个空背包槽位，没有则 -1。 */
    private int firstEmptySlot() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    /** 上游 {@code aa.h(int)}：收纳袋里地图画的总张数。 */
    private int countFilledMapsInBundle(int inventorySlot) {
        BundleContents contents = this.bundleContentsOf(inventorySlot);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack inner : contents.items()) {
            if (inner.getItem() == Items.FILLED_MAP) {
                count += inner.getCount();
            }
        }
        return count;
    }

    /** 上游 {@code aa.i(int)}：收纳袋里所有物品的总数量。 */
    private int countItemsInBundle(int inventorySlot) {
        BundleContents contents = this.bundleContentsOf(inventorySlot);
        if (contents == null) {
            return 0;
        }
        int count = 0;
        for (ItemStack inner : contents.items()) {
            count += inner.getCount();
        }
        return count;
    }

    private BundleContents bundleContentsOf(int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || inventorySlot < 0) {
            return null;
        }
        return player.getInventory().getItem(inventorySlot).get(DataComponents.BUNDLE_CONTENTS);
    }

    // ---------------------------------------------------------------------------------------------
    // 结束 / 延迟 / 文本
    // ---------------------------------------------------------------------------------------------

    /** 上游 {@code aa.F()}：关掉容器界面并停止模块。 */
    private void finish() {
        ContainerInteractionUtils.closeScreen();
        this.toggle();
    }

    /** 上游 {@code aw.c_be(...)}：切换状态并等待一个「延迟」。 */
    private void changeState(State next) {
        this.state = next;
        this.resetDelay();
    }

    /** 上游 {@code ag.l()}：把延迟重置为设置值。 */
    private void resetDelay() {
        this.delay = this.delaySetting.getValue();
    }

    /** 上游 {@code aa.onActivate()} / {@code aw.onDeactivate()}：清空全部运行状态。 */
    private void reset() {
        this.state = State.NONE;
        this.delay = 0;
        this.sourceBundleSlot = -1;
        this.targetBundleSlotA = -1;
        this.targetBundleSlotB = -1;
        this.inventoryCursor = 0;
        this.lastInventorySlot = -1;
    }

    /**
     * 上游 {@code Module.warning(String, Object...)}。
     *
     * <p>TODO(port): 上游 warning 带红色前缀与提示音；Evernight 只有一种聊天签名，
     * 这里统一用 {@link ChatUtils#moduleSignature(evernight.client.modules.Module)} 输出。
     */
    private void message(String key, Object... args) {
        String text = Translations.get(key);
        if (args.length > 0) {
            text = String.format(Locale.ROOT, text, args);
        }
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    /** 上游 {@code bf.h}（NEXT）与 {@code bf.I}（USE），外加移植用的初始态 NONE。 */
    private enum State {
        NONE,
        NEXT,
        USE
    }
}
