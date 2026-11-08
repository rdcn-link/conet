# CONET App Builder

[English](README.md)

用于配置式分析应用开发的 Scala 子模块：提供可视化应用编辑、对话式配置助手及 XStream Job Designer 对接、类型化表单、固定 Job 模板、Union 提交与取消、持久化历史及使用统计、成果解码、可视化和下载。发布后立即生成可访问的 `/apps/<id>/` 地址，无需重启服务器。门户自动发现已发布应用，展示描述和封面图；访客页面提供独立分析界面，不展示开发导航。

配置、发布、API、存储、限制及成果解码插件契约见[应用开发指南](../docs/appbuilder_zh.md)。`cases/` 下的三个内置应用提供完整定义。

在 CONET 仓库根目录执行：

```bash
sbt -batch 'appbuilder/test' test stage
bin/conet restart-web
```

[工作空间与共享托管](../docs/workspaces_zh.md)
