package com.xiaohe66.meteor.lotus.modules;

import com.mojang.blaze3d.vertex.PoseStack;
import evernight.addon.AddonModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.input.MouseHandlerOnScrollEvent;
import evernight.client.events.network.MultiPlayerGameModeUseItemOnEvent;
import evernight.client.events.render.HudRenderEvent;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.AbstractContainerScreenEvent;
import evernight.client.events.ui.AbstractContainerScreenRenderTooltipEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.events.ui.ScreenChangeEvent;
import evernight.client.gui.screens.misc.ShulkerPreviewScreen;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.gui.GuiDrawUtils;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.render.util.RenderUtils;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.ColorSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.utils.inventory.ContainerTooltipComponent;
import evernight.client.utils.inventory.ItemUtils;
import evernight.mixin.minecraft.gui.AbstractContainerScreenAccessor;
import java.awt.Color;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3x2fStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;

/**
 * Port of {@code ar} (V预览器, "V Previewer") from Lotus, the Meteor Client addon by xiaohe66
 * (GPL-3.0, built on Meteor Client, https://github.com/MeteorDevelopment/meteor-client).
 *
 * <p>The module has four independent parts, matching the original four setting groups:
 * <ul>
 *     <li><b>Box icon</b>: draws the dominant item inside every shulker box icon, both in a
 *     container screen and on the vanilla hotbar, with a small fill bar that shows how full the
 *     box is.</li>
 *     <li><b>Box preview</b>: adds a 3x9 content grid to the tooltip of a hovered shulker box
 *     (and a content grid for bundles).</li>
 *     <li><b>Container marks / highlight</b>: remembers the contents of every container the
 *     player opened, shows its dominant item as a billboard icon on the face that points at the
 *     camera, and highlights containers / item frames that contain the currently selected
 *     hotbar item.</li>
 *     <li><b>Box tiling</b>: lays out every distinct shulker box in the open container as a
 *     compact grid ("kit" view), scrollable with the mouse wheel.</li>
 * </ul>
 *
 * <p>Porting notes:
 * <ul>
 *     <li>The original is built on Lotus' own render events ({@code I_}, {@code x}, {@code z},
 *     {@code q}, {@code F_}, {@code L_}, ...) which are posted by its own mixins. Evernight's
 *     {@link AbstractContainerScreenEvent} is posted at the end of
 *     {@code AbstractContainerScreen#renderTooltip}, which is after slots and before tooltips, so
 *     it replaces both the "container screen render" and the "handled screen render" hooks.</li>
 *     <li>{@code Render3DEvent} → {@link WorldRenderEvent.Main}; {@code Renderer3D.box} →
 *     {@link WorldBoxRenderer}.</li>
 *     <li>The original renders a real 3D item model on the container face through a
 *     {@code SubmitNodeCollector} supplied by its own world-render mixin. Evernight 1.0.3 does
 *     not expose that collector, so the mark is drawn as a screen-projected 2D item icon in
 *     {@link HudRenderEvent} instead. The icon therefore follows the HUD and is hidden while a
 *     screen is open, see the {@code TODO(port)} at the end of the file.</li>
 *     <li>Shulker contents are read through {@link DataComponents#CONTAINER} /
 *     {@link ItemContainerContents} instead of Meteor's
 *     {@code ContainerComponentAccessor} mixin.</li>
 * </ul>
 */
public final class ContainerPreviewModule extends AddonModule {
    /** Slots of a shulker box / bundle preview grid. */
    private static final int SHULKER_SLOTS = 27;
    /** Player inventory slots that every container menu appends after its own slots. */
    private static final int PLAYER_INVENTORY_SLOTS = 36;
    /** Upper bound for captured menu slots (double chest). */
    private static final int MAX_CONTAINER_SLOTS = 54;
    /** Nine columns per row in a container grid. */
    private static final int GRID_COLUMNS = 9;
    /** Width of a grid cell. */
    private static final int CELL_SIZE = 18;
    /** Chrome around a tiled box panel. */
    private static final int PANEL_CHROME = 2;
    /** Vanilla hotbar: background width and its left offset inside the 182px stretch. */
    private static final int HOTBAR_WIDTH = 182;
    private static final int HOTBAR_SLOT_SPACING = 20;
    /** Maximum number of frames an opened container is remembered for. */
    private static final long INTERACT_WINDOW_TICKS = 5L;
    /** Approximate on-screen size used by the container mark projection. */
    private static final double MARK_PIXEL_FACTOR = 96.0;
    private static final double MARK_MIN_SIZE = 8.0;
    private static final double MARK_MAX_SIZE = 48.0;
    /** Fallback world marker used while a container screen hides the HUD. */
    private static final Color WORLD_MARK_COLOR = new Color(255, 215, 0, 70);

    // ---------------------------------------------------------------------------------------
    // Settings (flat, no groups: Evernight 1.0.3 has no setting groups that addons can use)
    // ---------------------------------------------------------------------------------------
    private final Setting<Boolean> boxIconSetting;
    private final Setting<Double> iconScaleSetting;
    private final Setting<Integer> iconOffsetXSetting;
    private final Setting<Integer> iconOffsetYSetting;

    private final Setting<Boolean> boxPreviewSetting;

    private final Setting<Boolean> containerMarkSetting;
    private final Setting<Double> markScaleSetting;
    private final Setting<Integer> markDistanceSetting;

    private final Setting<Boolean> highlightChestsSetting;
    private final Setting<Boolean> highlightItemFramesSetting;
    private final Setting<Integer> highlightDistanceSetting;
    private final Setting<Color> highlightColorSetting;

