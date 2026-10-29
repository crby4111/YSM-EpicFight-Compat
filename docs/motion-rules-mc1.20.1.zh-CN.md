# Minecraft 1.20.1 动作规则使用说明

适用分支为 `mc/1.20.1`，当前为 `1.1.0-alpha.4` 开发候选。
本文说明配置方法，不代表正式验收已经通过；结果以同一候选校验值对应的
[验收记录](acceptance-mc1.20.1.zh-CN.md)及随包验证记录为准。

## 安装与默认行为

当前验收固定组合为 Minecraft 1.20.1、Forge 47.4.10、Epic Fight
20.14.17、官方 Yes Steve Model 2.6.5、YSM-Mapping-API 0.1.8，游戏使用 Java 17。
其它版本组合尚未由本轮验收证明。各依赖单独安装，本模组不内置它们。

1. 退出游戏，保留当前模组、配置和存档备份。
2. 将候选兼容模组 JAR 放入实例的 `mods`，同一个实例只保留一份该模组。
   安装上述依赖；需要本模组提供模型同步的服务器也使用相同候选与对应依赖。
3. 模型仍由官方 YSM 安装和选择。普通使用只需安装与配置，无需修改模型或 Java 源码。
4. 进入游戏并切到 EF 接管渲染的模式。没有规则文件时，身体默认执行 EF 动作；
   普通模式仍由官方 YSM 渲染。

动作规则文件位于**当前游戏实例**的：

```text
config/ysm_epicfight_compat/motion_rules.json
```

这是客户端本地规则。修改后执行 `/ysmcompat rules reload`，再用
`/ysmcompat rules status` 检查实际命中与错误。修改其它实例的同名文件不会影响本实例。

## 找到模型、动作与控制器名称

- 先选好 YSM 模型，进入 EF 接管状态，让角色实际渲染一帧，再查看 `rules status`。
  `model=` 后面是规则要填写的模型 ID，`ef_animations=` 是当前观察到的 EF 动作 ID。
  若显示尚未计算转换模型帧，先检查模型是否已选择、EF 是否已接管。
- 外部预览器选择相同的 `yes_steve_model` 根目录和模型，模型列表、片段列表与
  “指定模型控制器”列表来自实际可读取的模型。`controller` 的 `id` 填声明的控制器名；
  `clip` 的 `id` 可填 `model:` 加片段名。不要把控制器名当成片段名。
- 额外层中的骨骼名必须来自该模型，例如 `Cape`；`*Hair*` 是名称匹配模式。
  不是所有模型都有同名骨骼。`bone mask matched no bones` 表示需要核对骨骼范围。

下面的 `example/model`、`Cape` 和控制器名是占位示例。
涉及模型的示例默认关闭，替换名称并确认来源存在后，再将对应条目的 `enabled` 改为 `true`。

## 三类配置

### 1. 默认 EF

整份文件可直接使用。没有覆盖或额外动作时由 EF 执行身体动作。

```json
{
  "schema_version": 1,
  "enabled": true,
  "default_pose": "epic_fight",
  "overrides": [],
  "extras": []
}
```

### 2. 自选覆盖：游泳时使用模型动作

这是整份配置。只在指定模型游泳时覆盖身体，退出条件后回到 EF。
`include_associated_layers` 选择伴随层，`head_tracking` 选择原生头部跟随。
规则命中后不会因为攻击或受伤而被额外的隐藏条件撤销。

```json
{
  "schema_version": 1,
  "enabled": true,
  "default_pose": "epic_fight",
  "overrides": [
    {
      "id": "my-swim",
      "enabled": false,
      "models": ["example/model"],
      "priority": 100,
      "when": {"movement": "swim"},
      "source": {
        "kind": "authored_state",
        "state": "swim",
        "include_associated_layers": true,
        "head_tracking": "native"
      },
      "transition": {"enter_seconds": 0.15, "exit_seconds": 0.15}
    }
  ],
  "extras": []
}
```

`overrides` 中同时匹配的有效来源按优先级选择一个。
`selected_override` 表示选中的规则，`base` 表示实际基础动作；
例如一个存在但当前不输出姿态的控制器可以被选中，而身体仍保持 EF。
缺失来源和冲突会写入 `issues` / `shadowed`，不能只看规则是否加载成功。

若需要选择模型声明的控制器，可将某条覆盖改成下面的条目：

```json
{
  "id": "my-controller",
  "enabled": false,
  "models": ["example/model"],
  "source": {"kind": "controller", "id": "model:player.main"},
  "playback": {"clock": "independent"}
}
```

这只是 `overrides` 数组内的一个条目。控制器目前支持的状态图子集和限制见
[控制器说明](../src/preview/CONTROLLER_RULES.md)。
控制器覆盖默认使用 `"body_orientation":"movement"`，按当前移动状态衔接
游泳、爬行、飞行和梯子整体朝向。只有控制器实际输出身体姿态时才取得朝向所有权；
空状态保留 EF。可设为 `"none"` 禁用，或填写 `"swim"` 等明确的移动状态。
普通 clip 来源默认不改变整体朝向；`authored_state` 默认使用所选状态。
`head_tracking` 可选 `native` / `none`；原生头部后处理遵守各移动状态，
游泳、水中停留不额外追加视线旋转，陆地动作避免重复追加已由片段表达的轴。

