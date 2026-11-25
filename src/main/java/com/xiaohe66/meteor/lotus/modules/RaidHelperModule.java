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
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.OminousBottleAmplifier;

/**
 * Port of {@code at} (A袭击助手) from Lotus (Meteor Client addon, author xiaohe66, GPL-3.0).
 *
 * <p>挂机袭击塔用：按设置的周期（或按&nbsp;袭击之兆&nbsp;剩余时间）从背包里把不详之瓶换到主手、自动喝药，
 * 喝药期间关闭杀戮光环，喝完再开回来。
 *
 * <p>移植说明：
 * <ul>
 *     <li>原模块继承 {@code ag}（Lotus 自己的模块基类），这里只把用到的那几个基类方法内联成本类私有方法：
 *     {@code h()} → {@link #isWorldUnavailable()}、{@code e()/n()} → 玩家背包、{@code a_Predicate_String}
 *     → {@link #swapBottleIntoMainHand(Predicate)}。{@code ag} 那个不可见的「延迟」设置在本模块里没有用到，
 *     没有移植。</li>
 *     <li>原模块通过 {@code bu#d}/{@code bu#e} 开关 Meteor 的 {@code KillAura}，还会用 mixin accessor 把
 *     {@code autoSwitch} 设为 {@code true}、{@code swapBack} 设为 {@code false}。Evernight 对应的是内置
 *     {@link AuraModule}：开启时套用「武器模式 = SWAP」（{@code applySwapWeaponMode()}），关闭时直接
 *     {@code disable()}。</li>
 *     <li>Meteor 的 {@code info/warning/error} 三档聊天在 Evernight 只有一个输出通道，这里全部走
 *     {@code printChat} + 模块签名，文本一律来自语言表 {@code addons.lotus.*}。</li>
 * </ul>
 */
public final class RaidHelperModule extends AddonModule {
    /** 原 {@code at} 里 {@code s} 的初值：把药换到主手大约要花的 tick 数。 */
    private static final int DEFAULT_PREP_TICKS = 2;
    /** 原 {@code at#b}：换药提前量的上限。 */
    private static final int PREP_TICKS_LIMIT = 10;
    /** 原 {@code at#d}：喝药超时。 */
    private static final int DRINK_TIMEOUT_TICKS = 52;
    /** 原 {@code at#e}：开始喝药后允许的重按右键窗口。 */
    private static final int DRINK_START_GRACE_TICKS = 8;
    /** Original inline {@code 100}: 计时日志的输出间隔。 */
    private static final int LOG_INTERVAL_TICKS = 100;
    /** 不详之瓶的等级数（I~V）。 */
    private static final int MAX_BOTTLE_LEVEL = 5;

    private final Setting<DrinkMode> modeSetting;
    private final Setting<Integer> periodTicksSetting;
    private final Setting<Integer> drinkAheadSetting;
    private final Setting<Boolean> keepOneSetting;
    private final Setting<Integer> minBottleLevelSetting;
    private final Setting<Integer> maxBottleLevelSetting;
    private final Setting<Boolean> drinkTimerSetting;

    /** 原 {@code at.a}：是否正在喝药。 */
    private boolean drinking;
    /** 原 {@code at.o}：模块启用后的 tick 计数。 */
    private int ticks;
    /** 原 {@code at.p}：本次喝药开始的 tick。 */
    private int drinkStartTick = -1;
    /** 原 {@code at.q}：下次喝药的 tick。 */
    private int nextDrinkTick = -1;
    /** 原 {@code at.r}：开始换药到主手的 tick。 */
    private int bottleSwapStartTick = -1;
    /** 原 {@code at.s}：观测到的「把药换到主手」所需 tick 数。 */
    private int prepTicks = DEFAULT_PREP_TICKS;
    /** 原 {@code at.t}：这一轮是否已经真正进入使用物品状态。 */
    private boolean useStarted;
    /** 原 {@code at.u}：开始喝药时主手那一叠的数量。 */
    private int drinkStartCount = -1;
    /** 原 {@code at.v}：是否已经提示过「没有不详之瓶」。 */
    private boolean warnedMissingBottle;
    /** 接管 Aura 之前的武器模式，模块关闭时还原。 */
    private AuraWeaponChoice previousAuraWeaponMode;

