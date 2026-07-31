# ABAP AI Completion - Eclipse Plugin

一个为SAP ABAP开发者打造的Eclipse AI代码补全插件。基于大语言模型（LLM），可使用本地LLM,在编写ABAP代码时提供智能的代码建议和自动补全功能。

## 功能特性

- **🤖 AI智能补全**: 基于AI模型，在光标位置提供上下文相关的ABAP代码建议
- **⌨️ 手动触发**: 按 `Ctrl+Shift+.` 手动调用AI代码补全
- **⚡ 自动补全**: 可选的自动补全模式，停止输入后自动触发AI建议
- **📋 INCLUDE解析**: 自动解析ABAP INCLUDE程序，提供更准确的上下文补全
- **🎯 技能目录**: 支持 `.abap`、`.txt`、`.skill` 文件作为AI参考模式
- **🌊 浮动覆盖层**: 代码建议以浮动覆盖层形式显示（类似Copilot风格）
- **🎨 自定义样式**: 可自定义补全代码的显示颜色
- **🔌 灵活配置**: 支持任何兼容OpenAI Chat Completions API的服务端点

---

## 安装说明（普通用户）

### 系统要求

- **Eclipse**: 4.7+ (Neon) 或更高版本
- **Java**: JDK 17 或更高版本
- **网络**: 需要能够访问配置的AI API服务

### 安装步骤

1. **下载插件JAR包**

   从仓库的 `dist` 目录下载最新版本的JAR文件：
   ```
   dist/com.sap.abap.ai.completion_1.0.0.jar
   ```

2. **安装到Eclipse**

   将JAR文件复制到Eclipse的 `dropins` 目录：
   ```
   <你的Eclipse安装路径>/dropins/
   ```
   
   例如：
   ```
   C:\Users\YourName\eclipse\dropins\com.sap.abap.ai.completion_1.0.0.jar
   ```

3. **重启Eclipse**

   完成复制后，**重启Eclipse**即可加载插件。

---

## 使用说明

### 手动触发代码补全

1. 打开任意ABAP源代码文件（`.abap`、`.prog`等）
2. 将光标放在需要补全的位置
3. 按快捷键 `Ctrl + Shift + .` 调用AI补全
4. AI建议将以浮动覆盖层形式显示
5. 按 `Tab` 键接受建议，按其他键取消

### 自动补全模式

在配置中启用 **"Auto-complete while typing"** 后，停止输入一段时间（默认2000ms）会自动触发AI建议。

### 修改快捷键

如需修改快捷键：
1. 菜单栏：`Window` → `Preferences`
2. 导航到：`General` → `Keys`
3. 搜索：`ABAP AI completion`
4. 点击 `Binding` 字段，按下新的快捷键组合
5. 点击 `OK` 保存

---

## 配置说明

### 打开配置页面

1. 菜单栏：`Window` → `Preferences`
2. 在左侧导航栏中找到 **`ABAP AI Completion`**
3. 配置页面如下图所示：

![配置页面](配置截图.png)

### 配置项详解

#### AI连接设置 (AI Connection Settings)

| 配置项 | 说明 | 示例 |
|--------|------|------|
| **API Base URL** | AI服务端点地址 | `https://api.openai.com/v1` |
| **Model Name** | 使用的AI模型名称 | `gpt-4`, `deepseek-v4-flash` |
| **API Key** | API认证密钥 | （将以星号显示） |
| **Max Tokens** | 最大生成Token数 | `256` |
| **Temperature** | 创造性温度 (0.0-2.0) | `0.3`（较低值表示更确定性的输出） |

> 💡 **提示**: 点击 `Test Connection` 按钮测试API连接是否正常。

#### 功能设置 (Feature Settings)

| 配置项 | 说明 |
|--------|------|
| **Enable ABAP AI Completion plugin** | 启用/禁用整个插件 |
| **Auto-complete while typing** | 启用后停止输入自动触发AI建议 |

#### 自动补全设置 (Auto-Completion Settings)

| 配置项 | 说明 |
|--------|------|
| **Delay after typing (ms)** | 停止输入后多少毫秒触发自动补全 |

> 💡 **建议**: 推荐值为 1500-3000 ms。较低的值会增加API请求频率。

#### 技能目录 (Skill Directory)

将 `.abap`、`.txt` 或 `.skill` 文件放置在技能目录中，AI会参考这些文件中的代码模式进行补全。这对于特定项目的代码规范和命名约定非常有用。

#### 自定义系统提示 (Custom System Prompt)

可以自定义发送给AI的系统提示，用于引导AI遵循特定的代码风格和规范。

#### 覆盖层样式 (Overlay Style)

自定义AI补全代码在编辑器中的显示颜色。

---

## 开发者指南（二次开发）

### 环境要求

- **JDK**: 17+
- **Eclipse**: 带Plug-in Development Environment (PDE) 的 Eclipse
- **构建工具**: Apache Maven（可选）

### 导入项目

1. 克隆仓库到本地：
   ```bash
   git clone https://github.com/yan252/ABAP-AI-completion.git
   ```

