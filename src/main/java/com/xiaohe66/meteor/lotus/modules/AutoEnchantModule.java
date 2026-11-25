package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.modules.combat.Aura.AuraModule;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.options.AuraWeaponChoice;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.ExperienceBottleUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import evernight.client.utils.world.BlockInteractionUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundRenameItemPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.AABB;

/**
 * Port of {@code X_} (A自动附魔) from Lotus (Meteor Client addon, author xiaohe66, GPL-3.0).
 *
 * <p>自动附魔（祛魔）与改名。附魔/祛魔必须搭配「附魔平台」使用，仅改名时可以随处使用：
 * <ul>
 *     <li>平台识别：物品展示框里挂<b>红石</b>表示输入箱（展示框背后的双人箱，或展示框下方 2 格处的潜影盒），
 *     挂<b>红石块</b>表示输出箱，挂<b>附魔书</b>表示该书所在潜影盒（展示框下方 2 格）；
 *     半径 5×3 内找铁砧（{@code anvil}）与砂轮（{@code grindstone}）。</li>
 *     <li>附魔流程：从输入箱取装备 → 装备上有「不在目标列表」的附魔就先丢砂轮祛魔 →
 *     按最短的「铁砧合并树」把缺少的附魔书从书箱里取出来、两两合并、再与装备合并 →
 *     附魔满级后把装备 shift 进输出箱。</li>
 *     <li>经验：来源可选「杀怪（开 Aura 挂机）」或「经验瓶」；经验不足时先去补经验。</li>
 *     <li>改名：在铁砧里把物品改成设置的名称；勾选「不附魔仅改名」时完全不碰容器，只循环改名主手物品。</li>
 * </ul>
 *
 * <p>移植说明：
 * <ul>
 *     <li>原模块继承 {@code aw}（Lotus 的状态机基类，再往上 {@code ag}）。这里把用到的部分内联成本类的
 *     私有实现：{@code d()} → {@link #delayTicks} 倒计时、{@code l()/c(int)} → {@link #setDelay(int)}、
 *     {@code a_be/b_be} → {@link #closeAndGoTo(Step)}、{@code c_be} → {@link #goTo(Step)}、
 *     {@code d_be} → {@link #scheduleStart(Step)}、{@code e()} → {@code player.getInventory()}、
 *     {@code t()/v()} → 背包/容器搜索游标。{@code ag} 的「延迟」设置保留为 {@code Delay}。</li>
 *     <li>原模块的「快速停止键」（{@code aw} 的 ESC 键位）与 {@code bE_/bG_/bF_/bH_} 铁砧合并树
 *     都自实现了：ESC 键位改用模块键位，合并树变成 {@link EnchantNode} 三个嵌套类型。</li>
 *     <li>上游用 Meteor 的 {@code KillAura} 当经验来源（开杀、并屏蔽它的武器槽位）。Evernight 对应
 *     {@link AuraModule}：开启时套用「武器模式 = SWAP」，关闭时直接 {@code disable()}。</li>
 *     <li>Meteor 的 {@code info/warning/error} 三档聊天在 Evernight 只有一个输出通道，全部走
 *     {@code printChat} + 模块签名，文本一律来自语言表 {@code addons.lotus.*}。</li>
 * </ul>
 *
 * <p>已知差异 / 未移植（代码里同样标了 {@code TODO(port)}）：
 * <ul>
 *     <li>改名时上游还会把新名字写进 {@code AnvilScreen} 的输入框；1.21.11 里该字段是 private，
 *     插件不改本体类就只能发 {@code ServerboundRenameItemPacket}（服务端照样会改名）。</li>
 *     <li>上游搜索背包时会跳过「Lotus 助手」模块锁定栏位里的物品；该助手模块没有移植，
 *     所以锁定位不再生效。</li>
 *     <li>上游「打开容器没有反应」的分支依赖一个永远为初始值的字段，属于死代码，未搬。</li>
 *     <li>上游用 {@code ItemFrame#getNearestViewDirection()} 判断朝向；1.21.11 已移除该方法，
 *     这里用等价的 {@code HangingEntity#getDirection()}。</li>
 *     <li>放置铁砧从「指定点下方那一面」改成 {@code BlockInteractionUtils.placeBlock}（自动找支撑面）。</li>
 *     <li>开容器走 {@code ContainerInteractionUtils.openContainer}：需要当前没有界面、且在 9 格内
 *     （远了交给 Baritone 走位），而上游是直接对着方块发右键包。</li>
 * </ul>
 */
public final class AutoEnchantModule extends AddonModule {
    /** 玩家背包（含快捷栏）的槽位数，对应上游 {@code bp.h()}。*/
    private static final int INVENTORY_SLOT_COUNT = 36;
    /** 上游 {@code ag#g} 里的 2000ms：容器打开后等服务器同步内容的最长时间。*/
    private static final long CONTAINER_SYNC_TIMEOUT_MS = 2000L;
    /** 上游改名失败重试间隔（1 秒）。*/
    private static final long RENAME_RETRY_MS = 1000L;
    /** 上游 {@code v}：铁砧合并树算出「可以开铁砧了」。*/
    private static final int RESULT_USE = -2;
    /** 上游 {@code w}：还缺附魔书，需要去书箱取。*/
    private static final int RESULT_NEED_BOOK = -3;

    private final Setting<XpSource> xpSourceSetting;
    private final Setting<Integer> xpDropDelaySetting;
    private final Setting<Boolean> renameSetting;
    private final Setting<String> newNameSetting;
    private final Setting<Boolean> renameOnlySetting;
    private final Setting<TargetItem> targetItemSetting;
    private final Setting<EnchantPreset> presetSetting;
    private final Setting<Set<String>> enchantmentsSetting;
    private final Setting<Integer> pauseTicksSetting;
    private final Setting<Integer> delaySetting;
    private final Setting<Integer> restartDelaySetting;

    /** 上游 {@code aw#m} / {@code o}：当前状态与「关界面后要去」的状态。*/
    private Step step = Step.NONE;
    private Step previousStep = Step.NONE;
    /** 上游 {@code d_be} 的定时任务：延时后进入的状态。*/
    private Step pendingStartStep = Step.NONE;
    /** 上游 {@code ag#a}：下一个操作前的等待 tick。*/
    private int delayTicks;
    /** 上游 {@code aw#p}：进入 {@code CLOSE_SCREEN} 后要补上的延迟。*/
    private int pendingDelay;
    /** 上游 {@code d_be} 的定时任务倒计时（秒 → tick）。*/
    private int startDelayTicks;
    /** 是否已经完成过一次平台识别。*/
    private boolean initialized;

    /** 上游 {@code z}：本地模拟铁砧，用来算经验消耗。*/
    private AnvilMenu simulatedAnvil;
    /** 上游 {@code A}：附魔 → 书箱（潜影盒）位置。*/
    private final Map<ResourceKey<Enchantment>, BlockPos> bookContainers = new HashMap<>();
    /** 上游 {@code B} / {@code C} / {@code D} / {@code E}：输入箱 / 砂轮 / 铁砧 / 输出箱。*/
    private BlockPos inputContainerPos;
    private BlockPos grindstonePos;
    private BlockPos anvilPos;
    private BlockPos outputContainerPos;
    /** 上游 {@code F}：这一轮要依次去取的书。*/
    private final LinkedList<ResourceKey<Enchantment>> bookQueue = new LinkedList<>();
    /** 上游 {@code G}：还差多少级经验。*/
    private int xpNeeded;
    /** 上游 {@code H} / {@code I} / {@code J}：铁砧输入槽的背包下标、附加槽、输入物品快照。*/
    private int inputSlot;
    private int additionalSlot;
    private ItemStack inputStack = ItemStack.EMPTY;
    /** 上游 {@code K}：只改名模式启动时主手物品的类型。*/
    private Item renameItem = Items.AIR;
    /** 上游 {@code L}：上次发改名包的时间。*/
    private long lastRenameAt;
    /** 上游 {@code ag#b} / {@code c}：背包 / 容器搜索游标。*/
    private int inventoryCursor;
    private int containerCursor;
    /** 上游 {@code ag#f} / {@code e}：最近一次打开的容器位置与时间。*/
    private BlockPos lastOpenedPos;
    private long lastOpenAt;
    /** 为了让 {@code bu.d()} 的武器模式可以还原，记录接管前的 Aura 武器模式。*/
    private AuraWeaponChoice previousAuraWeaponMode;

