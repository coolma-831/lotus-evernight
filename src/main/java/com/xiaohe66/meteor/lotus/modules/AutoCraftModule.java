package com.xiaohe66.meteor.lotus.modules;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import evernight.addon.AddonModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.InventoryUtils;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.item.equipment.trim.ArmorTrim;

/**
 * Port of {@code V_} (S自动合成) from Lotus (author xiaohe66), GPL-3.0.
 *
 * <p>必须在工作台或锻造台界面里使用：
 * <ul>
 *     <li>工作台：按「合成键」后按配方书自动放置配方并循环取出产物；若结果槽已有物品，
 *         则把它加入「合成物品」列表。潜影盒按染料循环染色，烟花按「烟花速度」调整火药数量。</li>
 *     <li>锻造台：按「合成键」记录当前已放置的模板/底材/附加材料，然后循环补料并取出产物。</li>
 * </ul>
 *
 * <p>原模块的两个按键是 Meteor 的 {@code KeybindSetting}；Evernight 1.0.3 没有设置级键位，
 * 且 {@code InputPressEvent} 只在没有界面打开时发布（本模块恰恰是在容器界面里按键），
 * 因此这里改为每 tick 轮询 GLFW 按键状态并做上升沿检测，键名仍可通过设置修改。
 */
public final class AutoCraftModule extends AddonModule {
    /** 潜影盒 → 染料（上游静态表 {@code b}）。*/
    private static final Map<Item, Item> SHULKER_DYES;

    static {
        Map<Item, Item> dyes = new HashMap<>();
        dyes.put(Items.WHITE_SHULKER_BOX, Items.WHITE_DYE);
        dyes.put(Items.ORANGE_SHULKER_BOX, Items.ORANGE_DYE);
        dyes.put(Items.MAGENTA_SHULKER_BOX, Items.MAGENTA_DYE);
        dyes.put(Items.LIGHT_BLUE_SHULKER_BOX, Items.LIGHT_BLUE_DYE);
        dyes.put(Items.YELLOW_SHULKER_BOX, Items.YELLOW_DYE);
        dyes.put(Items.LIME_SHULKER_BOX, Items.LIME_DYE);
        dyes.put(Items.PINK_SHULKER_BOX, Items.PINK_DYE);
        dyes.put(Items.GRAY_SHULKER_BOX, Items.GRAY_DYE);
        dyes.put(Items.LIGHT_GRAY_SHULKER_BOX, Items.LIGHT_GRAY_DYE);
        dyes.put(Items.CYAN_SHULKER_BOX, Items.CYAN_DYE);
        dyes.put(Items.PURPLE_SHULKER_BOX, Items.PURPLE_DYE);
        dyes.put(Items.BLUE_SHULKER_BOX, Items.BLUE_DYE);
        dyes.put(Items.BROWN_SHULKER_BOX, Items.BROWN_DYE);
        dyes.put(Items.GREEN_SHULKER_BOX, Items.GREEN_DYE);
        dyes.put(Items.RED_SHULKER_BOX, Items.RED_DYE);
        dyes.put(Items.BLACK_SHULKER_BOX, Items.BLACK_DYE);
        SHULKER_DYES = Collections.unmodifiableMap(dyes);
    }

    /** 上游「染料没进网格」时等待服务端同步的间隔。*/
    private static final long SHULKER_RETRY_MS = 1000L;
    private static final int GRID_FIRST_SLOT = 1;
    private static final int GRID_LAST_SLOT = 9;
    private static final int INVENTORY_SLOT_COUNT = 36;

    private final Setting<Integer> delaySetting;
    private final Setting<Set<Item>> craftItemsSetting;
    private final Setting<String> craftKeySetting;
    private final Setting<String> dropKeySetting;
    private final Setting<FireworkSpeed> fireworkSpeedSetting;
    private final Setting<Boolean> closeScreenSetting;

