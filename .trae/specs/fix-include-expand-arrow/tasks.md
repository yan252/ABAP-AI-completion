# Tasks
- [x] Task 1: 改 AdtNodeFactory：节点创建成功后显式反射 `setParent(parent)`；`isExpandableType` 提升为包级可见（去 private）。
  - [x] SubTask 1.1: 在 `createObjectNode` 节点创建成功（4 参或 2 参构造器）之后、`return node` 之前，反射调用 `setParent`；parent 为 null 或异常时静默跳过，不影响现有返回。
  - [x] SubTask 1.2: `isExpandableType` 方法修饰符从 private 改为包级（去掉 private），逻辑与类型清单不变。
- [x] Task 2: 重写 `RecentlyModifiedContentProvider.hasChildren`：
  - [x] SubTask 2.1: IProject / RecentlyModifiedRoot 分支维持 `getChildren(element).length > 0` 不变。
  - [x] SubTask 2.2: 新增分支——`RecentlyModifiedNodeTester.isUnderRecentlyModifiedRoot(element)` 为 true 时：反射 `getValue()` → `getType()`；类型命中可展开清单则反射 `setExpandable(true)` 并返回 true；否则反射调节点自身 `hasChildren()` 返回其结果；任何异常返回 false。
  - [x] SubTask 2.3: 其余元素返回 false。
- [x] Task 3: 版本升 1.2.14：MANIFEST.MF 第 5 行 `Bundle-Version: 1.2.13` → `1.2.14`；build.ps1 第 9 行 jar 名 `..._1.2.13.jar` → `..._1.2.14.jar`。
- [x] Task 4: 构建并验证产物：`powershell -File build.ps1` 成功；jar 内 RecentlyModifiedContentProvider.class 含 `setExpandable`/`getType`，AdtNodeFactory.class 含 `setParent`，MANIFEST.MF 版本 1.2.14。
- [x] Task 5: 部署到 `D:\Users\96000217\eclipse\dropins`（已复制 1.2.14 jar，已删除 1.2.13 jar）。
- [ ] Task 6: 用户重启 Eclipse 验证：①ZEWME00120_DYN(PROG/I) 节点出现 `>` 箭头；②点击箭头展开出下级内容（L_SUCC/LV_TITLE/PFM_CHECK_INPUT/STATUS_9001T 等）；③已有 PROG/P 对象展开行为不受影响。

# Task Dependencies
- Task 2 depends on Task 1（复用包级 isExpandableType；tester 命中依赖 setParent）
- Task 4 depends on Task 3（jar 名以新版本号为准）
- Task 5 depends on Task 4
- Task 6 depends on Task 5
