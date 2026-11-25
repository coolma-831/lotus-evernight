package com.xiaohe66.meteor.lotus.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import evernight.addon.AddonModule;
import evernight.client.ClientAccess;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.managers.player.RotationManager;
import evernight.client.modules.ModuleCategory;
import evernight.client.modules.render.FreeLook.FreeLookModule;
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
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.movement.EntityMotionAccess;
import evernight.client.utils.network.PacketSender;
import evernight.client.utils.world.BlockInteractionUtils;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.util.LayerRange;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Direction.Axis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.arrow.Arrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownExperienceBottle;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.lwjgl.glfw.GLFW;

/**
 * Port of {@code as} (A打印机) from Lotus (Meteor Client addon, author xiaohe66, GPL-3.0).
 *
 * <p>3c 专用打印机，三种打印模式：
 * <ul>
 *     <li><b>投影打印</b>：从 Litematica 的 {@code WorldSchematic} 里读目标方块，把玩家附近（放置范围内）
 *     与投影不一致的位置放成投影里的方块。</li>
 *     <li><b>平台打印</b>：把玩家脚下那一层（识别到的平台 Y）铺成「平台方块」；半砖可选上半砖 / 下半砖。</li>
 *     <li><b>防刷怪</b>：扫描玩家附近每个 X/Z 列的落脚面，凡是「整格净空 + 下方是可刷怪方块」的位置
 *     放一个防刷怪方块（默认铁轨 / 半砖），勾选「防矮小生物」时连只有 1 格净空的位置也放。</li>
 * </ul>
 *
 * <p>移植说明：
 * <ul>
 *     <li>上游继承 {@code ag}（带「延迟」设置的基类）。这里把用到的部分内联：{@code d()} → {@link #passDelay()}、
 *     {@code l()} → {@link #setDelay(int)}，「延迟」设置保留为 {@link #delaySetting}。</li>
 *     <li>上游的 {@code aY_}（打印目标）、{@code bI_}（渲染淡出方块）、{@code aU_}（打印模式）、
 *     {@code aV_}（打印优先级）、{@code aZ_}+{@code ba}（相机相对走位）都改成嵌套类型，各自保留原字段语义。</li>
 *     <li>上游 {@code bn} 的射线 / 面校验 / 命中点计算整体搬进本类（{@link #facePoint}、{@link #findVisiblePoint}、
 *     {@link #findClickDirection} 等）；真正发交互包改用本体
 *     {@link BlockInteractionUtils#sendPlacement}（内部处理「对方块要潜行」的情况）。</li>
 *     <li>上游 {@code bp.a(int, Runnable)}（换手 → 执行 → 换回）用本体
 *     {@link InventoryUtils#runWithSwap} / {@link InventoryUtils#findSwapSlot} 表达。</li>
 *     <li>上游用 {@code bt} 自己发 {@code ServerboundMovePlayerPacket.PosRot} 再点击，这里用
 *     {@link PacketSender#sendRotation} + {@link RotationManager#setRenderRotations} 复刻同样的「先转向再交互」顺序。</li>
 *     <li>上游 {@code br.a_BlockPos_BlockState_toBoolean}（实体碰撞）在本类里重写；「交互方块清单」改用本体
 *     {@link BlockInteractionUtils#isInteractiveBlock}，不再自带一份 13 个类的判断。</li>
 *     <li>Meteor 的 {@code info/warning/error} 三档聊天在 Evernight 只有一个输出通道，全部走
 *     {@code printChat} + 模块签名，文本一律来自语言表 {@code addons.lotus.*}。</li>
 * </ul>
 *
 * <p>Litematica 是<b>可选</b>依赖：所有 Litematica / Malilib 的调用都关在 {@link LitematicaBridge} 这个嵌套类里，
 * 只有 {@link #litematicaLoaded()} 为真时才会加载它，模块类本身加载时不解析 Litematica 的类。
 *
 * <p>已知差异 / TODO(port)（代码里同样标了）：
 * <ul>
 *     <li>上游把「用哪个角度放下这个方块」理解为发一条 {@code PosRot} 再点击；本体 {@code RotationManager}
 *     的静默旋转要等到下一个移动包才带上角度。这里按上游顺序自己发旋转包，但服务端是否接受仍取决于服务器，
 *     <b>需要游戏内验收</b>。</li>
 *     <li>上游用 Litematica 的渲染层范围过滤目标（{@code DataManager.getRenderLayerRange()}），未装 Litematica 时
 *     投影模式会直接提示并关闭模块，不做「无投影也照常打印」的降级。</li>
 *     <li>上游在缺方块时会 {@code return}，把本 tick 已放好的目标留在队列里导致下一 tick 重放；这里改成
 *     「放一个移除一个、缺方块就中断」，保留的是还没放的。</li>
 *     <li>上游 {@code bp.a(int, Runnable)} 换手后不还原、结束时补一条「关闭容器」包；这里统一用
 *     {@link InventoryUtils#runWithSwap}，每次放完还原原槽位。</li>
 *     <li>上游 {@code as#a()} / {@code a_Supplier} / {@code a(boolean)} 是给 3c 流程模块（{@code ac}）用的外部钩子；
 *     那个模块没有移植，所以这里的 {@link #printProjection()} / {@link #setPositionSupplier} /
 *     {@link #setSuppressFreeLookToggle} 目前没有仓库内调用者，只保留签名。</li>
 * </ul>
 */
public final class SchematicPrinterModule extends AddonModule {
    /** 上游 {@code z()}：从脚下往下最多找几格平台面。*/
    private static final int PLATFORM_SEARCH_DEPTH = 5;
    /** 上游 {@code x()} 里 {@code int var10 = Integer.MAX_VALUE} 的哨兵值。*/
    private static final int NO_LAYER = Integer.MAX_VALUE;
    /** 上游 {@code aZ_} 读的物理按键（GLFW 键码）。*/
    private static final int KEY_FORWARD = GLFW.GLFW_KEY_W;
    private static final int KEY_BACK = GLFW.GLFW_KEY_S;
    private static final int KEY_LEFT = GLFW.GLFW_KEY_A;
    private static final int KEY_RIGHT = GLFW.GLFW_KEY_D;

    private final Setting<Double> rangeSetting;
    private final Setting<Integer> blocksPerTickSetting;
    private final Setting<Boolean> freeLookMoveSetting;
    private final Setting<Set<Block>> blacklistSetting;
    private final Setting<Integer> placeTimeoutSetting;
    private final Setting<Integer> faceChecksSetting;
    private final Setting<PrintOrder> prioritySetting;
    private final Setting<PrintMode> modeSetting;
    private final Setting<Set<Block>> platformBlocksSetting;
    private final Setting<Boolean> topSlabSetting;
    private final Setting<Set<Block>> antiSpawnBlocksSetting;
    private final Setting<Boolean> antiSmallMobsSetting;
    private final Setting<Integer> fadeTicksSetting;
    private final Setting<Color> highlightColorSetting;
    private final Setting<Integer> delaySetting;

    /** 上游 {@code aZ_}：相机相对走位的按键重映射器。*/
    private final CameraRelativeMovement cameraMovement = new CameraRelativeMovement();
    /** 上游 {@code v}：渲染淡出中的方块。*/
    private final List<FadeBox> fadingBoxes = new ArrayList<>();
    /** 上游 {@code w}：位置 → 还要冷却多少 tick 才会再尝试。*/
    private final Map<BlockPos, Integer> placementCooldowns = new HashMap<>();
    /** 上游 {@code z}：本 tick 待执行（已选好点击面）的目标。*/
    private final List<PrintTarget> pendingPlacements = new ArrayList<>();
    /** 上游 {@code A}：目标列表的轮转游标，让扫描压力分摊到不同位置。*/
    private int rotationCursor;
    /** 上游 {@code B}：平台打印识别到的平台 Y。*/
    private Integer platformY;
    /** 上游 {@code D}：Litematica 的投影世界（实际类型是 {@code WorldSchematic}，这里只用 {@code Level} 接口）。*/
    private Level schematicWorld;
    /** 上游 {@code E}：外部（3c 流程）给的候选坐标。*/
    private Supplier<List<BlockPos>> positionSupplier;
    /** 上游 {@code y}：外部流程接管时压住 FreeLook 的自动开关。*/
    private boolean suppressFreeLookToggle;
    /** 上游 {@code ag#a}：本模块的「延迟」倒计时。*/
    private int delayTicks;
    /** Litematica 是否可用（{@link #onEnable()} 里解析一次，避免每格都查加载器）。*/
    private boolean litematicaAvailable;

