# MuoniumPlayer 内建说明

本文说明 `muonium/` 目录的来历、当前移植状态、以及怎么构建。

> 状态分级沿用 Muonium 仓库自己的口径：**已修改** / **静态核对通过** / **构建通过** / **游戏实测通过**。
> 没跑到的那一级不写成通过。

---

## 1. 做了什么

### 1.1 移除 NEL

NEL（网易云外部启动器链路）已废弃，整条链路删除：

| 删除项 | 内容 |
|---|---|
| `src/main/java/dev/southside/nel/` | `NeteaseLauncher`、`OpenNELApi`、`GameServer`、`protocol/ForwardServer`、`open_nel/*` |
| `src/main/java/dev/southside/gui/netease/` | `NeteaseScreen` 与 7 个页面、`NELSideBar`、`AbsPage` 等 |
| `SouthsideMenuScreen` | 主界面的 `NEL` 按钮 |
| `MixinTitleScreen` | 标题界面的 `NEL` 按钮 |
| `Southside.onInitialize` / `closeClient` | `NeteaseLauncher.get().killNEL()/init()/stop()` |

### 1.2 内建 MuoniumPlayer

`muonium/` 目录是一棵完整的 MuoniumPlayer 源码树，结构与上游一致：

```
muonium/
├── muonium-common/      共享业务层（1447 个 java，版本/加载器中立）
├── mpparty-common/      一起听
├── mpserver-core/       内嵌房间引擎（只读镜像）
├── gradle/              共用构建支持（kotlin-support.gradle 等）
└── 1.21.4/              本车道：版本差异层 + Fabric 外壳
    ├── src/main/java/   74 个文件（compat / mixin / 功能集）
    ├── fabric/          Fabric 外壳（入口、mixin 配置、AW、fabric.mod.json）
    └── libs/            rhino-relocated / voicechat-api / zstd-jni
```

---

## 2. 为什么基础版本是 1.21.1 而不是 1.21.11

Southside 是 **Minecraft 1.21.4 / Fabric**。MuoniumPlayer 没有 1.21.4 车道，只能从相邻车道移植。

两条候选：

| 基础 | 与 1.21.4 的距离 | 判断 |
|---|---|---|
| `1.21.11 recode` | 跨 1.21.5~1.21.11 | 渲染管线已被重写：`renderer.rendertype` 包、`RenderSetup`、`RenderPipeline`、`SubmitNodeCollector` **在 1.21.4 全部不存在**（已按官方 client 映射核实）。`Identifier` 也是 1.21.9 之后才改的名 |
| `1.21.1` | 跨 1.21.2~1.21.4 | 同属老式 `RenderType` 体系。版本层引用的 **65 个 MC 类在 1.21.4 全部存在，零缺失**（已核实） |

所以采用 1.21.1 作基础。这不是"差距小所以随便挑"，而是"类层面零缺失，只剩方法签名要逐个核对"。

### 2.1 已核实并修掉的差异

| 差异 | 依据 | 处理 |
|---|---|---|
| `net.minecraft.resources.Identifier` → 1.21.4 叫 `ResourceLocation` | 官方 client 映射：`Identifier` 不存在，`ResourceLocation` 存在 | 5 个文件改名；`ResourceLocation.fromNamespaceAndPath` 已核实存在 |
| `RegistryKey#identifier()` → 1.21.4 叫 `location()` | 映射：`ResourceKey.location()` 存在，`identifier()` 不存在 | `AmbientProbe` 改用 `location()` |
| `EntityRenderDispatcher.render` 少一个 `yaw` | 映射：1.21.4 是 `(Entity,D,D,D,F,PoseStack,MultiBufferSource,I[,EntityRenderer])`；1.21.1 是 `(Entity,D,D,D,F,F,...)` | `TransparentEntityMixin` / `SpinEntityMixin` 按 9 参重载改写签名 |
| `Entity#displayFireAnimation()` 在 1.21.4 不存在 | 映射查不到该成员；火焰动画已进 `EntityRenderState.displayFireAnimation` 字段 | 收尾注入点改为 `@At("RETURN")` |
| `EntityModel#setupAnim(Entity,F,F,F,F,F)` 变成 `setupAnim(EntityRenderState)` | 映射：单参数 | `SpinHeadMixin` 改为注入 `LivingEntityRenderer#extractRenderState` 的 RETURN，叠 `state.yRot/xRot` |

### 2.2 尚未核对完的部分（不写成已通过）

- 版本层另外约 60 个文件（`compat/mc` 其余、`core/screens`、`api/impl`、`compat/lwjgl2`）只做了**类级**核实，方法级签名未逐个对完。
- `SkijaHost`（52 KB）是 GL/FBO 生命周期与 Skija 上下文交接，1.21.4 与 1.21.1 的 GL 状态机差异未核实。
- 版本特有测试（`Port1211StaticTest` 等）已从本车道移除 —— 它们断言的是 1.21.1 的 API，不能拿来判 1.21.4 的成败。共享层测试保留。

---

## 3. GUI 入口

三个入口，都指向同一个反射桥 `dev.southside.muonium.MuoniumBridge`：

1. **主界面** `SouthsideMenuScreen`：`Music` 按钮（原 NEL 按钮的位置）
2. **标题界面** `MixinTitleScreen`：`Music` 按钮（原 NEL 按钮的位置）
3. **ClickGUI**：`Client` 分类下的按钮式模块 `MuoniumPlayer`

