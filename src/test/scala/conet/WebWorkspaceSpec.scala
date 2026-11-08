package conet
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import java.net.{ServerSocket,URI}
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import xstream.util.Json
class WebWorkspaceSpec extends AnyFunSuite {
 test("anonymous authoring and storage use selectable namespaces while obsolete identity routes disappear") {
  val root=Files.createTempDirectory("conet-workspace-http");val socket=new ServerSocket(0);val port=socket.getLocalPort;socket.close()
  Support.writeText(root.resolve("conet.yaml"),s"host: 127.0.0.1\nport: $port\ncases: []\ncenters: []\n")
  val web=new Web(new Settings(root));web.start();val http=HttpClient.newHttpClient()
  def call(path:String,body:Option[String]=None,space:String="",origin:String="")={val b=HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path"));if(space.nonEmpty)b.header("X-Conet-Workspace",space);if(origin.nonEmpty)b.header("Origin",origin);body.foreach(v=>b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(v)));http.send(b.build(),HttpResponse.BodyHandlers.ofString())}
  try{
   assert(call("/workspace/").statusCode==200)
   for(p<-Seq("/account/","/api/account","/api/admin/users"))assert(call(p).statusCode==404)
   assert(call("/api/account/login",Some("{}")).statusCode==404)
   assert(call("/api/appbuilder/apps").statusCode==200)
   val created=call("/api/workspaces",Some("""{"name":"研究空间"}"""));assert(created.statusCode==201);assert(created.headers.firstValue("Set-Cookie").isEmpty)
   val id=Json.mapper.readTree(created.body).path("id").asText
   assert(call("/api/storage/upload",Some("""{"path":"sample.txt","content_base64":"aGVsbG8="}"""),id).statusCode==201)
   assert(call("/api/storage/list",Some("{}"),id).body.contains("sample.txt"))
   assert(call("/api/storage/list",Some("{}")).body=="[]")
   assert(call("/api/storage/upload",Some("""{"path":"../outside","content_base64":"aA=="}"""),id).statusCode==400)
   assert(call("/api/workspaces",Some("""{"name":"bad"}"""),origin="https://other.example").statusCode==403)
   assert(call("/api/storage/summary",space="missing").statusCode==400)
  }finally web.close()
 }
}
