package dev.southside.module.impl.client;

import dev.southside.module.Category;
import dev.southside.module.Module;
import dev.southside.muonium.MuoniumBridge;

/**
 * 内建 MuoniumPlayer 的 ClickGUI 入口。
 *
 * <p>按钮式模块：在 GUI 里点一下开一次播放器，随即自动取消勾选，不会在模块列表里
 * 留下一个「常亮」的开关。与 {@link ClientSettings} 同一套写法。</p>
 *
 * <p>真正的界面由 Muonium 自己绘制（Skija 渲染层，不依附原版 GUI），
 * 这里只负责把用户的那一次点击转成对 {@code OnboardingManager#openPlayerEntry()} 的调用。</p>
 */
public class MuoniumPlayer extends Module {

    public MuoniumPlayer() {
        super(Category.Client, "MuoniumPlayer", "Open the built-in music player");
    }

    @Override
    public void onEnable() {
        // 先关掉开关再开界面：播放器是独立 Screen，模块状态不该跟着界面一起挂着。
        this.setEnable(false);
        MuoniumBridge.openPlayer();
    }
}
