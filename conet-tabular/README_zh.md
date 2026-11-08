# CONET 表格展示插件

`csv-table` ResultDecoder 使用 XStream 的 CSV 格式实现，将受限的 CSV 成果解码为 App Builder 表格和指标所需的 JSON。保留单元格文本，支持 UTF-8 BOM、引号及多行内容。输出记录数、字段数、空值及非空值数、数值单元格数、有限数值的最小及最大值。限制为 16 MiB、100000 条记录、256 个名称唯一的字段。不执行分布式聚合。

在 `META-INF/services/conet.appbuilder.ResultDecoder` 登记 `conet.tabular.CsvTableDecoder`，将 JAR 及其 XStream 格式依赖加入宿主 classpath。标准 CONET 分发包含本模块。设置 `result.decoder: csv-table`；参见 [CSV 应用](../examples/uploaded-csv/application.yaml)。其他解码器使用相同扩展契约，不需要修改核心注册表。运行 `sbt 'tabular/test'` 检查引号、多行、空单元格及结构冲突。
