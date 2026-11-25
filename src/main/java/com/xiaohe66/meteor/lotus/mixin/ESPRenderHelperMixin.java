package com.xiaohe66.meteor.lotus.mixin;

import com.xiaohe66.meteor.lotus.modules.StorageFilterModule;
import evernight.client.modules.render.ESP.ESPRenderHelper;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让「存储透视」（Evernight 的 {@code ESPModule}）跳过被 {@link StorageFilterModule} 判定为遗迹装饰的容器。
 *
 * <p>原 Lotus 通过 Meteor 的 {@code StorageESP} + {@code Renderer3D#line} 上的 {@code @Redirect} 实现；
 * Evernight 的容器是靠 {@code ESPRenderHelper#shouldRenderBlockEntity} 过滤后重绘模型，所以在这里拦截。
 * 未启用模块或设置关闭时直接放行。
 */
@Mixin(ESPRenderHelper.class)
public abstract class ESPRenderHelperMixin {
    @Inject(method = "shouldRenderBlockEntity", at = @At("HEAD"), cancellable = true)
    private void lotus$hideRuinContainers(BlockEntity blockEntity, CallbackInfoReturnable<Boolean> cir) {
        if (StorageFilterModule.shouldHideFromEsp(blockEntity)) {
            cir.setReturnValue(false);
        }
    }
}
