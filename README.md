<p align="center">
  <img src="./readme-header.png" alt="MonolithLib Banner" width="100%">
</p>

<div align="center">
  <img src="https://img.shields.io/badge/Minecraft-26.1-green?style=for-the-badge&logo=minecraft" alt="Minecraft">
  <img src="https://img.shields.io/badge/Kotlin-2.4.0-purple?style=for-the-badge&logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/License-MIT-blue?style=for-the-badge" alt="License">
</div>

<p align="center">
  <a href="https://github.com/pylonmc/rebar" target="_blank">
    <img src="https://img.shields.io/badge/生态伙伴-Rebar%200.42.0-9370DB?style=flat-square" alt="Rebar">
  </a>
</p>

<div align="center">
  <h1>MonolithLib</h1>
  <p><strong>Rebar 多方块机器的现代 3D 打印与高级验证方案</strong></p>
  <p>降维打击原生检测 · 完美状态支持 · 自动修正 · 告别手写坐标</p>
</div>

<div align="center">
  <a href="#-为什么需要-monolithlib"><strong>痛点</strong></a> ·
  <a href="#-核心理念"><strong>理念</strong></a> ·
  <a href="#-快速开始"><strong>快速开始</strong></a> ·
  <a href="#-rebar-集成指南"><strong>Rebar 集成</strong></a> ·
  <a href="#-项目结构"><strong>架构</strong></a> ·
  <a href="./DEVELOPER_GUIDE.md"><strong>开发者指南</strong></a>
</div>

<br/>

---

## 🤔 为什么需要 MonolithLib？

