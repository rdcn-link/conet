package conet

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import java.net.InetSocketAddress
import com.sun.net.httpserver.HttpServer
import java.util.concurrent.atomic.AtomicReference
import com.fasterxml.jackson.databind.JsonNode
import xstream.util.Json

class ApplicationChatSpec extends AnyFunSuite {
 test("interview uses server credentials, validates proposals and rejects data rebinding without publishing") {
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
  chat:
    codex_config: $config
""")
  for(name<-Seq("application.yaml","job.yaml"))Support.writeText(root.resolve("cases/observatory/"+name),Files.readString(Path.of("cases/observatory/"+name)))
  Support.write(root.resolve(".state/cases/observatory/data/manifest.json"),Support.obj)
  val settings=new Settings(root);val service=new CaseService(settings,"observatory");val apps=new Applications(settings,Map("observatory"->service));val chat=new ApplicationChat(settings,apps)
  try{
   val draft=Support.copy(apps.document("observatory").get)
   draft.set[JsonNode]("messages",Support.arr(Seq.empty))
   assert(chat.reply(draft).path("message").asText.contains("谁"))
   assert(!observed.get.path("store").asBoolean(true))
   assert(!observed.get.toString.contains("test-server-key"))
   val proposed=draft.path("application_yaml").asText.replace("三天文台多波段星表交叉匹配与颜色分析","面向学生的天文分析")
   answer.set(Support.obj.put("message","配置已准备好，请确认。 ").put("application_yaml",proposed).toString)
   intercept[IllegalArgumentException](chat.reply(draft)) // never generate on the initial question
   draft.set[JsonNode]("messages",Support.arr(Seq(
    Support.obj.put("role","assistant").put("content","面向谁？"),Support.obj.put("role","user").put("content","学生"),
    Support.obj.put("role","assistant").put("content","需要什么输出？"),Support.obj.put("role","user").put("content","保留当前图表，生成配置"))))
   assert(chat.reply(draft).path("valid").asBoolean)
   assert(apps.document("observatory").get.path("application_yaml").asText==draft.path("application_yaml").asText)
   answer.set(Support.obj.put("message","修改数据").put("application_yaml",proposed.replace("scope: observatory","scope: emergency")).toString)
   val rejected=chat.reply(draft);assert(!rejected.path("valid").asBoolean);assert(!rejected.has("application_yaml"));assert(rejected.has("validation_error"))
   draft.set[JsonNode]("messages",Support.arr(Seq(Support.obj.put("role","system").put("content","不允许浏览器设置系统提示"))))
   intercept[IllegalArgumentException](chat.reply(draft))
   answer.set("not valid json");draft.set[JsonNode]("messages",Support.arr(Seq.empty));intercept[IllegalStateException](chat.reply(draft))
  }finally{apps.close();service.close();server.stop(0)}
 }
}
