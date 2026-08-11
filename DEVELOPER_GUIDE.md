# MonolithLib 开发者指南

本文面向使用 Rebar 开发多方块机器、建筑结构和带展示实体设备的插件作者。MonolithLib 管理结构空间数据、两阶段建造、玩家预览、成型/解体生命周期和 MNB 配置；机器的库存、配方、物流、GUI 与业务状态由你的控制器负责。

## 1. 总体模型

项目目录：

~~~text
Works/<id>/
├── <id>.mnb
└── Setting.yml
~~~

MNB 蓝图包含 scaffoldShape、assembledShape、displayEntities、控制器偏移、槽位、元数据和自定义数据。Setting.yml 是编译覆盖层，不修改原始导入文件。修改后执行 /monolith project reload <id>；已经成型的机器应先用扳手解体，再重新成型。

## 2. 生成蓝图

### 2.1 游戏内录制

1. 使用 /monolith wand 获取选区魔杖。
2. 左键和右键设置选区两个角点。
3. 保存脚手架阶段：

~~~text
/monolith save scaffold <id>
~~~

4. 保存成型阶段：

~~~text
/monolith save assembled <id>
~~~

5. 不需要扫描展示实体时使用 /monolith save assembled <id> --no-displays。
6. 两阶段完成后执行 /monolith merge <id> 生成 Works/<id>/<id>.mnb。

命令会报告扫描到的方块数和展示实体数。展示实体保存为结构相对坐标，不要手动改成世界绝对坐标。

### 2.2 外部导入与查询

MonolithLib 支持 .mnb、.schem、.litematic 和 .nbt 数据。推荐导入或录制后，通过 Setting.yml 调整控制器和显示行为。

~~~text
/monolith bp list
/monolith bp info <id>
~~~

bp info 会显示尺寸、脚手架/成型方块数、展示实体数量和类型、编组数、控制器键、注册状态、成型策略及两个偏移值。

## 3. Setting.yml

最小配置：

~~~yaml
id: example_machine
version: "1.0"

meta:
  name: "Example Machine"
  description: "A machine built with MonolithLib"
  author: "YourName"

controller:
  type: rebar
  rebar_key: yourplugin:example_controller
  position: "0, 1, 0"
  generated_material: structure_void

form_strategy: block_only
~~~

### 3.1 控制器注册规则

controller.rebar_key 是最终控制器的 Rebar key：

- 如果 Rebar 已注册，MonolithLib 直接使用，不重复注册，也不接管材质和控制器类。
- 如果尚未注册，MonolithLib 注册通用 MNBController，材质使用 controller.generated_material。
- 外部插件自行注册的控制器只由 MonolithLib 使用；蓝图详情会提示注册状态。
- 没有有效控制器 key 的项目不能定型，也不能正确解体。

generated_material 只对 MonolithLib 自己生成的控制器有效。外部注册控制器的材质完全由外部注册定义。

### 3.2 槽位

槽位只描述功能坐标，不自动创建库存或物流组。使用类型加序号支持任意数量端口：

~~~yaml
slots:
  input_1: "0, 1, 0"
  input_2: "0, 1, 2"
  output_1: "4, 1, 0"
  output_2: "4, 1, 2"
~~~

控制器中可以读取所有同类槽位：

~~~kotlin
val inputs: Map<String, RebarBlock> = slotBlocks("input")
val output = slotBlocks("output")["output_1"]
~~~

槽位位置会随蓝图朝向转换到世界坐标，不要复制旋转公式。

### 3.3 方块覆盖

overrides 用于编译阶段的单位置匹配和脚手架替换：

~~~yaml
overrides:
  "2, 1, 0":
    type: loose
    material: hopper
    preview: hopper
    ignore_states:
      - facing
~~~

常见类型是 strict、loose 和 rebar。这一层解决玩家建造时如何判断位置正确，不等同于 Rebar 最终成型组件。需要一个位置接受多个 Rebar 型号时，应在控制器代码中使用 MonolithComponents。

### 3.4 脚手架材料映射

映射方向是脚手架中的原材料 -> 脚手架中的替代材料：

~~~yaml
scaffold_materials:
  iron_block: concrete
  gold_block: concrete_powder
~~~

它只影响 scaffoldShape，不会修改 assembledShape。位置级 overrides 优先于批量映射。适合把昂贵最终材料替换成便宜的建造材料。

### 3.5 偏移和旋转

~~~yaml
display_offset: "-0.5, 0.0, -0.5"

rotation:
  scaffold: 0
  assembled: 180
  center: "2, 1, 2"
~~~

