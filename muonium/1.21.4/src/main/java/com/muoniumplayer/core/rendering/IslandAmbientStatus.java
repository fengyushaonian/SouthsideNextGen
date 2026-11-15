package com.muoniumplayer.core.rendering;

import com.muoniumplayer.core.settings.HudConfig;

import java.util.Calendar;

/**
 * 常驻灵动岛的内容采样与状态判定。
 *
 * <p>拆成独立类有两个原因：一是把「读游戏状态」和「画灵动岛」分开，渲染层只消费不可变快照；
 * 二是这里的判定与格式化全是纯函数，可以离线探针验证，不必进游戏才能确认排队/常驻的交互。</p>
 *
 * <p>宽度模板是关键设计：FPS 与延迟每秒都在变，如果按真实字宽排版，灵动岛会一直微微伸缩。
 * 每个条目都按固定位数的模板预留宽度，数值居中绘制，于是常驻状态下宽度完全静止。</p>
 */
final class IslandAmbientStatus {

    /** 常驻条目类别。左侧小图标与单位都由类别决定。 */
    enum Kind {
        FPS,
        PING,
        CLOCK,
        /** 正在播放：歌名 + 歌手，左侧大图标换成封面。 */
        NOW_PLAYING,
        /**
         * 外挂（Southside 搭路）信息：搭路中显示剩余方块数与已放置数，
         * 空闲时显示当前方位。文本由 {@code CheatClientBridge} 每帧灌进来。
         *
         * <p>刻意复用 {@code showIcon=false}：渲染层的图标分支只有 FPS/PING/CLOCK 三档，
         * 新增类别会落到默认的时钟图标上，那会读成"时间"。这几个条目只出文字。</p>
         */
        SCAFFOLD
    }

    /** 常驻内容分页。轮换就是在这两页之间切换。 */
    enum Page {
        /** 帧率 / 延迟 / 时间。 */
        STATUS,
        /** 正在播放的歌曲。 */
        NOW_PLAYING
    }

    /** 数值健康度。渲染层据此选色，判定本身与主题、GL 无关，便于离线校验。 */
    enum Health {
        NEUTRAL,
        GOOD,
        WARN,
        BAD
    }

    /** 采样间隔。常驻内容不需要每帧刷新，250ms 已经比人眼分辨得出的更新更快。 */
    static final long SAMPLE_INTERVAL_MILLIS = 250L;

    private static final Chip[] NO_CHIPS = new Chip[0];
    /** 复用同一个 Calendar：常驻渲染每 250ms 才取一次时间，且只在渲染线程上使用。 */
    private static final Calendar CALENDAR = Calendar.getInstance();

    private IslandAmbientStatus() {
    }

    static final class Chip {
        final Kind kind;
        /** 实际绘制的数值文本。 */
        final String value;
        /** 只参与宽度计算的等位数模板，保证数值跳动时布局不动。 */
        final String widthTemplate;
        /** 数值后面的灰色单位，没有单位时为空串。 */
        final String unit;
        final Health health;

        /** 是否在数值左边画一枚小图标。正在播放页交给左侧封面，条目本身不再重复画图标。 */
        final boolean showIcon;

        private Chip(Kind kind, String value, String widthTemplate, String unit, Health health) {
            this(kind, value, widthTemplate, unit, health, true);
        }

        private Chip(Kind kind, String value, String widthTemplate, String unit, Health health,
                     boolean showIcon) {
            this.kind = kind;
            this.value = value;
            this.widthTemplate = widthTemplate;
            this.unit = unit;
            this.health = health;
            this.showIcon = showIcon;
        }
    }

    /** 一次采样的不可变结果。渲染线程只读，不会在绘制中途变化。 */
    static final class Snapshot {
        final Chip[] chips;
        final long sampledAt;
        /** 本次采样展示的是哪一页。 */
        final Page page;
        /** 玩家此刻是否在移动。状态页的左侧图标据此在心跳字形与跑步动画之间切换。 */
        final boolean moving;

        private Snapshot(Chip[] chips, long sampledAt) {
            this(chips, sampledAt, Page.STATUS, false);
        }

        private Snapshot(Chip[] chips, long sampledAt, Page page, boolean moving) {
            this.chips = chips;
            this.sampledAt = sampledAt;
            this.page = page;
            this.moving = moving;
        }

        boolean isEmpty() {
            return chips.length == 0;
        }