    public SchematicPrinterModule(ModuleCategory category) {
        super("lotus", "LotusSchematicPrinter",
                "3c printer: prints schematics, builds platforms and spawn-proofs areas through Litematica.",
                category);

        this.rangeSetting = this.add(new NumberSetting<>("Range", 4.1, 1.0, 6.0));
        this.blocksPerTickSetting = this.add(new NumberSetting<>("BlocksPerTick", 1, 1, 16));
        this.freeLookMoveSetting = this.add(new BooleanSetting("FreeLookMove", false));
        this.blacklistSetting = this.add(new RegistrySetSetting<Block>("Blacklist", BuiltInRegistries.BLOCK));
        this.placeTimeoutSetting = this.add(new NumberSetting<>("PlaceTimeout", 10, 0, 40));
        this.faceChecksSetting = this.add(new NumberSetting<>("FaceChecksPerTick", 16, 1, 64));
        this.prioritySetting = this.add(new EnumSetting<>("Priority", PrintOrder.Near));
        this.modeSetting = this.add(new EnumSetting<>("Mode", PrintMode.Projection));
        this.platformBlocksSetting = this.add(new RegistrySetSetting<Block>("PlatformBlocks",
                BuiltInRegistries.BLOCK,
                value -> this.modeSetting.getValue() == PrintMode.Platform,
                Blocks.STONE_BRICKS,
                Blocks.STONE_BRICK_SLAB,
                Blocks.SMOOTH_STONE,
                Blocks.SMOOTH_STONE_SLAB));
        this.topSlabSetting = this.add(new BooleanSetting("TopSlab", true,
                value -> this.modeSetting.getValue() == PrintMode.Platform));
        this.antiSpawnBlocksSetting = this.add(new RegistrySetSetting<Block>("AntiSpawnBlocks",
                BuiltInRegistries.BLOCK,
                value -> this.modeSetting.getValue() == PrintMode.AntiSpawn,
                Blocks.ACTIVATOR_RAIL,
                Blocks.COBBLESTONE_SLAB,
                Blocks.DETECTOR_RAIL,
                Blocks.POWERED_RAIL,
                Blocks.RAIL,
                Blocks.STONE_BRICK_SLAB,
                Blocks.SMOOTH_STONE_SLAB));
        this.antiSmallMobsSetting = this.add(new BooleanSetting("AntiSmallMobs", false,
                value -> this.modeSetting.getValue() == PrintMode.AntiSpawn));
        this.fadeTicksSetting = this.add(new NumberSetting<>("FadeTicks", 5, 1, 20));
        this.highlightColorSetting = this.add(new ColorSetting("HighlightColor", new Color(95, 190, 255)));
        // 上游 ag 的「延迟」：每次真正尝试打印之后等这么多 tick。
        this.delaySetting = this.add(new NumberSetting<>("Delay", 0, 0, 40));

        // 上游 h 的 onChanged：勾上就开 FreeLook，取消就还原按键并关 FreeLook。
        this.freeLookMoveSetting.setChangeCallback(() -> {
            if (this.freeLookMoveSetting.getValue()) {
                if (this.isToggled() && !this.suppressFreeLookToggle) {
                    this.enableFreeLook();
                }
            } else {
                this.cameraMovement.reset();
                this.disableFreeLook();
            }
        });
    }

    // ------------------------------------------------------------------
    // Litematica（可选依赖）
    // ------------------------------------------------------------------

    /** 有没有装 Litematica；模块入口注册与类内都按这个判断。*/
    public static boolean litematicaLoaded() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    /**
     * 所有 Litematica / Malilib 的调用都关在这里。
     *
     * <p>这是一个独立的嵌套类（独立的 class 文件），只有在 {@link #litematicaLoaded()} 为真、真的用到它的时候
     * 才会被加载，因此没装 Litematica 的环境里 {@link SchematicPrinterModule} 的加载与构造都不会解析这些类。
     */
    private static final class LitematicaBridge {
        private LitematicaBridge() {
        }

        /** 上游 {@code as#onActivate} 里的 {@code SchematicWorldHandler.getSchematicWorld()}。*/
        static Level schematicWorld() {
            try {
                return SchematicWorldHandler.getSchematicWorld();
            } catch (Throwable ignored) {
                return null;
            }
        }

        /** 上游 {@code DataManager.getRenderLayerRange().getLayerMax()}。*/
        static int renderLayerMax() {
            try {
                LayerRange range = DataManager.getRenderLayerRange();
                return range == null ? Integer.MAX_VALUE : range.getLayerMax();
            } catch (Throwable ignored) {
                return Integer.MAX_VALUE;
            }
        }

