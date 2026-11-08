# CONET tabular presentation plugin

The `csv-table` ResultDecoder uses XStream's CSV format implementation to decode bounded CSV output into JSON for App Builder tables and metrics. It preserves textual cells and supports UTF-8 BOM, quoting and multiline values. It reports row/column counts, empty/nonempty cells, numeric-cell counts and finite numeric minima/maxima. Limits: 16 MiB, 100,000 records and 256 uniquely named columns. It does not perform distributed aggregation.

Register `conet.tabular.CsvTableDecoder` in `META-INF/services/conet.appbuilder.ResultDecoder` and include the JAR and its XStream format dependencies on the host classpath. The standard CONET distribution includes this module. Select `result.decoder: csv-table`; see [the CSV application](../examples/uploaded-csv/application.yaml). Other decoders use the same extension contract without changing core registries. Run `sbt 'tabular/test'` for quoting, multiline, empty-cell and schema-conflict tests.