    private final Setting<Boolean> boxTilingSetting;
    private final Setting<Integer> topMarginSetting;
    private final Setting<Integer> leftMarginSetting;
    private final Setting<Integer> spacingSetting;
    private final Setting<Double> tilingScaleSetting;
    private final Setting<Boolean> compactModeSetting;
    private final Setting<Integer> backgroundAlphaSetting;

    // ---------------------------------------------------------------------------------------
    // Runtime state
    // ---------------------------------------------------------------------------------------
    /** Contents of every container the player opened, grouped by their (double-chest aware) key. */
    private final Map<ContainerKey, ContainerRecord> trackedContainers = new LinkedHashMap<>();
    /** Containers whose contents contain the selected hotbar item. */
    private final Set<ContainerKey> matchingContainers = new HashSet<>();
    /** Item frames whose display item matches the selected hotbar item. */
    private final Set<ItemFrame> matchingFrames = new HashSet<>();

    /** Distinct shulker boxes of the currently open container, used by the tiling view. */
    private final List<BoxGroup> tilingGroups = new ArrayList<>();
    private boolean tilingBuilt;
    private int tilingMenuId = -1;
    private int tilingStateId = -1;
    private double tilingScroll;
    private double tilingContentHeight;

    /** Block the player last right-clicked, used to resolve which container was opened. */
    private BlockPos lastInteractPos;
    private long lastInteractTick = Long.MIN_VALUE;
    /** Container the currently open screen belongs to (resolved when the screen opens). */
    private BlockPos pendingContainerPos;
    private int lastMenuId = -1;
    private int lastMenuStateId = -1;

    private int tickCounter;
    private int lastSelectedSlot = -1;

    private final Matrix4f cameraViewMatrix = new Matrix4f();
    private final Matrix4f worldProjectionMatrix = new Matrix4f();
    private final Vector4f projectedPoint = new Vector4f();

    public ContainerPreviewModule(ModuleCategory category) {
        super("lotus", "LotusContainerPreview",
                "Previews shulker box contents, marks opened containers and highlights containers holding the selected item.",
                category);

        this.boxIconSetting = this.add(new BooleanSetting("BoxIcon", true));
        this.iconScaleSetting = this.add(new NumberSetting<>("IconScale", 0.4, 0.1, 1.0));
        this.iconOffsetXSetting = this.add(new NumberSetting<>("IconOffsetX", 7, -20, 20));
        this.iconOffsetYSetting = this.add(new NumberSetting<>("IconOffsetY", 7, -20, 20));

        this.boxPreviewSetting = this.add(new BooleanSetting("BoxPreview", true));

        this.containerMarkSetting = this.add(new BooleanSetting("ContainerMark", true));
        this.markScaleSetting = this.add(new NumberSetting<>("MarkScale", 1.0, 0.5, 2.0));
        this.markDistanceSetting = this.add(new NumberSetting<>("MarkDistance", 32, 4, 64));

        this.highlightChestsSetting = this.add(new BooleanSetting("HighlightChests", true));
        this.highlightItemFramesSetting = this.add(new BooleanSetting("HighlightItemFrames", true));
        this.highlightDistanceSetting = this.add(new NumberSetting<>("HighlightDistance", 64, 4, 128));
        this.highlightColorSetting = this.add(new ColorSetting("HighlightColor", new Color(255, 215, 0, 64)));

        this.boxTilingSetting = this.add(new BooleanSetting("BoxTiling", true));
        this.topMarginSetting = this.add(new NumberSetting<>("TopMargin", 6, 0, 100));
        this.leftMarginSetting = this.add(new NumberSetting<>("LeftMargin", 6, 0, 500));
        this.spacingSetting = this.add(new NumberSetting<>("Spacing", 4, 0, 10));
        this.tilingScaleSetting = this.add(new NumberSetting<>("TilingScale", 1.0, 0.5, 1.5));
        this.compactModeSetting = this.add(new BooleanSetting("CompactMode", true));
        this.backgroundAlphaSetting = this.add(new NumberSetting<>("BackgroundAlpha", 30, 0, 255));

        this.compactModeSetting.setChangeCallback(this::invalidateTiling);
        this.spacingSetting.setChangeCallback(this::invalidateTiling);
        this.tilingScaleSetting.setChangeCallback(this::invalidateTiling);

        // TODO(port): the original also has a "PersistCache" setting which writes the container
        //  marks to Lotus/container-mark and restores them after a restart. Evernight modules are
        //  serialised by the core, and an addon needs an extra JsonConfigurable registered by its
        //  entrypoint to persist plugin-owned data. This single-file port therefore drops the
        //  setting instead of shipping a toggle that does nothing.
    }

    @Override
    public void onEnable() {
        this.clearState();
    }

    @Override
    public void onDisable() {
        this.clearState();
    }

    @Override
    public String getInfo() {
        int tracked = this.trackedContainers.size();
        return tracked == 0 ? null : Integer.toString(tracked);
    }

    private void clearState() {
        this.trackedContainers.clear();
        this.matchingContainers.clear();
        this.matchingFrames.clear();
        this.tilingGroups.clear();
        this.tilingBuilt = false;
        this.tilingMenuId = -1;
        this.tilingStateId = -1;
        this.tilingScroll = 0.0;
        this.tilingContentHeight = 0.0;
        this.lastInteractPos = null;
        this.lastInteractTick = Long.MIN_VALUE;
        this.pendingContainerPos = null;
        this.lastMenuId = -1;
        this.lastMenuStateId = -1;
        this.tickCounter = 0;
        this.lastSelectedSlot = -1;
    }

    // ---------------------------------------------------------------------------------------
    // Container screen: box icons + tiling
    // ---------------------------------------------------------------------------------------

