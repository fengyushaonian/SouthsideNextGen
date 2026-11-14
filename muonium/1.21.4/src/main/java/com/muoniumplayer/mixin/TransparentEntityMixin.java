package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.TransparentMode;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「通透模式」的实体压缩：把果冻矩阵包住原版的实体渲染拐号。
 *
 * <h2>为什么注入 9 参重载而不是 8 参</h2>
 * <p>1.21.4 的 {@code EntityRenderDispatcher} 有两个 {@code render}：</p>
 * <ul>
 *   <li>{@code render(Entity, double, double, double, float, PoseStack, MultiBufferSource, int)}
 *       —— 只有 4 行字节码，取到 renderer 后直接委托，<b>里面没有 translate</b>；</li>
 *   <li>{@code render(Entity, double, double, double, float, PoseStack, MultiBufferSource, int, EntityRenderer)}
 *       —— 真正的实现体，{@code translate} 与火焰/阴影/碰撞箱都在这里。</li>
 * </ul>
 * <p>所以注入点必须落在后者。签名依据是 1.21.4 官方 client 映射里该方法的真实声明
 * （{@code 154:206:void render(Entity,double,double,double,float,PoseStack,MultiBufferSource,int,EntityRenderer)}）。</p>
 *
 * <h2>与 1.21.1 车道的差异（已核实）</h2>
 * <ul>
 *   <li>参数表少了一个 {@code yaw}：1.21.4 的签名是 {@code (Entity, D, D, D, F, PoseStack, MultiBufferSource, I)}，
 *       1.21.1 是 {@code (Entity, D, D, D, F, F, PoseStack, MultiBufferSource, I)}。处理器必须按前者写，
 *       否则 mixin 在应用期就匹配不上（编译期不报错，只在运行期暴露）。</li>
 *   <li>1.21.1 用 {@code Entity#displayFireAnimation()} 作为收尾注入点；该成员在
 *       <b>1.21.4 的映射里查不到</b>，火焰动画已搬进 {@code EntityRenderState.displayFireAnimation} 字段。
 *       因此收尾改用 {@code @At("RETURN")}：push/pop 在同一个方法内严格配对。</li>
 * </ul>
 *
 * <p><b>已知观感差异</b>：用 RETURN 收尾意味着火焰、阴影、碰撞箱调试框也会落在缩放矩阵里。
 * 1.21.1 那版是把它们留在括号外。这是本车道相对 1.21.1 的降级；要精确复现需改成
 * 注入 {@code EntityRenderState.displayFireAnimation} 的字段访问（在 EntityRenderer.render 里）。</p>
 */
@Mixin(EntityRenderDispatcher.class)
abstract class TransparentEntityMixin {

    private static final String RENDER_WITH_RENDERER =
            "render(Lnet/minecraft/world/entity/Entity;DDDFLcom/mojang/blaze3d/vertex/PoseStack;"
                    + "Lnet/minecraft/client/renderer/MultiBufferSource;I"
                    + "Lnet/minecraft/client/renderer/entity/EntityRenderer;)V";

    @Inject(method = RENDER_WITH_RENDERER, at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/vertex/PoseStack;translate(DDD)V", ordinal = 0, shift = At.Shift.AFTER))
    private void transmode$begin(Entity entity, double x, double y, double z, float partialTick,
                                 PoseStack pose, MultiBufferSource buffers, int light,
                                 EntityRenderer renderer, CallbackInfo ci) {
        // push/pop 的条件必须逐字对称：begin 按节拍早退、end 按开关 pop 的话，
        // 「开着但无节拍」时每个生物会被多 pop 一次，帧尾 checkPoseStack 直接崩
        // （Pose stack not empty，1.21.1 实机踩过）。无节拍就 scale(1,1,1)，代价可忽略。
        if (!(entity instanceof LivingEntity) || !TransparentMode.isEnabled()) return;
        pose.pushPose();
        pose.scale(TransparentMode.widthScale(), TransparentMode.heightScale(), 1f);
    }

    @Inject(method = RENDER_WITH_RENDERER, at = @At("RETURN"))
    private void transmode$end(Entity entity, double x, double y, double z, float partialTick,
                               PoseStack pose, MultiBufferSource buffers, int light,
                               EntityRenderer renderer, CallbackInfo ci) {
        if (entity instanceof LivingEntity && TransparentMode.isEnabled()) pose.popPose();
    }
}