        /** 上游 {@code DataManager.getRenderLayerRange().isPositionWithinRange(pos)}。*/
        static boolean isWithinRenderLayer(BlockPos pos) {
            try {
                LayerRange range = DataManager.getRenderLayerRange();
                return range == null || range.isPositionWithinRange(pos);
            } catch (Throwable ignored) {
                return true;
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void onEnable() {
        this.clearState();
        this.litematicaAvailable = litematicaLoaded();

        if (this.modeSetting.getValue() == PrintMode.Projection) {
            // TODO(port): 上游没有「装没装 Litematica」的判断，没加载投影时会在 onTickPost 的 try 里抛 NPE
            //  然后打一条 onTickPost error；这里改成开模块时直接提示并关掉，行为更明确。
            if (!this.litematicaAvailable) {
                this.print("addons.lotus.messages.printerLitematicaMissing");
                this.toggle();
                return;
            }
            this.schematicWorld = LitematicaBridge.schematicWorld();
            if (this.schematicWorld == null) {
                this.print("addons.lotus.messages.printerNoSchematic");
                this.toggle();
                return;
            }
        }

        if (this.modeSetting.getValue() == PrintMode.Platform) {
            this.platformY = this.findPlatformY();
            if (this.platformY == null) {
                this.print("addons.lotus.messages.printerPlatformNotFound");
            }
        }

        if (this.isToggled() && this.freeLookMoveSetting.getValue() && !this.suppressFreeLookToggle) {
            this.enableFreeLook();
        }
    }

    @Override
    public void onDisable() {
        this.clearState();
        this.platformY = null;
        this.positionSupplier = null;
        this.schematicWorld = null;
        // 上游 onDeactivate 里的 x.a()（复位按键）+ bu.g()（关 FreeLook）。
        this.cameraMovement.reset();
        if (this.freeLookMoveSetting.getValue() && !this.suppressFreeLookToggle) {
            this.disableFreeLook();
        }
    }

    // ------------------------------------------------------------------
    // 主循环（上游 as#onTickPost）
    // ------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        // 上游 h()：没有玩家 / 世界就自动关模块。
        if (this.isWorldUnavailable() || this.evernightMinecraft.player == null || this.level() == null) {
            this.toggle();
            return;
        }

        this.updateCameraRelativeMovement();
        this.tickFadingBoxes();
        this.tickCooldowns();

        // 上游 ag#d()：等待「延迟」。
        if (!this.passDelay()) {
            return;
        }

        try {
            PrintMode mode = this.modeSetting.getValue();
            List<PrintTarget> targets;
            if (mode == PrintMode.Projection) {
                targets = this.collectProjectionTargets();
            } else if (mode == PrintMode.Platform) {
                if (this.platformBlocksSetting.getValue().isEmpty()) {
                    this.print("addons.lotus.messages.printerPlatformBlocksEmpty");
                    this.toggle();
                    return;
                }
                targets = this.collectPlatformTargets();
            } else {
                if (this.antiSpawnBlocksSetting.getValue().isEmpty()) {
                    this.print("addons.lotus.messages.printerAntiSpawnBlocksEmpty");
                    this.toggle();
                    return;
                }
                targets = this.collectAntiSpawnTargets();
            }

            this.selectPending(targets);
            this.placePending();
            this.setDelay(this.delaySetting.getValue());
        } catch (Exception error) {
            ClientAccess.logger.error("LotusSchematicPrinter tick failed", error);
            this.print("addons.lotus.messages.printerError", String.valueOf(error.getMessage()));
        }
    }

    private void tickFadingBoxes() {
        // 上游 bI_#a / #b：每个 tick 减 1，到 0 就移除。
        if (this.fadingBoxes.isEmpty()) {
            return;
        }
        for (FadeBox box : this.fadingBoxes) {
            box.ticks--;
        }
        this.fadingBoxes.removeIf(box -> box.ticks <= 0);
    }

    private void tickCooldowns() {
        if (this.placementCooldowns.isEmpty()) {
            return;
        }
        this.placementCooldowns.replaceAll((pos, ticks) -> ticks - 1);
        this.placementCooldowns.entrySet().removeIf(entry -> entry.getValue() <= 0);
    }

    private boolean passDelay() {
        if (this.delayTicks > 0) {
            this.delayTicks--;
            return false;
        }
        return true;
    }

    private void setDelay(int ticks) {
        this.delayTicks = ticks;
    }

    // ------------------------------------------------------------------
    // 相机相对走位（上游 aZ_ + ba）
    // ------------------------------------------------------------------

    private void updateCameraRelativeMovement() {
        if (!this.freeLookMoveSetting.getValue()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        this.cameraMovement.update(this.cameraYaw(player));
    }

    /**
     * 上游 {@code c()}：{@code FreeLook} 开着时用相机视角，否则用玩家实体的视角。
     *
     * <p>本体把相机 yaw 存在 {@code EntityMotionAccess#getCameraYaw()}（{@code CameraMixin} 也是从这里取），
     * 所以这里直接读它，而不是 {@code FreeLookModule#getCameraRotation()}。
     */
    private float cameraYaw(LocalPlayer player) {
        FreeLookModule freeLook = ClientContext.getModule(FreeLookModule.class);
        if (freeLook != null && freeLook.isToggled() && player instanceof EntityMotionAccess motionAccess) {
            return motionAccess.getCameraYaw();
        }
        return player.getYRot();
    }

    private void enableFreeLook() {
        FreeLookModule freeLook = ClientContext.getModule(FreeLookModule.class);
        if (freeLook != null && !freeLook.isToggled()) {
            freeLook.enable();
        }
    }

    private void disableFreeLook() {
        FreeLookModule freeLook = ClientContext.getModule(FreeLookModule.class);
        if (freeLook != null && freeLook.isToggled()) {
            freeLook.disable();
        }
    }

    // ------------------------------------------------------------------
    // 目标收集（上游 A / y / x）
    // ------------------------------------------------------------------

    /** 上游 {@code A()}：投影打印的目标。*/
    private List<PrintTarget> collectProjectionTargets() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null) {
            return List.of();
        }
        if (!this.litematicaAvailable || this.schematicWorld == null) {
            this.print("addons.lotus.messages.printerNoSchematic");
            this.toggle();
            return List.of();
        }

        double range = this.rangeSetting.getValue();
        double rangeSqr = range * range;
        int radius = (int) Math.ceil(range);

        List<BlockPos> positions;
        if (this.positionSupplier != null) {
            positions = this.positionSupplier.get();
            if (positions == null) {
                positions = List.of();
            }
        } else {
            BlockPos origin = player.blockPosition();
            int side = radius * 2 + 1;
            positions = new ArrayList<>(side * side * side);
            // 上游这里 Y 方向是 -radius .. radius-1（少一层），X/Z 是 -radius .. radius（含）；按原样复刻。
            for (int dy = -radius; dy < radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        positions.add(origin.offset(dx, dy, dz));
                    }
                }
            }
        }

        Vec3 eye = player.getEyePosition();
        Level schematic = this.schematicWorld;
        List<PrintTarget> targets = new ArrayList<>();
        for (BlockPos pos : positions) {
            if (!LitematicaBridge.isWithinRenderLayer(pos)) {
                continue;
            }
            double distanceSqr = eye.distanceToSqr(Vec3.atCenterOf(pos));
            if (distanceSqr > rangeSqr) {
                continue;
            }
            PrintTarget target = new PrintTarget(pos, distanceSqr);
            target.states = List.of(schematic.getBlockState(pos));
            target.currentState = level.getBlockState(pos);
            targets.add(target);
        }