        boolean isNowPlaying() {
            return page == Page.NOW_PLAYING;
        }
    }

    static final Snapshot EMPTY = new Snapshot(NO_CHIPS, 0L);

    /** 最近一次判定为"在移动"的时刻。用于给移动状态加一点滞回，避免图标在原地抖着换。 */
    private static long lastMovingAt;

    // ==================== 外挂（搭路）状态槽 ====================
    // 由 CheatClientBridge 每帧写入。只在状态页追加条目，不改动其它页的既有语义。
    /** 搭路模块是否启用。 */
    private static volatile boolean scaffoldActive;
    /** 搭路方向（度，MC 口径）。 */
    private static volatile float scaffoldYawDeg;
    /** 热键栏剩余方块数。 */
    private static volatile int scaffoldHotbar;
    /** 本次搭路已放置方块数。 */
    private static volatile int scaffoldPlaced;
    /** 方位名（N/E/S/W）。空串 = 未知，此时不显示方位条目。 */
    private static volatile String scaffoldFacing = "";

    /**
     * 写入搭路状态。由 {@code CheatClientBridge} 转发，宿主每帧调一次即可。
     *
     * <p>空闲（{@code active=false}）时仍然保留方位 —— 用户要的是"空闲时也看得到朝哪边"，
     * 所以这里不清 facing，只清搭路专属字段。</p>
     */
    public static void setScaffold(boolean active, float yawDeg,
                            int hotbarBlocks, int placedBlocks, String facing) {
        scaffoldActive = active;
        scaffoldYawDeg = yawDeg;
        scaffoldHotbar = Math.max(0, hotbarBlocks);
        scaffoldPlaced = Math.max(0, placedBlocks);
        scaffoldFacing = facing == null ? "" : facing.trim();
    }

    /** 清空搭路状态（宿主关掉搭路模块时调用）。方位一并清掉。 */
    public static void clearScaffold() {
        scaffoldActive = false;
        scaffoldHotbar = 0;
        scaffoldPlaced = 0;
        scaffoldFacing = "";
    }

    /** 是否有可展示的外挂条目（供 captureStatus 决定要不要做空快照早退）。 */
    private static boolean hasScaffoldChip() {
        return scaffoldActive || !scaffoldFacing.isEmpty();
    }

    /**
     * 把外挂条目并到状态页最前面。
     *
     * <p>放最前面而不是末尾：搭路时玩家最关心的是还剩多少方块，帧率与延迟是背景信息。
     * 条数最多两个（搭路中：方块数 + 方位；空闲：只有方位），岛宽不会因为多一行而跳变。</p>
     */
    private static Chip[] mergeScaffold(Chip[] base) {
        if (!hasScaffoldChip()) {
            return base;
        }
        java.util.ArrayList<Chip> list = new java.util.ArrayList<>(base.length + 2);
        if (scaffoldActive) {
            int safe = Math.min(9999, scaffoldHotbar);
            list.add(new Chip(Kind.SCAFFOLD, Integer.toString(safe),
                    safe >= 1000 ? "8888" : "888", "块",
                    safe <= 8 ? Health.BAD : (safe <= 24 ? Health.WARN : Health.GOOD), false));
            if (!scaffoldFacing.isEmpty()) {
                list.add(new Chip(Kind.SCAFFOLD, scaffoldFacing, "WW", "", Health.NEUTRAL, false));
            }
        } else if (!scaffoldFacing.isEmpty()) {
            // 空闲：只报方位（用户要的"空闲时显示方向"）。
            list.add(new Chip(Kind.SCAFFOLD, scaffoldFacing, "WW", "", Health.NEUTRAL, false));
        }
        for (Chip chip : base) {
            list.add(chip);
        }
        return list.toArray(new Chip[0]);
    }

    /** 移动状态的滞回窗口：停下后仍按"移动"再显示这么久，跑步动画不会一步一停。 */
    static final long MOVING_HOLD_MILLIS = 450L;

    /*
     * 歌名/歌手的硬字数上限。
     *
     * 这两个数字只是防御异常元数据（整段简介塞进歌名之类）的最后一道闸，不再承担排版职责：
     * 灵动岛现在会自己伸长去装下全文（见 DynamicIslandLayoutCalculator.GROWTH_SCREEN_FRACTION），
     * 放不下时先缩字号。原来的 22 / 14 是按"卡在基础宽度上"定的，很多正常歌名刚过一半就被
     * 截成"前半段…"，宽度明明还有余量。
     */
    private static final int NOW_PLAYING_TITLE_LIMIT = 64;
    private static final int NOW_PLAYING_ARTIST_LIMIT = 40;
    /** 歌词副行的字数上限：比歌手宽，因为副行现在优先放当前歌词。 */
    private static final int NOW_PLAYING_LYRIC_LIMIT = 56;

