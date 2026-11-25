package com.xiaohe66.meteor.lotus.mixin;

import com.xiaohe66.meteor.lotus.modules.QuickItemMoveModule;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 把容器界面的滚轮事件转给 {@link QuickItemMoveModule}，用于「滚轮移动」。
 *
 * <p>Evernight 的 {@code MouseHandlerOnScrollEvent} 只在没有界面时发布，容器界面里拿不到滚轮，
 * 所以插件自带这个 mixin。未启用模块时直接放行，不影响原版行为。
 */
@Mixin(AbstractContainerScreen.class)
public abstract class QuickItemMoveScreenMixin {
    @Inject(method = "mouseScrolled(DDDD)Z", at = @At("HEAD"), cancellable = true)
    private void lotus$quickItemMoveScroll(double mouseX, double mouseY, double horizontal, double vertical,
                                           CallbackInfoReturnable<Boolean> cir) {
        if (QuickItemMoveModule.onMouseScroll((AbstractContainerScreen<?>) (Object) this,
                mouseX, mouseY, horizontal, vertical)) {
            cir.setReturnValue(true);
        }
    }
}
