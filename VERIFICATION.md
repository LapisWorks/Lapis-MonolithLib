# MonolithLib 功能验证清单

> 手动逐项验证，排除潜在问题。每项完成后标记 [x]，发现问题记录在"问题记录"表。

## 环境准备

- [X] 启动开发服务器，MonolithLib 无异常加载（`depend: [Rebar]` 正常）
- [X] 控制台无 ERROR/WARN 级异常（Except 预期内的 anchor 注册重复等）
- [X] `/monolith` 显示完整帮助菜单，子命令都能被 tab 补全

## A. 蓝图管理（bp）

- [X] `bp list`：列出所有已注册蓝图（尺寸 x NxMxK、方块数、[Rebar] 标记）
- [ ] 没有任何蓝图时提示导入目录格式（.mnb/.schem/.litematic/.nbt）
- [X] `bp info <ID>`：显示尺寸/阶段方块数/展示实体数/编组数/控制器key/注册状态/策略/两个偏移
- [X] `bp info <不存在的ID>`：提示未找到
- [X] `bp give <ID>`：获得工地展位物品，lore 含蓝图名
- [X] tab 补全：`bp` 二级/三级补全正常，蓝图 ID 自动补全

## B. 选区与录制（wand / save / merge）

- [X] `wand`：获得选区魔杖（Rebar 物品）
- [X] 左键设第一角、右键设第二角，消息提示正确
- [ ] 选区未完成时 `save` 被拒绝并提示
- [X] `save scaffold <id>`：保存脚手架阶段，报告方块数与展示实体数
- [X] `save assembled <id>`：保存成型阶段
- [X] `save assembled <id> --no-displays`：跳过展示实体扫描
- [X] `merge <id>`：生成 `Works/<id>/<id>.mnb` 与默认 `Setting.yml`
- [ ] 两阶段坐标集不一致时 merge 拒绝并报错
- [X] 展示实体被正确保存为结构相对坐标（`bp info` 数量对上）

## C. 项目配置与热重载（project）

- [X] `project reload <id>`：修改 Setting.yml 后重载生效，已成型旧机器不受影响
- [X] `project test <id>`：发放测试工具包（锚点+扳手+全部脚手架材料）
- [ ] Setting.yml：`controller`（type/rebar_key/generated_material/position）
- [ ] Setting.yml：`slots` 多端口（input_1/2, output_1/2）坐标解析正确
- [ ] Setting.yml：`overrides`（strict/loose/rebar + ignore_states + preview）
- [ ] Setting.yml：`scaffold_materials` 映射只影响 scaffoldShape
- [ ] Setting.yml：`rotation`（scaffold/assembled/center）旋转后形状/坐标/实体同步
- [ ] Setting.yml：`display_offset` 与 `display_entities` 覆写生效

## D. 工地系统（BuildSite）

- [X] 手持锚点物品右键方块 → 弹出预览 + "再次右键确认"提示
- [X] 二次右键同一位置 → 消耗物品、创建工地、显示 ghost
- [X] 一次右键后切换主手物品 → 预览取消
- [ ] 确认超时（30s）后再次右键重新预览而非确认
- [X] ghost 方块正确显示：材质匹配=黄、不匹配=红
- [X] ghost 仅渲染玩家周围 7 格内；移动时动态增删
- [X] 目标位置非空气时拒绝放置
- [X] `site list`：列出活跃工地
- [X] `site info`：显示附近工地进度百分比/朝向
- [X] `site cancel`：取消工地且不留残渣
- [X] 破坏锚点方块 → 掉落带蓝图 ID 的锚点物品，可重新放置
- [X] 放置正确材质方块后对应 ghost 消失（绿色=完成判定）

## E. 辅助建造模式

### EasyBuild
- [ ] `easybuild on`：开启，无工地时提示错误
- [ ] 手持正确材质右键 ghost 位置 → 自动放置并消耗物品
- [ ] Creative 模式不消耗物品
- [ ] 手持错误材质右键无反应
- [ ] `easybuild off` 关闭