        if (this.positionSupplier == null) {
            this.sortByOrder(targets);
        }
        return targets;
    }

    /** 上游 {@code y()}：把平台那一层铺满。*/
    private List<PrintTarget> collectPlatformTargets() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null) {
            return List.of();
        }
        if (this.platformY == null) {
            this.platformY = this.findPlatformY();
            if (this.platformY == null) {
                return List.of();
            }
        }
        // 玩家已经在平台这一层（或更低）就没有可铺的了。
        if (player.getY() <= this.platformY) {
            return List.of();
        }

        BlockPos center = new BlockPos(player.blockPosition().getX(), this.platformY, player.blockPosition().getZ());
        Vec3 eye = player.getEyePosition();
        double rangeSqr = this.rangeSetting.getValue() * this.rangeSetting.getValue();
        SlabType slabType = this.topSlabSetting.getValue() ? SlabType.TOP : SlabType.BOTTOM;
        List<BlockState> states = this.blockStatesWithSlab(this.platformBlocksSetting.getValue(), slabType);

        int radius = this.rangeSetting.getValue().intValue();
        List<PrintTarget> targets = new ArrayList<>();
        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                BlockPos pos = new BlockPos(x, center.getY(), z);
                double distanceSqr = eye.distanceToSqr(Vec3.atCenterOf(pos));
                if (distanceSqr > rangeSqr) {
                    continue;
                }
                PrintTarget target = new PrintTarget(pos, distanceSqr);
                target.states = states;
                target.currentState = level.getBlockState(pos);
                target.fromLevel = true;
                targets.add(target);
            }
        }

        this.sortByOrder(targets);
        return targets;
    }

    /** 上游 {@code x()}：找可刷怪的空格并铺防刷怪方块。*/
    private List<PrintTarget> collectAntiSpawnTargets() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null) {
            return List.of();
        }

        List<BlockState> states = this.blockStatesWithSlab(this.antiSpawnBlocksSetting.getValue(), SlabType.BOTTOM);
        double range = this.rangeSetting.getValue();
        double rangeSqr = range * range;
        int radius = (int) Math.ceil(range);
        Vec3 eye = player.getEyePosition();
        BlockPos center = player.blockPosition();
        boolean allowSmallMobs = this.antiSmallMobsSetting.getValue();

        List<PrintTarget> targets = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int layer = NO_LAYER;
                for (int dy = 1; dy >= -radius; dy--) {
                    BlockPos candidate = center.offset(dx, dy, dz);
                    if (!this.isOpenSpace(candidate)) {
                        continue;
                    }
                    BlockState below = level.getBlockState(candidate.below());
                    if (isSolid(below) && isValidSpawnBlock(below)
                            && (this.hasHeadroom(candidate) || allowSmallMobs)) {
                        layer = dy;
                        break;
                    }
                }
                if (layer == NO_LAYER) {
                    continue;
                }

                BlockPos pos = center.offset(dx, layer, dz);
                double distanceSqr = eye.distanceToSqr(Vec3.atCenterOf(pos));
                if (distanceSqr > rangeSqr) {
                    continue;
                }
                PrintTarget target = new PrintTarget(pos, distanceSqr);
                target.states = states;
                target.currentState = level.getBlockState(pos);
                target.fromLevel = true;
                targets.add(target);
            }
        }

        this.sortByOrder(targets);
        return targets;
    }

    /** 上游 {@code n.get().a_List_toVoid(...)}：近处优先 / 远处优先。*/
    private void sortByOrder(List<PrintTarget> targets) {
        if (this.prioritySetting.getValue() == PrintOrder.Near) {
            targets.sort((left, right) -> Double.compare(left.distanceSqr, right.distanceSqr));
        } else {
            targets.sort((left, right) -> Double.compare(right.distanceSqr, left.distanceSqr));
        }
    }

    /** 把方块集合转成「默认状态 + 半砖改成指定类型」，上游 {@code y()}/{@code x()} 里那段 stream。*/
    private List<BlockState> blockStatesWithSlab(Set<Block> blocks, SlabType slabType) {
        List<BlockState> states = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            BlockState state = block.defaultBlockState();
            if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                state = state.setValue(BlockStateProperties.SLAB_TYPE, slabType);
            }
            states.add(state);
        }
        return states;
    }

    // ------------------------------------------------------------------
    // 选目标（上游 a_List_toVoid）
    // ------------------------------------------------------------------

    private void selectPending(List<PrintTarget> targets) {
        // 上游：本 tick 已经有待执行的目标就不重新挑，直接把它们放完。
        if (!this.pendingPlacements.isEmpty() || targets.isEmpty()) {
            return;
        }

        int faceBudget = this.faceChecksSetting.getValue();
        int size = targets.size();
        int visited = 0;
        PrintMode mode = this.modeSetting.getValue();
        Set<Block> blacklist = this.blacklistSetting.getValue();
        int timeout = this.placeTimeoutSetting.getValue();

        for (int index = 0; index < size && faceBudget > 0; index++) {
            PrintTarget target = targets.get((this.rotationCursor + index) % size);
            visited++;
            BlockPos pos = target.pos;

            // 上游：超出 Litematica 渲染层上限的位置不打印。
            if (pos.getY() > this.renderLayerMax()) {
                continue;
            }

            BlockState current = target.currentState;
            if (current == null) {
                continue;
            }
            if (mode == PrintMode.AntiSpawn) {
                boolean vegetation = current.getBlock() instanceof VegetationBlock;
                if ((!current.isAir() && !vegetation) || this.antiSpawnBlocksSetting.getValue().contains(current.getBlock())) {
                    continue;
                }
            } else if (isSolid(current)) {
                continue;
            }

            if (timeout != 0 && this.placementCooldowns.containsKey(pos)) {
                continue;
            }
            if (!current.canBeReplaced()) {
                continue;
            }

            for (BlockState candidate : target.states) {
                if (candidate == null || candidate.isAir()) {
                    continue;
                }
                Block block = candidate.getBlock();
                if (blacklist.contains(block)) {
                    continue;
                }
                Item item = block.asItem();
                if (!(item instanceof BlockItem)) {
                    continue;
                }
                if (!InventoryUtils.findSwapSlot(item, true).isFound()) {
                    continue;
                }
                // 上游 br.a_BlockPos_BlockState_toBoolean：有实体会卡住这个方块就不放。
                if (this.hasBlockingEntity(pos, candidate)) {
                    continue;
                }
                // 上游 a_aY_BlockState：双人箱只能放离玩家更近的那一半。
                if (this.chestPartTooFar(target, candidate)) {
                    continue;
                }
                // 上游：漏斗要求前方那格能点得到。
                if (candidate.hasProperty(BlockStateProperties.FACING_HOPPER)
                        && this.findHopperHitPoint(pos, candidate) == null) {
                    continue;
                }

                faceBudget--;
                Direction clickDirection = this.findClickDirection(pos, this.rangeSetting.getValue());
                if (clickDirection != null) {
                    target.clickDirection = clickDirection;
                    this.pendingPlacements.add(target);
                }
                break;
            }

            if (this.pendingPlacements.size() >= this.blocksPerTickSetting.getValue()) {
                break;
            }
        }

        this.rotationCursor = (this.rotationCursor + visited) % size;
    }

    private int renderLayerMax() {
        return this.litematicaAvailable ? LitematicaBridge.renderLayerMax() : Integer.MAX_VALUE;
    }

    // ------------------------------------------------------------------
    // 摆放（上游 w）
    // ------------------------------------------------------------------

    private void placePending() {
        if (this.pendingPlacements.isEmpty()) {
            return;
        }

        int timeout = this.placeTimeoutSetting.getValue();
        int previousSlot = InventoryUtils.getSelectedSlot();
        Iterator<PrintTarget> iterator = this.pendingPlacements.iterator();
        while (iterator.hasNext()) {
            PrintTarget target = iterator.next();

            BlockState state = null;
            InventoryUtils.SwapSlot swapSlot = null;
            for (BlockState candidate : target.states) {
                if (candidate == null || candidate.isAir()) {
                    continue;
                }
                Item item = candidate.getBlock().asItem();
                if (!(item instanceof BlockItem)) {
                    continue;
                }
                InventoryUtils.SwapSlot found = InventoryUtils.findSwapSlot(item, true);
                if (!found.isFound()) {
                    continue;
                }
                state = candidate;
                swapSlot = found;
                break;
            }

            if (state == null) {
                // 上游这里直接 return（已放好的目标留在队列里，下一 tick 会被重放）；这里改成中断，
                // 只保留还没放的目标。
                break;
            }

            BlockState placedState = state;
            InventoryUtils.SwapSlot slot = swapSlot;
            PrintTarget placedTarget = target;
            // TODO(port): 上游 bp.a(int, Runnable) 换手之后不还原（只有需要塞进空槽时才换回），结束时还会发
            //  ServerboundContainerClosePacket；这里统一用本体的 runWithSwap（每次放完还原原槽位、不发关容器包）。
            InventoryUtils.runWithSwap(previousSlot, slot, () -> {
                if (this.placeAt(placedTarget, placedState, slot.hand())) {
                    this.fadingBoxes.add(new FadeBox(placedTarget.pos, this.fadeTicksSetting.getValue()));
                }
                this.placementCooldowns.put(placedTarget.pos.immutable(), timeout);
            });
            iterator.remove();
        }
    }

    /** 上游 {@code w()} 里那段「按方块类型决定怎么放」的分支。*/
    private boolean placeAt(PrintTarget target, BlockState state, InteractionHand hand) {
        BlockPos pos = target.pos;
        Block block = state.getBlock();

        if (block instanceof SlabBlock) {
            return this.placeSlab(pos, state, hand);
        }
        if (block instanceof StairBlock) {
            return this.placeStairs(pos, state, hand);
        }
        if (target.fromLevel) {
            return this.placeGeneric(pos, target.clickDirection, hand);
        }
        if (isTorch(block)) {
            return this.placeTorch(pos, state, hand);
        }
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return this.placeFacing(pos, state, BlockStateProperties.FACING, target.clickDirection, hand);
        }
        if (state.hasProperty(BlockStateProperties.FACING_HOPPER)) {
            return this.placeHopper(pos, state, hand);
        }
        if (state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return this.placeFacing(pos, state, BlockStateProperties.HORIZONTAL_FACING, target.clickDirection, hand);
        }
        return this.placeGeneric(pos, target.clickDirection, hand);
    }

    /** 上游 {@code a_aY_BlockPos}：用选好的方向点击；没有就随便找一面。*/
    private boolean placeGeneric(BlockPos pos, Direction dirTowardSupport, InteractionHand hand) {
        if (dirTowardSupport == null) {
            return this.placeAnyFace(pos, hand);
        }
        return this.clickSupportFace(pos, dirTowardSupport, hand);
    }

    /** 上游 {@code bn.d_BlockPos_toBoolean}：自己找一个朝向玩家的支撑方块。*/
    private boolean placeAnyFace(BlockPos pos, InteractionHand hand) {
        Direction dirTowardSupport = this.findAnySupportDirection(pos);
        if (dirTowardSupport == null) {
            return false;
        }
        return this.clickSupportFace(pos, dirTowardSupport, hand);
    }

    /** 上游 {@code bn.c_BlockPos_Direction}：点击 {@code pos.relative(dir)} 朝向目标的那一面。*/
    private boolean clickSupportFace(BlockPos pos, Direction dirTowardSupport, InteractionHand hand) {
        Direction clickedFace = dirTowardSupport.getOpposite();
        BlockPos support = pos.relative(dirTowardSupport);
        Vec3 hit = this.facePoint(support, clickedFace);
        this.applyRotation(hit);
        return this.sendPlacement(pos, hit, clickedFace, hand);
    }

    /** 上游 {@code bn.a_BlockPos_BlockState_toBoolean}：半砖。*/
    private boolean placeSlab(BlockPos pos, BlockState state, InteractionHand hand) {
        if (!state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            return this.placeAnyFace(pos, hand);
        }
        SlabType type = state.getValue(BlockStateProperties.SLAB_TYPE);
        boolean top = type == SlabType.TOP;
        Direction clickedFace = this.findSlabClickFace(pos, top);
        if (clickedFace == null) {
            return false;
        }

        BlockPos support = pos.relative(clickedFace.getOpposite());
        if (type == SlabType.DOUBLE) {
            // 上游 DOUBLE 分支：支撑方块中心 + 0.25 直接当命中点。
            Vec3 hit = Vec3.atCenterOf(support).add(0.0, 0.25, 0.0);
            this.applyRotation(hit);
            return this.sendPlacement(pos, hit, clickedFace, hand);
        }

        // 上游：上半砖点的命中点往上偏 0.25，下半砖往下偏 0.25。
        Vec3 hit = this.facePoint(support, clickedFace).add(0.0, top ? 0.25 : -0.25, 0.0);
        this.applyRotation(hit);
        return this.sendPlacement(pos, hit, clickedFace, hand);
    }

    /** 上游 {@code bn.b_BlockPos_BlockState}：楼梯（要同时控制上下半与朝向）。*/
    private boolean placeStairs(BlockPos pos, BlockState state, InteractionHand hand) {
        if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                || !state.hasProperty(BlockStateProperties.HALF)) {
            return this.placeAnyFace(pos, hand);
        }
        Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        boolean top = state.getValue(BlockStateProperties.HALF) == Half.TOP;
        Direction clickedFace = this.findSlabClickFace(pos, top);
        if (clickedFace == null) {
            return false;
        }

        BlockPos support = pos.relative(clickedFace.getOpposite());
        Vec3 base = Vec3.atCenterOf(support)
                .add(clickedFace.getStepX() * 0.5, clickedFace.getStepY() * 0.5, clickedFace.getStepZ() * 0.5)
                .add(0.0, top ? 0.25 : -0.25, 0.0);
        return this.placeWithOrientation(pos, clickedFace, base, facing, hand);
    }

    /**
     * 上游 {@code bn.a(BlockPos, Direction, Direction, Vec3)}：先转向命中点，再改成
     * 「能摆出指定朝向」的角度，最后点击。
     */
    private boolean placeWithOrientation(BlockPos pos, Direction clickedFace, Vec3 base,
            Direction wantedFacing, InteractionHand hand) {
        Vec3 hit = this.findVisiblePoint(base, clickedFace, true);
        if (hit == null) {
            return false;
        }
        // 上游 bt.a_f(bt.c(hit))：先转向命中点。
        this.applyRotation(hit);
        // 上游 bt.a(yaw, pitch)：再强制成决定方块朝向的角度。
        float yaw = facingYaw(wantedFacing);
        float pitch = wantedFacing == Direction.UP ? -90.0F : wantedFacing == Direction.DOWN ? 90.0F : 5.0F;
        this.applyRotation(yaw, pitch);
        return this.sendPlacement(pos, hit, clickedFace, hand);
    }

    /** 上游 {@code as#a(BlockState, Block, BlockPos, EnumProperty, Direction)}：带朝向的方块。*/
    private boolean placeFacing(BlockPos pos, BlockState state, EnumProperty<Direction> property,
            Direction dirTowardSupport, InteractionHand hand) {
        Direction facing = state.getValue(property);
        // 上游 bn.d_Block：观察者 / 漏斗的朝向要反过来算。
        Direction wanted = isObserverOrHopper(state.getBlock()) ? facing : facing.getOpposite();

        Direction direction = dirTowardSupport;
        if (direction == null) {
            direction = this.findAnySupportDirection(pos);
            if (direction == null) {
                return false;
            }
        }

        Direction clickedFace = direction.getOpposite();
        BlockPos support = pos.relative(direction);
        Vec3 base = Vec3.atCenterOf(support)
                .add(clickedFace.getStepX() * 0.5, clickedFace.getStepY() * 0.5, clickedFace.getStepZ() * 0.5);
        return this.placeWithOrientation(pos, clickedFace, base, wanted, hand);
    }

    /** 上游 {@code bn.c_BlockPos_BlockState}：火把 / 墙上火把。*/
    private boolean placeTorch(BlockPos pos, BlockState state, InteractionHand hand) {
        Block block = state.getBlock();
        if (isWallTorch(block)) {
            if (!state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return this.placeAnyFace(pos, hand);
            }
            Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
            BlockPos support = pos.relative(facing.getOpposite());
            BlockState supportState = this.stateAt(support);
            if (supportState.isAir() || !supportState.getFluidState().isEmpty()) {
                return false;
            }
            Vec3 hit = this.facePoint(support, facing);
            if (!this.canSee(hit)) {
                return false;
            }
            this.applyRotation(hit);
            return this.sendPlacement(pos, hit, facing, hand);
        }

        BlockPos support = pos.below();
        BlockState supportState = this.stateAt(support);
        Level level = this.level();
        boolean canSupport = level != null && !supportState.isAir()
                && Block.canSupportCenter(level, support, Direction.UP);
        if (!canSupport) {
            return false;
        }
        Vec3 hit = this.facePoint(support, Direction.UP);
        if (!this.canSee(hit)) {
            return false;
        }
        this.applyRotation(hit);
        return this.sendPlacement(pos, hit, Direction.UP, hand);
    }

    /** 上游 {@code bn.d_BlockPos_BlockState}：漏斗（朝向由点击面决定）。*/
    private boolean placeHopper(BlockPos pos, BlockState state, InteractionHand hand) {
        Vec3 hit = this.findHopperHitPoint(pos, state);
        if (hit == null) {
            return false;
        }
        Direction facing = state.getValue(BlockStateProperties.FACING_HOPPER);
        Direction clickedFace = facing.getOpposite();
        this.applyRotation(hit);
        return this.sendPlacement(pos, hit, clickedFace, hand);
    }

    /** 上游 {@code bn.e_BlockPos_BlockState}：漏斗前方那格的可见命中点。*/
    private Vec3 findHopperHitPoint(BlockPos pos, BlockState state) {
        if (!state.hasProperty(BlockStateProperties.FACING_HOPPER)) {
            return null;
        }
        Direction facing = state.getValue(BlockStateProperties.FACING_HOPPER);
        BlockPos neighbor = pos.relative(facing);
        BlockState neighborState = this.stateAt(neighbor);
        if (neighborState.isAir() || !neighborState.getFluidState().isEmpty()) {
            return null;
        }
        Direction clickedFace = facing.getOpposite();
        return this.findVisiblePoint(this.facePoint(neighbor, clickedFace), clickedFace, true);
    }

    /** 真正把右键包发出去；同时把镜头转到命中点（上游 {@code bt.a_f}）。*/
    private boolean sendPlacement(BlockPos pos, Vec3 hit, Direction clickedFace, InteractionHand hand) {
        // sendPlacement 的 direction 是「从目标方块指向支撑方块」，也就是点击面的反向。
        BlockInteractionUtils.sendPlacement(pos, hit, clickedFace.getOpposite(), false, hand);
        return true;
    }

    /**
     * 上游 {@code bt.a(float, float)}：先发一条 {@code PosRot}，再让玩家视角跟过去。
     *
     * <p>顺序很重要：服务端是拿「最近一次移动包里的角度」来算方块朝向与放置合法性的。
     */
    private void applyRotation(float yaw, float pitch) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        // TODO(port): 上游发的是 ServerboundMovePlayerPacket.PosRot（Rotations.setCamRotation 同步镜头），
        //  点击前这条包一定已经在服务端生效。这里用 PacketSender.sendRotation 复刻同样的顺序，但服务端是否
        //  认可「自己发的旋转包」取决于反作弊（3c 尤其严格），需要游戏内验收。
        PacketSender.sendRotation(yaw, pitch, player.onGround());
        ClientContext.rotationManager.setRenderRotations(new float[]{yaw, pitch}, 1);
    }

    private void applyRotation(Vec3 hit) {
        float[] rotations = RotationManager.getRotationsToPosition(hit);
        this.applyRotation(rotations[0], rotations[1]);
    }

    // ------------------------------------------------------------------
    // 半砖 / 楼梯的支撑方向（上游 bn.b）
    // ------------------------------------------------------------------

    /**
     * 上游 {@code bn.b(BlockPos, boolean)}：找一个可以点、并且点下去能摆出「上半 / 下半」的点击面。
     *
     * @param top true 表示要摆上半砖 / 上半楼梯（点上方方块的底面）
     * @return 支撑方块上被点击的那一面；找不到返回 {@code null}
     */
    private Direction findSlabClickFace(BlockPos pos, boolean top) {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null) {
            return null;
        }

        Set<Direction> faces = facesToward(player.getEyePosition(), Vec3.atCenterOf(pos));
        if (faces.remove(Direction.DOWN) && top && isSolid(level.getBlockState(pos.above()))) {
            return Direction.DOWN;
        }
        if (faces.remove(Direction.UP) && !top && isSolid(level.getBlockState(pos.below()))) {
            return Direction.UP;
        }

        for (Direction face : faces) {
            BlockState neighbor = level.getBlockState(pos.relative(face.getOpposite()));
            if (!isSolid(neighbor)) {
                continue;
            }
            if (!(neighbor.getBlock() instanceof SlabBlock)) {
                return face;
            }
            if (!neighbor.hasProperty(BlockStateProperties.SLAB_TYPE)) {
                return face;
            }
            SlabType type = neighbor.getValue(BlockStateProperties.SLAB_TYPE);
            if (type == SlabType.DOUBLE
                    || (type == SlabType.BOTTOM && !top)
                    || (type == SlabType.TOP && top)) {
                return face;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 支撑方块 / 命中点（上游 bn 的射线部分）
    // ------------------------------------------------------------------

    /** 上游 {@code bn.h}：随便找一个可以点的支撑方块方向（从目标指向支撑）。*/
    private Direction findAnySupportDirection(BlockPos pos) {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null) {
            return null;
        }

        Set<Direction> faces = facesToward(player.getEyePosition(), Vec3.atCenterOf(pos));
        boolean sneaking = this.isSneakPressed();
        for (Direction direction : Direction.values()) {
            BlockState neighbor = level.getBlockState(pos.relative(direction));
            if (neighbor.isAir()) {
                continue;
            }
            if (neighbor.getBlock() instanceof LiquidBlock) {
                continue;
            }
            // 上游 bn.c_Block：对着箱子 / 铁砧这类方块点会打开界面，只有潜行时才允许。
            if (BlockInteractionUtils.isInteractiveBlock(neighbor.getBlock()) && !sneaking) {
                continue;
            }
            if (!faces.contains(direction.getOpposite())) {
                continue;
            }
            return direction;
        }
        return null;
    }

    /**
     * 上游 {@code bn.a(BlockPos, double, double)}：在放置范围内挑一个看得见的支撑面。
     *
     * @return 从目标方块指向支撑方块的方向；找不到返回 {@code null}
     */
    private Direction findClickDirection(BlockPos pos, double range) {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null) {
            return null;
        }

        Vec3 eye = player.getEyePosition();
        Set<Direction> faces = facesToward(eye, Vec3.atCenterOf(pos));
        Direction bestDirection = null;
        double bestDistanceSqr = Double.MAX_VALUE;
        for (Direction direction : Direction.values()) {
            // 只能点朝向玩家的那一侧（否则点击面在方块背后，服务端会当成非法朝向）。
            if (!faces.contains(direction.getOpposite())) {
                continue;
            }
            BlockPos support = pos.relative(direction);
            BlockState supportState = level.getBlockState(support);
            if (supportState.getShape(level, support).isEmpty()) {
                continue;
            }
            if (supportState.canBeReplaced()) {
                continue;
            }
            Vec3 hit = this.facePoint(support, direction.getOpposite());
            if (!this.canSee(hit)) {
                continue;
            }
            double distanceSqr = eye.distanceToSqr(hit);
            if (Math.sqrt(distanceSqr) > range) {
                continue;
            }
            if (distanceSqr < bestDistanceSqr) {
                bestDistanceSqr = distanceSqr;
                bestDirection = direction;
            }
        }
        return bestDirection;
    }

    /**
     * 上游 {@code bn.g}：方块「某个面」上按碰撞箱算出来的中心点（非整方块会缩进形状里）。
     */
    private Vec3 facePoint(BlockPos pos, Direction face) {
        Level level = this.level();
        if (level == null) {
            return Vec3.atCenterOf(pos);
        }
        VoxelShape shape = level.getBlockState(pos).getShape(level, pos);
        if (shape.isEmpty()) {
            return Vec3.atCenterOf(pos);
        }
        AABB box = shape.bounds();
        double halfX = (box.maxX - box.minX) * 0.5;
        double halfY = (box.maxY - box.minY) * 0.5;
        double halfZ = (box.maxZ - box.minZ) * 0.5;
        double centerX = pos.getX() + box.minX + halfX;
        double centerY = pos.getY() + box.minY + halfY;
        double centerZ = pos.getZ() + box.minZ + halfZ;
        return new Vec3(
                centerX + face.getStepX() * halfX,
                centerY + face.getStepY() * halfY,
                centerZ + face.getStepZ() * halfZ);
    }

    /**
     * 上游 {@code bn.a(Vec3, Direction, boolean)}：命中点被挡住时，在面内做 ±0.25 的微调，取最近的可见点。
     */
    private Vec3 findVisiblePoint(Vec3 base, Direction face, boolean allowVerticalOffset) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        Vec3 eye = player.getEyePosition();
        if (this.canSee(base)) {
            return base;
        }

        double offsetX = face.getAxis() == Axis.X ? 0.0 : 0.25;
        double offsetY = face.getAxis() == Axis.Y ? 0.0 : 0.25;
        double offsetZ = face.getAxis() == Axis.Z ? 0.0 : 0.25;
        if (!allowVerticalOffset) {
            offsetY = 0.0;
        }

        Vec3 best = null;
        double bestDistanceSqr = Double.MAX_VALUE;
        for (double dx : new double[]{-offsetX, 0.0, offsetX}) {
            for (double dy : new double[]{-offsetY, 0.0, offsetY}) {
                for (double dz : new double[]{-offsetZ, 0.0, offsetZ}) {
                    if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
                        continue;
                    }
                    Vec3 candidate = base.add(dx, dy, dz);
                    if (!this.canSee(candidate)) {
                        continue;
                    }
                    double distanceSqr = eye.distanceToSqr(candidate);
                    if (distanceSqr < bestDistanceSqr) {
                        bestDistanceSqr = distanceSqr;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /** 上游 {@code bn.b_Vec3_Vec3}：眼睛到目标点之间没有方块挡住。*/
    private boolean canSee(Vec3 point) {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null || point == null) {
            return false;
        }
        BlockHitResult hit = level.clip(new ClipContext(player.getEyePosition(), point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    /** 上游 {@code bn.a_Vec3_Vec3}：从眼睛看过去，方块的哪几个面朝向玩家。*/
    private static Set<Direction> facesToward(Vec3 eye, Vec3 target) {
        Set<Direction> faces = new HashSet<>(6);
        double deltaX = eye.x - target.x;
        double deltaY = eye.y - target.y;
        double deltaZ = eye.z - target.z;

        if (deltaY > 0.5) {
            faces.add(Direction.UP);
        } else if (deltaY < -0.5) {
            faces.add(Direction.DOWN);
        } else {
            faces.add(Direction.UP);
            faces.add(Direction.DOWN);
        }

        if (deltaX > 0.5) {
            faces.add(Direction.EAST);
        } else if (deltaX < -0.5) {
            faces.add(Direction.WEST);
        } else {
            faces.add(Direction.EAST);
            faces.add(Direction.WEST);
        }

        if (deltaZ > 0.5) {
            faces.add(Direction.SOUTH);
        } else if (deltaZ < -0.5) {
            faces.add(Direction.NORTH);
        } else {
            faces.add(Direction.SOUTH);
            faces.add(Direction.NORTH);
        }

        return faces;
    }

    // ------------------------------------------------------------------
    // 平台 / 防刷怪的方块判定（上游 bn 的方块判定部分）
    // ------------------------------------------------------------------

    /** 上游 {@code z()}：找脚下的平台 Y。*/
    private Integer findPlatformY() {
        LocalPlayer player = this.evernightMinecraft.player;
        Level level = this.level();
        if (player == null || level == null) {
            return null;
        }

        Vec3 position = player.position();
        BlockPos feet = player.blockPosition();
        int stepX = position.x < feet.getX() + 0.5 ? -1 : 1;
        int stepZ = position.z < feet.getZ() + 0.5 ? -1 : 1;

        List<BlockPos> columns = new ArrayList<>(4);
        columns.add(feet);
        columns.add(feet.offset(stepX, 0, 0));
        columns.add(feet.offset(0, 0, stepZ));
        columns.add(feet.offset(stepX, 0, stepZ));

        Integer platformTop = null;
        for (BlockPos column : columns) {
            for (int dy = 0; dy >= -PLATFORM_SEARCH_DEPTH; dy--) {
                BlockPos pos = new BlockPos(column.getX(), feet.getY() + dy, column.getZ());
                if (!this.isStandable(level.getBlockState(pos), pos, position.y)) {
                    continue;
                }
                if (platformTop == null || pos.getY() > platformTop) {
                    platformTop = pos.getY();
                }
                break;
            }
        }
        return platformTop;
    }

    /** 上游 {@code as#a(BlockState, BlockPos, double)}：这一格能不能站住（顶面不高于玩家脚底）。*/
    private boolean isStandable(BlockState state, BlockPos pos, double feetY) {
        Level level = this.level();
        if (level == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        return pos.getY() + shape.max(Axis.Y) <= feetY + 1.0E-4;
    }

    /** 上游 {@code bn.a_BlockState_BlockPos}：碰撞箱为空且不含流体。*/
    private boolean isOpenSpace(BlockPos pos) {
        Level level = this.level();
        if (level == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** 上游 {@code bn.e}：上方一格净空（用来排除只有 1 格净空的位置）。*/
    private boolean hasHeadroom(BlockPos pos) {
        Level level = this.level();
        if (level == null) {
            return false;
        }
        BlockPos above = pos.above();
        return level.getBlockState(above).getCollisionShape(level, above).isEmpty();
    }

    /** 上游 {@code bn.b_BlockState}：非空气且不含流体。*/
    private static boolean isSolid(BlockState state) {
        return !state.isAir() && state.getFluidState().isEmpty();
    }

    /** 上游 {@code BlockUtils.isValidSpawnBlock}（Meteor Client，GPL-3.0）：能否作为刷怪落脚面。*/
    private static boolean isValidSpawnBlock(BlockState state) {
        Block block = state.getBlock();
        if (block == Blocks.BEDROCK
                || block == Blocks.BARRIER
                || block instanceof TransparentBlock
                || block instanceof ScaffoldingBlock) {
            return false;
        }
        if (block == Blocks.SOUL_SAND || block == Blocks.MUD) {
            return true;
        }
        if (block instanceof SlabBlock
                && state.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.TOP) {
            return true;
        }
        if (block instanceof StairBlock
                && state.getValue(BlockStateProperties.HALF) == Half.TOP) {
            return true;
        }
        return state.isSolidRender();
    }

    /** 上游 {@code br.a_BlockPos_BlockState_toBoolean}：这个位置有活着的实体会被卡住。*/
    private boolean hasBlockingEntity(BlockPos pos, BlockState state) {
        Level level = this.level();
        if (level == null) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        shape = shape.move(pos.getX(), pos.getY(), pos.getZ());
        AABB box = new AABB(pos);
        for (Entity entity : level.getEntities((Entity) null, box, entity -> entity.isAlive()
                && !(entity instanceof ItemEntity)
                && !(entity instanceof ExperienceOrb)
                && !(entity instanceof ThrownExperienceBottle)
                && !(entity instanceof Arrow)
                && !(entity instanceof EndCrystal))) {
            if (Shapes.joinIsNotEmpty(shape, Shapes.create(entity.getBoundingBox()), BooleanOp.AND)) {
                return true;
            }
        }
        return false;
    }

    /** 上游 {@code as#a_aY_BlockState}：双人箱的另一半离玩家更近时，不在这里放。*/
    private boolean chestPartTooFar(PrintTarget target, BlockState state) {
        BlockPos other = otherChestHalf(target.pos, state);
        if (other == null || !this.isChestHalfPlaceable(other, state)) {
            return false;
        }
        return target.distanceSqr < this.eyeDistanceSqr(other);
    }

    /** 上游 {@code as#a_BlockPos_BlockState_toBlockPos}：双人箱的另一半位置。*/
    private static BlockPos otherChestHalf(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)) {
            return null;
        }
        if (!state.hasProperty(ChestBlock.TYPE) || !state.hasProperty(ChestBlock.FACING)) {
            return null;
        }
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) {
            return null;
        }
        Direction facing = state.getValue(ChestBlock.FACING);
        Direction side = type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise();
        return pos.relative(side);
    }

    /** 上游 {@code as#b_BlockPos_BlockState}：那一半现在不是箱子、或者是朝向不同的箱子。*/
    private boolean isChestHalfPlaceable(BlockPos pos, BlockState state) {
        BlockState existing = this.stateAt(pos);
        if (!(existing.getBlock() instanceof ChestBlock)) {
            return true;
        }
        if (!existing.hasProperty(ChestBlock.FACING) || !state.hasProperty(ChestBlock.FACING)) {
            return true;
        }
        return existing.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING);
    }

    private double eyeDistanceSqr(BlockPos pos) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return 0.0;
        }
        return player.getEyePosition().distanceToSqr(Vec3.atCenterOf(pos));
    }

    private boolean isSneakPressed() {
        LocalPlayer player = this.evernightMinecraft.player;
        return player != null
                && this.evernightMinecraft.options.keyShift.isDown()
                && player.isShiftKeyDown();
    }

    private Level level() {
        return this.evernightMinecraft.level;
    }

    private BlockState stateAt(BlockPos pos) {
        Level level = this.level();
        return level == null ? Blocks.AIR.defaultBlockState() : level.getBlockState(pos);
    }

    private static boolean isTorch(Block block) {
        return block == Blocks.TORCH
                || block == Blocks.WALL_TORCH
                || block == Blocks.REDSTONE_TORCH
                || block == Blocks.REDSTONE_WALL_TORCH
                || block == Blocks.SOUL_TORCH
                || block == Blocks.SOUL_WALL_TORCH;
    }

    private static boolean isWallTorch(Block block) {
        return block == Blocks.WALL_TORCH
                || block == Blocks.REDSTONE_WALL_TORCH
                || block == Blocks.SOUL_WALL_TORCH;
    }

    private static boolean isObserverOrHopper(Block block) {
        return block == Blocks.OBSERVER || block == Blocks.HOPPER;
    }

    /** 上游 {@code bn.a_Direction_toFloat}：能摆出该水平朝向的 yaw。*/
    private static float facingYaw(Direction direction) {
        if (direction == Direction.NORTH) {
            return 180.0F;
        }
        if (direction == Direction.WEST) {
            return 90.0F;
        }
        if (direction == Direction.EAST) {
            return -90.0F;
        }
        return 0.0F;
    }

    // ------------------------------------------------------------------
    // 渲染（上游 onRender）
    // ------------------------------------------------------------------

    @EventHandler
    private void onRender(WorldRenderEvent.Main event) {
        if (this.fadingBoxes.isEmpty()) {
            return;
        }
        PoseStack poses = event.getPoseStack();
        Color base = this.highlightColorSetting.getValue();
        int fadeTicks = Math.max(1, this.fadeTicksSetting.getValue());
        for (FadeBox box : this.fadingBoxes) {
            int alpha = (int) ((float) box.ticks / fadeTicks * base.getAlpha());
            if (alpha <= 0) {
                continue;
            }
            WorldBoxRenderer.fillBox(poses, box.pos,
                    new Color(base.getRed(), base.getGreen(), base.getBlue(), alpha));
        }
    }

    // ------------------------------------------------------------------
    // 给 3c 流程模块用的外部钩子（上游 bu.h / bu.i 调的三个方法）
    // ------------------------------------------------------------------

    /** 上游 {@code as#a()}：切回投影打印并开启模块。*/
    public void printProjection() {
        if (this.modeSetting.getValue() != PrintMode.Projection) {
            this.modeSetting.setValue(PrintMode.Projection);
        }
        if (!this.isToggled()) {
            this.toggle();
        }
    }

    /** 上游 {@code as#a_Supplier}：外部给一组候选坐标（3c 流程按自己的目标点打印）。*/
    public void setPositionSupplier(Supplier<List<BlockPos>> supplier) {
        this.positionSupplier = supplier;
    }

    /** 上游 {@code as#a(boolean)}：外部流程接管时压住 FreeLook 的自动开关。*/
    public void setSuppressFreeLookToggle(boolean suppress) {
        this.suppressFreeLookToggle = suppress;
    }

    /** 上游 {@code as#b()}：清空待执行 / 冷却 / 渲染状态（不清平台 Y）。*/
    public void clearState() {
        this.fadingBoxes.clear();
        this.placementCooldowns.clear();
        this.pendingPlacements.clear();
        this.rotationCursor = 0;
    }

    // ------------------------------------------------------------------
    // 文本
    // ------------------------------------------------------------------

    private void print(String translationKey, Object... args) {
        String pattern = Translations.get(translationKey);
        String text = args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    // ------------------------------------------------------------------
    // 嵌套类型
    // ------------------------------------------------------------------

    /** 打印模式（上游 {@code aU_}）。*/
    public enum PrintMode implements NamedEntry {
        Projection("Projection"),
        Platform("Platform"),
        AntiSpawn("AntiSpawn");

        private final String name;

        PrintMode(String name) {
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

    /** 打印优先级（上游 {@code aV_}）。*/
    public enum PrintOrder implements NamedEntry {
        Near("Near"),
        Far("Far");

        private final String name;

        PrintOrder(String name) {
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
     * 上游 {@code aY_}（extends {@code d}）：一个待打印的位置。
     *
     * @param pos         目标位置
     * @param distanceSqr 眼睛到方块中心的距离平方（排序 / 双人箱判定用）
     */
    private static final class PrintTarget {
        private final BlockPos pos;
        private final double distanceSqr;
        /** 上游 {@code a}：这个位置可以放成哪些方块状态（按优先级）。*/
        private List<BlockState> states = List.of();
        /** 上游 {@code b}：这个位置当前在世界里的状态。*/
        private BlockState currentState;
        /** 上游 {@code c}：目标是不是「世界扫描」来的（平台 / 防刷怪），这类目标不做朝向特殊处理。*/
        private boolean fromLevel;
        /** 上游 {@code d}：选好的点击方向（从目标方块指向支撑方块）。*/
        private Direction clickDirection;

        private PrintTarget(BlockPos pos, double distanceSqr) {
            this.pos = pos;
            this.distanceSqr = distanceSqr;
        }
    }

    /** 上游 {@code bI_}：渲染淡出中的方块。*/
    private static final class FadeBox {
        private final BlockPos pos;
        private int ticks;

        private FadeBox(BlockPos pos, int ticks) {
            this.pos = pos;
            this.ticks = ticks;
        }
    }

    /**
     * 上游 {@code aZ_} + {@code ba}：相机视角相对走位。
     *
     * <p>FreeLook 只转相机、不转玩家，所以按住 W 时玩家的「前进」还是实体朝向。这里的做法和上游一样：
     * 读物理 WASD，算出「相对相机期望的世界方向」，再把这个方向换算回一组方向键按下去
     * （{@code options.keyUp/...}#setDown），于是移动方向就跟着相机走了。
     */
    private static final class CameraRelativeMovement {
        private KeyCombo current = KeyCombo.NONE;

        private void update(float cameraYaw) {
            LocalPlayer player = ClientAccess.evernightMinecraft.player;
            if (player == null) {
                this.reset();
                return;
            }

            KeyCombo pressed = comboFromKeys();
            if (pressed == KeyCombo.NONE) {
                this.reset();
                return;
            }

            // 相对相机想要的方向 = 按键角度 + 玩家朝向 - 相机朝向。
            float desired = pressed.angle + player.getYRot();
            KeyCombo remapped = nearestCombo(normalizeAngle(desired - cameraYaw));
            if (remapped != this.current) {
                this.apply(remapped);
                this.current = remapped;
            }
        }

        private void reset() {
            this.releaseAll();
            this.current = KeyCombo.NONE;
        }

        private static KeyCombo comboFromKeys() {
            boolean forward = isKeyDown(KEY_FORWARD);
            boolean back = isKeyDown(KEY_BACK);
            boolean left = isKeyDown(KEY_LEFT);
            boolean right = isKeyDown(KEY_RIGHT);

            if (forward && left) {
                return KeyCombo.FORWARD_LEFT;
            }
            if (forward && right) {
                return KeyCombo.FORWARD_RIGHT;
            }
            if (back && left) {
                return KeyCombo.BACK_LEFT;
            }
            if (back && right) {
                return KeyCombo.BACK_RIGHT;
            }
            if (forward) {
                return KeyCombo.FORWARD;
            }
            if (back) {
                return KeyCombo.BACK;
            }
            if (left) {
                return KeyCombo.LEFT;
            }
            return right ? KeyCombo.RIGHT : KeyCombo.NONE;
        }

        private static boolean isKeyDown(int key) {
            long window = ClientAccess.evernightMinecraft.getWindow().handle();
            return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
        }

        private static KeyCombo nearestCombo(float angle) {
            KeyCombo best = KeyCombo.NONE;
            float bestDifference = Float.MAX_VALUE;
            for (KeyCombo combo : KeyCombo.values()) {
                if (combo == KeyCombo.NONE) {
                    continue;
                }
                float difference = Math.abs(normalizeAngle(angle - combo.angle));
                if (difference < bestDifference) {
                    bestDifference = difference;
                    best = combo;
                }
            }
            return best;
        }

        /** 上游 {@code aZ_.b(float)}：把角度折到 [-180, 180)。*/
        private static float normalizeAngle(float angle) {
            float wrapped = angle % 360.0F;
            if (wrapped >= 180.0F) {
                wrapped -= 360.0F;
            }
            if (wrapped < -180.0F) {
                wrapped += 360.0F;
            }
            return wrapped;
        }

        private void apply(KeyCombo combo) {
            this.releaseAll();
            switch (combo) {
                case FORWARD -> ClientAccess.evernightMinecraft.options.keyUp.setDown(true);
                case FORWARD_LEFT -> {
                    ClientAccess.evernightMinecraft.options.keyUp.setDown(true);
                    ClientAccess.evernightMinecraft.options.keyLeft.setDown(true);
                }
                case LEFT -> ClientAccess.evernightMinecraft.options.keyLeft.setDown(true);
                case BACK_LEFT -> {
                    ClientAccess.evernightMinecraft.options.keyDown.setDown(true);
                    ClientAccess.evernightMinecraft.options.keyLeft.setDown(true);
                }
                case BACK -> ClientAccess.evernightMinecraft.options.keyDown.setDown(true);
                case BACK_RIGHT -> {
                    ClientAccess.evernightMinecraft.options.keyDown.setDown(true);
                    ClientAccess.evernightMinecraft.options.keyRight.setDown(true);
                }
                case RIGHT -> ClientAccess.evernightMinecraft.options.keyRight.setDown(true);
                case FORWARD_RIGHT -> {
                    ClientAccess.evernightMinecraft.options.keyUp.setDown(true);
                    ClientAccess.evernightMinecraft.options.keyRight.setDown(true);
                }
                default -> {
                }
            }
        }

        private void releaseAll() {
            ClientAccess.evernightMinecraft.options.keyUp.setDown(false);
            ClientAccess.evernightMinecraft.options.keyDown.setDown(false);
            ClientAccess.evernightMinecraft.options.keyLeft.setDown(false);
            ClientAccess.evernightMinecraft.options.keyRight.setDown(false);
        }
    }

    /** 上游 {@code ba}：8 个方向 + NONE，带各自的相对角度。*/
    private enum KeyCombo {
        FORWARD(0.0F),
        FORWARD_LEFT(45.0F),
        LEFT(90.0F),
        BACK_LEFT(135.0F),
        BACK(180.0F),
        BACK_RIGHT(-135.0F),
        RIGHT(-90.0F),
        FORWARD_RIGHT(-45.0F),
        NONE(0.0F);

        private final float angle;

        KeyCombo(float angle) {
            this.angle = angle;
        }
    }
}
