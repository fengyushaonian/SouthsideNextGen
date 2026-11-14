package com.muoniumplayer.mixin;

import com.muoniumplayer.core.ncm.music.MusicSpin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「陀螺模式·头乱晃」的头部动作。
 *
 * <h2>为什么不能沿用 1.21.1 的写法</h2>
 * <p>1.21.1 的实现在 {@code LivingEntityRenderer.render} 里 {@code @Redirect}
 * {@code EntityModel.setupAnim(Entity, F, F, F, F, F)}，然后直接改 {@code ModelPart} 的
 * xRot/yRot。这条路在 1.21.4 上整段失效，两处都变了（已按官方 client 映射核实）：</p>
 * <ul>
 *   <li>{@code EntityModel#setupAnim} 的签名变成 {@code setupAnim(EntityRenderState)} —— 单参数，
 *       拿不到 {@code Entity}，也就判不出「是不是自己」；</li>
 *   <li>渲染走「extract → render」两段式，头部角度是 {@link LivingEntityRenderState} 里的
 *       {@code yRot}/{@code xRot} 字段（已核实存在），由 {@code extractRenderState} 填好。</li>
 * </ul>
 *
 * <h2>现在的做法</h2>
 * <p>注入 {@code LivingEntityRenderer.extractRenderState(LivingEntity, LivingEntityRenderState, float)}
 * 的 {@code RETURN}：原版填完之后再把差值叠上去。这样模型在 render 阶段读到的就是叠过的角度，
 * 而且只改本机画面快照，不碰实体本体与服务端状态。</p>
 *
 * <p>注意 {@code yRot}/{@code xRot} 存的是「头相对身体」的规范角度，叠在它上面就是头部独立动作；
 * 整模型的旋转归 {@link SpinEntityMixin}（叠在 {@code bodyRot} 那一侧）。</p>
 */
@Mixin(LivingEntityRenderer.class)
abstract class SpinHeadMixin {

    @Inject(method = "extractRenderState", at = @At("RETURN"))
    private void spin$headAnim(LivingEntity entity, LivingEntityRenderState state, float partialTick,
                               CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        boolean isSelf = entity == mc.player;
        if (!MusicSpin.headShakeActiveFor(isSelf, entity instanceof Player)) {
            return;
        }
        float[] head = MusicSpin.headDeltas();
        if (head[0] == 0f && head[1] == 0f) {
            return;
        }
        // 共享层给的是「度」，渲染状态里是同一个量纲（原版 extractRenderState 直接写度数），
        // 不需要 1.21.1 那次的 ×(π/180) 换算 —— 那次换算是因为要写进 ModelPart 的弧度字段。
        state.yRot += head[0];
        state.xRot += head[1];
    }
}
