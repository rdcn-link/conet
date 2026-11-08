# CONET 共享托管

[English](README.md)

一个托管 DACP 节点对外发布个人空间中明确共享的数据集和分析产品。`HostedCatalogProvider` 实现 XStream 的 `DataCatalogProvider`，复用 `directory` Dataset 插件，通过 Java ServiceLoader 发现。每次发现及打开请求读取发布描述，并在访问时校验撤回和到期状态，不会发布私有空间根目录。

注册、部署、配置、配额语义及 API 见[工作空间](../docs/workspaces_zh.md)。构建测试用 `sbt 'hosting/test' hosting/packageBin`，将生成的 JAR 安装到 DACP 的 `plugin_directory`，不需要修改核心注册表或重新构建客户端。格式依赖使用 DACP 节点已经安装的格式插件。
