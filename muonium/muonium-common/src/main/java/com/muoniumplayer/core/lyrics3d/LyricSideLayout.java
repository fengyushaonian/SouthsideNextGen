package com.muoniumplayer.core.lyrics3d;

/**
 * 「搭路模式」的歌词侧向布局状态。
 *
 * <h2>解决什么问题</h2>
 * <p>默认的 3D 世界歌词是以<b>视线</b>为中心散布的（{@code spawn()} 里
 * {@code yaw = camera.yaw()} 再叠随机偏角）。搭路时视线长期压向斜下方、
 * 并且在两个方向之间来回甩，结果就是歌词又挡路又乱飞，根本没法看。</p>
 *
 * <p>这个类提供另一套布局：整句锚到玩家<b>左右两侧</b>，朝向以<b>搭路方向</b>
 * 为基准，而不是鼠标视线。外挂（Southside）在搭路模块启用期间每帧把状态推进来，
 * 关掉搭路就清空 —— 于是搭路时歌词安静地贴在两侧，平时恢复原来的散布行为。</p>
 *
 * <h2>为什么放在共享层</h2>
 * <p>这里只有静态数据与纯三角运算，不碰任何 Minecraft / 加载器类型，
 * 所以七棵树都能直接编译同一份。真正的「状态从哪来」属于宿主问题，
 * 放在各树的 compat 桥里（本仓库是 {@code compat/mc/CheatClientBridge}）。</p>
 *
 * <h2>线程</h2>
 * <p>字段都是 volatile：写入方是客户端主线程（外挂 tick / 渲染事件），
 * 读取方是渲染线程的歌词布局。两者读到的可能差一帧，对观感无影响，
 * 不值得为它加锁。</p>
 */
public final class LyricSideLayout {

    /** 搭路模式是否生效。由外挂状态桥写入。 */
    public static volatile boolean active;

    /**
     * 搭路方向（度，MC 口径：0 = +Z / 南，90 = -X / 西，180 = -Z / 北，270 = +X / 东）。
     * 只在 {@link #active} 为真时有意义。
     */
    public static volatile float travelYawDeg;

    /** 左右偏角（度）：一句落在 −该值（左侧），另一句落在 +该值（右侧）。 */
    public static volatile float sideAngleDeg = 62f;

    /** 与玩家的水平距离（格）。搭路时人贴得很近，比默认 10.5 格小得多。 */
    public static volatile float distanceBlocks = 3.6f;

    /** 抬升（格）：让歌词略高于视线中心，避开正前方要看的方块。 */
    public static volatile float liftBlocks = 0.45f;

    /** 左右交替：连续两句分居两侧，避免全堆在同一边。 */
    public static volatile boolean alternateSides = true;

    /** 布局用的固定俯角（度）。负数 = 略向上看，抵消「眼睛比歌词低」的落差。 */
    public static volatile float fixedPitchDeg = -6f;

    private LyricSideLayout() {
    }

    /** 关掉搭路模式（外挂关闭搭路时调用）。 */
    public static void clear() {
        active = false;
    }

    /**
     * 把一句锚到侧向位置。
     *
     * <p>返回的 yaw 就是「从眼睛指向这个锚点」的方向 —— 所以歌词平面天然正对玩家，
     * 文字始终可读；而它的<b>位置</b>由搭路方向 ± 侧偏角决定，也就是「方向与搭路
     * 同向、落在左右两边」。这两件事同时成立，靠的就是位置和朝向用同一个 yaw 推出来。</p>
     *
     * @param eyeX      相机（眼睛）世界 X
     * @param eyeY      相机（眼睛）世界 Y
     * @param eyeZ      相机（眼睛）世界 Z
     * @param lineIndex 句序号，用于左右交替；负数按左侧处理
     * @return {@code {x, y, z, yawDeg, pitchDeg}}
     */
    public static double[] anchor(double eyeX, double eyeY, double eyeZ, int lineIndex) {
        boolean left = !alternateSides || (lineIndex & 1) == 0;
        double sideOffsetDeg = left ? -sideAngleDeg : sideAngleDeg;
        double yawDeg = travelYawDeg + sideOffsetDeg;
        double pitchDeg = fixedPitchDeg;

        // 与 WorldLyricsState#yawPitchDirectionStatic 同一套 MC 口径换算：
        //   dx = -sin(yaw)cos(pitch), dy = -sin(pitch), dz = cos(yaw)cos(pitch)
        // 两边必须一致，否则「朝向」和「位置」会各算各的，文字就斜着看。
        double yawRad = Math.toRadians(yawDeg);
        double pitchRad = Math.toRadians(pitchDeg);
        double cosPitch = Math.cos(pitchRad);
        double dx = -Math.sin(yawRad) * cosPitch;
        double dy = -Math.sin(pitchRad);
        double dz = Math.cos(yawRad) * cosPitch;

        double distance = Math.max(0.8, distanceBlocks);
        return new double[] {
                eyeX + dx * distance,
                eyeY + dy * distance + liftBlocks,
                eyeZ + dz * distance,
                yawDeg,
                pitchDeg
        };
    }
}
