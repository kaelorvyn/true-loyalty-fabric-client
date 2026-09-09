# project-0015-真忠诚-Fabric客户端模组

Paper 服务端插件 `TrueLoyalty` 的客户端视觉配套模组。

- 客户端模组，仅用于 MC 1.21.11 Fabric。
- 作用：当不死图腾实体事件触发时，屏幕中央旋转动画强制显示手持的
  真·忠诚三叉戟，而不是不死图腾。
- 实际技能（附魔、死亡保护、128 把幻影三叉戟、闪电、成就）仍由服务端
  Paper 插件实现。

构建：

```powershell
.\gradlew.bat build
```

产物：`build/libs/TrueLoyalty-Client-1.0.0.jar`，放入客户端 mods 目录。
