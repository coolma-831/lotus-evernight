package com.xiaohe66.meteor.lotus.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import evernight.addon.AddonModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.events.world.BlockUpdateAppliedEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.render.util.RenderUtils;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.NumberSetting;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TransparentBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Port of {@code av} (V生电辅助) from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>围绕相机扫描一个圆柱体区域，把「可以刷怪」和「亮度安全」的方块位置缓存下来，然后在世界渲染
 * 阶段叠加显示：
 * <ul>
 *     <li>可能刷怪的位置：脚下画一圈方框，并在方块上方显示方块亮度等级；</li>
 *     <li>安全的位置：只显示亮度数字（颜色可配置）。</li>
 * </ul>
 *
 * <p>移植说明：
 * <ul>
 *     <li>Meteor 的 {@code Render3DEvent} → {@link WorldRenderEvent.Main}；方框改用
 *     {@link WorldBoxRenderer#outlineBox}，亮度数字改用 {@link RenderUtils#drawWorldText}
 *     （原模块用 {@code lotus:textures/misc/light_level_numbers.png} 数字图集 + 自定义几何，
 *     插件只有一个 Java 文件、无法附带贴图，因此改为世界空间文字）；</li>
 *     <li>相机位置取 {@link Camera#position()}，因此 Freecam 下扫描中心跟着自由相机走（等价原
 *     {@code br.a()}）；</li>
 *     <li>原模块的「自定义球体」子功能、NBT 持久化与 Meteor GUI 没有移植，见文件末尾的
 *     {@code TODO(port)}。</li>
 * </ul>
 */
public final class LightLevelModule extends AddonModule {
    /**
     * 原模块用 0.2 格半宽的数字贴图（约 0.4 格高）；Evernight 世界文本 {@code scale = 1} 时约
     * 0.225 格高，所以乘 20 把 {@code NumberScale} 的原始范围（0.01 ~ 0.2）换算成文本缩放。
     */
    private static final double NUMBER_SCALE_FACTOR = 20.0;
    /** 方框相对方块边缘的内缩量（原模块 line 的 0.05 偏移）。 */
    private static final double BOX_INSET = 0.05;
    /** 方框贴在脚下地面上的高度偏移。 */
    private static final double BOX_Y_OFFSET = 0.01;
    /** 数字贴在脚下地面上的高度偏移。 */
    private static final double NUMBER_Y_OFFSET = 0.01;

    private final Setting<Boolean> showLight;
    private final Setting<Integer> horizontalRange;
    private final Setting<Integer> verticalRange;
    private final Setting<Integer> rescanDistance;
    private final Setting<Integer> rescanInterval;
    private final Setting<Boolean> rescanOnBlockChange;
    private final Setting<Integer> spawnThreshold;
    private final Setting<Boolean> showBox;
    private final Setting<Boolean> showNumbers;
    private final Setting<Double> numberScale;
    private final Setting<Color> spawnColor;
    private final Setting<Color> safeColor;
    private final Setting<Boolean> includeSmallMobs;

    /** 当前扫描结果（仅渲染用，扫描时整体重建）。 */
    private final List<LightEntry> entries = new ArrayList<>();
    /** 复用池，避免每次重扫都分配几千个对象（等价 Meteor 的 {@code Pool<bb>}）。 */
    private final List<LightEntry> entryPool = new ArrayList<>();
    private int poolIndex;

    private final MutableBlockPos cursor = new MutableBlockPos();
    private final MutableBlockPos belowCursor = new MutableBlockPos();

    private BlockPos lastScanCenter;
    private int tickCounter;
    private int lastScanTick = -1;
    private boolean needsRescan = true;

    public LightLevelModule(ModuleCategory category) {
        super("lotus", "LightLevel",
                "Shows block light levels and mob spawn risk areas as a world overlay.", category);

        this.showLight = this.add(new BooleanSetting("ShowLight", true));
        this.horizontalRange = this.add(new NumberSetting<>("HorizontalRange", 32, 1, 64));
        this.verticalRange = this.add(new NumberSetting<>("VerticalRange", 24, 1, 64));
        this.rescanDistance = this.add(new NumberSetting<>("RescanDistance", 4, 1, 16));
        this.rescanInterval = this.add(new NumberSetting<>("RescanInterval", 20, 1, 200));
        this.rescanOnBlockChange = this.add(new BooleanSetting("RescanOnBlockChange", true));
        this.spawnThreshold = this.add(new NumberSetting<>("SpawnThreshold", 0, 0, 15));
        this.showBox = this.add(new BooleanSetting("ShowBox", true));
        this.showNumbers = this.add(new BooleanSetting("ShowNumbers", true));
        this.numberScale = this.add(new NumberSetting<>("NumberScale", 0.1, 0.01, 0.2));
        this.spawnColor = this.add(new ColorSetting("SpawnColor", new Color(200, 0, 0)));
        this.safeColor = this.add(new ColorSetting("SafeColor", new Color(0, 200, 0)));
        this.includeSmallMobs = this.add(new BooleanSetting("IncludeSmallMobs", false));

        // 原模块在这些设置变化时把「需要重扫」置位。
        this.triggerRescan(this.showLight);
        this.triggerRescan(this.horizontalRange);
        this.triggerRescan(this.verticalRange);
        this.triggerRescan(this.spawnThreshold);
        this.triggerRescan(this.includeSmallMobs);
    }

    private void triggerRescan(Setting<?> setting) {
        setting.setChangeCallback(() -> this.needsRescan = true);
    }

    @Override
    public void onEnable() {
        this.needsRescan = true;
        this.lastScanCenter = null;
        this.lastScanTick = -1;
        this.tickCounter = 0;
    }

    @Override
    public void onDisable() {
        this.entries.clear();
        this.poolIndex = 0;
        this.lastScanCenter = null;
        this.needsRescan = true;
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        this.tickCounter++;

        if (!this.showLight.getValue()) {
            return;
        }

        Vec3 camera = this.cameraPosition();
        if (this.needsRescan || this.shouldRescan(camera)) {
            this.scan(camera);
            this.lastScanCenter = BlockPos.containing(camera);
            this.lastScanTick = this.tickCounter;
            this.needsRescan = false;
        }
    }

    /**
     * 是否重扫：移动超过 {@code RescanDistance}，或距离上次扫描超过 {@code RescanInterval} tick。
     *
     * <p>原模块还会在玩家朝向变化时重扫，因为数字是贴朝向的平面贴图；这里数字由
     * {@link RenderUtils#drawWorldText} 始终面向相机，所以去掉了「转向就重扫」这一条，避免频繁
     * 重扫大范围。
     */
    private boolean shouldRescan(Vec3 camera) {
        if (this.lastScanCenter == null) {
            return true;
        }
        if (this.tickCounter - this.lastScanTick >= this.rescanInterval.getValue()) {
            return true;
        }
        int distance = this.rescanDistance.getValue();
        return Math.abs(camera.x - this.lastScanCenter.getX()) > distance
                || Math.abs(camera.y - this.lastScanCenter.getY()) > distance
                || Math.abs(camera.z - this.lastScanCenter.getZ()) > distance;
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateAppliedEvent event) {
        if (!this.showLight.getValue()
                || !this.rescanOnBlockChange.getValue()
                || this.lastScanCenter == null) {
            return;
        }
        // TODO(port): Meteor 的 BlockUpdateEvent 带方块坐标，原模块据此只过滤「扫描范围内」的
        //  更新；Evernight 的 BlockUpdateAppliedEvent 不带坐标（一个事件可能覆盖多个方块），
        //  这里只能任意更新都标记重扫。重扫自身仍被「每 tick 最多一次」限制。
        this.needsRescan = true;
    }

    /** 相机位置：Freecam 等自定义相机下等同于原模块的 {@code br.a()}。 */
    private Vec3 cameraPosition() {
        Camera camera = this.evernightMinecraft.gameRenderer.getMainCamera();
        if (camera.isInitialized()) {
            return camera.position();
        }
        return this.evernightMinecraft.player.position();
    }

    /** 扫描相机周围的圆柱体区域，重建 {@link #entries}（对应原模块的 {@code b()}）。 */
    private void scan(Vec3 camera) {
        this.entries.clear();
        this.poolIndex = 0;

        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }

        int centerX = Mth.floor(camera.x);
        int centerY = Mth.floor(camera.y);
        int centerZ = Mth.floor(camera.z);

        int horizontal = this.horizontalRange.getValue();
        int vertical = this.verticalRange.getValue();
        int radiusSq = horizontal * horizontal;
        int threshold = this.spawnThreshold.getValue();
        boolean includeSmall = this.includeSmallMobs.getValue();

        int minY = Math.max(level.getMinY(), centerY - vertical);
        int maxY = Math.min(level.getMaxY(), centerY + vertical);

        MutableBlockPos pos = this.cursor;
        LevelChunk chunk = null;
        LevelChunkSection[] sections = null;
        int chunkX = Integer.MIN_VALUE;
        int chunkZ = Integer.MIN_VALUE;
        int topFilledY = Integer.MIN_VALUE;

        for (int x = centerX - horizontal; x <= centerX + horizontal; x++) {
            int offsetX = x - centerX;
            int offsetXSq = offsetX * offsetX;

            for (int z = centerZ - horizontal; z <= centerZ + horizontal; z++) {
                int offsetZ = z - centerZ;
                if (offsetXSq + offsetZ * offsetZ > radiusSq) {
                    continue;
                }

                int currentChunkX = x >> 4;
                int currentChunkZ = z >> 4;
                if (currentChunkX != chunkX || currentChunkZ != chunkZ) {
                    chunkX = currentChunkX;
                    chunkZ = currentChunkZ;
                    chunk = level.getChunk(currentChunkX, currentChunkZ);
                    if (chunk == null || chunk.isEmpty()) {
                        // 空区块（客户端未加载时是 EmptyLevelChunk）没有可扫描的节。
                        sections = null;
                        topFilledY = Integer.MIN_VALUE;
                    } else {
                        sections = chunk.getSections();
                        int highestFilledSection = chunk.getHighestFilledSectionIndex();
                        topFilledY = highestFilledSection < 0
                                ? Integer.MIN_VALUE
                                : level.getMinY() + (highestFilledSection << 4) + 15;
                    }
                }

                if (chunk == null || sections == null) {
                    continue;
                }

                int topY = Math.min(maxY, topFilledY);
                if (topY < minY) {
                    continue;
                }

                // 脚下方块状态：从扫描范围底部往上一格开始滚动（原模块的 var26）。
                BlockState floor = level.getBlockState(pos.set(x, minY - 1, z));
                int lastSectionIndex = Integer.MIN_VALUE;

                for (int y = minY; y <= topY; y++) {
                    int sectionIndex = level.getSectionIndex(y);
                    if (sectionIndex != lastSectionIndex) {
                        lastSectionIndex = sectionIndex;
                        // 整节都是空气时直接跳到本节末尾，省掉 16 次方块查询。
                        if (sectionIndex >= 0
                                && sectionIndex < sections.length
                                && sections[sectionIndex].hasOnlyAir()) {
                            y = (y & ~15) + 15;
                            floor = Blocks.AIR.defaultBlockState();
                            continue;
                        }
                    }

                    pos.set(x, y, z);
                    BlockState state = chunk.getBlockState(pos);

                    // 铁轨不是落脚面，但原模块把它当「地面状态」记录下来后跳过刷怪判定。
                    if (!state.isAir() && state.is(BlockTags.RAILS)) {
                        floor = state;
                        continue;
                    }

                    SpawnRisk risk = this.spawnRisk(pos, state, floor, threshold);
                    if (risk == SpawnRisk.ALWAYS || risk == SpawnRisk.POTENTIAL) {
                        if (this.hasHeadroom(pos) || includeSmall) {
                            this.addEntry(pos, false);
                        }
                    } else if (this.isSpawnSpace(state, pos)) {
                        // 当前位置本身不刷怪，但脚下是完整不透明方块：把它标成「安全」显示亮度。
                        this.belowCursor.set(x, y - 1, z);
                        if (floor.isRedstoneConductor(level, this.belowCursor)
                                && (this.hasHeadroom(pos) || includeSmall)) {
                            this.addEntry(pos, true);
                        }
                    }

                    floor = state;
                }
            }
        }
    }

    /**
     * 原模块的 {@code a(BlockPos, BlockState, BlockState, int)}：判定某个空气格能否刷怪。
     *
     * @param pos      被判定位置
     * @param state    被判定位置的方块状态
     * @param floor    脚下方块状态
     * @param threshold 刷怪阈值（方块亮度高于它就不刷怪）
     */
    private SpawnRisk spawnRisk(BlockPos pos, BlockState state, BlockState floor, int threshold) {
        boolean snowLayer = state.getBlock() instanceof SnowLayerBlock
                && state.getValue(SnowLayerBlock.LAYERS) == 1;
        if (!this.isSpawnSpace(state, pos) && !snowLayer) {
            return SpawnRisk.NEVER;
        }
        if (!isValidSpawnBlock(floor)) {
            return SpawnRisk.NEVER;
        }
        if (this.evernightMinecraft.level.getBrightness(LightLayer.BLOCK, pos) > threshold) {
            return SpawnRisk.NEVER;
        }
        if (this.evernightMinecraft.level.getBrightness(LightLayer.SKY, pos) > threshold) {
            return SpawnRisk.POTENTIAL;
        }
        return SpawnRisk.ALWAYS;
    }

    /** 碰撞箱为空且不含流体（原模块 {@code bn.a_BlockState_BlockPos}）。 */
    private boolean isSpawnSpace(BlockState state, BlockPos pos) {
        Level level = this.evernightMinecraft.level;
        return state.getCollisionShape(level, pos).isEmpty() && state.getFluidState().isEmpty();
    }

    /** 上方一格是否净空（原模块 {@code bn.e}），用于排除只有 1 格净空的位置。 */
    private boolean hasHeadroom(BlockPos pos) {
        Level level = this.evernightMinecraft.level;
        BlockPos above = pos.above();
        return level.getBlockState(above).getCollisionShape(level, above).isEmpty();
    }

    /**
     * 原模块的 {@code BlockUtils.isValidSpawnBlock(BlockState)}（Meteor Client，GPL-3.0）：
     * 能作为刷怪落脚面的方块。
     */
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

    private void addEntry(BlockPos pos, boolean safe) {
        LightEntry entry = this.nextEntry();
        entry.x = pos.getX();
        entry.y = pos.getY();
        entry.z = pos.getZ();
        entry.safe = safe;
        int light = this.evernightMinecraft.level.getBrightness(LightLayer.BLOCK, pos);
        entry.label = Integer.toString(light);
        this.entries.add(entry);
    }

    private LightEntry nextEntry() {
        if (this.poolIndex < this.entryPool.size()) {
            return this.entryPool.get(this.poolIndex++);
        }
        LightEntry entry = new LightEntry();
        this.entryPool.add(entry);
        this.poolIndex++;
        return entry;
    }

    @EventHandler
    private void onRender(WorldRenderEvent.Main event) {
        if (!this.showLight.getValue() || this.entries.isEmpty()) {
            return;
        }

        boolean boxes = this.showBox.getValue();
        boolean numbers = this.showNumbers.getValue();
        if (!boxes && !numbers) {
            return;
        }

        // TODO(port): 原模块的「透视显示」开关在 Meteor 里切换 renderer / depthRenderer 两套缓冲。
        //  Evernight 的 WorldBoxRenderer 固定走 NO_DEPTH_TEST 的世界线管线（方框始终透视），
        //  所以该设置没有移植；需要「被方块遮挡」的版本时，得自己用 MeshBuffer +
        //  ClientRenderTypes.worldLinesDepth() 另开一条渲染通道，不能走 WorldBoxRenderer。
        PoseStack poses = event.getPoseStack();
        float lineWidth = 1.0F;
        double textScale = this.numberScale.getValue() * NUMBER_SCALE_FACTOR;

        for (LightEntry entry : this.entries) {
            Color color = entry.safe ? this.safeColor.getValue() : this.spawnColor.getValue();

            if (boxes && !entry.safe) {
                WorldBoxRenderer.outlineBox(poses, boxAt(entry), color, lineWidth);
            }

            if (numbers) {
                RenderUtils.INSTANCE.drawWorldText(
                        poses,
                        entry.label,
                        new Vec3(entry.x + 0.5, entry.y + NUMBER_Y_OFFSET, entry.z + 0.5),
                        textScale,
                        color,
                        true);
            }
        }
    }

    /** 贴在方块脚下地面的一圈细方框（原模块 4 条 inset 0.05 的线）。 */
    private static AABB boxAt(LightEntry entry) {
        double minX = entry.x + BOX_INSET;
        double minZ = entry.z + BOX_INSET;
        double maxX = entry.x + 1.0 - BOX_INSET;
        double maxZ = entry.z + 1.0 - BOX_INSET;
        double y = entry.y + BOX_Y_OFFSET;
        return new AABB(minX, y, minZ, maxX, y + 0.001, maxZ);
    }

    /** 刷怪风险等级（原模块的 {@code BlockUtils.MobSpawn}）。 */
    private enum SpawnRisk {
        /** 方块亮度与天空亮度都不超过阈值：随时可刷。 */
        ALWAYS,
        /** 方块亮度够暗但天空亮度超阈值：夜晚/雷雨或更低光照时才可刷。 */
        POTENTIAL,
        /** 不可刷。 */
        NEVER
    }

    /** 一个被标记的方块位置（原模块的 {@code bb}，改成对象池复用）。 */
    private static final class LightEntry {
        private int x;
        private int y;
        private int z;
        /** true = 亮度安全位置（只显示数字），false = 可能刷怪位置（方框 + 数字）。 */
        private boolean safe;
        /** 扫描时算好的亮度文本，避免每帧重新拼接。 */
        private String label = "";
    }

    // TODO(port): 原模块还有「自定义球体」功能，本文件没有移植：
    //  - bc：每个球体自带一套设置（名称 / 启用 / 球心 / 半径 / 分段数 / 线框颜色 / 显示可刷怪方块 /
    //    扫描间隔 / 高亮颜色 / 渲染上限 / 透视 / 含矮小生物），并可渲染球体线框与球内可刷怪方块；
    //  - bd：Meteor GUI 的球体编辑界面（getWidget），插件无法在不新增文件的前提下提供等价 GUI；
    //  - toTag / fromTag：球体列表写进模块 NBT，而 Evernight 的模块配置由本体自动序列化设置，
    //    没有 Meteor 那种「模块自带 NBT 数据」的钩子（只能用 JsonConfigurable + 额外文件）。
    //  要恢复这部分，需要：加一个字符串集合设置（如 "Spheres" = "x,y,z,radius"）或自带配置类，
    //  再用 LineMeshRenderer 画球体线框，并在 WorldRenderEvent.Main 里遍历球内可刷怪方块。
}