需要组合完整的受支持原生显示层时，可使用下面的控制器来源（控制器名换成模型实际提供的名称）：

```json
{
  "kind": "controller",
  "id": "model:player.main",
  "include_associated_layers": true,
  "head_tracking": "native",
  "body_orientation": "movement"
}
```

开启关联层后，会按控制器顺序组合其余已声明控制器、实际实体的装备/持物/使用提供者及
前后外观层。显式选中的控制器当前为空时仍保留 EF，不由其他关联层擅自接管身体。

### 3. 额外动作：与 EF 或覆盖动作一起执行

将下面的条目放入上面任一配置的 `extras` 数组。
用于默认 EF 配置时，它与 EF 并行；用于游泳覆盖配置时，它也会与覆盖动作并行。
例子引用模组内置的原创披风片段，仅修改 `Cape` 的局部旋转。

```json
{
  "id": "my-cape",
  "enabled": false,
  "models": ["example/model"],
  "source": {"kind": "clip", "id": "ysm_epicfight_compat:example_cape_swing"},
  "target": {"bones": ["Cape"], "channels": ["rotation"]},
  "blend": {"mode": "additive", "reference": "neutral", "weight": 0.7},
  "playback": {"clock": "independent"},
  "transition": {"enter_seconds": 0.1, "exit_seconds": 0.2}
}
```

`additive` 在现有局部通道上叠加，`replace_local_channels` 替换所选骨骼的所选通道。
完整身体覆盖使用权重 1，淡入淡出通过 `transition` 设置。
并行额外层可用 `order` 安排顺序。若覆盖已经包含某伴随来源，重复额外引用会报出原因。

时钟选择如下：普通片段可使用独立时钟；原生 `parallel*` / `pre_parallel*`
片段必须用 `authored` 共享时钟；控制器只支持 `independent`。
与某个 EF 动作进度同步时，`playback` 使用 `clock: "ef"` 并填写真实的
`ef_animation` ID，条件中也可用同一个 ID 限定生效范围。

## 配置自己的额外动作

使用标准 1.20.1 资源包，将 Bedrock 动画 JSON 放到：

```text
assets/你的命名空间/motion_animations/任意文件名.json
```

JSON 中 `animations` 的键决定片段 ID。假设命名空间为 `my_motion`、
键为 `cape_wave`，规则的来源就是 `my_motion:cape_wave`，与文件名无关。
资源包的 `pack.mcmeta` 使用该版本的 `pack_format: 15`。
在游戏中启用资源包并完成资源重载后，再执行 `rules reload`。
不同文件不要在同一命名空间下声明相同片段键，否则本次加载会被拒绝。

外部预览器可以先载入动作 JSON 检查，但只在预览窗口中生效。
预览导出规则不会自动安装资源包，也不会写回游戏配置。

## 检查与回退

- 成功重载后检查 `mode=true`、`reload error`、`base`、`extras` 和 `issues`。
  条件未命中的规则不出现在活动额外层列表中。
- 规则语法或资源加载失败时保留上一份有效规则及动作资源；修正错误后再次重载。
  刚编辑的文件未必已经生效，应以状态信息为准。
- 将配置改为 `{"schema_version":1,"enabled":false}` 后重载，可恢复旧兼容路径。
  移走规则文件再重载，会恢复默认 EF 规则。
- 恢复自己备份的旧配置后重载即可回退规则。卸载兼容模组前退出游戏，
  移走本模组 JAR；保留 YSM、原模型、配置备份和存档。

## 当前边界

规则模式自动保留所支持外观层的缩放与显隐；旋转、位移按 EF、显式覆盖和额外层的选择合成。
自选控制器已接入受支持原生槽位的 `ysm-builtin` 提供者和脚本调度，
包含按当前实体选择的主动作、持物及装备来源。脚本独立声明的受支持槽位也可选择。
控制器状态与脚本切换使用已显示姿态快照过渡，不重新执行退出的旧动作。
`parallel` / `pre_parallel` 和模型声明的外观片段共用 authored 时钟、时间线与采样结果；
直接引用这些片段需设置 `"playback":{"clock":"authored"}`。
未知原生槽位仍会明确报错。声音、粒子和同步输出不会因选择模型控制器而自动重放。

外部预览是受控第三人称重建；未实现的查询和效果会列出名称。
可设置转身速度与三轴速度，驱动位置变化等输入。也可导入 F9 的 `issue.json`，
直接查看记录的最终模型蒙皮帧；导入不会推进动画或物理，也不等于重演完整游戏过程。
武器本体、第一人称最终画面、光影、游戏事件与多人表现仍需游戏内验证。
同一模型原来存在的穿模和兼容层新增的问题，应结合官方 YSM 对照记录。
实机持物、第一人称、多人、长时间运行和性能门槛未完成前，候选仍按开发测试版使用。

后续功能、修复顺序与交付方式见[开发计划](development-plan-mc1.20.1.zh-CN.md)。
