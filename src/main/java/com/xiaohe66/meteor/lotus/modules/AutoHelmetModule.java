package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import java.util.Set;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Port of {@code Y_} (L自动头盔) from Lotus (author xiaohe66).
 *
 * <p>附近有猪灵、且附近没有其他玩家时换上金头盔；猪灵离开后换回钻石/下界合金头盔。
 * 当前头盔带有「避免的附魔」（默认绑定诅咒、冰霜行者）时完全不干预。
 */
public final class AutoHelmetModule extends AddonModule {
    private final Setting<Integer> detectRangeSetting;
    private final Setting<Integer> detectTicksSetting;
    private final Setting<Integer> delaySetting;
    private final Setting<Boolean> skipEmptySetting;
    private final Setting<Set<String>> avoidEnchantmentsSetting;

    private int tickDelay;
    private long firstSeenAt = -1L;

    public AutoHelmetModule(ModuleCategory category) {
        super("lotus", "AutoHelmet",
                "Swaps to a golden helmet near piglins and back to diamond/netherite afterwards.",
                category);
        this.detectRangeSetting = this.add(new NumberSetting<>("DetectRange", 10, 1, 40));
        this.detectTicksSetting = this.add(new NumberSetting<>("DetectTicks", 5, 1, 64));
        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
        this.skipEmptySetting = this.add(new BooleanSetting("SkipEmpty", true));
        this.avoidEnchantmentsSetting = this.add(new StringSetSetting("AvoidEnchantments",
                "minecraft:binding_curse", "minecraft:frost_walker"));
    }

    @Override
    public void onEnable() {
        this.tickDelay = 0;
        this.firstSeenAt = -1L;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (this.tickDelay > 0) {
            this.tickDelay--;
            return;
        }

        Player player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        if (this.hasAvoidedEnchantment(head)) {
            return;
        }

        boolean piglinNear = this.isPiglinNearWithoutPlayer();
        if (piglinNear) {
            if (head.getItem() == Items.GOLDEN_HELMET) {
                this.firstSeenAt = -1L;
                return;
            }
            if (this.firstSeenAt < 0L) {
                this.firstSeenAt = this.evernightMinecraft.level.getDayTime();
                return;
            }
            if (this.evernightMinecraft.level.getDayTime() - this.firstSeenAt
                    < this.detectTicksSetting.getValue()) {
                return;
            }
            if (this.skipEmptySetting.getValue() && head.isEmpty()) {
                return;
            }
            if (this.equipHelmet(stack -> stack.getItem() == Items.GOLDEN_HELMET)) {
                this.firstSeenAt = -1L;
                this.tickDelay = this.delaySetting.getValue();
            }
            return;
        }

        this.firstSeenAt = -1L;
        if (head.getItem() == Items.DIAMOND_HELMET || head.getItem() == Items.NETHERITE_HELMET) {
            return;
        }
        if (this.skipEmptySetting.getValue() && head.isEmpty()) {
            return;
        }
        if (this.equipHelmet(stack -> stack.getItem() == Items.DIAMOND_HELMET
                || stack.getItem() == Items.NETHERITE_HELMET)) {
            this.tickDelay = this.delaySetting.getValue();
        }
    }

    private boolean equipHelmet(java.util.function.Predicate<ItemStack> predicate) {
        int slot = InventoryUtils.findInventorySlot(
                stack -> predicate.test(stack) && !this.hasAvoidedEnchantment(stack), false);
        if (slot < 0) {
            return false;
        }
        InventoryUtils.clickSlot(InventoryUtils.toMenuSlot(slot), 0, ClickType.QUICK_MOVE);
        ChatUtils.printChat(Component.literal(Translations.get("addons.lotus.messages.helmetSwitched")),
                ChatUtils.moduleSignature(this));
        return true;
    }

    private boolean hasAvoidedEnchantment(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        ItemEnchantments enchantments = stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        Set<String> avoided = this.avoidEnchantmentsSetting.getValue();
        if (avoided.isEmpty()) {
            return false;
        }
        for (var entry : enchantments.entrySet()) {
            String id = entry.getKey().unwrapKey()
                    .map(key -> key.identifier().toString())
                    .orElse("");
            if (avoided.contains(id)) {
                return true;
            }
        }
        return false;
    }

    /** 有猪灵在范围内，且范围内没有其他玩家。*/
    private boolean isPiglinNearWithoutPlayer() {
        Player self = this.evernightMinecraft.player;
        if (self == null || this.evernightMinecraft.level == null) {
            return false;
        }
        double range = this.detectRangeSetting.getValue();
        double rangeSq = range * range;
        boolean piglin = false;
        for (Entity entity : this.evernightMinecraft.level.entitiesForRendering()) {
            if (entity == self) {
                continue;
            }
            if (entity.distanceToSqr(self) > rangeSq) {
                continue;
            }
            if (entity instanceof Player) {
                return false;
            }
            if (entity instanceof Piglin) {
                piglin = true;
            }
        }
        return piglin;
    }
}