    public RaidHelperModule(ModuleCategory category) {
        super("lotus", "LotusRaidHelper",
                "For AFK raid farms: fetches an ominous bottle from the inventory on schedule, drinks it,"
                        + " then turns Aura on.",
                category);

        this.modeSetting = this.add(new EnumSetting<DrinkMode>("Mode", DrinkMode.Period));
        this.periodTicksSetting = this.add(new NumberSetting<Integer>("PeriodTicks", 610, 100, 2400,
                value -> this.modeSetting.getValue() == DrinkMode.Period));
        this.drinkAheadSetting = this.add(new NumberSetting<Integer>("DrinkAhead", 30, 0, 600,
                value -> this.modeSetting.getValue() == DrinkMode.RaidOmen));
        this.keepOneSetting = this.add(new BooleanSetting("KeepOne", true,
                value -> this.modeSetting.getValue() != DrinkMode.Always));
        this.minBottleLevelSetting = this.add(new NumberSetting<Integer>("MinBottleLevel", 2, 1, MAX_BOTTLE_LEVEL));
        this.maxBottleLevelSetting = this.add(new NumberSetting<Integer>("MaxBottleLevel", 4, 1, MAX_BOTTLE_LEVEL));
        this.drinkTimerSetting = this.add(new BooleanSetting("DrinkTimer", true));
    }

    @Override
    public void onEnable() {
        this.drinking = false;
        this.useStarted = false;
        this.ticks = 0;
        this.drinkStartTick = -1;
        this.bottleSwapStartTick = -1;
        this.drinkStartCount = -1;
        this.warnedMissingBottle = false;
        this.previousAuraWeaponMode = null;
        this.setUseKeyDown(false);

        switch (this.modeSetting.getValue()) {
            case Period -> this.nextDrinkTick = 0;
            case RaidOmen -> {
                this.nextDrinkTick = -1;
                if (!this.needsDrink()) {
                    this.enableAura();
                }
            }
            case Always -> this.nextDrinkTick = -1;
        }
    }

    @Override
    public void onDisable() {
        this.resetDrinkState();
        if (this.modeSetting.getValue() != DrinkMode.Always) {
            this.disableAura();
        }

        AuraModule aura = ClientContext.getModule(AuraModule.class);
        if (aura != null && this.previousAuraWeaponMode != null) {
            aura.setWeaponMode(this.previousAuraWeaponMode);
            this.previousAuraWeaponMode = null;
        }
    }

    /** 原 {@code at#onTick(TickEvent.Pre)}。 */
    @EventHandler(priority = -100)
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        this.ticks++;

        if (this.drinking) {
            this.tickDrinking();
            return;
        }