    /**
     * 按配置组装常驻条目。三个开关全关时返回空快照，灵动岛会退回紧凑胶囊而不是画一条空白行。
     *
     * @param fps        当前帧率，负数视为未知
     * @param ping       当前延迟毫秒，未知时配合 {@code pingKnown=false}
     * @param pingKnown  是否拿到了服务器延迟（单机/未进服时拿不到）
     */
    static Snapshot describe(boolean showFps, boolean showPing, boolean showClock,
                             int fps, int ping, boolean pingKnown, long epochMillis) {
        int count = (showFps ? 1 : 0) + (showPing ? 1 : 0) + (showClock ? 1 : 0);
        if (count == 0) return EMPTY;
        Chip[] chips = new Chip[count];
        int index = 0;
        if (showFps) {
            int safeFps = Math.max(0, Math.min(9999, fps));
            // 模板必须容得下真实数值，否则居中绘制会溢出预留槽压到下一个条目上。
            // 破千帧确实会发生（低画质小窗口），所以只在这时才升到四位模板。
            chips[index++] = new Chip(Kind.FPS, fps < 0 ? "--" : Integer.toString(safeFps),
                    safeFps >= 1000 ? "8888" : "888", "FPS",
                    fps < 0 ? Health.NEUTRAL : fpsHealth(safeFps));
        }
        if (showPing) {
            int safePing = Math.max(0, Math.min(9999, ping));
            chips[index++] = new Chip(Kind.PING, pingKnown ? Integer.toString(safePing) : "--",
                    "8888", "ms", pingKnown ? pingHealth(safePing) : Health.NEUTRAL);
        }
        if (showClock) {
            chips[index] = new Chip(Kind.CLOCK, formatClock(epochMillis), "88:88", "", Health.NEUTRAL);
        }
        return new Snapshot(chips, epochMillis);
    }

    /**
     * 组装"正在播放"页。没有歌曲时返回空快照，调用方据此跳过这一页。
     *
     * <p>宽度模板直接用真实文本（而不是等位数模板）：歌名不像帧率那样每秒都在跳，用真实宽度
     * 排版更好看；换歌时的宽度变化本来就该看得出来。</p>
     */
    static Snapshot describeNowPlaying(String title, String artist, long epochMillis, boolean moving) {
        String safeTitle = trimText(title, NOW_PLAYING_TITLE_LIMIT);
        if (safeTitle.isEmpty()) return EMPTY;
        // 副行优先给「当前歌词」：主行已经是歌名了，副行再放歌手属于信息重复；
        // 空闲时想扫一眼词才是用户的真实诉求。取不到词（前奏/间奏/纯音乐）才退回歌手。
        String lyric = trimText(
                com.muoniumplayer.core.lyrics3d.WorldLyricsState.activeLyricText(),
                NOW_PLAYING_LYRIC_LIMIT);
        String subLabel;
        if (!lyric.isEmpty()) {
            subLabel = "· " + lyric;
        } else {
            String safeArtist = trimText(artist, NOW_PLAYING_ARTIST_LIMIT);
            // 歌手前面补一个分隔点：常驻行的"单位"槽间距只有 2.2 像素，直接接歌名会读成一个词。
            subLabel = safeArtist.isEmpty() ? "" : "· " + safeArtist;
        }
        Chip[] chips = new Chip[] {
                new Chip(Kind.NOW_PLAYING, safeTitle, safeTitle, subLabel, Health.NEUTRAL, false)
        };
        return new Snapshot(chips, epochMillis, Page.NOW_PLAYING, moving);
    }

    /** 截断到指定长度并补省略号。null / 空白一律返回空串。 */
    private static String trimText(String value, int limit) {
        if (value == null) return "";
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return "";
        if (trimmed.length() <= limit) return trimmed;
        return trimmed.substring(0, Math.max(1, limit - 1)) + "…";
    }

