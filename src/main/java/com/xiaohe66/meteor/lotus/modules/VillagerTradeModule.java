package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.integrations.baritone.BaritoneBridge;
import evernight.client.managers.player.RotationManager;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.RegistrySetSetting;
import evernight.client.settings.types.StringSetSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.ContainerInventoryMath;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import evernight.client.utils.world.BlockInteractionUtils;
import evernight.client.utils.world.WorldUtils;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code az} (A自动村民交易) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>自动和村民交易。仓库约定是「展示框 + 盒子」：展示框里的物品 = 该盒子装的东西，盒子在展示框下 2 格；
 * 展示框上方/后面是补货用的大箱子（放书的箱子），绿宝石从最近的木桶补给。整个循环是
 * 「去卸货 → 卸货 → 去补给 → 补货 → 挑下一个村民 → 开交易 → 执行交易」，每一步之间带 tick 延迟，
 * 靠交易列表排序 + 白天/夜里两个时间窗口，让同一个村民一天只交易 1~2 次，避免价格被抬高。
 *
 * <p><b>Evernight 1.0.3 的对应物</b>（全部用 {@code javap} 核对过发行 jar）：
 * <ul>
 *     <li>走位：上游继承 {@code aA_}，直接用 Baritone 的 {@code GoalNear} + {@code ICustomGoalProcess}；
 *     这里换成 {@link BaritoneBridge#pathTo(BlockPos)} / {@code isPathingOrCalculating()} /
 *     {@code cancelPathing()}，并在每次下发前判 {@code isAvailable()}。</li>
 *     <li>容器：上游 {@code ag#a_BlockPos_Consumer} + {@code bn}/{@code bp} 负责开箱、点格子、关界面；
 *     这里换成 {@link ContainerInteractionUtils#openContainer(BlockPos, boolean)} /
 *     {@code closeScreen()} / {@code menuKind(AbstractContainerMenu)} 与
 *     {@link InventoryUtils#clickSlot(int, int, int, ClickType)}（{@link ClickType#QUICK_MOVE} 等价于
 *     Meteor 的 {@code InvUtils.shiftClick()}）。</li>
 *     <li>交易：{@link MerchantMenu#setSelectionHint(int)} / {@link MerchantMenu#tryMoveItems(int)} +
 *     {@link PacketSender#send(net.minecraft.network.protocol.Packet)} 发
 *     {@link ServerboundSelectTradePacket}，与原模块一致。</li>
 *     <li>物品签名：上游 {@code g}（物品 + 自定义名 + 附魔 + 药水 + 地图编号 + 不祥之瓶等级）换成
 *     {@link ItemKey} 记录，用 1.21 的数据组件按同样的字段比较。</li>
 *     <li>物品栏：{@code InvUtils.find / shiftClick / findEmpty} → 本地 36 格扫描 +
 *     {@link InventoryUtils#clickSlot(int, int, int, ClickType)}。</li>
 * </ul>
 *
 * <p><b>与原模块的差异（都留有 TODO(port)）</b>：
 * <ul>
 *     <li>设置：上游每个职业一个 {@code SettingGroup}（{@code 交易_X}），本体 1.0.3 没有分组语义
 *     （只有 {@code markAsGroup} 的视觉折叠），这里按要求平铺；「村民类型」下拉只保留在「其他」组，
 *     因为其余 6 组的该设置被 {@code visible(...)} 藏起来、值恒等于组自身的职业。</li>
 *     <li>交易物品列表的「职业过滤」：上游给 {@code ItemListSetting} 挂了 {@code filter}，让下拉里只显示
 *     该职业会卖的东西。{@code RegistrySetSetting} 没有条目过滤器，所以任何物品都能选（只是 UI 便利性，
 *     不影响运行逻辑）。</li>
 *     <li>附魔书列表：上游是 {@code EnchantmentListSetting}（注册表多选、带译名），本体没有对应物，
 *     与 {@code LotusEnchantBookFarm} 一致改用 {@code StringSetSetting} 存附魔 id。</li>
 *     <li>「初始化」上游自动初始化那段实际是坏的（{@code a_Boolean(false)} 传了 false，什么也不做，
 *     然后必定提示「自动初始化失败」并关模块）。这里按它的注释意图改成真的调用初始化，见
 *     {@link #onEnable()}。</li>
 *     <li>Baritone 的 {@code allowBreak / allowPlace} 在模块启用期间被上游强制关闭；{@link BaritoneBridge}
 *     没有对应开关，未迁移。</li>
 *     <li>{@code PathEvent.CANCELED} 没被桥接：上游靠它把状态从 WALKING 弹回待执行状态。这里改成每 tick
 *     轮询「到点了吗 / 还在寻路吗」，寻路停了就补发目标，补发次数用完就跳到下一步（下一步自己会再判距离）。</li>
 *     <li>{@code GoalNear(pos, range)} 的「停在 range 格内」语义：{@link BaritoneBridge#pathTo(BlockPos)}
 *     只表达 {@code GoalBlock}，所以 range 由本模块自己按距离判断（见 {@link #arriveDistance()}）。</li>
 *     <li>上游的「快速停止键」（{@code KeybindSetting}）与「重启延时」调度（{@code ScheduledFuture}）：
 *     前者本体没有设置级键位（模块键位本身就是开关），未迁移；后者改成 tick 倒计时。</li>
 *     <li>上游「指定位置不是盒子 / 盒子已满」用系统时间做 3 秒节流，这里同样节流；状态机与延迟一致。</li>
 *     <li>上游一次交易成功后不关界面，靠下一步继续；本移植保持原样（{@link Step#Next} 里再关）。</li>
 * </ul>
 */
public final class VillagerTradeModule extends AddonModule {
    /** 上游 {@code aA_#a} 的距离保护：到目标超过 1000 格就关模块。 */
    private static final double MAX_TRAVEL_DISTANCE = 1000.0D;
    /** 上游 {@code G()} 选下一个村民时的距离保护。 */
    private static final double MAX_VILLAGER_DISTANCE = 300.0D;
    /** 上游 {@code bt} 的转向优先级 6666；本体 100 已经压过绝大多数模块。 */
    private static final int ROTATION_PRIORITY = 100;
    /** 背包 36 格（0-8 快捷栏、9-35 主背包），与上游 {@code bp#h()} 一致。 */
    private static final int INVENTORY_SIZE = 36;
    /** {@code MerchantMenu} 的成品槽与第一付款槽。 */
    private static final int RESULT_SLOT = 2;
    private static final int PAYMENT_SLOT = 0;
    /** 一组 = 64 个。 */
    private static final int STACK_SIZE = 64;
    /** 上游 {@code D()}：价格上限 × 12，封顶 64，就是「这一轮要带多少绿宝石」。 */
    private static final int EMERALDS_PER_PRICE_UNIT = 12;
    /** 上游 {@code D()}：书少于 12 本、空位少于 12 格时先补给。 */
    private static final int BOOK_BATCH = 12;
    private static final int EMPTY_SLOT_BATCH = 12;
    /** 上游 {@code x()}/{@code C()}：一天 24000 tick，2000 是早上 8 点、9000 是下午 3 点。 */
    private static final long DAY_LENGTH = 24000L;
    private static final long DAY_WORK_START = 2000L;
    private static final long DAY_EARLY = 2020L;
    private static final long DAY_WORK_END = 9000L;
    /** 上游 {@code G()} 的 filter 用 8900 而不是 9000（收盘前 100 tick 就不再算「今天交易过」）。 */
    private static final long DAY_WORK_END_TOLERANCE = 8900L;
    /** 上游 {@code a_AbstractContainerMenu_toBoolean} 之后 {@code c(20)}：盒子满了等 20 tick 再看。 */
    private static final int FULL_BOX_RETRY_TICKS = 20;
    /** 上游 {@code G()} 的 {@code now - 100}：同一个村民两次尝试之间的最小间隔。 */
    private static final long TRADE_ATTEMPT_GAP = 100L;
    /** 上游 {@code b_String_toVoid} 的 3000ms 节流。 */
    private static final long MESSAGE_GAP_MS = 3000L;
    /** 开容器包的最小重发间隔（上游没有节流，服务端不响应时会每 tick 发一次）。 */
    private static final long OPEN_THROTTLE_MS = 500L;
    /** 容器打开后等 stateId 同步的上限，对应上游 {@code ag} 的 2000ms。 */
    private static final long CONTAINER_SYNC_TIMEOUT_MS = 2000L;
    /** 寻路失败后重发目标的次数上限，用完就跳到下一步。 */
    private static final int MAX_PATH_ATTEMPTS = 40;
    /** 上游「重启延时」的单位是秒，原版一 tick = 50ms。 */
    private static final int TICKS_PER_SECOND = 20;
    /** 上游 {@code D} 表里的检查顺序（牧师 → 农民 → 图书管理员 → 盔甲匠 → 武器匠 → 工具匠）。 */
    private static final List<Profession> TRADE_ORDER = List.of(
            Profession.Cleric,
            Profession.Farmer,
            Profession.Librarian,
            Profession.Armorer,
            Profession.Weaponsmith,
            Profession.Toolsmith);
    /** 上游 {@code bq#a_Item_toBoolean}：能当「盒子」用的 17 种潜影盒。 */
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

    private final Setting<Boolean> debugSetting;
    private final Setting<Integer> searchRangeSetting;
    private final Setting<Integer> operationRangeSetting;
    private final Setting<Integer> delaySetting;
    private final Setting<Integer> restartDelaySetting;
    private final Setting<Boolean> initializeSetting;

    private final ProfessionConfig cleric;
    private final ProfessionConfig farmer;
    private final ProfessionConfig librarian;
    private final ProfessionConfig armorer;
    private final ProfessionConfig weaponsmith;
    private final ProfessionConfig toolsmith;
    private final ProfessionConfig other;
    private final List<ProfessionConfig> configs;

    /** 上游 {@code az#E}：初始化时锁定的村民列表。 */
    private List<TargetVillager> villagers = List.of();
    /** 上游 {@code az#F}：物品签名 → 卸货盒子位置。 */
    private Map<ItemKey, BlockPos> unloadContainers = Map.of();
    /** 上游 {@code az#G}：绿宝石补给用的木桶。 */
    private BlockPos emeraldSupply;
    /** 上游 {@code az#H}：书补给用的大箱子。 */
    private BlockPos bookSupply;
    /** 上游 {@code az#I}：当前在交易的职业配置。 */
    private ProfessionConfig currentConfig;
    /** 上游 {@code az#J}/{@code az#K}：当前卸货目标与要卸的物品。 */
    private BlockPos unloadTarget;
    private ItemKey unloadKey;
    /** 上游 {@code az#L}/{@code az#M}：当前补给目标与要补的物品。 */
    private BlockPos supplyTarget;
    private Item supplyItem;
    /** 上游 {@code az#N}：初始化时汇总出来的「所有要买的物品」。 */
    private List<ItemKey> purchaseKeys = List.of();
    /** 上游 {@code az#O}：当前锁定的村民。 */
    private TargetVillager currentVillager;
    /** 上游 {@code az#P}：最后一次交易完成的时间（-1 = 还没交易过）。 */
    private long waitingSince = -1L;
    /** 上游 {@code az#Q}：当前正在看的交易索引。 */
    private int offerIndex;
    /** 上游 {@code az#R}：上一次检查时的「第几轮」（1 = 工作时间，2 = 非工作时间）。 */
    private int round;
    /** 上游 {@code az#S}：节流消息的时间戳。 */
    private long lastMessageAt;

    /** 上游 {@code aw#m}：当前状态。 */
    private Step step = Step.None;
    /** 上游 {@code aw#o}：{@link Step#CloseScreen} 结束后去哪。 */
    private Step closeNext;
    /** 上游 {@code aw#n}：走位结束后去哪。 */
    private Step walkNext;
    /** 走位目标（Baritone 的目标只有方块坐标，range 由本模块自己判）。 */
    private BlockPos walkTarget;
    private int walkRange;
    private int pathAttempts;
    /** 上游 {@code ag#a}：距离下次执行还要等多少 tick。 */
    private int stepDelay;
    /** 上游 {@code aw#t} 的 {@code ScheduledFuture}：重启倒计时（tick）。 */
    private int restartTicks;

    /** 已经打开（并且已经下发过开箱包）的容器位置。 */
    private BlockPos openedPos;
    private long lastOpenAttemptAt;
    private long openRequestedAt;

    public VillagerTradeModule(ModuleCategory category) {
        super("lotus", "LotusVillagerTrade",
                "Trades with villagers automatically: unloads into the shulker boxes two blocks below the"
                        + " item frames, refills emeralds from the nearest barrel and books from the chest"
                        + " behind the book item frame.",
                category);

        // 上游 az：调试模式 / 搜索容器范围 / 操作范围。
        this.debugSetting = this.add(new BooleanSetting("Debug", false));
        this.searchRangeSetting = this.add(new NumberSetting<>("SearchRange", 32, 6, 100));
        this.operationRangeSetting = this.add(new NumberSetting<>("OperationRange", 2, 2, 3));
        // 上游 ag 的「延迟」：默认 5，上限 40；上游 aw 的「重启延时」：默认 2 秒。
        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
        this.restartDelaySetting = this.add(new NumberSetting<>("RestartDelay", 2, 1, 200).withUnit("s"));

        // 上游 bi × 7：每个职业一份「开关 + 交易物 + 附魔书 + 补给数量(组) + 价格上限」。
        this.cleric = new ProfessionConfig(Profession.Cleric, "Cleric", false, 24);
        this.farmer = new ProfessionConfig(Profession.Farmer, "Farmer", false, 8);
        this.librarian = new ProfessionConfig(Profession.Librarian, "Librarian", false, 1);
        this.armorer = new ProfessionConfig(Profession.Armorer, "Armorer", false, 1);
        this.weaponsmith = new ProfessionConfig(Profession.Weaponsmith, "Weaponsmith", false, 1);
        this.toolsmith = new ProfessionConfig(Profession.Toolsmith, "Toolsmith", false, 1);
        this.other = new ProfessionConfig(Profession.Mason, "Other", true, 6);
        this.configs = List.of(
                this.cleric, this.farmer, this.librarian,
                this.armorer, this.weaponsmith, this.toolsmith, this.other);

        // 上游的「初始化」开关：打开时立刻扫一遍仓库并与村民对上号。
        this.initializeSetting = this.add(new BooleanSetting("Initialize", false));
        this.initializeSetting.setChangeCallback(this::onInitializeSettingChanged);
    }

    // ------------------------------------------------------------------
    // 生命周期（上游 az#onActivate / aw#onDeactivate / aA_#onDeactivate）
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.step = Step.None;
        this.closeNext = null;
        this.walkNext = null;
        this.walkTarget = null;
        this.pathAttempts = 0;
        this.stepDelay = 0;
        this.restartTicks = 0;
        this.openedPos = null;

        if (this.isWorldUnavailable()) {
            this.print("addons.lotus.messages.worldUnavailable");
            this.toggle();
            return;
        }

        // 上游只在创造/生存里跑；其它模式停在 NONE，模块本身不关。
        GameType mode = this.evernightMinecraft.gameMode == null
                ? null
                : this.evernightMinecraft.gameMode.getPlayerMode();
        if (mode != GameType.CREATIVE && mode != GameType.SURVIVAL) {
            return;
        }

        if (this.villagers.isEmpty()) {
            this.print("addons.lotus.messages.autoInitialize");
            // TODO(port): 上游这里调用的是 a_Boolean(false)，被判成「不初始化」，于是必定走到
            //  「自动初始化失败」并关掉模块。这里按它的注释意图真的初始化一次。
            this.initialize();
            if (this.villagers.isEmpty()) {
                this.print("addons.lotus.messages.autoInitializeFailed");
                this.toggle();
                return;
            }
        }

        this.print("addons.lotus.messages.villagerTradeInstructions");
        this.scheduleRestart();
    }

    @Override
    public void onDisable() {
        this.step = Step.None;
        this.closeNext = null;
        this.walkNext = null;
        this.walkTarget = null;
        this.restartTicks = 0;
        this.openedPos = null;
        this.cancelPathing();
        // TODO(port): 上游 aA_#onDeactivate 还会把 Baritone 的 allowBreak/allowPlace 打开，
        //  BaritoneBridge 没有对应开关。
    }

    /** 上游 {@code A#onChanged} → {@code a_Boolean(true)}。 */
    private void onInitializeSettingChanged() {
        if (!this.initializeSetting.getValue()) {
            return;
        }
        this.initialize();
    }

    // ------------------------------------------------------------------
    // 主循环（上游 aw#onTick(TickEvent.Pre)）
    // ------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }

        if (this.restartTicks > 0 && --this.restartTicks == 0) {
            this.print("addons.lotus.messages.started");
            this.step = Step.Next;
            this.setDelay();
            return;
        }

        if (this.stepDelay > 0) {
            this.stepDelay--;
            return;
        }

        switch (this.step) {
            case None -> {
                // 上游 z()：空处理器。出错的占位状态，等玩家重新初始化/重开模块。
            }
            case Walking -> this.tickWalking();
            case CloseScreen -> this.tickCloseScreen();
            case Next -> this.tickNext();
            case GoToPutItem -> this.tickGoToPutItem();
            case PutItem -> this.tickPutItem();
            case GoToTakeItem -> this.tickGoToTakeItem();
            case TakeItem -> this.tickTakeItem();
            case OpenTrade -> this.tickOpenTrade();
            case ExecuteTrade -> this.tickExecuteTrade();
            case Wait -> this.tickWait();
        }
    }

    /** 上游 {@code aw#y()}：关界面，然后切到待执行状态。 */
    private void tickCloseScreen() {
        this.closeScreenNow();
        this.step = this.closeNext != null ? this.closeNext : Step.Next;
        this.closeNext = null;
        this.setDelay();
    }

    /** 上游 {@code az#G()}（NEXT）：挑下一个村民，或者宣告这一轮结束。 */
    private void tickNext() {
        int currentRound = this.currentRound();
        if (currentRound != this.round) {
            this.round = currentRound;
            this.print("addons.lotus.messages.villagerTradeRoundChanged", this.roundLabel(currentRound));
        }

        // 上游先看要不要补给：要补给时先去卸货，卸不了就直接去补给。
        if (this.needsSupply() && this.goUnload()) {
            return;
        }

        LocalPlayer player = this.evernightMinecraft.player;
        ClientLevel level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return;
        }

        long now = level.getDayTime();
        long timeOfDay = now % DAY_LENGTH;
        long day = now / DAY_LENGTH;

        Map<Profession, List<TargetVillager>> byProfession = new EnumMap<>(Profession.class);
        for (TargetVillager villager : this.villagers) {
            if (!villager.isTradable(day, timeOfDay)) {
                continue;
            }
            if (villager.lastAttemptAt >= now - TRADE_ATTEMPT_GAP) {
                continue;
            }
            byProfession.computeIfAbsent(villager.profession, key -> new ArrayList<>()).add(villager);
        }

        // 上游按 D 表的顺序找第一个有人的职业，剩下的全归「其他」组。
        ProfessionConfig selected = null;
        List<TargetVillager> candidates = null;
        for (Profession profession : TRADE_ORDER) {
            List<TargetVillager> group = byProfession.get(profession);
            if (group != null && !group.isEmpty()) {
                candidates = group;
                selected = this.configFor(profession);
                break;
            }
        }
        if (selected != null) {
            this.currentConfig = selected;
        } else {
            candidates = byProfession.getOrDefault(this.other.profession(), List.of());
            if (!candidates.isEmpty()) {
                this.currentConfig = this.other;
            }
        }

        if (candidates == null || candidates.isEmpty()) {
            this.print("addons.lotus.messages.villagerTradeAllTraded",
                    this.roundLabel(currentRound == 1 ? 2 : 1));
            this.waitingSince = now;
            this.goTo(Step.GoToPutItem);
            return;
        }

        TargetVillager nearest = null;
        double bestDistance = Double.MAX_VALUE;
        Vec3 playerPosition = player.position();
        for (TargetVillager villager : candidates) {
            double distance = villager.standPos.getCenter().distanceTo(playerPosition);
            if (distance < bestDistance) {
                bestDistance = distance;
                nearest = villager;
            }
        }

        this.currentVillager = nearest;
        if (nearest == null) {
            this.goTo(Step.Next);
            return;
        }

        BlockPos target = nearest.standPos;
        if (!target.closerToCenterThan(playerPosition, MAX_VILLAGER_DISTANCE)) {
            this.print("addons.lotus.messages.villagerTradeTooFar");
            this.toggle();
            return;
        }

        this.debug("addons.lotus.messages.villagerTradeDebugTarget", target.toShortString());
        // 上游 a(pos, 0, bf.E, null)：容差 0，走到站位上再开交易。
        this.travel(target, 0, Step.OpenTrade, null);
    }

    /** 上游 {@code az#M()}（GOTO_PUT_ITEM）：先卸货，卸不了就去补给。 */
    private void tickGoToPutItem() {
        if (!this.goUnload()) {
            this.goSupply();
        }
    }

    /** 上游 {@code az#L()}（PUT_ITEM）：把包里匹配的物品塞进展示框下的盒子。 */
    private void tickPutItem() {
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null || this.unloadTarget == null || this.unloadKey == null) {
            this.setDelay();
            this.goTo(Step.Next);
            return;
        }

        if (!isUnloadContainer(level.getBlockState(this.unloadTarget))) {
            this.printThrottled("addons.lotus.messages.villagerTradeNotBox");
            this.setDelay();
            return;
        }

        ItemKey key = this.unloadKey;
        this.withContainer(this.unloadTarget, menu -> {
            int slot = this.findInventorySlot(key::matches);
            if (slot < 0) {
                // 上游：包里已经没有这类物品 → 看有没有空位，有就继续下一个，没有就清空了再说。
                if (this.findEmptyInventorySlot() >= 0) {
                    this.closeScreenNow();
                    this.tickGoToPutItem();
                } else {
                    this.closeScreenNow();
                    this.print("addons.lotus.messages.villagerTradeCannotClearInventory");
                    this.step = Step.None;
                    this.scheduleRestart();
                }
                return;
            }

            if (this.isBoxFull(menu, key)) {
                this.closeScreenNow();
                this.printThrottled("addons.lotus.messages.villagerTradeBoxFull");
                this.stepDelay = FULL_BOX_RETRY_TICKS;
                return;
            }

            this.shiftClickInventory(slot);
            this.setDelay();
        });
    }

    /** 上游 {@code az#J()}（GOTO_TAKE_ITEM）：身上的绿宝石/书不够就去对应的容器补。 */
    private void tickGoToTakeItem() {
        int supplyStacks = this.activeConfig().supplyStacks();
        if (this.countInInventory(stack -> stack.is(Items.EMERALD)) >= STACK_SIZE * supplyStacks) {
            if (this.activeConfig().needsBooks()
                    && this.countInInventory(stack -> stack.is(Items.BOOK)) < STACK_SIZE) {
                this.supplyTarget = this.bookSupply;
                this.supplyItem = Items.BOOK;
                this.travel(this.bookSupply, this.operationRangeSetting.getValue(), Step.TakeItem,
                        "addons.lotus.messages.villagerTradeSupplyBook");
                return;
            }
            this.afterSupply();
            return;
        }

        this.supplyTarget = this.emeraldSupply;
        this.supplyItem = Items.EMERALD;
        this.travel(this.emeraldSupply, this.operationRangeSetting.getValue(), Step.TakeItem,
                "addons.lotus.messages.villagerTradeSupplyEmerald");
    }

    /** 上游 {@code az#I()}（TAKE_ITEM）：从已经打开的容器 shift 出补给物。 */
    private void tickTakeItem() {
        if (this.supplyTarget == null || this.supplyItem == null) {
            this.setDelay();
            this.goTo(Step.Next);
            return;
        }

        Item item = this.supplyItem;
        this.withContainer(this.supplyTarget, menu -> {
            int requiredStacks = item == Items.BOOK ? 1 : this.activeConfig().supplyStacks();
            if (this.countInInventory(stack -> stack.is(item)) >= STACK_SIZE * requiredStacks) {
                this.closeScreenNow();
                if (item == Items.EMERALD) {
                    this.tickGoToTakeItem();
                } else {
                    this.afterSupply();
                }
                return;
            }

            int slot = this.findContainerSlot(menu, stack -> stack.is(item));
            if (slot < 0) {
                this.print("addons.lotus.messages.cannotSupply", item.getName().getString());
                this.step = Step.None;
                this.scheduleRestart();
                return;
            }

            this.print("addons.lotus.messages.takingItem", item.getName().getString());
            this.shiftClickContainer(slot);
            this.setDelay();
        });
    }

    /** 上游 {@code az#F()}（OPEN_TRADE）：走近后右键村民，把交易界面顶开。 */
    private void tickOpenTrade() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.currentVillager == null) {
            this.goTo(Step.Next);
            return;
        }

        Villager villager = this.resolveVillager(this.currentVillager);
        if (villager == null) {
            this.currentVillager.markTraded(this.dayTime());
            this.goTo(Step.Next);
            return;
        }

        Vec3 playerPosition = player.position();
        Vec3 villagerPosition = villager.position();
        double distance = villagerPosition.distanceTo(playerPosition);
        int operationRange = this.operationRangeSetting.getValue();
        if (distance > operationRange + 0.5D) {
            this.debug("addons.lotus.messages.villagerTradeDebugDistance");
            this.currentVillager.lastAttemptAt = this.dayTime();
            this.goTo(Step.Next);
            return;
        }

        if (!villager.isAlive()) {
            this.print("addons.lotus.messages.villagerTradeVillagerDead");
            this.currentVillager.markTraded(this.dayTime());
            this.goTo(Step.Next);
            return;
        }

        EntityHitResult hitResult = ProjectileUtil.getEntityHitResult(
                player, playerPosition, villagerPosition, villager.getBoundingBox(),
                Entity::isPickable, playerPosition.distanceToSqr(villagerPosition));
        Vec3 aimPoint = hitResult != null ? hitResult.getLocation() : villager.getEyePosition();
        // 上游按「射线命没命中」分别走 interactAt / interact 并打日志；
        // TODO(port): 这里统一交给 interactWithEntity（内部先 interactAt 再回落 interact），
        //  射线结果只用来决定朝向点。
        this.debug("addons.lotus.messages.villagerTradeDebugRaycast", hitResult != null ? "hit" : "miss");

        this.rotateAndRun(aimPoint, () -> BlockInteractionUtils.interactWithEntity(
                villager, InteractionHand.MAIN_HAND));
        this.step = Step.ExecuteTrade;
        // 上游 c(this.k.get() * 2)：开界面后多等一倍延迟再动手。
        this.stepDelay = this.delaySetting.getValue() * 2;
    }

    /** 上游 {@code az#E()}（EXECUTE_TRADE）：翻交易列表，价格合适就锁定并发包取货。 */
    private void tickExecuteTrade() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }

        if (!(player.containerMenu instanceof MerchantMenu merchant)) {
            // 上游 l(); m = bf.E：界面没开出来，等一会儿重开。
            this.setDelay();
            this.step = Step.OpenTrade;
            return;
        }

        MerchantOffers offers = merchant.getOffers();
        if (offers == null) {
            // TODO(port): 上游这里会 NPE（交易列表还没同步就直接 getOffers().size()），这里退回去重开。
            this.setDelay();
            this.step = Step.OpenTrade;
            return;
        }

        while (this.offerIndex < offers.size()) {
            // 上游用的是 for (; Q < size; Q++)：先取当前项，再让索引自增。
            int index = this.offerIndex;
            this.offerIndex++;
            MerchantOffer offer = offers.get(index);
            if (offer.getMaxUses() <= offer.getUses()) {
                continue;
            }

            ItemCost cost = offer.getItemCostA();
            if (cost == null || !cost.itemStack().is(Items.EMERALD)) {
                continue;
            }

            ItemStack result = offer.getResult();
            if (!this.isTargetOffer(result)) {
                continue;
            }

            int baseCost = offer.getBaseCostA().getCount();
            int price = baseCost + offer.getSpecialPriceDiff();
            if (offer.getDemand() > 0) {
                price = (int) (price + baseCost * offer.getPriceMultiplier() * offer.getDemand());
            }
            this.debug("addons.lotus.messages.villagerTradeDebugPrice", baseCost,
                    offer.getSpecialPriceDiff(), offer.getDemand(), offer.getPriceMultiplier(), price);

            if (price > this.activeConfig().priceLimit()) {
                this.print("addons.lotus.messages.villagerTradePriceTooHigh");
                continue;
            }

            if (this.needsSupply()) {
                this.closeThen(Step.GoToPutItem);
                return;
            }

            merchant.setSelectionHint(index);
            merchant.tryMoveItems(index);
            PacketSender.send(new ServerboundSelectTradePacket(index));
            InventoryUtils.clickSlot(merchant.containerId, RESULT_SLOT, 0, ClickType.QUICK_MOVE);
            this.setDelay();
            return;
        }

        if (this.currentVillager != null) {
            this.currentVillager.markTraded(this.dayTime());
        }
        this.offerIndex = 0;
        this.closeThen(Step.Next);
    }

    /** 上游 {@code az#x()}（WAIT）：等下一个交易时间窗口。 */
    private void tickWait() {
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }

        long waitDay = this.waitingSince / DAY_LENGTH;
        long waitTimeOfDay = this.waitingSince % DAY_LENGTH;
        long now = level.getDayTime();
        long day = now / DAY_LENGTH;
        long timeOfDay = now % DAY_LENGTH;

        boolean ready;
        if (waitTimeOfDay > DAY_WORK_END) {
            ready = day > waitDay && timeOfDay > DAY_EARLY;
        } else if (waitTimeOfDay > DAY_WORK_START) {
            ready = day > waitDay || timeOfDay > DAY_WORK_END;
        } else {
            ready = day > waitDay || timeOfDay > DAY_EARLY;
        }

        if (ready) {
            this.waitingSince = -1L;
            this.step = Step.GoToPutItem;
        } else {
            this.debug("addons.lotus.messages.villagerTradeDebugWait", waitDay, day, waitTimeOfDay, timeOfDay);
            this.setDelay();
        }
    }

    // ------------------------------------------------------------------
    // 初始化（上游 az#a_Boolean(true)）
    // ------------------------------------------------------------------

    private void initialize() {
        // 上游 A.set(false)：把开关拨回去，避免它一直亮着。
        this.initializeSetting.setValue(false);
        this.villagers = List.of();

        List<TargetVillager> found = this.findVillagers();
        if (found.isEmpty()) {
            this.print("addons.lotus.messages.villagerTradeNoVillager");
            return;
        }

        Map<ItemKey, BlockPos> containers = this.scanUnloadContainers();
        if (containers.isEmpty()) {
            this.print("addons.lotus.messages.villagerTradeNoUnloadContainer");
            return;
        }

        BlockPos newBookSupply = null;
        if (this.configs.stream().anyMatch(ProfessionConfig::needsBooks)) {
            newBookSupply = this.findBookSupply();
            if (newBookSupply == null) {
                this.print("addons.lotus.messages.villagerTradeNoBookSupply");
                return;
            }
        }

        BlockPos barrel = this.findNearestBarrel();
        if (barrel == null) {
            this.print("addons.lotus.messages.villagerTradeNoEmeraldSupply");
            return;
        }

        List<ItemKey> keys = this.collectPurchaseKeys();
        if (keys.isEmpty()) {
            this.print("addons.lotus.messages.villagerTradeNoPurchase");
            return;
        }

        for (ItemKey key : keys) {
            if (!containers.containsKey(key)) {
                this.print("addons.lotus.messages.villagerTradeMissingUnloadContainer", this.describeKey(key));
                return;
            }
        }

        this.villagers = found;
        this.unloadContainers = containers;
        this.bookSupply = newBookSupply;
        this.emeraldSupply = barrel;
        this.purchaseKeys = keys;
        this.waitingSince = -1L;
        this.offerIndex = 0;
        this.currentConfig = this.cleric;
        this.print("addons.lotus.messages.initializeDone");
    }

    /** 上游 {@code az#N()}：找附近（上下 2 格内）职业匹配、并且工作台旁边有站位可站的村民。 */
    private List<TargetVillager> findVillagers() {
        LocalPlayer player = this.evernightMinecraft.player;
        ClientLevel level = this.evernightMinecraft.level;
        List<TargetVillager> result = new ArrayList<>();
        if (player == null || level == null) {
            return result;
        }

        double playerY = player.position().y;
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof Villager villager)) {
                continue;
            }

            double deltaY = villager.position().y - playerY;
            if (deltaY < -2.0D || deltaY > 2.0D) {
                continue;
            }

            Profession profession = Profession.from(villager.getVillagerData().profession());
            if (profession == null) {
                continue;
            }

            ProfessionConfig config = this.configFor(profession);
            if (!config.enabled() || config.profession() != profession) {
                continue;
            }

            BlockPos standPos = this.findStandPosition(villager, profession);
            if (standPos == null) {
                continue;
            }

            result.add(new TargetVillager(profession, villager.getUUID(), standPos));
        }
        return result;
    }

    /**
     * 上游 {@code az#a_Villager_bj_Direction}：工作台紧挨着村民，站位在工作台再往外一格，
     * 且站位上方是空气。按 EAST → SOUTH → WEST → NORTH 的顺序取第一个能用的。
     */
    private BlockPos findStandPosition(Villager villager, Profession profession) {
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null) {
            return null;
        }

        BlockPos base = villager.blockPosition();
        for (Direction direction : List.of(Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.NORTH)) {
            BlockPos workstationPos = base.relative(direction);
            if (!profession.isWorkstation(level.getBlockState(workstationPos).getBlock())) {
                continue;
            }

            BlockPos standPos = workstationPos.relative(direction);
            if (level.getBlockState(standPos.above()).isAir()) {
                return standPos;
            }
        }
        return null;
    }

    /**
     * 上游 {@code br#a(int)}：扫附近的展示框，把「展示框里的物品」映射到「展示框下 2 格的盒子」。
     * 同一个物品有多个卸货点时取离玩家最近的那个。
     */
    private Map<ItemKey, BlockPos> scanUnloadContainers() {
        LocalPlayer player = this.evernightMinecraft.player;
        ClientLevel level = this.evernightMinecraft.level;
        Map<ItemKey, BlockPos> result = new HashMap<>();
        if (player == null || level == null) {
            return result;
        }

        int range = this.searchRangeSetting.getValue();
        Vec3 playerPosition = player.position();
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class,
                player.getBoundingBox().inflate(range), candidate -> !candidate.getItem().isEmpty())) {
            ItemKey key = ItemKey.of(frame.getItem());
            BlockPos pos = frame.blockPosition().relative(Direction.Axis.Y, -2);
            if (!isUnloadContainer(level.getBlockState(pos))) {
                continue;
            }

            BlockPos existing = result.get(key);
            if (existing == null) {
                result.put(key, pos);
                continue;
            }

            this.print("addons.lotus.messages.multipleUnloadPoints", this.describeKey(key));
            if (pos.getCenter().distanceTo(playerPosition) < existing.getCenter().distanceTo(playerPosition)) {
                result.put(key, pos);
            }
        }

        if (result.isEmpty()) {
            this.print("addons.lotus.messages.noMatchingBoxes");
            return result;
        }

        List<String> names = new ArrayList<>(result.size());
        for (ItemKey key : result.keySet()) {
            names.add(this.describeKey(key));
        }
        this.print("addons.lotus.messages.identifiedContainers", String.join(", ", names));
        return result;
    }

    /** 上游 {@code az#w()}：找一个挂着书的展示框，返回它后面大箱子的另一半。 */
    private BlockPos findBookSupply() {
        LocalPlayer player = this.evernightMinecraft.player;
        ClientLevel level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return null;
        }

        int range = this.searchRangeSetting.getValue();
        for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class,
                player.getBoundingBox().inflate(range), candidate -> candidate.getItem().is(Items.BOOK))) {
            BlockPos partner = this.findDoubleChestPartner(frame);
            if (partner != null) {
                return partner;
            }
        }
        return null;
    }

    /**
     * 上游 {@code br#a_ItemFrame}：展示框贴着的那个箱子必须是双箱，返回另一半的位置。
     *
     * <p>TODO(port): 上游按 {@code ChestType.LEFT/RIGHT} + {@code ChestBlock.FACING} 查表算另一半，
     * 这里改成扫 4 个水平邻居，找「同朝向 + 相反类型」的箱子，结果一样但不用维护方向表。
     */
    private BlockPos findDoubleChestPartner(ItemFrame frame) {
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null) {
            return null;
        }

        // 1.21 里 HangingEntity#getDirection() 就是展示框的朝向（原来叫 getNearestViewDirection）。
        Direction facing = frame.getDirection();
        BlockPos behind = frame.blockPosition().relative(facing.getOpposite());
        BlockState state = level.getBlockState(behind);
        if (!state.is(Blocks.CHEST)) {
            return null;
        }

        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return null;
        }
        ChestType expected = type == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;
        Direction chestFacing = state.getValue(ChestBlock.FACING);

        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos other = behind.relative(direction);
            BlockState otherState = level.getBlockState(other);
            if (!otherState.is(Blocks.CHEST)) {
                continue;
            }
            if (otherState.getValue(ChestBlock.FACING) != chestFacing) {
                continue;
            }
            if (otherState.getValue(ChestBlock.TYPE) != expected) {
                continue;
            }
            return other;
        }
        return null;
    }

    /** 上游 {@code a_Boolean} 里找木桶的那一段（{@code Utils.blockEntities()} + 最近的 BARREL）。 */
    private BlockPos findNearestBarrel() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }

        double range = this.searchRangeSetting.getValue();
        Vec3 playerPosition = player.position();
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BlockEntity blockEntity : WorldUtils.getLoadedBlockEntities()) {
            if (!BlockEntityType.BARREL.equals(blockEntity.getType())) {
                continue;
            }
            double distance = playerPosition.distanceTo(blockEntity.getBlockPos().getCenter());
            if (distance < range && distance < bestDistance) {
                best = blockEntity.getBlockPos();
                bestDistance = distance;
            }
        }
        return best;
    }

    /** 上游 {@code a_List_bi}：把已启用职业的交易物（图书管理员再加附魔书）汇总成要买的物品表。 */
    private List<ItemKey> collectPurchaseKeys() {
        List<ItemKey> keys = new ArrayList<>();
        for (ProfessionConfig config : this.configs) {
            if (!config.enabled()) {
                continue;
            }

            for (Item item : config.tradeItems()) {
                ItemKey key = ItemKey.of(item);
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }

            if (!config.needsBooks()) {
                continue;
            }
            for (ResourceKey<Enchantment> enchantment : this.enchantmentKeys(config.books())) {
                ItemKey key = ItemKey.of(Items.ENCHANTED_BOOK, enchantment);
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys;
    }

    // ------------------------------------------------------------------
    // 补给 / 卸货的决策（上游 az#D / J / K / H）
    // ------------------------------------------------------------------

    /** 上游 {@code az#D()}：这一轮要不要先去补给。 */
    private boolean needsSupply() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }

        int required = Math.min(this.activeConfig().priceLimit() * EMERALDS_PER_PRICE_UNIT, STACK_SIZE);
        int emeralds = this.countInInventory(stack -> stack.is(Items.EMERALD));
        if (player.containerMenu instanceof MerchantMenu merchant) {
            ItemStack payment = merchant.getSlot(PAYMENT_SLOT).getItem();
            if (payment.is(Items.EMERALD)) {
                emeralds += payment.getCount();
            }
        }
        if (emeralds < required) {
            return true;
        }

        if (this.activeConfig().needsBooks()) {
            if (this.countInInventory(stack -> stack.is(Items.BOOK)) < BOOK_BATCH) {
                return true;
            }
            return this.countEmptySlots() < EMPTY_SLOT_BATCH;
        }

        // 不带书的职业：包满了也得先卸货。
        return this.countEmptySlots() <= 0;
    }

    /** 上游 {@code az#K()}：包里有要买的物品就先去卸货，返回是否已经切到卸货流程。 */
    private boolean goUnload() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.purchaseKeys.isEmpty()) {
            return false;
        }

        Vec3 playerPosition = player.position();
        ItemKey bestKey = null;
        BlockPos bestPos = null;
        double bestDistance = Double.MAX_VALUE;
        for (ItemKey key : this.purchaseKeys) {
            if (this.findInventorySlot(key::matches) < 0) {
                continue;
            }
            BlockPos pos = this.unloadContainers.get(key);
            if (pos == null) {
                continue;
            }
            double distance = pos.getCenter().distanceTo(playerPosition);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestKey = key;
                bestPos = pos;
            }
        }

        if (bestKey == null || bestPos == null) {
            return false;
        }

        this.unloadTarget = bestPos;
        this.unloadKey = bestKey;
        // 上游用操作范围 + 1：卸货要站得比补给远一点，避免挡住漏斗。
        this.travel(bestPos, this.operationRangeSetting.getValue() + 1, Step.PutItem,
                "addons.lotus.messages.villagerTradeGoUnload");
        return true;
    }

    /** 上游 {@code az#J()}（GOTO_TAKE_ITEM）的主体：决定去木桶还是去箱子。 */
    private void goSupply() {
        int supplyStacks = this.activeConfig().supplyStacks();
        if (this.countInInventory(stack -> stack.is(Items.EMERALD)) >= STACK_SIZE * supplyStacks) {
            if (this.activeConfig().needsBooks()
                    && this.countInInventory(stack -> stack.is(Items.BOOK)) < STACK_SIZE) {
                this.supplyTarget = this.bookSupply;
                this.supplyItem = Items.BOOK;
                this.travel(this.bookSupply, this.operationRangeSetting.getValue(), Step.TakeItem,
                        "addons.lotus.messages.villagerTradeSupplyBook");
                return;
            }
            this.afterSupply();
            return;
        }

        this.supplyTarget = this.emeraldSupply;
        this.supplyItem = Items.EMERALD;
        this.travel(this.emeraldSupply, this.operationRangeSetting.getValue(), Step.TakeItem,
                "addons.lotus.messages.villagerTradeSupplyEmerald");
    }

    /** 上游 {@code az#H()}：刚交易过就进 WAIT 等下一个窗口，否则直接下一轮。 */
    private void afterSupply() {
        this.step = this.waitingSince > 0L ? Step.Wait : Step.Next;
        this.setDelay();
    }

    /** 上游 {@code az#L()} 里判断「盒子满了」的那段（{@code a_AbstractContainerMenu_toBoolean}）。 */
    private boolean isBoxFull(AbstractContainerMenu menu, ItemKey key) {
        int slots = this.containerSlotCount(menu);
        if (slots <= 0) {
            return false;
        }

        for (int index = 0; index < slots; index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (stack.isEmpty()) {
                return false;
            }
            if (key.matches(stack) && stack.getCount() < stack.getMaxStackSize()) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 走位（上游 aA_#a + Baritone）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code aA_#a(BlockPos, int, be, String)}：超过 1000 格关模块；超过 range 格就下发 Baritone
     * 目标并切到 WALKING（走完去 {@code next}）；否则直接切到 {@code next}。
     */
    private void travel(BlockPos pos, int range, Step next, String messageKey) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || pos == null) {
            this.goTo(next);
            return;
        }

        double distance = player.position().distanceTo(pos.getCenter());
        if (distance > MAX_TRAVEL_DISTANCE) {
            this.print("addons.lotus.messages.tooFar");
            this.toggle();
            return;
        }

        if (distance <= range) {
            this.goTo(next);
            return;
        }

        if (messageKey != null) {
            this.print(messageKey);
        }

        this.walkTarget = pos.immutable();
        this.walkRange = range;
        this.walkNext = next;
        this.pathAttempts = 0;
        this.step = Step.Walking;
        this.setDelay();
        this.startPath();
    }

    private void tickWalking() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.walkTarget == null) {
            this.finishWalking(Step.Next);
            return;
        }

        Step next = this.walkNext != null ? this.walkNext : Step.Next;
        if (player.position().distanceTo(this.walkTarget.getCenter()) <= this.arriveDistance()) {
            this.finishWalking(next);
            return;
        }

        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            this.print("addons.lotus.messages.baritoneUnavailable");
            this.toggle();
            return;
        }

        if (bridge.isPathingOrCalculating()) {
            this.setDelay();
            return;
        }

        // TODO(port): 上游靠 PathEvent.CANCELED 回到待执行状态；这里寻路停了就补发目标，
        //  补满 MAX_PATH_ATTEMPTS 次还到不了就交给下一步自己判距离放弃。
        if (this.pathAttempts >= MAX_PATH_ATTEMPTS) {
            this.print("addons.lotus.messages.villagerTradePathGiveUp");
            this.finishWalking(next);
            return;
        }

        this.pathAttempts++;
        this.startPath();
        this.setDelay();
    }

    /** 「到点」的容差：Baritone 只能吃方块坐标，range 由这里补回来。 */
    private double arriveDistance() {
        return Math.max(this.walkRange, 1.0D);
    }

    private void finishWalking(Step next) {
        this.walkTarget = null;
        this.walkNext = null;
        this.pathAttempts = 0;
        this.goTo(next);
    }

    private void startPath() {
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            this.print("addons.lotus.messages.baritoneUnavailable");
            this.toggle();
            return;
        }
        bridge.pathTo(this.walkTarget);
    }

    private void cancelPathing() {
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge != null && bridge.isAvailable()) {
            bridge.cancelPathing();
        }
    }

    // ------------------------------------------------------------------
    // 容器交互（上游 ag#a_BlockPos_Consumer / bn / bp）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code ag#a_BlockPos_Consumer}：没开就开、开错了就关、内容没同步就等，都对了再执行动作。
     */
    private void withContainer(BlockPos position, Consumer<AbstractContainerMenu> action) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || position == null) {
            return;
        }

        AbstractContainerMenu menu = player.containerMenu;
        if (menu instanceof InventoryMenu || !position.equals(this.openedPos)) {
            if (!(menu instanceof InventoryMenu)) {
                // 别的容器还开着：openContainer 在 screen != null 时什么也不做，先关掉下一 tick 再来。
                this.openedPos = null;
                ContainerInteractionUtils.closeScreen();
                this.setDelay();
                return;
            }

            long now = System.currentTimeMillis();
            if (now - this.lastOpenAttemptAt < OPEN_THROTTLE_MS) {
                this.setDelay();
                return;
            }
            this.lastOpenAttemptAt = now;
            this.openRequestedAt = now;
            this.openedPos = position.immutable();
            ContainerInteractionUtils.openContainer(position, true);
            this.setDelay();
            return;
        }

        // TODO(port): 上游只接受 ChestMenu/ShulkerBoxMenu（木桶会被判成「容器错误」而永远补不到绿宝石），
        //  这里用 menuKind == CONTAINER，木桶/箱子/潜影盒都算容器。
        if (ContainerInteractionUtils.menuKind(menu) != ContainerInventoryMath.MenuKind.CONTAINER) {
            this.openedPos = null;
            ContainerInteractionUtils.closeScreen();
            this.print("addons.lotus.messages.wrongContainer");
            this.setDelay();
            return;
        }

        if (menu.getStateId() <= 0) {
            if (System.currentTimeMillis() - this.openRequestedAt > CONTAINER_SYNC_TIMEOUT_MS) {
                this.print("addons.lotus.messages.containerNotSynced");
                this.openedPos = null;
                ContainerInteractionUtils.closeScreen();
            }
            this.setDelay();
            return;
        }

        action.accept(menu);
    }

    /** 上游 {@code bp#a()}：不是玩家背包界面时关掉当前容器。 */
    private void closeScreenNow() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player != null && !(player.containerMenu instanceof InventoryMenu)) {
            ContainerInteractionUtils.closeScreen();
        }
        this.openedPos = null;
    }

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

    private void shiftClickContainer(int containerSlot) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || containerSlot < 0) {
            return;
        }
        InventoryUtils.clickSlot(player.containerMenu.containerId, containerSlot, 0, ClickType.QUICK_MOVE);
    }

    /** 当前菜单里属于容器的槽位数量（上游 {@code bp#g()} 的通用写法，容器槽永远排在最前）。 */
    private int containerSlotCount(AbstractContainerMenu menu) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int count = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != inventory) {
                count++;
            }
        }
        return count;
    }

    private int findContainerSlot(AbstractContainerMenu menu, Predicate<ItemStack> predicate) {
        int slots = this.containerSlotCount(menu);
        for (int index = 0; index < slots; index++) {
            ItemStack stack = menu.getSlot(index).getItem();
            if (!stack.isEmpty() && predicate.test(stack)) {
                return index;
            }
        }
        return -1;
    }

    /** 背包槽位 → 当前菜单槽位（上游 {@code bp#h()} 那套 {@code SlotUtils.indexToId} 的等价写法）。 */
    private static int findMenuSlotForInventory(AbstractContainerMenu menu, LocalPlayer player, int inventoryIndex) {
        Inventory inventory = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container == inventory && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // 背包工具（上游 ag / bp 的替代）
    // ------------------------------------------------------------------

    private int countInInventory(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            ItemStack stack = inventory.getItem(index);
            if (!stack.isEmpty() && predicate.test(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    /** 上游 {@code ag#a_Predicate}：背包里第一个匹配的槽位（-1 = 没有）。 */
    private int findInventorySlot(Predicate<ItemStack> predicate) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            ItemStack stack = inventory.getItem(index);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return index;
            }
        }
        return -1;
    }

    /** 上游 {@code InvUtils.findEmpty().found() == false} 的反面。 */
    private int findEmptyInventorySlot() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return -1;
        }
        Inventory inventory = player.getInventory();
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            if (inventory.getItem(index).isEmpty()) {
                return index;
            }
        }
        return -1;
    }

    private int countEmptySlots() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return 0;
        }
        Inventory inventory = player.getInventory();
        int count = 0;
        for (int index = 0; index < INVENTORY_SIZE; index++) {
            if (inventory.getItem(index).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /** 上游 {@code bg#b()}：按 UUID 取回村民实体。 */
    private Villager resolveVillager(TargetVillager target) {
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null) {
            return null;
        }
        UUID uuid = target.uuid;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(uuid)) {
                return entity instanceof Villager villager ? villager : null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 交易相关的小工具
    // ------------------------------------------------------------------

    /** 上游 {@code az#C()}：1 = 工作时间(8:00)，2 = 非工作时间(15:00)。 */
    private int currentRound() {
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null) {
            return 2;
        }
        long timeOfDay = level.getDayTime() % DAY_LENGTH;
        return timeOfDay >= DAY_WORK_START && timeOfDay < DAY_WORK_END ? 1 : 2;
    }

    /** 上游 {@code az#f(int)}。 */
    private String roundLabel(int value) {
        return Translations.get(value == 1
                ? "addons.lotus.messages.villagerTradeWorkTime"
                : "addons.lotus.messages.villagerTradeOffWorkTime");
    }

    /** 上游 {@code az#E()} 里那段「结果物品是不是我想要的」。 */
    private boolean isTargetOffer(ItemStack result) {
        if (result.isEmpty()) {
            return false;
        }

        if (this.activeConfig().tradeItems().contains(result.getItem())) {
            return true;
        }

        if (this.activeConfig().profession() != Profession.Librarian || !result.is(Items.ENCHANTED_BOOK)) {
            return false;
        }

        ResourceKey<Enchantment> enchantment = maxLevelEnchantment(result);
        if (enchantment == null) {
            return false;
        }
        return this.activeConfig().books().contains(enchantment.identifier().toString());
    }

    private long dayTime() {
        ClientLevel level = this.evernightMinecraft.level;
        return level == null ? 0L : level.getDayTime();
    }

    /** 上游 {@code bt#a_Vec3_Runnable}：先转过去，再执行动作。 */
    private void rotateAndRun(Vec3 point, Runnable action) {
        ClientContext.rotationManager.requestRotation(
                RotationManager.getRotationsToPosition(point), ROTATION_PRIORITY);
        ClientContext.rotationManager.executeAction(action);
    }

    /** 上游 {@code bm#b_ItemStack_toResourceKey}：附魔书里那个满级的附魔。 */
    private static ResourceKey<Enchantment> maxLevelEnchantment(ItemStack stack) {
        for (var entry : EnchantmentHelper.getEnchantmentsForCrafting(stack).entrySet()) {
            var key = entry.getKey().unwrapKey();
            if (key.isPresent() && entry.getIntValue() >= entry.getKey().value().getMaxLevel()) {
                return key.get();
            }
        }
        return null;
    }

    private Set<ResourceKey<Enchantment>> enchantmentKeys(Set<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        Set<ResourceKey<Enchantment>> keys = new HashSet<>(ids.size());
        for (String id : ids) {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier != null) {
                keys.add(ResourceKey.create(Registries.ENCHANTMENT, identifier));
            }
        }
        return keys;
    }

    private String describeKey(ItemKey key) {
        if (key.enchantment() != null) {
            ClientLevel level = this.evernightMinecraft.level;
            if (level != null) {
                Enchantment enchantment = level.registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT)
                        .getValue(key.enchantment());
                if (enchantment != null) {
                    return enchantment.description().getString();
                }
            }
            return key.enchantment().identifier().toString();
        }
        return key.item().getName().getString();
    }

    private static boolean isUnloadContainer(BlockState state) {
        return SHULKER_BOXES.contains(state.getBlock().asItem());
    }

    // ------------------------------------------------------------------
    // 状态机小工具 / 文本
    // ------------------------------------------------------------------

    private ProfessionConfig activeConfig() {
        if (this.currentConfig == null) {
            this.currentConfig = this.cleric;
        }
        return this.currentConfig;
    }

    private ProfessionConfig configFor(Profession profession) {
        return switch (profession) {
            case Cleric -> this.cleric;
            case Farmer -> this.farmer;
            case Librarian -> this.librarian;
            case Armorer -> this.armorer;
            case Weaponsmith -> this.weaponsmith;
            case Toolsmith -> this.toolsmith;
            default -> this.other;
        };
    }

    /** 上游 {@code aw#c_be} / {@code aw#l()}。 */
    private void goTo(Step next) {
        this.step = next;
        this.setDelay();
    }

    /** 上游 {@code aw#b_be}：先关界面，再切到 {@code next}。 */
    private void closeThen(Step next) {
        this.closeNext = next;
        this.step = Step.CloseScreen;
        this.setDelay();
    }

    /** 上游 {@code ag#l()}。 */
    private void setDelay() {
        this.stepDelay = this.delaySetting.getValue();
    }

    /** 上游 {@code aw#a_()} / {@code d_be}：隔一段时间再重新进入 NEXT。 */
    private void scheduleRestart() {
        GameType mode = this.evernightMinecraft.gameMode == null
                ? null
                : this.evernightMinecraft.gameMode.getPlayerMode();
        if (mode == GameType.SPECTATOR || mode == GameType.ADVENTURE) {
            return;
        }
        this.restartTicks = Math.max(1, this.restartDelaySetting.getValue() * TICKS_PER_SECOND);
    }

    /** 上游 {@code az#b_String_toVoid}：同一条提示 3 秒内只刷一次。 */
    private void printThrottled(String key) {
        long now = System.currentTimeMillis();
        if (now - this.lastMessageAt < MESSAGE_GAP_MS) {
            return;
        }
        this.lastMessageAt = now;
        this.print(key);
    }

    /** 所有玩家可见文本都从语言表取，并统一挂模块签名。 */
    private void print(String key, Object... args) {
        String pattern = Translations.get(key);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    /** 上游那堆 {@code a_String_ObjectArr} 调试日志：只在 Debug 打开时写日志，不刷聊天框。 */
    private void debug(String key, Object... args) {
        if (!this.debugSetting.getValue()) {
            return;
        }
        String pattern = Translations.get(key);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        logger.info("[LotusVillagerTrade] {}", text);
    }

    // ------------------------------------------------------------------
    // 内部类型
    // ------------------------------------------------------------------

    /** 上游 {@code aw#m} 用到的 {@code be} 状态常量。 */
    private enum Step {
        /** {@code bf.a} NONE。 */
        None,
        /** {@code bf.g} WALKING：Baritone 走位中。 */
        Walking,
        /** {@code bf.c} CLOSE_SCREEN：先关界面再切状态。 */
        CloseScreen,
        /** {@code bf.h} NEXT：挑下一个村民。 */
        Next,
        /** {@code bf.v} GOTO_PUT_ITEM：去卸货（卸不了就补给）。 */
        GoToPutItem,
        /** {@code bf.w} PUT_ITEM：把物品塞进盒子。 */
        PutItem,
        /** {@code bf.s} GOTO_TAKE_ITEM：去补给。 */
        GoToTakeItem,
        /** {@code bf.t} TAKE_ITEM：从容器里拿补给物。 */
        TakeItem,
        /** {@code bf.E} OPEN_TRADE：右键村民开交易。 */
        OpenTrade,
        /** {@code bf.F} EXECUTE_TRADE：翻交易并下单。 */
        ExecuteTrade,
        /** {@code bf.f} WAIT：等下一个交易时间窗口。 */
        Wait
    }

    /** 上游 {@code bj}：村民职业 + 工作台 + 默认交易物。 */
    public enum Profession implements NamedEntry {
        Armorer("Armorer", Items.BLAST_FURNACE, VillagerProfession.ARMORER,
                Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS),
        Butcher("Butcher", Items.SMOKER, VillagerProfession.BUTCHER, Items.COOKED_PORKCHOP),
        Cartographer("Cartographer", Items.CARTOGRAPHY_TABLE, VillagerProfession.CARTOGRAPHER, Items.MAP),
        Cleric("Cleric", Items.BREWING_STAND, VillagerProfession.CLERIC,
                Items.EXPERIENCE_BOTTLE, Items.ENDER_PEARL),
        Farmer("Farmer", Items.COMPOSTER, VillagerProfession.FARMER, Items.GOLDEN_CARROT),
        Librarian("Librarian", Items.LECTERN, VillagerProfession.LIBRARIAN, Items.GLASS),
        Mason("Mason", Items.STONECUTTER, VillagerProfession.MASON),
        Shepherd("Shepherd", Items.LOOM, VillagerProfession.SHEPHERD, Items.WHITE_WOOL),
        Toolsmith("Toolsmith", Items.SMITHING_TABLE, VillagerProfession.TOOLSMITH,
                Items.DIAMOND_AXE, Items.DIAMOND_SHOVEL, Items.DIAMOND_PICKAXE),
        Weaponsmith("Weaponsmith", Items.GRINDSTONE, VillagerProfession.WEAPONSMITH,
                Items.DIAMOND_AXE, Items.DIAMOND_SWORD);

        private final String name;
        private final Item workstation;
        private final ResourceKey<VillagerProfession> professionKey;
        private final List<Item> defaultItems;

        Profession(String name, Item workstation, ResourceKey<VillagerProfession> professionKey,
                   Item... defaultItems) {
            this.name = name;
            this.workstation = workstation;
            this.professionKey = professionKey;
            this.defaultItems = List.of(defaultItems);
        }

        @Override
        public String getName() {
            return this.name;
        }

        @Override
        public String getDisplayName() {
            return Translations.get("addons.lotus.names.values." + this.name);
        }

        /** 上游 {@code bj#a()}：工作台方块对应的物品。 */
        private boolean isWorkstation(Block block) {
            return block.asItem() == this.workstation;
        }

        private Item[] defaultItems() {
            return this.defaultItems.toArray(new Item[0]);
        }

        /** 上游 {@code bj#a(Holder)}。 */
        private static Profession from(Holder<VillagerProfession> holder) {
            for (Profession profession : values()) {
                if (holder.is(profession.professionKey)) {
                    return profession;
                }
            }
            return null;
        }
    }

    /**
     * 上游 {@code g}：物品签名。原类还比较「自定义名」，但本模块的构造入口
     * （{@code new g(ItemStack)} / {@code new g(Item)}）永远传 {@code useCustomName = false}，
     * 所以这里没有这个字段。
     */
    private record ItemKey(Item item,
                           ResourceKey<Enchantment> enchantment,
                           PotionContents potion,
                           Integer mapId,
                           OminousBottleAmplifier ominous) {
        private static ItemKey of(ItemStack stack) {
            ResourceKey<Enchantment> enchantment = stack.is(Items.ENCHANTED_BOOK)
                    ? maxLevelEnchantment(stack)
                    : null;
            PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
            if (potion != null && potion.equals(PotionContents.EMPTY)) {
                potion = null;
            }
            MapId map = stack.get(DataComponents.MAP_ID);
            return new ItemKey(stack.getItem(), enchantment, potion,
                    map == null ? null : map.id(),
                    stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER));
        }

        private static ItemKey of(Item item) {
            return new ItemKey(item, null, null, null, null);
        }

        private static ItemKey of(Item item, ResourceKey<Enchantment> enchantment) {
            return new ItemKey(item, enchantment, null, null, null);
        }

        /** 上游 {@code g#b_ItemStack_toBoolean}。 */
        private boolean matches(ItemStack stack) {
            return !stack.isEmpty() && this.equals(of(stack));
        }
    }

    /** 上游 {@code bg}：锁定的村民 + 站位，外加两个可变的时间戳。 */
    private static final class TargetVillager {
        private final Profession profession;
        private final UUID uuid;
        private final BlockPos standPos;
        /** 上游 {@code bg#h}：最后一次交易成功的时间。 */
        private long tradedAt;
        /** 上游 {@code bg#i}：最后一次尝试（开界面失败）的时间。 */
        private long lastAttemptAt;

        private TargetVillager(Profession profession, UUID uuid, BlockPos standPos) {
            this.profession = profession;
            this.uuid = uuid;
            this.standPos = standPos;
        }

        /** 上游 {@code bg#a(long)}。 */
        private void markTraded(long dayTime) {
            this.tradedAt = dayTime;
        }

        /**
         * 上游 {@code G()} 的三个 filter：白天（> 2020）只接受「今天还没交易 / 上一次是凌晨」的村民，
         * 夜里（> 9000）接受「今天还没交易 / 上一次是白天」，凌晨（<= 2020）要求跨天且上次是夜里。
         */
        private boolean isTradable(long day, long timeOfDay) {
            long tradedDay = this.tradedAt / DAY_LENGTH;
            long tradedTimeOfDay = this.tradedAt % DAY_LENGTH;
            if (timeOfDay > DAY_WORK_END) {
                return tradedDay < day || tradedTimeOfDay < DAY_WORK_END_TOLERANCE;
            }
            if (timeOfDay > DAY_EARLY) {
                return tradedDay < day || tradedTimeOfDay < DAY_WORK_START;
            }
            return tradedDay < day && tradedTimeOfDay < DAY_WORK_END_TOLERANCE;
        }
    }

    /**
     * 上游 {@code bi}：一个职业的交易配置。上游是「开关 + 隐藏的 SettingGroup + 5 个设置」，
     * 本体重写成平铺设置；职业下拉只在「其他」组里出现（见类注释）。
     */
    private final class ProfessionConfig {
        private final Profession fixedProfession;
        private final Setting<Boolean> enabled;
        private final Setting<Profession> professionSetting;
        private final Setting<Set<Item>> items;
        private final Setting<Set<String>> books;
        private final Setting<Integer> supply;
        private final Setting<Integer> priceLimit;

        private ProfessionConfig(Profession profession, String prefix, boolean selectable, int defaultSupply) {
            this.fixedProfession = profession;
            this.enabled = VillagerTradeModule.this.add(new BooleanSetting("Trade" + prefix, false));
            this.professionSetting = selectable
                    ? VillagerTradeModule.this.add(
                            new EnumSetting<>(prefix + "Profession", profession).dependsOn(this.enabled))
                    : null;
            this.items = VillagerTradeModule.this.add(
                            new RegistrySetSetting<Item>(prefix + "Items", BuiltInRegistries.ITEM,
                                    profession.defaultItems()))
                    .dependsOn(this.enabled);
            this.books = profession == Profession.Librarian
                    ? VillagerTradeModule.this.add(new StringSetSetting(prefix + "Books")).dependsOn(this.enabled)
                    : null;
            this.supply = VillagerTradeModule.this.add(
                            new NumberSetting<>(prefix + "Supply", defaultSupply, 1, 27))
                    .dependsOn(this.enabled);
            this.priceLimit = VillagerTradeModule.this.add(
                            new NumberSetting<>(prefix + "PriceLimit", 1, 1, 64))
                    .dependsOn(this.enabled);
        }

        /** 上游 {@code bi#b()}。 */
        private boolean enabled() {
            return this.enabled.getValue();
        }

        /** 上游 {@code bi#c()}。 */
        private Profession profession() {
            return this.professionSetting != null
                    ? this.professionSetting.getValue()
                    : this.fixedProfession;
        }

        /** 上游 {@code bi#d()}。 */
        private Set<Item> tradeItems() {
            Set<Item> value = this.items.getValue();
            return value == null ? Set.of() : value;
        }

        /** 上游 {@code bi#g()}：只有图书管理员才有附魔书列表。 */
        private Set<String> books() {
            if (this.books == null || this.profession() != Profession.Librarian) {
                return Set.of();
            }
            Set<String> value = this.books.getValue();
            return value == null ? Set.of() : value;
        }

        /** 上游 {@code bi#a()}：需要靠附魔书交易（而不是普通物品）。 */
        private boolean needsBooks() {
            return this.enabled() && !this.books().isEmpty();
        }

        /** 上游 {@code bi#e()}。 */
        private int supplyStacks() {
            return this.supply.getValue();
        }

        /** 上游 {@code bi#f()}。 */
        private int priceLimit() {
            return this.priceLimit.getValue();
        }
    }
}