    public AutoEnchantModule(ModuleCategory category) {
        super("lotus", "LotusAutoEnchant",
                "Automatically enchants, disenchants and renames items at an enchanting platform.",
                category);

        this.xpSourceSetting = this.add(new EnumSetting<XpSource>("XpSource", XpSource.KillAura));
        this.xpDropDelaySetting = this.add(new NumberSetting<Integer>("XpDropDelay", 5, 0, 100,
                value -> this.xpSourceSetting.getValue() == XpSource.XpBottle));
        this.renameSetting = this.add(new BooleanSetting("Rename", true));
        this.newNameSetting = this.add(new StringSetting("NewName", "lotus打造的神兵"));
        this.renameOnlySetting = this.add(new BooleanSetting("RenameOnly", false));
        this.targetItemSetting = this.add(new EnumSetting<TargetItem>("Item", TargetItem.DiamondPickaxe,
                value -> !this.renameOnlySetting.getValue()));
        this.presetSetting = this.add(new EnumSetting<EnchantPreset>("Preset", EnchantPreset.Custom,
                value -> !this.renameOnlySetting.getValue()));
        this.enchantmentsSetting = this.add(new StringSetSetting("Enchantments"));
        this.pauseTicksSetting = this.add(new NumberSetting<Integer>("PauseTicks", 20, 0, 100,
                value -> !this.renameOnlySetting.getValue()));
        this.delaySetting = this.add(new NumberSetting<Integer>("Delay", 5, 0, 40));
        this.restartDelaySetting = this.add(new NumberSetting<Integer>("RestartDelay", 2, 1, 200));

        // 上游 e 的 onChanged：勾选「不附魔仅改名」时强制打开「改名」。
        this.renameOnlySetting.setChangeCallback(() -> {
            if (this.renameOnlySetting.getValue()) {
                this.renameSetting.setValue(true);
            }
        });
        // 上游 g 的 onChanged：换预设时同步附魔列表。
        this.presetSetting.setChangeCallback(this::applyPreset);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.step = Step.NONE;
        this.previousStep = Step.NONE;
        this.pendingStartStep = Step.NONE;
        this.delayTicks = 0;
        this.pendingDelay = 0;
        this.startDelayTicks = 0;
        this.xpNeeded = 0;
        this.inputSlot = 0;
        this.additionalSlot = 0;
        this.inputStack = ItemStack.EMPTY;
        this.renameItem = Items.AIR;
        this.lastRenameAt = 0L;
        this.inventoryCursor = 0;
        this.containerCursor = 0;
        this.lastOpenedPos = null;
        this.lastOpenAt = 0L;
        this.simulatedAnvil = null;
        this.bookContainers.clear();
        this.bookQueue.clear();
        this.initialized = false;
        this.initialize();
    }

    @Override
    public void onDisable() {
        this.step = Step.NONE;
        this.previousStep = Step.NONE;
        this.pendingStartStep = Step.NONE;
        this.startDelayTicks = 0;
        this.delayTicks = 0;
        this.pendingDelay = 0;

        // 上游 onDeactivate 里的 bu.e()。
        this.disableAura();
        AuraModule aura = ClientContext.getModule(AuraModule.class);
        if (aura != null && this.previousAuraWeaponMode != null) {
            aura.setWeaponMode(this.previousAuraWeaponMode);
            this.previousAuraWeaponMode = null;
        }
    }

