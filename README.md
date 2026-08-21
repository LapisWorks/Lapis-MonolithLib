<p align="center">
  <img src="./readme-header.png" alt="MonolithLib Banner" width="100%">
</p>

<div align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.2-green?style=for-the-badge&logo=minecraft" alt="Minecraft">
  <img src="https://img.shields.io/badge/Kotlin-2.4.0-purple?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License">
</div>

<p align="center">
  <a href="https://github.com/pylonmc/rebar" target="_blank">
    <img src="https://img.shields.io/badge/生态伙伴-Rebar%200.43.0--26.2-9370DB?style=flat-square" alt="Rebar">
  </a>
</p>

<div align="center">
  <h1>MonolithLib</h1>
  <p><strong>Rebar 多方块的蓝图基础设施：搭好即保存，复用零代码</strong></p>
  <p>游戏内录制 · 展示实体入库 · 幽灵投影 · 百万方块不卡顿</p>
</div>

<div align="center">
  <a href="#-rebar-做不到的事"><strong>痛点</strong></a> ·
  <a href="#-核心能力"><strong>核心能力</strong></a> ·
  <a href="#-工作机制"><strong>工作机制</strong></a> ·
  <a href="#-快速开始"><strong>快速开始</strong></a> ·
  <a href="#-性能工程"><strong>性能工程</strong></a> ·
  <a href="#-项目结构"><strong>架构</strong></a> ·
  <a href="./DEVELOPER_GUIDE.md"><strong>开发者指南</strong></a>
</div>

<br/>

---

## 🤔 Rebar 做不到的事

