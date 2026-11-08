# Union data catalog

The **Node list** page (`/nodes/`) shows the data centers and shared center, including connection status, dataset summaries, data volume and reported resource metrics.

Use the category filters to distinguish original datasets from analysis data products. Each card labels its category; category filters can be combined with text search.

The catalog opens with a searchable grid of dataset cards, showing each title, description and identifier. Open a card to browse Items; use the back button to return to the filtered dataset list. Item requests are sent only after opening a dataset.

Browse datasets advertised by the configured Union at `/catalog/`, including member data centers and shared-center data products. Search names, descriptions and identifiers; selecting a dataset loads its Items and Folders lazily.

Dataset details show descriptions, public metadata, DACP addresses and the Union S3 address when enabled. Copy each Item's DACP URI. Items with bytes views also offer S3 address copying when Union S3 is enabled. Item actions are labeled “复制DACP URI” and “复制S3地址”; they do not expose temporary-link generation. Connections must still trust the service certificate.

Record previews are bounded to 20 rows, 64 columns and 512 characters per cell. Byte previews read at most 4096 bytes; binary displays show at most 256 bytes in hexadecimal. Specialized scientific views show structural metadata without flattening arrays into records. DACP enforces content permissions; discovery does not imply permission to read.

Requests are restricted to datasets advertised by the configured Union, rather than arbitrary remote sources. Directory entries are cached for 30 seconds. Pages load only on opening, interaction or manual refresh. An Item listing displays at most 1000 entries.

Operations: after starting the network, run `bin/conet catalog-up` to enable S3 on an idle Union and prepare the catalog signing account, followed by `bin/conet restart-web`. The seven scientific nodes do not need S3. Never distribute long-lived signing secrets to browsers or application packages.
