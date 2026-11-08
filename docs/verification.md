# Verification record

[中文](verification_zh.md) · [Home](../README.md)

## Shared-network execution

Verified locally with Java 17, the Scala CONET service, one Union, seven members, fourteen datasets and the full synthetic inputs. Requests use the actual Scala DACP SDK and execute on DACP/Union/XStream services.

| Case | Verified Union Job | Result |
|---|---|---|
| Check dams | `7023b606-3042-44ae-90d6-0a3c01ec3946` | First tile; 3 accepted candidates, 4 rejected |
| Observatories | `1dd3a51b-1e2d-4e37-8c7f-6069eb6e70cb` | Cone `[150,2,0.15]`; 3118 three-band matches |
| Earthquake | `85c54dbd-f7e6-4340-aedc-28a07d6637da` | 208.5 km² suspected impact, 14 settlements |
| Mudslide | `6a97c6bb-f9f0-4f9e-ba4d-4c1405724c7c` | 75 km² suspected impact, 4 settlements |

Missing imagery and stale hydrology produce incomplete results with unknown values. Exact triangular selection returns 2 check-dam sites versus 3 in its rectangular baseline. These are deterministic synthetic-data checks, not accuracy or performance claims.

## Automated checks

```bash
sbt test stage
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python scripts/smoke_web.py --url http://127.0.0.1:8870
.venv/bin/python scripts/smoke_appbuilder.py --url http://127.0.0.1:8870
.venv/bin/python scripts/smoke_appbuilder.py --url http://127.0.0.1:8870
```

Six Scala tests cover polygon validity and coverage, numerical validation, alias-free variables, HTTP routes, cross-origin rejection, persisted interrupted jobs, dataset containment, byte-size deduplication, linked package assets and atomic packaging failure. Twenty-four Python scientific tests cover stream fragmentation, raster processing, polygon clipping, astronomy crossmatching and emergency provenance/missing-data behavior.

Live tests submit all three case workflows and retrieve their artifacts. `bin/conet down` followed by `bin/conet up` verifies owned-process shutdown and full network startup. `restart-web` preserves the running network. Browser checks cover the portal, live screen, three case pages and the physical-plan viewer without JavaScript exceptions. Generated data, models, credentials and results remain outside Git.

## Operator repository

Seven source submissions were accepted by the local repository. All have completed static analysis and successful isolated builds; all remain **pending administrator review**. They are not public downloads yet. No trust score or category was assigned by the submission script.

| Operator | Submission ID |
|---|---|
| `check_dam_detect@1.0` | `793e745c-3a3f-4f40-9616-d7a5b3f10af8` |
| `check_dam_map@1.0` | `203c790d-d857-4d01-8db7-466573500b7d` |
| `check_dam_overlap@1.0` | `06d87932-5f9a-4d2a-8183-506a3c972af0` |
| `check_dam_susceptibility@1.0` | `bd018150-7620-411e-8a8d-9750b3b96f26` |
| `check_dam_terrain@1.0` | `97ec93f8-880c-41b2-85d1-5e9efd195c05` |
| `observatory_crossmatch@1.0` | `865a9020-a9ac-4c14-814f-39158a4402ee` |
| `observatory_select@1.0` | `080ed50c-4256-49d5-bade-44afc3670612` |

Execution runtimes `conet-geospatial@1.0` and `conet-astronomy@1.0` were registered. The former includes the synthetic detector weights at `/opt/models/check-dam.pt`; sources contain code only. The local application currently uses its administrator-installed operator packages. Re-running from publicly downloaded packages is a separate check after administrator approval, and has not been claimed as complete.

## Reference website

The requested reference `https://conet.4fair.cn/` was retried using HTTPS/HTTP, the configured proxy, direct connections and Chromium. Proxy requests failed with TLS/502 errors and direct requests timed out. The reference page could not be inspected. The screen follows the user-provided reference screenshot's globe-and-side-panel composition, with live Union observations and clearly marked illustrative node locations.

## Polygon selection verification

Run `python scripts/smoke_appbuilder.py --url http://127.0.0.1:8870` with the deployment Python environment. It rejects a self-intersecting request, executes a rectangle and a triangle over the first tile through the shared Union, and verifies every returned point against its Polygon. The rectangle yielded **3** valid sites and the triangle **2**. Nineteen contract tests passed, including envelope false positives, boundary inclusion, hole preservation under projection and the overlay membership guard. Browser checks covered polygon drawing, completion and vertex editing.

The five check-dam operators at version `1.1` were submitted as source-only packages and remain pending administrator approval. Their submission IDs are `1b44925a-24a0-4802-a235-e25fd36033b2` (detect), `bd86c3d9-53b5-489b-bcb1-40dcd0338132` (map), `68c0ba4b-574c-4d8e-8830-7cb781097e8d` (overlap), `835040fb-8da6-4ff3-bc8b-10b427906294` (susceptibility), and `71cea7dc-c7fc-4805-9c34-d0f076de97da` (terrain). Local execution uses the installed packages; public-download execution is not claimed.

