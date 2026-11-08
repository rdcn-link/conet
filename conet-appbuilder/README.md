# CONET App Builder

[中文](README_zh.md)

A Scala submodule for configuration-driven analysis applications: visual application editing, an interview assistant and XStream Job Designer integration, typed forms, fixed Job templates, Union submission and cancellation, persisted history and usage counters, result decoding, visualizations and downloads. Publishing creates an immediately usable `/apps/<id>/` URL without a server restart. The portal discovers published applications automatically and displays their descriptions and cover images. Visitor pages provide a standalone analysis interface without developer navigation.

See the [application development guide](../docs/appbuilder.md) for configuration, publishing, API, storage, limits and the result-decoder plugin contract. The three bundled applications in `cases/` provide complete definitions.

From the CONET repository root:

```bash
sbt -batch 'appbuilder/test' test stage
bin/conet restart-web
```

[Accounts, personal spaces and shared hosting](../docs/workspaces.md)
