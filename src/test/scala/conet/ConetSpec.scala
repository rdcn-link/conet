package conet

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import java.net.{ServerSocket,URI}
import java.net.http.{HttpRequest,HttpResponse}
import com.fasterxml.jackson.databind.JsonNode
import xstream.util.{Json,YamlDocument,WorkflowVariables}

class ConetSpec extends AnyFunSuite {
 private def json(value:String)=Json.mapper.readTree(value)
 private def validate(id:String,payload:JsonNode,m:JsonNode)=conet.appbuilder.Definition.load(Path.of("cases").resolve(id)).validate(payload,m)
 private val manifest=json("""{"bbox_wgs84":[100,20,110,40]}""")
 private val polygon="""{"type":"Polygon","coordinates":[[[101,21],[102,21],[102,22],[101,22],[101,21]]]}"""
 test("polygon topology rejects crossed rings, holes outside shell and malformed coordinates"){
  assert(conet.appbuilder.InputValidation.polygon(json(polygon)).getArea==1)
  Seq("""{"type":"Polygon","coordinates":[[[101,21],[102,22],[102,21],[101,22],[101,21]]]}""","""{"type":"Polygon","coordinates":[[[101,21],[102,21],[102,22],[101,22],[101,21]],[[103,23],[104,23],[104,24],[103,24],[103,23]]]}""").foreach(p=>intercept[IllegalArgumentException](conet.appbuilder.InputValidation.polygon(json(p))))
 }
 test("case input validation rejects nonobjects, out of coverage and invalid numerical types"){
  intercept[IllegalArgumentException](validate("check-dams",json("[]"),manifest))
  intercept[IllegalArgumentException](validate("check-dams",json(s"""{"region":$polygon,"min_confidence":"0.5"}"""),manifest))
  val accepted=validate("check-dams",json(s"""{"region":$polygon}"""),manifest);assert(accepted.path("min_confidence").asDouble==.35)
  intercept[IllegalArgumentException](validate("observatory",json("""{"cone":[360,2,0.1]}"""),manifest))
  assert(validate("observatory",json("""{"cone":[150,2,0.1]}"""),manifest).path("min_snr").asInt==5)
 }
 test("repeated structured variables expand without YAML aliases"){
  val result=WorkflowVariables.resolve("a: $CONE\nb: $CONE\n",json("""{"CONE":[150,2,0.15]}"""))
  assert(!result.contains("&id"));assert(YamlDocument.parse(result).path("a")==YamlDocument.parse(result).path("b"))
 }
 test("Scala web routes preserve case state, origin checks, static allowlists and volume deduplication"){
  val root=Files.createTempDirectory("conet-test-")
  def port():Int={val s=new ServerSocket(0);try s.getLocalPort finally s.close()}
  val p=port();val casePort=port()
  Support.writeText(root.resolve("conet.yaml"),s"""name: CONET
host: 127.0.0.1
port: $p
cases:
- id: check-dams
  directory: cases/check-dams
  port: $casePort
  title: 测试
centers:
- id: a
  datasets:
  - case: check-dams
    directory: a
    name: terrain
  - case: check-dams
    directory: a
    name: duplicate
""")
  Support.write(root.resolve(".state/network/connection.json"),json("""{"web_urls":{"union":"http://127.0.0.1:1"},"node_urls":{}}"""))
  Support.write(root.resolve(".state/cases/check-dams/data/manifest.json"),manifest)
  Support.writeText(root.resolve(".state/cases/check-dams/data/a/sample.txt"),"12345")
  Support.writeText(root.resolve("cases/check-dams/job.yaml"),Files.readString(Path.of("cases/check-dams/job.yaml")))
  Support.writeText(root.resolve("cases/check-dams/application.yaml"),Files.readString(Path.of("cases/check-dams/application.yaml")))
  Support.writeText(root.resolve("web/index.html"),"CONET")
  Support.write(root.resolve(".state/cases/check-dams/web-jobs/old.json"),json("""{"id":"old","state":"running","created_at":"2026-01-01"}"""))
  val web=new Web(new Settings(root))
  try{
   web.start()
   def request(path:String,body:Option[String]=None,origin:Option[String]=None)={val b=HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$p$path"));origin.foreach(b.header("Origin",_));body.foreach(v=>b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(v)));Support.http.send(b.build,HttpResponse.BodyHandlers.ofString())}
   assert(request("/api/config").statusCode==200)
   assert(request("/appbuilder/").body.contains("App Builder"))
   assert(request("/apps/check-dams/").statusCode==200)
   assert(Json.mapper.readTree(request("/api/appbuilder/apps").body).size==1)
   val document=Json.mapper.readTree(request("/api/appbuilder/apps/check-dams").body)
   val cloned=Support.copy(document);cloned.put("application_yaml",document.path("application_yaml").asText.replace("id: check-dams","id: published-test"));cloned.remove("revision")
   assert(request("/api/appbuilder/apps",Some(cloned.toString)).statusCode==201)
   assert(request("/apps/published-test/").statusCode==200)
   val publishedDocument=Json.mapper.readTree(request("/api/appbuilder/apps/published-test").body)
   val stateBody=Support.obj.put("revision",publishedDocument.path("revision").asText).put("status","offline")
   assert(request("/api/appbuilder/apps/published-test/status",Some(stateBody.toString),Some("https://other.example")).statusCode==403)
   assert(request("/api/appbuilder/apps/published-test/status",Some(stateBody.toString)).statusCode==200)
   assert(request("/apps/published-test/").statusCode==410)
   assert(request("/apps/published-test/api/jobs",Some("{}")).statusCode==410)
   assert(!Json.mapper.readTree(request("/api/config").body).path("applications").toString.contains("published-test"))
   stateBody.put("status","online")
   assert(request("/api/appbuilder/apps/published-test/status",Some(stateBody.toString)).statusCode==200)
   assert(request("/apps/published-test/").statusCode==200)

   assert(request("/api/appbuilder/apps",Some(cloned.toString)).statusCode==400)
   assert(request("/api/appbuilder/statistics").statusCode==200)
   val converted=Json.mapper.readTree(request("/api/appbuilder/convert",Some(Support.obj.put("application",document.path("application_yaml").asText).put("job",document.path("job_yaml").asText).toString)).body)
   assert(converted.path("application").path("id").asText=="check-dams")
   assert(converted.path("job").path("steps").size>0)
   val image=new java.awt.image.BufferedImage(10,10,java.awt.image.BufferedImage.TYPE_INT_RGB);val buffer=new java.io.ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",buffer)
   val uploaded=request("/api/appbuilder/media",Some(Support.obj.put("content_base64",java.util.Base64.getEncoder.encodeToString(buffer.toByteArray)).toString));assert(uploaded.statusCode==201)
   assert(request(Json.mapper.readTree(uploaded.body).path("url").asText).statusCode==200)
   assert(request("/api/appbuilder/media",Some("""{"content_base64":"aW52YWxpZA=="}""")).statusCode==400)
   assert(Json.mapper.readTree(request("/api/appbuilder/apps").body).elements.next().has("cover_url"))
   assert(request("/cases/check-dams/").statusCode==404)
   assert(request("/cases/check-dams/api/jobs").statusCode==404)
   assert(Json.mapper.readTree(request("/apps/check-dams/api/jobs/old").body).path("state").asText=="interrupted")
   assert(request("/apps/check-dams/api/jobs",Some("[]")).statusCode==400)
   assert(request("/apps/check-dams/api/jobs",Some("{}"),Some("https://other.example")).statusCode==403)
   assert(request("/apps/check-dams/results/old/connection.json").statusCode==404)
   assert(request("/api/union-jobs/../../secret").statusCode==400)
   assert(web.snapshot().path("center_volumes").path("a").path("data_bytes").asLong==5)
   assert(request("/api/appbuilder/apps/published-test/delete",Some(stateBody.toString)).statusCode==200)
   assert(request("/apps/published-test/").statusCode==410)
   assert(request("/api/appbuilder/apps/published-test").statusCode==404)

  }finally web.close()
 }
 test("dataset configuration cannot escape the case data directory"){
  val root=Files.createTempDirectory("conet-dataset-");Support.writeText(root.resolve("conet.yaml"),"cases: []\ncenters: []\n")
  Files.createDirectories(root.resolve(".state/cases/test/data"))
  intercept[IllegalArgumentException](Network.datasets(new Settings(root),json("""{"id":"a","datasets":[{"case":"test","name":"bad","directory":"../../.."}]}""")))
 }
 test("operator packaging supports configured linked assets and preserves a valid archive on failure"){
  val root=Files.createTempDirectory("conet-package-");val state=root.resolve(".state/cases/sample")
  Support.writeText(root.resolve("conet.yaml"),"cases: [{id: sample, directory: cases/sample}]\ncenters: []\n")
  Support.writeText(root.resolve("cases/sample/manifests/sample.yaml"),"name: sample\nversion: '1.0'\n")
  Support.writeText(root.resolve("cases/sample/operator/analysis.py"),"value = 1\n")
  Support.writeText(root.resolve("cases/sample/packaging.yaml"),"assets: {sample: {model.pt: model/model.pt}}\n")
  val external=Files.createTempDirectory("conet-model-");Support.writeText(external.resolve("model.pt"),"weights")
  Files.createDirectories(state);Files.createSymbolicLink(state.resolve("model"),external)
  val settings=new Settings(root);Operators.build(settings)
  val archive=state.resolve("operators/sample/1.0/analysis.zip");val original=Files.readAllBytes(archive)
  scala.util.Using.resource(new java.util.zip.ZipFile(archive.toFile)){zip=>assert(zip.getEntry("model.pt")!=null);assert(zip.getEntry("analysis.py")!=null)}
  Files.delete(external.resolve("model.pt"));intercept[IllegalArgumentException](Operators.build(settings))
  assert(java.util.Arrays.equals(original,Files.readAllBytes(archive)))
 }

}
