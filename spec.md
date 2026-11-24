# Spec: 修复 Recently Modified 节点下 INCLUDE(PROG/I) 对象无展开箭头（v1.2.14）

## 问题
`Recently Modified` 节点下的 INCLUDE 对象（如 ZEWME00120_DYN，类型 PROG/I）左侧没有 `>` 展开箭头，无法展开下级内容；同一对象在 SAP 标准 `Favorite Objects` 下可正常展开（L_SUCC、LV_TITLE、PFM_CHECK_INPUT、STATUS_9001T 等）。不得影响可执行程序（PROG/P）现有可展开功能。

## 已确认事实（字节码级，ADT 3.62.0）
1. CNF `NavigatorContentServiceContentProvider.hasChildren` 为 OR 语义：所有 triggerPoints 匹配的扩展逐一调用，任一 true 即 true；我们扩展返回 false 不会否决 ADT 的 true。
2. ADT 扩展 `com.sap.adt.semanticfs.repositoryFolders` 的 triggerPoints 仅 `instanceof AbapRepositoryAsyncListLoaderBaseNode`——我们的节点必然匹配。
3. `AbapRepositoryPackageTreeContentProvider.hasChildren` 字节码确认：`element instanceof TreeNode → ((TreeNode)element).hasChildren()`（invokevirtual，虚分派）。
4. `AbapRepositoryObjectGenericNode.hasChildren()` 字节码确认 = `((IRepositoryObject)getValue()).hasChildren()`。
5. `RepositoryObjectListItem.hasChildren()` = 直接返回 `expandable` 字段；`setExpandable(boolean)` 存在；6 参构造器委托 7 参，硬编码 `expandable=true`。
6. **关键差异**：收藏夹 `TreeContentAndLabelProvider.getObjectNodes` 创建对象节点用的是 **`AbapRepositoryObjectGenericLaunchableNode`**（继承 GenericNode，多实现 `ILaunchableNode`，未覆写 hasChildren），构造后**显式 `node.setParent(folderNode)`**，且 IRepositoryObject 来自后端 `VirtualFolder.getExpandedObjects()`。我们用的是 `AbapRepositoryObjectGenericNode`，未显式 setParent。
7. 静态链路完整（expandable=true → 箭头必有）与实测（无箭头）矛盾 → 运行时某环节与静态模型不符，**必须拿运行时数据**。

## 方案（v1.2.14，双管齐下）
### A. 对齐收藏夹实例化路径
- 节点类优先改用收藏夹实际使用的 `AbapRepositoryObjectGenericLaunchableNode`（4 参构造器 TreeViewer/IProject/TreeNode/IRepositoryObject），失败回退现有 `AbapRepositoryObjectGenericNode`（4 参 → 2 参）。
- 节点创建成功后显式反射调用 `setParent(parent)`（收藏夹的做法）。

### B. 运行时诊断日志（前缀 `[RecentlyModified] DIAG`，写入 `.metadata/.log`）
节点创建后、返回前反射输出：
- `node.getClass().getName()`（实际节点类）
- `repoObj.getClass().getName()`（实际 IRepositoryObject 实现类）
- `expandable` 字段实际值
- `repoObj.hasChildren()` 调用结果
- `node.hasChildren()` 调用结果（或异常）
- `node.getValue() == repoObj` 是否成立
- viewer 是否为 null / viewer 类名
另：把现有 `setExpandable` 的 `catch (Throwable ignored)` 改为记录异常（定位静默失败）。

## 修改文件（精确）
| 文件 | 修改 |
|---|---|
| `META-INF/MANIFEST.MF` 第 5 行 | `Bundle-Version: 1.2.13` → `1.2.14` |
| `build.ps1` 第 9 行 | jar 名 `..._1.2.13.jar` → `..._1.2.14.jar` |
| `src/com/sap/abap/ai/completion/recent/AdtNodeFactory.java` | ①新增常量 `CLS_ABAP_LAUNCHABLE_NODE`；②节点创建：先试 Launchable 4 参构造器，回退 Generic；③创建成功后 `setParent(parent)`；④`setExpandable` catch 记录异常；⑤`return node` 前加 DIAG 诊断日志块 |

不改动：`RecentlyModifiedContentProvider`（getChildren 结构不变）、plugin.xml、PROG/P 相关路径逻辑。

## 验证步骤
1. `powershell -File build.ps1` 构建成功，产物 `dist/com.sap.abap.ai.completion_1.2.14.jar`。
2. 从 jar 提取 `AdtNodeFactory.class`，确认含字符串 `AbapRepositoryObjectGenericLaunchableNode`、`DIAG`。
3. 复制 jar 到 `D:\Users\96000217\eclipse\dropins`（`[System.IO.File]::Copy`），删除旧版 jar。
4. 用户重启 Eclipse，展开 `Recently Modified`：
   - 观察 ZEWME00120_DYN(PROG/I) 是否出现 `>` 箭头、能否展开下级（L_SUCC/LV_TITLE/PFM_CHECK_INPUT/STATUS_9001T 等）；
   - 观察 PROG/P 对象原有展开功能不受影响。
5. 收集 `{workspace}/.metadata/.log` 中 `[RecentlyModified] DIAG` 行反馈，定位断裂环节（若箭头仍未出现，诊断数据将指明是节点本身还是 CNF 聚合层问题，作为下一版修复依据）。

## 回退
诊断日志不影响功能；若 Launchable 节点引发异常，回退逻辑自动落到 GenericNode（现状行为）。
