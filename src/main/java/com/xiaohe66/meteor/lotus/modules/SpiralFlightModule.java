package com.xiaohe66.meteor.lotus.modules;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import evernight.addon.AddonModule;
import evernight.client.ClientAccess;
import evernight.client.ClientContext;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.modules.render.FreeLook.FreeLookModule;
import evernight.client.render.mesh.LineMeshRenderer;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.network.PacketSender;
import java.awt.Color;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * Port of {@code aq} (H螺旋扫图) from Lotus (author xiaohe66, GPL-3.0).
 *
 * <p>以阿基米德螺旋线的方式自动使用 Pitch40 鞘翅飞行扫描地图；核心算法源于
 * &lt;Wandelion&gt; 的 MilkyAddon 中的 SpiralFlight。
 *
 * <p>原模块的飞行状态机由 {@code ag}/{@code aw} 的状态表驱动（{@code bf.h} = 起飞、
 * {@code bf.i} = 飞行），这里改成本类的 {@link State} 枚举 + {@link MinecraftTickEvent}；
 * 螺旋数学来自 {@code aJ_}、Pitch40 俯仰控制来自 {@code aI_}、进度存档来自
 * {@code aG_}/{@code aH_}，全部内联进本类，不依赖原插件的其它类。
 *
 * <p>{@code ag}/{@code aw} 里没有移植的继承逻辑（无效设置、不可达状态、容器辅助方法）见本类中的
 * {@code TODO(port)} 注释。
 */
public final class SpiralFlightModule extends AddonModule {
    private static final double TWO_PI = Math.PI * 2.0;
    /** 渲染螺旋线时每段的角度步长（弧度），与原 {@code aq} 一致。 */
    private static final double RENDER_THETA_STEP = 0.05;
    /** 渲染线宽。 */
    private static final float LINE_WIDTH = 1.0F;
    /** 已飞过的部分画绿色、前方还没飞到的部分画白色（原 aq 的 onRender3D）。 */
    private static final Color PATH_TRAVELED_COLOR = new Color(0, 255, 0, 180);
    private static final Color PATH_REMAINING_COLOR = new Color(255, 255, 255, 180);
    /** 反解 θ 时搜索半径的上限（原 aJ_ 的 5000）。 */
    private static final double MAX_SEARCH_THETA = 5000.0;
    private static final float DIVE_PITCH = 37.72F;
    private static final float CLIMB_PITCH = -54.77F;
    private static final float TAKEOFF_PITCH = -80.0F;
    private static final long START_DELAY_MS = 1000L;
    private static final long FALL_FLYING_PACKET_INTERVAL_MS = 500L;
    private static final long FIREWORK_INTERVAL_MS = 2000L;
    private static final long ELYTRA_SWAP_INTERVAL_MS = 1000L;
    private static final long PROGRESS_AUTOSAVE_INTERVAL_MS = 300_000L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 螺旋中心 X（原「中心坐标」的 X 分量；1.0.3 发行版没有 BlockPos 设置）。 */
    private final Setting<Integer> centerXSetting;
    /** 螺旋中心 Z（原「中心坐标」的 Z 分量）。 */
    private final Setting<Integer> centerZSetting;
    private final Setting<Integer> spacingSetting;
    private final Setting<Integer> heightRangeSetting;
    private final Setting<Integer> minHeightSetting;
    private final Setting<Boolean> clockwiseSetting;
    private final Setting<Boolean> autoElytraSetting;
    private final Setting<Boolean> autoFlySetting;
    private final Setting<Integer> maxRenderLengthSetting;
    private final Setting<Double> turnSensitivitySetting;
    private final Setting<Double> lookaheadSetting;

    private State state = State.NONE;
    private boolean joined;
    private long startAtMillis;
    private long lastFallFlyingPacketAt;
    private long lastFireworkAt;
    private long lastElytraSwapAt;
    private long lastProgressSaveAt;
    /** 原 {@code aq.A}：该服务器/维度上次的扫图进度，进服时读、离开/定时写。 */
    private Progress savedProgress;
    /** 进服时缓存下来的服务器/维度，等价于原 {@code bu} 的静态 server key（离开世界后仍能存档）。 */
    private String sessionServerKey;
    private String sessionDimensionId;

