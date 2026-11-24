# 修复 Recently Modified 下 INCLUDE 对象无展开箭头 Spec

## Why
v1.2.12（仅加 PROG/I 到 isExpandableType）与 v1.2.13（对齐收藏夹 6 参构造器 + userName=""）部署后实测均无效：PROG/I 对象（如 ZEWME00120_DYN）节点左侧仍无 `>` 箭头。静态字节码链路（6 参构造器硬编码 expandable=true → node.hasChildren() → ADT provider 返回 true）完整无懈，与实测矛盾，说明运行时 expandable 实际值或 CNF 聚合环节与静态模型不符。继续沿"对齐 ADT 节点构造"方向无法收敛，改用新思路：**不再依赖 ADT 扩展回答 hasChildren，由我们自己的 navigatorContent 扩展直接对该节点回答 true**。

## What Changes
- **新思路立足点（已核实）**：plugin.xml 中 `recentlyModifiedContent` 的 triggerPoints 含 `<test property="com.sap.abap.ai.completion.recentlyModified.node"/>`，该 tester（RecentlyModifiedNodeTester）沿 getParent() 链匹配 RecentlyModifiedRoot 下的所有 ADT 子节点；CNF `NavigatorContentServiceContentProvider.hasChildren` 为 OR 语义（字节码已确认）——任一匹配扩展返回 true 即 true。**因此我们自己的 ContentProvider 对 PROG/I 节点返回 true，箭头立即出现**，无需 ADT 侧配合。
- `RecentlyModifiedContentProvider.hasChildren` 改造（单点改动）：
  - `IProject` / `RecentlyModifiedRoot`：维持现状（`getChildren().length > 0`）；
  - tester 命中的 ADT 对象节点：
    1. 反射 `node.getValue()` 取 IRepositoryObject，再反射 `getType()`；
    2. 类型属于可展开清单（PROG/P、PROG/I、CLAS/、INTF/、TABL/、STRU/、DDLS/、FUGR/F）时，反射 `setExpandable(true)` 做防御性补设（覆盖运行时 expandable 被置 false 的情形），**直接返回 true**；
    3. 其他类型：反射调节点自身 `hasChildren()` 返回真实值；
  - 其余元素：返回 false。
- `AdtNodeFactory` 配套小改：
  - 节点创建成功后显式反射 `setParent(parent)`（对齐收藏夹 `TreeContentAndLabelProvider.getObjectNodes` 的做法）——tester 依赖 getParent 链命中，这是新思路生效的必要条件；
  - `isExpandableType` 提升为包级可见（`static` 去 private），供 ContentProvider 复用同一份类型清单。
- 版本升 1.2.14：MANIFEST.MF 第 5 行 `Bundle-Version` + build.ps1 第 9 行 jar 名。
- 展开后的子节点仍由 ADT `repositoryFolders` 扩展的 getChildren 聚合提供（v1.2.13 的 userName="" 修复已就位）；本方案**不改 getChildren 逻辑、不改节点工厂构造路径、不改 plugin.xml**。

## Impact
- Affected code：
  - `src/com/sap/abap/ai/completion/recent/RecentlyModifiedContentProvider.java`（hasChildren 重写）
  - `src/com/sap/abap/ai/completion/recent/AdtNodeFactory.java`（setParent + isExpandableType 可见性）
  - `META-INF/MANIFEST.MF`、`build.ps1`（版本号 1.2.14）
- 不影响：PROG/P 现有展开功能（只是 hasChildren 回答路径多走一条，结果一致）；plugin.xml；getChildren；其他节点类型。

## ADDED Requirements
### Requirement: PROG/I 节点展开箭头由本插件扩展直接回答
系统 SHALL 在 `RecentlyModifiedContentProvider.hasChildren` 中对 tester 命中的 ADT 对象节点，按可展开类型清单直接返回 true（并防御性补设 expandable），利用 CNF OR 聚合使箭头出现。

#### Scenario: INCLUDE 对象显示展开箭头
- **WHEN** 用户修改并保存 INCLUDE 程序（PROG/I），Recently Modified 节点下出现该对象
- **THEN** 节点左侧显示 `>` 展开箭头

#### Scenario: 展开后显示下级内容
- **WHEN** 用户点击 PROG/I 节点的 `>` 箭头
- **THEN** 由 ADT repositoryFolders 扩展的 getChildren 提供下级内容（L_SUCC、LV_TITLE、PFM_CHECK_INPUT、STATUS_9001T 等），与标准 Favorite Objects 一致

## MODIFIED Requirements
### Requirement: ADT 节点 parent 链完整性
`AdtNodeFactory.createObjectNode` 创建节点成功后 SHALL 显式反射调用 `setParent(parent)`，保证 RecentlyModifiedNodeTester 沿 getParent() 链能命中 RecentlyModifiedRoot。

#### Scenario: tester 命中子节点
- **WHEN** CNF 对 Recently Modified 下的 ADT 对象节点评估 triggerPoints
- **THEN** tester 沿 parent 链找到 RecentlyModifiedRoot，返回 true，本插件扩展参与 hasChildren 聚合

#### Scenario: 不影响可执行程序
- **WHEN** Recently Modified 下已有 PROG/P 可执行程序对象
- **THEN** 其箭头与下级展开行为保持现状不变

## 回退
改动集中在两个文件、逻辑可加性（只在原返回 false 的路径上新增 true 分支）；若出现异常，hasChildren 的 catch 返回 false，退化为现状（无箭头），不会产生更差行为。
