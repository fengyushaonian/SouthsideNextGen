package com.muoniumplayer.compat.mc;

import com.muoniumplayer.core.lyrics3d.LyricSideLayout;
import com.muoniumplayer.core.rendering.IslandAmbientStatus;

/**
 * 外挂（客户端 mod）→ Muonium 的状态桥。
 *
 * <h2>为什么需要它</h2>
 * <p>内建进 Southside 之后，Muonium 需要知道一些「只有宿主知道」的实时状态：
 * 现在是不是在搭路、搭路朝哪个方向、热键栏还剩多少方块。这些都属于宿主私有信息，
 * 共享层不该去查，所以统一从这个桥灌进去。</p>
 *
 * <h2>调用方向</h2>
 * <p>这个类只提供 {@code public static} 方法，<b>由宿主反射调用</b>。
 * 原因是两边映射不同（宿主 Yarn / 本 mod mojmap），编译期互不可见 ——
 * 反射是唯一不需要把两套映射搅进同一份源码的做法。方法签名因此属于<b>公开契约</b>：
 * 改名字或改参数表等于破坏宿主接入，不是重构。</p>
 *
 * <h2>线程</h2>
 * <p>约定由客户端主线程调用（宿主在模块 tick / 渲染事件里推）。写入的是 volatile
 * 静态字段与一个加锁的小结构，渲染线程读到的可能差一帧，对观感无影响。</p>
 */
public final class CheatClientBridge {

    private CheatClientBridge() {
    }

    /**
     * 推送搭路状态。宿主每帧调一次即可（幂等）。
     *
     * @param active      搭路模块是否启用
     * @param yawDeg      搭路方向（度，MC 口径：0 = 南 / +Z）。非搭路时该值被忽略
     * @param hotbarBlocks 热键栏剩余方块数（用于灵动岛）
     * @param placedBlocks 本次搭路已放置的方块数（用于灵动岛）
     * @param facing      方位名（N/E/S/W 或「南」「西」等），空串表示未知
     */
    public static void setScaffoldState(boolean active, float yawDeg,
                                        int hotbarBlocks, int placedBlocks, String facing) {
        LyricSideLayout.active = active;
        if (active) {
            LyricSideLayout.travelYawDeg = yawDeg;
        }
        IslandAmbientStatus.setScaffold(active, yawDeg, hotbarBlocks, placedBlocks, facing);
    }

    /** 关闭搭路模式（模块被关掉时调用）。等价于 {@code setScaffoldState(false, ...)}。 */
    public static void clearScaffold() {
        LyricSideLayout.clear();
        IslandAmbientStatus.clearScaffold();
    }

    /** 搭路模式当前是否生效（宿主侧可用于自检）。 */
    public static boolean isScaffoldActive() {
        return LyricSideLayout.active;
    }
}
