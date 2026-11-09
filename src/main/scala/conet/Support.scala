package conet

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import xstream.util.{Json,YamlDocument}
import java.net.{URI,InetSocketAddress}
import java.net.http.{HttpClient,HttpRequest,HttpResponse}
import java.nio.file.{Files,Path,StandardCopyOption}
import java.time.Duration
import java.util.concurrent.Executors
import scala.jdk.CollectionConverters._
import scala.util.Using

final class Settings(val root:Path){
 lazy val analysisScheduler:conet.appbuilder.AnalysisScheduler={
  val limits=config.path("appbuilder").path("execution")
  new conet.appbuilder.AnalysisScheduler(limits.path("max_running").asInt(2),limits.path("max_queued").asInt(16),limits.path("min_free_bytes").asLong(1024L*1024*1024))
 }
 val config=YamlDocument.parse(Files.readString(root.resolve("conet.yaml")))
 val state=root.resolve(".state");Files.createDirectories(state)
 val network=state.resolve("network")
 def cases:Vector[JsonNode]=config.path("cases").elements.asScala.toVector
 def centers:Vector[JsonNode]=config.path("centers").elements.asScala.toVector
 def caseState(id:String):Path=state.resolve("cases").resolve(id)
 def caseRoot(id:String):Path=root.resolve(cases.find(_.path("id").asText==id).get.path("directory").asText)
 def connection:JsonNode=Support.read(network.resolve("connection.json"))
 val dacpHome=Path.of(sys.env.getOrElse("DACP_HOME",root.getParent.resolve("dacp-codex").toString)).toAbsolutePath
 def publicHost:String=sys.env.getOrElse("CONET_PUBLIC_HOST",Using.resource(new java.net.DatagramSocket()){socket=>socket.connect(java.net.InetAddress.getByName("10.0.0.1"),80);socket.getLocalAddress.getHostAddress})
}
object Support {
 val http=HttpClient.newBuilder.connectTimeout(Duration.ofSeconds(3)).build()
 def read(path:Path):JsonNode=Json.mapper.readTree(Files.readAllBytes(path))
 def arr(values:Iterable[JsonNode]):JsonNode={val a=Json.mapper.createArrayNode();values.foreach(a.add);a}
 def obj=Json.obj()
 def copy(n:JsonNode):ObjectNode=n.deepCopy[ObjectNode]()
 def write(path:Path,value:JsonNode):Unit=writeText(path,Json.mapper.writerWithDefaultPrettyPrinter.writeValueAsString(value)+"\n")
 def writeText(path:Path,text:String):Unit={Files.createDirectories(path.getParent);Files.writeString(path,text)}
 def atomic(path:Path,value:JsonNode):Unit={Files.createDirectories(path.getParent);val temp=path.resolveSibling(path.getFileName.toString+".tmp");write(temp,value);Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
 def children(path:Path):Vector[Path]=if(!Files.isDirectory(path))Vector.empty else Using.resource(Files.list(path))(_.iterator.asScala.toVector)
 def walk(path:Path):Vector[Path]=if(!Files.exists(path))Vector.empty else Using.resource(Files.walk(path))(_.iterator.asScala.toVector)
 def get(url:String,seconds:Long=5):JsonNode={val response=http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(seconds)).GET.build,HttpResponse.BodyHandlers.ofByteArray());require(response.statusCode==200,"服务不可用");Json.mapper.readTree(response.body)}
 def localHttp(url:String):String={val u=URI.create(url);new URI("http",null,"127.0.0.1",u.getPort,null,null,null).toString}
 def now:String=java.time.Instant.now.toString
 def command(args:Seq[String],cwd:Path):String={val p=new ProcessBuilder(args:_*).directory(cwd.toFile).redirectError(ProcessBuilder.Redirect.INHERIT).start();val out=new String(p.getInputStream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);require(p.waitFor()==0,"Command failed: "+args.head);out}
 def run(args:Seq[String],cwd:Path):Unit={val p=new ProcessBuilder(args:_*).directory(cwd.toFile).inheritIO.start();require(p.waitFor()==0,"Command failed: "+args.head)}
}