    /**
     * 该显示哪一页。
     *
     * <p>刻意写成 {@code now} 的纯函数（不保存"上次翻页时刻"）：常驻岛可能被玩家列表、界面切换
     * 反复打断，用累计计时会在每次恢复时错一段相位。按绝对时间分片则无论中间中断多久，
     * 回来时都落在正确的节拍上。</p>
     *
     * <p>两页的停留时长是分开配置的，所以不能再用「按固定间隔取整判奇偶」那种写法。改成把
     * 「状态时长 + 歌曲时长」当作一个周期，用绝对时间对周期取模得到相位，相位落在前半段显示状态页、
     * 后半段显示歌曲页。这样既保留了「纯 {@code now} 函数」的性质（中断多久回来都落在正确节拍上），
     * 又让两段可以不等长。</p>
     *
     * @param statusMillis     状态页停留时长，非正数时按 5 秒兜底
     * @param nowPlayingMillis 正在播放页停留时长，非正数时按 8 秒兜底
     */
    static Page pageFor(long now, boolean statusAvailable, boolean nowPlayingAvailable,
                        boolean rotate, double statusMillis, double nowPlayingMillis) {
        if (!nowPlayingAvailable) return Page.STATUS;
        if (!statusAvailable) return Page.NOW_PLAYING;
        if (!rotate) return Page.STATUS;
        // 取模用 long：epoch 毫秒到 2026 年已经是 1.7e12，用 double 做除法再取整虽然还在 53 位精度内，
        // 但整数取模更直白，也不会因为间隔是小数而在边界上抖动。
        long status = Math.max(1L, Math.round(statusMillis > 0.0 ? statusMillis : 5000.0));
        long playing = Math.max(1L, Math.round(nowPlayingMillis > 0.0 ? nowPlayingMillis : 8000.0));
        long phase = Math.floorMod(now, status + playing);
        return phase < status ? Page.STATUS : Page.NOW_PLAYING;
    }

    /** 玩家是否在移动。带滞回：停下后 {@link #MOVING_HOLD_MILLIS} 内仍算移动。 */
    static boolean sampleMoving(long now) {
        try {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            if (mc == null || mc.player == null) {
                lastMovingAt = 0L;
                return false;
            }
            double delta = Math.abs(mc.player.getX() - mc.player.xo)
                    + Math.abs(mc.player.getY() - mc.player.yo)
                    + Math.abs(mc.player.getZ() - mc.player.zo);
            if (delta > 0.0035) lastMovingAt = now;
        } catch (Throwable ignored) {
            return false;
        }
        return lastMovingAt > 0L && now - lastMovingAt <= MOVING_HOLD_MILLIS;
    }

    /** 读取真实游戏状态。任何取值失败都退化成「未知」，绝不让常驻显示把渲染打断。 */
    static Snapshot capture(long now) {
        boolean moving = sampleMoving(now);
        Page page = pageFor(now, HudConfig.dynamicIslandAmbientFps || HudConfig.dynamicIslandAmbientPing
                        || HudConfig.dynamicIslandAmbientClock,
                HudConfig.dynamicIslandAmbientNowPlaying && hasCurrentTrack(),
                HudConfig.dynamicIslandAmbientRotate,
                HudConfig.dynamicIslandAmbientStatusSeconds * 1000.0,
                HudConfig.dynamicIslandAmbientNowPlayingSeconds * 1000.0);
        if (page == Page.NOW_PLAYING) {
            Snapshot nowPlaying = captureNowPlaying(now, moving);
            // 歌曲信息临时取不到（切歌瞬间）时退回状态页，而不是显示一张空卡片。
            if (!nowPlaying.isEmpty()) return nowPlaying;
        }
        return captureStatus(now, moving);
    }

    /**
     * 播放器交接回来时用的采样：只问「现在在放什么」。
     *
     * <p>不参与状态页/歌曲页轮换，也不受常驻那三项开关影响——交接只有几百毫秒，用户要看到的是
     * 刚关掉的那首歌，而不是一枚帧率读数。没有在放任何东西时返回 {@link #EMPTY}，
     * 让灵动岛收成紧凑胶囊，而不是展开一张空卡片。</p>
     */
    static Snapshot captureHandoff(long now) {
        return captureNowPlaying(now, sampleMoving(now));
    }