偏移格式必须是三个逗号分隔的浮点数。控制器位置、槽位和旋转中心是整数结构坐标。旋转只接受 0、90、180、270；编译器会同步旋转形状、控制器坐标、槽位和展示实体。

### 3.6 展示实体覆写

~~~yaml
display_entities:
  "2, 2, 1":
    type: block
    block: glass
    translation: "0, 0.25, 0"
    scale: "0.8, 0.8, 0.8"
    group: screen
~~~

type 可为 block 或 item。Item display 使用 item 字符串，旋转使用 x, y, z, w 四元数。group 是控制器查询和批量清理实体的稳定名称。

## 4. 玩家工作流

### 4.1 预览和放置

~~~text
/monolith preview <id>
/monolith bp give <id>
~~~

蓝图物品放置后生成 BuildSite 锚点。黄色 ghost 表示材质正确但状态可能不同；红色表示材质不匹配。脚手架阶段只检查材质，不检查方块朝向、半砖状态等 BlockData 状态，这是有意降低建造门槛。

### 4.2 辅助建造

~~~text
/monolith easy on
/monolith printer on
~~~

EasyBuild 响应玩家右键附近 ghost；Printer 自动处理附近候选位置。ghost 按玩家独立显示，只显示玩家周围 7 格内内容，不会让所有玩家共享大型工地的全部实体。

### 4.3 定型

玩家手持多方块扳手右键 BuildSite 锚点：

1. 检查材质完成率。
2. 检查控制器 key 存在且已注册。
3. 脚手架替换为 assembled 方块并应用旋转后的 BlockData。
4. 锚点替换为 Rebar 控制器。
5. 调用 Rebar 多方块成型回调。
6. 控制器生成并托管展示实体。

控制器未注册或蓝图没有控制器 key 时，定型会被阻止，不会留下半成品机器。

### 4.4 解体和破坏回退

Shift+右键成型机器或结构部分，使用扳手解体，结构恢复为脚手架工地且不掉落整机方块。成型结构任意组件被破坏时，MonolithLib 会取消破坏并立即回退到未成型阶段。

## 5. MNBController 生命周期

通用控制器位于 src/main/kotlin/top/mc506lw/monolith/integration/MNBController.kt。它实现 Rebar 的 RebarMultiblock、EntityHolderRebarBlock 和破坏处理接口。

关键回调：

~~~kotlin
override fun postLoad()
override fun onMultiblockFormed()
override fun onMultiblockUnformed(partUnloaded: Boolean)
override fun onBlockBreak(drops: MutableList<ItemStack>, context: BlockBreakContext)
~~~

推荐做法：

- 在 onMultiblockFormed 中调用 super，再创建交互实体、物流组或运行任务。
- 在 onMultiblockUnformed 中调用 super，停止任务并清理额外资源。
- 使用 addEntity(group, entity) 把展示实体交给 EntityHolderRebarBlock。
- 不要自己维护展示实体 UUID 列表，也不要只依赖实体的 persistent 标记。
- Rebar 负责托管实体的持久化；postLoad 会恢复或重建展示实体。

## 6. Rebar 组件与多型号端口

MNB 的 assembled 方块会自动生成默认 MultiblockComponent。控制器可以覆盖默认规则，而无需复制整张组件 Map。

### 6.1 一个端口接受多个 Rebar key

~~~kotlin
class ExampleController(
    block: Block,
    context: BlockCreateContext
) : MNBController(block, context) {

    override fun configureComponents(components: MonolithComponents) {
        val bp = blueprint ?: return
        components.replaceSlots(
            bp,
            "input",
            MonolithComponents.rebarAny(
                NamespacedKey("yourplugin", "basic_input"),
                NamespacedKey("yourplugin", "advanced_input")
            )
        )
    }
}
~~~

上述规则使 input_1、input_2 等输入槽位都接受 basic 或 advanced 型号。成型后读取实际型号，再决定速度、容量或效率：

~~~kotlin
val tier = when {
    slotBlocks("input").values.any { it.key == ADVANCED_INPUT } -> 2
    slotBlocks("input").isNotEmpty() -> 1
    else -> 0
}
~~~

只覆盖单坐标时：

~~~kotlin
components.replace(Vector3i(1, 0, 0), component)
~~~

MonolithComponents.fromMNB(blueprint) 是默认组件来源；replace 和 replaceSlots 是覆盖层。

### 6.2 自定义 Rebar 控制器

如果插件已经注册控制器，蓝图只需填写相同 key。控制器建议继承 MNBController：

~~~kotlin
class ExampleController(
    block: Block,
    context: BlockCreateContext
) : MNBController(block, context), GuiRebarBlock, LogisticRebarBlock {
    constructor(block: Block, pdc: PersistentDataContainer) : super(block, pdc)
}
~~~