2. 在Eclipse中导入项目：
   - `File` → `Import` → `Existing Projects into Workspace`
   - 选择克隆的项目目录
   - 勾选项目并点击 `Finish`

### 项目结构

```
com.sap.abap.ai.completion/
├── META-INF/
│   └── MANIFEST.MF              # OSGi bundle清单
├── src/
│   └── com/sap/abap/ai/completion/
│       ├── client/              # AI API客户端
│       │   ├── AIClient.java
│       │   └── AIClientException.java
│       ├── editor/              # 编辑器集成
│       │   ├── AICompletionHandler.java
│       │   ├── AICompletionService.java
│       │   ├── AICompletionOverlay.java
│       │   ├── AICompletionProposalPopup.java
│       │   ├── AIOverlayManager.java
│       │   └── AICompletionListener.java
│       ├── parser/              # ABAP解析器
│       │   └── AbapIncludeResolver.java
│       ├── preferences/        # 配置管理
│       │   ├── AICompletionPreferencePage.java
│       │   ├── AIConfiguration.java
│       │   ├── PreferenceConstants.java
│       │   └── PreferenceInitializer.java
│       └── Activator.java       # Bundle激活器
├── plugin.xml                   # 插件扩展点声明
├── build.properties             # 构建属性
├── build_plugin.xml             # 构建脚本（ANT）
└── dist/
    └── com.sap.abap.ai.completion_1.0.0.jar  # 发布的JAR
```

### 关键扩展点

本插件通过以下Eclipse扩展点集成：

| 扩展点 | 用途 |
|--------|------|
| `org.eclipse.ui.startup` | 插件自动启动 |
| `org.eclipse.ui.preferencePages` | 配置页面注册 |
| `org.eclipse.core.runtime.preferences` | 偏好设置初始化 |
| `org.eclipse.ui.commands` | 补全命令定义 |
| `org.eclipse.ui.bindings` | 快捷键绑定 |

### 核心架构

1. **AIClient**: 纯Java实现的HTTP客户端，调用AI Chat Completions API，无需外部JSON库
2. **AICompletionService**: 异步补全服务，处理超时、取消和错误
3. **AbapIncludeResolver**: 解析ABAP INCLUDE程序，为AI提供完整的上下文
4. **AIOverlayManager**: 管理浮动覆盖层的显示和交互
5. **AIConfiguration**: 统一的配置管理，支持偏好存储

### 构建插件

#### 使用Eclipse导出

1. 右键点击项目 → `Export`
2. 选择 `Deployable plug-ins and fragments`
3. 选择输出目录为 `dist/`
4. 点击 `Finish`

#### 使用ANT脚本

```bash
# Windows PowerShell
.\build.ps1
```

或使用提供的 `build_plugin.xml`：
```bash
ant -f build_plugin.xml
```

### 调试插件

在Eclipse中启动一个新的Eclipse实例进行调试：

1. `Run` → `Run Configurations`
2. 新建 `Eclipse Application` 配置
3. 在 `Plug-ins` 选项卡中，确保 `com.sap.abap.ai.completion` 已包含
4. 点击 `Run` 启动

---

## 常见问题 (FAQ)

### Q: 插件安装后没有出现配置选项？

**A:** 请确认：
1. JAR文件已正确放入 `dropins` 目录
2. 已重启Eclipse
3. 使用的Eclipse版本符合要求（4.7+）
4. Java版本为17或更高

### Q: 代码补全请求超时怎么办？

**A:** 
- 检查网络连接是否正常
- 确认API Base URL和API Key配置正确
- 可以在配置中增加 `Max Tokens` 和 `Temperature` 的值
- 尝试点击 `Test Connection` 按钮验证连接

### Q: 支持哪些AI服务？

**A:** 支持任何兼容OpenAI Chat Completions API的服务，包括但不限于：
- OpenAI (GPT-4, GPT-3.5等)
- Azure OpenAI Service
- 阿里云百炼 (DashScope)
- 任何兼容的第三方API服务

### Q: 能否在离线环境使用？

**A:** 不能。本插件需要网络连接来调用AI API服务。但技能目录中的参考文件可以帮助AI生成更符合项目规范的代码。

### Q: API Key安全吗？

**A:** API Key存储在Eclipse的偏好设置中，以星号显示。但请注意：
- 不要将包含API Key的配置文件提交到版本控制系统
- 使用环境变量或密钥管理服务存储密钥（如果可能）

---

## 技术栈

- **语言**: Java 17
- **框架**: Eclipse Platform / JFace / SWT
- **API协议**: OpenAI Chat Completions API
- **JSON处理**: 纯Java实现（无第三方依赖）
- **构建**: Eclipse PDE / ANT

---

## 许可证

本项目仅供学习和个人使用。请遵守所使用AI服务的相关服务条款和许可证要求。

---

## 致谢

本插件灵感来源于VS Code Copilot等AI辅助编程工具，旨在为SAP ABAP开发者提供更好的编码体验。