    /** 当前要合成的物品（上游 {@code i}）。*/
    private Item targetItem;
    /** 是否已按下合成键、正在循环（上游 {@code l}）。*/
    private boolean running;
    /** 本次是否「合成并丢出」（上游 {@code m}）。*/
    private boolean dropMode;
    /** 已把潜影盒放进网格、等待服务端返回结果的标记（上游 {@code n}）。*/
    private boolean waitingForShulker;
    /** 上一次放潜影盒的时间（上游 {@code o}）。*/
    private long lastShulkerPlacedAt;
    /** 烟花需要的火药数量（上游 {@code p}）。*/
    private int fireworkFlight;
    /** 锻造台：模板/底材/附加材料（上游 {@code q/r/s}）。*/
    private Item templateItem;
    private Item baseItem;
    private Item additionItem;
    /** 锻造台：当前已放置成品的纹饰（上游 {@code t}）。*/
    private ArmorTrim trim;

    /** 上游基类 {@code ag} 的「延迟」倒计时（上游 {@code a}）。*/
    private int delayTicks;
    /** 背包搜索的轮转游标（上游 {@code b}）。*/
    private int inventoryCursor;
    /** 最近一次在背包里找到的槽位（上游 {@code t()}）。*/
    private int lastInventorySlot = -1;
    private boolean craftKeyWasDown;
    private boolean dropKeyWasDown;

    public AutoCraftModule(ModuleCategory category) {
        super("lotus", "LotusAutoCraft",
                "Crafts automatically at a crafting table, or repeats a placed smithing recipe.",
                category);
        this.delaySetting = this.add(new NumberSetting<>("Delay", 3, 0, 40));
        this.craftItemsSetting = this.add(new RegistrySetSetting<Item>("CraftItems", BuiltInRegistries.ITEM));
        this.craftKeySetting = this.add(new StringSetting("CraftKey", "c"));
        this.dropKeySetting = this.add(new StringSetting("DropKey", "v"));
        this.fireworkSpeedSetting = this.add(new EnumSetting<>("FireworkSpeed", FireworkSpeed.Speed3));
        this.closeScreenSetting = this.add(new BooleanSetting("CloseScreen", true));
    }

