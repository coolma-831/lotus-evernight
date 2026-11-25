package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.NamedEntry;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.EnumSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import net.minecraft.network.chat.Component;

/**
 * Port of {@code ao} (Lotus助手) from Lotus (author xiaohe66).
 *
 * <p>原模块是插件级的辅助设置：数据目录命名方式，以及仓库管理时要跳过的锁定物品栏。
 * 后续移植的仓库类模块会读取 {@link #getLockedSlots()}。
 */
public final class LotusHelperModule extends AddonModule {
    private final Setting<ServerIdMode> serverIdModeSetting;
    private final Setting<String> lockedSlotsSetting;

    private final Set<Integer> lockedSlots = new LinkedHashSet<>();

    public LotusHelperModule(ModuleCategory category) {
        super("lotus", "LotusHelper",
                "Shared helper settings for the Lotus addon: data folder naming and locked inventory slots.",
                category);
        this.serverIdModeSetting = this.add(new EnumSetting<>("ServerIdMode", ServerIdMode.ServerAddress));
        this.lockedSlotsSetting = this.add(new StringSetting("LockedSlots", "0,1,2,8"));
        this.parseLockedSlots(this.lockedSlotsSetting.getValue(), false);
        this.lockedSlotsSetting.setChangeCallback(
                () -> this.parseLockedSlots(this.lockedSlotsSetting.getValue(), true));
    }

    /** 当前要跳过的物品栏下标（0-35）。*/
    public Set<Integer> getLockedSlots() {
        return this.lockedSlots;
    }

    public ServerIdMode getServerIdMode() {
        return this.serverIdModeSetting.getValue();
    }

    private void parseLockedSlots(String value, boolean notify) {
        this.lockedSlots.clear();
        if (value != null && !value.isBlank()) {
            for (String part : value.trim().split(",")) {
                try {
                    int slot = Integer.parseInt(part.trim());
                    if (slot >= 0 && slot < 36) {
                        this.lockedSlots.add(slot);
                    }
                } catch (NumberFormatException ignored) {
                    // 与上游一致：非法项静默跳过。
                }
            }
        }
        if (notify) {
            ChatUtils.printChat(
                    Component.literal(Translations.get("addons.lotus.messages.lockedSlots")
                            + " " + new TreeSet<>(this.lockedSlots)),
                    ChatUtils.moduleSignature(this));
        }
    }

    /** 原 {@code ap}：保存数据时文件夹用服务器地址还是服务器名称。*/
    public enum ServerIdMode implements NamedEntry {
        ServerAddress("ServerAddress"),
        ServerName("ServerName");

        private final String name;

        ServerIdMode(String name) {
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