    // 螺旋状态（原 aJ_ 的字段）。
    private double spiralCenterX;
    private double spiralCenterZ;
    /** 每整圈半径增加多少格（螺距 * 16）。 */
    private double spiralBlocksPerTurn;
    private boolean spiralClockwise;
    /** 当前角度 θ，顺时针时为正、逆时针时为负（原 aJ_.n）。 */
    private double spiralTheta;

    // Pitch40 俯仰控制状态（原 aI_ 的字段）。
    private float flightPitch = DIVE_PITCH;
    private boolean leveling;
    private double flightBaseY;
    private boolean needClimb;

    public SpiralFlightModule(ModuleCategory category) {
        super("lotus", "SpiralFlight",
                "Scans the map automatically with an Archimedean-spiral, Pitch40 elytra flight.", category);
        // TODO(port): ag 的「延迟」(默认 5, 0..40) 与 aw 的「重启延时」(默认 2 秒) 只存在于原模块的设置列表里：
        //  aq 的状态回调从不调用 ag.l() 重置延迟计数，并且覆盖了 aw.a_() 用固定 1 秒启动，所以这两个设置在
        //  原模块里本来就不生效；为了不提供“假设置”，这里没有移植，需要的话可以补两个 NumberSetting。
        this.centerXSetting = this.add(new NumberSetting<>("CenterX", 0, -30_000_000, 30_000_000));
        this.centerZSetting = this.add(new NumberSetting<>("CenterZ", 0, -30_000_000, 30_000_000));
        this.spacingSetting = this.add(new NumberSetting<>("Spacing", 64, 10, 640));
        this.heightRangeSetting = this.add(new NumberSetting<>("HeightRange", 40, 40, 80));
        this.minHeightSetting = this.add(new NumberSetting<>("MinHeight", 320, 1, 1024));
        this.clockwiseSetting = this.add(new BooleanSetting("Clockwise", true));
        this.autoElytraSetting = this.add(new BooleanSetting("AutoElytra", true));
        this.autoFlySetting = this.add(new BooleanSetting("AutoFly", true));
        this.maxRenderLengthSetting = this.add(new NumberSetting<>("MaxRenderLength", 2048, 512, 4096));
        this.turnSensitivitySetting = this.add(new NumberSetting<>("TurnSensitivity", 10.0, 0.0, 180.0));
        this.lookaheadSetting = this.add(new NumberSetting<>("Lookahead", 6.0, 1.0, 32.0));
    }

    @Override
    public void onEnable() {
        this.state = State.NONE;
        this.joined = false;
        this.startAtMillis = 0L;
        this.lastFallFlyingPacketAt = 0L;
        this.lastFireworkAt = 0L;
        this.lastElytraSwapAt = 0L;
        this.lastProgressSaveAt = 0L;
        this.savedProgress = null;
        this.sessionServerKey = null;
        this.sessionDimensionId = null;
        this.spiralBlocksPerTurn = 0.0;
        // 原 onActivate() 只在世界里做事：bu.f()（FreeLook 相机）+ a_()（1 秒后进入起飞）。
        if (!this.isWorldUnavailable()) {
            this.enableFreeLook();
            this.joinWorld();
        }
    }

    @Override
    public void onDisable() {
        // 原 onDeactivate()：放掉前进键、关掉 FreeLook、保存进度。
        this.releaseKeys();
        this.disableFreeLook();
        this.saveProgress();
        this.state = State.NONE;
        this.startAtMillis = 0L;
        this.joined = false;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            if (this.joined) {
                // 原 MeteorLotus.onGameLeftEvent 里的 aq.x()：离开世界时保存进度，并放开按住的键。
                this.saveProgress();
                this.releaseKeys();
                this.joined = false;
            }
            this.startAtMillis = 0L;
            this.state = State.NONE;
            return;
        }
        if (!this.joined) {
            // 原 MeteorLotus.onGameJoinedEvent 里的 aq.w()：进服后再读进度并开始。
            this.joinWorld();
        }

