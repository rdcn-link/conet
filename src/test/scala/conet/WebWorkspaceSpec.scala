package conet
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import java.net.{ServerSocket,URI}
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import xstream.util.Json
class WebWorkspaceSpec extends AnyFunSuite {
 test("shared authoring and storage have no workspace management or client namespace selection") {
  val root=Files.createTempDirectory("conet-workspace-http");val socket=new ServerSocket(0);val port=socket.getLocalPort;socket.close()
  Support.writeText(root.resolve("conet.yaml"),s"host: 127.0.0.1\nport: $port\ncases: []\ncenters: []\n")
  for(name<-Seq("products.html","products.js","products.css","product-robot.html","product-robot.js"))Support.writeText(root.resolve("web").resolve(name),Files.readString(java.nio.file.Path.of("web").resolve(name)))
  val web=new Web(new Settings(root));web.start();val http=HttpClient.newHttpClient()
  def call(path:String,body:Option[String]=None,space:String="",origin:String="")={val b=HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path"));if(space.nonEmpty)b.header("X-Conet-Workspace",space);if(origin.nonEmpty)b.header("Origin",origin);body.foreach(v=>b.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(v)));http.send(b.build(),HttpResponse.BodyHandlers.ofString())}
  try{
   assert(call("/workspace/").statusCode==200)
   for(p<-Seq("/account/","/api/account","/api/admin/users"))assert(call(p).statusCode==404)
   assert(call("/api/account/login",Some("{}")).statusCode==404)
   assert(call("/api/appbuilder/apps").statusCode==200)
   for(p<-Seq("/api/workspaces","/api/workspaces/resources")){assert(call(p).statusCode==404);assert(call(p,Some("{}")).statusCode==404)}
   assert(call("/products/").body.contains("数据产品"));assert(call("/product-robot/").body.contains("产品加工机器人"))
   assert(!Json.mapper.readTree(call("/api/product-robot").body).path("enabled").asBoolean)
   assert(call("/api/product-robot/settings",Some("{\"enabled\":true}"),origin="https://other.example").statusCode==403)
   assert(call("/api/product-robot/settings",Some("{\"enabled\":true,\"extra\":1}")).statusCode==400)
   assert(call("/api/product-robot/settings",Some("{\"enabled\":false}")).statusCode==200)
   assert(call("/api/product-robot/run",Some("{\"job\":{}}")).statusCode==400)
   assert(call("/api/products").body=="[]");assert(call("/api/analyses").body=="[]")
   assert(call("/api/storage/upload",Some("""{"path":"sample.txt","content_base64":"aGVsbG8="}""")).statusCode==201)
   assert(call("/api/storage/list",Some("{}"),space="unused").body.contains("sample.txt"))
   assert(call("/api/storage/list",Some("{}")).body.contains("sample.txt"))
   assert(call("/api/storage/upload",Some("""{"path":"../outside","content_base64":"aA=="}""")).statusCode==400)
   assert(call("/api/storage/upload",Some("{}"),origin="https://other.example").statusCode==403)
   assert(call("/appbuilder/assets/workspace-context.js").statusCode==404)

  }finally web.close()
 }
}
