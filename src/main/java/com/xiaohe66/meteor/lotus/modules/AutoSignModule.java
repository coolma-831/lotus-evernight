package com.xiaohe66.meteor.lotus.modules;

import evernight.addon.AddonModule;
import evernight.client.events.core.EventHandler;
import evernight.client.events.network.ClientPacketListenerHandleOpenSignEditorEvent;
import evernight.client.events.network.PacketEvent;
import evernight.client.events.ui.MinecraftTickEvent;
import evernight.client.modules.ModuleCategory;
import evernight.client.render.text.Translations;
import evernight.client.settings.core.Setting;
import evernight.client.settings.types.BooleanSetting;
import evernight.client.settings.types.NumberSetting;
import evernight.client.settings.types.StringSetting;
import evernight.client.utils.chat.ChatUtils;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/**
 * Port of {@code ae} (L自动签名) from Lotus (author xiaohe66).
 *
 * <p>服务器打开告示牌编辑界面时，自动按 4 行模板填好并直接提交。
 * 支持 {@code ${name}}（玩家名）与 {@code $time{yyyy.MM.dd HH:mm}}（自定义时间格式）占位符。
 *
 * <p>{@code TODO(port)}：原版还能在手动编辑告示牌时把文本同步回设置，这里只做了发包同步；
 * 打开界面后预填文本需要改 {@code AbstractSignEditScreen}，暂未移植。
 */
public final class AutoSignModule extends AddonModule {
    private static final DateTimeFormatter DEFAULT_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private final Setting<String> line1Setting;
    private final Setting<String> line2Setting;
    private final Setting<String> line3Setting;
    private final Setting<String> line4Setting;
    private final Setting<Boolean> onlyEmptySetting;
    private final Setting<Boolean> autoSubmitSetting;
    private final Setting<Boolean> syncSettingsSetting;
    private final Setting<Integer> delaySetting;

    private int tickDelay;

    public AutoSignModule(ModuleCategory category) {
        super("lotus", "LotusAutoSign",
                "Fills and submits sign text automatically when a sign editor opens.", category);
        this.line1Setting = this.add(new StringSetting("Line1", "${name}"));
        this.line2Setting = this.add(new StringSetting("Line2", "was here,到此一游"));
        this.line3Setting = this.add(new StringSetting("Line3", "lotus by xiaohe66"));
        this.line4Setting = this.add(new StringSetting("Line4", "$time{yyyy.MM.dd HH:mm}"));
        this.onlyEmptySetting = this.add(new BooleanSetting("OnlyEmpty", true));
        this.autoSubmitSetting = this.add(new BooleanSetting("AutoSubmit", true));
        this.syncSettingsSetting = this.add(new BooleanSetting("SyncSettings", false));
        this.delaySetting = this.add(new NumberSetting<>("Delay", 10, 0, 100));
    }

    @Override
    public void onEnable() {
        this.tickDelay = 0;
    }

    @EventHandler
    private void onOpenSignEditor(ClientPacketListenerHandleOpenSignEditorEvent event) {
        if (!this.autoSubmitSetting.getValue() || this.evernightMinecraft.player == null) {
            return;
        }
        if (this.tickDelay > 0) {
            return;
        }
        SignBlockEntity sign = event.getSign();
        if (this.onlyEmptySetting.getValue()) {
            for (Component message : sign.getFrontText().getMessages(false)) {
                if (!message.getString().isBlank()) {
                    return;
                }
            }
        }

        ServerboundSignUpdatePacket packet = new ServerboundSignUpdatePacket(
                event.getPosition(),
                event.isFrontText(),
                this.format(this.line1Setting.getValue()),
                this.format(this.line2Setting.getValue()),
                this.format(this.line3Setting.getValue()),
                this.format(this.line4Setting.getValue()));
        this.evernightMinecraft.player.connection.send(packet);
        event.cancel();
        this.tickDelay = this.delaySetting.getValue();
        // TODO(port): 取消事件后是否真的能阻止编辑界面打开取决于本体发布点；若仍弹出界面，
        //  需要改为在 ScreenChangeEvent.Before 里改动 AbstractSignEditScreen 的文本。
    }

    @EventHandler
    private void onTick(MinecraftTickEvent event) {
        if (this.tickDelay > 0) {
            this.tickDelay--;
        }
    }

    @EventHandler
    private void onSendPacket(PacketEvent.Send event) {
        if (!this.syncSettingsSetting.getValue() || !(event.getPacket() instanceof ServerboundSignUpdatePacket packet)) {
            return;
        }
        if (!packet.isFrontText()) {
            return;
        }
        String[] lines = packet.getLines();
        this.syncSetting(this.line1Setting, lines, 0);
        this.syncSetting(this.line2Setting, lines, 1);
        this.syncSetting(this.line3Setting, lines, 2);
        this.syncSetting(this.line4Setting, lines, 3);
    }

    private void syncSetting(Setting<String> setting, String[] lines, int index) {
        String current = setting.getValue();
        if (current.contains("${name}") || (current.startsWith("$time{") && current.endsWith("}"))) {
            return;
        }
        setting.setValue(lines[index]);
    }

    /** 展开 {@code ${name}} 与 {@code $time{...}}。*/
    private String format(String template) {
        if (template == null) {
            return "";
        }
        String result = template;
        if (result.contains("${name}")) {
            String name = this.evernightMinecraft.player == null
                    ? ""
                    : this.evernightMinecraft.player.getName().getString();
            result = result.replace("${name}", name);
        }
        if (result.startsWith("$time{") && result.endsWith("}")) {
            String pattern = result.substring("$time{".length(), result.length() - 1);
            try {
                return DateTimeFormatter.ofPattern(pattern).format(LocalDateTime.now());
            } catch (Exception invalidPattern) {
                ChatUtils.printChat(Component.literal(Translations.get("addons.lotus.messages.invalidTimeFormat")),
                        ChatUtils.moduleSignature(this));
                return DEFAULT_TIME.format(LocalDateTime.now());
            }
        }
        return result;
    }
}
