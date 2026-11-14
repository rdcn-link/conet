package conet

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import conet.appbuilder.{ProductMetadata,Values}
import com.fasterxml.jackson.databind.JsonNode
import xstream.util.{Json,YamlDocument}

class ProductsSpec extends AnyFunSuite {
 test("publications preserve provenance, gate rights and downloads, survive source deletion and support expiry/revocation") {
  val root=Files.createTempDirectory("conet-products-")
  Support.writeText(root.resolve("conet.yaml"),"cases: [{id: observatory, directory: cases/observatory}]\ncenters: []\nappbuilder: {products: {directory: published, retention_days: 7}}\n")
  val config=Files.readString(Path.of("cases/observatory/application.yaml"));val job=Files.readString(Path.of("cases/observatory/job.yaml"))
  Support.writeText(root.resolve("cases/observatory/application.yaml"),config);Support.writeText(root.resolve("cases/observatory/job.yaml"),job)
  val base=root.resolve(".state/cases/observatory");Support.write(base.resolve("data/manifest.json"),Values.obj)
  val vars=Json.mapper.readTree("""{"CONE":[150,2,0.1],"MATCH_RADIUS_ARCSEC":0.8,"MIN_SNR":5,"G_CATALOG_URI":"dacp://node:1/g/catalog.bundle","R_CATALOG_URI":"dacp://node:1/r/catalog.bundle","I_CATALOG_URI":"dacp://node:1/i/catalog.bundle","API_TOKEN":"must-not-appear"}""")
  val result=Json.mapper.readTree("""{"rows":[],"scanned_rows":0,"band_counts":{"g":0,"r":0,"i":0}}""")
  val record=Values.obj.put("id","test-run").put("state","succeeded").put("created_at","2026-01-01T00:00:00Z").put("started_at","2026-01-01T00:00:01Z").put("finished_at","2026-01-01T00:00:04Z").put("synthetic",true).put("job_yaml",job)
  record.set[JsonNode]("definition",YamlDocument.parse(config));record.set[JsonNode]("variables",vars);record.set[JsonNode]("result",result);record.set[JsonNode]("cone",vars.path("CONE"));record.set[JsonNode]("summary",Values.obj.put("matched_sources",0))
  record.set[JsonNode]("dataset_bindings",Support.arr(Seq("g","r","i").map(b=>Values.obj.put("uri",s"dacp://node:1/$b").put("name",b+"-band"))))
  record.set[JsonNode]("artifacts",Values.obj.put("csv","/cases/observatory/results/test-run/matches.csv").put("analysis","/cases/observatory/results/test-run/analysis.json"))
  Support.write(base.resolve("web-jobs/test-run.json"),record);Support.write(base.resolve("results/test-run/analysis.json"),result);Support.writeText(base.resolve("results/test-run/matches.csv"),"source_id,ra,dec\n")
  val service=new CaseService(new Settings(root),"observatory");val products=new Products(new Settings(root))
  try{
   val preview=products.defaults(service.application,"test-run");val metadata=preview.path("metadata")
   assert(metadata.path("timing").path("duration_ms").asLong==3000)
   assert(metadata.path("sources").size==3);assert(metadata.path("operators").size==7);assert(!metadata.toString.contains("must-not-appear"))
   val request=Values.obj.put("confirmed",true).put("publisher_name","测试发布者");request.set[JsonNode]("rights",preview.path("rights"));request.set[JsonNode]("downloads",Support.arr(Seq(Json.mapper.getNodeFactory.textNode("csv"))))
   intercept[IllegalArgumentException](products.publish(service.application,"test-run",request,"dacp://127.0.0.1:1234"))
   val rights=Values.copy(preview.path("rights"));val app=Values.copy(rights.path("application")).put("name","应用作者").put("license","演示许可").put("view","allowed").put("download","allowed").put("commercial","denied");rights.set[JsonNode]("application",app)
   rights.path("sources").elements().forEachRemaining(s=>s.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("rights_holder","数据权利人").put("license","演示许可").put("view","allowed").put("download","allowed").put("commercial","denied"))
   request.set[JsonNode]("rights",rights);val automation=Values.obj.put("candidate_id","a"*32).put("title","自主加工成果").put("purpose","多波段质量分析").put("review_note","已核对来源和业务方法");automation.set[JsonNode]("research",Values.obj.put("question","星表匹配质量如何"));val published=products.publish(service.application,"test-run",request,"dacp://127.0.0.1:1234",Some(automation));val id=published.path("id").asText
   val document=products.document(id).get;assert(document.path("automation").path("candidate_id").asText=="a"*32);assert(document.path("title").asText=="自主加工成果");assert(Files.readString(products.download(id,"report.md").get).contains("已核对来源和业务方法"));assert(published.path("dataset_uri").asText=="dacp://127.0.0.1:1234/result-"+id);assert(!published.has("url"));assert(document.path("metadata_uri").asText.endsWith("/metadata.json"));assert(document.path("artifacts").get(0).path("item_uri").asText.startsWith(published.path("dataset_uri").asText));assert(!published.has("manage_token"));assert(document.path("rights").path("verification").asText=="self_declared");assert(document.path("rights").path("commercial_use").asText=="not_granted")
   val storage=new HostedStorage(new Settings(root));storage.captureRun(service.application,"test-run");assert(storage.resultDatasets.get(0).path("readonly").asBoolean);assert(storage.resultDatasets.get(0).path("url").asText.endsWith("?run=test-run"))
   val archived=storage.resultFile("observatory-test-run","matches.csv");assert(Files.isRegularFile(archived));intercept[IllegalArgumentException](storage.resultFile("observatory-test-run","publication.json"))
   val dataset=storage.publishProduct(id,products);val publicFolder=storage.root.resolve(dataset.path("directory").asText);assert(Files.exists(publicFolder.resolve("matches.csv")));assert(!Files.exists(publicFolder.resolve("analysis.json")));assert(Files.exists(publicFolder.resolve("metadata.json")))
   storage.unpublish(dataset.path("id").asText)
   assert(products.download(id,"matches.csv").nonEmpty);assert(products.download(id,"analysis.json").isEmpty);assert(products.download(id,"publication.json").isEmpty);assert(products.download(id,"../publication.json").isEmpty)
   assert(products.download(id,"metadata.json").nonEmpty);assert(products.data(id).nonEmpty)
   val expiring=products.publish(service.application,"test-run",request,"dacp://127.0.0.1:1234");val expiredId=expiring.path("id").asText
   val expiryFile=products.directory.resolve(expiredId).resolve("publication.json");val expiry=Values.copy(Support.read(expiryFile)).put("expires_at","2000-01-01T00:00:00Z");Support.write(expiryFile,expiry)
   assert(products.document(expiredId).isEmpty);assert(products.download(expiredId,"metadata.json").isEmpty)
   Files.delete(base.resolve("results/test-run/matches.csv"));assert(Files.readString(products.download(id,"matches.csv").get).startsWith("source_id"))
   assert(products.document(id).nonEmpty)
   val restored=new Products(new Settings(root));assert(restored.document(id).nonEmpty);assert(restored.revoke(id));assert(restored.document(id).isEmpty);assert(restored.download(id,"matches.csv").isEmpty)
   // Explicit denials in the saved application cannot be removed in a publication request.
   Support.writeText(base.resolve("results/test-run/matches.csv"),"source_id,ra,dec\n")
   val defn=YamlDocument.parse(config).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode];val configured=Values.obj;configured.set[JsonNode]("application",Values.obj.put("view","denied"));defn.set[JsonNode]("publication",configured)
   Support.writeText(root.resolve("cases/observatory/application.yaml"),YamlDocument.dump(defn))
   val newRecord=Values.copy(record);newRecord.set[JsonNode]("definition",defn);Support.write(base.resolve("web-jobs/test-run.json"),newRecord)
   val deniedService=new CaseService(new Settings(root),"observatory")
   try intercept[IllegalArgumentException](products.publish(deniedService.application,"test-run",request,"dacp://127.0.0.1:1234"))finally deniedService.close()
  }finally service.close()
 }
}
