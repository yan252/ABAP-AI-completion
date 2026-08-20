# ABAP AI Completion - Eclipse 插件

> 🌐 **语言 / Language**: [English](README.md) | [中文](README_CN.md)

---

## 中文

为 SAP ABAP 开发者打造的基于 AI 的代码补全插件，运行于 Eclipse。基于大语言模型（LLM），支持本地 LLM，在编写 ABAP 代码时提供智能代码建议与自动补全。

### 相对于 Copilot 的优势
- **🤖 完整工作区上下文**：除当前编辑器的 ABAP 代码外，你还可以选择传入工作区中当前打开的所有 ABAP 代码。Copilot 仅支持当前编辑器的代码，不提供此功能。
- **⌨️ 相关代码引用深度搜索**：搜索相关程序并将其作为补全的代码引用发送给 AI。注意搜索层级越深会影响性能；建议最多一级。
- **🎯 本地 SKILL 作为代码引用**：在 SKILL 文件中定义自定义函数或模板，并将其作为引用传入以获得更合理的 AI 补全。Copilot 也可配置 SKILL，但仅用于对话，不用于 AI 引用代码补全。

### 功能特性
- **🤖 AI 智能补全**：基于 AI 模型在光标位置提供上下文感知的 ABAP 代码建议
- **⌨️ 手动触发**：按 `Ctrl+Shift+.` 手动调用 AI 代码补全
- **⚡ 自动补全**：可选自动补全模式，停止输入后自动触发 AI 建议
- **📋 INCLUDE 解析**：自动解析 ABAP INCLUDE 程序以获得更准确的上下文补全
- **🎯 技能目录**：支持 `.abap`、`.txt`、`.skill` 文件作为 AI 引用模式
- **🌊 浮动覆盖层**：代码建议以浮动覆盖层形式显示（Copilot 风格）
- **🎨 自定义样式**：自定义补全代码的显示颜色
- **🔌 灵活配置**：支持任何与 OpenAI Chat Completions API 兼容的服务端点
- ** 手动补全（`Ctrl+Shift+.`）使用 SKILL**：自动补全（`requestQuickCompletion`）使用简化的独立提示词，不加载 SKILL 目录内容。
- ** 补全模式配置**：在设置中可选择手动补全是显示为覆盖层还是直接插入；两者均使用 SKILL 内容。

---

### 安装（最终用户）

#### 系统要求

- **Eclipse**：4.7+（Neon）或更高版本
- **Java**：JDK 17 或更高版本
- **网络**：可访问所配置的 AI API 服务

#### 安装步骤

1. **下载插件 JAR**

   从仓库的 `dist` 目录下载最新的 JAR 文件：
   ```
   dist/com.sap.abap.ai.completion_1.0.0.jar
   ```

2. **安装到 Eclipse**

   将 JAR 文件复制到 Eclipse 的 `dropins` 目录（若不存在则创建）：
   ```
   <Your-Eclipse-Install-Path>/dropins/
   ```

   例如：
   ```
   C:\Users\YourName\eclipse\dropins\com.sap.abap.ai.completion_1.0.0.jar
   ```

3. **重启 Eclipse**

   复制完成后，**重启 Eclipse** 以加载插件。

---

### 使用方法

#### 手动代码补全

1. 打开任意 ABAP 源文件（`.abap`、`.prog` 等）
2. 将光标放置到需要补全的位置
3. 按 `Ctrl + Shift + .` 调用 AI 补全
4. AI 建议以浮动覆盖层形式显示
5. 按 `Tab` 接受建议，或按其他按键取消

#### 自动补全模式

在配置中启用 **"输入时自动补全"** 后，停止输入一段时间（默认 2000ms）将自动触发 AI 建议。

#### 更改快捷键

更改快捷键：
1. 菜单：`Window` → `Preferences`
2. 定位到：`General` → `Keys`
3. 搜索：`ABAP AI completion`
4. 点击 `Binding` 字段并按新的按键组合
5. 点击 `OK` 保存

---

### 配置

#### 打开配置页面

1. 菜单：`Window` → `Preferences`
2. 在左侧导航树中找到 **`ABAP AI Completion`**
3. 配置页面如下所示：

![配置页面](配置截图.png)

#### 配置项

##### AI 连接设置

| 设置项 | 说明 | 示例 |
|---------|-------------|---------|
| **API 基础 URL** | AI 服务端点 URL | `https://api.openai.com/v1` |
| **模型名称** | 使用的 AI 模型名称 | `gpt-4`、`deepseek-v4-flash` |
| **API 密钥** | API 认证密钥 | (以星号显示) |
| **最大 Token 数** | 生成的最大 token 数量 | `256` |
| **温度** | 创作随机度（0.0-2.0） | `0.3`（越低输出越确定） |

> 💡 **提示**：点击 `Test Connection` 按钮可验证 API 连接。

##### 功能设置

