# Tasks: v1.2.14 — Recently Modified 下 PROG/I 无展开箭头修复

- [ ] T1 升版本号：MANIFEST.MF 第 5 行 `1.2.13`→`1.2.14`；build.ps1 第 9 行 jar 名同步
- [ ] AdtNodeFactory.java：新增 `CLS_ABAP_LAUNCHABLE_NODE` 常量；节点创建优先 Launchable 4 参构造器（回退 Generic 4 参 → Generic 2 参）；成功后显式 `setParent(parent)`
- [ ] AdtNodeFactory.java：`setExpandable` 的 catch 打开记录异常
- [ ] AdtNodeFactory.java：`return node` 前加 DIAG 诊断日志（节点类名/repoObj 类名/expandable 值/repoObj.hasChildren/node.hasChildren/getValue 同一性/viewer 状态）
- [ ] 构建 `powershell -File build.ps1`，验证 jar 内含新代码字符串
- [ ] 部署到 `D:\Users\96000217\eclipse\dropins`（删旧 jar，`[System.IO.File]::Copy` 复制新 jar）
- [ ] 用户验证：重启 Eclipse → 检查 PROG/I 箭头与展开、PROG/P 不受影响 → 反馈 `.metadata/.log` 的 DIAG 行