    /** 当前是否有正在播放的歌曲。任何异常都当作"没有"。 */
    private static boolean hasCurrentTrack() {
        try {
            return com.muoniumplayer.core.ncm.music.CloudMusic.currentlyPlaying != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Snapshot captureNowPlaying(long now, boolean moving) {
        try {
            com.muoniumplayer.core.ncm.music.dto.Music music =
                    com.muoniumplayer.core.ncm.music.CloudMusic.currentlyPlaying;
            if (music == null) return EMPTY;
            return describeNowPlaying(music.getName(), music.getArtistsName(), now, moving);
        } catch (Throwable ignored) {
            return EMPTY;
        }
    }

    private static Snapshot captureStatus(long now, boolean moving) {
        boolean showFps = HudConfig.dynamicIslandAmbientFps;
        boolean showPing = HudConfig.dynamicIslandAmbientPing;
        boolean showClock = HudConfig.dynamicIslandAmbientClock;
        if (!showFps && !showPing && !showClock && !hasScaffoldChip()) return EMPTY;
        int fps = -1;
        int ping = 0;
        boolean pingKnown = false;
        if (showFps) {
            try {
                fps = net.minecraft.client.Minecraft.getInstance().getFps();
            } catch (Throwable ignored) {
                fps = -1;
            }
        }
        if (showPing) {
            try {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc != null && mc.player != null && mc.getConnection() != null) {
                    net.minecraft.client.multiplayer.PlayerInfo info =
                            mc.getConnection().getPlayerInfo(mc.player.getUUID());
                    if (info != null) {
                        ping = info.getLatency();
                        pingKnown = true;
                    }
                }
            } catch (Throwable ignored) {
                pingKnown = false;
            }
        }
        Snapshot status = describe(showFps, showPing, showClock, fps, ping, pingKnown, now);
        // describe 保持原签名（离线探针在用），移动状态在这里补进快照。
        // 外挂条目（搭路方块数/方位）并入状态页最前面。
        Chip[] merged = mergeScaffold(status.chips);
        return merged.length == 0 ? EMPTY : new Snapshot(merged, now, Page.STATUS, moving);
    }

    private static Health fpsHealth(int fps) {
        if (fps >= 90) return Health.GOOD;
        if (fps >= 45) return Health.NEUTRAL;
        if (fps >= 25) return Health.WARN;
        return Health.BAD;
    }

    private static Health pingHealth(int ping) {
        if (ping <= 60) return Health.GOOD;
        if (ping <= 120) return Health.NEUTRAL;
        if (ping <= 250) return Health.WARN;
        return Health.BAD;
    }

    private static String formatClock(long epochMillis) {
        CALENDAR.setTimeInMillis(epochMillis);
        int hour = CALENDAR.get(Calendar.HOUR_OF_DAY);
        int minute = CALENDAR.get(Calendar.MINUTE);
        return twoDigits(hour) + ":" + twoDigits(minute);
    }

    private static String twoDigits(int value) {
        int safe = Math.max(0, Math.min(99, value));
        return safe < 10 ? "0" + safe : Integer.toString(safe);
    }

    /**
     * 灵动岛是否应该可见。常驻只是多了一条「没有任何事件也留在屏幕上」的理由，
     * 下载/完成停留/通知这三条既有理由完全不变，所以排队机制的时序不受影响。
     */
    static boolean shouldShow(boolean enabled, boolean alwaysOn, boolean activeDownload,
                              boolean holdingCompletion, boolean activeNotice) {
        return enabled && (alwaysOn || activeDownload || holdingCompletion || activeNotice);
    }

    /** 常驻内容只在「确实无事可报」时出现，永远不会抢下载或通知的展示位。 */
    static boolean isAmbient(boolean enabled, boolean alwaysOn, boolean activeDownload,
                             boolean holdingCompletion, boolean activeNotice) {
        return enabled && alwaysOn && !activeDownload && !holdingCompletion && !activeNotice;
    }

    /**
     * 常驻且已经稳定停在屏幕上时，必须抑制入场动画：否则每来一条通知都会重播一次
     * 缩放入场，看起来像灵动岛在原地抽动。阈值取 .55 是为了让「刚打开常驻」那一次入场照常播完。
     */
    static boolean suppressEntry(boolean alwaysOn, float visibility) {
        return alwaysOn && visibility > .55f;
    }

    /** 常驻但三项内容全关时收成紧凑胶囊，而不是展开一条空白卡片。 */
    static double expansionTarget(boolean shouldShow, boolean ambient, boolean hasChips) {
        if (!shouldShow) return 0.0;
        return ambient && !hasChips ? 0.0 : 1.0;
    }
}
