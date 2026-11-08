# 应用程序包

CONET 应用程序包（`*.conet.zip`）是应用的可移交交付单元，包含输入表单、变量绑定、结果展示及固定的 XStream Job。在 App Builder 导入后核对依赖，点击“加载并发布”，应用就会注册并立即获得 `/apps/<id>/` 访问地址。用户通过该页面提交分析。仅导入只形成可编辑草稿，不执行 Job，也不发布应用。

```mermaid
flowchart LR
  J[流程设计] --> A[应用设计]
  A --> P[导出程序包]
  P --> I[导入草稿]
  I --> L[加载并发布]
  L --> W[在线应用]
  W --> R[提交分析]
```

## 包内容

```text
application.conet.zip
├── manifest.json
├── application.yaml
├── job.yaml
├── cover.png 或 cover.jpg（可选）
└── background.png 或 background.jpg（可选）
```

清单采用 `format: conet-application`、`version: 1`。CONET 导出时记录引用的算子版本及可选的 `data_scope`，用于说明依赖。`application.yaml` 内的 Job 引用固定为 `job.yaml`。自定义上传的 PNG/JPEG 封面随包保存，导入时恢复；标准 `/assets/` 封面及 HTTPS 封面保留 URL 引用。包不包含数据集、上传输入、运行历史、结果、算子二进制、运行环境、服务凭据或模型 API 配置。

目标部署须具备相应算子和运行环境。数据集或 manifest 绑定需要本地配置有效的 `data.scope`；显式远程 URI 须可访问。仅接收上传文件的应用可以省略 `data.scope`。文件上传需要共享托管节点。加载时校验配置的数据上下文及应用定义；实际算子可用性、数据权限由执行服务在分析时检查。

## App Builder 操作

- “导入应用程序包”打开草稿，先展示流程设计，再提供应用设计及 YAML 源码。界面不再显示重复的四步提示条。
- 编辑器中的“导出程序包”导出经过校验的草稿；概览中的导出链接下载当前加载版本。
- “加载并发布”在修订校验后保存定义并提供在线页面，不自动开始分析。
- 导入的 ID 已存在时，编辑器明确提示加载将更新同名应用，加载时再次核对修订。要保留两份应用可先复制。

程序包的压缩及展开内容均不得超过 4 MiB，只接受七个明确支持的文件名。应用、Job 和清单各不超过 256 KiB，每张背景图或缩略图不超过 2 MiB。拒绝未知文件、重复名称、路径穿越、缺失文件及无效定义。条目使用受限缓冲区读取，不解压到任意文件系统路径。导入不会执行包中的内容。

## API 与命令

| 接口 | 用途 |
|---|---|
| `POST /api/appbuilder/packages/import` | `application/octet-stream` 原始 ZIP，返回未发布草稿 |
| `POST /api/appbuilder/packages/export` | JSON 提供 `application_yaml` 与 `job_yaml`，返回 ZIP |
| `GET /api/appbuilder/packages/<id>` | 导出当前加载应用 |
| `POST /api/appbuilder/apps` | 加载发布已核对的草稿，更新时提供修订 |

```bash
python3 scripts/build_application_package.py examples/uploaded-csv /tmp/uploaded-csv.conet.zip
```

通过 App Builder 导入该 ZIP。上传配置与可直接运行的 CSV 示例见[文件输入](file-inputs_zh.md)。辅助脚本打包 `application.yaml` 和 `job.yaml`；需要携带自定义上传封面时使用 App Builder 导出。