        switch (this.modeSetting.getValue()) {
            case Period -> this.tickPeriodMode();
            case RaidOmen -> this.tickRaidOmenMode();
            case Always -> this.tickAlwaysMode();
        }
    }

    /** 原 {@code at#a()}：周期模式。 */
    private void tickPeriodMode() {
        if (this.nextDrinkTick < 0) {
            this.nextDrinkTick = this.ticks;
        }

        int ticksLeft = this.nextDrinkTick - this.ticks;
        if (ticksLeft > this.getPrepTicks()) {
            this.logNextDrink(ticksLeft);
            return;
        }

        this.disableAura();
        if (this.ensureBottleInMainHand() && ticksLeft <= 0) {
            this.startDrinking();
        }
    }

    /** 原 {@code at#b()}：检测 Buff 模式（按袭击之兆剩余时间喝）。 */
    private void tickRaidOmenMode() {
        if (this.hasBadOmen()) {
            return;
        }

        int remaining = this.getRaidOmenTicks();
        int ahead = this.drinkAheadSetting.getValue();
        if (remaining >= ahead + this.getPrepTicks()) {
            this.logRaidOmen(remaining);
            return;
        }

        this.disableAura();
        if (this.ensureBottleInMainHand() && remaining < ahead) {
            this.startDrinking();
        }
    }

    /** 原 {@code at#c()}：一直喝。 */
    private void tickAlwaysMode() {
        this.disableAura();

        if (!this.hasBottle()) {
            if (!this.warnedMissingBottle) {
                this.warnedMissingBottle = true;
                this.print("addons.lotus.messages.noOminousBottle");
            }
            return;
        }

        this.warnedMissingBottle = false;
        if (this.ensureBottleInMainHand()) {
            this.startDrinking();
        }
    }

    /** 原 {@code at#w()}：喝药进行中。 */
    private void tickDrinking() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            this.finishDrinking();
            return;
        }

        if (this.isDrinkFinished(player)) {
            this.finishDrinking();
            return;
        }

        int elapsed = this.ticks - this.drinkStartTick;
        if (player.isUsingItem()) {
            this.useStarted = true;
            if (elapsed > DRINK_TIMEOUT_TICKS) {
                this.print("addons.lotus.messages.drinkTimeout", elapsed);
                this.finishDrinking();
            }
            return;
        }

        if (this.useStarted) {
            this.finishDrinking();
            return;
        }

        if (elapsed <= DRINK_START_GRACE_TICKS) {
            if (!this.isBottleInMainHand(player)) {
                this.ensureBottleInMainHand();
            } else {
                this.setUseKeyDown(true);
                this.rightClick(player);
            }
            return;
        }

        this.print("addons.lotus.messages.drinkNotStarted");
        this.finishDrinking();
    }

    /** 原 {@code at#x()}：开始喝药。 */
    private void startDrinking() {
        this.disableAura();

        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        ItemStack stack = player.getMainHandItem();
        if (!this.isValidBottle(stack)) {
            this.print("addons.lotus.messages.bottleNotInMainHand");
            return;
        }

        this.drinkStartTick = this.ticks;
        this.drinkStartCount = stack.getCount();
        this.useStarted = false;
        this.drinking = true;
        this.setUseKeyDown(true);
        if (!player.isUsingItem()) {
            this.rightClick(player);
        }

        this.print("addons.lotus.messages.drinkStarted");
    }

    /** 原 {@code at#y()}：喝药结束。 */
    private void finishDrinking() {
        int startTick = this.drinkStartTick;
        this.resetDrinkState();
        this.print("addons.lotus.messages.drinkFinished");

        if (this.modeSetting.getValue() == DrinkMode.Period) {
            this.nextDrinkTick = startTick + this.periodTicksSetting.getValue();
            if (this.nextDrinkTick <= this.ticks) {
                this.nextDrinkTick = this.ticks + 1;
            }
        }

        if (this.modeSetting.getValue() != DrinkMode.Always) {
            this.enableAura();
        }
    }

    /** 原 {@code at#z()}：主手那一叠变少/换物/空 → 这一瓶喝完了。 */
    private boolean isDrinkFinished(LocalPlayer player) {
        if (this.drinkStartCount < 0) {
            return false;
        }

        ItemStack stack = player.getMainHandItem();
        if (stack.isEmpty()) {
            return true;
        }
        if (stack.getItem() != Items.OMINOUS_BOTTLE) {
            return true;
        }
        return stack.getCount() < this.drinkStartCount;
    }

    /** 原 {@code at#A()}：确认主手是不详之瓶；必要时先换到主手（本 tick 返回 {@code false}）。 */
    private boolean ensureBottleInMainHand() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }

        if (this.isBottleInMainHand(player)) {
            this.bottleSwapStartTick = -1;
            return true;
        }

        if (this.bottleSwapStartTick < 0) {
            this.bottleSwapStartTick = this.ticks;
        }

        int level = this.findBestBottleLevel(player);
        if (level < 0) {
            this.print("addons.lotus.messages.missingOminousBottle");
            this.toggle();
            return false;
        }

        if (!this.swapBottleIntoMainHand(stack -> this.isValidBottle(stack) && bottleLevel(stack) == level)) {
            return false;
        }

        int swapTicks = this.ticks - this.bottleSwapStartTick;
        if (swapTicks >= 0 && swapTicks <= PREP_TICKS_LIMIT) {
            this.prepTicks = swapTicks;
        }
        this.bottleSwapStartTick = -1;
        return true;
    }

    /** 原 {@code at#B()}：换药提前量。 */
    private int getPrepTicks() {
        return Math.min(this.prepTicks + 1, PREP_TICKS_LIMIT);
    }

    /** 原 {@code at#e(int)}。 */
    private void logNextDrink(int ticksLeft) {
        if (this.drinkTimerSetting.getValue() && ticksLeft % LOG_INTERVAL_TICKS == 0) {
            this.print("addons.lotus.messages.nextDrinkIn", ticksLeft);
        }
    }

    /** 原 {@code at#f(int)}。 */
    private void logRaidOmen(int remaining) {
        if (this.drinkTimerSetting.getValue() && remaining >= 0 && remaining % LOG_INTERVAL_TICKS == 0) {
            this.print("addons.lotus.messages.raidOmenRemaining", remaining);
        }
    }

    /**
     * Port of {@code ag#a_Predicate_String}：找不到就报错并关掉模块；找到但不在主手时换到主手并返回
     * {@code false}（下一 tick 再确认），已经在主手返回 {@code true}。
     *
     * <p>TODO(port): Meteor 的 {@code InvUtils.find} 也会命中副手，这里只搜 0..35 的主背包槽位，
     * 副手里的不详之瓶不会被换出来。
     */
    private boolean swapBottleIntoMainHand(Predicate<ItemStack> predicate) {
        int slot = InventoryUtils.findInventorySlot(predicate, false);
        if (slot < 0) {
            this.print("addons.lotus.messages.missingOminousBottle");
            this.toggle();
            return false;
        }

        if (slot == InventoryUtils.getSelectedSlot()) {
            return true;
        }

        if (slot < 9) {
            InventoryUtils.selectHotbarSlot(slot);
        } else {
            InventoryUtils.swapIntoSelectedSlot(slot);
        }
        return false;
    }

    /** 原 {@code at#D()}。 */
    private boolean isBottleInMainHand(LocalPlayer player) {
        return this.isValidBottle(player.getMainHandItem());
    }

    /** 原 {@code at#E()}：背包里（按等级过滤后）还有没有能喝的药。 */
    private boolean hasBottle() {
        return this.findBestBottleLevel(this.evernightMinecraft.player) >= 0;
    }

    /** 原 {@code at#F()}。 */
    private boolean shouldKeepOne() {
        return this.keepOneSetting.getValue() && this.modeSetting.getValue() != DrinkMode.Always;
    }

    /** 原 {@code at#G()}：返回等级区间内数量最多的那一级，没有则 -1。 */
    private int findBestBottleLevel(LocalPlayer player) {
        if (player == null) {
            return -1;
        }

        Inventory inventory = player.getInventory();
        int[] counts = new int[MAX_BOTTLE_LEVEL + 1];
        int slots = inventory.getNonEquipmentItems().size();
        for (int slot = 0; slot < slots; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (this.isValidBottle(stack)) {
                counts[bottleLevel(stack)] += stack.getCount();
            }
        }

        int bestLevel = -1;
        int bestCount = 0;
        int min = Math.max(1, this.minBottleLevelSetting.getValue());
        int max = Math.min(MAX_BOTTLE_LEVEL, this.maxBottleLevelSetting.getValue());
        for (int level = min; level <= max; level++) {
            if (counts[level] > bestCount) {
                bestCount = counts[level];
                bestLevel = level;
            }
        }
        return bestLevel;
    }

    /** 原 {@code at#a_ItemStack_toInt}：不详之瓶等级（I~V），非不详之瓶返回 -1。 */
    private static int bottleLevel(ItemStack stack) {
        OminousBottleAmplifier amplifier = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
        return amplifier == null ? -1 : amplifier.value() + 1;
    }

    /** 原 {@code at#b_ItemStack_toBoolean}：等级在区间内、且满足「保留一瓶」的药。 */
    private boolean isValidBottle(ItemStack stack) {
        if (stack.isEmpty() || stack.getItem() != Items.OMINOUS_BOTTLE) {
            return false;
        }

        int level = bottleLevel(stack);
        if (level < this.minBottleLevelSetting.getValue() || level > this.maxBottleLevelSetting.getValue()) {
            return false;
        }

        return !this.shouldKeepOne() || stack.getCount() > 1;
    }

    /** 原 {@code at#H()}。 */
    private void resetDrinkState() {
        this.setUseKeyDown(false);
        this.drinking = false;
        this.useStarted = false;
        this.drinkStartCount = -1;
    }

    /** 原 {@code at#a(boolean)}。 */
    private void setUseKeyDown(boolean down) {
        this.evernightMinecraft.options.keyUse.setDown(down);
    }

    /**
     * 对应 Meteor 的 {@code Utils.rightClick()}：Meteor 只是打一个标记，让 {@code Minecraft#startUseItem}
     * 在 tick 末尾走原版流程。这里直接调用 {@code MultiPlayerGameMode#useItem}（对空右键那一支），喝药够用。
     *
     * <p>TODO(port): 原版 {@code startUseItem} 还会做方块/实体的射线交互、4 tick 的右键冷却与挥臂，
     * 这里没有复刻；喝药本身不依赖这些。
     */
    private void rightClick(LocalPlayer player) {
        if (player.isUsingItem() || this.evernightMinecraft.screen != null
                || this.evernightMinecraft.gameMode == null) {
            return;
        }
        this.evernightMinecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
    }

    /** 原 {@code at#I()}：检测 Buff 模式下是不是该喝了。 */
    private boolean needsDrink() {
        if (this.isWorldUnavailable()) {
            return false;
        }
        return !this.hasBadOmen() && this.getRaidOmenTicks() < this.drinkAheadSetting.getValue();
    }

    /** 原 {@code at#J()}：袭击之兆剩余 tick，没有该效果返回 -1。 */
    private int getRaidOmenTicks() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return -1;
        }

        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (effect.getEffect() == MobEffects.RAID_OMEN) {
                int duration = effect.getDuration();
                return duration < 0 ? Integer.MAX_VALUE : duration;
            }
        }
        return -1;
    }

    /** 原 {@code at#K()}。 */
    private boolean hasBadOmen() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }

        for (MobEffectInstance effect : player.getActiveEffects()) {
            if (effect.getEffect() == MobEffects.BAD_OMEN) {
                return true;
            }
        }
        return false;
    }

    /**
     * Port of {@code bu#d()}：原逻辑开 KillAura 前会把 {@code autoSwitch} 打开、{@code swapBack} 关掉，
     * Evernight 的 {@link AuraModule} 对应「武器模式 = SWAP」（自动换到最好的武器）。
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

    /** Port of {@code bu#e()}。 */
    private void disableAura() {
        AuraModule aura = ClientContext.getModule(AuraModule.class);
        if (aura == null || !aura.isToggled()) {
            return;
        }

        aura.disable();
        this.print("addons.lotus.messages.auraDisabled");
    }

    /** 所有玩家可见文本都从语言表取，并统一挂模块签名。 */
    private void print(String key, Object... args) {
        String pattern = Translations.get(key);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    /** 原 {@code au}：周期模式 / 检测 Buff 模式 / 一直喝。 */
    public enum DrinkMode implements NamedEntry {
        Period("Period"),
        RaidOmen("RaidOmen"),
        Always("Always");

        private final String name;

        DrinkMode(String name) {
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
}