### Printer
- [X] `printer on`：开启后每 4 tick 自动放置 1 个方块
- [X] 放置范围 = 玩家 3 格球体
- [X] 正确消耗背包材料
- [X] `printer off` 停止

### 远离工地自动关闭
- [ ] 开启模式后离开工地 16 格 → 5 秒倒计时提示
- [ ] 倒计时结束自动关闭模式，倒计时内返回则取消关闭

## F. 定型（Wrench Finalize）

- [X] `project test` 或 `give` 得到扳手
- [X] 建造未完成时右键锚点 → 提示缺失数量与完成率
- [ ] 蓝图无 `controller.rebar_key` → 定型被阻止
- [ ] 控制器 key 未注册 → 定型被阻止（不留下半成品）
- [X] 建造完成后右键锚点 → 定型成功：
  - scaffold 方块被替换为 assembled 方块（含旋转后 BlockData）
  - 锚点被替换为 Rebar 控制器
  - `onMultiblockFormed` 触发，控制台出现 "Structure formed"
  - 展示实体生成（BlockDisplay/ItemDisplay）

## G. 解体（Wrench Disassemble）

- [X] 普通右键成型控制器 → 提示需要 Shift+右键
- [X] Shift+右键控制器 → 解体成功：恢复为 scaffold 方块 + 工地锚点，不掉落物品
- [X] Shift+右键多方块的任意组件方块 → 也能找到控制器并解体
- [X] 解体后 ghost 重新显示，可继续建造

## H. 破坏回退（Break Revert）

- [X] 成型结构被破坏任意组件 → 取消破坏并立即回退未成型
- [X] 回退后：隐藏方块恢复、展示实体移除、锚点/脚手架状态
- [X] 破坏控制器本体 → 正确不掉落物品并返回脚手架阶段

## I. 成型策略（FormStrategy）

- [ ] `block_only`：保留真实方块，展示实体按需生成
- [ ] `full_display`：原方块隐藏为 STRUCTURE_VOID，纯展示实体渲染
- [ ] `hybrid`：仅隐藏指定 hiddenPositions
- [ ] 解体/回退后 STRUCTURE_VOID 恢复为原方块数据

## J. 朝向与旋转

- [X] 放置锚点时 4 个朝向（N/E/S/W）分别验证：
  - [X] 相对坐标 → 世界坐标变换正确（左右镜像不颠倒）
  - [X] 楼梯/半砖等 BlockData 状态随朝向旋转正确
  - [X] 展示实体位置与朝向正确
  - [ ] 槽位（slotBlocks）世界坐标与旋转后一致
- [X] 控制器 `isPartOfMultiblock` / `checkFormed` 与旋转一致（破坏组件能正确触发回退）

## K. 展示实体

- [X] BlockDisplay：方块类型、rotation、scale、translation 正确
- [X] ItemDisplay：物品、四元数旋转正确
- [X] `display_offset` 整体浮点偏移生效
- [X] `addEntity` 托管的实体跟随控制器生命周期
- [X] 成型时先清旧实体，避免重复 UUID

## L. 持久化与重载

- [X] 重启服务器后：
  - [X] 未成型工地（锚点 + ghost）恢复
  - [X] 成型结构恢复为成型状态（`postLoad` 重建实体）
  - [X] 展示实体恢复（无重复、无残留）
- [X] 卸载再加载 chunk：ghost 渲染恢复
- [X] `/monolith reload`：全部蓝图重载，工地刷新（`refreshBlueprint`）
- [X] `project reload` 后已成型旧机器保持原样，新放置工地用新配置

## M. 权限与边界

- [X] 无权限玩家使用受限命令被拒
- [X] 非玩家执行 `/monolith bp give` 等 → 提示仅玩家可用
- [X] 控制台执行命令不崩溃
- [X] 空参数、非法子命令、多参数均有合理提示不崩溃

---

## 问题记录

| # | 模块 | 问题描述 | 复现步骤 | 严重程度 | 状态 |
|---|------|----------|----------|----------|------|
|   |      |          |          |          |      |

## 完成状态

- 通过：X / 全部
- 阻塞项：...
