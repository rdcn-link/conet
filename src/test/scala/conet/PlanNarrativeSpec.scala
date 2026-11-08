package conet

import org.scalatest.funsuite.AnyFunSuite
import xstream.util.YamlDocument

class PlanNarrativeSpec extends AnyFunSuite {
 test("explanations identify source node, dataset and item, expand saved parameters and preserve YAML"){
  val plan=Support.obj.put("yaml","original snapshot");plan.set[com.fasterxml.jackson.databind.JsonNode]("job",YamlDocument.parse("""name: demo
steps:
- id: read
  operator: read_item@1.0
  inputs: {}
  params: {uri: 'dacp://127.0.0.1:19860/ds-demo/catalog.bundle', view: bytes}
- id: select
  operator: select@1.0
  inputs: {source: read}
  params: {min_snr: 5}
outputs: {result: select}
"""))
  val templates=YamlDocument.parse("""operators:
  select@1.0: '筛选{{/source_summary}}，信噪比至少为 {{/params/min_snr}}'
""")
  val datasets=Support.arr(Seq(Support.obj.put("dacp_uri","dacp://10.0.82.103:19860/ds-demo").put("title","g 波段星表").put("node_title","华中天文台")))
  val result=PlanNarrative.enrich(plan,templates,datasets,"10.0.82.103")
  val read=result.path("job").path("steps").get(0).path("explanation").asText
  assert(read.contains("华中天文台")&&read.contains("g 波段星表")&&read.contains("/catalog.bundle")&&read.contains("bytes"))
  val selection=result.path("job").path("steps").get(1).path("explanation").asText
  assert(selection.contains("g 波段星表")&&selection.contains("至少为 5"));assert(result.path("yaml").asText=="original snapshot")
  assert(!plan.path("job").path("steps").get(0).has("explanation"))
  val unavailable=PlanNarrative.enrich(plan,templates,Support.arr(Seq.empty),"10.0.82.103")
  assert(unavailable.path("job").path("steps").get(0).path("explanation").asText.contains("dacp://127.0.0.1:19860/ds-demo/catalog.bundle"))
 }
}