| 设置项 | 说明 |
|---------|-------------|
| **启用 ABAP AI Completion 插件** | 启用/禁用整个插件 |
| **输入时自动补全** | 启用后，停止输入后自动触发 AI 建议 |

##### 自动补全设置

| 设置项 | 说明 |
|---------|-------------|
| **输入停止后延迟（ms）** | 停止输入后多少毫秒触发自动补全 |

> 💡 **建议**：推荐值为 1500-3000 ms。值越小 API 请求频率越高。

##### 技能目录

将 `.abap`、`.txt` 或 `.skill` 文件放入技能目录，AI 将引用这些文件的代码模式进行补全。这对项目特定的编码规范和命名约定非常有用。

##### 自定义系统提示词

自定义发送给 AI 的系统提示词，以引导其遵循特定的代码风格和标准。

##### 覆盖层样式

自定义编辑器中 AI 补全代码的显示颜色。

---

### 开发者指南（自定义开发）

#### 环境要求

- **JDK**：17+
- **Eclipse**：带插件开发环境（PDE）的 Eclipse
- **构建工具**：Apache Maven（可选）

#### 导入项目

1. 在本地克隆仓库：
   ```bash
   git clone https://github.com/yan252/ABAP-AI-completion.git
   ```

2. 将项目导入 Eclipse：
   - `File` → `Import` → `Existing Projects into Workspace`
   - 选择克隆的项目目录
   - 勾选项目并点击 `Finish`

#### 项目结构

```
com.sap.abap.ai.completion/
├── META-INF/
│   └── MANIFEST.MF              # OSGi bundle 清单
├── src/
│   └── com/sap/abap/ai/completion/
│       ├── client/              # AI API 客户端
│       │   ├── AIClient.java
│       │   ├── AIClientException.java
│       │   └── PromptCacheManager.java    # 提示词压缩与多节点缓存管理
│       ├── editor/              # 编辑器集成
│       │   ├── AICompletionHandler.java
│       │   ├── AICompletionListener.java
│       │   ├── AICompletionOverlay.java
│       │   ├── AICompletionProposalPopup.java
│       │   ├── AICompletionService.java
│       │   └── AIOverlayManager.java
│       ├── logging/             # 日志
│       │   └── AILogger.java
│       ├── parser/              # ABAP 解析器
│       │   ├── AbapCodeTruncator.java
│       │   ├── AbapIncludeResolver.java
│       │   ├── AbapLanguageDetector.java
│       │   ├── ParentProgramContext.java
│       │   ├── ParentProgramResolver.java
│       │   └── WorkspaceCodeCollector.java
│       ├── preferences/         # 配置管理
│       │   ├── AICompletionPreferencePage.java
│       │   ├── AIConfiguration.java
│       │   ├── PreferenceConstants.java
│       │   └── PreferenceInitializer.java
│       ├── ui/                  # UI 集成（如状态栏）
│       │   └── AbapAIStatusLineContribution.java
│       └── Activator.java       # Bundle 激活器
├── icons/
│   └── SAPLogo.ico              # 插件图标
├── dist/                        # 发布 JAR
│   └── com.sap.abap.ai.completion_1.0.1.jar
├── update-site/                 # 更新站点
│   ├── features/
│   ├── plugins/
│   ├── artifacts.jar / artifacts.xml
│   ├── content.jar / content.xml
│   └── site.xml
├── tools/
│   └── CacheVerifyTool.java     # 缓存验证工具
├── plugin.xml                   # 插件扩展点声明
├── build.properties             # 构建属性
├── build.ps1                    # Windows 构建脚本
├── build_plugin.xml             # 构建脚本（ANT）
├── generate-update-site.ps1     # 更新站点生成脚本
└── rebuild.ps1                  # 重建脚本
```

#### 关键扩展点

本插件通过以下 Eclipse 扩展点进行集成：

| 扩展点 | 用途 |
|-----------------|---------|
| `org.eclipse.ui.startup` | 插件自动启动 |
| `org.eclipse.ui.preferencePages` | 配置页面注册 |
| `org.eclipse.core.runtime.preferences` | 偏好设置初始化 |
| `org.eclipse.ui.commands` | 补全命令定义 |
| `org.eclipse.ui.bindings` | 快捷键绑定 |

#### 核心架构

1. **AIClient**：纯 Java HTTP 客户端，调用 AI Chat Completions API，无需外部 JSON 库
2. **AICompletionService**：异步补全服务，处理超时、取消与错误
3. **AbapIncludeResolver**：解析 ABAP INCLUDE 程序，为 AI 提供完整上下文
4. **AIOverlayManager**：管理浮动覆盖层的显示与交互
5. **AIConfiguration**：统一配置管理，带偏好设置存储

#### 发送给 AI 的 MESSAGE 节点

