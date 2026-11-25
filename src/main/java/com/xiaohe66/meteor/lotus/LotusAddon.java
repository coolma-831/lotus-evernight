package com.xiaohe66.meteor.lotus;

import com.xiaohe66.meteor.lotus.modules.AutoHelmetModule;
import evernight.addon.AddonContext;
import evernight.addon.EvernightAddon;
import evernight.client.modules.ModuleCategory;

/**
 * Lotus 的 Evernight 插件入口。
 *
 * <p>原插件是 Meteor Client 的 Lotus 扩展（作者 xiaohe66）。这里按照
 * {@code evernight-addon-template} 的约定重写：一次性注册模块，玩家可见文本全部走
 * {@code addons.lotus.*} 语言表。
 *
 * <p>移植说明见仓库 README。
 */
public final class LotusAddon extends EvernightAddon {
    /** 插件分类，显示名来自 {@code addons.lotus.names.categories.Lotus}。 */
    private ModuleCategory lotusCategory;

    @Override
    public void onInitialize(AddonContext context) {
        this.lotusCategory = context.registerCategory("Lotus");

        context.registerModule(new AutoHelmetModule(this.lotusCategory));

        context.logger().info("Lotus {} initialized ({} modules)",
                context.metadata().version(), 1);
    }

    public ModuleCategory getLotusCategory() {
        return this.lotusCategory;
    }
}
