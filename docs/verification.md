# Verification record

[中文](verification_zh.md) · [Home](../README.md)

## Shared-network execution

Verified locally on 2026-11-06 using one shared Union, three members, six published datasets and the full synthetic inputs. Both analyses were submitted through the CONET same-origin case gateway and executed by the actual DACP/Union/XStream services, not mocked Web timers.

| Case | Verified Union Job | Result |
|---|---|---|
| Check dams | `8b2f2e18-d37f-4a65-8a10-6e5062f54e10` | First tile's bounding box; 3 accepted candidates, 4 rejected |
| Observatories | `3e7b3e87-6f9f-4c90-a412-8fc8636ddc9f` | Cone `[150,2,0.15]`; 3118 three-band matches |

Subsequent smoke-test reruns confirmed the same counts. These are reproducibility checks on synthetic data, not scientific accuracy or performance claims. Each of the three members participated in both analyses. The topology reported receiver-observed inter-node payloads after completion. Full test IDs and latest output directories are recorded locally in `.state/smoke-results.json`.

## Automated checks

```bash
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/python scripts/smoke_web.py --url http://127.0.0.1:8870
```

The 15 unit/contract tests cover chunk fragmentation and empty chunks, alias-free repeated variables, graph source references, dataset ownership tags, entropy, slope units, topology cycles, coordinate-based matching independent of IDs, ambiguity rejection, right-ascension wrapping, missing bands, pairwise separation propagated color errors, and partial upstream failure with order-independent member mapping, and georeferenced preview pixels/cache reuse.

The smoke test submits both real analyses, waits for completion, retrieves every advertised artifact, verifies nonempty results, checks all three approved members and their participation counts, and rejects non-object and cross-origin requests. Browser checks cover portal, screen and both case pages, with no JavaScript exceptions. Host dependency checks report no broken installed requirements.

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

Run `python scripts/smoke_polygon.py --url http://127.0.0.1:8870` with the deployment Python environment. It rejects a self-intersecting request, executes a rectangle and a triangle over the first tile through the shared Union, and verifies every returned point against its Polygon. The rectangle yielded **3** valid sites and the triangle **2**. Nineteen contract tests passed, including envelope false positives, boundary inclusion, hole preservation under projection and the overlay membership guard. Browser checks covered polygon drawing, completion and vertex editing.

The five check-dam operators at version `1.1` were submitted as source-only packages and remain pending administrator approval. Their submission IDs are `1b44925a-24a0-4802-a235-e25fd36033b2` (detect), `bd86c3d9-53b5-489b-bcb1-40dcd0338132` (map), `68c0ba4b-574c-4d8e-8830-7cb781097e8d` (overlap), `835040fb-8da6-4ff3-bc8b-10b427906294` (susceptibility), and `71cea7dc-c7fc-4805-9c34-d0f076de97da` (terrain). Local execution uses the installed packages; public-download execution is not claimed.

## Seven-center network

All seven configured members were observed as approved, with ten published datasets and nonzero measured file volumes. The two real case jobs were repeated after expansion: check dams returned 3 valid candidates and astronomy returned 3118 matches. Browser checks at 1920×1080, 1440×900 and 1366×768 verified seven node entries, seven online markers, no overlapping callout rectangles and no page overflow. Nineteen contract tests passed. The four supplementary centers publish small reference datasets; the case inputs still originate from the first three centers.
