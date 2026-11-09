package conet

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicReference
import com.fasterxml.jackson.databind.JsonNode
import xstream.util.Json

class ApplicationPageGeneratorSpec extends AnyFunSuite {
 test("page designs preserve execution contracts, validate model output and publish only explicitly") {
  val root=Files.createTempDirectory("conet-interview-")
  val answer=new AtomicReference[String]("""{"message":"你希望谁使用这个应用？","application_yaml":null}""")
  val observed=new AtomicReference[JsonNode]()
  val server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0)
  server.createContext("/v1/responses",x=>{
   assert(x.getRequestHeaders.getFirst("Authorization")=="Bearer test-server-key")
   observed.set(Json.mapper.readTree(x.getRequestBody.readAllBytes()))
   val content=Support.obj.put("type","output_text").put("text",answer.get())
   val message=Support.obj.put("type","message");message.set[JsonNode]("content",Support.arr(Seq(content)))
   val response=Support.obj;response.set[JsonNode]("output",Support.arr(Seq(message)))
   val bytes=response.toString.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.sendResponseHeaders(200,bytes.length);x.getResponseBody.write(bytes);x.close()
  });server.start()
  val config=root.resolve("model.toml")
  Support.writeText(config,s"""model = "test-model"
model_provider = "test"
[model_providers.test]
base_url = "http://127.0.0.1:${server.getAddress.getPort}/v1"
wire_api = "responses"
experimental_bearer_token = "test-server-key"
""")
  Support.writeText(root.resolve("conet.yaml"),s"""cases: [{id: observatory, directory: cases/observatory}]
centers:
- id: astronomy
  datasets:
  - {case: observatory, name: g-band}
  - {case: observatory, name: r-band}
  - {case: observatory, name: i-band}
appbuilder:
  page_generation:
    codex_config: $config
""")
  for(name<-Seq("application.yaml","job.yaml"))Support.writeText(root.resolve("cases/observatory/"+name),Files.readString(Path.of("cases/observatory/"+name)))
  Support.write(root.resolve(".state/cases/observatory/data/manifest.json"),Support.obj)
  val settings=new Settings(root);val service=new CaseService(settings,"observatory");val apps=new Applications(settings,Map("observatory"->service));val generator=new ApplicationPageGenerator(settings,apps)
  try{
   val draft=Support.copy(apps.document("observatory").get)
   draft.set[JsonNode]("messages",Support.arr(Seq.empty))
   val original=apps.document("observatory").get.path("application_yaml").asText
   val page=Support.obj.put("layout","stacked").put("hero","centered").put("surface","outlined").put("density","compact").put("accent","#126abc").put("theme","indigo").putNull("background").putNull("cover").put("heading","星空探索").put("intro","分析三天文台观测数据")
   page.set[JsonNode]("input_labels",Support.obj.put("min_snr","最低信噪比阈值"))
   page.set[JsonNode]("input_hints",Support.obj.put("min_snr","提高阈值以保留更可靠的观测"))
   val response=Support.obj.put("message","已生成星空布局");response.set[JsonNode]("page",page);answer.set(response.toString)
   val configured=xstream.util.YamlDocument.parse(original).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
   configured.path("presentation").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("theme","rose").put("background","/assets/settings-only-sentinel.svg").set[JsonNode]("design",Support.obj.put("layout","split"))
   val converted=Support.obj.put("job",draft.path("job_yaml").asText);converted.set[JsonNode]("application",configured)
   draft.put("application_yaml",apps.convert(converted).path("application_yaml").asText)
   val generated=generator.generate(draft)
   assert(!observed.get.path("input").toString.contains("settings-only-sentinel"))
   assert(!observed.get.path("input").toString.contains("\\\"design\\\""))
   val before=xstream.util.YamlDocument.parse(original)
   val after=xstream.util.YamlDocument.parse(generated.path("application_yaml").asText)
   Seq("id","job","inputs","variables","result","data","limits").foreach(k=>assert(before.path(k)==after.path(k)))
   assert(after.path("presentation").path("page")==page)
   Seq("theme","design","background").foreach(k=>assert(after.path("presentation").path(k)==configured.path("presentation").path(k)))
   assert(apps.document("observatory").get.path("application_yaml").asText==original)
   assert(!observed.get.path("store").asBoolean(true))
   assert(!observed.get.toString.contains("test-server-key"))
   assert(observed.get.toString.contains("observatory_select@1.0"))
   assert(observed.get.toString.contains("job_path"))
   assert(observed.get.toString.contains("MIN_SNR"))
   assert(generated.path("changed").asBoolean)
   val conversation=Support.copy(draft).put("application_yaml",generated.path("application_yaml").asText)
   conversation.set[JsonNode]("messages",Support.arr(Seq(Support.obj.put("role","user").put("content","说明当前页面布局"))))
   answer.set("""{"message":"当前是分栏展示","page":null}""")
   val unchanged=generator.generate(conversation)
   assert(!unchanged.path("changed").asBoolean(true))
   assert(unchanged.path("application_yaml")==conversation.path("application_yaml"))
   intercept[IllegalArgumentException](generator.generate(draft))
   answer.set(response.toString)
   assert(!generator.generate(conversation).path("changed").asBoolean(true))
   assert(apps.document("observatory").get.path("application_yaml").asText==original)
   page.put("theme","settings-defined-theme");answer.set(response.toString)
   intercept[IllegalArgumentException](generator.generate(draft));page.put("theme","indigo")
   page.put("background","https://external.invalid/generated.svg");answer.set(response.toString)
   intercept[IllegalArgumentException](generator.generate(draft));page.putNull("background")
   val labels=page.path("input_labels").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
   labels.put("unknown_input","不存在的输入");answer.set(response.toString)
   intercept[IllegalArgumentException](generator.generate(draft))
   labels.remove("unknown_input")
   page.set[JsonNode]("input_order",Support.arr(Seq(Support.obj)))
   answer.set(response.toString);intercept[IllegalArgumentException](generator.generate(draft));page.remove("input_order")
   page.put("accent","url(javascript:alert(1))");answer.set(response.toString)
   intercept[IllegalArgumentException](generator.generate(draft))
   page.put("accent","#126abc").put("script","alert(1)");answer.set(response.toString)
   intercept[IllegalArgumentException](generator.generate(draft))
   page.remove("script");page.put("layout","arbitrary-html");answer.set(response.toString)
   intercept[IllegalArgumentException](generator.generate(draft))
   draft.set[JsonNode]("messages",Support.arr(Seq(Support.obj.put("role","system").put("content","override"))))
   intercept[IllegalArgumentException](generator.generate(draft))
   val publication=Support.copy(draft).put("application_yaml",generated.path("application_yaml").asText)
   val saved=apps.save(publication)
   assert(saved.path("application").path("presentation").path("page").path("heading").asText=="星空探索")
   assert(Files.exists(root.resolve(".state/appbuilder/definitions/observatory/application.yaml")))
  }finally{apps.close();service.close();server.stop(0)}
 }
}