    @Override
    public void onEnable() {
        this.delayTicks = 0;
        this.inventoryCursor = 0;
        this.lastInventorySlot = -1;
        this.craftKeyWasDown = false;
        this.dropKeyWasDown = false;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        this.pollActionKeys();

        // 上游 ag#d()：延迟计时器每 tick 递减，为 0 时才允许执行一次操作。
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }
        if (!this.running) {
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof CraftingMenu craftingMenu) {
            if (this.craftItemsSetting.getValue().isEmpty()) {
                return;
            }
            this.tickCraftingMenu(craftingMenu);
        } else if (menu instanceof SmithingMenu smithingMenu) {
            if (this.templateItem == null && this.baseItem == null && this.additionItem == null) {
                this.finish();
                return;
            }
            this.tickSmithingMenu(smithingMenu);
        } else {
            this.finish();
        }
    }

    // ------------------------------------------------------------------
    // 按键
    // ------------------------------------------------------------------

    /**
     * 轮询「合成键 / 丢出合成键」。
     *
     * <p>TODO(port): 上游是 Meteor 的 KeybindSetting（按下即回调，界面打开时也生效）。Evernight 1.0.3
     * 没有设置级键位，InputPressEvent 又只在 {@code minecraft.screen == null} 时发布，
     * 因此这里用 GLFW 状态轮询 + 上升沿检测；若以后本体提供了屏幕内可用的按键事件，应替换本方法。
     */
    private void pollActionKeys() {
        boolean craftDown = this.isBoundKeyDown(this.craftKeySetting.getValue());
        if (craftDown && !this.craftKeyWasDown) {
            this.startCrafting(false);
        }
        this.craftKeyWasDown = craftDown;

        boolean dropDown = this.isBoundKeyDown(this.dropKeySetting.getValue());
        if (dropDown && !this.dropKeyWasDown) {
            this.startCrafting(true);
        }
        this.dropKeyWasDown = dropDown;
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
     * <p>接受 {@code c} 这样的单键名，也接受 {@code key.keyboard.left.shift} 这样的完整名字；
     * 无法识别时返回 -1（相当于未绑定）。
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
    // 触发一次合成（上游 a(boolean)）
    // ------------------------------------------------------------------

    private void startCrafting(boolean drop) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (this.isWorldUnavailable() || player == null) {
            return;
        }

        if (player.containerMenu instanceof CraftingMenu craftingMenu) {
            if (!drop && !this.hasEmptyInventorySlot()) {
                this.info("addons.lotus.messages.needEmptySlot");
                return;
            }

            Item selected = Items.AIR;
            ItemStack result = craftingMenu.getResultSlot().getItem();
            if (!result.isEmpty()) {
                Set<Item> selectedItems = this.craftItemsSetting.getValue();
                if (!selectedItems.contains(result.getItem())) {
                    selectedItems.add(result.getItem());
                }
                selected = result.getItem();
            }

            if (selected == Items.AIR) {
                for (Map.Entry<Item, RecipeDisplayId> entry : this.collectCraftableRecipes().entrySet()) {
                    Item item = entry.getKey();
                    if (this.craftItemsSetting.getValue().contains(item)) {
                        selected = item;
                        // 优先选潜影盒（上游 b.containsKey(item)），让染色循环先跑起来。
                        if (SHULKER_DYES.containsKey(item)) {
                            break;
                        }
                    }
                }
            }

            if (selected == Items.AIR) {
                return;
            }
            if (selected == Items.FIREWORK_ROCKET) {
                this.fireworkFlight = this.fireworkSpeedSetting.getValue().flightDuration();
            }

            this.targetItem = selected;
            this.running = true;
            this.dropMode = drop;
            this.waitingForShulker = false;
            this.info("addons.lotus.messages.craftingItem", selected.getName().getString());
            return;
        }

        if (player.containerMenu instanceof SmithingMenu smithingMenu) {
            if (!drop && !this.hasEmptyInventorySlot()) {
                this.info("addons.lotus.messages.needEmptySlot");
                return;
            }

            ItemStack template = smithingMenu.getSlot(SmithingMenu.TEMPLATE_SLOT).getItem();
            ItemStack base = smithingMenu.getSlot(SmithingMenu.BASE_SLOT).getItem();
            ItemStack addition = smithingMenu.getSlot(SmithingMenu.ADDITIONAL_SLOT).getItem();
            ItemStack result = smithingMenu.getSlot(SmithingMenu.RESULT_SLOT).getItem();

            if (result.isEmpty() && (template.isEmpty() || base.isEmpty() || addition.isEmpty())) {
                return;
            }
            if (!result.isEmpty()) {
                this.trim = result.get(DataComponents.TRIM);
            }

            this.templateItem = template.isEmpty() ? null : template.getItem();
            this.baseItem = base.isEmpty() ? null : base.getItem();
            this.additionItem = addition.isEmpty() ? null : addition.getItem();
            this.targetItem = result.isEmpty() ? Items.AIR : result.getItem();
            this.running = true;
            this.dropMode = drop;
            this.waitingForShulker = false;

            String resultName = this.targetItem == Items.AIR
                    ? Translations.get("addons.lotus.messages.recipeDetected")
                    : this.targetItem.getName().getString();
            this.info("addons.lotus.messages.smithingCraft", resultName);
            return;
        }

        this.running = false;
    }

    // ------------------------------------------------------------------
    // 工作台
    // ------------------------------------------------------------------

    private void tickCraftingMenu(CraftingMenu menu) {
        if (this.targetItem == Items.FIREWORK_ROCKET) {
            this.tickFireworkCrafting(menu);
        } else if (isShulkerBox(this.targetItem)) {
            // 上游把「潜影盒」整体交给染色分支；无色潜影盒没有染料映射，上游同样合不出来。
            // TODO(port): 若要修复，应让无色 SHULKER_BOX 走下面的配方书分支（这里保持上游行为）。
            this.tickShulkerCrafting(menu);
        } else {
            this.tickRecipeBookCrafting(menu);
        }
    }

    private void tickRecipeBookCrafting(CraftingMenu menu) {
        RecipeDisplayId recipe = null;
        if (this.targetItem != null) {
            recipe = this.collectCraftableRecipes().get(this.targetItem);
        }
        if (recipe == null) {
            this.finish();
            return;
        }
        this.evernightMinecraft.gameMode.handlePlaceRecipe(menu.containerId, recipe, true);
        ClickType clickType = this.dropMode ? ClickType.THROW : ClickType.QUICK_MOVE;
        // 上游固定用 button=1 + (THROW|QUICK_MOVE) 点击结果槽 0。
        InventoryUtils.clickSlot(menu.containerId, CraftingMenu.RESULT_SLOT, 1, clickType);
        this.resetDelay();
    }

    /**
     * 潜影盒染色循环（上游 b_CraftingMenu）。
     *
     * <p>网格里没有潜影盒时先放一个待染色的盒子；没有染料时补染料；两者都有就把成品取出。
     */
    private void tickShulkerCrafting(CraftingMenu menu) {
        ItemStack result = menu.getResultSlot().getItem();
        if (this.targetItem != null && result.getItem() == this.targetItem) {
            this.waitingForShulker = false;
            this.clickResultSlot(menu, CraftingMenu.RESULT_SLOT);
            this.resetDelay();
            return;
        }

        Item dye = SHULKER_DYES.get(this.targetItem);
        boolean gridHasNoShulker = true;
        boolean gridHasNoDye = true;
        for (int slot = GRID_FIRST_SLOT; slot <= GRID_LAST_SLOT; slot++) {
            Item item = menu.getSlot(slot).getItem().getItem();
            if (isShulkerBox(item)) {
                gridHasNoShulker = false;
            }
            if (item == dye) {
                gridHasNoDye = false;
            }
        }

        if (gridHasNoShulker) {
            if (this.waitingForShulker) {
                if (System.currentTimeMillis() - this.lastShulkerPlacedAt < SHULKER_RETRY_MS) {
                    this.resetDelay();
                    return;
                }
                this.waitingForShulker = false;
            }
            ItemStack found = this.findInventoryStack(stack -> isShulkerBox(stack.getItem())
                    && stack.getItem() != this.targetItem);
            if (found.isEmpty()) {
                this.finish();
                return;
            }
            this.shiftClickFoundSlot();
            this.waitingForShulker = true;
            this.lastShulkerPlacedAt = System.currentTimeMillis();
            this.resetDelay();
        } else if (gridHasNoDye) {
            this.waitingForShulker = false;
            ItemStack found = this.findInventoryStack(stack -> stack.getItem() == dye);
            if (found.isEmpty()) {
                this.finish();
                return;
            }
            this.shiftClickFoundSlot();
            this.resetDelay();
        } else {
            this.resetDelay();
        }
    }

    /** 烟花合成循环（上游 c_CraftingMenu）。*/
    private void tickFireworkCrafting(CraftingMenu menu) {
        ItemStack result = menu.getResultSlot().getItem();
        if (result.getItem() == Items.FIREWORK_ROCKET
                && getFlightDuration(result) == this.fireworkFlight) {
            this.clickResultSlot(menu, CraftingMenu.RESULT_SLOT);
            this.resetDelay();
            return;
        }

        int paper = 0;
        int gunpowder = 0;
        for (int slot = GRID_FIRST_SLOT; slot <= GRID_LAST_SLOT; slot++) {
            Item item = menu.getSlot(slot).getItem().getItem();
            if (item == Items.PAPER) {
                paper++;
            } else if (item == Items.GUNPOWDER) {
                gunpowder++;
            } else if (item != Items.AIR) {
                this.info("addons.lotus.messages.wrongCraftingMaterial");
                this.finish();
                return;
            }
        }

        if (paper <= 0) {
            ItemStack found = this.findInventoryStack(stack -> stack.getItem() == Items.PAPER);
            if (found.isEmpty()) {
                this.finish();
            } else {
                this.shiftClickFoundSlot();
                this.resetDelay();
            }
        } else if (gunpowder > this.fireworkFlight) {
            // 火药多了：把网格里的火药 shift 回背包。
            for (int slot = GRID_FIRST_SLOT; slot <= GRID_LAST_SLOT; slot++) {
                if (menu.getSlot(slot).getItem().getItem() == Items.GUNPOWDER) {
                    InventoryUtils.clickSlot(menu.containerId, slot, 0, ClickType.QUICK_MOVE);
                    this.resetDelay();
                    return;
                }
            }
        } else if (gunpowder < this.fireworkFlight) {
            ItemStack found = this.findInventoryStack(stack -> stack.getItem() == Items.GUNPOWDER);
            if (found.isEmpty()) {
                this.finish();
            } else {
                this.shiftClickFoundSlot();
                this.resetDelay();
            }
        } else {
            this.resetDelay();
        }
    }

    // ------------------------------------------------------------------
    // 锻造台（上游 a_SmithingMenu）
    // ------------------------------------------------------------------

    private void tickSmithingMenu(SmithingMenu menu) {
        ItemStack result = menu.getSlot(SmithingMenu.RESULT_SLOT).getItem();
        if (!result.isEmpty()) {
            ArmorTrim resultTrim = result.get(DataComponents.TRIM);
            if (resultTrim != null) {
                this.trim = resultTrim;
            }
            this.clickResultSlot(menu, SmithingMenu.RESULT_SLOT);
        } else if (menu.getSlot(SmithingMenu.TEMPLATE_SLOT).getItem().isEmpty()
                && this.templateItem != null
                && this.templateItem != Items.AIR) {
            ItemStack found = this.findInventoryStack(stack -> stack.getItem() == this.templateItem);
            if (found.isEmpty()) {
                this.finish();
                return;
            }
            this.shiftClickFoundSlot();
        } else if (menu.getSlot(SmithingMenu.BASE_SLOT).getItem().isEmpty()
                && this.baseItem != null
                && this.baseItem != Items.AIR) {
            ItemStack found = this.findInventoryStack(stack -> {
                if (stack.getItem() != this.baseItem) {
                    return false;
                }
                if (this.trim != null) {
                    ArmorTrim stackTrim = stack.get(DataComponents.TRIM);
                    return !this.trim.equals(stackTrim);
                }
                return true;
            });
            if (found.isEmpty()) {
                this.finish();
                return;
            }
            this.shiftClickFoundSlot();
        } else if (menu.getSlot(SmithingMenu.ADDITIONAL_SLOT).getItem().isEmpty()
                && this.additionItem != null
                && this.additionItem != Items.AIR) {
            ItemStack found = this.findInventoryStack(stack -> stack.getItem() == this.additionItem);
            if (found.isEmpty()) {
                this.finish();
                return;
            }
            this.shiftClickFoundSlot();
        }

        this.resetDelay();
    }

    // ------------------------------------------------------------------
    // 配方书匹配（上游 c()）
    // ------------------------------------------------------------------

    private Map<Item, RecipeDisplayId> collectCraftableRecipes() {
        Map<Item, RecipeDisplayId> recipes = new HashMap<>();
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.evernightMinecraft.level == null) {
            return recipes;
        }

        ClientRecipeBook recipeBook = player.getRecipeBook();
        for (RecipeCollection collection : recipeBook.getCollections()) {
            for (RecipeDisplayEntry entry : collection.getSelectedRecipes(
                    RecipeCollection.CraftableStatus.CRAFTABLE)) {
                RecipeDisplay display = entry.display();
                for (ItemStack stack : display.result().resolveForStacks(
                        SlotDisplayContext.fromLevel(this.evernightMinecraft.level))) {
                    Item item = stack.getItem();
                    if (this.craftItemsSetting.getValue().contains(item)) {
                        recipes.put(item, entry.id());
                    }
                }
            }
        }
        return recipes;
    }

    // ------------------------------------------------------------------
    // 背包 / 点击工具
    // ------------------------------------------------------------------

    /** 上游 a_Predicate：从轮转游标开始遍历 36 个背包槽，返回匹配物品的副本。*/
    private ItemStack findInventoryStack(java.util.function.Predicate<ItemStack> predicate) {
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

    /** 上游 InvUtils.shiftClick().slot(index)：把刚找到的背包槽 shift 进容器。*/
    private void shiftClickFoundSlot() {
        if (this.lastInventorySlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(InventoryUtils.toMenuSlot(this.lastInventorySlot), 0, ClickType.QUICK_MOVE);
    }

    /** 上游 InvUtils.drop()/shiftClick().slotId(id)：对结果槽丢出或 shift 取出。*/
    private void clickResultSlot(AbstractContainerMenu menu, int slotId) {
        if (this.dropMode) {
            InventoryUtils.clickSlot(menu.containerId, slotId, 1, ClickType.THROW);
        } else {
            InventoryUtils.clickSlot(menu.containerId, slotId, 0, ClickType.QUICK_MOVE);
        }
    }

    private boolean hasEmptyInventorySlot() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOT_COUNT; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isShulkerBox(Item item) {
        return item != null && (item == Items.SHULKER_BOX || SHULKER_DYES.containsKey(item));
    }

    private static int getFlightDuration(ItemStack stack) {
        if (stack.getItem() != Items.FIREWORK_ROCKET) {
            return -1;
        }
        Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
        return fireworks == null ? -1 : fireworks.flightDuration();
    }

    // ------------------------------------------------------------------
    // 结束 / 延迟 / 文本
    // ------------------------------------------------------------------

    /** 上游 b()：提示结束、清空锻造台配方状态并（可选）关闭界面。*/
    private void finish() {
        this.info("addons.lotus.messages.craftFinished");
        this.running = false;
        this.templateItem = null;
        this.baseItem = null;
        this.additionItem = null;
        this.trim = null;
        this.closeScreenIfEnabled();
    }

    /** 上游 ag#l()：把延迟重置为设置值。*/
    private void resetDelay() {
        this.delayTicks = this.delaySetting.getValue();
    }

    /** 上游 bp.a()：只在不是玩家背包界面时关闭当前容器。*/
    private void closeScreenIfEnabled() {
        if (!this.closeScreenSetting.getValue()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
            ContainerInteractionUtils.closeScreen();
        }
    }

    /**
     * 上游 info()/warning()：Evernight 只有一种聊天签名，警告文案也走普通聊天。
     *
     * <p>TODO(port): 上游 warning 带红色前缀；本体没有对应 API，这里统一用模块签名输出。
     */
    private void info(String translationKey, Object... args) {
        String pattern = Translations.get(translationKey);
        String message = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(message), ChatUtils.moduleSignature(this));
    }

    /** 烟花速度：对应上游枚举 {@code W_}（一速/二速/三速），值即火药数量。*/
    public enum FireworkSpeed implements NamedEntry {
        Speed1("Speed1", 1),
        Speed2("Speed2", 2),
        Speed3("Speed3", 3);

        private final String name;
        private final int flightDuration;

        FireworkSpeed(String name, int flightDuration) {
            this.name = name;
            this.flightDuration = flightDuration;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String getDisplayName() {
            return Translations.get("addons.lotus.names.values." + this.name);
        }

        public int flightDuration() {
            return this.flightDuration;
        }
    }
}
