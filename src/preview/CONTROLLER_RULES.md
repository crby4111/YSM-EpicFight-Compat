# alpha.3：按规则选择模型控制器

新增 `source.kind: "controller"`，`id` 填当前模型声明的控制器名，可加 `model:` 前缀。配置格式版本仍为 1，旧的 clip / authored_state 配置继续有效。

```json
{
  "schema_version": 1,
  "enabled": true,
  "default_pose": "epic_fight",
  "overrides": [
    {
      "id": "my-model-main",
      "enabled": false,
      "models": ["example/model"],
      "source": {"kind": "controller", "id": "model:player.main"}
    }
  ],
  "extras": [
    {
      "id": "my-cape-controller",
      "enabled": false,
      "models": ["example/model"],
      "source": {"kind": "controller", "id": "model:player.cape"},
      "target": {"bones": ["Cape*"], "channels": ["rotation"]},
      "blend": {"mode": "additive", "weight": 0.7},
      "transition": {"enter_seconds": 0.1, "exit_seconds": 0.2}
    }
  ]
}
```

示例默认关闭，先将模型 ID、控制器名和骨骼名换成实际值。外部预览器增加“指定模型控制器”，可以直接从所选模型列出的控制器中选择；也可以使用“当前动作规则”检查完整配置。

## 行为

- 只运行规则选中的控制器。优先级落选、条件未命中或骨骼范围为空的控制器不会因为被加载而执行生命周期。
- 使用模型中声明的初始状态、状态切换条件、进入/退出数值语句、状态变量、片段权重及过渡曲线；沿用现有 Bedrock 控制器解析器支持的子集。
- 同一模型、实体、视图内，同一控制器被多个规则遮罩引用时共享一次推进结果。不同实体的状态独立。
- controller 的播放时钟固定为 `independent`，从首次激活开始；不接受 EF 进度时钟或 authored 时钟，避免猜测整个状态图应如何映射到单个片段的进度。
- 额外层淡入时立即启动状态时钟；淡出期间继续推进，彻底退出后释放状态。重新激活时从初始状态开始，时间线初始化也重新执行。
- 空状态或纯数值初始化不会把 EF 身体变成绑定姿态。此时保留 EF，status 的 selected_override 表示被选中的规则，base 表示实际基础动作。空状态仍占有这条规则的优先级；不自动运行较低优先级的控制器。
- 控制器片段里的数值初始化可供后续片段使用。声音、粒子与同步输出仍被抑制，不能通过 controller 来源绕过。
- 修复 `ctrl.parcool_state` 的字符串类型：无活动 ParCool 动作时返回空字符串，有已识别动作时返回短名称，避免 `== ''` 条件被数值 0 误判。外部受控预览没有活动 ParCool 动作。
- `ctrl.death`、`ctrl.attacked`、`ctrl.sleep`、`ctrl.riptide` 等原生布尔动作标志由显式集合识别；预览事实未提供的标志取 0，当前运动事实选中的标志仍按运动输入计算。未知 `ctrl.*` 名称会出现在 `unhandled`，不会因为共享 `ctrl.` 前缀而被静默当作已支持。
- `MolangScriptRuntime` 已识别的脚本常量继续沿用原值。数值函数支持范围与 `SnapshotExpressionEnvironment` 的实际分派一致；其中未实现的函数返回 0 并出现在 `unhandled`，已实现的 `math.*`（例如 `math.sin`）不会被误报。

## 尚不支持的部分

- `ysm-builtin` 已接入受支持原生槽位的提供者、脚本调度和状态过渡。脚本独立声明的槽位也会列入控制器列表。未知槽位明确报错；离线环境只能重建已提供输入，不能凭空恢复真实装备和游戏事件。
- 控制器可以引用 `parallel*` / `pre_parallel*`；这些片段与外观层、其他控制器及额外层共享 authored 时钟、时间线和采样结果。直接选择同一外观片段时也需使用 authored 时钟。
- 不支持把另一个控制器当成片段递归调用。缺失片段不会生成替代姿态。
- controller 覆盖默认 `body_orientation: movement`，可设为 `none` 或明确移动状态。渲染器先确定当帧实际身体所有权，再应用整体朝向；空控制器保留 EF 的朝向。
- 外部预览仍是受控第三人称重建，不等同于游戏最终画面、持物、第一人称或多人验收。
- 预览已提供转身速度、三轴速度，以及 `query.position`、`query.position_delta`、`query.yaw_speed` 等输入。`tlm.is_sitting`、部分持物查询等仍明确列为未重建。新建重建任务使用独立物理状态。
- 可导入 F9 报告，检查模型、骨架绑定与缩放后直接显示记录的蒙皮结果，保留已记录的隐藏骨骼。它是记录帧查看，不包含完整控制器、物理历史和摄像机回放。

本次没有修改协议、序列化缓存格式、官方 YSM 映射请求、Minecraft/Forge 目标或依赖版本，也没有向游戏实例安装文件。
