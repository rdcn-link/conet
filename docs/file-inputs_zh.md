# 上传文件作为应用输入

访客无需登录即可在应用表单中上传文件。CONET 流式写入暂存区，校验扩展名与大小，返回上传引用。提交分析时，将服务端生成的 DACP Item URI 绑定到固定 Job 的变量。客户端不能提交文件系统路径，也不能用自定义 URI 冒充上传引用。

```mermaid
flowchart LR
  B[浏览器文件] -->|受限上传| S[暂存文件]
  S -->|提交分析| I[不可变运行输入]
  I --> C[共享 DACP 节点]
  C -->|DACP URI 变量| U[Union Job]
  U --> R[read_item]
  R --> O[结果与下载]
```

## 配置文件字段

```yaml
inputs:
  - name: source
    label: Input file
    widget: file
    required: true
    extensions: [.csv]
    max_bytes: 16777216
    view: bytes
variables:
  INPUT_URI: {input: source}
```

```yaml
name: Uploaded input
steps:
  - id: read_input
    operator: read_item@1.0
    params: {uri: $INPUT_URI, view: bytes}
    inputs: {}
outputs: {result: read_input}
```

变量采用 XStream 实际支持的 `$INPUT_URI` 语法。绑定文件字段得到 Item URI，而不是整个元数据对象。`read_item.params.view` 必须与文件字段的 `view` 一致。结构化流供下游算子处理时可用 `records`；读取原始内容时采用 `bytes`，包括 ZIP 根项。指定视图不会将不支持的格式转换为 records。支持对象分组内的嵌套文件字段，使用点分输入绑定。

实际上传上限为字段 `max_bytes` 与 `hosting.max_upload_bytes` 中的较小值，默认 16 MiB，最多 64 MiB。拒绝空文件和不符合扩展名的文件。执行时由选定的格式读取器验证内容，扩展名检查不代表内容有效。当前为单文件请求，不支持断点续传。编辑器中的表单预览只展示控件；加载发布应用后才能上传。

## 存储与保留期

八个 Node 成员——七个科学数据中心与一个共享中心——继续连接同一 Union。共享中心存储应用输入文件。每次受理分析均建立独立、不可变的运行目录和 `input-<run-id>` 数据集，使用共享节点的完整 `dacp://…` URI。输入数据集不出现在公开 Catalog 列表；直接读取仍遵循节点的正常 DACP 授权。工作空间无需登录，不提供用户隐私隔离；上传标识不属于用户认证凭据。

```yaml
appbuilder:
  uploads:
    retention_days: 7
    quota_bytes: 1073741824
hosting:
  max_upload_bytes: 16777216
```

暂存文件在配置天数后过期。运行输入在任务执行期间保持可读，执行结束后开始计算保留期，失败和取消同样适用。服务重启导致中断的运行输入在清理时获得相同保留期。每分钟清理一次，跳过活动任务。文件位于 `.state/hosting/inputs/`，非公开执行描述位于 `.state/hosting/execution-inputs/`。输入存储采用独立总配额，计入暂存与运行文件。分享结果不会自动附带输入原文件；运行溯源记录保留文件名、字节数、SHA-256、视图、URI 及保留信息。发布结果可能包含由输入推导的信息。

## API 与示例

`POST /apps/<app>/api/uploads/<field>` 接收 `application/octet-stream` 请求体，通过百分号编码的 `X-File-Name` 请求头传递文件名，保留同源修改检查。返回 `upload_id`、文件名、大小、SHA-256、视图和过期时间，再向 `/apps/<app>/api/jobs` 提交 `{"source":{"upload_id":"<id>"}}`。上传引用只适用于对应应用及字段。预览校验引用，仅正式提交时分配运行 Item。

参见 [CSV 应用](../examples/uploaded-csv/application.yaml) 和 [JSON 应用](../examples/uploaded-json/application.yaml)。CSV 应用通过真实 Union Job 读取原始文件，再由 `csv-table` 成果展示插件生成表格与数值字段统计。这是受限的文件分析示例，不是分布式聚合基准。支持 UTF-8 CSV、可选 BOM、引号和多行单元格，最多 16 MiB、100000 行、256 个唯一字段。保留文本值，统计有限数值单元格。预览前 10 行，CSV 下载包含全部受理记录并转义电子表格公式；还可下载统计、分析 JSON、溯源元数据及报告。

```bash
python3 scripts/build_application_package.py examples/uploaded-csv /tmp/uploaded-csv.conetapp
python3 scripts/smoke_uploaded_csv.py --url http://127.0.0.1:8870
python3 scripts/smoke_input_retention.py --url http://127.0.0.1:8870
```

## 使用远程 Item

文件输入提供“上传文件”和“远程 DACP Item”两种方式。远程方式提交 `{"uri":"dacp://host:port/dataset/item.csv"}`，绑定同一个 Job 变量，不创建本地上传副本。服务端校验路径、所需 View 和已知文件大小；远程目录仅在明确提供 bytes 且输入需要 bytes 时接受。URI 不接受账号、查询参数或片段。连接使用已配置的 CA 验证服务器身份，不将 Union 凭据转发到用户指定的地址。执行端仍须能够访问并读取该 Item。

## 复用分析输入

App 和工作空间的分析历史显示输入可用状态、文件名和自动清理时间。**长期保留输入**固定保存当次上传快照，仍不公开，并计入输入存储配额。**恢复自动清理**为已结束的运行重新开始计算配置的保留期；执行中的输入一直保留到运行结束。结果产品发布与上传输入保留相互独立。

**重新运行**根据保存的 Job、应用定义、清单、参数和数据集绑定创建新的分析，应用后来被编辑也不会改变这些历史设置。上传文件来自原运行的快照，不依赖暂存上传引用。提交前检查过期时间、文件存在性、安全路径、大小和 SHA-256；输入过期、丢失或内容改变时需重新上传。新运行拥有独立的输入 URI 和保留期。任务排队限制、取消及存储配额同样适用于重新运行。远程 Item 会重新校验，但不会保存快照；其内容可能改变，因此不保证结果完全相同。

- `GET /apps/<app>/api/jobs/<run>/inputs`：查询输入状态和过期时间。
- `POST /apps/<app>/api/jobs/<run>/inputs/retention`，请求体 `{"keep":true}` 长期保留；`false` 恢复自动清理。
- `POST /apps/<app>/api/jobs/<run>/rerun`，请求体 `{}`，复用历史输入，不接受替换参数；应用必须在线。

### 共享节点执行权限

`bin/conet hosting-up`确保共享节点已注册，并在 Union 的 `trusted_job_sources` 中配置 `conet-app` 服务账号及该节点的准确 DACP 来源地址。更新已有配置要求 Union 空闲，仅在配置变化时重启 Union。这允许服务使用非公开上传输入执行任务，无需将这些数据集列入目录或公开发布。其他客户端和来源仍进行正常的发布策略检查。共享节点通过绑定证书的成员凭据授权 Union。不要向访客提供服务 token。
