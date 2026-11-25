package com.xiaohe66.meteor.lotus;

import com.xiaohe66.meteor.lotus.modules.AutoCraftModule;
import com.xiaohe66.meteor.lotus.modules.AutoEnchantModule;
import com.xiaohe66.meteor.lotus.modules.AutoHelmetModule;
import com.xiaohe66.meteor.lotus.modules.AutoKitModule;
import com.xiaohe66.meteor.lotus.modules.AutoPaintingModule;
import com.xiaohe66.meteor.lotus.modules.AutoSignModule;
import com.xiaohe66.meteor.lotus.modules.ContainerPreviewModule;
import com.xiaohe66.meteor.lotus.modules.EnchantBookFarmModule;
import com.xiaohe66.meteor.lotus.modules.EntityListModule;
import com.xiaohe66.meteor.lotus.modules.LightLevelModule;
import com.xiaohe66.meteor.lotus.modules.LotusHelperModule;
import com.xiaohe66.meteor.lotus.modules.MapArtCopyModule;
import com.xiaohe66.meteor.lotus.modules.QuickItemMoveModule;
import com.xiaohe66.meteor.lotus.modules.RaidHelperModule;
import com.xiaohe66.meteor.lotus.modules.SpiralFlightModule;
import com.xiaohe66.meteor.lotus.modules.SpawnerFinderModule;
import com.xiaohe66.meteor.lotus.modules.StorageFilterModule;
import evernight.addon.AddonContext;
import evernight.addon.EvernightAddon;
import evernight.client.modules.ModuleCategory;
import java.util.ArrayList;
import java.util.List;

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
    /** 已注册模块名，仅用于启动日志。 */
    private final List<String> registeredModules = new ArrayList<>();

    @Override
    public void onInitialize(AddonContext context) {
        this.lotusCategory = context.registerCategory("Lotus");

        this.register(context, new AutoHelmetModule(this.lotusCategory));
        this.register(context, new AutoSignModule(this.lotusCategory));
        this.register(context, new SpiralFlightModule(this.lotusCategory));
        this.register(context, new AutoCraftModule(this.lotusCategory));
        this.register(context, new RaidHelperModule(this.lotusCategory));
        this.register(context, new LightLevelModule(this.lotusCategory));
        this.register(context, new AutoPaintingModule(this.lotusCategory));
        this.register(context, new MapArtCopyModule(this.lotusCategory));
        this.register(context, new AutoEnchantModule(this.lotusCategory));
        this.register(context, new LotusHelperModule(this.lotusCategory));
        this.register(context, new StorageFilterModule(this.lotusCategory));
        this.register(context, new EntityListModule(this.lotusCategory));
        this.register(context, new ContainerPreviewModule(this.lotusCategory));
        this.register(context, new SpawnerFinderModule(this.lotusCategory));
        this.register(context, new EnchantBookFarmModule(this.lotusCategory));
        this.register(context, new QuickItemMoveModule(this.lotusCategory));
        this.register(context, new AutoKitModule(this.lotusCategory));

        context.logger().info("Lotus {} initialized ({} modules)",
                context.metadata().version(), this.registeredModules.size());
    }

    private void register(AddonContext context, evernight.addon.AddonModule module) {
        context.registerModule(module);
        this.registeredModules.add(module.getName());
    }

    public ModuleCategory getLotusCategory() {
        return this.lotusCategory;
    }
}