[Rebar](https://github.com/pylonmc/rebar) 的多方块机制很强，但结构定义**完全依赖代码**：每个组件、每个坐标、每份 `BlockData` 都要写死在代码里。这让 Rebar 天然缺了这几块拼图：

| 痛点 | Rebar 原生 | MonolithLib 补上 |
|---|---|---|
| 📦 结构无法保存/复用 | 组件逐块代码定义，搭好的结构留不下来 | 游戏内选区录制 → 一个 `.mnb` 文件，随手发放即用 |
| 🎬 展示实体无法入库 | 机器外观（旋转部件、发光、指示灯）只能在代码里运行时拼 | 展示实体随蓝图一起保存，定型后自动托管还原 |
| 👁️ 幽灵投影不缩放 | Rebar 有组件幽灵，但全量生成、每次检测全量刷新颜色，大结构直接崩 | 玩家中心的工地渲染：7 格半径节流、远距外框、红/黄提示、自动放置 |
| ⚡ 大结构会卡死 | 就算用代码定义百万方块结构，原生检测（全量匹配）与幽灵生成也扛不住 | 百万方块全流程不卡顿（异步/批处理/O(1) 索引） |
| 🧱 状态定义繁琐 | 朝向、半砖等状态必须在代码里精确声明，`checkFormed` 严格匹配、错一个 unform | 游戏里搭出来什么样，保存下来就是什么样 |

> **一句话：Rebar 的多方块是"代码写出来的"，MonolithLib 的多方块是"游戏里搭出来、存起来、随手复用的"。**

---

## 💡 核心能力

### 📦 搭好即存：两阶段蓝图系统

用魔杖框选结构，游戏内搭好脚手架与成型两阶段，`/monolith save` 录制、`/monolith merge` 合并成一个 `.mnb`：

- **脚手架阶段（scaffold）**：玩家要亲手铺的部分
- **成型阶段（assembled）**：定型后的最终机器（精确 `BlockData`）
- **展示实体（display entities）**：旋转部件、发光指示、自定义模型——**随蓝图一起保存**，定型后由 MonolithLib 自动托管还原

分发方式：蓝图锚点物品。玩家右键一放，一个可建造的工地就出现在眼前。

### 👁️ 玩家中心的工地渲染（Rebar 的组件幽灵做不到这个规模）

Rebar 的 `SimpleRebarMultiblock` 自带组件幽灵：每个组件生成 3 个实体（BlockDisplay + ItemDisplay + 点击取物 Hitbox），放置时全量生成、每次 `checkFormed` 全量刷新颜色——几十个组件时很贴心，百万级时是灾难（一次生成 300 万实体）。

MonolithLib 的工地渲染是**玩家中心**的：

- 只渲染玩家周身 7 格内的幽灵（实体上限 300），远距只画结构外框（绿=已完成）
- 红=材质错，黄=材质对但状态不同（定型时自动修正）
- 分层投影、EasyBuild 自动放置、Printer 自动打印

### ⚡ 百万方块不卡顿

从建造、验证、成型到解体，全链路围绕"主线程零阻塞"设计。细节见[性能工程](#-性能工程)。

### 🧱 宽松验证（一个小点，但让建造舒服）

玩家放对了材质就算有效进度，朝向/半砖状态错了不触发停机；定型时 MonolithLib 一次性把精确状态写入，交给 Rebar 标准检测。宽容的建造体验，严格的运行结果。

---

## 💡 工作机制

MonolithLib 不绕过 Rebar 的检测——把生命周期切成两段：

**阶段一 · 建造期（MonolithLib 接管）**
- 玩家在工地上按蓝图铺方块，MonolithLib 实时验证（材质级），完成度 O(1) 增量计数。

**阶段二 · 定型后（Rebar 接管）**
- 扳手定型：所有方块写入蓝图保存的精确 `BlockData`，锚点替换为真正的 Rebar 控制器，`checkFormed()` 天然通过，机器作为标准 Rebar 多方块运行。
- 解体同理：控制器移除，结构分批转回脚手架工地。

---

## 🚀 快速开始（开发者视角）

### 0. 环境与依赖

| 项 | 版本 |
|---|---|
| Minecraft | **26.2** |
| Rebar | **0.43.0-26.2**（[JitPack](https://jitpack.io/#pylonmc/rebar)） |
| JDK | 25 |

附属插件接入方式：

```kotlin
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

dependencies {
    compileOnly("io.github.pylonmc:rebar:0.43.0-26.2")
    compileOnly(files("libs/MonolithLib-1.2.0-all.jar")) // 或发布到私有仓库
}
```

### 1. 注册蓝图

```kotlin
class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        val blueprint = MonolithAPI.io.loadBuiltMNB(File(dataFolder, "blast_furnace.mnb")) ?: return
        MonolithAPI.registry.register(blueprint)
    }
}
```

简单结构也可以用 `Blueprint.fromSingleShape` 直接构造：

```kotlin
val shape = MonolithAPI.io.loadShape(File(dataFolder, "frame.schem")) ?: return
MonolithAPI.registry.register(
    Blueprint.fromSingleShape(
        id = "blast_furnace",
        shape = shape,
        meta = BlueprintMeta(displayName = "高级高炉", description = "支持自动状态修正的巨型高炉")
    )
)
```

### 2. 代码化定制（可选，替代 Setting.yml）

```kotlin
val mine = MonolithAPI.io.loadBuiltMNB(file)!!.transform {
    controllerRebarKey(NamespacedKey("myplugin", "blast_furnace_controller"))
    controllerMaterial(Material.STRUCTURE_BLOCK)
    scaffoldMaterials(mapOf(Material.IRON_BLOCK to Material.CONCRETE)) // 脚手架批量换材料
    overrideAt("1, 0, 0", "rebar", rebarKey = NamespacedKey("myplugin", "input_hatch"), preview = Material.HOPPER)
    meta("高级高炉", description = "...")
}
MonolithAPI.registry.register(mine)
```

`transform` 提供与 Setting.yml 等价的全部能力（overrides / scaffold_materials / rotation / display 覆写 / 控制器 key），详见 `BlueprintTransformer` 与开发者指南第 11 章。

### 3. 控制器组件（Rebar 侧）

控制器所需的组件映射默认由蓝图生成——每个成型方块对应一个精确 vanilla 组件，蓝图中的 Rebar 位置对应 Rebar 组件；可按位置/槽位覆盖：

```kotlin
val components: Map<Vector3i, MultiblockComponent> = MonolithComponents.fromMNB(blueprint)
    .replaceSlots(blueprint, "input", MonolithComponents.rebarAny(MY_INPUT_KEY))
    .toMap()
```

控制器类需通过 `ProjectControllerRegistry` 注册（参考 `integration/` 与开发者指南）。

---

## 🔌 与 Rebar 的协作流程

1. **建造期**：Rebar 不参与检测，MonolithLib 验证引擎实时判定（只比材质）。红幽灵=材质错；黄幽灵=状态差异，定型时自动修正。
2. **判定完成**：完成度计数器达到 100%，扳手定型可用。
3. **定型**：MonolithLib 把全部方块写入精确 BlockData（脚手架→成型），锚点替换为控制器，触发 `checkFormed()` → `onMultiblockFormed()`。
4. **运行期**：机器作为标准 Rebar 多方块工作；破坏任意组件或 Shift+扳手控制器 → 分批解体回脚手架工地，可继续调整。

**开发者无需编写任何修正逻辑。**

---

## ⚡ 性能工程

面向百万方块级结构的实测优化，设计原则是**主线程零阻塞**：

- **异步构建**：ghost 数据、完成度校准等 1M 级纯计算放在单线程后台执行器（`BuildSiteAsync`），结果回投主线程应用。
- **O(1) 完成度**：放置/破坏时逐位置增量维护计数器，不再每次放置都全量扫描结构。
- **O(1) 组件定位**：成型索引 = 世界 → 成型条目（AABB 粗筛 + 蓝图精确判定），破坏组件/扳手解体不再遍历百万位置。
- **批处理转换**：解体/成型按 ≤8ms/tick 分摊，未加载区块延迟到 chunk load 补转，无单帧冻结。
- **哈希保真**：`Vector3i.toLong()` 21 位位打包（历史上 z 轴只保留低 2 位，Long 键集合退化为红黑树）。
- **共享实例**：材质 predicate 全局缓存，消除百万次包装对象构造。
- **渲染节流**：仅渲染玩家周身 7 格内的幽灵（上限 300 实体），远距只画结构外框（绿=已完成）。

---

## 🎮 玩家使用

**蓝图浏览与发放**
- `/monolith bp list` - 查看所有可用蓝图
- `/monolith bp info <ID>` - 查看详情、阶段方块数、展示实体与材料
- `/monolith bp give <ID>` - 给予蓝图工地锚点物品

**预览与建造**
- `/monolith preview <ID>` - 开启分层投影预览
- `/monolith preview stop` - 停止预览
- `/monolith build here <ID> [facing]` - 一键建造
- `/monolith easybuild [on|off]` - 开启/关闭轻松放置模式
- `/monolith printer [on|off]` - 开启/关闭自动打印模式

**工地管理 (BuildSite)**
- `/monolith site list` - 活跃工地列表
- `/monolith site info` - 附近工地状态（进度/朝向）
- `/monolith site cancel` - 取消工地

**蓝图制作（开发者/管理员）**
- `/monolith wand` - 获取选区魔杖
- `/monolith save <scaffold|assembled> <ID> [--no-displays]` - 保存建造阶段（含展示实体）
- `/monolith merge <ID>` - 合并两阶段生成 `.mnb`
- `/monolith project reload <ID>` - 热重载项目配置
- `/monolith project test <ID>` - 发放完整测试工具包

**建造体验**：玩家看着幽灵投影，不需要死磕每一个方块的朝向。只要放对了材质，MonolithLib 会自动修正所有状态，机器直接启动。

---

## 🧱 项目结构

```
MonolithLib/
├── api/                      # 🚪 对外门面
│   ├── MonolithAPI.kt        # 核心 API 入口（registry / io / preview）
│   ├── BlueprintTransformer.kt  # 代码化定制（等价 Setting.yml）
│   └── dsl/                  # DSL 构建器
│
├── common/                   # 🔧 通用基础设施（I18n、日志）
│
├── core/                     # 🧱 纯基础设施
│   ├── model/                #    Shape、Blueprint、FormStrategy、BoundingBox
│   ├── math/                 #    Vector3i（21 位位打包）、矩阵
│   ├── io/                   #    .mnb/.schem/.litematic/.nbt 读写与编译器
│   └── transform/            #    坐标变换、BlockData 旋转、朝向
│
├── feature/                  # 🛠️ 业务功能
│   ├── preview/              #    分层投影、幽灵渲染、远距外框
│   ├── buildsite/            #    工地系统：锚点、验证、EasyBuild、Printer、BuildSiteAsync
│   ├── buildmode/            #    自动打印任务
│   ├── builder/              #    一键建造
│   ├── display/              #    展示实体池
│   ├── editor/               #    选区扫描
│   ├── material/             #    材料统计
│   ├── rebar/                #    Rebar 适配
│   └── virtual/              #    虚拟显示锚点
│
├── validation/               # 🛡️ 验证引擎与谓词
│   └── predicate/            #    材质/严格/Rebar/旋转谓词
│
├── integration/              # 🔗 Rebar 集成
│   ├── MNBController.kt      #    多方块控制器（成型/解体）
│   ├── MonolithComponents.kt #    组件生成与覆盖 API
│   ├── MultiblockWrench.kt   #    定型/解体扳手
│   └── ProjectControllerRegistry.kt
│
├── lifecycle/                # ♻️ 生命周期数据（PositionCache）
│
└── internal/                 # ⚙️ 内部实现
    ├── command/              #    命令系统
    ├── listener/             #    事件监听器
    ├── scheduler/            #    调度器
    └── selection/            #    选区魔杖
```

---

## 🗺️ 路线图

- [x] **投影打印机**：玩家周围 7 格内的幽灵方块自动放置，每 4 tick 一个方块
- [ ] **蓝图加农炮**：类似机械动力，搭好加农炮，放入材料图纸，轰出巨型机器
- [ ] **图形化蓝图 GUI**：分类浏览、材料预览
- [ ] ~~**蓝图分享网络**~~：已取消

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](./LICENSE) 文件。

<div align="center">
  <p>用 MonolithLib，让你的玩家享受造巨型机器的乐趣，而不是被死板的规则折磨。</p>
  <p>如果觉得有用，请给一个 ⭐️ ！</p>
</div>
