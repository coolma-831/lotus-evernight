package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.render.WorldRenderEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.mesh.WorldBoxRenderer;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.utils.chat.ChatUtils;
import evernight.client.utils.inventory.ContainerInteractionUtils;
import evernight.client.utils.inventory.InventoryUtils;
import evernight.client.utils.world.BlockInteractionUtils;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;

/**
 * Port of {@code ab} (M自动贴画) from Lotus (author xiaohe66, GPL-3.0).
 *
 * <p>Usage: right-click two corners while holding item frames; the module remembers both empty
 * blocks, waits until item frames exist there, then fills the resulting rectangle with map-art
 * paintings in the configured order.
 *
 * <p>Behaviour carried over from upstream:
 * <ul>
 *     <li>paintings are taken from the inventory, from the hotbar and, one per pass, from bundles;</li>
 *     <li>targets outside the vanilla interaction range are skipped until the player walks closer;</li>
 *     <li>item frames are placed with the frame item currently held (main hand, off hand or hotbar).</li>
 * </ul>
 *
 * <p>Meteor-only machinery ({@code aw}/{@code ag} state base, {@code aK_} target holder, {@code bD_}
 * name comparator, GUI code) is reimplemented locally with fields and the {@link State} enum.
 */
public final class AutoPaintingModule extends AddonModule {
    /** Inventory slots searched for frames / paintings / bundles, same as upstream. */
    private static final int INVENTORY_SIZE = 36;
    /** Same colour upstream passed to {@code Renderer3D.box(...)} for the planned targets. */
    private static final Color TARGET_COLOR = Color.ORANGE;
    /** Upstream {@code bD_} pattern: map art names look like {@code art_<x>_<y>}. */
    private static final Pattern MAP_ART_COORDINATES = Pattern.compile("_(\\d+)_(\\d+)");
    private static final Comparator<String> MAP_ART_ORDER = AutoPaintingModule::compareMapArtNames;

    private final Setting<PlaceOrder> placeOrderSetting;
    private final Setting<Integer> delaySetting;

    private final List<Target> targets = new ArrayList<>();

    private State state = State.NONE;
    /** First clicked empty block (frame corner), also rendered while the plan is empty. */
    private BlockPos cornerA;
    /** Second clicked empty block. */
    private BlockPos cornerB;
    /** Direction from the wall into the frame; the opposite of the frames' facing. */
    private Direction facing;
    /** Round-robin cursor over {@link #targets}; upstream started at 1 to skip {@link #cornerA}. */
    private int cursor;
    /** Remaining ticks before the state machine runs again (upstream {@code ag.d()}). */
    private int delay;
    /** Previous state of the use key, so one physical click is captured once. */
    private boolean useKeyWasDown;

    public AutoPaintingModule(ModuleCategory category) {
        super("lotus", "LotusAutoPainting",
                "Place item frames at two corners, then fills the area with paintings automatically. "
                        + "Takes paintings out of bundles and waits until out-of-reach spots come close.",
                category);
        this.placeOrderSetting = this.add(new EnumSetting<>("PlaceOrder", PlaceOrder.VerticalFirst));
        this.delaySetting = this.add(new NumberSetting<>("Delay", 5, 0, 40));
    }

    @Override
    public void onEnable() {
        this.reset();
        this.useKeyWasDown = false;
    }