### 3.1 为什么用反射

Southside 用 **Yarn** 映射，Muonium 用 **Mojang 官方名（mojmap）**。

两边各自 remap 到 intermediary 后可以在同一进程共存 —— 那是 Fabric 的标准多 mod 机制 —— 但在**编译期**，Southside 的 classpath 上没有 Muonium 的符号。所以桥里只认字符串形式的类名与方法名：

```java
Class.forName("com.muoniumplayer.core.screens.onboarding.OnboardingManager")
       .getMethod("openPlayerEntry")   // public static void，已核对源码
       .invoke(null);
```

调 `openPlayerEntry()` 而不是直接 `NCMScreen.getInstance()`：前者会让 Muonium 自己判断要不要先走首次运行引导，后者会绕过它。

Muonium 缺失时按钮不会崩，会在聊天栏给一句可读提示。

---

## 4. 构建

**本地不编译，全部走 GitHub Actions**（`.github/workflows/build.yml`）。

两个 job 是串行的，因为内建依赖成品：

```
job muonium    : cd muonium/1.21.4 && ./gradlew :fabric:remapJar
                 → 产物 MuoniumPlayer-1.21.4-fabric-2.5.2.jar
job southside  : 取回上面的 jar → ./gradlew build
                 → Southside 产物里含 META-INF/jars/MuoniumPlayer-1.21.4-fabric-2.5.2.jar
```

Southside 的 `build.gradle` 里 `processResources` 负责把 Muonium 的 jar 塞进 `META-INF/jars/`；找不到就打印警告并产出一个**不含播放器**的包，不会静默。

`fabric.mod.json` 里对应声明了：

```json
"jars": [{ "file": "META-INF/jars/MuoniumPlayer-1.21.4-fabric-2.5.2.jar" }],
"depends": { "fabric-language-kotlin": ">=1.13.12" }
```

> 玩家侧因此需要装 **Fabric Language Kotlin**。Muonium 是 Kotlin/Java 混编，运行时靠 FLK 提供。

### 4.1 构建期注意

- 原 Muonium 工程里的本机事实已全部移除：`org.gradle.java.home`、`org.gradle.java.installations.paths`、`127.0.0.1:7890` 代理。这些在原开发者机器上是对的，在任何别的机器上都会直接失败。
- ModernUI 面板默认关闭（`-PuseModernUi` 才开）。原 1.21.11 树用的是 mVUS 分支的 `mc1.21.11` 构件，1.21.4 没有对应构建。
- `libs/muoniumconfig-1.8.0.jar`（26 MB）已删除 —— 它只用于开发期往 `run/mods` 里丢，不进构建。

---

## 5. 构建结果

| 项 | 结果 |
|---|---|
| CI run | `43605489193`，两个 job 均 success |
| Muonium 产物 | `MuoniumPlayer-1.21.4-fabric-2.5.2.jar`，17,004,848 字节 |
| Southside 产物 | `Southside-2.0.1-rebuilt.jar`，105.5 MB |
| 嵌套验证 | 成品内含 `META-INF/jars/MuoniumPlayer-1.21.4-fabric-2.5.2.jar`（同 17,004,848 字节） |

编译错误是三轮收敛下来的，全部是 1.21.2~1.21.4 的 API 变更，没有一处是"改不动只能删功能"：

| 轮次 | 错误数（去重后） | 内容 |
|---|---|---|
| 1 | 6 类 | `setPixelRGBA`→`setPixelABGR`、`TextureStateShard` 参数转 `TriState`、`VertexSorting`→`ProjectionType`、`applyModelViewMatrix` 被删、`getTimer`→`getDeltaTracker`、`getNearest` 重载、`upload` 少一参、`load(ResourceManager)` 钩子没了 |
| 2 | 2 类 | `setPixelABGR` 是 private（改走 AW 开放可见性）、`getNearest` 需要 4 参 |
| 3 | 0 | 两个 job 全绿 |

## 6. 已知限制

1. **没有游戏实测**。编译通过与"播放器能起界面、能出声"是两件事，按 Muonium 自己的验收口径这只到「构建通过」，没到「游戏实测通过」。Skija 渲染层能否在 1.21.4 的 GL 状态机上起帧、音源能否出声，都还没验证。
2. **`transparent` / `spin` 两个模式有已知观感降级**：1.21.1 把火焰、阴影、碰撞箱留在缩放矩阵之外，本车道用 `@At("RETURN")` 收尾，它们会一起进矩阵。要精确复现需改成注入 `EntityRenderState.displayFireAnimation` 的字段访问。
3. **`applyModelViewMatrix()` 是直接删掉的**：1.21.4 里矩阵由 `getModelViewMatrix()` 直接读，不再有「把栈顶应用到 GL」这一步。删掉后 3D 歌词直画的矩阵语义是否与 1.21.1 完全一致，需要在游戏里比对（这是本车道最可疑的一处）。
4. **Mixin 注入点是编译期验证不到的**：`muonium.mixins.json` 里 10 个 mixin 的目标方法在 1.21.4 是否存在、描述符是否匹配，只有进游戏才会暴露。已按映射逐个核对了 4 个实体渲染相关的，其余 6 个只做了类级核对。
5. **映射隔离靠反射**：桥里写的类名/方法名是 Muonium 的公开契约，改动它们等于改接口，不是重构。
6. **玩家需要装 Fabric Language Kotlin**（Muonium 是 Kotlin/Java 混编，运行时靠 FLK 提供）。