    /**
     * Evernight posts {@link AbstractContainerScreenEvent} right at the start of
     * {@code AbstractContainerScreen#renderTooltip}, i.e. after all slots (and their items) have
     * been drawn but before tooltips. That is exactly the point where the original drew its
     * overlays.
     */
    @EventHandler
    private void onContainerScreen(AbstractContainerScreenEvent event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        GuiGraphics graphics = event.getGraphics();
        if (graphics == null) {
            return;
        }

        if (this.boxIconSetting.getValue()) {
            AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) screen;
            this.drawBoxIcons(graphics, screen.getMenu(), accessor.getX(), accessor.getY());
        }
        if (this.boxTilingSetting.getValue()) {
            this.drawTiling(graphics, screen, event.getMouseX(), event.getMouseY());
        }

        this.captureOpenedContainer(screen);
    }

    private void drawBoxIcons(GuiGraphics graphics, AbstractContainerMenu menu, int originX, int originY) {
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemUtils.isShulkerBox(stack.getItem())) {
                continue;
            }
            List<ItemStack> contents = readShulkerItems(stack);
            if (contents == null) {
                continue;
            }
            ItemStack preview = dominantStack(contents);
            if (!preview.isEmpty()) {
                this.drawBoxIcon(graphics, preview, originX + slot.x, originY + slot.y);
            }
        }
    }

    /** Draws one box icon plus its fill bar at the top-left corner of a slot. */
    private void drawBoxIcon(GuiGraphics graphics, ItemStack preview, int x, int y) {
        float scale = this.iconScaleSetting.getValue().floatValue();
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(x + this.iconOffsetXSetting.getValue(), y + this.iconOffsetYSetting.getValue());
        pose.scale(scale, scale);
        graphics.renderItem(preview, 0, 0);
        pose.popMatrix();

        // The bar is full when the box holds 27 stacks, exactly like the original.
        int barHeight = (int) (preview.getCount() * 1.0
                / preview.getMaxStackSize()
                / SHULKER_SLOTS
                * 12.0);
        barHeight = Math.max(0, Math.min(12, barHeight));
        int top = y + 2;
        int bottom = y + 14;
        if (barHeight >= 12) {
            graphics.fill(x + 16, top, x + 17, bottom, 0xFFFF0000);
        } else {
            int split = bottom - barHeight;
            graphics.fill(x + 16, top, x + 17, split, 0xFFFFFFFF);
            graphics.fill(x + 16, split, x + 17, bottom, 0xFF00FF00);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Hotbar box icons
    // ---------------------------------------------------------------------------------------

    @EventHandler
    private void onHudRender(HudRenderEvent event) {
        if (this.boxIconSetting.getValue()) {
            this.drawHotbarIcons(event.getGuiGraphics());
        }
        if (this.containerMarkSetting.getValue()) {
            this.drawContainerMarks(event);
        }
    }

    private void drawHotbarIcons(GuiGraphics graphics) {
        Player player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        int width = this.evernightMinecraft.getWindow().getGuiScaledWidth();
        int height = this.evernightMinecraft.getWindow().getGuiScaledHeight();
        int baseX = (width - HOTBAR_WIDTH) / 2 + 3;
        int baseY = height - 22 + 3;

        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || !ItemUtils.isShulkerBox(stack.getItem())) {
                continue;
            }
            List<ItemStack> contents = readShulkerItems(stack);
            if (contents == null) {
                continue;
            }
            ItemStack preview = dominantStack(contents);
            if (!preview.isEmpty()) {
                this.drawBoxIcon(graphics, preview, baseX + slot * HOTBAR_SLOT_SPACING, baseY);
            }
        }
    }

    // ---------------------------------------------------------------------------------------
    // Container marks (dominant item on the face that points at the camera)
    // ---------------------------------------------------------------------------------------

    private void drawContainerMarks(HudRenderEvent event) {
        if (this.trackedContainers.isEmpty() || this.evernightMinecraft.level == null) {
            return;
        }
        Camera camera = this.evernightMinecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 cameraPos = camera.position();
        int guiScale = Math.max(1, this.evernightMinecraft.getWindow().getGuiScale());
        double rawWidth = this.evernightMinecraft.getWindow().getWidth();
        double rawHeight = this.evernightMinecraft.getWindow().getHeight();
        int guiWidth = this.evernightMinecraft.getWindow().getGuiScaledWidth();
        int guiHeight = this.evernightMinecraft.getWindow().getGuiScaledHeight();

        // Same projection setup as Evernight's ESP2DRenderer: GUI pose * camera rotation *
        // world projection, which yields window-space pixels.
        this.cameraViewMatrix.set(event.getPoseStack().last().pose());
        RenderUtils.applyCameraRotation(this.cameraViewMatrix);
        RenderUtils.WORLD_PROJECTION_MATRIX.mul(this.cameraViewMatrix, this.worldProjectionMatrix);

        boolean drewAny = false;
        double maxDistance = this.markDistanceSetting.getValue();

        for (Map.Entry<ContainerKey, ContainerRecord> entry : this.trackedContainers.entrySet()) {
            ItemStack marker = entry.getValue().marker;
            if (marker.isEmpty()) {
                continue;
            }
            ContainerFace face = pickFace(entry.getKey(), cameraPos);
            if (face == null) {
                continue;
            }
            Vec3 center = face.center();
            double distance = cameraPos.distanceTo(center);
            if (distance > maxDistance) {
                continue;
            }

            this.projectedPoint.set(
                    (float) (center.x - cameraPos.x),
                    (float) (center.y - cameraPos.y),
                    (float) (center.z - cameraPos.z),
                    1.0F);
            this.worldProjectionMatrix.transform(this.projectedPoint);
            if (this.projectedPoint.w <= 0.0001F) {
                continue;
            }

            float screenX = (float) ((this.projectedPoint.x / this.projectedPoint.w + 1.0F)
                    * 0.5F * rawWidth / guiScale);
            float screenY = (float) ((1.0F - this.projectedPoint.y / this.projectedPoint.w)
                    * 0.5F * rawHeight / guiScale);
            if (screenX < -MARK_MAX_SIZE || screenX > guiWidth + MARK_MAX_SIZE
                    || screenY < -MARK_MAX_SIZE || screenY > guiHeight + MARK_MAX_SIZE) {
                continue;
            }

            float iconSize = (float) Math.max(MARK_MIN_SIZE,
                    Math.min(MARK_MAX_SIZE,
                            this.markScaleSetting.getValue() * MARK_PIXEL_FACTOR / Math.max(1.0, distance)));
            PoseStack poses = event.getPoseStack();
            poses.pushPose();
            poses.translate(screenX, screenY, 0.0F);
            poses.scale(iconSize / 16.0F, iconSize / 16.0F, 1.0F);
            GuiDrawUtils.drawItem(poses, marker, -8.0F, -8.0F, 0.0F, false);
            poses.popPose();
            drewAny = true;
        }

        if (drewAny) {
            RenderUtils.INSTANCE.flushEntities();
        }
    }

    /**
     * Picks the face of the container that points most directly at the camera, skipping faces
     * covered by another block or container. For a double chest the best face of both halves is
     * used, i.e. only one icon is drawn.
     */
    private ContainerFace pickFace(ContainerKey key, Vec3 cameraPos) {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return null;
        }
        ContainerFace best = null;
        double bestScore = 0.0;

        for (BlockPos pos : key.positions()) {
            Vec3 center = Vec3.atCenterOf(pos);
            for (Direction direction : Direction.values()) {
                double score = (cameraPos.x - center.x) * direction.getStepX()
                        + (cameraPos.y - center.y) * direction.getStepY()
                        + (cameraPos.z - center.z) * direction.getStepZ()
                        - 0.51;
                if (score <= 0.0 || score <= bestScore) {
                    continue;
                }
                BlockPos neighbor = pos.relative(direction);
                if (!level.isLoaded(neighbor) || isFaceOccluded(level, neighbor)) {
                    continue;
                }
                bestScore = score;
                best = new ContainerFace(center.add(
                        direction.getStepX() * 0.51,
                        direction.getStepY() * 0.51,
                        direction.getStepZ() * 0.51));
            }
        }
        return best;
    }

    private static boolean isFaceOccluded(Level level, BlockPos neighbor) {
        BlockState state = level.getBlockState(neighbor);
        if (state.isAir()) {
            return false;
        }
        if (state.canOcclude() && state.isCollisionShapeFullBlock(level, neighbor)) {
            return true;
        }
        return level.getBlockEntity(neighbor) instanceof Container;
    }

    // ---------------------------------------------------------------------------------------
    // Opening / capturing containers
    // ---------------------------------------------------------------------------------------

    @EventHandler
    private void onUseItemOn(MultiPlayerGameModeUseItemOnEvent event) {
        if (!this.containerMarkSetting.getValue() && !this.highlightChestsSetting.getValue()) {
            return;
        }
        if (this.evernightMinecraft.level == null) {
            return;
        }
        BlockHitResult hit = event.getHitResult();
        if (hit == null) {
            return;
        }
        BlockPos pos = hit.getBlockPos();
        if (!this.isTrackableContainer(pos)) {
            return;
        }
        this.lastInteractPos = pos.immutable();
        this.lastInteractTick = this.evernightMinecraft.level.getGameTime();
    }

    @EventHandler
    private void onScreenChange(ScreenChangeEvent.Before event) {
        if (!(event.getNewScreen() instanceof AbstractContainerScreen<?> screen)) {
            this.pendingContainerPos = null;
            this.lastMenuId = -1;
            this.lastMenuStateId = -1;
            return;
        }
        this.lastMenuId = -1;
        this.lastMenuStateId = -1;
        this.tilingBuilt = false;
        this.tilingScroll = 0.0;

        if (screen.getMenu() instanceof InventoryMenu
                || screen instanceof CreativeModeInventoryScreen
                || screen instanceof ShulkerPreviewScreen) {
            this.pendingContainerPos = null;
            return;
        }
        this.pendingContainerPos = this.resolveInteractedContainer();
    }

    private BlockPos resolveInteractedContainer() {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return null;
        }
        if (this.lastInteractPos != null
                && level.getGameTime() - this.lastInteractTick <= INTERACT_WINDOW_TICKS
                && this.isTrackableContainer(this.lastInteractPos)) {
            return this.lastInteractPos;
        }
        if (this.evernightMinecraft.hitResult instanceof BlockHitResult hit) {
            BlockPos pos = hit.getBlockPos();
            if (this.isTrackableContainer(pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private boolean isTrackableContainer(BlockPos pos) {
        Level level = this.evernightMinecraft.level;
        if (level == null || !level.isLoaded(pos)) {
            return false;
        }
        return level.getBlockEntity(pos) instanceof Container;
    }

    private void captureOpenedContainer(AbstractContainerScreen<?> screen) {
        if (this.pendingContainerPos == null) {
            return;
        }
        if (!this.containerMarkSetting.getValue() && !this.highlightChestsSetting.getValue()) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (menu.containerId == this.lastMenuId && menu.getStateId() == this.lastMenuStateId) {
            return;
        }
        this.lastMenuId = menu.containerId;
        this.lastMenuStateId = menu.getStateId();

        int slotCount = Math.min(menu.slots.size() - PLAYER_INVENTORY_SLOTS, MAX_CONTAINER_SLOTS);
        if (slotCount <= 0) {
            return;
        }
        List<ItemStack> items = new ArrayList<>(slotCount);
        for (int slot = 0; slot < slotCount; slot++) {
            items.add(menu.getSlot(slot).getItem().copy());
        }
        ContainerKey key = ContainerKey.of(this.pendingContainerPos, this.evernightMinecraft.level);
        this.trackedContainers.put(key, new ContainerRecord(items));
        this.matchingContainers.clear();
        this.lastSelectedSlot = -1;
    }

    private void pruneTrackedContainers() {
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            this.trackedContainers.clear();
            return;
        }
        this.trackedContainers.entrySet().removeIf(entry -> !isStillContainer(level, entry.getKey()));
        this.matchingContainers.retainAll(this.trackedContainers.keySet());
    }

    /** Keeps entries whose chunk is unloaded (the container may simply be out of range). */
    private static boolean isStillContainer(Level level, ContainerKey key) {
        for (BlockPos pos : key.positions()) {
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof Container)) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------------------------------
    // Tooltip preview
    // ---------------------------------------------------------------------------------------

    @EventHandler
    @SuppressWarnings("unchecked")
    private void onRenderTooltip(AbstractContainerScreenRenderTooltipEvent event) {
        if (!this.boxPreviewSetting.getValue()) {
            return;
        }
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        Slot slot = ((AbstractContainerScreenAccessor) screen).evernight$getFocusedSlot();
        if (slot == null || !slot.hasItem()) {
            return;
        }
        ItemStack stack = slot.getItem();
        if (ItemUtils.isShulkerBox(stack.getItem())) {
            List<ItemStack> contents = readShulkerItems(stack);
            if (contents != null) {
                event.getTooltipComponents().add(new ContainerTooltipComponent(contents));
            }
            return;
        }

        BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (bundle != null && !bundle.isEmpty()) {
            List<ItemStack> contents = new ArrayList<>(SHULKER_SLOTS);
            for (ItemStack item : bundle.items()) {
                if (contents.size() >= SHULKER_SLOTS) {
                    break;
                }
                contents.add(item);
            }
            while (contents.size() < SHULKER_SLOTS) {
                contents.add(ItemStack.EMPTY);
            }
            // TODO(port): the built-in Tooltips module has a bundle-specific tooltip component
            //  (BundleTooltipComponent), but it needs a live Slot and the TooltipsModule instance
            //  and is not meant to be built from an addon. This grid is the closest stable
            //  equivalent; restore the bundle layout if Evernight exposes a public builder.
            event.getTooltipComponents().add(new ContainerTooltipComponent(contents));
        }
    }

    // ---------------------------------------------------------------------------------------
    // Container box tiling
    // ---------------------------------------------------------------------------------------

    private void drawTiling(GuiGraphics graphics, AbstractContainerScreen<?> screen,
            float mouseX, float mouseY) {
        this.ensureTilingGroups(screen.getMenu());
        if (this.tilingGroups.isEmpty() || this.tilingContentHeight <= 0.0) {
            return;
        }

        float scale = this.tilingScaleSetting.getValue().floatValue();
        float scaledMouseX = mouseX / scale;
        float scaledMouseY = mouseY / scale;
        int originX = this.leftMarginSetting.getValue();
        float y = (float) (this.topMarginSetting.getValue() + this.tilingScroll);
        int spacing = this.spacingSetting.getValue();
        int guiHeight = this.evernightMinecraft.getWindow().getGuiScaledHeight();
        Font font = this.evernightMinecraft.font;
        int alpha = this.backgroundAlphaSetting.getValue();
        int background = (alpha & 0xFF) << 24;
        boolean compact = this.compactModeSetting.getValue();
        ItemStack hovered = ItemStack.EMPTY;

        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.scale(scale, scale);

        for (BoxGroup group : this.tilingGroups) {
            List<ItemStack> items = compact ? group.compactItems : group.slots;
            int count = compact ? group.compactItems.size() : SHULKER_SLOTS;
            int rows = Math.max(1, (count - 1) / GRID_COLUMNS + 1);
            int columns = Math.max(1, Math.min(GRID_COLUMNS, count));
            int panelWidth = columns * CELL_SIZE + PANEL_CHROME * 2;
            int panelHeight = rows * CELL_SIZE + PANEL_CHROME * 2;

            if (y + panelHeight >= 0.0F && y <= guiHeight / scale) {
                int panelX = originX;
                int panelTop = (int) y;
                int panelBottom = (int) (y + panelHeight);
                graphics.fill(panelX, panelTop, panelX + panelWidth, panelBottom, background);
                graphics.fill(panelX, panelTop - 1, panelX + panelWidth, panelTop, shulkerColor(group.box));

                int index = 0;
                for (ItemStack stack : items) {
                    int column = index % GRID_COLUMNS;
                    int row = index / GRID_COLUMNS;
                    int itemX = panelX + PANEL_CHROME + column * CELL_SIZE;
                    int itemY = panelTop + PANEL_CHROME + row * CELL_SIZE;
                    if (!stack.isEmpty()) {
                        this.drawTilingItem(graphics, font, stack, itemX, itemY);
                        if (scaledMouseX >= itemX && scaledMouseX < itemX + CELL_SIZE
                                && scaledMouseY >= itemY && scaledMouseY < itemY + CELL_SIZE) {
                            hovered = stack;
                        }
                    }
                    index++;
                }

                if (group.count > 1) {
                    graphics.drawString(font, "x" + group.count,
                            panelX + panelWidth + 2,
                            panelTop + panelHeight - 11,
                            0xFF00FF00,
                            true);
                }
            }
            y += panelHeight + spacing;
        }

        pose.popMatrix();

        if (!hovered.isEmpty()) {
            graphics.setTooltipForNextFrame(font, hovered, (int) mouseX, (int) mouseY);
        }
    }

    private void drawTilingItem(GuiGraphics graphics, Font font, ItemStack stack, int x, int y) {
        graphics.renderItem(stack, x, y);
        if (stack.getCount() > 999) {
            String count = String.format(Locale.ROOT, "%.1fk", stack.getCount() / 1000.0F);
            graphics.renderItemDecorations(font, stack, x, y, count);
        } else {
            graphics.renderItemDecorations(font, stack, x, y);
        }
    }

    /** Rebuilds the tiled box list only when the open menu (or its synced state) changes. */
    private void ensureTilingGroups(AbstractContainerMenu menu) {
        if (menu.containerId != this.tilingMenuId) {
            this.tilingMenuId = menu.containerId;
            this.tilingStateId = -1;
            this.tilingBuilt = false;
            this.tilingScroll = 0.0;
            this.tilingContentHeight = 0.0;
            this.tilingGroups.clear();
        }
        if (this.tilingBuilt && menu.getStateId() == this.tilingStateId) {
            return;
        }
        this.tilingBuilt = true;
        this.tilingStateId = menu.getStateId();
        this.tilingGroups.clear();

        // The tiling view intentionally scans every menu slot (chest + player inventory), which
        // is what the original's bp.b() did: kits that are still in the inventory are shown too.
        Map<ItemContainerContents, BoxGroup> groups = new LinkedHashMap<>();
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (stack.isEmpty() || !ItemUtils.isShulkerBox(stack.getItem())) {
                continue;
            }
            ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
            if (contents == null) {
                continue;
            }
            BoxGroup group = groups.get(contents);
            if (group == null) {
                List<ItemStack> slots = readSlots(contents);
                if (!hasAnyItem(slots)) {
                    continue;
                }
                group = new BoxGroup(stack.copy(), slots);
                groups.put(contents, group);
            }
            group.count++;
        }

        List<BoxGroup> sorted = new ArrayList<>(groups.values());
        sorted.sort((left, right) -> Integer.compare(right.count, left.count));
        this.tilingGroups.addAll(sorted);

        double total = this.spacingSetting.getValue();
        for (BoxGroup group : sorted) {
            int count = this.compactModeSetting.getValue()
                    ? group.compactItems.size()
                    : SHULKER_SLOTS;
            int rows = Math.max(1, (count - 1) / GRID_COLUMNS + 1);
            total += rows * CELL_SIZE + PANEL_CHROME * 2 + this.spacingSetting.getValue();
        }
        this.tilingContentHeight = total;
    }

    private void invalidateTiling() {
        this.tilingBuilt = false;
        this.tilingContentHeight = 0.0;
        this.tilingScroll = 0.0;
    }

    @EventHandler
    private void onMouseScroll(MouseHandlerOnScrollEvent event) {
        if (!this.boxTilingSetting.getValue()) {
            return;
        }
        if (!(this.evernightMinecraft.screen instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        this.ensureTilingGroups(screen.getMenu());
        if (this.tilingGroups.isEmpty()) {
            return;
        }
        double scale = this.tilingScaleSetting.getValue();
        double visibleHeight = this.evernightMinecraft.getWindow().getGuiScaledHeight() / scale;
        double minimum = Math.min(-this.tilingContentHeight + visibleHeight, 0.0);
        this.tilingScroll = Math.max(minimum,
                Math.min(0.0, this.tilingScroll + event.getVerticalOffset() * 15.0));
        // Deliberately not cancelled: vanilla container screens ignore the wheel, and other mods
        // may still want the event.
    }

    // ---------------------------------------------------------------------------------------
    // Highlighting
    // ---------------------------------------------------------------------------------------

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            this.clearState();
            return;
        }
        this.tickCounter++;
        Player player = this.evernightMinecraft.player;
        if (player == null) {
            return;
        }
        this.pruneTrackedContainers();

        int selectedSlot = player.getInventory().getSelectedSlot();
        if (selectedSlot != this.lastSelectedSlot || this.tickCounter % 20 == 0) {
            this.lastSelectedSlot = selectedSlot;
            this.updateHighlightTargets(player);
        }
    }

    private void updateHighlightTargets(Player player) {
        this.matchingContainers.clear();
        this.matchingFrames.clear();

        if (!this.highlightChestsSetting.getValue() && !this.highlightItemFramesSetting.getValue()) {
            return;
        }
        Level level = this.evernightMinecraft.level;
        if (level == null) {
            return;
        }
        ItemStack selected = displayedStack(player.getInventory().getSelectedItem());
        if (selected.isEmpty()) {
            return;
        }
        MatchKey key = MatchKey.of(selected);

        if (this.highlightChestsSetting.getValue()) {
            for (Map.Entry<ContainerKey, ContainerRecord> entry : this.trackedContainers.entrySet()) {
                if (entry.getValue().contains(key, 2)) {
                    this.matchingContainers.add(entry.getKey());
                }
            }
        }

        if (this.highlightItemFramesSetting.getValue()) {
            AABB searchBox = player.getBoundingBox().inflate(this.highlightDistanceSetting.getValue());
            for (ItemFrame frame : level.getEntitiesOfClass(ItemFrame.class, searchBox, ItemFrame::isAlive)) {
                ItemStack item = displayedStack(frame.getItem());
                if (!item.isEmpty() && MatchKey.of(item).equals(key)) {
                    this.matchingFrames.add(frame);
                }
            }
        }
    }

    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        boolean drawHighlights = !this.matchingContainers.isEmpty() || !this.matchingFrames.isEmpty();
        boolean drawWorldMarks = this.containerMarkSetting.getValue()
                && this.evernightMinecraft.screen != null;
        if (!drawHighlights && !drawWorldMarks) {
            return;
        }
        if (this.isWorldUnavailable()) {
            return;
        }
        Camera camera = this.evernightMinecraft.gameRenderer.getMainCamera();
        if (!camera.isInitialized()) {
            return;
        }
        Vec3 cameraPos = camera.position();
        double maxDistance = this.highlightDistanceSetting.getValue();
        PoseStack poses = event.getPoseStack();

        if (drawWorldMarks) {
            this.renderWorldContainerMarks(poses, cameraPos);
        }
        if (!drawHighlights) {
            return;
        }
        Color color = this.highlightColorSetting.getValue();

        if (this.highlightChestsSetting.getValue()) {
            for (ContainerKey key : this.matchingContainers) {
                AABB box = key.box();
                if (cameraPos.distanceTo(box.getCenter()) > maxDistance) {
                    continue;
                }
                AABB renderBox = box.inflate(0.002);
                WorldBoxRenderer.fillBox(poses, renderBox, color);
                WorldBoxRenderer.outlineBox(poses, renderBox, color, 1.0F);
            }
        }

        if (this.highlightItemFramesSetting.getValue()) {
            for (ItemFrame frame : this.matchingFrames) {
                if (!frame.isAlive()) {
                    continue;
                }
                AABB box = frame.getBoundingBox();
                if (cameraPos.distanceTo(box.getCenter()) > maxDistance) {
                    continue;
                }
                WorldBoxRenderer.fillBox(poses, box, color);
                WorldBoxRenderer.outlineBox(poses, box, color, 1.0F);
            }
        }
    }

    /**
     * World-space fallback for container marks while any screen is open: the HUD (and with it the
     * projected item icons) is not rendered in that state, so a small plate at the selected face
     * keeps the marks visible. This is intentionally a plain marker - the original 3D item model
     * is not available through Evernight's world render API.
     */
    private void renderWorldContainerMarks(PoseStack poses, Vec3 cameraPos) {
        double maxDistance = this.markDistanceSetting.getValue();
        for (Map.Entry<ContainerKey, ContainerRecord> entry : this.trackedContainers.entrySet()) {
            if (entry.getValue().marker.isEmpty()) {
                continue;
            }
            ContainerFace face = pickFace(entry.getKey(), cameraPos);
            if (face == null) {
                continue;
            }
            Vec3 center = face.center();
            if (cameraPos.distanceTo(center) > maxDistance) {
                continue;
            }
            AABB plate = new AABB(
                    center.x - 0.16, center.y - 0.16, center.z - 0.16,
                    center.x + 0.16, center.y + 0.16, center.z + 0.16);
            WorldBoxRenderer.fillBox(poses, plate, WORLD_MARK_COLOR);
            WorldBoxRenderer.outlineBox(poses, plate, WORLD_MARK_COLOR, 1.0F);
        }
    }

    // ---------------------------------------------------------------------------------------
    // Shulker / item helpers
    // ---------------------------------------------------------------------------------------

    /**
     * Returns the 27 shulker contents (empty stacks included), or {@code null} when the stack has
     * no container component or the container is empty.
     */
    private static List<ItemStack> readShulkerItems(ItemStack stack) {
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return null;
        }
        List<ItemStack> items = readSlots(contents);
        return hasAnyItem(items) ? items : null;
    }

    private static List<ItemStack> readSlots(ItemContainerContents contents) {
        NonNullList<ItemStack> slots = NonNullList.withSize(SHULKER_SLOTS, ItemStack.EMPTY);
        contents.copyInto(slots);
        return new ArrayList<>(slots);
    }

    private static boolean hasAnyItem(List<ItemStack> items) {
        for (ItemStack item : items) {
            if (!item.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replaces a shulker box stack with the dominant item inside it (one level deep); any other
     * stack is returned unchanged. Used for highlights and container marks.
     */
    private static ItemStack displayedStack(ItemStack stack) {
        if (stack.isEmpty() || !ItemUtils.isShulkerBox(stack.getItem())) {
            return stack;
        }
        List<ItemStack> contents = readShulkerItems(stack);
        if (contents == null) {
            return stack;
        }
        ItemStack dominant = dominantStack(contents);
        return dominant.isEmpty() ? stack : dominant;
    }

    /** Item with the highest {@code count / maxStackSize} ratio and its summed count. */
    private static ItemStack dominantStack(List<ItemStack> items) {
        Map<StackKey, Integer> counts = new LinkedHashMap<>();
        Map<StackKey, ItemStack> samples = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            StackKey key = new StackKey(stack);
            counts.merge(key, stack.getCount(), Integer::sum);
            samples.putIfAbsent(key, stack.copyWithCount(1));
        }

        StackKey best = null;
        double bestRatio = 0.0;
        for (Map.Entry<StackKey, Integer> entry : counts.entrySet()) {
            ItemStack sample = samples.get(entry.getKey());
            double ratio = entry.getValue() / (double) sample.getItem().getDefaultMaxStackSize();
            if (ratio > bestRatio) {
                bestRatio = ratio;
                best = entry.getKey();
            }
        }
        if (best == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = samples.get(best).copy();
        result.setCount(counts.get(best));
        return result;
    }

    private static List<ItemStack> compactItems(List<ItemStack> items) {
        Map<StackKey, ItemStack> merged = new LinkedHashMap<>();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            StackKey key = new StackKey(stack);
            ItemStack existing = merged.get(key);
            if (existing == null) {
                merged.put(key, stack.copy());
            } else {
                existing.setCount(existing.getCount() + stack.getCount());
            }
        }
        return new ArrayList<>(merged.values());
    }

    private static int shulkerColor(ItemStack stack) {
        if (stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock shulkerBox) {
            DyeColor color = shulkerBox.getColor();
            if (color != null) {
                return 0xFF000000 | color.getTextureDiffuseColor();
            }
        }
        return 0xFF976B4B;
    }

    // ---------------------------------------------------------------------------------------
    // Data holders
    // ---------------------------------------------------------------------------------------

    /** Item identity without the count, used for grouping and dominant-item selection. */
    private static final class StackKey {
        private final ItemStack sample;
        private final int hash;

        private StackKey(ItemStack stack) {
            this.sample = stack.copyWithCount(1);
            this.hash = ItemStack.hashItemAndComponents(this.sample);
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof StackKey key
                    && ItemStack.isSameItemSameComponents(this.sample, key.sample);
        }

        @Override
        public int hashCode() {
            return this.hash;
        }
    }

    /**
     * Item identity used for "does this container hold the selected item" checks. Mirrors the
     * original {@code g}: the item plus the components that matter for stacking (stored
     * enchantments of enchanted books, potion contents, map id, ominous amplifier). Custom names,
     * damage and other components are deliberately ignored, so a renamed item still matches.
     */
    private record MatchKey(Item item, ItemEnchantments enchantments, PotionContents potion,
            MapId mapId, OminousBottleAmplifier ominous) {
        private static MatchKey of(ItemStack stack) {
            Item item = stack.getItem();
            ItemEnchantments enchantments = item == Items.ENCHANTED_BOOK
                    ? EnchantmentHelper.getEnchantmentsForCrafting(stack)
                    : null;
            PotionContents potion = stack.get(DataComponents.POTION_CONTENTS);
            if (potion != null && potion.equals(PotionContents.EMPTY)) {
                potion = null;
            }
            return new MatchKey(item, enchantments, potion,
                    stack.get(DataComponents.MAP_ID),
                    stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER));
        }
    }

    /** One container (single block) or a double chest (two adjacent halves). */
    private record ContainerKey(BlockPos first, BlockPos second) {
        private static ContainerKey of(BlockPos pos, Level level) {
            BlockPos first = pos;
            BlockPos second = null;
            if (level != null) {
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof ChestBlock
                        && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                    Direction facing = state.getValue(ChestBlock.FACING);
                    Direction side = state.getValue(ChestBlock.TYPE) == ChestType.LEFT
                            ? facing.getClockWise()
                            : facing.getCounterClockWise();
                    BlockPos other = pos.relative(side);
                    BlockState otherState = level.getBlockState(other);
                    if (otherState.getBlock() instanceof ChestBlock
                            && otherState.getValue(ChestBlock.FACING) == facing) {
                        second = other;
                    }
                }
            }
            if (second != null && first.compareTo(second) > 0) {
                BlockPos swap = first;
                first = second;
                second = swap;
            }
            return new ContainerKey(first.immutable(), second == null ? null : second.immutable());
        }

        private List<BlockPos> positions() {
            return this.second == null ? List.of(this.first) : List.of(this.first, this.second);
        }

        private AABB box() {
            AABB box = new AABB(this.first);
            return this.second == null ? box : box.minmax(new AABB(this.second));
        }
    }

    /** Snapshot of an opened container's slots plus the item shown on its face. */
    private static final class ContainerRecord {
        private final List<ItemStack> items;
        private final ItemStack marker;

        private ContainerRecord(List<ItemStack> items) {
            this.items = items;
            this.marker = displayedStack(dominantStack(items));
        }

        private boolean contains(MatchKey key, int depth) {
            return containsItems(this.items, key, depth);
        }

        private static boolean containsItems(List<ItemStack> items, MatchKey key, int depth) {
            for (ItemStack stack : items) {
                if (stack.isEmpty()) {
                    continue;
                }
                if (MatchKey.of(stack).equals(key)) {
                    return true;
                }
                if (depth > 0 && ItemUtils.isShulkerBox(stack.getItem())) {
                    List<ItemStack> nested = readShulkerItems(stack);
                    if (nested != null && containsItems(nested, key, depth - 1)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    /** A distinct shulker-box content signature found in the open container. */
    private static final class BoxGroup {
        private final ItemStack box;
        private final List<ItemStack> slots;
        private final List<ItemStack> compactItems;
        private int count;

        private BoxGroup(ItemStack box, List<ItemStack> slots) {
            this.box = box;
            this.slots = slots;
            this.compactItems = compactItems(slots);
        }
    }

    /** Face selected for a container mark. */
    private record ContainerFace(Vec3 center) {
    }

    // TODO(port): remaining gaps compared to the original {@code ar} / its helper classes
    //  {@code aS_}, {@code aT_}, {@code aL_}, {@code aP_}, {@code aO_}:
    //  - Container marks were 3D item models rendered in the world (Render3DEvent + the
    //    SubmitNodeCollector supplied by Lotus' own {@code L_} event). Evernight 1.0.3 does not
    //    expose that collector through WorldRenderEvent, so this port projects a 2D item icon in
    //    HudRenderEvent instead. Consequences: while a screen is open (HUD suppressed) only a
    //    small world-space plate marks the face, and the icon size is an approximation rather
    //    than a true perspective projection.
    //  - The "pin preview" key (Meteor KeybindSetting, default Ctrl) and the nested pinned preview
    //    are not ported. Pinning needs the custom tooltip renderer / positioner that Lotus ships
    //    ({@code aL_}/{@code aN_}/{@code aM_}); Evernight offers no setting-level keybind and
    //    InputPressEvent is not published while a container screen is open, so a faithful version
    //    would need its own screen-level input mixin. Hover preview itself works.
    //  - "PersistCache" (container marks saved to disk) is not ported; see the constructor TODO.
    //  - The original cancels the vanilla container tooltip text when a preview is shown
    //    ({@code aL_.a_q}); here the preview is appended below the normal tooltip text, which is
    //    also how Evernight's built-in Tooltips module behaves.
}