    @Override
    public void onDisable() {
        this.reset();
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.isWorldUnavailable()) {
            return;
        }
        if (this.delay > 0) {
            this.delay--;
        } else {
            switch (this.state) {
                case READY -> this.collectTargets();
                case PLACE -> this.fillTargets();
                case NONE -> {
                }
            }
        }
        // Upstream captured corners from ClientTickEvents.END_CLIENT_TICK, i.e. after the state
        // machine for that tick; keep the same order inside the single tick event.
        this.captureCornerOnUse();
    }

    @EventHandler
    private void onWorldRender(WorldRenderEvent.Main event) {
        if (this.targets.isEmpty()) {
            if (this.cornerA != null) {
                WorldBoxRenderer.outlineBox(event.getPoseStack(), this.cornerA, TARGET_COLOR, 1.0F);
            }
            return;
        }
        for (Target target : this.targets) {
            WorldBoxRenderer.outlineBox(event.getPoseStack(), target.position, TARGET_COLOR, 1.0F);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Corner capture (upstream ab.D() driven by the use key)
    // ---------------------------------------------------------------------------------------------

    private void captureCornerOnUse() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null || this.state == State.PLACE) {
            return;
        }
        boolean useDown = this.evernightMinecraft.options.keyUse.isDown();
        if (useDown && !this.useKeyWasDown) {
            this.captureCorner(player);
        }
        this.useKeyWasDown = useDown;
    }

    private void captureCorner(LocalPlayer player) {
        if (!this.isItemFrame(player.getItemInHand(InteractionHand.MAIN_HAND))
                && !this.isItemFrame(player.getItemInHand(InteractionHand.OFF_HAND))) {
            return;
        }
        if (!(this.evernightMinecraft.hitResult instanceof BlockHitResult hit)
                || hit.getType() != net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return;
        }
        BlockPos corner = hit.getBlockPos().relative(hit.getDirection()).immutable();
        if (this.cornerA == null) {
            this.cornerA = corner;
            return;
        }
        this.cornerB = corner;
        this.state = State.READY;
    }

    // ---------------------------------------------------------------------------------------------
    // State READY (upstream ab.w()): both frames placed -> plan the rectangle and the paintings
    // ---------------------------------------------------------------------------------------------

    private void collectTargets() {
        ItemFrame first = this.findFrame(this.cornerA);
        ItemFrame second = this.findFrame(this.cornerB);
        if (first == null || second == null) {
            return;
        }

        // Upstream used Meteor's own ItemFrame mixin (ItemFrame#getNearestViewDirection); vanilla
        // 1.21.11 only exposes HangingEntity#getDirection(). Both frames are read through the same
        // call and the plane axis below is identical for a direction and for its opposite.
        // TODO(port): confirm in game that two frames on the same wall report the same direction and
        //             that the resulting plane always matches the two clicked corners.
        Direction direction = first.getDirection();
        if (direction != second.getDirection()) {
            this.message("addons.lotus.messages.paintingDirectionMismatch");
            return;
        }

        this.facing = direction.getOpposite();
        this.targets.clear();
        if (!this.buildTargets()) {
            return;
        }
        this.assignPaintings();
        this.resetDelay();
        this.cursor = 1;
        this.state = State.PLACE;
    }

    /**
     * Upstream {@code ab.F()}: enumerates every block between the two corners in the configured
     * order. Returns {@code false} when the corners are not on the same plane (module is disabled).
     */
    private boolean buildTargets() {
        boolean verticalFirst = this.placeOrderSetting.getValue() == PlaceOrder.VerticalFirst;
        int x1 = this.cornerA.getX();
        int y1 = this.cornerA.getY();
        int z1 = this.cornerA.getZ();
        int x2 = this.cornerB.getX();
        int y2 = this.cornerB.getY();
        int z2 = this.cornerB.getZ();

        if (this.facing == Direction.NORTH || this.facing == Direction.SOUTH) {
            if (z1 != z2) {
                return this.rejectPlane();
            }
            if (this.facing == Direction.NORTH) {
                if (verticalFirst) {
                    for (int x = x1; x <= x2; x++) {
                        for (int y = y1; y >= y2; y--) {
                            this.addTarget(x, y, z1);
                        }
                    }
                } else {
                    for (int y = y1; y >= y2; y--) {
                        for (int x = x1; x <= x2; x++) {
                            this.addTarget(x, y, z1);
                        }
                    }
                }
            } else if (verticalFirst) {
                for (int x = x1; x >= x2; x--) {
                    for (int y = y1; y >= y2; y--) {
                        this.addTarget(x, y, z1);
                    }
                }
            } else {
                for (int y = y1; y >= y2; y--) {
                    for (int x = x1; x >= x2; x--) {
                        this.addTarget(x, y, z1);
                    }
                }
            }
            return true;
        }

        if (this.facing == Direction.WEST || this.facing == Direction.EAST) {
            if (x1 != x2) {
                return this.rejectPlane();
            }
            if (this.facing == Direction.WEST) {
                if (verticalFirst) {
                    for (int z = z1; z >= z2; z--) {
                        for (int y = y1; y >= y2; y--) {
                            this.addTarget(x1, y, z);
                        }
                    }
                } else {
                    for (int y = y1; y >= y2; y--) {
                        for (int z = z1; z >= z2; z--) {
                            this.addTarget(x1, y, z);
                        }
                    }
                }
            } else if (verticalFirst) {
                for (int z = z1; z <= z2; z++) {
                    for (int y = y1; y >= y2; y--) {
                        this.addTarget(x1, y, z);
                    }
                }
            } else {
                for (int y = y1; y >= y2; y--) {
                    for (int z = z1; z <= z2; z++) {
                        this.addTarget(x1, y, z);
                    }
                }
            }
            return true;
        }

        // Frames attached to a floor or ceiling have no supported plane upstream either: the target
        // list stays empty and the PLACE state reports "done" on the next pass.
        return true;
    }

    private boolean rejectPlane() {
        this.message("addons.lotus.messages.paintingNotOnPlane");
        this.toggle();
        return false;
    }

    private void addTarget(int x, int y, int z) {
        this.targets.add(new Target(new BlockPos(x, y, z)));
    }

    /**
     * Upstream {@code ab.E()}: marks targets whose frame already holds a painting and hands out the
     * painting names found in the inventory (including bundle contents) in natural map-art order.
     */
    private void assignPaintings() {
        int missing = 0;
        for (Target target : this.targets) {
            ItemFrame frame = this.findFrame(target.position);
            boolean filled = frame != null && !frame.getItem().isEmpty();
            target.filled = filled;
            if (!filled) {
                missing++;
            }
        }

        List<String> available = new ArrayList<>();
        Inventory inventory = this.evernightMinecraft.player.getInventory();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.getItem() == Items.FILLED_MAP) {
                this.addPaintingNames(available, stack);
            } else if (stack.getItem() instanceof BundleItem) {
                BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
                if (contents == null) {
                    continue;
                }
                for (ItemStack inner : contents.items()) {
                    if (inner.getItem() == Items.FILLED_MAP) {
                        this.addPaintingNames(available, inner);
                    }
                }
            }
        }

        if (available.size() < missing) {
            this.message("addons.lotus.messages.paintingNotEnough", missing, available.size());
            this.toggle();
            return;
        }

        available.sort(MAP_ART_ORDER);
        int index = 0;
        for (Target target : this.targets) {
            if (!target.filled) {
                target.painting = available.get(index++);
            }
        }
    }

    private void addPaintingNames(List<String> names, ItemStack stack) {
        Component customName = stack.getCustomName();
        if (customName == null) {
            return;
        }
        for (int copy = 0; copy < stack.getCount(); copy++) {
            names.add(customName.getString());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // State PLACE (upstream ab.x()): place frames / put paintings until every target is filled
    // ---------------------------------------------------------------------------------------------

    private void fillTargets() {
        Map<String, Integer> missing = new HashMap<>();
        for (Target target : this.targets) {
            ItemFrame frame = this.findFrame(target.position);
            if ((frame == null || frame.getItem().isEmpty()) && target.painting != null) {
                missing.merge(target.painting, 1, Integer::sum);
            }
        }

        if (missing.isEmpty()) {
            this.message("addons.lotus.messages.paintingDone");
            this.toggle();
            return;
        }

        Set<String> needed = this.neededPaintings(missing);
        if (!needed.isEmpty() && this.tryTakePaintingFromBundle(needed)) {
            return;
        }

        Target mainHandTarget = null;
        ItemFrame mainHandFrame = null;
        Target hotbarTarget = null;
        ItemFrame hotbarFrame = null;
        Target otherTarget = null;
        ItemFrame otherFrame = null;
        int size = this.targets.size();

        for (int i = 0; i < size; i++) {
            Target target = this.targets.get(this.cursor);
            this.cursor++;
            if (this.cursor >= size) {
                this.cursor = 0;
            }

            ItemFrame frame = this.findFrame(target.position);
            if (frame != null && !frame.getItem().isEmpty()) {
                continue;
            }
            if (this.isOutOfReach(target.position, frame)) {
                continue;
            }
            String painting = target.painting;
            if (painting == null) {
                continue;
            }

            if (frame == null) {
                if (otherTarget == null) {
                    otherTarget = target;
                }
                continue;
            }
            if (this.paintingHand(painting) != null) {
                mainHandTarget = target;
                mainHandFrame = frame;
                break;
            }
            if (this.findPaintingInHotbar(painting) >= 0) {
                if (hotbarTarget == null) {
                    hotbarTarget = target;
                    hotbarFrame = frame;
                }
                continue;
            }
            if (this.findPaintingInInventory(painting) >= 0 && otherTarget == null) {
                otherTarget = target;
                otherFrame = frame;
            }
        }

        if (mainHandTarget != null) {
            this.putPainting(mainHandTarget, mainHandFrame);
        } else if (hotbarTarget != null) {
            this.putPainting(hotbarTarget, hotbarFrame);
        } else if (otherTarget != null) {
            if (otherFrame == null) {
                this.placeItemFrame(otherTarget);
            } else {
                this.putPainting(otherTarget, otherFrame);
            }
        } else {
            if (!needed.isEmpty() && this.findBundleWithPainting(needed) < 0) {
                this.message("addons.lotus.messages.paintingMissing", needed);
            } else {
                this.message("addons.lotus.messages.paintingOutOfReach");
            }
            this.defer(20);
        }
    }

    /**
     * Upstream {@code ab.a_aK_ItemFrame(...)}: places the missing frame, or moves/interacts with the
     * painting according to where it currently is.
     */
    private void putPainting(Target target, ItemFrame frame) {
        InteractionHand hand = this.paintingHand(target.painting);
        if (hand != null) {
            BlockInteractionUtils.interactWithEntity(frame, hand);
            this.resetDelay();
            return;
        }
        int hotbarSlot = this.findPaintingInHotbar(target.painting);
        if (hotbarSlot >= 0) {
            // Upstream swapped the painting into the hotbar and interacted on the next pass.
            InventoryUtils.selectHotbarSlot(hotbarSlot);
            return;
        }
        int inventorySlot = this.findPaintingInInventory(target.painting);
        if (inventorySlot >= 0) {
            InventoryUtils.swapIntoSelectedSlot(inventorySlot);
            return;
        }
        this.message("addons.lotus.messages.paintingMissingNamed", target.painting);
        this.resetDelay();
    }

    private void placeItemFrame(Target target) {
        InteractionHand hand = this.itemFrameHand();
        if (hand == null) {
            int slot = this.findItemFrameSlot();
            if (slot < 0) {
                this.message("addons.lotus.messages.paintingMissingFrame");
                this.toggle();
                return;
            }
            if (slot < 9) {
                InventoryUtils.selectHotbarSlot(slot);
            } else {
                // The click only lands after the server answers; retry on the next tick instead of
                // placing with the stale held item.
                InventoryUtils.swapIntoSelectedSlot(slot);
                return;
            }
            hand = this.itemFrameHand();
            if (hand == null) {
                return;
            }
        }
        // Upstream used Meteor's BlockUtils.place(...), which also rotated the player. Evernight's
        // placeBlock sends the use packet without rotating; vanilla servers only check the distance.
        // TODO(port): request a rotation through ClientContext.rotationManager if a server or
        //             anti-cheat rejects unrotated frame placements.
        BlockInteractionUtils.placeBlock(target.position, false, hand);
        this.resetDelay();
    }

    /**
     * Upstream {@code ab.a_Set_toBoolean(...)}: when the needed painting is only inside a bundle,
     * close foreign screens and pull one item out of the bundle into an empty slot.
     *
     * @return {@code true} when this pass did something and the caller must wait
     */
    private boolean tryTakePaintingFromBundle(Set<String> needed) {
        if (!(this.evernightMinecraft.player.containerMenu instanceof InventoryMenu)) {
            ContainerInteractionUtils.closeScreen();
            this.resetDelay();
            return true;
        }
        int bundleSlot = this.findBundleWithPainting(needed);
        if (bundleSlot < 0) {
            return false;
        }
        int emptySlot = this.firstEmptySlot();
        if (emptySlot < 0) {
            return false;
        }

        int bundleMenuSlot = InventoryUtils.toMenuSlot(bundleSlot);
        int emptyMenuSlot = InventoryUtils.toMenuSlot(emptySlot);
        // Upstream bp.d(...): pick the bundle up, right-click the empty slot so vanilla pops one
        // bundled item into it, then put the bundle back into its own slot.
        InventoryUtils.clickSlot(bundleMenuSlot, 0, ClickType.PICKUP);
        InventoryUtils.clickSlot(emptyMenuSlot, 1, ClickType.PICKUP);
        InventoryUtils.clickSlot(bundleMenuSlot, 0, ClickType.PICKUP);
        this.resetDelay();
        return true;
    }

    /** Upstream {@code ab.a_Map_toSet(...)}: required paintings that are not directly available. */
    private Set<String> neededPaintings(Map<String, Integer> missing) {
        Map<String, Integer> remaining = new HashMap<>(missing);
        Inventory inventory = this.evernightMinecraft.player.getInventory();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            Component customName = stack.getCustomName();
            if (stack.getItem() == Items.FILLED_MAP && customName != null) {
                remaining.merge(customName.getString(), -stack.getCount(), Integer::sum);
            }
        }
        Set<String> needed = new HashSet<>();
        for (Map.Entry<String, Integer> entry : remaining.entrySet()) {
            if (entry.getKey() != null && entry.getValue() > 0) {
                needed.add(entry.getKey());
            }
        }
        return needed;
    }

    /** Upstream {@code ab.b_Set_toInt(...)}: inventory slot of a bundle holding a needed painting. */
    private int findBundleWithPainting(Set<String> needed) {
        Inventory inventory = this.evernightMinecraft.player.getInventory();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!(stack.getItem() instanceof BundleItem)) {
                continue;
            }
            BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
            if (contents == null) {
                continue;
            }
            for (ItemStack inner : contents.items()) {
                Component customName = inner.getCustomName();
                if (inner.getItem() == Items.FILLED_MAP && customName != null
                        && needed.contains(customName.getString())) {
                    return slot;
                }
            }
        }
        return -1;
    }

    /** Upstream {@code ag.p()}: first empty inventory slot (hotbar first). */
    private int firstEmptySlot() {
        Inventory inventory = this.evernightMinecraft.player.getInventory();
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    // ---------------------------------------------------------------------------------------------
    // Item / frame lookups
    // ---------------------------------------------------------------------------------------------

    private ItemFrame findFrame(BlockPos position) {
        if (position == null || this.evernightMinecraft.level == null) {
            return null;
        }
        AABB box = new AABB(position.getX(), position.getY(), position.getZ(),
                position.getX() + 1.0, position.getY() + 1.0, position.getZ() + 1.0);
        List<ItemFrame> frames = this.evernightMinecraft.level.getEntitiesOfClass(
                ItemFrame.class, box, frame -> frame.blockPosition().equals(position));
        return frames.isEmpty() ? null : frames.getFirst();
    }

    /** Upstream {@code ab.a_BlockPos_ItemFrame(...)}: is the spot outside the interaction range? */
    private boolean isOutOfReach(BlockPos position, ItemFrame frame) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return true;
        }
        double range = frame == null ? player.blockInteractionRange() : player.entityInteractionRange();
        return player.getEyePosition().distanceToSqr(position.getCenter()) > range * range;
    }

    private boolean isItemFrame(ItemStack stack) {
        return stack.is(Items.ITEM_FRAME) || stack.is(Items.GLOW_ITEM_FRAME);
    }

    private boolean isPainting(ItemStack stack, String name) {
        if (stack.getItem() != Items.FILLED_MAP) {
            return false;
        }
        Component customName = stack.getCustomName();
        return customName != null && customName.getString().equals(name);
    }

    /** Hand currently holding the given painting, or {@code null}. */
    private InteractionHand paintingHand(String name) {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        if (this.isPainting(player.getMainHandItem(), name)) {
            return InteractionHand.MAIN_HAND;
        }
        if (this.isPainting(player.getOffhandItem(), name)) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    /** Hand currently holding an item frame, or {@code null}. */
    private InteractionHand itemFrameHand() {
        LocalPlayer player = this.evernightMinecraft.player;
        if (player == null) {
            return null;
        }
        if (this.isItemFrame(player.getMainHandItem())) {
            return InteractionHand.MAIN_HAND;
        }
        if (this.isItemFrame(player.getOffhandItem())) {
            return InteractionHand.OFF_HAND;
        }
        return null;
    }

    private int findPaintingInHotbar(String name) {
        return InventoryUtils.findHotbarSlot(stack -> this.isPainting(stack, name));
    }

    private int findPaintingInInventory(String name) {
        // excludeHotbar = true: hotbar candidates were already handled by the caller.
        return InventoryUtils.findInventorySlot(stack -> this.isPainting(stack, name), true);
    }

    private int findItemFrameSlot() {
        int hotbarSlot = InventoryUtils.findHotbarSlot(this::isItemFrame);
        if (hotbarSlot >= 0) {
            return hotbarSlot;
        }
        return InventoryUtils.findInventorySlot(this::isItemFrame, false);
    }

    // ---------------------------------------------------------------------------------------------
    // Delay / lifecycle / messages
    // ---------------------------------------------------------------------------------------------

    /** Upstream {@code ag.l()}: wait for the configured delay before the next action. */
    private void resetDelay() {
        this.delay = this.delaySetting.getValue();
    }

    /** Upstream {@code ag.c(int)}: wait for an explicit number of ticks. */
    private void defer(int ticks) {
        this.delay = ticks;
    }

    /** Upstream {@code ab.G()}. */
    private void reset() {
        this.cornerA = null;
        this.cornerB = null;
        this.facing = null;
        this.targets.clear();
        this.cursor = 0;
        this.delay = 0;
        this.state = State.NONE;
    }

    private void message(String key, Object... args) {
        String text = Translations.get(key);
        if (args.length > 0) {
            text = String.format(Locale.ROOT, text, args);
        }
        ChatUtils.printChat(Component.literal(text), ChatUtils.moduleSignature(this));
    }

    /** Upstream {@code bD_}: compare {@code <prefix>_<x>_<y>} map-art names naturally. */
    private static int compareMapArtNames(String first, String second) {
        Matcher firstMatcher = MAP_ART_COORDINATES.matcher(first);
        String firstPrefix = first;
        int firstX = 0;
        int firstY = 0;
        if (firstMatcher.find()) {
            firstPrefix = first.substring(0, firstMatcher.start());
            firstX = Integer.parseInt(firstMatcher.group(1));
            firstY = Integer.parseInt(firstMatcher.group(2));
        }

        Matcher secondMatcher = MAP_ART_COORDINATES.matcher(second);
        String secondPrefix = second;
        int secondX = 0;
        int secondY = 0;
        if (secondMatcher.find()) {
            secondPrefix = second.substring(0, secondMatcher.start());
            secondX = Integer.parseInt(secondMatcher.group(1));
            secondY = Integer.parseInt(secondMatcher.group(2));
        }

        int result = firstPrefix.compareTo(secondPrefix);
        if (result == 0) {
            result = Integer.compare(firstX, secondX);
            if (result == 0) {
                result = Integer.compare(firstY, secondY);
            }
        }
        return result;
    }

    /** One block of the planned rectangle (upstream {@code aK_}). */
    private static final class Target {
        private final BlockPos position;
        private String painting;
        private boolean filled;

        private Target(BlockPos position) {
            this.position = position;
        }
    }

    /** Upstream {@code o}: iteration order of the rectangle. */
    public enum PlaceOrder implements NamedEntry {
        HorizontalFirst("HorizontalFirst"),
        VerticalFirst("VerticalFirst");

        private final String name;

        PlaceOrder(String name) {
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

    /** Replaces upstream's {@code bf} state objects; only the states this module uses are kept. */
    private enum State {
        NONE,
        READY,
        PLACE
    }
}