## Seven-center network

All seven configured members were observed as approved, with ten published datasets and nonzero measured file volumes. The two real case jobs were repeated after expansion: check dams returned 3 valid candidates and astronomy returned 3118 matches. Browser checks at 1920×1080, 1440×900 and 1366×768 verified seven node entries, seven online markers, no overlapping callout rectangles and no page overflow. Nineteen contract tests passed. The six scientific inputs are distributed among three disciplinary data centers and three regional observatories. The remaining center publishes spatial reference datasets.

## App Builder verification

```bash
sbt -batch 'appbuilder/test' 'hosting/test' test stage
python3 scripts/smoke_web.py
python3 scripts/smoke_appbuilder.py
python3 scripts/smoke_appbuilder.py
python3 scripts/smoke_appbuilder_browser.py --chromium /path/to/chromium
```

The Python environment for emergency checks requires `shapely`; browser checks require `playwright` and Chromium. App Builder tests cover typed variable binding, input limits, safe artifact paths, XML entity rejection, CSV export, cancellation, queue limits, history recovery, retry-aware event deduplication and publication with revision protection. Browser checks cover generated forms, initially blank results, history selection, result views and editor preview. Tianditu components run in separate frames so their event handlers do not interfere with application controls.

Real shared-Union runs produced 3 valid check-dam candidates and 3118 matched astronomical sources. Emergency checks passed earthquake and mudslide analyses, plus missing-imagery and stale-hydrology scenarios. A fourth application published only from the observatory configuration completed with 3118 matched sources; its artifacts and all four usage counter categories were verified. Published definitions and counters were checked after restarting the web services.

The portal cards are sourced from the published application registry, including each application description and cover image. The application visual editor synchronizes with YAML; Job editing uses XStream Job Designer in a separate window. `scripts/smoke_appbuilder_visual.py --chromium /path/to/chromium` verifies explicit editing, duplication, Designer return, YAML roundtrip, dynamic homepage consistency and desktop/mobile layouts. Published visitor pages expose no workbench navigation or YAML source.


## App Builder authoring verification

The module and portal suites pass 21 distinct Scala tests, including a mock Responses server that checks server-owned credentials, multi-round proposal gating, configuration validation, protected data bindings and no implicit publication. The browser authoring test exercises explicit New/Edit/Duplicate, XStream Designer import/return, YAML roundtrip and standalone desktop/mobile pages. Its optional `--chat` mode runs a real three-request interview through the deployment's configured model connection and applies the proposal to the draft without publishing.

The public-page test stubs the external map frame to verify form, history and preview controls independently of third-party map availability. It is not a live-map connectivity test. Previously completed scientific runs remain separate execution checks; authoring verification does not resubmit analyses.


`scripts/smoke_appbuilder_configuration.py` checks compact field/group editing, variables extracted from Job values, missing bindings, fixed source selection, result controls and YAML roundtrip without publishing.

## Personal spaces and shared publication



A new observatory run returned **3,118 matches**. Its read-only archive, independently shared page, public metadata/report and permitted CSV were verified. DACP bytes reading returned **729,598 bytes** with the expected SHA-256; the records view returned 3,118 rows. A real `read_item@1.0` Job submitted to the Union read the same hosted result and returned 3,118 rows (Job `74a92337-dba9-4b8b-a8b5-cb260d1c2eee`). Withdrawing a separate test publication removed its Catalog entry and made its share API return HTTP 410. The test reader account was restricted to `reader` and disabled after verification. These are synthetic demonstration results, not scientific validation.

```bash
python3 scripts/smoke_personal_space.py --root /path/to/conet
python3 scripts/smoke_products.py --output /tmp/product-run.json
python3 scripts/smoke_products_browser.py --run /tmp/product-run.json --output /tmp/product-share.json
python3 scripts/smoke_hosted_products.py --product /tmp/product-share.json --root /path/to/conet --dacp-home /path/to/dacp-codex
```


## Anonymous workspace checks

The Scala suites cover anonymous authoring and storage, removed authentication routes, namespace persistence, origin checks, traversal and symlink rejection, quotas, immutable publication snapshots, and read-only result archives. Run `scripts/smoke_workspaces.py` for local HTTP and browser verification without credentials.

## Application packages and file inputs

`sbt test` covers package round trips, unsafe/oversized ZIP entries, file-reference validation, immutable per-run inputs, cross-application reference rejection, size/extension limits, expiration and hidden input Catalog access. The tabular plugin tests CSV quoting, multiline fields, missing values and header conflicts. `python3 scripts/smoke_uploaded_csv.py` imports and loads the CSV package, uploads a browser file, submits a real Union Job and verifies tables, statistics, every download, package export and desktop/mobile layouts. Its sample produces three rows and five columns. The shared hosted plugin must be redeployed when its Catalog implementation changes; the original seven data nodes and Union can remain running.
