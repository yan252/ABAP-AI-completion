# Changelog

本文件记录了 ABAP AI Completion 插件的主要修改记录。

所有值得注意的变更都会按时间倒序记录在该文档中。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.0.0/)，版本号遵循 [Semantic Versioning](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### Added
- **内联（类 Copilot）补全显示模式**：新增 `AICompletionInlineOverlay` 与公共抽象 `AICompletionOverlayBase`，可在配置页「Overlay Style → Completion display type」中选择补全代码的显示方式：
  - **1 - Dialog display（默认）**：以浮动弹窗形式展示（原有行为），新增鼠标/键盘交互完善（点击弹窗空白处接受、点击外部取消、Esc 取消、滚动条点击不误触）。
  - **2 - Inline display（内联幽灵文本）**：直接在编辑器光标处绘制与真实代码同字体同字号的幽灵文本（不真正插入文档），可用配置的补全字体颜色渲染；按 `Tab`/`Enter` 或点击提示文本处接受并插入，按其它任意键或点击编辑器外部取消。`AIOverlayManager` 统一通过 `AICompletionOverlayBase` 管理两种覆盖层的显示、定位与关闭，并修正了鼠标监听器清理逻辑。

### Changed
- **补全触发门控优化**：`AICompletionHandler` 中调整判断逻辑 —— 只要光标所在行内、光标之后存在非空白字符（行中间或行首）即直接退出补全，且不显示任何提示（包括状态栏提示）；只有光标位于行尾或空行时才触发 AI 代码补全。
- **节点2/3 压缩截取日志标记**：`PromptCacheManager.compressContent` 在压缩结果超过配置设定的"工作区/上下文最大字符数"而触发截取时，诊断日志前缀打上 `[TRUNCATED]` 标记，并在日志中记录 `maxInputChars`（当前截取上限配置值），便于确认是否发生超长截取。

### Fixed
- **节点2/3 变量定义块压缩修复**：压缩 `DATA`/`TYPES`/`TABLES` 等多行变量定义时，按 ABAP 语句结束符 `.` 作为定义结束（`.` 后可能跟 `"` 行尾注释），整体保留从声明行起的全部字段行，不再把 `TYPES:`/`DATA:` 多行字段列表拆散、仅保留关键字行。同步修复：`AbapCodeTruncator` 引入声明语句块识别并补齐 `END OF` 结构行；`PromptCacheManager.containsSignEnd` 剥离 `"` 注释后再判断 `.`，避免注释中点号误判。
- **补全前缀去重优化**：在 `dedupePrefixWithCodeBefore` 中，当剥掉与光标行重复的前缀后，补充 `.trim()`，去掉 AI 在重复前缀与后续内容（如注释引号后）之间多余的空白，使补全结果更干净（例如只输出 `客户名称` 而非 ` LV_KUNNR = '1111' ."客户名称`）（未测试通过）。
- **配置页中增加显示插件版本号（未测试通过）

### Docs
- 新增英文 `README.md` 与中文 `README_CN.md` 的对照同步：补齐介绍段中"本地 LLM / 企业内部使用 / 代码保密"的表述，并在"比 Copilot 的优势"中增加"本地 LLM 支持"与"完全开源、企业免费"两个条目。

---

## [1.0.6] - 2026-09-03

### Fixed
- **API Key 保存无效修复**：`AICompletionPreferencePage.saveValues()` 之前未调用 `store.setValue(PreferenceConstants.API_KEY, ...)`，导致 API Key 从未写入偏好存储，重新打开配置页后为空。已补上保存逻辑（在测试连接时正常、但保存后丢失的问题）。
- **配置页过宽问题**：将「System Prompt」输入框的 `widthHint` 由 `500` 收窄至 `420`（约 5 个汉字宽度），避免在部分电脑上出现横向滚动条。

### Changed
- 插件版本号由 `1.0.5` 提升至 `1.0.6`（插件 JAR + p2 更新站点）。

---

## [1.0.5] - 2026-08-28

### Changed
- **菜单项顺序调整**：状态栏图标菜单与 ABAP 编辑器右键菜单中的启用项顺序由「AI Reference Workspace Code → Enable Skill Reference → Enable Parent Program Lookup」调整为「Enable Skill Reference → Enable Parent Program Lookup → AI Reference Workspace Code」。
- 插件版本号由 `1.0.4` 提升至 `1.0.5`（插件 JAR + p2 更新站点）。

---

## [1.0.4] - 2026-08-25

### Changed
- **补全显示前处理逻辑重构**：删除旧的 `dedupePrefixWithCodeBefore` / `dedupeWithCodeAfter` 处理逻辑，在 `cleanupCompletion` 中改为多情况分步处理（Case 1 去除前重复行、Case 2 当前行部分提示、Case 3 其它预留）。各子处理逻辑的方法注释中写明了处理说明。
- 插件版本号由 `1.0.3` 提升至 `1.0.4`（插件 JAR）。

---

## [1.0.3] - 2026-08-24

### Fixed
- **补全内容去重**：新增 `cleanupCompletion` / `dedupePrefixWithCodeBefore` / `dedupeWithCodeAfter` 逻辑。当 AI 返回内容与光标前已有代码存在字符级前缀重复（如 `B~` → `B~MATID,` 重复成 `B~~MATID,`），或与光标后已有代码存在整行重复时，自动剔除，避免重复插入。

### Changed
- 插件构建并发布 `1.0.3` 版本（插件 JAR + p2 更新站点）。

### Docs
- 同步英文 README 与中文 README 的"发送给 AI 的 MESSAGE 节点说明"（1 个 system 节点 + 6 个 user 节点）。

---

## [1.0.2] - 2026

### Added
- 新增 AI 预览图标（`AI4.png` 等），清理旧的 dist 归档。
- 插件右键菜单子菜单加入 SAP logo 图标（`AICompletionMenuBuilder` / `AICompletionEditorMenuContribution`）。

### Fixed
- 修正 p2 artifact 映射问题，使更新站点可正常下载（`id_version.jar` 映射）。

### Changed
- 带图标资源重建插件 JAR 与 p2 更新站点 `1.0.2`。

### Docs
- 新增中文 README `README_CN.md`。
- 在 README 安装说明中补充自定义 SKILL 配置目录（`ABAP_SKILLS`）的说明。
- 在 README Copilot 对比中澄清 SKILL 的用途（用于代码补全参考，而非仅用于对话）。

---

## [1.0.1] - 2026

### Added
- 引入三节点 `PromptCacheManager`（Prompt 压缩与多节点缓存管理）。
- 仅对以 `Z`/`Y` 前缀的程序解析父级调用程序（提升性能）。

### Changed
- 默认工作区参考文件数量上限由 20 调整为 5。
- 更新站点发布 `1.0.1`。

---

## [1.0.0] - 2026

### Added
- 首个正式发布版本。
- 精简 parser / logger / preference 代码，新增编辑器右键菜单 UI。
- 浮动覆盖层支持完整代码与滚动条显示、不透明设置、SKILL 在系统提示中优先。
- 简化状态栏与偏好设置页面。

---

[Unreleased]: https://github.com/yan252/ABAP-AI-completion/compare/v1.0.6...HEAD
[1.0.6]: https://github.com/yan252/ABAP-AI-completion/releases/tag/v1.0.6
[1.0.5]: https://github.com/yan252/ABAP-AI-completion/releases/tag/v1.0.5
[1.0.4]: https://github.com/yan252/ABAP-AI-completion/releases/tag/v1.0.4
[1.0.3]: https://github.com/yan252/ABAP-AI-completion/releases/tag/v1.0.3
[1.0.2]: https://github.com/yan252/ABAP-AI-completion/releases/tag/v1.0.2
[1.0.1]: https://github.com/yan252/ABAP-AI-completion/releases/tag/v1.0.1
[1.0.0]: https://github.com/yan252/ABAP-AI-completion/releases/tag/v1.0.0
