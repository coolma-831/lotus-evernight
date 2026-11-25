package com.xiaohe66.meteor.lotus.mixin;

import com.xiaohe66.meteor.lotus.modules.StorageFilterModule;
import evernight.client.managers.world.WorldStateManager;
import evernight.client.modules.misc.StashFinder.StashFinderModule;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 让「存储查找器」（Evernight 的 {@code StashFinderModule}）不把遗迹装饰容器算成 stash。
 *
 * <p>原 Lotus 在 Meteor 的 {@code StashFinder#onChunkData} 里 {@code @Redirect} 了
 * {@code List#contains}；Evernight 的计数循环用
 * {@link WorldStateManager#hasKnownPlacement(BlockPos)} 判断「这个容器是不是玩家放的」，
 * 这里对该调用做 {@code @Redirect}：命中遗迹过滤时返回 {@code true}，
 * 效果等同于「该容器不计入箱子数量、也不登记潜影盒」。
 */
@Mixin(StashFinderModule.class)
public abstract class StashFinderModuleMixin {
    @Redirect(
            method = "onClientChunkCacheReplaceWithPacketDataEvent",
            at = @At(
                    value = "INVOKE",
                    target = "Levernight/client/managers/world/WorldStateManager;hasKnownPlacement(Lnet/minecraft/core/BlockPos;)Z"))
    private boolean lotus$hideRuinContainers(WorldStateManager manager, BlockPos position) {
        return manager.hasKnownPlacement(position)
                || StorageFilterModule.shouldHideFromStashFinderAt(position);
    }
}
