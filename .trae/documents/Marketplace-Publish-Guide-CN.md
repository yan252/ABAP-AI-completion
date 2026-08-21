# 将 ABAP AI Completion 发布到 Eclipse Marketplace —— 中文操作指南

本指南用中文说明整个发布流程。所有**需要提交/展示给用户的内容（Marketplace 表单、README、网站文案）一律使用英文**，这些英文素材已经为你准备好了（见 `.trae/documents/Eclipse-Marketplace-Listing.md`）。

先理解核心原理，再做操作：
Eclipse Marketplace 并不托管插件文件，它只提供一个**让用户在 Eclipse 里搜索并一键安装的入口**。安装时 Eclipse 会去访问你提供的一个 **p2 更新站点（Update Site）URL** 下载插件。所以：

> **你必须先拥有一个公网可访问的 p2 更新站点 URL，才能提交 Marketplace。**

你已选择了 **GitHub Pages** 免费托管，这是 Eclipse 插件社区最常用的做法。

---

## 一、整体流程总览

```
① 生成更新站点（已有，1.0.2）
② 确认仓库为 public 并推送到 GitHub
③ 开启 GitHub Pages，得到更新站点 URL
④ 用 Eclipse 验证该 URL 能正常安装
⑤ 拍摄截图素材
⑥ 填 Marketplace 提交表单（英文素材已备好）
⑦ 等待 Marketplace 审核通过 → 用户可搜索安装
```

---

## 二、生成更新站点（这一步基本已完成）

