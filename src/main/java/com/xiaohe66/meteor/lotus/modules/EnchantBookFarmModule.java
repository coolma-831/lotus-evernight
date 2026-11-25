package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
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
import evernight.client.settings.types.StringSetSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import evernight.client.utils.world.BlockInteractionUtils;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code ay} (A刷附魔书) from Lotus (Meteor Client addon, author xiaohe66, GPL-3.0).
 *
 * <p>自动刷附魔书：找一个「失业」村民，在村民旁边的岩浆块上方放讲台、等它转职成图书管理员，
 * 然后翻交易列表，命中目标附魔（最高等级且价格不超过上限）就锁定交易并把书收进背包；
 * 没命中就把讲台挖掉、等村民失业后重来。整套逻辑是「状态机 + 每步延迟」：
 * 上游 {@code aw}/{@code ag} 的 {@code m}（当前状态）、{@code o}（关屏幕后要去的状态）、
 * {@code a}（距离下次执行还要等多少 tick）在这里分别对应 {@link Step}、{@code pendingStep}、
 * {@code stepDelay}。
 *
 * <p>移植说明（与原模块的差异都写在这里）：
 * <ul>
 *     <li>走位：上游直接调 Baritone 的 {@code ICustomGoalProcess#setGoalAndPath(new GoalNear(pos, 0))}，
 *     这里换成 {@link BaritoneBridge#pathTo(BlockPos)}，并且先判 {@code isAvailable()}；
 *     模块关闭时 {@code cancelPathing()}。上游在 {@code onActivate/onDeactivate} 里改 Baritone 的
 *     {@code allowBreak/allowPlace}，{@code BaritoneBridge} 没有对应开关，见下方 TODO(port)。</li>
 *     <li>村民：{@code bg}（村民 + 站位 + 讲台位置）换成 {@link TargetVillager} 记录；
 *     {@code bg#b()} 用 UUID 索引取实体，这里遍历已加载实体。</li>
 *     <li>交易：{@code InvUtils}/Meteor 的 {@code Names} 换成 {@link InventoryUtils} 与
 *     {@code Enchantment#description()}；锁定交易照旧发 {@link ServerboundSelectTradePacket}。</li>
 *     <li>目标附魔设置：Meteor 的 {@code EnchantmentListSetting}（注册表多选，带译名）在本体没有对应物
 *     ——{@code RegistrySetSetting} 的默认值要在构造期拿 {@code Enchantment} 实例，而附魔在 1.21 是
 *     数据包注册表，插件初始化时还不一定加载完。这里沿用本插件 {@code AutoHelmetModule} 的做法，
 *     用 {@link StringSetSetting} 存附魔 id（{@code minecraft:mending} 这种），运行期按 id 匹配，
 *     因此数据包自定义附魔也能写进列表，代价是界面上显示的是 id 而不是译名。</li>
 *     <li>聊天：Meteor 的 {@code info/warning/error} 三档统一走
 *     {@code printChat} + 模块签名，文本全部来自语言表 {@code addons.lotus.*}；
 *     上游「调试模式」用 slf4j 打日志，这里同样只写日志（不刷屏）。</li>
 * </ul>
 *
 * <p>TODO(port) 汇总：
 * <ul>
 *     <li>Baritone 的 {@code allowBreak/allowPlace} 开关（{@code aA_#onActivate/onDeactivate}）
 *     在 {@link BaritoneBridge} 上不存在，未迁移。本模块的放/挖讲台走原版发包，不靠 Baritone 破坏，
 *     所以影响仅限于「Baritone 寻路过程中可能顺手拆/放方块」。</li>
 *     <li>上游靠 Baritone 的 {@code PathEvent.CANCELED} 把状态从 WALKING 还原；
 *     {@code BaritoneBridge} 不暴露路径事件，这里改成每个 tick 自己判距离、发现没在寻路就重新下发目标，
 *     路径被取消也能自愈。</li>
 *     <li>{@code aw} 基类还带两个 GUI 设置：{@code 快速停止键}（KeybindSetting，默认 ESC）与
 *     {@code 重启延时}（秒）。前者在本体是模块级键位（模块键位本身就是开关），没有设置级键位；
 *     后者只被「死亡后重启模块」的 {@code aw#d_be} 用到，本模块从不调用，因此都没有迁移，
 *     免得留下不起作用的假设置。</li>
 *     <li>上游 {@code E()}/{@code ag#a_Entity} 会按 {@code ProjectileUtil} 是否命中村民来区分
 *     {@code interactAt}/{@code interact}；这里统一交给
 *     {@link BlockInteractionUtils#interactWithEntity}（内部先 {@code interactAt} 再 {@code interact}），
 *     射线结果只用来决定朝向点。</li>
 *     <li>上游挖讲台用 Meteor 的 {@code BlockUtils#breakBlock(pos, true)}（会自动续挖）；
 *     本体没有等价工具，这里用原版 {@code startDestroyBlock}/{@code continueDestroyBlock} 每 tick 续挖。</li>
 *     <li>上游「讲台位置被占用」时把状态重置成 {@code bf.a}（空处理器），模块从此空转、只能重开；
 *     这里照搬这个行为（{@link Step#Idle}），没有擅自改成自动换村民。</li>
 *     <li>上游取书用 {@code InvUtils.findEmpty()} + {@code InvUtils.move().fromId(2).to(空槽)}；
 *     这里改成 {@code isInventoryFull()} + 对结果槽 shift 点击（{@link ClickType#QUICK_MOVE}），
 *     服务端效果相同（把锁定的书收进背包）。</li>
 * </ul>
 */
public final class EnchantBookFarmModule extends AddonModule {
    /** 上游 {@code bt} 里的转向优先级 6666；本体 100 已经压过绝大多数模块。 */
    private static final int ROTATION_PRIORITY = 100;
    /** 上游 {@code x()} 的到达判定：到站位中心 1.5 格以内就算到了。 */
    private static final double ARRIVE_DISTANCE = 1.5;
    /** 上游 {@code aA_#a(BlockPos, int, be, String)} 里的距离保护。 */
    private static final double MAX_TRAVEL_DISTANCE = 1000.0;
    /** {@code MerchantMenu} 的结果槽（0/1 是付款槽，2 是成品槽）。 */
    private static final int RESULT_SLOT = 2;
    /** 付款槽 A，判断绿宝石时要把这一格也算上。 */
    private static final int PAYMENT_SLOT = 0;
    /** 上游 {@code ay#a}：能用来挖讲台的斧头。 */
    private static final List<Item> AXES = List.of(
            Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE,
            Items.GOLDEN_AXE, Items.STONE_AXE, Items.WOODEN_AXE);

    private final Setting<Set<String>> targetEnchantmentsSetting;
    private final Setting<Integer> maxPriceSetting;
    private final Setting<Integer> searchRangeSetting;
    private final Setting<Integer> professionTimeoutSetting;
    private final Setting<Boolean> removeWhenFoundSetting;
    private final Setting<Boolean> playSoundSetting;
    private final Setting<Boolean> debugSetting;
    private final Setting<Integer> delaySetting;

    /** 上游 {@code aw#m}：当前状态机步骤。 */
    private Step step = Step.Idle;
    /** 上游 {@code aw#o}：{@link Step#CloseScreen} 结束后要切到的步骤。 */
    private Step pendingStep;
    /** 上游 {@code ag#a}：距离下次执行还要等多少 tick。 */
    private int stepDelay;
    /** 上游 {@code ay#y}：当前锁定的失业村民，没有则为 {@code null}。 */
    private TargetVillager target;
    /** 上游 {@code ay#z}：讲台就位（新放好或本来就在）的时间。 */
    private long lecternReadyAt;
    /** 上游 {@code ay#A}：开始挖讲台的时间。 */
    private long lecternBreakAt;
    /** 上游 {@code ay#B}：命中的交易索引。 */
    private int offerIndex = -1;
    /** 正在挖的方块；原版破坏要求 start/continue 成对调用，换目标时要清空。 */
    private BlockPos breakingPos;

    public EnchantBookFarmModule(ModuleCategory category) {
        super("lotus", "LotusEnchantBookFarm",
                "Finds an unemployed villager and rerolls its lectern trades until it offers one of the"
                        + " selected enchantment books at or below the price limit.",
                category);

        // 上游 aw/ag 基类自带的「延迟」（tick）：每个状态步骤之间的间隔。
        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
        this.targetEnchantmentsSetting = this.add(new StringSetSetting("TargetEnchantments",
                "minecraft:protection",
                "minecraft:feather_falling",
                "minecraft:blast_protection",
                "minecraft:respiration",
                "minecraft:aqua_affinity",
                "minecraft:depth_strider",
                "minecraft:thorns",
                "minecraft:sharpness",
                "minecraft:knockback",
                "minecraft:fire_aspect",
                "minecraft:looting",
                "minecraft:sweeping_edge",
                "minecraft:efficiency",
                "minecraft:silk_touch",
                "minecraft:fortune",
                "minecraft:mending",
                "minecraft:unbreaking"));
        this.maxPriceSetting = this.add(new NumberSetting<>("MaxPrice", 26, 1, 64));
        this.searchRangeSetting = this.add(new NumberSetting<>("SearchRange", 32, 8, 64));
        this.professionTimeoutSetting = this.add(
                new NumberSetting<>("ProfessionTimeout", 5000, 1000, 10000).withUnit("ms"));
        this.removeWhenFoundSetting = this.add(new BooleanSetting("RemoveWhenFound", false));
        this.playSoundSetting = this.add(new BooleanSetting("PlaySound", true));
        this.debugSetting = this.add(new BooleanSetting("Debug", false));
    }

    /** 上游 {@code ay#onActivate}。 */
    @Override
    public void onEnable() {
        this.step = Step.FindingTarget;
        this.pendingStep = null;
        this.stepDelay = 0;
        this.target = null;
        this.offerIndex = -1;
        this.breakingPos = null;

        if (this.targetEnchantmentsSetting.getValue().isEmpty()) {
            this.print("addons.lotus.messages.enchantBookFarmNoTargets");
            this.toggle();
            return;
        }
        this.print("addons.lotus.messages.enchantBookFarmStart");
    }

    /** 上游 {@code aw#onDeactivate} + {@code aA_#onDeactivate}。 */
    @Override
    public void onDisable() {
        this.target = null;
        this.step = Step.Idle;
        this.pendingStep = null;
        this.breakingPos = null;
        this.cancelPathing();
    }

    /** 上游 {@code aw#onTick(TickEvent.Pre)}：先扣延迟，再跑当前状态的处理器。 */
    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (this.stepDelay > 0) {
            this.stepDelay--;
            return;
        }

        switch (this.step) {
            case Idle -> {
                // 上游 z()：空处理器。停在这里等玩家自己重开模块（见 TODO(port)）。
            }
            case FindingTarget -> this.tickFindingTarget();
            case GotoTarget -> this.tickGotoTarget();
            case PlaceLectern -> this.tickPlaceLectern();
            case WaitProfession -> this.tickWaitProfession();
            case OpenTrade -> this.tickOpenTrade();
            case CheckTrades -> this.tickCheckTrades();
            case ExecuteTrade -> this.tickExecuteTrade();
            case BreakLectern -> this.tickBreakLectern();
            case WaitProfessionClear -> this.tickWaitProfessionClear();
            case CloseScreen -> this.tickCloseScreen();
        }
    }

    // ------------------------------------------------------------------
    // 状态机：每个 tick 只跑一个步骤，和上游 ay#w 到最后一步一一对应
    // ------------------------------------------------------------------

    /** 上游 {@code ay#w()}（FINDING_TARGET）。 */
    private void tickFindingTarget() {
        if (this.targetEnchantmentsSetting.getValue().isEmpty()) {
            this.print("addons.lotus.messages.enchantBookFarmAllFound");
            this.toggle();
            return;
        }

        TargetVillager found = this.findTargetVillager();
        if (found == null) {
            this.print("addons.lotus.messages.enchantBookFarmNoVillager");
            this.toggle();
            return;
        }

        this.target = found;
        this.debug("addons.lotus.messages.enchantBookFarmFoundVillager", found.uuid(), found.direction());
        this.goTo(Step.GotoTarget);
    }

    /** 上游 {@code ay#x()}（GOTO_TARGET）。 */
    private void tickGotoTarget() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (this.target == null || player == null) {
            this.goTo(Step.FindingTarget);
            return;
        }

        if (player.position().distanceTo(this.target.standPos().getCenter()) <= ARRIVE_DISTANCE) {
            this.goTo(Step.PlaceLectern);
            return;
        }
        this.travelTo(this.target.standPos(), "addons.lotus.messages.enchantBookFarmWalking");
    }

    /** 上游 {@code ay#C()}（PLACE_LECTERN）。 */
    private void tickPlaceLectern() {
        ClientLevel level = this.evernightMinecraft.level;
        if (this.target == null || level == null) {
            this.goTo(Step.FindingTarget);
            return;
        }

        BlockPos lecternPos = this.target.lecternPos();
        BlockState state = level.getBlockState(lecternPos);
        if (!state.isAir()) {
            if (state.getBlock() instanceof LecternBlock) {
                this.lecternReadyAt = System.currentTimeMillis();
                this.goTo(Step.WaitProfession);
            } else {
                // 上游 a_String_toVoid("<讲台位置>被占用")：警告后把状态扔回 NONE，模块就此停住。
                this.print("addons.lotus.messages.enchantBookFarmLecternOccupied");
                this.step = Step.Idle;
            }
            return;
        }

        if (!this.selectLecternForPlacement()) {
            this.setDelay();
            return;
        }
        this.rotateAndRun(lecternPos.getCenter(), () -> this.placeLectern(lecternPos));
    }

    /** 上游 {@code ay#D()}（WAIT_PROFESSION）。 */
    private void tickWaitProfession() {
        if (this.target == null) {
            this.goTo(Step.FindingTarget);
            return;
        }

        Villager villager = this.resolveVillager();
        if (villager == null) {
            this.goTo(Step.FindingTarget);
            return;
        }

        if (System.currentTimeMillis() - this.lecternReadyAt > this.professionTimeoutSetting.getValue()) {
            this.print("addons.lotus.messages.enchantBookFarmProfessionTimeout");
            this.goTo(Step.BreakLectern);
            return;
        }

        if (villager.getVillagerData().profession().is(VillagerProfession.LIBRARIAN)) {
            this.debug("addons.lotus.messages.enchantBookFarmBecameLibrarian");
            this.goTo(Step.OpenTrade);
            return;
        }
        this.setDelay();
    }

    /** 上游 {@code ay#E()}（OPEN_TRADE）。 */
    private void tickOpenTrade() {
        if (this.target == null) {
            this.goTo(Step.FindingTarget);
            return;
        }

        Villager villager = this.resolveVillager();
        if (villager == null) {
            this.goTo(Step.FindingTarget);
            return;
        }

        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        if (!villager.isAlive()) {
            this.print("addons.lotus.messages.enchantBookFarmVillagerDead");
            this.closeAndGoTo(Step.FindingTarget);
            return;
        }

        Vec3 eye = player.getEyePosition();
        Vec3 aim = villager.getEyePosition();
        EntityHitResult hitResult = ProjectileUtil.getEntityHitResult(
                player, eye, aim, villager.getBoundingBox(), Entity::isPickable, eye.distanceToSqr(aim));
        Vec3 aimPoint = hitResult != null ? hitResult.getLocation() : aim;

        this.rotateAndRun(aimPoint, () -> BlockInteractionUtils.interactWithEntity(villager, InteractionHand.MAIN_HAND));
        this.step = Step.CheckTrades;
    }

    /** 上游 {@code ay#F()}（CHECK_TRADES）。 */
    private void tickCheckTrades() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        if (!(player.containerMenu instanceof MerchantMenu menu)) {
            this.goTo(Step.OpenTrade);
            return;
        }

        MerchantOffers offers = menu.getOffers();
        if (offers == null) {
            // 上游没判空（交易列表还没同步时直接 NPE），这里退回去重新开界面。
            this.goTo(Step.OpenTrade);
            return;
        }

        Set<String> targets = this.targetEnchantmentsSetting.getValue();
        for (int index = 0; index < offers.size(); index++) {
            MerchantOffer offer = offers.get(index);
            ItemStack result = offer.getResult();
            if (!result.is(Items.ENCHANTED_BOOK)) {
                continue;
            }
            if (offer.getUses() >= offer.getMaxUses()) {
                continue;
            }

            ItemEnchantments stored = result.get(DataComponents.STORED_ENCHANTMENTS);
            if (stored == null) {
                continue;
            }

            for (var entry : stored.entrySet()) {
                String id = entry.getKey().unwrapKey()
                        .map(key -> key.identifier().toString())
                        .orElse("");
                if (id.isEmpty() || !targets.contains(id)) {
                    continue;
                }

                int level = entry.getIntValue();
                int maxLevel = entry.getKey().value().getMaxLevel();
                if (level < maxLevel) {
                    this.debug("addons.lotus.messages.enchantBookFarmLevelTooLow", id, level, maxLevel);
                    continue;
                }

                int price = offer.getBaseCostA().getCount();
                if (price > this.maxPriceSetting.getValue()) {
                    this.debug("addons.lotus.messages.enchantBookFarmPriceTooHigh", price, this.maxPriceSetting.getValue());
                    continue;
                }

                String name = entry.getKey().value().description().getString();
                this.print("addons.lotus.messages.enchantBookFarmFoundBook", name, price);
                if (this.playSoundSetting.getValue()) {
                    this.evernightMinecraft.getSoundManager().play(
                            SimpleSoundInstance.forUI(SoundEvents.AMETHYST_CLUSTER_BREAK, 1.0F, 1.0F));
                }
                if (this.removeWhenFoundSetting.getValue()) {
                    targets.remove(id);
                    this.debug("addons.lotus.messages.enchantBookFarmRemovedTarget", name);
                }

                this.offerIndex = index;
                this.goTo(Step.ExecuteTrade);
                return;
            }
        }

        this.debug("addons.lotus.messages.enchantBookFarmNoTargetOffer");
        this.closeAndGoTo(Step.BreakLectern);
    }

    /** 上游 {@code ay#G()}（EXECUTE_TRADE）。 */
    private void tickExecuteTrade() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        if (!(player.containerMenu instanceof MerchantMenu menu)) {
            this.goTo(Step.OpenTrade);
            return;
        }

        MerchantOffers offers = menu.getOffers();
        if (offers == null || this.offerIndex < 0 || this.offerIndex >= offers.size()) {
            this.closeAndGoTo(Step.BreakLectern);
            return;
        }

        MerchantOffer offer = offers.get(this.offerIndex);
        int price = offer.getBaseCostA().getCount();

        int emeralds = InventoryUtils.countItems(stack -> stack.is(Items.EMERALD));
        ItemStack payment = menu.getSlot(PAYMENT_SLOT).getItem();
        if (payment.is(Items.EMERALD)) {
            emeralds += payment.getCount();
        }
        if (emeralds < price) {
            this.print("addons.lotus.messages.enchantBookFarmNotEnoughEmeralds", price);
            this.toggle();
            return;
        }

        Optional<ItemCost> secondCost = offer.getItemCostB();
        if (secondCost.isPresent()) {
            ItemStack costStack = secondCost.get().itemStack();
            if (costStack.is(Items.BOOK) && InventoryUtils.findInventorySlot(Items.BOOK) < 0) {
                this.print("addons.lotus.messages.enchantBookFarmMissingBook");
                this.toggle();
                return;
            }
        }

        if (InventoryUtils.isInventoryFull()) {
            this.print("addons.lotus.messages.enchantBookFarmInventoryFull");
            this.toggle();
            return;
        }

        menu.setSelectionHint(this.offerIndex);
        menu.tryMoveItems(this.offerIndex);
        PacketSender.send(new ServerboundSelectTradePacket(this.offerIndex));
        InventoryUtils.clickSlot(RESULT_SLOT, 0, ClickType.QUICK_MOVE);
        this.print("addons.lotus.messages.enchantBookFarmTradeLocked");
        this.closeAndGoTo(Step.FindingTarget);
    }

    /** 上游 {@code ay#H()}（BREAK_LECTERN）。 */
    private void tickBreakLectern() {
        ClientLevel level = this.evernightMinecraft.level;
        if (this.target == null || level == null) {
            this.closeAndGoTo(Step.FindingTarget);
            return;
        }

        BlockPos lecternPos = this.target.lecternPos();
        if (!level.getBlockState(lecternPos).is(Blocks.LECTERN)) {
            this.lecternBreakAt = System.currentTimeMillis();
            this.breakingPos = null;
            this.goTo(Step.WaitProfessionClear);
            return;
        }

        if (!this.ensureAxeInMainHand()) {
            // 上游这里不重置延迟：先换斧头，下一 tick 就开挖。
            return;
        }
        this.rotateAndRun(lecternPos.getCenter(), () -> this.breakBlock(lecternPos));
    }

    /** 上游 {@code ay#I()}（WAIT_PROFESSION_CLEAR）。 */
    private void tickWaitProfessionClear() {
        if (this.target == null) {
            this.closeAndGoTo(Step.FindingTarget);
            return;
        }

        Villager villager = this.resolveVillager();
        if (villager == null) {
            this.closeAndGoTo(Step.FindingTarget);
            return;
        }

        if (System.currentTimeMillis() - this.lecternBreakAt > this.professionTimeoutSetting.getValue()) {
            this.debug("addons.lotus.messages.enchantBookFarmUnemployedTimeout");
            this.closeAndGoTo(Step.FindingTarget);
            return;
        }

        if (villager.getVillagerData().profession().is(VillagerProfession.NONE)) {
            this.debug("addons.lotus.messages.enchantBookFarmVillagerUnemployed");
            this.goTo(Step.PlaceLectern);
            return;
        }
        this.setDelay();
    }

    /** 上游 {@code aw#y()}（CLOSE_SCREEN）：先关容器，再切到 {@code pendingStep}。 */
    private void tickCloseScreen() {
        this.closeMerchantScreen();
        this.step = this.pendingStep != null ? this.pendingStep : Step.FindingTarget;
        this.pendingStep = null;
        this.setDelay();
    }

    // ------------------------------------------------------------------
    // 村民搜索（上游 ay#J()）
    // ------------------------------------------------------------------

    /**
     * 找最近的、满足「水平相邻格下方是岩浆块 + 那格是空气 + 再往外一格头顶是空气」的失业村民。
     *
     * <p>和上游一样：每个村民只看第一个够用的水平朝向，然后在所有候选村民里取距离最近的那个。
     */
    private TargetVillager findTargetVillager() {
        LocalPlayer player = this.evernightMinecraft.player;
        ClientLevel level = this.evernightMinecraft.level;
        if (player == null || level == null) {
            return null;
        }

        TargetVillager best = null;
        double bestDistanceSq = Double.MAX_VALUE;
        int range = this.searchRangeSetting.getValue();

        for (Villager villager : level.getEntitiesOfClass(Villager.class, player.getBoundingBox().inflate(range),
                candidate -> true)) {
            if (!villager.getVillagerData().profession().is(VillagerProfession.NONE)) {
                continue;
            }

            BlockPos base = villager.blockPosition();
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos aboveMagma = base.relative(direction);
                if (!level.getBlockState(aboveMagma.below()).is(Blocks.MAGMA_BLOCK)) {
                    continue;
                }
                if (!level.getBlockState(aboveMagma).isAir()) {
                    continue;
                }
                // 站位在再往外一格，要求头顶是空气（上游只判这一格）。
                BlockPos standPos = aboveMagma.relative(direction);
                if (!level.getBlockState(standPos.above()).isAir()) {
                    continue;
                }

                double distanceSq = player.position().distanceToSqr(base.getCenter());
                if (distanceSq < bestDistanceSq) {
                    bestDistanceSq = distanceSq;
                    best = new TargetVillager(villager.getUUID(), standPos, direction, aboveMagma);
                }
                break;
            }
        }
        return best;
    }

    /** 上游 {@code bg#b()}：按 UUID 取回村民实体。 */
    private Villager resolveVillager() {
        ClientLevel level = this.evernightMinecraft.level;
        if (level == null || this.target == null) {
            return null;
        }
        UUID uuid = this.target.uuid();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.getUUID().equals(uuid)) {
                return entity instanceof Villager villager ? villager : null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 走位 / 放置 / 挖掘 / 交互
    // ------------------------------------------------------------------

    /** 上游 {@code aA_#a(BlockPos, int, be, String)}：用 Baritone 走过去。 */
    private void travelTo(BlockPos pos, String messageKey) {
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge == null || !bridge.isAvailable()) {
            this.print("addons.lotus.messages.enchantBookFarmNoBaritone");
            this.toggle();
            return;
        }

        LocalPlayer player = this.evernightMinecraft.player;
        if (player != null && player.position().distanceTo(pos.getCenter()) > MAX_TRAVEL_DISTANCE) {
            this.print("addons.lotus.messages.enchantBookFarmTooFar");
            this.toggle();
            return;
        }

        // 上游只在「刚开始走」时提示一次，这里用「当前没在寻路」近似同一次下发。
        if (!bridge.isPathingOrCalculating()) {
            this.print(messageKey);
            bridge.pathTo(pos);
        }
        this.setDelay();
    }

    private void cancelPathing() {
        BaritoneBridge bridge = ClientContext.baritoneBridge;
        if (bridge != null && bridge.isAvailable()) {
            bridge.cancelPathing();
        }
    }

    /** 把讲台换到当前选中的快捷栏格子（上游 {@code bp#c(Items.LECTERN)} + {@code K()}）。 */
    private boolean selectLecternForPlacement() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }

        int selected = InventoryUtils.getSelectedSlot();
        if (player.getInventory().getItem(selected).is(Items.LECTERN)) {
            return true;
        }

        int slot = InventoryUtils.findInventorySlot(Items.LECTERN);
        if (slot < 0) {
            this.print("addons.lotus.messages.enchantBookFarmMissingLectern");
            this.toggle();
            return false;
        }

        if (slot < 9) {
            InventoryUtils.selectHotbarSlot(slot);
        } else {
            InventoryUtils.swapIntoSelectedSlot(slot);
        }
        return true;
    }

    private void placeLectern(BlockPos lecternPos) {
        if (BlockInteractionUtils.placeBlock(lecternPos, false, InteractionHand.MAIN_HAND)) {
            this.lecternReadyAt = System.currentTimeMillis();
            this.debug("addons.lotus.messages.enchantBookFarmLecternPlaced");
            this.goTo(Step.WaitProfession);
        } else {
            this.print("addons.lotus.messages.enchantBookFarmLecternPlaceFailed");
            this.setDelay();
        }
    }

    /** 上游 {@code ag#a_Predicate_String(x -> a.contains(item), "未找到<斧头>")}：斧头必须在主手。 */
    private boolean ensureAxeInMainHand() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }

        int slot = InventoryUtils.findInventorySlot(stack -> AXES.contains(stack.getItem()));
        if (slot < 0) {
            this.print("addons.lotus.messages.enchantBookFarmMissingAxe");
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

    /**
     * 上游 {@code BlockUtils#breakBlock(pos, true)}：本体没有可续挖的工具类，
     * 这里用原版 {@code startDestroyBlock}/{@code continueDestroyBlock} 每 tick 续挖同一个位置。
     */
    private void breakBlock(BlockPos pos) {
        if (this.evernightMinecraft.gameMode == null) {
            return;
        }

        Direction side = this.breakSide(pos);
        if (!pos.equals(this.breakingPos)) {
            this.breakingPos = pos;
            this.evernightMinecraft.gameMode.startDestroyBlock(pos, side);
        } else {
            this.evernightMinecraft.gameMode.continueDestroyBlock(pos, side);
        }
    }

    /** 破坏包的朝向：取「方块中心 → 玩家眼睛」最近的轴，等于玩家看到的那一面。 */
    private Direction breakSide(BlockPos pos) {
        LocalPlayer player = this.evernightMinecraft.player;
        Vec3 center = pos.getCenter();
        Vec3 eye = player != null ? player.getEyePosition() : center;
        return Direction.getApproximateNearest(eye.x - center.x, eye.y - center.y, eye.z - center.z);
    }

    /** 上游 {@code bt#a_Vec3_Runnable}：先转过去，再执行动作。 */
    private void rotateAndRun(Vec3 point, Runnable action) {
        ClientContext.rotationManager.requestRotation(
                RotationManager.getRotationsToPosition(point), ROTATION_PRIORITY);
        ClientContext.rotationManager.executeAction(action);
    }

    /** 上游 {@code bp#a()}：关掉当前容器界面。 */
    private void closeMerchantScreen() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player != null && player.containerMenu instanceof MerchantMenu) {
            // LocalPlayer#closeContainer() 既发 ServerboundContainerClosePacket，也会 setScreen(null)。
            player.closeContainer();
        }
    }

    // ------------------------------------------------------------------
    // 状态机小工具 / 文本 / 信息
    // ------------------------------------------------------------------

    /** 上游 {@code aw#c_be}：切状态并重置延迟。 */
    private void goTo(Step next) {
        this.step = next;
        this.setDelay();
    }

    /** 上游 {@code aw#b_be}：先关界面，再切到 {@code next}。 */
    private void closeAndGoTo(Step next) {
        this.pendingStep = next;
        this.step = Step.CloseScreen;
        this.setDelay();
    }

    /** 上游 {@code ag#l()}。 */
    private void setDelay() {
        this.stepDelay = this.delaySetting.getValue();
    }

    /** 所有玩家可见文本都从语言表取，并统一挂模块签名。 */
    private void print(String key, Object... args) {
        String pattern = Translations.get(key);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    /** 上游 {@code ay#b_String_toVoid}：调试信息只写日志，不刷聊天框。 */
    private void debug(String key, Object... args) {
        if (!this.debugSetting.getValue()) {
            return;
        }
        String pattern = Translations.get(key);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        logger.info("[LotusEnchantBookFarm] {}", text);
    }

    /** 上游 {@code ay#getInfoString()}：有没有锁定村民。 */
    @Override
    public String getInfo() {
        return Translations.get(this.target != null
                ? "addons.lotus.messages.enchantBookFarmFarming"
                : "addons.lotus.messages.enchantBookFarmSearching");
    }

    /** 上游 {@code bg}：选中的失业村民 + 站位 + 讲台位置。 */
    private record TargetVillager(UUID uuid, BlockPos standPos, Direction direction, BlockPos lecternPos) {
    }

    /** 上游 {@code bf} 里本模块用到的状态常量。 */
    private enum Step {
        /** {@code bf.a} NONE —— 上游 {@code z()}：空处理器。 */
        Idle,
        /** {@code bf.T} FINDING_TARGET */
        FindingTarget,
        /** {@code bf.U} GOTO_TARGET（上游还会切到 {@code bf.g} WALKING 干等，这里合并进本状态） */
        GotoTarget,
        /** {@code bf.V} PLACE_LECTERN */
        PlaceLectern,
        /** {@code bf.W} WAIT_PROFESSION */
        WaitProfession,
        /** {@code bf.E} OPEN_TRADE */
        OpenTrade,
        /** {@code bf.X} CHECK_TRADES */
        CheckTrades,
        /** {@code bf.F} EXECUTE_TRADE */
        ExecuteTrade,
        /** {@code bf.Y} BREAK_LECTERN */
        BreakLectern,
        /** {@code bf.Z} WAIT_PROFESSION_CLEAR */
        WaitProfessionClear,
        /** {@code bf.c} CLOSE_SCREEN */
        CloseScreen
    }
}
