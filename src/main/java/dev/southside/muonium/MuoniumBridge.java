package dev.southside.muonium;

import dev.southside.Southside;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * MuoniumPlayer 内建接入桥。
 *
 * <h2>为什么是反射而不是直接 import</h2>
 * <p>Southside 用 Yarn 映射（{@code net.minecraft.client.gui.DrawContext} 这套），
 * 内建的 MuoniumPlayer 用 Mojang 官方名（mojmap，{@code GuiGraphics} 这套）。
 * 两个工程各自 remap 到 intermediary 后可以在同一个进程里共存 —— 那是 Fabric 的标准
 * 多 mod 机制 —— 但在<b>编译期</b>，Southside 的 classpath 上没有 Muonium 的符号。
 * 直接 import 会让 Southside 编译失败，强行加 compileOnly 又会把两套映射混进同一份源码。</p>
 *
 * <p>所以这里只认<b>字符串形式</b>的类名与方法名，走反射。好处有三个：</p>
 * <ol>
 *   <li>Southside 编译不依赖 Muonium 是否已构建；</li>
 *   <li>映射差异被隔离在这一个文件里，不污染任何业务代码；</li>
 *   <li>Muonium 缺失时能降级成一句聊天栏提示，而不是 NoClassDefFoundError 崩游戏。</li>
 * </ol>
 *
 * <h2>被调用的入口是公开契约</h2>
 * <p>{@code com.muoniumplayer.core.screens.onboarding.OnboardingManager#openPlayerEntry()}
 * 是 {@code public static void} 无参方法（已核对源码）。它内部按 onboarding 状态决定
 * 打开引导页还是播放器主界面，也就是说「首次启动的引导」这套逻辑仍然归 Muonium 自己管，
 * 这里只是替用户点了一下那个入口。</p>
 *
 * <p>不直接调 {@code NCMScreen.getInstance()}：那会绕过首次运行的引导流程。</p>
 */
public final class MuoniumBridge {

    /** 播放器入口（Muonium 共享层公开方法）。 */
    private static final String ENTRY_CLASS = "com.muoniumplayer.core.screens.onboarding.OnboardingManager";
    private static final String ENTRY_METHOD = "openPlayerEntry";

    /** 探测用的标记类：Muonium 的客户端主类。 */
    private static final String PROBE_CLASS = "com.muoniumplayer.MuoniumPlayerClient";

    /** 解析后的句柄；null 表示不可用或还没解析。 */
    private static MethodHandle entryHandle;
    /** 只尝试解析一次，避免每次点按钮都走一遍 Class.forName 的失败路径。 */
    private static boolean resolved;
    private static String unavailableReason = "未解析";

    private MuoniumBridge() {
    }

    /**
     * MuoniumPlayer 是否在当前运行时可用。
     *
     * <p>可用 = 类能被加载 + 入口方法签名匹配。注意这里只证明「静态契约成立」，
     * 不证明播放器真的能出声 —— 那要看 Skija 渲染层与音源，属于运行期的事。</p>
     */
    public static synchronized boolean isAvailable() {
        resolve();
        return entryHandle != null;
    }

    /** 不可用时的原因，用于给用户看的那句提示。 */
    public static synchronized String unavailableReason() {
        resolve();
        return unavailableReason;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            ClassLoader loader = MuoniumBridge.class.getClassLoader();
            Class<?> probe = Class.forName(PROBE_CLASS, false, loader);
            Class<?> entry = Class.forName(ENTRY_CLASS, true, loader);
            MethodHandle handle = MethodHandles.lookup().findStatic(
                    entry, ENTRY_METHOD, MethodType.methodType(void.class));
            entryHandle = handle;
            unavailableReason = null;
            Southside.LOGGER.info("[Muonium] 内建播放器已就绪（{} / {}）",
                    probe.getName(), ENTRY_CLASS + "#" + ENTRY_METHOD);
        } catch (ClassNotFoundException e) {
            entryHandle = null;
            unavailableReason = "内嵌的 MuoniumPlayer 没有加载（缺 " + e.getMessage() + "）";
            Southside.LOGGER.warn("[Muonium] 内建播放器不可用：{}", unavailableReason);
        } catch (NoSuchMethodException e) {
            entryHandle = null;
            unavailableReason = "MuoniumPlayer 的入口方法签名对不上（" + ENTRY_CLASS + "#" + ENTRY_METHOD + "）";
            Southside.LOGGER.warn("[Muonium] 内建播放器不可用：{}", unavailableReason);
        } catch (Throwable t) {
            entryHandle = null;
            unavailableReason = "MuoniumPlayer 初始化异常：" + t;
            Southside.LOGGER.warn("[Muonium] 内建播放器不可用", t);
        }
    }

    /**
     * 打开 MuoniumPlayer 播放器界面。
     *
     * <p>必须在客户端主线程调用（按钮回调与模块 onEnabled 都满足）。
     * 失败时不抛异常：给聊天栏一句可读的提示，让玩家知道是内建没生效而不是按钮坏了。</p>
     *
     * @return 是否成功唤起
     */
    public static boolean openPlayer() {
        if (!isAvailable()) {
            notifyUnavailable();
            return false;
        }
        try {
            entryHandle.invokeExact();
            return true;
        } catch (Throwable t) {
            Southside.LOGGER.error("[Muonium] 打开播放器失败", t);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("§c[Muonium] 打开播放器失败：" + t), false);
            }
            return false;
        }
    }

    private static void notifyUnavailable() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String msg = "§c[Muonium] 内建播放器不可用：" + unavailableReason();
        Southside.LOGGER.warn(msg);
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal(msg), false);
        }
    }
}