你的 `update-site/` 目录已经由 [generate-update-site.ps1](file:///c:/Users/96000217/Documents/trae_projects/com.sap.abap.ai.completion/generate-update-site.ps1) 生成了 **1.0.2** 版本，内容正确：
- `site.xml`
- `content.jar` / `content.xml`
- `artifacts.jar` / `artifacts.xml`
- `features/com.sap.abap.ai.completion.feature_1.0.2.jar`
- `plugins/com.sap.abap.ai.completion_1.0.2.jar`

以后每次发新版：改版本号 → 重新编译打包到 `dist/` → 运行 `generate-update-site.ps1` 重新生成 → 再次提交并推送。

> 注意：`generate-update-site.ps1` 里硬编码了本机绝对路径（第 10、11 行），换电脑或路径变动时需要改。这不影响 Marketplace 上的用户，只影响你自己构建。

---

## 三、确认仓库公开并推送到 GitHub

Marketplace 和用户都需要能访问你的源码与更新站点，仓库必须 **public**。

1. 在 GitHub 打开你的仓库 `https://github.com/yan252/ABAP-AI-completion`。
2. `Settings` → 底部 `Danger Zone` → `Change visibility` → 选 **Public**（如尚为 private）。
3. 把最新的 `update-site/`（1.0.2）、`dist/`、README 等提交并推送：

```powershell
git add update-site dist README.md
git commit -m "chore: publish 1.0.2 update site"
git push origin main
```

> 注意：`.gitignore` 目前**没有**排除 `update-site/`，所以它能正常提交；请确认 `update-site/` 中的 1.0.2 文件确实已被 `git add`（前面 `git status` 显示 1.0.2 文件还是未跟踪状态）。
> 建议同时在仓库根目录放一个 `LICENSE` 文件（MIT，内容见英文素材），Marketplace 审核更顺畅。

---

## 四、开启 GitHub Pages（关键步骤）

要让 GitHub Pages 以 **`https://yan252.github.io/ABAP-AI-completion/update-site/`** 提供更新站点。

1. 仓库页面 → `Settings` → 左侧 `Pages`。
2. 在 **Build and deployment** 中：
   - **Source** 选 **Deploy from a branch**
   - **Branch** 选你发布用的分支（默认 `main`），目录 `/ (root)`
   - 点击 **Save**。
3. 等待 1-2 分钟，页面顶部会显示站点地址：`https://yan252.github.io/ABAP-AI-completion/`。

这样整个仓库（包括 `update-site/`）就通过 Pages 发布了。你需要填给 Marketplace 的更新站点 URL 就是：

```
https://yan252.github.io/ABAP-AI-completion/update-site/
```

> 备选方案：如果你想**不污染源码仓库**、单独发布更新站点，可以用 GitHub Actions 方案。但那需要额外写 workflow，对当前情况没有必要，直接用上面的分支方案最简单稳妥。

---

## 五、验证更新站点 URL（务必做，否则 Marketplace 会拒绝）

发布后，用浏览器打开以下地址，确认能正常返回（不是 404）：
- `https://yan252.github.io/ABAP-AI-completion/update-site/site.xml`
- `https://yan252.github.io/ABAP-AI-completion/update-site/content.jar`

然后在 Eclipse 里真正测一次安装：
1. `Help` → `Install New Software...`
2. `Add...` → Name 填 `ABAP AI Completion`，Location 填上面的 `.../update-site/`
3. 应出现 **ABAP AI Completion Feature**，勾选安装。
4. 安装成功并重启后，确认出现 `Window > Preferences > ABAP AI Completion` 配置页。

> 这一步最重要：**更新站点 URL 必须真实可用**，Marketplace 审核时会抓取验证。URL 无效是最常见的被拒原因。

---

## 六、拍摄截图

Marketplace 允许 1–3 张截图。请实际运行插件后截图：

1. **偏好设置页**（必选）：`Window > Preferences > ABAP AI Completion`，展示 AI 连接设置、测试按钮等。
2. **代码补全覆盖层**（建议）：打开一个 `.abap` 文件，按 `Ctrl+Shift+.` 触发 AI 补全，截取浮动覆盖层正在显示建议的瞬间。

建议：1280×800 或更宽的 PNG，内容清晰、不要带敏感信息。

---

## 七、提交 Marketplace（英文表单，素材已备好）

> ⚠️ 更新说明：直链 `https://marketplace.eclipse.org/listing-request` 已失效（返回 404），不要再使用。Eclipse Marketplace 是基于 Drupal 的站点，插件提交表单**必须登录后**才会显示。请按下面的修正版流程操作。

### 正确入口（登录后提交）

1. 登录：打开 `https://marketplace.eclipse.org/user/login`（无账号先点 `https://marketplace.eclipse.org/user/register` 注册，需邮箱）。
2. 登录后，在页面顶部的 **Listings** 菜单里找到 **Submit a Solution / Add a Listing**（或直接访问可用的 listings 页面进入提交表单的入口）。
3. 在提交表单中，用 `.trae/documents/Eclipse-Marketplace-Listing.md` 中的英文内容逐项填写并提交。

该文件已含：
- 标题 / 短描述 / 长描述（HTML）
- 版本 / 分类 / 标签 / 许可证
- 更新站点 URL（需要你把 `TODO` 替换成第四步得到的真实 URL）
- 源码 / 文档 URL

关键字段速查：

| 表单字段 | 填写内容 |
|---------|---------|
| Listing Title | `ABAP AI Completion` |
| Update Site / p2 | `https://yan252.github.io/ABAP-AI-completion/update-site/` |
| License | MIT License |
| Version | 1.0.2 |
| Category | Developer Tools |
| Source | https://github.com/yan252/ABAP-AI-completion |

提交后，Marketplace 团队会人工审核。审核通过后，用户就能在 Eclipse 里 `Help → Eclipse Marketplace...` → 搜索 **ABAP AI Completion** 一键安装。

---

## 八、之后每次发新版要做什么

1. 改 `META-INF/MANIFEST.MF`、`feature`、`generate-update-site.ps1`、README、英文素材里的版本号。
2. 编译打包 → 运行 `generate-update-site.ps1` 重新生成 `update-site/`。
3. `git add update-site dist` + 推送。
4. （可选）去 Marketplace 后台更新版本号与 changelog。

只要更新站点 URL 不变，Marketplace 条目无需重新提交就能给已有用户推送更新。

---

## 九、常见问题

- **Q：Pages 打开了但 site.xml 404？** → 确认分支和根目录选对，且 `update-site/` 已提交并推送；等几分钟再刷新浏览器的缓存。
- **Q：Eclipse 报 "No repository found at..."？** → URL 拼写错误或 p2 元数据未随目录一起发布，重新核对第五步。
- **Q：需要备案 / 服务器吗？** → GitHub Pages 免费，无需备案、无需服务器。
- **Q：Marketplace 有哪些费用？** → 个人开源项目提交免费。
- **Q：我的仓库还没开源，想先发布？** → 必须先 public，私有仓库无法 Pages 公开访问也无法被 Marketplace 审核。

---

## 材料清单（英文内容已就绪）

| 材料 | 位置 | 状态 |
|------|------|------|
| Marketplace 投稿稿（英文） | `.trae/documents/Eclipse-Marketplace-Listing.md` | ✅ 已完善，仅剩替换 URL 占位符 |
| 用户安装说明（英文） | `.trae/documents/INSTALL-EN.md` | ✅ 已有 |
| 截图素材 | 你拍摄后放入 `screenshots/` | ⏳ 待你提供 |
| 更新站点（p2） | `update-site/`（1.0.2） | ✅ 已生成，待推送到 Pages |
| LICENSE 文件（推荐） | 仓库根目录 | ⏳ 建议补充 |
