package com.xiaohe66.meteor.lotus.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.HudRenderEvent;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.font.FontRenderer;
import evernight.client.render.mesh.LineMeshRenderer;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import evernight.client.utils.chat.ChatUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code ah} (V实体列表) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>把周围的玩家、掉落物与非玩家实体汇总成一份屏幕列表，按「重要程度」分组显示：
 * <ol>
 *     <li>玩家（按距离升序，好友单独用好友颜色）——用护甲等级把玩家标成「合金甲 / 钻石甲 / 着甲 / 裸奔」；</li>
 *     <li>重点关注的掉落物（默认红色）；</li>
 *     <li>关注的掉落物（默认青色）；</li>
 *     <li>其他掉落物（默认黄色）；</li>
 *     <li>其他实体按类型计数（默认品红）。</li>
 * </ol>
 * 另外可选地把「重点关注物品」画成世界 ESP 方框，并连一条到屏幕中心的追踪线；捡到这类掉落物时聊天提示。
 *
 * <p>移植说明：
 * <ul>
 *     <li>Meteor {@code Render2DEvent} → {@link HudRenderEvent} + {@link FontRenderer}。原模块用
 *     {@code TextRenderer.begin(scale)} 缩放字体，这里用 PoseStack 缩放；原模块用
 *     {@code Window#getScreenWidth()} 做右对齐，这里改用 {@code getGuiScaledWidth()}，因为 HUD 渲染
 *     事件运行在 GUI 缩放坐标空间；</li>
 *     <li>Meteor {@code Render3DEvent} → {@link WorldRenderEvent.Main}：方框用 {@link WorldBoxRenderer}，
 *     「屏幕中心到物品」的连接线用 {@link LineMeshRenderer#addTracer}（两者都吃世界坐标，渲染器内部
 *     自己减去相机位置）；</li>
 *     <li>{@code ShapeMode} → 本地 {@link RenderMode}（Fill / Outline / Both），{@code aF_} →
 *     {@link ScreenSide}；两者都是 {@link NamedEntry}，显示名走语言表；</li>
 *     <li>{@code Names.get(item)} / {@code Names.get(EntityType)} → {@code Item#getName()} /
 *     {@code EntityType#getDescription()}；{@code Friends.get()} → {@link ClientContext#socialRegistry}，
 *     好友颜色取 {@code SocialRegistry#getFriendColor()}；</li>
 *     <li>{@code Utils.isLoading()} → {@code isWorldUnavailable()}；实体快照按每 tick 遍历
 *     {@code level.entitiesForRendering()} 重建（原模块在 Render2D 里每帧重建），HUD 只负责画缓存；</li>
 *     <li>{@code Module#info(...)} → {@link ChatUtils#printChat}。</li>
 * </ul>
 *
 * <p>没移植：Meteor 的 {@code MeteorToast} 弹窗，见文件末尾的 {@code TODO(port)}。
 */
public final class EntityListModule extends AddonModule {
    /** 掉落物「最近距离」的初始值（原模块 {@code i.c} 的初值）。 */
    private static final float NO_DISTANCE = Float.MAX_VALUE;
    /** 世界方框/追踪线的线宽。 */
    private static final float LINE_WIDTH = 1.0F;
    /** 玩家护甲等级的哨兵物品：裸奔 / 其他护甲 / 钻石 / 合金（沿用原模块的表示法）。 */
    private static final Item NAKED_MARKER = Items.AIR;
    private static final Item ARMORED_MARKER = Items.DIAMOND;
    private static final Item DIAMOND_ARMOR_MARKER = Items.DIAMOND_BLOCK;
    private static final Item NETHERITE_ARMOR_MARKER = Items.NETHERITE_BLOCK;

    private final Setting<Set<EntityType<?>>> overworldEntitiesSetting;
    private final Setting<Set<EntityType<?>>> netherEntitiesSetting;
    private final Setting<Set<EntityType<?>>> endEntitiesSetting;
    private final Setting<Color> entityColorSetting;
    private final Setting<Color> netheritePlayerColorSetting;
    private final Setting<Color> diamondPlayerColorSetting;
    private final Setting<Color> otherPlayerColorSetting;
    private final Setting<Integer> playerLimitSetting;

    private final Setting<Set<Item>> priorityItemsSetting;
    private final Setting<Color> priorityItemColorSetting;
    private final Setting<Boolean> notifyPriorityItemsSetting;
    private final Setting<Integer> notifyDetectSecondsSetting;
    private final Setting<Integer> notifyIntervalSecondsSetting;
    private final Setting<Set<Item>> watchedItemsSetting;
    private final Setting<Color> watchedItemColorSetting;
    private final Setting<Color> otherItemColorSetting;
    private final Setting<Integer> otherItemLimitSetting;
    private final Setting<Set<Item>> blacklistSetting;

    private final Setting<Boolean> renderPriorityItemsSetting;
    private final Setting<RenderMode> renderModeSetting;
    private final Setting<Double> fillOpacitySetting;
    private final Setting<Boolean> tracerLinesSetting;

    private final Setting<Integer> xOffsetSetting;
    private final Setting<Integer> yOffsetSetting;
    private final Setting<ScreenSide> positionSetting;
    private final Setting<Integer> lineHeightSetting;
    private final Setting<Double> fontScaleSetting;

    /** 非好友玩家（原模块 {@code I}）。 */
    private final List<PlayerEntry> otherPlayers = new ArrayList<>();
    /** 好友玩家（原模块 {@code J}）。 */
    private final List<PlayerEntry> friends = new ArrayList<>();
    /** 重点关注掉落物（原模块 {@code K}）。 */
    private final Map<Item, ItemEntry> priorityItemsFound = new LinkedHashMap<>();
    /** 关注掉落物（原模块 {@code L}）。 */
    private final Map<Item, ItemEntry> watchedItemsFound = new LinkedHashMap<>();
    /** 其他掉落物（原模块 {@code M}）。 */
    private final Map<Item, ItemEntry> otherItemsFound = new LinkedHashMap<>();
    /** 非玩家、非掉落物的实体计数（原模块 {@code N}）。 */
    private final Map<EntityType<?>, Integer> entityCounts = new LinkedHashMap<>();

    /** 第一次看到重点关注掉落物的时刻（原模块 {@code R}），0 = 当前没有。 */
    private long prioritySeenAt;
    /** 上一次弹提示的时刻（原模块 {@code S}）。 */
    private long lastPickupNoticeAt;

    public EntityListModule(ModuleCategory category) {
        super("lotus", "LotusEntityList",
                "Shows nearby players, items and entities grouped by importance, with priority item "
                        + "boxes and pickup alerts.",
                category);

        this.overworldEntitiesSetting = this.add(new RegistrySetSetting<>("OverworldEntities",
                BuiltInRegistries.ENTITY_TYPE,
                EntityType.PLAYER, EntityType.EXPERIENCE_ORB, EntityType.ZOMBIFIED_PIGLIN));
        this.netherEntitiesSetting = this.add(new RegistrySetSetting<>("NetherEntities",
                BuiltInRegistries.ENTITY_TYPE,
                EntityType.PLAYER,
                EntityType.EXPERIENCE_ORB,
                EntityType.COW,
                EntityType.SHEEP,
                EntityType.PIG,
                EntityType.HORSE,
                EntityType.ZOMBIE,
                EntityType.CREEPER,
                EntityType.BOGGED,
                EntityType.HUSK,
                EntityType.SLIME,
                EntityType.VILLAGER,
                EntityType.SPIDER,
                EntityType.CAVE_SPIDER,
                EntityType.DROWNED,
                EntityType.ZOMBIE_VILLAGER));
        this.endEntitiesSetting = this.add(new RegistrySetSetting<>("EndEntities",
                BuiltInRegistries.ENTITY_TYPE,
                EntityType.PLAYER, EntityType.EXPERIENCE_ORB, EntityType.VILLAGER));
        this.entityColorSetting = this.add(new ColorSetting("EntityColor", new Color(255, 0, 255)));
        this.netheritePlayerColorSetting = this.add(
                new ColorSetting("NetheritePlayerColor", new Color(255, 0, 255)));
        this.diamondPlayerColorSetting = this.add(
                new ColorSetting("DiamondPlayerColor", new Color(0, 255, 255)));
        this.otherPlayerColorSetting = this.add(
                new ColorSetting("OtherPlayerColor", new Color(255, 165, 0)));
        this.playerLimitSetting = this.add(new NumberSetting<>("PlayerLimit", 10, 1, 64));

        this.priorityItemsSetting = this.add(new RegistrySetSetting<>("PriorityItems",
                BuiltInRegistries.ITEM,
                Items.ELYTRA,
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
                Items.BLACK_SHULKER_BOX,
                Items.BUNDLE,
                Items.WHITE_BUNDLE,
                Items.ORANGE_BUNDLE,
                Items.MAGENTA_BUNDLE,
                Items.LIGHT_BLUE_BUNDLE,
                Items.YELLOW_BUNDLE,
                Items.LIME_BUNDLE,
                Items.PINK_BUNDLE,
                Items.GRAY_BUNDLE,
                Items.LIGHT_GRAY_BUNDLE,
                Items.CYAN_BUNDLE,
                Items.PURPLE_BUNDLE,
                Items.BLUE_BUNDLE,
                Items.BROWN_BUNDLE,
                Items.GREEN_BUNDLE,
                Items.RED_BUNDLE,
                Items.BLACK_BUNDLE,
                Items.ANCIENT_DEBRIS,
                Items.NETHERITE_SCRAP,
                Items.NETHERITE_INGOT,
                Items.NETHERITE_BLOCK,
                Items.NETHERITE_SWORD,
                Items.NETHERITE_AXE,
                Items.NETHERITE_HOE,
                Items.NETHERITE_PICKAXE,
                Items.NETHERITE_SHOVEL,
                Items.NETHERITE_HELMET,
                Items.NETHERITE_CHESTPLATE,
                Items.NETHERITE_LEGGINGS,
                Items.NETHERITE_BOOTS,
                Items.NETHERITE_SPEAR,
                Items.END_CRYSTAL,
                Items.ENCHANTED_GOLDEN_APPLE,
                Items.MACE,
                Items.HEAVY_CORE,
                Items.CREEPER_HEAD,
                Items.ZOMBIE_HEAD,
                Items.SKELETON_SKULL,
                Items.WITHER_SKELETON_SKULL,
                Items.PLAYER_HEAD,
                Items.PIGLIN_HEAD,
                Items.DRAGON_HEAD));
        this.priorityItemColorSetting = this.add(new ColorSetting("PriorityItemColor", Color.RED));
        this.notifyPriorityItemsSetting = this.add(new BooleanSetting("NotifyPriorityItems", true));
        this.notifyDetectSecondsSetting = this.add(
                new NumberSetting<>("NotifyDetectSeconds", 2, 1, 5));
        this.notifyIntervalSecondsSetting = this.add(
                new NumberSetting<>("NotifyIntervalSeconds", 5, 1, 10));
        this.watchedItemsSetting = this.add(new RegistrySetSetting<>("WatchedItems",
                BuiltInRegistries.ITEM,
                Items.DIAMOND_SWORD,
                Items.DIAMOND_AXE,
                Items.DIAMOND_HOE,
                Items.DIAMOND_PICKAXE,
                Items.DIAMOND_SHOVEL,
                Items.DIAMOND_HELMET,
                Items.DIAMOND_CHESTPLATE,
                Items.DIAMOND_LEGGINGS,
                Items.DIAMOND_BOOTS,
                Items.COAL_ORE,
                Items.DEEPSLATE_COAL_ORE,
                Items.IRON_ORE,
                Items.DEEPSLATE_IRON_ORE,
                Items.COPPER_ORE,
                Items.DEEPSLATE_COPPER_ORE,
                Items.GOLD_ORE,
                Items.DEEPSLATE_GOLD_ORE,
                Items.REDSTONE_ORE,
                Items.DEEPSLATE_REDSTONE_ORE,
                Items.LAPIS_ORE,
                Items.DEEPSLATE_LAPIS_ORE,
                Items.DIAMOND_ORE,
                Items.DEEPSLATE_DIAMOND_ORE,
                Items.EMERALD_ORE,
                Items.DEEPSLATE_EMERALD_ORE,
                Items.NETHER_QUARTZ_ORE,
                Items.NETHER_GOLD_ORE,
                Items.RAW_IRON,
                Items.RAW_COPPER,
                Items.RAW_GOLD,
                Items.COAL,
                Items.IRON_INGOT,
                Items.COPPER_INGOT,
                Items.GOLD_INGOT,
                Items.REDSTONE,
                Items.LAPIS_LAZULI,
                Items.DIAMOND,
                Items.EMERALD,
                Items.QUARTZ,
                Items.AMETHYST_SHARD,
                Items.COAL_BLOCK,
                Items.IRON_BLOCK,
                Items.COPPER_BLOCK,
                Items.GOLD_BLOCK,
                Items.REDSTONE_BLOCK,
                Items.LAPIS_BLOCK,
                Items.DIAMOND_BLOCK,
                Items.EMERALD_BLOCK,
                Items.QUARTZ_BLOCK,
                Items.AMETHYST_BLOCK,
                Items.SHULKER_SHELL,
                Items.ENDER_PEARL,
                Items.ENDER_CHEST,
                Items.MUSIC_DISC_13,
                Items.MUSIC_DISC_CAT,
                Items.MUSIC_DISC_BLOCKS,
                Items.MUSIC_DISC_CHIRP,
                Items.MUSIC_DISC_FAR,
                Items.MUSIC_DISC_MALL,
                Items.MUSIC_DISC_MELLOHI,
                Items.MUSIC_DISC_STAL,
                Items.MUSIC_DISC_STRAD,
                Items.MUSIC_DISC_WARD,
                Items.MUSIC_DISC_11,
                Items.MUSIC_DISC_WAIT,
                Items.MUSIC_DISC_OTHERSIDE,
                Items.MUSIC_DISC_5,
                Items.MUSIC_DISC_PIGSTEP,
                Items.MUSIC_DISC_RELIC,
                Items.ENDER_EYE,
                Items.END_ROD,
                Items.SNIFFER_EGG,
                Items.SEA_LANTERN,
                Items.VERDANT_FROGLIGHT,
                Items.OCHRE_FROGLIGHT,
                Items.PEARLESCENT_FROGLIGHT,
                Items.SHROOMLIGHT,
                Items.BEACON,
                Items.TNT,
                Items.SLIME_BALL,
                Items.SLIME_BLOCK,
                Items.DRAGON_BREATH,
                Items.GOLDEN_CARROT,
                Items.GHAST_TEAR,
                Items.BLAZE_ROD,
                Items.BREEZE_ROD,
                Items.NETHER_STAR,
                Items.GOLDEN_APPLE,
                Items.PORKCHOP,
                Items.COOKED_PORKCHOP,
                Items.BEEF,
                Items.COOKED_BEEF,
                Items.WIND_CHARGE,
                Items.GOAT_HORN,
                Items.RABBIT_FOOT,
                Items.OMINOUS_TRIAL_KEY,
                Items.TRIAL_KEY,
                Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE,
                Items.SENTRY_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.VEX_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.WILD_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.COAST_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.DUNE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.WAYFINDER_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.RAISER_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.SHAPER_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.HOST_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.WARD_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.SILENCE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.TIDE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.SNOUT_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.RIB_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.EYE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.SPIRE_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.FLOW_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.BOLT_ARMOR_TRIM_SMITHING_TEMPLATE,
                Items.HONEY_BLOCK,
                Items.TOTEM_OF_UNDYING,
                Items.OMINOUS_BOTTLE,
                Items.FIREWORK_ROCKET,
                Items.PUMPKIN_PIE,
                Items.CHORUS_FRUIT));
        this.watchedItemColorSetting = this.add(new ColorSetting("WatchedItemColor", Color.CYAN));
        this.otherItemColorSetting = this.add(new ColorSetting("OtherItemColor", Color.YELLOW));
        // 名字沿用上游「其他物品数量限制」；上游实际用它限制「非玩家非掉落物」的实体类型数量。
        this.otherItemLimitSetting = this.add(new NumberSetting<>("OtherItemLimit", 15, 5, 30));
        this.blacklistSetting = this.add(new RegistrySetSetting<>("Blacklist",
                BuiltInRegistries.ITEM));

        this.renderPriorityItemsSetting = this.add(new BooleanSetting("RenderPriorityItems", true));
        this.renderModeSetting = this.add(new EnumSetting<>("RenderMode", RenderMode.Both)
                .dependsOn(this.renderPriorityItemsSetting));
        this.fillOpacitySetting = this.add(new NumberSetting<>("FillOpacity", 0.3, 0.0, 1.0)
                .dependsOn(this.renderPriorityItemsSetting));
        this.tracerLinesSetting = this.add(new BooleanSetting("TracerLines", true)
                .dependsOn(this.renderPriorityItemsSetting));

        this.xOffsetSetting = this.add(new NumberSetting<>("XOffset", 20, 0, 2048));
        this.yOffsetSetting = this.add(new NumberSetting<>("YOffset", 500, 0, 2048));
        this.positionSetting = this.add(new EnumSetting<>("Position", ScreenSide.Left));
        this.lineHeightSetting = this.add(new NumberSetting<>("LineHeight", 20, 0, 100));
        this.fontScaleSetting = this.add(new NumberSetting<>("FontScale", 1.0, 0.0, 6.0));
    }

    @Override
    public void onEnable() {
        this.prioritySeenAt = 0L;
        this.lastPickupNoticeAt = 0L;
        this.clearSnapshot();
    }

    @Override
    public void onDisable() {
        this.clearSnapshot();
    }

    private void clearSnapshot() {
        this.otherPlayers.clear();
        this.friends.clear();
        this.priorityItemsFound.clear();
        this.watchedItemsFound.clear();
        this.otherItemsFound.clear();
        this.entityCounts.clear();
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null || this.evernightMinecraft.player == null) {
            return;
        }
        this.rebuildSnapshot(level, this.cameraPosition());
        this.checkPickupNotice();
    }

    /** 相机位置：原模块的 {@code br.a()}（Freecam 下跟着自由相机）。 */
    private Vec3 cameraPosition() {
        Camera camera = this.evernightMinecraft.gameRenderer.getMainCamera();
        if (camera.isInitialized()) {
            return camera.position();
        }
        return this.evernightMinecraft.player.position();
    }

    /**
     * 重建实体快照（原模块在 Render2D 里做的收集 + 去重 + 分组）。
     *
     * <p>掉落物按「重点关注 → 关注 → 其他 → 黑名单」归类并累加数量、记录最近距离；玩家按好友 /
     * 非好友分桶，并按护甲等级记录颜色分组；其余实体按类型计数。
     */
    private void rebuildSnapshot(ClientLevel level, Vec3 camera) {
        this.clearSnapshot();

        Set<EntityType<?>> selectedTypes = this.selectedEntityTypes(level);
        Set<Item> priorityItems = this.priorityItemsSetting.getValue();
        Set<Item> watchedItems = this.watchedItemsSetting.getValue();
        Set<Item> blacklist = this.blacklistSetting.getValue();
        int playerLimit = this.playerLimitSetting.getValue();
        int entityTypeLimit = this.otherItemLimitSetting.getValue();

        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ItemEntity itemEntity) {
                this.collectItem(itemEntity, camera, priorityItems, watchedItems, blacklist);
                continue;
            }
            if (!selectedTypes.contains(entity.getType())) {
                continue;
            }
            if (entity instanceof Player player) {
                if (player == this.evernightMinecraft.player) {
                    continue;
                }
                if (this.otherPlayers.size() + this.friends.size() >= playerLimit) {
                    continue;
                }
                PlayerEntry entry = new PlayerEntry(player.getName().getString(),
                        this.armorKind(player), (float) camera.distanceTo(player.position()));
                if (this.isFriend(player)) {
                    this.friends.add(entry);
                } else {
                    this.otherPlayers.add(entry);
                }
            } else {
                EntityType<?> type = entity.getType();
                if (this.entityCounts.containsKey(type)
                        || this.entityCounts.size() < entityTypeLimit) {
                    this.entityCounts.merge(type, 1, Integer::sum);
                }
            }
        }
    }

    /** 当前维度对应的实体白名单（原模块按主世界 / 下界 / 末地三套设置切换）。 */
    private Set<EntityType<?>> selectedEntityTypes(ClientLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        if (Level.NETHER.equals(dimension)) {
            return this.netherEntitiesSetting.getValue();
        }
        if (Level.OVERWORLD.equals(dimension)) {
            return this.overworldEntitiesSetting.getValue();
        }
        return this.endEntitiesSetting.getValue();
    }

    /** 归类一个掉落物实体（原模块 {@code a_Entity_Vec3_ItemEntity}）。 */
    private void collectItem(ItemEntity entity, Vec3 camera, Set<Item> priorityItems,
            Set<Item> watchedItems, Set<Item> blacklist) {
        ItemStack stack = entity.getItem();
        if (stack.isEmpty()) {
            return;
        }
        Item item = stack.getItem();
        if (blacklist.contains(item)) {
            return;
        }

        Map<Item, ItemEntry> target;
        if (priorityItems.contains(item)) {
            target = this.priorityItemsFound;
        } else if (watchedItems.contains(item)) {
            target = this.watchedItemsFound;
        } else {
            target = this.otherItemsFound;
        }

        ItemEntry entry = target.computeIfAbsent(item, ItemEntry::new);
        entry.count += stack.getCount();
        float distance = (float) camera.distanceTo(entity.position());
        if (distance < entry.distance) {
            entry.distance = distance;
        }
    }

    private boolean isFriend(Player player) {
        return ClientContext.socialRegistry != null
                && ClientContext.socialRegistry.isFriendPlayer(player);
    }

    /**
     * 玩家护甲等级（原模块 {@code a_Player} + {@code bq}）：四件全空 = 裸奔，任意一件合金 =
     * 合金甲，任意一件钻石 = 钻石甲，否则 = 着甲。
     */
    private Item armorKind(Player player) {
        Item helmet = player.getItemBySlot(EquipmentSlot.HEAD).getItem();
        Item chest = player.getItemBySlot(EquipmentSlot.CHEST).getItem();
        Item legs = player.getItemBySlot(EquipmentSlot.LEGS).getItem();
        Item feet = player.getItemBySlot(EquipmentSlot.FEET).getItem();

        if (helmet == NAKED_MARKER && chest == NAKED_MARKER
                && legs == NAKED_MARKER && feet == NAKED_MARKER) {
            return NAKED_MARKER;
        }
        if (isNetheriteArmor(helmet) || isNetheriteArmor(chest)
                || isNetheriteArmor(legs) || isNetheriteArmor(feet)) {
            return NETHERITE_ARMOR_MARKER;
        }
        if (isDiamondArmor(helmet) || isDiamondArmor(chest)
                || isDiamondArmor(legs) || isDiamondArmor(feet)) {
            return DIAMOND_ARMOR_MARKER;
        }
        return ARMORED_MARKER;
    }

    private static boolean isNetheriteArmor(Item item) {
        return item == Items.NETHERITE_HELMET
                || item == Items.NETHERITE_CHESTPLATE
                || item == Items.NETHERITE_LEGGINGS
                || item == Items.NETHERITE_BOOTS;
    }

    private static boolean isDiamondArmor(Item item) {
        return item == Items.DIAMOND_HELMET
                || item == Items.DIAMOND_CHESTPLATE
                || item == Items.DIAMOND_LEGGINGS
                || item == Items.DIAMOND_BOOTS;
    }

    @EventHandler
    private void onHudRender(HudRenderEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }

        PoseStack poses = event.getPoseStack();
        double y = this.yOffsetSetting.getValue();

        int playerLimit = this.playerLimitSetting.getValue();
        y = this.renderPlayers(poses, this.otherPlayers, null, y, playerLimit);
        if (this.otherPlayers.size() < playerLimit) {
            y = this.renderPlayers(poses, this.friends, this.friendColor(), y,
                    playerLimit - this.otherPlayers.size());
        }

        y = this.renderItems(poses, this.priorityItemsFound,
                this.priorityItemColorSetting.getValue(), y);
        y = this.renderItems(poses, this.watchedItemsFound,
                this.watchedItemColorSetting.getValue(), y);
        y = this.renderItems(poses, this.otherItemsFound, this.otherItemColorSetting.getValue(), y);
        this.renderEntityCounts(poses, y);

        // 文本是批量提交的：本处理器画完就 flush，避免和 HUD 其余元素共用一批时错位。
        FontRenderer.INSTANCE.flush();
    }

    /**
     * 画一段玩家列表（原模块 {@code a(List, Color, double, int)}）。
     *
     * @param override 非 null 时整段用这个颜色（好友段用好友颜色）
     * @param max      最多画几条（好友段只拿剩余名额）
     */
    private double renderPlayers(PoseStack poses, List<PlayerEntry> entries, Color override,
            double y, int max) {
        if (entries.isEmpty() || max <= 0) {
            return y;
        }
        entries.sort(Comparator.comparingDouble(PlayerEntry::distance));
        int shown = Math.min(entries.size(), max);
        for (int i = 0; i < shown; i++) {
            PlayerEntry entry = entries.get(i);
            String label;
            Color color;
            if (entry.armorKind() == NETHERITE_ARMOR_MARKER) {
                label = Translations.get("addons.lotus.labels.netheriteArmor");
                color = this.netheritePlayerColorSetting.getValue();
            } else if (entry.armorKind() == DIAMOND_ARMOR_MARKER) {
                label = Translations.get("addons.lotus.labels.diamondArmor");
                color = this.diamondPlayerColorSetting.getValue();
            } else if (entry.armorKind() == NAKED_MARKER) {
                label = Translations.get("addons.lotus.labels.unarmored");
                color = Color.WHITE;
            } else {
                label = Translations.get("addons.lotus.labels.armored");
                color = this.otherPlayerColorSetting.getValue();
            }
            String text = String.format(Locale.ROOT, "[%s] %s %.1fm", label, entry.name(),
                    entry.distance());
            this.drawLine(poses, text, override != null ? override : color, y);
            y += this.lineAdvance();
        }
        return y;
    }

    /** 画一段掉落物列表（原模块 {@code a_Map_Color_double}）。 */
    private double renderItems(PoseStack poses, Map<Item, ItemEntry> entries, Color color,
            double y) {
        if (entries.isEmpty()) {
            return y;
        }
        // 上游用 HashMap 的遍历顺序；这里按最近距离排序，保证同一批掉落物每帧顺序稳定。
        List<ItemEntry> ordered = new ArrayList<>(entries.values());
        ordered.sort(Comparator.comparingDouble(entry -> entry.distance));
        for (ItemEntry entry : ordered) {
            String name = entry.item.getName().getString();
            String text = String.format(Locale.ROOT, "%s x%s  (%.1f m)", name, entry.count,
                    entry.distance);
            this.drawLine(poses, text, color, y);
            y += this.lineAdvance();
        }
        return y;
    }

    /** 画实体类型计数（原模块 {@code b(Map, Color, double)}）。 */
    private double renderEntityCounts(PoseStack poses, double y) {
        if (this.entityCounts.isEmpty()) {
            return y;
        }
        Color color = this.entityColorSetting.getValue();
        for (Map.Entry<EntityType<?>, Integer> entry : this.entityCounts.entrySet()) {
            String name = entry.getKey().getDescription().getString();
            String text = String.format(Locale.ROOT, "%s x%s", name, entry.getValue());
            this.drawLine(poses, text, color, y);
            y += this.lineAdvance();
        }
        return y;
    }

    /** 行距：原模块 {@code LineHeight * FontScale}。 */
    private double lineAdvance() {
        return this.lineHeightSetting.getValue() * this.fontScaleSetting.getValue();
    }

    private Color friendColor() {
        if (ClientContext.socialRegistry == null) {
            return Color.WHITE;
        }
        Color color = ClientContext.socialRegistry.getFriendColor();
        return color != null ? color : Color.WHITE;
    }

    /**
     * 画一行文字（原模块 {@code a_String_Color_double}）。
     *
     * <p>原模块用 {@code TextRenderer.begin(scale)} 缩放；这里把缩放交给 PoseStack，宽度用未缩放的
     * {@link FontRenderer#getWidth(String)} 乘缩放量算出来做左右对齐。
     */
    private void drawLine(PoseStack poses, String text, Color color, double y) {
        double scale = this.fontScaleSetting.getValue();
        if (scale <= 0.0 || text.isEmpty()) {
            return;
        }
        float width = FontRenderer.INSTANCE.getWidth(text);
        float x = this.positionSetting.getValue() == ScreenSide.Right
                ? (float) (this.evernightMinecraft.getWindow().getGuiScaledWidth()
                        - width * scale - this.xOffsetSetting.getValue())
                : this.xOffsetSetting.getValue();
        poses.pushPose();
        try {
            poses.translate(x, (float) y, 0.0F);
            poses.scale((float) scale, (float) scale, 1.0F);
            FontRenderer.INSTANCE.drawWithShadow(poses, text, 0.0F, 0.0F, color);
        } finally {
            poses.popPose();
        }
    }

    /** 捡到重点关注物品的提示（原模块 {@code a()}）。 */
    private void checkPickupNotice() {
        if (!this.notifyPriorityItemsSetting.getValue()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (this.priorityItemsFound.isEmpty()) {
            this.prioritySeenAt = 0L;
            return;
        }
        if (this.prioritySeenAt == 0L) {
            this.prioritySeenAt = now;
            return;
        }
        long detectMillis = this.notifyDetectSecondsSetting.getValue() * 1000L;
        long intervalMillis = this.notifyIntervalSecondsSetting.getValue() * 1000L;
        if (now - this.prioritySeenAt > detectMillis
                && now - this.lastPickupNoticeAt > intervalMillis) {
            ChatUtils.printChat(
                    Component.literal(Translations.get("addons.lotus.messages.itemsPickedUp")),
                    ChatUtils.moduleSignature(this));
            // TODO(port): 原模块还会弹一个 MeteorToast（标题 = 模块名，图标 = 箱子，文本「捡东西啦~」）。
            //  Evernight 发行 jar 里没有面向插件的 toast API（只有内部的 ToastManager mixin），
            //  所以这里只保留聊天提示；本体若以后提供 toast 入口，在这里补上即可。
            this.lastPickupNoticeAt = now;
        }
    }

    /** 重点关注物品的世界 ESP 方框 + 追踪线（原模块 {@code onRender3D}）。 */
    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        if (!this.renderPriorityItemsSetting.getValue() || this.isWorldUnavailable()) {
            return;
        }
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }

        PoseStack poses = event.getPoseStack();
        float partialTick = event.getPartialTick();
        Color outlineColor = this.priorityItemColorSetting.getValue();
        Color fillColor = withAlpha(outlineColor,
                (int) (outlineColor.getAlpha() * this.fillOpacitySetting.getValue()));
        RenderMode mode = this.renderModeSetting.getValue();
        boolean drawFill = mode != RenderMode.Outline;
        boolean drawOutline = mode != RenderMode.Fill;
        boolean drawTracers = this.tracerLinesSetting.getValue()
                && !this.evernightMinecraft.options.hideGui;

        Set<Item> priorityItems = this.priorityItemsSetting.getValue();
        Set<Item> blacklist = this.blacklistSetting.getValue();

        if (drawTracers) {
            LineMeshRenderer.begin(poses, LINE_WIDTH);
        }
        boolean anyTracer = false;

        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof ItemEntity itemEntity)) {
                continue;
            }
            Item item = itemEntity.getItem().getItem();
            if (!priorityItems.contains(item) || blacklist.contains(item)) {
                continue;
            }

            // 用上一 tick 到当前 tick 的插值位置，抵消 1 tick 的抖动（原模块 Render3DEvent 的写法）。
            double offsetX = Mth.lerp(partialTick, entity.xOld, entity.getX()) - entity.getX();
            double offsetY = Mth.lerp(partialTick, entity.yOld, entity.getY()) - entity.getY();
            double offsetZ = Mth.lerp(partialTick, entity.zOld, entity.getZ()) - entity.getZ();
            AABB box = entity.getBoundingBox().move(offsetX, offsetY, offsetZ);

            if (drawFill) {
                WorldBoxRenderer.fillBox(poses, box, fillColor);
            }
            if (drawOutline) {
                WorldBoxRenderer.outlineBox(poses, box, outlineColor, LINE_WIDTH);
            }
            if (drawTracers) {
                LineMeshRenderer.addTracer(box.getCenter(), outlineColor);
                anyTracer = true;
            }
        }

        if (anyTracer) {
            LineMeshRenderer.draw();
        }
    }

    private static Color withAlpha(Color color, int alpha) {
        int clamped = Math.max(0, Math.min(255, alpha));
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), clamped);
    }

    /** 列表位置（原模块 {@code aF_}）。 */
    public enum ScreenSide implements NamedEntry {
        Left("Left"),
        Right("Right");

        private final String name;

        ScreenSide(String name) {
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

    /** 方框渲染方式（替换 Meteor 的 {@code ShapeMode}）。 */
    public enum RenderMode implements NamedEntry {
        Outline("Outline"),
        Fill("Fill"),
        Both("Both");

        private final String name;

        RenderMode(String name) {
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

    /** 一个玩家的显示数据（原模块 {@code k}）。 */
    private record PlayerEntry(String name, Item armorKind, float distance) {
    }

    /** 一种掉落物的汇总数据（原模块 {@code i}）：数量 + 最近距离。 */
    private static final class ItemEntry {
        private final Item item;
        private int count;
        private float distance = NO_DISTANCE;

        private ItemEntry(Item item) {
            this.item = item;
        }
    }
}
