# CONET shared hosting

[中文](README_zh.md)

One hosted DACP node publishes explicitly shared datasets and analysis products from personal spaces. `HostedCatalogProvider` implements XStream's `DataCatalogProvider`, uses the `directory` Dataset provider and is discovered by Java ServiceLoader. It reads publication descriptors on each discovery/open request and enforces withdrawal and expiration during access. Private workspace roots are never advertised.

See [workspaces](../docs/workspaces.md) for plugin registration, deployment, configuration, quota semantics and API examples. Build and test with `sbt 'hosting/test' hosting/packageBin`. Install the resulting JAR in DACP's `plugin_directory`; no core registry change or client rebuild is needed. Format dependencies stay in the DACP node's installed format plugins.
