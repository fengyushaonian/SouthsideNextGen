package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.MusicSpin;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「陀螺模式」的实体旋转：把绕 Y 的旋转矩阵包住原版的实体渲染拐号。
 *
 * <h2>1.21.4 的形态</h2>
 * <p>注入的是 {@code EntityRenderDispatcher.render(Entity, D, D, D, F, PoseStack, MultiBufferSource, I, EntityRenderer)}
 * —— 真正的实现体重载（8 参那个只是委托，没有 translate）。签名依据是 1.21.4 官方 client 映射。</p>
 *
 * <h2>与 1.21.1 的差异</h2>
 * <ul>
 *   <li>参数表少了 {@code yaw}（1.21.1 是 {@code Entity, D, D, D, F, F, PoseStack, ...}）；</li>
 *   <li>收尾注入点从 {@code Entity#displayFireAnimation()}（1.21.4 无此成员）
 *       改为 {@code @At("RETURN")}，保证 push/pop 严格配对。</li>
 * </ul>
 *
 * <p>旋转只动本机画面，不碰实体本体与服务端状态。名牌画在拐号外，
 * 火焰/阴影/碰撞箱按 {@link TransparentEntityMixin} 的说明同样落在矩阵内（已知降级）。</p>
 */
@Mixin(EntityRenderDispatcher.class)
abstract class SpinEntityMixin {

    private static final String RENDER_WITH_RENDERER =
            "render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I"
                    + "Lnet/minecraft/client/renderer/entity/EntityRenderer;)V";

    @Inject(method = RENDER_WITH_RENDERER, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void spin$begin(Entity entity, double x, double y, double z, float partialTick,
                            PoseStack pose, MultiBufferSource buffers, int light,
                            EntityRenderer renderer, CallbackInfo ci) {
        // push/pop 的条件必须逐字对称（TransparentEntityMixin 的实机坑）：两端都走
        // entitySpins —— 纯判定、无状态推进，隔着一次模型渲染结果也恒等。
        if (!MusicSpin.entitySpins(entity == Minecraft.getInstance().player,
                entity instanceof Player, entity instanceof LivingEntity)) {
            return;
        }
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(
                MusicSpin.entityModelAngle(entity == Minecraft.getInstance().player, entity.getYRot())));
    }

    @Inject(method = RENDER_WITH_RENDERER, at = @At("RETURN"))
    private void spin$end(Entity entity, double x, double y, double z, float partialTick,
                          PoseStack pose, MultiBufferSource buffers, int light,
                          EntityRenderer renderer, CallbackInfo ci) {
        if (MusicSpin.entitySpins(entity == Minecraft.getInstance().player,
                entity instanceof Player, entity instanceof LivingEntity)) {
            pose.popPose();
        }
    }
}