手动补全（`Ctrl+Shift+.`）触发时，插件构造 **1 个 `system` 节点 + 5 个 `user` 节点** 的消息列表发送给 AI（对应 [AICompletionService.java](src/com/sap/abap/ai/completion/editor/AICompletionService.java) 中的 `buildUserMessages` 方法）。每个节点的含义如下：

| 节点 | 角色 | 内容 |
|------|------|---------|
| **System** | `system` | 系统提示词。定义 AI 的角色（资深 SAP ABAP 开发专家）、补全规则和输出约束。可使用 `自定义系统提示词` 覆盖 |
| **节点 1/5** | `user` | **SKILL 文件内容**：从技能目录加载的 `.abap`/`.txt`/`.skill` 文件，作为代码风格和最佳实践引用。若无 SKILL，则声明"使用系统默认 ABAP 编码标准" |
| **节点 2/5** | `user` | **父程序调用上下文**：当当前文件为 INCLUDE 时，深度搜索找到调用它的父程序代码（按配置的搜索深度递归搜索）。通过 `PromptCacheManager.compressAbapContext` 压缩 |
| **节点 3/5** | `user` | **工作区中打开的程序**：当前 Eclipse 工作区中打开的其他 ABAP 文件，作为风格参考和补充上下文（由 `WorkspaceCodeCollector` 收集，同样压缩） |
| **节点 4/5** | `user` | **程序元数据**：文件名、代码类型、INCLUDE 数量、父程序/工作区/SKILL 加载状态摘要，帮助 AI 理解整体上下文 |
| **节点 5/5** | `user` | **当前程序与光标位置**：当前光标程序的完整代码（通过 `AbapIncludeResolver` 展开所有 INCLUDE），标注文件名和代码类型；插入位置用 `[[[CURSOR_HERE]]]` 标记，即 AI 实际生成补全内容的位置 |

> **注意**：节点 2 和 3 在发送前根据 `Max Context Chars（getMaxContextChars）` 阈值统一压缩，以避免上下文窗口溢出；其他节点按其自身规则原样发送。若某节点无匹配内容，仍会发送占位消息（说明该节点的当前状态），以确保 AI 始终收到完整的 1+5 节点结构。

> **对比**：自动补全（`requestQuickCompletion`）不加载 SKILL 或上述上下文，使用简化的独立提示词，仅发送 1 个 `system` 节点 + 1 个 `user` 节点（当前行上下文）。

#### 构建插件

##### 使用 Eclipse 导出

1. 右键点击项目 → `Export`
2. 选择 `Deployable plug-ins and fragments`
3. 输出目录选择为 `dist/`
4. 点击 `Finish`

##### 使用 ANT 脚本

```bash
# Windows PowerShell
.\build.ps1
```

或使用提供的 `build_plugin.xml`：
```bash
ant -f build_plugin.xml
```

#### 调试插件

在 Eclipse 中启动一个新的 Eclipse 实例进行调试：

1. `Run` → `Run Configurations`
2. 新建一个 `Eclipse Application` 配置
3. 在 `Plug-ins` 选项卡中，确保包含 `com.sap.abap.ai.completion`
4. 点击 `Run` 启动

---

### 常见问题

#### 问：安装插件后没有出现配置选项？

**答：** 请确认：
1. JAR 文件是否正确放置在 `dropins` 目录
2. Eclipse 是否已重启
3. Eclipse 版本是否满足要求（4.7+）
4. Java 版本是否为 17 或更高

#### 问：代码补全请求超时怎么办？

**答：**
- 检查网络连接
- 确认 API 基础 URL 和 API 密钥配置正确
- 可在配置中增大 `最大 Token 数` 和 `温度` 值
- 尝试点击 `Test Connection` 按钮验证连接

#### 问：支持哪些 AI 服务？

**答：** 任何与 OpenAI Chat Completions API 兼容的服务，包括但不限于：
- OpenAI（GPT-4、GPT-3.5 等）
- Azure OpenAI 服务
- 阿里云百炼（DashScope）
- 任何兼容的第三方 API 服务

#### 问：可以离线使用吗？

**答：** 不可以。本插件需要网络连接调用 AI API 服务。不过技能目录中的参考文件可以帮助 AI 生成更符合项目规范的代码。

#### 问：API 密钥安全吗？

**答：** API 密钥存储在 Eclipse 偏好设置中并以星号显示。但请注意：
- 不要将含 API 密钥的配置文件提交到版本控制
- 尽可能使用环境变量或密钥管理服务存储密钥

---

### 技术栈

- **语言**：Java 17
- **框架**：Eclipse Platform / JFace / SWT
- **API 协议**：OpenAI Chat Completions API
- **JSON 处理**：纯 Java 实现（无第三方依赖）
- **构建**：Eclipse PDE / ANT

---

### 许可

本项目仅供学习与个人使用。请遵守您使用的 AI 服务的服务条款和许可要求。

---

### 致谢

本插件受 VS Code Copilot 等 AI 辅助编程工具的启发，旨在为 SAP ABAP 开发者提供更好的编码体验。