        this.tickStartTimer();
        if (this.state == State.NONE) {
            return;
        }
        // TODO(port): aw 的 bf.c（CLOSE_SCREEN）状态及其 y()/bp.a() 关界面逻辑、ag 的容器交互与背包扫描
        //  辅助方法在 aq 的状态表里没有入口（aq 从不进入 bf.c），因此没有移植。
        try {
            if (this.state == State.TAKEOFF) {
                this.tickTakeoff();
            } else {
                this.tickFly();
            }
        } catch (RuntimeException failure) {
            // 原 aw.onTick 捕获异常后提示并关闭模块。
            ClientAccess.logger.error("Spiral flight tick failed", failure);
            this.printMessage("addons.lotus.messages.error", failure.getMessage());
            this.disable();
        }
    }

    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        if (this.isWorldUnavailable() || this.state == State.NONE || this.spiralBlocksPerTurn <= 0.0) {
            return;
        }

        boolean clockwise = this.spiralClockwise;
        double currentTheta = this.spiralTheta;
        double renderY = this.flightBaseY - this.heightRangeSetting.getValue() / 2.0;
        double startTheta;
        double endTheta;
        int maxRenderLength = this.maxRenderLengthSetting.getValue();
        if (maxRenderLength > 0) {
            // 只保留当前位置附近：向前 75%、向后 25% 的长度窗口。
            startTheta = this.walkTheta(currentTheta, clockwise ? -1 : 1, maxRenderLength * 0.25);
            startTheta = clockwise ? Math.max(0.0, startTheta) : Math.min(0.0, startTheta);
            endTheta = this.walkTheta(currentTheta, clockwise ? 1 : -1, maxRenderLength * 0.75);
        } else {
            // 0 = 不限制：从 θ=0 画到当前位置之后两圈。
            endTheta = currentTheta + (clockwise ? TWO_PI * 2.0 : -TWO_PI * 2.0);
            startTheta = 0.0;
        }

        LineMeshRenderer.begin(event.getPoseStack(), LINE_WIDTH);
        double previousX = this.spiralX(startTheta);
        double previousZ = this.spiralZ(startTheta);
        for (double theta = startTheta + (clockwise ? RENDER_THETA_STEP : -RENDER_THETA_STEP);
                clockwise ? theta <= endTheta : theta >= endTheta;
                theta += clockwise ? RENDER_THETA_STEP : -RENDER_THETA_STEP) {
            double x = this.spiralX(theta);
            double z = this.spiralZ(theta);
            boolean passed = clockwise ? theta <= currentTheta : theta >= currentTheta;
            Color color = passed ? PATH_TRAVELED_COLOR : PATH_REMAINING_COLOR;
            LineMeshRenderer.addVertex(previousX, renderY, previousZ, color);
            LineMeshRenderer.addVertex(x, renderY, z, color);
            previousX = x;
            previousZ = z;
        }
        LineMeshRenderer.draw();
    }

    // ---------------------------------------------------------------- 状态机

    private void tickStartTimer() {
        if (this.startAtMillis <= 0L || System.currentTimeMillis() < this.startAtMillis) {
            return;
        }
        this.startAtMillis = 0L;
        this.state = State.TAKEOFF;
        this.printMessage("addons.lotus.messages.start");
    }

    /** 原 a_()/d_be()：非旁观/冒险模式下，1 秒后进入起飞状态。 */
    private void startCountdown() {
        // TODO(port): aw.d_be() 在「定时器到点但还没进服」时会重试并提示「延迟启动」；插件只在启用/进服时
        //  排定启动（onEnable / onTick 的进服分支），这条分支不可达，所以没有保留该提示。
        this.startAtMillis = 0L;
        if (this.evernightMinecraft.gameMode == null) {
            return;
        }
        GameType gameMode = this.evernightMinecraft.gameMode.getPlayerMode();
        if (gameMode == GameType.SPECTATOR || gameMode == GameType.ADVENTURE) {
            return;
        }
        this.startAtMillis = System.currentTimeMillis() + START_DELAY_MS;
    }

    private void joinWorld() {
        this.joined = true;
        this.sessionServerKey = this.currentServerKey();
        this.sessionDimensionId = this.currentDimensionId();
        this.loadProgress();
        this.startCountdown();
    }

    /** 原 C()（状态 bf.h = NEXT）：地面起跳、空中展开鞘翅、拉升到高度后进入飞行。 */
    private void tickTakeoff() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        if (!this.autoFlySetting.getValue()) {
            this.printMessage("addons.lotus.messages.unpowered");
            this.startFlight();
            return;
        }

        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.getItem() != Items.ELYTRA) {
            return;
        }
        if (player.onGround()) {
            this.evernightMinecraft.options.keyJump.setDown(true);
            return;
        }

        this.evernightMinecraft.options.keyJump.setDown(false);
        if (!player.isFallFlying()) {
            if (player.fallDistance > 0.0) {
                long now = System.currentTimeMillis();
                if (now - this.lastFallFlyingPacketAt > FALL_FLYING_PACKET_INTERVAL_MS) {
                    this.lastFallFlyingPacketAt = now;
                    PacketSender.sendStartFallFlying();
                }
            }
            return;
        }

        player.setXRot(TAKEOFF_PITCH);
        if (player.getDeltaMovement().y >= 1.0) {
            return;
        }
        if (player.getY() < this.minHeightSetting.getValue() + this.heightRangeSetting.getValue()) {
            this.useFirework();
        } else if (player.isFallFlying()) {
            this.startFlight();
            this.printMessage("addons.lotus.messages.takeoffComplete");
        }
    }

    /** 原 E()（状态 bf.i = FLY）：Pitch40 姿态控制 + 沿螺旋转向 + 定时存档。 */
    private void tickFly() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        if (this.autoFlySetting.getValue()) {
            if (!player.isFallFlying()) {
                this.state = State.TAKEOFF;
                return;
            }
            this.tickPitchControl();
            this.setForwardKey(true);
            this.checkElytra();
        }
        this.updateSpiral();
        this.checkProgressAutosave();
    }

    /** 原 D()：初始化螺旋（必要时恢复上次进度）并进入飞行状态。 */
    private void startFlight() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        this.resetSpiral(this.centerXSetting.getValue(), this.centerZSetting.getValue(),
                this.spacingSetting.getValue() * 16.0, this.clockwiseSetting.getValue());

        if (this.savedProgress != null
                && this.savedProgress.x == this.centerXSetting.getValue()
                && this.savedProgress.z == this.centerZSetting.getValue()) {
            double theta = this.thetaForPlayer(player.getX(), player.getZ());
            this.spiralTheta = theta;
            this.printMessage("addons.lotus.messages.resumeProgress", theta, this.savedProgress.theta);
        }

        this.startPitchControl();
        this.lastProgressSaveAt = System.currentTimeMillis();
        this.state = State.FLY;
    }

    // ------------------------------------------------------------ 螺旋数学（aJ_）

    private void resetSpiral(double centerX, double centerZ, double blocksPerTurn, boolean clockwise) {
        this.spiralCenterX = centerX;
        this.spiralCenterZ = centerZ;
        this.spiralBlocksPerTurn = blocksPerTurn;
        this.spiralClockwise = clockwise;
        this.spiralTheta = 0.0;
    }

    private double radiusPerRadian() {
        return this.spiralBlocksPerTurn / TWO_PI;
    }

    private double spiralRadius(double theta) {
        double signedTheta = this.spiralClockwise ? theta : -theta;
        return Math.max(0.0, this.radiusPerRadian() * signedTheta);
    }

    private double spiralX(double theta) {
        return this.spiralCenterX + this.spiralRadius(theta) * Math.cos(theta);
    }

    private double spiralZ(double theta) {
        return this.spiralCenterZ + this.spiralRadius(theta) * Math.sin(theta);
    }

    private double distanceSqToSpiral(double theta, double targetX, double targetZ) {
        double dx = this.spiralX(theta) - targetX;
        double dz = this.spiralZ(theta) - targetZ;
        return dx * dx + dz * dz;
    }

    /** 原 aJ_.a(double, double)：由玩家坐标反解最近的角度 θ。 */
    private double thetaForPlayer(double playerX, double playerZ) {
        if (this.spiralBlocksPerTurn <= 0.0) {
            return 0.0;
        }
        double radiusPerRadian = this.radiusPerRadian();
        double sign = this.spiralClockwise ? 1.0 : -1.0;
        double dx = playerX - this.spiralCenterX;
        double dz = playerZ - this.spiralCenterZ;
        double distance = Math.sqrt(dx * dx + dz * dz);
        double maxTheta = Math.max(distance / radiusPerRadian, Math.abs(this.spiralTheta)) + TWO_PI * 2.0;
        maxTheta = Math.min(maxTheta, MAX_SEARCH_THETA);

        double bestTheta = 0.0;
        double bestDistanceSq = Double.MAX_VALUE;
        double step = 0.5;
        int steps = (int) (maxTheta / step) + 100;
        for (int i = 0; i <= steps; i++) {
            double theta = sign * i * step;
            double distanceSq = this.distanceSqToSpiral(theta, playerX, playerZ);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestTheta = theta;
            }
        }
        return this.refineTheta(bestTheta, step * 2.0, 192, 3, playerX, playerZ)[0];
    }

    /** 原 aJ_.a(theta, ...)：先在区间内均匀取样，再做三元搜索细化。 */
    private double[] refineTheta(
            double startTheta, double halfRange, int samples, int iterations, double targetX, double targetZ) {
        double bestTheta = startTheta;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < samples; i++) {
            double theta = startTheta + ((double) i / (samples - 1) - 0.5) * 2.0 * halfRange;
            double distanceSq = this.distanceSqToSpiral(theta, targetX, targetZ);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestTheta = theta;
            }
        }

        double low = bestTheta - halfRange / 6.0;
        double high = bestTheta + halfRange / 6.0;
        for (int i = 0; i < iterations; i++) {
            double third = (high - low) / 3.0;
            double lowerTheta = low + third;
            double upperTheta = high - third;
            double lowerDistanceSq = this.distanceSqToSpiral(lowerTheta, targetX, targetZ);
            double upperDistanceSq = this.distanceSqToSpiral(upperTheta, targetX, targetZ);
            if (lowerDistanceSq < upperDistanceSq) {
                high = upperTheta;
                bestTheta = lowerTheta;
                bestDistanceSq = lowerDistanceSq;
            } else {
                low = lowerTheta;
                bestTheta = upperTheta;
                bestDistanceSq = upperDistanceSq;
            }
        }
        return new double[]{bestTheta, Math.sqrt(bestDistanceSq)};
    }

    /** 原 aJ_ 的 static b(...)：沿螺旋前进「前瞻距离」个采样步，返回转角后的 θ。 */
    private double stepThetaForward(double theta, double lookahead, boolean clockwise) {
        double radiusPerRadian = this.radiusPerRadian();
        double current = theta;
        double traveled = 0.0;
        int guard = 0;
        while (traveled < lookahead && guard++ < 4096) {
            double signedTheta = clockwise ? current : -current;
            double radius = Math.max(0.0, radiusPerRadian * signedTheta);
            double step = 1.5 / Math.max(1.0E-6, Math.sqrt(radius * radius + radiusPerRadian * radiusPerRadian));
            step = clampMagnitude(step, 1.0E-4, 0.25);
            current += clockwise ? step : -step;
            // 原实现按「步数」而不是实际弧长累计 1.0，保持一致。
            traveled += 1.0;
        }
        return current;
    }

    /** 原 aq.a(θ, dir, distance, ...)：按弧长沿螺旋走，返回 θ。 */
    private double walkTheta(double theta, int direction, double arcLength) {
        if (arcLength <= 0.0 || this.spiralBlocksPerTurn <= 0.0) {
            return theta;
        }
        double current = theta;
        double traveled = 0.0;
        double previousX = this.spiralX(current);
        double previousZ = this.spiralZ(current);
        int guard = 0;
        while (traveled < arcLength && guard++ < 100_000) {
            current += direction * RENDER_THETA_STEP;
            if (this.spiralClockwise && current < 0.0) {
                return 0.0;
            }
            if (!this.spiralClockwise && current > 0.0) {
                return 0.0;
            }
            double x = this.spiralX(current);
            double z = this.spiralZ(current);
            double dx = x - previousX;
            double dz = z - previousZ;
            traveled += Math.sqrt(dx * dx + dz * dz);
            previousX = x;
            previousZ = z;
        }
        return current;
    }

    /** 原 aJ_ 的 static c(...)：只允许沿飞行方向把 θ 拉向目标。 */
    private static double advanceThetaToward(double from, double to, double maxStep, boolean clockwise) {
        if (clockwise) {
            if (to < from) {
                to = from;
            }
            double step = Math.min(to - from, maxStep);
            if (step < 0.0) {
                step = 0.0;
            }
            return from + step;
        }
        if (to > from) {
            to = from;
        }
        double step = Math.min(from - to, maxStep);
        if (step < 0.0) {
            step = 0.0;
        }
        return from - step;
    }

    private static double clampMagnitude(double value, double min, double max) {
        double sign = Math.signum(value == 0.0 ? 1.0 : value);
        double magnitude = Math.abs(value);
        if (magnitude < min) {
            magnitude = min;
        }
        if (magnitude > max) {
            magnitude = max;
        }
        return sign * magnitude;
    }

    /** 原 aJ_.a()：每个 tick 更新 θ 并把玩家 yaw 转向螺旋前方。 */
    private void updateSpiral() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.spiralBlocksPerTurn <= 0.0) {
            return;
        }
        double playerX = player.getX();
        double playerZ = player.getZ();
        double radiusPerRadian = this.radiusPerRadian();
        boolean clockwise = this.spiralClockwise;
        double signedTheta = clockwise ? this.spiralTheta : -this.spiralTheta;
        double radius = Math.max(0.0, radiusPerRadian * signedTheta);
        double step = 1.5
                / Math.max(1.0E-6, Math.sqrt(radius * radius + radiusPerRadian * radiusPerRadian));
        step = clampMagnitude(step, 1.0E-4, 0.25);
        double signedStep = clockwise ? step : -step;

        double[] nearest = this.refineTheta(this.spiralTheta, 0.8, 192, 3, playerX, playerZ);
        this.spiralTheta = advanceThetaToward(
                this.spiralTheta, nearest[0], 3.0 * Math.abs(signedStep), clockwise);
        double aheadTheta = this.spiralTheta;
        if (nearest[1] <= 8.0) {
            aheadTheta += (clockwise ? 1.0 : -1.0) * Math.abs(signedStep) * 0.5;
        }

        double lookaheadTheta = this.stepThetaForward(aheadTheta, this.lookaheadSetting.getValue(), clockwise);
        double targetX = this.spiralX(lookaheadTheta);
        double targetZ = this.spiralZ(lookaheadTheta);
        double targetYaw = Math.toDegrees(Math.atan2(targetZ - playerZ, targetX - playerX)) - 90.0;
        this.rotateYaw(targetYaw, this.turnSensitivitySetting.getValue().floatValue());
    }

    private void rotateYaw(double targetYaw, float maxDelta) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        float currentYaw = player.getYRot();
        float delta = Mth.wrapDegrees((float) targetYaw - currentYaw);
        if (maxDelta <= 0.0F) {
            player.setYRot(currentYaw + delta);
        } else {
            player.setYRot(currentYaw + Mth.clamp(delta, -maxDelta, maxDelta));
        }
    }

    // ------------------------------------------------------ Pitch40 控制（aI_）

    private void startPitchControl() {
        this.flightPitch = DIVE_PITCH;
        this.flightBaseY = this.evernightMinecraft.player.getY();
        this.needClimb = true;
        this.leveling = false;
    }

    private double flightUpperBound() {
        return this.flightBaseY - this.heightRangeSetting.getValue();
    }

    /** 原 aI_.b()：抬头爬升到上限、平飞下压、过低时用烟花托底。 */
    private void tickPitchControl() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        double y = player.getY();
        if (this.leveling && y < this.flightUpperBound()) {
            this.leveling = false;
        }
        if (y < this.minHeightSetting.getValue()) {
            this.needClimb = true;
        }

        if (this.leveling) {
            if (this.flightPitch < DIVE_PITCH) {
                this.flightPitch += randomAround(0.9F, 0.5F);
            }
        } else {
            if (this.flightPitch > CLIMB_PITCH) {
                this.flightPitch -= randomAround(5.45F, 1.0F);
                if (this.flightPitch < CLIMB_PITCH) {
                    this.flightPitch = CLIMB_PITCH;
                }
            }
            if (this.flightPitch <= CLIMB_PITCH) {
                if (!this.needClimb) {
                    this.leveling = true;
                } else if (player.getDeltaMovement().y < 1.0) {
                    if (y > this.minHeightSetting.getValue() + this.heightRangeSetting.getValue()) {
                        this.needClimb = false;
                        this.flightBaseY = y;
                        this.leveling = true;
                        this.printMessage("addons.lotus.messages.altitudeAdjusted", y);
                    } else {
                        this.useFirework();
                    }
                }
            }
        }
        player.setXRot(this.flightPitch);
    }

    private static float randomAround(float base, float spread) {
        return (float) (base + spread * (Math.random() - 0.5));
    }

    // ------------------------------------------------------------- 背包 / 烟花

    /** 原 aI_.c()：每 2 秒尝试用一次烟花把自己拉高。 */
    private void useFirework() {
        long now = System.currentTimeMillis();
        if (now - this.lastFireworkAt <= FIREWORK_INTERVAL_MS) {
            return;
        }
        if (!this.prepareFirework()) {
            return;
        }
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.evernightMinecraft.gameMode == null) {
            return;
        }
        this.printMessage("addons.lotus.messages.fireworkClimb", player.getY());
        this.lastFireworkAt = now;
        this.evernightMinecraft.gameMode.useItem(player, InteractionHand.MAIN_HAND);
    }

    /** 原 aI_.f()/g()：把烟花拿在手上；需要切槽时返回 false，下一 tick 再放。 */
    private boolean prepareFirework() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return false;
        }
        if (player.getMainHandItem().getItem() == Items.FIREWORK_ROCKET) {
            return true;
        }
        int hotbarSlot = InventoryUtils.findHotbarSlot(stack -> stack.getItem() == Items.FIREWORK_ROCKET);
        if (hotbarSlot >= 0) {
            InventoryUtils.selectHotbarSlot(hotbarSlot);
            return false;
        }
        int inventorySlot = InventoryUtils.findInventorySlot(
                stack -> stack.getItem() == Items.FIREWORK_ROCKET, true);
        if (inventorySlot < 0) {
            this.printMessage("addons.lotus.messages.noFirework");
            return false;
        }
        InventoryUtils.swapIntoSelectedSlot(inventorySlot);
        return false;
    }

    /** 原 G()：鞘翅耐久见底时从背包换一件。 */
    private void checkElytra() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        boolean needsSwap = chest.getItem() != Items.ELYTRA || chest.getMaxDamage() - chest.getDamageValue() <= 2;
        if (!needsSwap || System.currentTimeMillis() - this.lastElytraSwapAt <= ELYTRA_SWAP_INTERVAL_MS) {
            return;
        }
        int slot = InventoryUtils.findInventorySlot(stack -> stack.getItem() == Items.ELYTRA
                && stack.getMaxDamage() - stack.getDamageValue() > 2, false);
        if (slot < 0) {
            this.printMessage("addons.lotus.messages.noElytra");
        } else if (this.autoElytraSetting.getValue()) {
            InventoryUtils.clickSlot(InventoryUtils.toMenuSlot(slot), 0, ClickType.QUICK_MOVE);
            this.lastElytraSwapAt = System.currentTimeMillis();
        }
    }

    // ------------------------------------------------------------ 进度存档（aH_）

    private void checkProgressAutosave() {
        long now = System.currentTimeMillis();
        if (now - this.lastProgressSaveAt >= PROGRESS_AUTOSAVE_INTERVAL_MS) {
            this.saveProgress();
            this.lastProgressSaveAt = now;
        }
    }

    /** 原 F()：读取该服务器/维度的上次进度，并把中心坐标同步到设置里。 */
    private void loadProgress() {
        this.savedProgress = null;
        String server = this.sessionServerKey;
        String dimension = this.sessionDimensionId;
        if (server == null || dimension == null) {
            return;
        }
        Path file = this.progressFile(server, dimension);
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            Progress progress = GSON.fromJson(Files.readString(file), Progress.class);
            if (progress == null) {
                return;
            }
            this.savedProgress = progress;
            this.centerXSetting.setValue(progress.x);
            this.centerZSetting.setValue(progress.z);
        } catch (IOException | RuntimeException failure) {
            ClientAccess.logger.error("Could not load spiral flight progress from " + file, failure);
        }
    }

    /** 原 I()：把中心坐标与当前 θ 写回磁盘。 */
    private void saveProgress() {
        String server = this.sessionServerKey;
        String dimension = this.sessionDimensionId;
        if (server == null || dimension == null) {
            return;
        }
        if (this.savedProgress == null) {
            this.savedProgress = new Progress();
        }
        Progress progress = this.savedProgress;
        progress.x = this.centerXSetting.getValue();
        progress.z = this.centerZSetting.getValue();
        progress.theta = this.spiralTheta;
        progress.savedAt = System.currentTimeMillis();
        progress.dimension = dimension;

        Path file = this.progressFile(server, dimension);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(progress));
        } catch (IOException failure) {
            ClientAccess.logger.error("Could not save spiral flight progress to " + file, failure);
        }
    }

    /** 原 {@code aH_} 写在游戏目录的 {@code Lotus/search} 下；这里改到 {@code lotus/spiralflight}，避免与 Meteor 版混用。 */
    private Path progressFile(String server, String dimension) {
        return this.evernightMinecraft.gameDirectory.toPath()
                .resolve("lotus")
                .resolve("spiralflight")
                .resolve(sanitizePathComponent(server))
                .resolve(sanitizePathComponent(dimension) + ".json");
    }

    private static String sanitizePathComponent(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private String currentServerKey() {
        ServerData server = this.evernightMinecraft.getCurrentServer();
        if (server != null && server.ip != null && !server.ip.isBlank()) {
            return server.ip;
        }
        if (this.evernightMinecraft.getSingleplayerServer() != null) {
            return this.evernightMinecraft.getSingleplayerServer().getWorldData().getLevelName();
        }
        return null;
    }

    private String currentDimensionId() {
        if (this.evernightMinecraft.level == null) {
            return null;
        }
        return this.evernightMinecraft.level.dimension().identifier().toString();
    }

    // ------------------------------------------------------------------ 杂项

    /** 原 bu.f()/bu.g()：飞行时让相机可以自由看，落地/关闭时还原。 */
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

    private void setForwardKey(boolean down) {
        if (this.evernightMinecraft.options != null && this.evernightMinecraft.options.keyUp != null) {
            this.evernightMinecraft.options.keyUp.setDown(down);
        }
    }

    private void releaseKeys() {
        if (this.evernightMinecraft.options == null) {
            return;
        }
        this.setForwardKey(false);
        this.evernightMinecraft.options.keyJump.setDown(false);
    }

    /** 聊天文本只从语言表取，键见 {@code assets/lotus/lang/*.json}。 */
    private void printMessage(String translationKey, Object... args) {
        String text = Translations.get(translationKey);
        if (args.length > 0) {
            try {
                text = String.format(Locale.ROOT, text, args);
            } catch (RuntimeException ignoredFormatFailure) {
                // 语言表占位符与参数不匹配时保留原文，方便定位漏翻/错翻。
            }
        }
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    /** 原 bf.h / bf.i；NONE 对应原来的 bf.a（什么都没发生）。 */
    private enum State {
        NONE,
        TAKEOFF,
        FLY
    }

    /** 原 aG_：一个服务器 + 维度下的扫图进度。 */
    private static final class Progress {
        int x;
        int z;
        double theta;
        long savedAt;
        String dimension;
    }
}