如果你在用 [Rebar](https://github.com/pylonmc/rebar) 做巨型多方块机器，你一定会遇到这三个致命痛点：

1. **状态丢失**：Rebar 原生预览和检测不支持楼梯朝向、半砖等复杂 `BlockData`。你定义的机器只能是光秃秃的方块。
2. **状态严格匹配**：巨型机器（几千方块）如果玩家放错了一个楼梯方向，Rebar 会瞬间判定 `unform`，整个机器停机，体验极差。
3. **检测性能瓶颈**：Rebar 的 `checkFormed()` 是暴力遍历匹配，结构越大，每次放置方块时的延迟检测消耗越严重。

**MonolithLib 就是为了彻底消灭这三个问题而生。**

---

## 💡 核心理念：“验改分离”与独立引擎

MonolithLib **不依赖** Rebar 的检测系统，而是拥有一套完全独立的、借鉴格雷科技的高级验证引擎。

- **独立验证**：采用增量式、格雷科技级别的高性能检测，**只检查材质匹配**，忽略方块状态（朝向、半砖类型等）。玩家只需放对材质，状态由 MonolithLib 自动修正。
- **无缝欺骗**：当所有方块材质匹配后，MonolithLib 会在底层**瞬间自动修正**所有方块状态为 Rebar 要求的精确状态，然后轻触 Rebar 的触发器，让 Rebar 毫无察觉地接收这台完美机器。

### 两个核心概念
1. **Shape（形状）**：纯粹的 3D 物理模型。包含精确的相对坐标和 `BlockData`（完美保存楼梯朝向等状态）。
2. **Blueprint（蓝图）**：`Shape` + 业务元数据（显示名、所需材料、核心偏移量）。这是玩家和开发者直接交互的对象。

---

## ✨ 核心特性

<div align="center">
  <table>
    <tr>
      <td align="center" width="25%">
        <h3>🧠 高级验证引擎</h3>
        <p>独立于 Rebar，只检查材质匹配，忽略方块状态，巨型结构不卡顿</p>
      </td>
      <td align="center" width="25%">
        <h3>🛠️ 瞬间状态修正</h3>
        <p>材质全部匹配后，自动将所有方块状态修正为 Rebar 要求的精确状态</p>
      </td>
      <td align="center" width="25%">
        <h3>👁️ 极致性能预览</h3>
        <p>类似 Litematica 的逐层投影，仅渲染玩家周身 7 格幽灵方块，万级结构丝滑预览</p>
      </td>
      <td align="center" width="25%">
        <h3>📦 零代码定义形状</h3>
        <p>支持导入 .schem/.litematic/.nbt，导出极速二进制 .mnb 格式，游戏内搭建导出</p>
      </td>
    </tr>
  </table>
</div>

---

## 🚀 快速开始（开发者视角）

作为 Rebar 机械开发者，你的代码将变得极度清爽。你只需要关心机器的逻辑，物理形状全部交给 `.mnb`。

### 1. 注册蓝图

```kotlin
class MyPlugin : JavaPlugin() {
    override fun onEnable() {
        // 加载物理形状
        val shape = MonolithAPI.io.loadShape(File(dataFolder, "blast_furnace.mnb"))
        
        val blueprint = Blueprint(
            id = "blast_furnace",
            shape = shape,
            meta = BlueprintMeta(
                name = text("高级高炉"),
                description = listOf(text("支持自动状态修正的巨型高炉"))
            )
        )
        
        MonolithAPI.registry.register(blueprint)
    }
}
```

### 1b. 从 .mnb 加载成品结构（推荐给复杂机器）

游戏内录制的复杂结构（两阶段 + 展示实体）用 `.mnb` 分发，用代码定制而无需 Setting.yml：

```kotlin
override fun onEnable() {
    saveResource("blast_furnace.mnb", false)   // 打包进插件 resources
    val file = File(dataFolder, "blast_furnace.mnb")

    val blueprint = MonolithAPI.io.loadBuiltMNB(file)!!.transform {
        controllerRebarKey(NamespacedKey("myplugin", "blast_furnace_controller"))
        scaffoldMaterials(mapOf(Material.IRON_BLOCK to Material.CONCRETE))  // 脚手架批量换材料
        overrideRebar(Vector3i(1, 0, 0), NamespacedKey("myplugin", "input_hatch")) // 位置级 Rebar 覆盖
    }
    MonolithAPI.registry.register(blueprint)
}
```

`transform` 提供与 Setting.yml 等价的全部能力（overrides / scaffold_materials / rotation / display 覆写 / 控制器 key），详见 `BlueprintTransformer` 与开发者指南第 11 章。

### 2. 编写 Rebar 代码（坐标契约）

Rebar 代码里写死最严格的规则，MonolithLib 会在最后帮你"骗"过这些规则。

```kotlin
class BlastFurnaceMultiblock : RebarSimpleMultiblock {
    override val components = mapOf(
        Pair(Vector3i(0, 0, 0), RebarMultiblockComponent(NamespacedKey("myplugin", "core"))),
        // Rebar 要求必须是朝西的楼梯，但玩家朝东放了也能用，MonolithLib 会自动修正！
        Pair(Vector3i(1, 0, 0), VanillaBlockdataMultiblockComponent(
            Material.OAK_STAIRS.createBlockData("[facing=west,half=bottom]")
        ))
    )
}
```

---

## 🔌 Rebar 集成指南：魔法开关（验改分离）

当玩家在建造巨型结构时，MonolithLib 完全接管了检测过程：

1. **玩家建造期**：Rebar 完全休眠。MonolithLib 的高性能引擎实时检测，**只检查材质**，即使玩家把楼梯放反了，MonolithLib 也算作"有效进度"。
2. **材质全部匹配**：当所有方块材质都正确放置后，MonolithLib 判定建造完成。
3. **自动修正**：MonolithLib 在 1 tick 内，将所有方块 `setBlockData` 修正为 Rebar 代码里要求的精确状态。
4. **唤醒 Rebar**：MonolithLib 自动触发 Rebar 的核心放置机制。Rebar 醒来一测，全是完美匹配，瞬间 `onMultiblockFormed()`。

**开发者无需编写任何修正逻辑，MonolithLib 全包了。**

---

## 🎮 玩家使用方法

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
- `/monolith save <scaffold|assembled> <ID> [--no-displays]` - 保存建造阶段
- `/monolith merge <ID>` - 合并两阶段生成 `.mnb`
- `/monolith project reload <ID>` - 热重载项目配置
- `/monolith project test <ID>` - 发放完整测试工具包

**工地系统 (BuildSite)**：玩家使用蓝图锚点物品右键放置后创建的建造区域，支持：
- 分层渲染：逐层显示待建造的幽灵方块（黄=材质正确但状态可不同，红=材质不匹配）
- 进度追踪：记录已放置的方块位置
- 核心检测：支持 Rebar 核心控制器的放置检测
- 朝向调整：放置时可设定结构朝向

**轻松放置模式 (EasyBuild)**：类似 Litematica 的半自动建造
- 玩家手持正确材质的方块对准幽灵位置右键即可自动放置
- 层级完成时自动切换到下一层
- 建造完所有层后自动进行最终状态修正

**自动打印模式 (Printer)**：自动化的建造助手
- 玩家周围 7 格范围内的幽灵方块会被自动放置
- 每 4 tick 自动检测并放置一个方块
- 适合生存模式下的快速建造

**定型与解体**：玩家手持多方块扳手右键工地锚点完成定型（脚手架替换为成型方块 + 激活 Rebar 控制器 + 托管展示实体）；Shift+右键成型结构使用扳手解体，结构恢复为脚手架工地。

**建造体验**：玩家看着投影，不需要死磕每一个方块的朝向。只要放对了材质，MonolithLib 会自动修正所有状态，机器直接启动。

---

## 🧱 项目结构

```
MonolithLib/
├── api/                      # 🚪 对外门面
│   ├── MonolithAPI.kt        # 核心 API 入口
│   ├── BlueprintAPI.kt       # 蓝图操作 API
│   ├── dsl/                  # DSL 构建器
│   └── event/                # 事件定义
│
├── common/                   # 🔧 通用基础设施
│   ├── I18n.kt               #    多语言消息目录
│   ├── MonolithLogger.kt     #    统一日志
│   └── Extensions.kt         #    通用扩展
│
├── core/                     # 🧱 纯粹的基础设施
│   ├── model/                #    Shape、Blueprint、FormStrategy 数据模型
│   ├── math/                 #    向量、矩阵数学
│   ├── io/                   #    格式读写器 (.mnb/.schem/.litematic/.nbt) 与编译器
│   └── transform/            #    坐标变换、方块状态旋转、朝向
│
├── feature/                  # 🛠️ 业务功能
│   ├── preview/             #    Ghost 渲染器、投影会话、显示实体池
│   ├── builder/             #    建造执行器
│   ├── buildsite/           #    工地系统：锚点、分层建造、EasyBuild、Printer
│   ├── display/             #    显示实体组与管理
│   ├── editor/              #    选区扫描器
│   ├── material/            #    材料统计计算器
│   ├── rebar/               #    Rebar 适配器
│   └── virtual/             #    虚拟显示锚点
│
├── validation/               # 🛡️ 核心灵魂：高级验证引擎
│   ├── ValidationEngine.kt  #    高性能增量检测（只检查材质）
│   ├── AsyncValidator.kt    #    异步验证
│   ├── AutoFixer.kt         #    自动状态修正
│   └── predicate/           #    谓词工厂：严格/松散/Rebar 匹配
│
├── integration/              # 🔗 第三方集成
│   ├── MNBController.kt                # MNB/Rebar 多方块控制器
│   ├── MonolithComponents.kt           # 默认组件与开发者覆盖 API
│   ├── MultiblockWrench.kt             # 定型/解体扳手
│   └── ProjectControllerRegistry.kt    # 控制器注册
│
├── lifecycle/                # ♻️ 生命周期管理
│   └── BlueprintLifecycle.kt
│
└── internal/                 # ⚙️ 内部实现
    ├── command/              #    命令系统
    ├── listener/             #    事件监听器
    ├── mixin/                #    底层注入
    ├── scheduler/            #    调度器
    └── selection/            #    选区魔杖与选区管理
```

---

## ️ 路线图 (未来计划)

- [x] **投影打印机**：玩家周围 7 格内的幽灵方块自动放置，每 4 tick 一个方块
- [ ] **蓝图加农炮**：类似机械动力，搭好加农炮，放入材料图纸，轰出巨型机器。
- [ ] **图形化蓝图 GUI**：分类浏览、材料预览。
- [ ] ~~**蓝图分享网络**~~：已取消

---

## 📄 许可证

本项目采用 MIT 许可证 - 详见 [LICENSE](./LICENSE) 文件。

<div align="center">
  <p>用 MonolithLib，让你的玩家享受造巨型机器的乐趣，而不是被死板的规则折磨。</p>
  <p>如果觉得有用，请给一个 ⭐️ ！</p>
</div>