    // ------------------------------------------------------------------
    // 主循环（上游 aw#onTick + X_ 的 8 个状态处理）
    // ------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        // 在主菜单里打开模块时 onEnable 拿不到玩家，进世界后补做一次平台识别。
        if (!this.initialized) {
            this.initialize();
            return;
        }
        // 上游 d_be：延时若干秒后才真正启动。
        if (this.startDelayTicks > 0) {
            this.startDelayTicks--;
            if (this.startDelayTicks == 0) {
                this.print("addons.lotus.messages.started");
                this.step = this.pendingStartStep;
                this.pendingStartStep = Step.NONE;
            }
            return;
        }
        if (this.step == Step.NONE) {
            return;
        }
        // 上游 ag#d()：等待计时器。
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return;
        }

        try {
            this.tickStep();
        } catch (Throwable error) {
            error.printStackTrace();
            this.print("addons.lotus.messages.unexpectedError", String.valueOf(error.getMessage()));
            this.toggle();
        }
    }

    private void tickStep() {
        switch (this.step) {
            case CLOSE_SCREEN -> this.tickCloseScreen();
            case NEXT -> this.tickNext();
            case TAKE_ITEM -> this.tickTakeItem();
            case PUT_ITEM -> this.tickPutItem();
            case GRIND -> this.tickGrind();
            case TAKE_BOOK -> this.tickTakeBook();
            case PLACE -> this.tickPlace();
            case USE -> this.tickUse();
            case LEVEL -> this.tickLevel();
            default -> {
                this.print("addons.lotus.messages.unhandledState", this.step.name());
                this.toggle();
            }
        }
    }

    /** 上游 aw#y()：关掉界面，回到「关之前想去」的状态，并补上挂起的延迟。*/
    private void tickCloseScreen() {
        this.closeContainer();
        this.step = this.previousStep;
        if (this.pendingDelay > 0) {
            this.setDelay(this.pendingDelay);
            this.pendingDelay = 0;
        } else {
            this.setDelay(this.delaySetting.getValue());
        }
    }

    /** 上游 X_#I()：决定下一步做什么。*/
    private void tickNext() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        if (this.renameOnlySetting.getValue()) {
            ItemStack toRename = this.findInventoryStack(stack -> stack.getItem() == this.renameItem
                    && !this.hasTargetName(stack));
            if (toRename.isEmpty()) {
                // 上游 a_String_toVoid + y() + toggle()：提示、关界面、关模块。
                this.print("addons.lotus.messages.renameFinished");
                this.step = Step.NONE;
                this.previousStep = Step.NONE;
                this.closeContainer();
                this.toggle();
                return;
            }
            this.setInputSlot(this.inventoryCursor);
            this.goTo(Step.USE);
            return;
        }

        Item target = this.targetItemSetting.getValue().item();
        ItemStack equipment = this.findEquipmentStack(stack -> stack.getItem() == target);
        if (equipment.isEmpty()) {
            this.goTo(Step.TAKE_ITEM);
            return;
        }

        Set<ResourceKey<Enchantment>> required = this.requiredEnchantments();
        Set<ResourceKey<Enchantment>> present = enchantmentKeys(equipment, false);
        // 装备上带着目标列表以外的附魔 → 先丢砂轮祛魔。
        // TODO(port): 上游对「无法祛除的诅咒附魔」会无限祛魔（砂轮不除诅咒），这里保持同样行为。
        if (!required.containsAll(present)) {
            this.setInputSlot(this.inventoryCursor);
            this.goTo(Step.GRIND);
            return;
        }

        Set<ResourceKey<Enchantment>> maxed = enchantmentKeys(equipment, true);
        if (maxed.containsAll(required)) {
            this.goTo(Step.PUT_ITEM);
            return;
        }

        Set<ResourceKey<Enchantment>> missing = new HashSet<>(required);
        missing.removeAll(maxed);
        EnchantNode task = this.buildAnvilTask(equipment, missing);
        this.resolveAnvilStep(task);
    }

    /** 上游 X_#G()：从输入箱里拿装备。*/
    private void tickTakeItem() {
        this.withContainer(this.inputContainerPos, menu -> {
            Item target = this.targetItemSetting.getValue().item();
            ItemStack found = this.findContainerStack(stack -> stack.getItem() == target);
            if (found.isEmpty()) {
                this.print("addons.lotus.messages.cannotSupply", target.getName().getString());
                this.setDelay(this.delaySetting.getValue());
                return;
            }
            this.shiftClickSlotId(this.containerCursor);
            this.print("addons.lotus.messages.tookEquipment");
            this.closeAndGoTo(Step.NEXT);
        });
    }

    /** 上游 X_#w()：把附魔好的装备卸进输出箱。*/
    private void tickPutItem() {
        this.withContainer(this.outputContainerPos, menu -> {
            Item target = this.targetItemSetting.getValue().item();
            Set<ResourceKey<Enchantment>> required = this.requiredEnchantments();
            ItemStack found = this.findEquipmentStack(stack -> stack.getItem() == target
                    && enchantmentKeys(stack, false).equals(required));
            if (found.isEmpty()) {
                this.closeAndGoTo(Step.NEXT);
                this.pendingDelay = this.pauseTicksSetting.getValue();
                return;
            }
            this.print("addons.lotus.messages.unloading");
            this.shiftClickInventory(this.inventoryCursor);
            this.setDelay(this.delaySetting.getValue());
        });
    }

    /** 上游 X_#F()：祛魔。*/
    private void tickGrind() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        if (player.containerMenu instanceof GrindstoneMenu grindstone) {
            if (grindstone.getSlot(GrindstoneMenu.INPUT_SLOT).getItem().isEmpty()) {
                if (this.inventoryItem(this.inputSlot).getItem() != this.inputStack.getItem()) {
                    this.closeAndGoTo(Step.NEXT);
                    return;
                }
                this.shiftClickInventory(this.inputSlot);
                this.setDelay(this.delaySetting.getValue());
            } else {
                this.print("addons.lotus.messages.grinding");
                this.shiftClickSlotId(GrindstoneMenu.RESULT_SLOT);
                this.closeAndGoTo(Step.NEXT);
            }
            return;
        }

        if (this.blockAt(this.grindstonePos).getBlock() != Blocks.GRINDSTONE) {
            // 上游 a_String_toVoid("砂轮位置错误")：提示并把状态清空。
            this.print("addons.lotus.messages.wrongGrindstone");
            this.step = Step.NONE;
            this.previousStep = Step.NONE;
            return;
        }
        this.openContainerAt(this.grindstonePos);
        this.setDelay(this.delaySetting.getValue());
    }

    /** 上游 X_#H()：去书箱取一本附魔书。*/
    private void tickTakeBook() {
        if (this.bookQueue.isEmpty()) {
            this.closeAndGoTo(Step.NEXT);
            return;
        }

        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        ResourceKey<Enchantment> enchantment = this.bookQueue.getFirst();
        BlockPos containerPos = this.bookContainers.get(enchantment);
        if (player.containerMenu instanceof InventoryMenu) {
            if (containerPos == null) {
                this.print("addons.lotus.messages.noBookContainer", this.enchantmentName(enchantment));
                this.toggle();
                return;
            }
            if (!isShulkerBoxItem(this.blockAt(containerPos).getBlock().asItem())) {
                this.print("addons.lotus.messages.noBookContainer", this.enchantmentName(enchantment));
                this.setDelay(this.delaySetting.getValue());
                return;
            }
        }

        this.withContainer(containerPos, menu -> {
            ItemStack found = this.findContainerStack(stack -> stack.getItem() == Items.ENCHANTED_BOOK
                    && enchantmentKeys(stack, false).contains(enchantment));
            if (found.isEmpty()) {
                this.print("addons.lotus.messages.cannotTakeBook", this.enchantmentName(enchantment));
                this.setDelay(this.delaySetting.getValue());
                return;
            }
            this.print("addons.lotus.messages.takingBook", this.enchantmentName(enchantment));
            this.shiftClickSlotId(this.containerCursor);
            this.bookQueue.poll();
            if (this.bookQueue.isEmpty()) {
                this.closeAndGoTo(Step.NEXT);
            } else {
                this.closeAndGoTo(Step.TAKE_BOOK);
            }
        });
    }

    /** 上游 X_#D()：把快捷栏里的铁砧放到平台位置。*/
    private void tickPlace() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        int slot = InventoryUtils.findInventorySlot(Items.ANVIL);
        if (slot < 0) {
            this.print("addons.lotus.messages.missingAnvil");
            this.toggle();
            return;
        }
        if (!BlockInteractionUtils.canPlaceAt(this.anvilPos, true)) {
            this.print("addons.lotus.messages.cannotPlaceAnvil");
            this.toggle();
            return;
        }

        this.disableAura();
        if (player.getMainHandItem().getItem() == Items.ANVIL) {
            this.print("addons.lotus.messages.placingAnvil");
            BlockInteractionUtils.placeBlock(this.anvilPos, true, InteractionHand.MAIN_HAND);
            this.goTo(Step.USE);
        } else {
            InventoryUtils.swapIntoSelectedSlot(slot);
            this.setDelay(this.delaySetting.getValue());
        }
    }

    /** 上游 X_#E()：在铁砧里合并 / 改名 / 取成品。*/
    private void tickUse() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        ItemStack input = this.inventoryItem(this.inputSlot);
        ItemStack additional = this.renameOnlySetting.getValue()
                ? ItemStack.EMPTY
                : this.inventoryItem(this.additionalSlot);
        String name = this.renameSetting.getValue() ? this.newNameSetting.getValue() : null;

        int cost = this.simulatedAnvilCost(input, additional, name);
        if (cost > player.experienceLevel) {
            this.xpNeeded = cost;
            this.closeAndGoTo(Step.LEVEL);
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof AnvilMenu anvilMenu)) {
            if (this.blockAt(this.anvilPos).isAir()) {
                this.print("addons.lotus.messages.replacingAnvil");
                this.goTo(Step.PLACE);
            } else {
                this.openContainerAt(this.anvilPos);
                this.setDelay(this.delaySetting.getValue());
            }
            return;
        }

        if (anvilMenu.getSlot(AnvilMenu.INPUT_SLOT).getItem().isEmpty()) {
            if (this.inventoryItem(this.inputSlot).getItem() != this.inputStack.getItem()) {
                this.closeAndGoTo(Step.NEXT);
                return;
            }
            this.shiftClickInventory(this.inputSlot);
            this.setDelay(this.delaySetting.getValue());
            return;
        }

        if (!this.renameOnlySetting.getValue()
                && anvilMenu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem().isEmpty()) {
            if (this.inventoryItem(this.additionalSlot).getItem() != additional.getItem()) {
                this.closeAndGoTo(Step.NEXT);
                return;
            }
            this.shiftClickInventory(this.additionalSlot);
            this.setDelay(this.delaySetting.getValue());
            return;
        }

        if (this.renameSetting.getValue()) {
            ItemStack result = anvilMenu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
            if (result.getItem() != Items.ENCHANTED_BOOK) {
                Component customName = result.getCustomName();
                if (customName == null || !Objects.equals(name, customName.getString())) {
                    long now = System.currentTimeMillis();
                    if (now - this.lastRenameAt < RENAME_RETRY_MS) {
                        this.print("addons.lotus.messages.renameTimeout");
                        this.setDelay(this.delaySetting.getValue());
                        return;
                    }
                    this.lastRenameAt = now;
                    this.print("addons.lotus.messages.renaming");
                    anvilMenu.setItemName(name);
                    // TODO(port): 上游还会把名字写进 AnvilScreen 的输入框
                    //  （((AnvilScreen) this.mc.screen).name）。1.21.11 里该字段是 private，
                    //  插件不改本体就只能依赖下面的改名包，服务端会按包改名。
                    PacketSender.send(new ServerboundRenameItemPacket(name));
                    this.setDelay(this.delaySetting.getValue());
                    return;
                }
            }
        }

        if (anvilMenu.getCost() > player.experienceLevel) {
            this.xpNeeded = anvilMenu.getCost();
            this.closeAndGoTo(Step.LEVEL);
            return;
        }

        this.shiftClickSlotId(AnvilMenu.RESULT_SLOT);
        this.lastRenameAt = 0L;
        if (this.renameOnlySetting.getValue()) {
            this.goTo(Step.NEXT);
        } else {
            this.closeAndGoTo(Step.NEXT);
        }
    }

    /** 上游 X_#C()：补经验。*/
    private void tickLevel() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        if (this.xpSourceSetting.getValue() == XpSource.XpBottle) {
            if (this.xpNeeded <= player.experienceLevel) {
                this.goTo(Step.NEXT);
                return;
            }

            int hotbarSlot = InventoryUtils.findHotbarSlot(Items.EXPERIENCE_BOTTLE);
            if (hotbarSlot >= 0) {
                // 上游 Rotations.rotate(yaw, 90, () -> gameMode.useItem(player, hand))：
                // 这里让 throwBottles 直接把旋转写进使用包（静默转向 90° 朝下）。
                InventoryUtils.SwapSlot source =
                        new InventoryUtils.SwapSlot(hotbarSlot, true, InteractionHand.MAIN_HAND);
                ExperienceBottleUtils.throwBottles(InteractionHand.MAIN_HAND, source, 1,
                        new float[]{player.getYRot(), 90.0F}, false);
                this.setDelay(this.xpDropDelaySetting.getValue());
                return;
            }

            int inventorySlot = InventoryUtils.findInventorySlot(
                    stack -> stack.getItem() == Items.EXPERIENCE_BOTTLE, false);
            if (inventorySlot >= 0) {
                InventoryUtils.swapIntoSelectedSlot(inventorySlot);
                this.setDelay(this.delaySetting.getValue());
                return;
            }

            this.print("addons.lotus.messages.missingXp");
            this.toggle();
            return;
        }

        // 经验来源 = 杀怪：开 Aura 挂机，经验够了再关掉。
        if (this.xpNeeded > player.experienceLevel) {
            this.enableAura();
            this.setDelay(this.delaySetting.getValue());
        } else {
            this.disableAura();
            this.goTo(Step.NEXT);
        }
    }

    // ------------------------------------------------------------------
    // 平台识别（上游 X_#onActivate）
    // ------------------------------------------------------------------

    private void initialize() {
        if (this.isWorldUnavailable()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        this.initialized = true;

        String newName = this.newNameSetting.getValue();
        if (this.renameSetting.getValue() && (newName == null || newName.isBlank())) {
            this.print("addons.lotus.messages.renameNameRequired");
            this.toggle();
            return;
        }

        if (!this.renameOnlySetting.getValue()) {
            this.initializeEnchantMode(player);
        } else {
            this.initializeRenameMode(player);
        }
    }

    private void initializeEnchantMode(LocalPlayer player) {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }

        this.bookContainers.clear();
        this.bookQueue.clear();
        this.inputContainerPos = null;
        this.grindstonePos = null;
        this.anvilPos = null;
        this.outputContainerPos = null;

        // 红石 = 输入位置，红石块 = 输出位置。
        AABB searchBox = player.getBoundingBox().inflate(6.0);
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, searchBox, frame -> {
            Item frameItem = frame.getItem().getItem();
            return frameItem == Items.REDSTONE || frameItem == Items.REDSTONE_BLOCK;
        })) {
            ItemStack stack = frame.getItem();
            if (stack.isEmpty()) {
                continue;
            }
            if (stack.getItem() == Items.REDSTONE) {
                this.containerForFrame(frame).ifPresent(pos -> this.inputContainerPos = pos);
            } else if (stack.getItem() == Items.REDSTONE_BLOCK) {
                this.containerForFrame(frame).ifPresent(pos -> this.outputContainerPos = pos);
            }
        }

        // 半径 5×3（X/Z 各 ±5，Y 各 ±3）内的铁砧与砂轮，后找到的覆盖前一个（上游同样如此）。
        for (BlockPos pos : boxPositions(5, 3, player.blockPosition())) {
            Block block = level.getBlockState(pos).getBlock();
            if (isAnvil(block)) {
                this.anvilPos = pos;
            } else if (block == Blocks.GRINDSTONE) {
                this.grindstonePos = pos;
            }
        }

        if (this.inputContainerPos == null) {
            this.print("addons.lotus.messages.noInputPosition");
            this.toggle();
            return;
        }
        if (this.outputContainerPos == null) {
            this.print("addons.lotus.messages.noOutputPosition");
            this.toggle();
            return;
        }
        if (this.anvilPos == null) {
            this.print("addons.lotus.messages.noAnvil");
            this.toggle();
            return;
        }
        if (this.grindstonePos == null) {
            this.print("addons.lotus.messages.noGrindstone");
            this.toggle();
            return;
        }

        this.scanBookContainers(player, 6);
        if (this.bookContainers.isEmpty()) {
            this.print("addons.lotus.messages.noEnchantedBooks");
            this.toggle();
            return;
        }

        this.scheduleStart(Step.NEXT);
    }

    private void initializeRenameMode(LocalPlayer player) {
        if (!this.renameSetting.getValue()) {
            this.print("addons.lotus.messages.renameOptionRequired");
            this.toggle();
            return;
        }

        Inventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItem(inventory.getSelectedSlot());
        if (mainHand.isEmpty()) {
            this.print("addons.lotus.messages.holdItemToRename");
            this.toggle();
            return;
        }

        for (BlockPos pos : boxPositions(4, 2, player.blockPosition())) {
            if (isAnvil(this.blockAt(pos).getBlock())) {
                this.anvilPos = pos;
                this.renameItem = mainHand.getItem();
                this.print("addons.lotus.messages.renameStarted", this.renameItem.getName().getString());
                this.scheduleStart(Step.NEXT);
                return;
            }
        }

        this.print("addons.lotus.messages.noAnvilNearby");
        this.toggle();
    }

    /** 上游 aw#d_be：延时若干秒后进入指定状态（上游用定时线程，这里用 tick 计时）。*/
    private void scheduleStart(Step target) {
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

    /**
     * 上游 {@code br.a(int)}：扫描物品展示框里的附魔书，找到书正下方 2 格的潜影盒。
     *
     * <p>与上游一样会输出「没有符合条件的盒子 / 成功识别 / 存在多个卸货点」。
     */
    private void scanBookContainers(LocalPlayer player, int range) {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }

        BlockPos center = player.blockPosition();
        AABB area = new AABB(center.getX() - range, center.getY(), center.getZ() - range,
                center.getX() + range, center.getY() + 3, center.getZ() + range);

        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, area,
                frame -> !frame.getItem().isEmpty())) {
            ItemStack stack = frame.getItem();
            BlockPos shulkerPos = frame.blockPosition().below(2);
            if (!isShulkerBoxItem(this.blockAt(shulkerPos).getBlock().asItem())) {
                continue;
            }

            // TODO(port): 上游的键是完整的「物品 + 附魔」快照，所以非附魔书也会进表（键为 null）。
            //  本模块只用到附魔键，这里只收满级附魔书。
            ResourceKey<Enchantment> enchantment = maxLevelEnchantment(stack);
            if (enchantment == null) {
                continue;
            }

            BlockPos existing = this.bookContainers.get(enchantment);
            if (existing == null) {
                this.bookContainers.put(enchantment, shulkerPos);
            } else {
                this.print("addons.lotus.messages.multipleUnloadPoints", this.enchantmentName(enchantment));
                if (shulkerPos.getCenter().distanceTo(player.position())
                        < existing.getCenter().distanceTo(player.position())) {
                    this.bookContainers.put(enchantment, shulkerPos);
                }
            }
        }

        if (this.bookContainers.isEmpty()) {
            this.print("addons.lotus.messages.noMatchingBoxes");
            return;
        }
        StringBuilder names = new StringBuilder();
        for (ResourceKey<Enchantment> enchantment : this.bookContainers.keySet()) {
            names.append(',').append(this.enchantmentName(enchantment));
        }
        this.print("addons.lotus.messages.identifiedContainers", names.substring(1));
    }

    /**
     * 上游 {@code X_#a_ItemFrame}：展示框背后的双人箱（取另一半，避免点到展示框），
     * 否则取展示框下方 2 格的潜影盒。
     */
    private Optional<BlockPos> containerForFrame(ItemFrame frame) {
        Optional<BlockPos> chest = doubleChestBehind(frame);
        if (chest.isPresent()) {
            return chest;
        }
        BlockPos below = frame.blockPosition().below(2);
        return isShulkerBoxItem(this.blockAt(below).getBlock().asItem())
                ? Optional.of(below)
                : Optional.empty();
    }

    /** 上游 {@code br.a_ItemFrame}：背后是双人箱时返回另一半的位置。*/
    private Optional<BlockPos> doubleChestBehind(ItemFrame frame) {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return Optional.empty();
        }

        BlockPos behind = frame.getPos().relative(frame.getDirection().getOpposite());
        BlockState state = level.getBlockState(behind);
        if (state.getBlock() != Blocks.CHEST) {
            return Optional.empty();
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return Optional.empty();
        }
        if (!(level.getBlockEntity(behind) instanceof ChestBlockEntity)) {
            return Optional.empty();
        }

        BlockPos other = behind.relative(ChestBlock.getConnectedDirection(state));
        BlockState otherState = level.getBlockState(other);
        if (otherState.getBlock() != Blocks.CHEST) {
            return Optional.empty();
        }
        if (otherState.getValue(ChestBlock.TYPE).getOpposite() != type) {
            return Optional.empty();
        }
        if (!state.getValue(ChestBlock.FACING).equals(otherState.getValue(ChestBlock.FACING))) {
            return Optional.empty();
        }
        return Optional.of(other);
    }

    // ------------------------------------------------------------------
    // 容器交互（上游 ag#a_BlockPos_Consumer / a_BlockPos_Direction_Consumer）
    // ------------------------------------------------------------------

    /**
     * 打开容器并在「同一个容器仍然开着」的后续 tick 里执行 {@code action}。
     *
     * <p>上游的「打开没有反应...」分支依赖一个永远等于初始值的字段，属于死代码，没有搬过来。
     */
    private void withContainer(BlockPos pos, Consumer<AbstractContainerMenu> action) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        if (pos == null) {
            this.print("addons.lotus.messages.wrongContainer");
            this.step = Step.NONE;
            this.previousStep = Step.NONE;
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu || !pos.equals(this.lastOpenedPos)) {
            this.openContainerAt(pos);
            this.setDelay(this.delaySetting.getValue());
            return;
        }

        if (!(menu instanceof ChestMenu) && !(menu instanceof ShulkerBoxMenu)) {
            this.print("addons.lotus.messages.wrongContainer");
            this.closeContainer();
            this.setDelay(this.delaySetting.getValue());
            return;
        }

        if (menu.getStateId() <= 0) {
            if (System.currentTimeMillis() - this.lastOpenAt > CONTAINER_SYNC_TIMEOUT_MS) {
                this.print("addons.lotus.messages.containerNotSynced");
                this.lastOpenedPos = null;
                this.closeContainer();
            }
            this.setDelay(this.delaySetting.getValue());
            return;
        }

        action.accept(menu);
    }

    /** 上游 ag#c()：右键方块打开容器（不在 9 格内时交给 Baritone 走位）。*/
    private void openContainerAt(BlockPos pos) {
        if (pos == null) {
            return;
        }
        if (this.evernightMinecraft.screen != null) {
            ContainerInteractionUtils.closeScreen();
            this.lastOpenedPos = null;
            return;
        }
        ContainerInteractionUtils.openContainer(pos, true);
        this.lastOpenedPos = pos.immutable();
        this.lastOpenAt = System.currentTimeMillis();
    }

    /** 上游 bp#a()：只关容器界面，玩家背包界面不动。*/
    private void closeContainer() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || player.containerMenu instanceof InventoryMenu) {
            return;
        }
        ContainerInteractionUtils.closeScreen();
    }

    /** 上游 InvUtils.shiftClick().slot(index)：背包下标 → 当前菜单槽位后 shift 点击。*/
    private void shiftClickInventory(int inventoryIndex) {
        if (inventoryIndex < 0) {
            return;
        }
        InventoryUtils.clickSlot(this.inventorySlotToMenuSlot(inventoryIndex), 0, ClickType.QUICK_MOVE);
    }

    /** 上游 InvUtils.shiftClick().slotId(id)：直接对菜单槽位 shift 点击。*/
    private void shiftClickSlotId(int slotId) {
        InventoryUtils.clickSlot(slotId, 0, ClickType.QUICK_MOVE);
    }

    /**
     * 背包下标 → 当前容器菜单的槽位号。
     *
     * <p>{@code InventoryUtils.toMenuSlot} 只对玩家背包菜单成立；容器打开时玩家背包是菜单的最后 36 格
     * （快捷栏再后 9 格），所以这里按菜单槽位总数推算。
     */
    private int inventorySlotToMenuSlot(int inventorySlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
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

    // ------------------------------------------------------------------
    // 搜索（上游 ag#a_Function / b_Function、bp#a_ResourceKey_toInt / a_Set_toInt）
    // ------------------------------------------------------------------

    /** 上游 ag#a_Predicate：从背包游标开始遍历 36 格，返回匹配物品的副本。*/
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
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * 上游 ag#b_Predicate：和 {@link #findInventoryStack(Predicate)} 一样，但跳过「被锁定」的栏位。
     *
     * <p>TODO(port): 上游跳过的是「Lotus 助手」模块里锁定的物品栏（默认 0,1,2,8）。
     * 该助手模块没有移植，所以这里没有可跳过的栏位，行为等于普通背包搜索。
     */
    private ItemStack findEquipmentStack(Predicate<ItemStack> predicate) {
        return this.findInventoryStack(predicate);
    }

    /** 上游 ag#d_Predicate：从容器游标开始遍历容器槽位，返回匹配物品的副本。*/
    private ItemStack findContainerStack(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return ItemStack.EMPTY;
        }
        AbstractContainerMenu menu = player.containerMenu;
        int slotCount = this.containerSlotCount(menu);
        for (int index = 0; index < slotCount; index++) {
            this.containerCursor = (this.containerCursor + 1) % slotCount;
            ItemStack stack = menu.getSlot(this.containerCursor).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                return stack.copy();
            }
        }
        return ItemStack.EMPTY;
    }

    /** 上游 bp#g()：菜单里属于容器（不含玩家背包）的槽位数。*/
    private int containerSlotCount(AbstractContainerMenu menu) {
        int count = menu instanceof InventoryMenu
                ? menu.slots.size() - INVENTORY_SLOT_COUNT - 1
                : menu.slots.size() - INVENTORY_SLOT_COUNT;
        return Math.max(0, count);
    }

    /** 上游 ag#a(int)：按背包下标取物品（与容器是否打开无关）。*/
    private ItemStack inventoryItem(int slot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || slot < 0) {
            return ItemStack.EMPTY;
        }
        return player.getInventory().getItem(slot);
    }

    /** 上游 X_#f(int)：记录铁砧输入槽与输入物品快照。*/
    private void setInputSlot(int slot) {
        this.inputSlot = slot;
        this.inputStack = this.inventoryItem(slot);
    }

    /** 上游 X_#g(int)。*/
    private void setAdditionalSlot(int slot) {
        this.additionalSlot = slot;
    }

    private BlockState blockAt(BlockPos pos) {
        Level level = this.evernightMinecraft.level;
        if (level == null || pos == null) {
            return Blocks.AIR.defaultBlockState();
        }
        return level.getBlockState(pos);
    }

    // ------------------------------------------------------------------
    // 铁砧成本模拟（上游 a_ItemStack_ItemStack_String）
    // ------------------------------------------------------------------

    private int simulatedAnvilCost(ItemStack input, ItemStack additional, String name) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return 0;
        }
        if (this.simulatedAnvil == null) {
            this.simulatedAnvil = new AnvilMenu(-1, player.getInventory());
        }
        this.simulatedAnvil.getSlot(AnvilMenu.INPUT_SLOT).setByPlayer(input);
        if (!additional.isEmpty()) {
            this.simulatedAnvil.getSlot(AnvilMenu.ADDITIONAL_SLOT).setByPlayer(additional);
        }
        if (name != null) {
            this.simulatedAnvil.setItemName(name);
        }
        this.simulatedAnvil.createResult();
        return this.simulatedAnvil.getCost();
    }

    // ------------------------------------------------------------------
    // 附魔 / 铁砧合并树（上游 bm + bE_/bG_/bF_/bH_）
    // ------------------------------------------------------------------

    private void applyPreset() {
        Set<String> ids = new LinkedHashSet<>();
        for (ResourceKey<Enchantment> enchantment : this.presetSetting.getValue().enchantments()) {
            ids.add(enchantment.identifier().toString());
        }
        if (!ids.equals(this.enchantmentsSetting.getValue())) {
            this.enchantmentsSetting.setValue(ids);
        }
    }

    /** 把附魔设置里的字符串 id 解析成附魔键。*/
    private Set<ResourceKey<Enchantment>> requiredEnchantments() {
        Set<String> ids = this.enchantmentsSetting.getValue();
        if (ids.isEmpty()) {
            return Set.of();
        }
        Set<ResourceKey<Enchantment>> enchantments = new HashSet<>();
        for (String id : ids) {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier != null) {
                enchantments.add(ResourceKey.create(Registries.ENCHANTMENT, identifier));
            }
        }
        return enchantments;
    }

    /** 上游 bm#a_ItemStack_toSet / a(ItemStack, boolean)：物品上的附魔键。*/
    private static Set<ResourceKey<Enchantment>> enchantmentKeys(ItemStack stack, boolean maxLevelOnly) {
        if (stack.isEmpty()) {
            return Set.of();
        }
        ItemEnchantments enchantments = EnchantmentHelper.getEnchantmentsForCrafting(stack);
        if (enchantments.isEmpty()) {
            return Set.of();
        }
        Set<ResourceKey<Enchantment>> keys = new HashSet<>(enchantments.size());
        for (var entry : enchantments.entrySet()) {
            Optional<ResourceKey<Enchantment>> key = entry.getKey().unwrapKey();
            if (key.isEmpty()) {
                continue;
            }
            if (maxLevelOnly && entry.getIntValue() < entry.getKey().value().getMaxLevel()) {
                continue;
            }
            keys.add(key.get());
        }
        return keys;
    }

    /** 上游 bm#b_ItemStack_toResourceKey：附魔书里那个满级的附魔。*/
    private static ResourceKey<Enchantment> maxLevelEnchantment(ItemStack stack) {
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            Optional<ResourceKey<Enchantment>> key = entry.getKey().unwrapKey();
            if (key.isPresent() && entry.getIntValue() >= entry.getKey().value().getMaxLevel()) {
                return key.get();
            }
        }
        return null;
    }

    private Enchantment enchantmentValue(ResourceKey<Enchantment> key) {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return null;
        }
        return level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getValue(key);
    }

    /** 上游 bm#c_ResourceKey：一本书在合并树里的「价值」。*/
    private int enchantmentWeight(ResourceKey<Enchantment> key) {
        Enchantment enchantment = this.enchantmentValue(key);
        if (enchantment == null) {
            return 0;
        }
        return Math.max(1, enchantment.getAnvilCost() / 2) * enchantment.getMaxLevel();
    }

    /** 上游 Names.get：附魔名（用原版语言表，随当前语言显示）。*/
    private String enchantmentName(ResourceKey<Enchantment> key) {
        Enchantment enchantment = this.enchantmentValue(key);
        return enchantment == null ? key.identifier().toString() : enchantment.description().getString();
    }

    /** 上游 bp#a_Set_toInt：背包里有没有一本「包含这一组附魔」的附魔书。*/
    private int findBookSlot(Set<ResourceKey<Enchantment>> enchantments) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < INVENTORY_SLOT_COUNT; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() == Items.ENCHANTED_BOOK
                    && enchantmentKeys(stack, false).containsAll(enchantments)) {
                return slot;
            }
        }
        return -1;
    }

    /** 上游 bp#a_ResourceKey_toInt：背包里指定附魔的附魔书。*/
    private int findBookSlot(ResourceKey<Enchantment> enchantment) {
        return this.findBookSlot(Set.of(enchantment));
    }

    /** 上游 bm#a_ItemStack_Set：先建树，再沿左子树下到「左边是装备」的那个节点。*/
    private EnchantNode buildAnvilTask(ItemStack equipment, Set<ResourceKey<Enchantment>> missing) {
        EnchantNode root = new EquipmentNode(equipment.getOrDefault(DataComponents.REPAIR_COST, 0));
        Set<EnchantNode> books = new HashSet<>();
        for (ResourceKey<Enchantment> enchantment : missing) {
            books.add(new BookNode(enchantment, this.enchantmentWeight(enchantment)));
        }

        EnchantNode tree = combineNodes(root, books, false);
        while (tree instanceof CombinedNode combined) {
            if (combined.left() instanceof EquipmentNode) {
                return combined;
            }
            tree = combined.left();
        }
        throw new IllegalStateException("unexpected anvil task");
    }

    /**
     * 上游 bm#a(bE_, Set, boolean)：把若干本书按「价值」两两合并成一棵树。
     *
     * <p>算法原样搬运：先按重量降序排，同层两两配对往上抬，抬到抬不动再顺序串起来。
     */
    private static EnchantNode combineNodes(EnchantNode root, Set<EnchantNode> nodes, boolean firstOnly) {
        if (nodes.isEmpty()) {
            return null;
        }

        LinkedList<EnchantNode> sorted = new LinkedList<>(nodes);
        sorted.sort(null);
        Map<Integer, List<EnchantNode>> layers = new HashMap<>();
        layers.put(0, sorted);
        int remaining = sorted.size();
        EnchantNode current = root;

        while (remaining > 0) {
            int currentLevel = current.level();
            List<EnchantNode> layer = layers.computeIfAbsent(currentLevel, key -> new LinkedList<>());
            if (!layer.isEmpty()) {
                EnchantNode next = layer.removeFirst();
                current = new CombinedNode(current, next);
                remaining--;
                if (firstOnly) {
                    return current;
                }
                continue;
            }

            int merged = 0;
            for (int level = 0; level < currentLevel; level++) {
                List<EnchantNode> source = layers.computeIfAbsent(level, key -> new LinkedList<>());
                List<EnchantNode> target = layers.computeIfAbsent(currentLevel, key -> new LinkedList<>());
                while (source.size() >= 2) {
                    EnchantNode left = source.removeFirst();
                    EnchantNode right = source.removeLast();
                    target.add(new CombinedNode(left, right));
                    merged++;
                }
                target.sort(null);
            }
            if (merged <= 0) {
                break;
            }
            remaining -= merged;
        }

        EnchantNode pending = null;
        for (int level = 0; remaining > 1 && level < 10; level++) {
            List<EnchantNode> layer = layers.get(level);
            if (layer == null) {
                continue;
            }
            while (!layer.isEmpty()) {
                if (pending == null) {
                    pending = layer.removeLast();
                } else {
                    EnchantNode node = layer.removeFirst();
                    pending = new CombinedNode(node, pending);
                    remaining--;
                }
            }
        }

        if (remaining > 0) {
            if (pending == null) {
                for (List<EnchantNode> layer : layers.values()) {
                    if (!layer.isEmpty()) {
                        pending = layer.removeFirst();
                        break;
                    }
                }
                if (pending == null) {
                    throw new IllegalStateException("unexpected book tree state");
                }
            }
            current = new CombinedNode(current, pending);
        }
        return current;
    }

    /**
     * 上游 X_#a_bE(bE_)：按合并树逐步执行。
     *
     * @return {@link #RESULT_USE} 表示可以开铁砧了；{@link #RESULT_NEED_BOOK} 表示缺书；
     *         非负数是背包里那本书的槽位下标
     */
    private int resolveAnvilStep(EnchantNode node) {
        if (node instanceof CombinedNode combined) {
            if (combined.left() instanceof EquipmentNode) {
                int result = this.resolveAnvilStep(combined.right());
                if (result >= 0) {
                    this.setInputSlot(this.inventoryCursor);
                    this.setAdditionalSlot(result);
                    this.step = Step.USE;
                    return RESULT_USE;
                }
                if (result == RESULT_NEED_BOOK) {
                    this.step = Step.TAKE_BOOK;
                    return RESULT_NEED_BOOK;
                }
                if (result == RESULT_USE) {
                    return RESULT_USE;
                }
                this.print("addons.lotus.messages.unexpectedEquipState", String.valueOf(result));
                return result;
            }

            int bookSlot = this.findBookSlot(combined.enchantments());
            if (bookSlot >= 0) {
                return bookSlot;
            }

            int left = this.resolveAnvilStep(combined.left());
            if (left == RESULT_USE) {
                return RESULT_USE;
            }
            int right = this.resolveAnvilStep(combined.right());
            if (right == RESULT_USE) {
                return RESULT_USE;
            }
            if (left == RESULT_NEED_BOOK || right == RESULT_NEED_BOOK) {
                this.step = Step.TAKE_BOOK;
                return RESULT_NEED_BOOK;
            }
            if (left < 0) {
                this.print("addons.lotus.messages.unexpectedBookLeft", String.valueOf(left));
                return left;
            }
            if (right < 0) {
                this.print("addons.lotus.messages.unexpectedBookRight", String.valueOf(right));
                return right;
            }
            this.setInputSlot(left);
            this.setAdditionalSlot(right);
            this.step = Step.USE;
            return RESULT_USE;
        }

        if (node instanceof BookNode book) {
            int slot = this.findBookSlot(book.enchantment());
            if (slot >= 0) {
                return slot;
            }
            this.bookQueue.add(book.enchantment());
            return RESULT_NEED_BOOK;
        }

        throw new IllegalStateException("unexpected anvil node");
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private void setDelay(int ticks) {
        this.delayTicks = ticks;
    }

    /** 上游 aw#a_be / b_be：先关界面，再进入目标状态。*/
    private void closeAndGoTo(Step target) {
        this.previousStep = target;
        this.step = Step.CLOSE_SCREEN;
        this.setDelay(this.delaySetting.getValue());
    }

    /** 上游 aw#c_be：直接切状态（不关界面）。*/
    private void goTo(Step target) {
        this.step = target;
        this.setDelay(this.delaySetting.getValue());
    }

    private boolean hasTargetName(ItemStack stack) {
        Component customName = stack.getCustomName();
        return customName != null && Objects.equals(this.newNameSetting.getValue(), customName.getString());
    }

    private static boolean isAnvil(Block block) {
        return block == Blocks.ANVIL || block == Blocks.CHIPPED_ANVIL || block == Blocks.DAMAGED_ANVIL;
    }

    /** 上游 bq#a_Item_toBoolean：潜影盒方块对应的物品。*/
    private static boolean isShulkerBoxItem(Item item) {
        return item == Items.SHULKER_BOX
                || item == Items.WHITE_SHULKER_BOX
                || item == Items.ORANGE_SHULKER_BOX
                || item == Items.MAGENTA_SHULKER_BOX
                || item == Items.LIGHT_BLUE_SHULKER_BOX
                || item == Items.YELLOW_SHULKER_BOX
                || item == Items.LIME_SHULKER_BOX
                || item == Items.PINK_SHULKER_BOX
                || item == Items.GRAY_SHULKER_BOX
                || item == Items.LIGHT_GRAY_SHULKER_BOX
                || item == Items.CYAN_SHULKER_BOX
                || item == Items.PURPLE_SHULKER_BOX
                || item == Items.BLUE_SHULKER_BOX
                || item == Items.BROWN_SHULKER_BOX
                || item == Items.GREEN_SHULKER_BOX
                || item == Items.RED_SHULKER_BOX
                || item == Items.BLACK_SHULKER_BOX;
    }

    /** 上游 bn#a(int, int, BlockPos)：以玩家为中心的长方体（X/Z 半径与 Y 半径，上界不含）。*/
    private static List<BlockPos> boxPositions(int radiusXZ, int radiusY, BlockPos center) {
        List<BlockPos> positions = new ArrayList<>();
        for (int x = center.getX() - radiusXZ; x < center.getX() + radiusXZ; x++) {
            for (int z = center.getZ() - radiusXZ; z < center.getZ() + radiusXZ; z++) {
                for (int y = center.getY() - radiusY; y < center.getY() + radiusY; y++) {
                    positions.add(new BlockPos(x, y, z));
                }
            }
        }
        return positions;
    }

    /**
     * Port of {@code bu#d()}：上游开 KillAura 前会把 {@code autoSwitch} 打开、{@code swapBack} 关掉，
     * Evernight 的 {@link AuraModule} 对应「武器模式 = SWAP」。
     */
    private void enableAura() {
        AuraModule aura = ClientContext.getModule(AuraModule.class);
        if (aura == null || aura.isToggled()) {
            return;
        }
        if (this.previousAuraWeaponMode == null) {
            this.previousAuraWeaponMode = aura.getWeaponMode();
        }
        aura.applySwapWeaponMode();
        aura.enable();
        this.print("addons.lotus.messages.auraEnabled");
    }

    /** Port of {@code bu#e()}：关掉 Aura。*/
    private void disableAura() {
        AuraModule aura = ClientContext.getModule(AuraModule.class);
        if (aura == null || !aura.isToggled()) {
            return;
        }
        aura.disable();
        this.print("addons.lotus.messages.auraDisabled");
    }

    /** 所有玩家可见文本都从语言表取，并统一挂模块签名。*/
    private void print(String translationKey, Object... args) {
        String pattern = Translations.get(translationKey);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    // ------------------------------------------------------------------
    // 状态机 / 铁砧合并树的嵌套类型
    // ------------------------------------------------------------------

    /** 上游 bf 里本模块用到的状态（名字沿用上游的语义）。*/
    private enum Step {
        /** 空闲。*/
        NONE,
        /** 先关界面，再去 {@link #previousStep}。*/
        CLOSE_SCREEN,
        /** 决定下一步。*/
        NEXT,
        /** 去输入箱拿装备。*/
        TAKE_ITEM,
        /** 把成品卸进输出箱。*/
        PUT_ITEM,
        /** 砂轮祛魔。*/
        GRIND,
        /** 去书箱取附魔书。*/
        TAKE_BOOK,
        /** 放铁砧。*/
        PLACE,
        /** 开铁砧合并 / 改名。*/
        USE,
        /** 补经验。*/
        LEVEL
    }

    /** 经验来源：对应上游 {@code aE_}。*/
    public enum XpSource implements NamedEntry {
        KillAura("KillAura"),
        XpBottle("XpBottle");

        private final String name;

        XpSource(String name) {
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

    /**
     * 要附魔的物品（对应上游 {@code ItemSetting} 的白名单，单选）。
     *
     * <p>显示名直接用原版物品名（随当前语言），所以不需要额外的语言键；{@link #getName()} 是配置里
     * 的稳定标识。
     */
    public enum TargetItem implements NamedEntry {
        DiamondPickaxe("DiamondPickaxe", Items.DIAMOND_PICKAXE),
        DiamondAxe("DiamondAxe", Items.DIAMOND_AXE),
        DiamondShovel("DiamondShovel", Items.DIAMOND_SHOVEL),
        DiamondHoe("DiamondHoe", Items.DIAMOND_HOE),
        Shears("Shears", Items.SHEARS),
        FlintAndSteel("FlintAndSteel", Items.FLINT_AND_STEEL),
        DiamondSword("DiamondSword", Items.DIAMOND_SWORD),
        DiamondSpear("DiamondSpear", Items.DIAMOND_SPEAR),
        Bow("Bow", Items.BOW),
        Crossbow("Crossbow", Items.CROSSBOW),
        Trident("Trident", Items.TRIDENT),
        Mace("Mace", Items.MACE),
        DiamondHelmet("DiamondHelmet", Items.DIAMOND_HELMET),
        TurtleHelmet("TurtleHelmet", Items.TURTLE_HELMET),
        DiamondChestplate("DiamondChestplate", Items.DIAMOND_CHESTPLATE),
        Elytra("Elytra", Items.ELYTRA),
        DiamondLeggings("DiamondLeggings", Items.DIAMOND_LEGGINGS),
        DiamondBoots("DiamondBoots", Items.DIAMOND_BOOTS),
        FishingRod("FishingRod", Items.FISHING_ROD),
        Book("Book", Items.BOOK),
        EnchantedBook("EnchantedBook", Items.ENCHANTED_BOOK);

        private final String name;
        private final Item item;

        TargetItem(String name, Item item) {
            this.name = name;
            this.item = item;
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String getDisplayName() {
            return this.item.getName().getString();
        }

        public Item item() {
            return this.item;
        }
    }

    /** 附魔预设：对应上游 {@code aD_}，选择后自动覆盖「附魔」设置。*/
    public enum EnchantPreset implements NamedEntry {
        Custom("Custom", Set.of()),
        PreciseTools("PreciseTools", Set.of(
                Enchantments.EFFICIENCY, Enchantments.UNBREAKING, Enchantments.MENDING,
                Enchantments.SILK_TOUCH)),
        FortuneTools("FortuneTools", Set.of(
                Enchantments.FORTUNE, Enchantments.UNBREAKING, Enchantments.MENDING,
                Enchantments.EFFICIENCY)),
        RedstoneSword("RedstoneSword", Set.of(
                Enchantments.SWEEPING_EDGE, Enchantments.LOOTING, Enchantments.UNBREAKING,
                Enchantments.SHARPNESS, Enchantments.MENDING)),
        KnockbackSword("KnockbackSword", Set.of(
                Enchantments.SWEEPING_EDGE, Enchantments.LOOTING, Enchantments.SHARPNESS,
                Enchantments.FIRE_ASPECT, Enchantments.UNBREAKING, Enchantments.KNOCKBACK,
                Enchantments.MENDING)),
        Helmet("Helmet", Set.of(
                Enchantments.RESPIRATION, Enchantments.PROTECTION, Enchantments.AQUA_AFFINITY,
                Enchantments.UNBREAKING, Enchantments.MENDING)),
        Chestplate("Chestplate", Set.of(
                Enchantments.PROTECTION, Enchantments.UNBREAKING, Enchantments.MENDING)),
        ThornChestplate("ThornChestplate", Set.of(
                Enchantments.THORNS, Enchantments.UNBREAKING, Enchantments.MENDING,
                Enchantments.PROTECTION)),
        Leggings("Leggings", Set.of(
                Enchantments.PROTECTION, Enchantments.UNBREAKING, Enchantments.MENDING)),
        BlastLeggings("BlastLeggings", Set.of(
                Enchantments.BLAST_PROTECTION, Enchantments.UNBREAKING, Enchantments.MENDING)),
        Boots("Boots", Set.of(
                Enchantments.DEPTH_STRIDER, Enchantments.PROTECTION, Enchantments.UNBREAKING,
                Enchantments.FEATHER_FALLING, Enchantments.MENDING)),
        BlastBoots("BlastBoots", Set.of(
                Enchantments.BLAST_PROTECTION, Enchantments.DEPTH_STRIDER, Enchantments.UNBREAKING,
                Enchantments.FEATHER_FALLING, Enchantments.MENDING)),
        Elytra("Elytra", Set.of(
                Enchantments.UNBREAKING, Enchantments.MENDING)),
        Trident("Trident", Set.of(
                Enchantments.IMPALING, Enchantments.CHANNELING, Enchantments.LOYALTY,
                Enchantments.UNBREAKING, Enchantments.MENDING)),
        InfiniteBow("InfiniteBow", Set.of(
                Enchantments.POWER, Enchantments.INFINITY, Enchantments.UNBREAKING)),
        Spear("Spear", Set.of(
                Enchantments.LUNGE, Enchantments.LOOTING, Enchantments.SHARPNESS,
                Enchantments.FIRE_ASPECT, Enchantments.UNBREAKING, Enchantments.KNOCKBACK,
                Enchantments.MENDING));

        private final String name;
        private final Set<ResourceKey<Enchantment>> enchantments;

        EnchantPreset(String name, Set<ResourceKey<Enchantment>> enchantments) {
            this.name = name;
            this.enchantments = Collections.unmodifiableSet(enchantments);
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String getDisplayName() {
            return Translations.get("addons.lotus.names.values." + this.name);
        }

        public Set<ResourceKey<Enchantment>> enchantments() {
            return this.enchantments;
        }
    }

    /** 上游 bE_：铁砧合并树的节点。*/
    private interface EnchantNode extends Comparable<EnchantNode> {
        /** 上游 a()：合并层数（装备节点是修复成本）。*/
        int level();

        /** 上游 b()：合并价值。*/
        int weight();

        /** 上游 c()：额外的累积成本。*/
        default int cost() {
            return 0;
        }

        @Override
        default int compareTo(EnchantNode other) {
            return Integer.compare(other.weight(), this.weight());
        }
    }

    /** 上游 bG_：装备节点。*/
    private record EquipmentNode(int level) implements EnchantNode {
        @Override
        public int weight() {
            return 0;
        }
    }

    /** 上游 bF_：一本附魔书。*/
    private record BookNode(ResourceKey<Enchantment> enchantment, int weight) implements EnchantNode {
        @Override
        public int level() {
            return 0;
        }
    }

    /** 上游 bH_：两个节点合并。*/
    private static final class CombinedNode implements EnchantNode {
        private final EnchantNode left;
        private final EnchantNode right;
        private final int level;
        private final int weight;
        private int cost = -1;
        private Set<ResourceKey<Enchantment>> enchantments;

        private CombinedNode(EnchantNode left, EnchantNode right) {
            this.left = left;
            this.right = right;
            this.level = Math.max(left.level(), right.level()) + 1;
            this.weight = left.weight() + right.weight();
        }

        private EnchantNode left() {
            return this.left;
        }

        private EnchantNode right() {
            return this.right;
        }

        @Override
        public int level() {
            return this.level;
        }

        @Override
        public int weight() {
            return this.weight;
        }

        @Override
        public int cost() {
            if (this.cost < 0) {
                this.cost = this.left.level() + this.left.cost()
                        + this.right.level() + this.right.cost() + this.right.weight();
            }
            return this.cost;
        }

        /** 上游 bH_#f()：这棵子树里所有的附魔。*/
        private Set<ResourceKey<Enchantment>> enchantments() {
            if (this.enchantments == null) {
                Set<ResourceKey<Enchantment>> keys = new HashSet<>();
                if (this.left instanceof CombinedNode combined) {
                    keys.addAll(combined.enchantments());
                } else if (this.left instanceof BookNode book) {
                    keys.add(book.enchantment());
                }
                if (this.right instanceof CombinedNode combined) {
                    keys.addAll(combined.enchantments());
                } else if (this.right instanceof BookNode book) {
                    keys.add(book.enchantment());
                }
                this.enchantments = keys;
            }
            return this.enchantments;
        }
    }
}