使用 Rebar 原生注册方式注册该类。MonolithLib 发现 key 已存在后不会再次注册。

## 7. GUI、库存与物流

MonolithLib 不规定机器 GUI。控制器可以实现 Rebar 的 GuiRebarBlock、VirtualInventoryRebarBlock 和 LogisticRebarBlock。

~~~kotlin
override fun postInitialise() {
    createLogisticGroup("input", LogisticGroupType.INPUT, inputInventory)
    createLogisticGroup("output", LogisticGroupType.OUTPUT, outputInventory)
}

override fun createGui(): Gui = Gui.builder()
    .setStructure(
        "# # # # # # # # #",
        "# I i i i i i I #",
        "# # # # # # # # #"
    )
    .addIngredient('#', GuiItems.background())
    .addIngredient('I', GuiItems.input())
    .addIngredient('i', inputInventory)
    .build()
~~~

GUI 应由 Rebar 的交互事件打开，不要让 Structure Block 原版界面先处理点击。多个物理输入槽位可以共享一个 VirtualInventory，也可以分别建立库存，取决于机器设计。

## 8. 展示实体

### 8.1 保存

save scaffold/assembled 命令默认扫描选区中的展示实体并写入 MNB；需要关闭时使用 --no-displays。用 bp info <id> 确认实体数量。

### 8.2 坐标

展示实体位置等于：

~~~text
控制器世界坐标
+ entity.position 经朝向变换后的方块坐标
+ display_offset
+ entity.translation
~~~

不要给 entity.position 手动加控制器坐标。display_offset 是整体浮点偏移，translation 是单实体偏移。

### 8.3 控制器托管

自定义控制器创建的实体必须使用：

~~~kotlin
addEntity("screen", display)
~~~

这样实体会跟随 Rebar 控制器生成、保存、加载和移除。不要只调用 world.spawn 后丢弃引用。

## 9. 热重载与验证

开发阶段推荐：

~~~text
1. 修改 Works/<id>/Setting.yml
2. /monolith project reload <id>
3. Shift+右键旧机器解体
4. /monolith bp give <id>
5. 放置新的 BuildSite
6. 建造并用扳手定型
7. /monolith bp info <id>
~~~

project reload 只重新编译指定项目。修改控制器类或 Rebar 注册代码需要重启插件/服务器，而不是只重载 YAML。

建议先确认 MNB 方块数和展示实体数，再确认 controller key 和注册状态，再确认偏移和朝向，最后验证成型、GUI、物流和破坏回退。

## 10. 常见问题

### 定型提示控制器未注册

检查 key 是否为 namespace:key。外部控制器必须在蓝图加载前完成 Rebar 注册；自动控制器则提供 generated_material。

### GUI 一打开就关闭

不要让 Structure Block 原版交互先处理点击。使用 Rebar GUI 接口和 Rebar 交互事件；控制器需实现 GuiRebarBlock 并返回有效 InvUI Gui。

### 展示实体位置不对

先用 bp info 确认实体已保存，再检查 display_offset、单实体 translation 和 assembled rotation。不要手动修改世界坐标。

### 成型后没有展示实体

检查控制器是否继承 MNBController，实体是否通过 addEntity 托管，form_strategy 是否符合预期，并确认覆写回调调用了 super。

### 修改 Settings 后旧机器没有变化

这是预期行为。配置重载影响后续 Blueprint 和新工地；旧控制器已经保存了自己的成型状态，需先解体再重新定型。

### 方块朝向不一致

脚手架阶段只检查材质。成型阶段使用 assembled MNB 保存的 BlockData，并按 rotation.assembled 修正。

## 11. API 速查

~~~kotlin
MonolithAPI.getInstance().registry.register(blueprint)

val components = MonolithComponents.fromMNB(blueprint)
components.replace(position, component)
components.replaceSlots(blueprint, "input", component)
components.toMap()

MonolithComponents.rebarAny(BASIC_KEY, ADVANCED_KEY)

getMNBComponents()
slotBlocks("input")
blueprintId
facing

blueprint.getSlotPosition("input_1")
blueprint.getSlotPositions("input")
blueprint.getCustomString("tier")
blueprint.customData
~~~

## 12. 设计边界

MonolithLib 负责结构是什么、如何建造、何时成型、如何回退以及如何显示。Rebar 控制器负责机器是什么、如何交互、如何处理库存和物流以及成型后做什么。通过这两层分离，开发者可以用 Settings 快速调空间和视觉参数，也可以在 Kotlin 中覆盖端口组件和业务规则，而不必把整套机器逻辑复制进蓝图文件。

