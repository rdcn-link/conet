package conet

import java.nio.file.{Files,Path}
import java.net.{InetAddress,ServerSocket,URI}
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

/** Operator-managed lifecycle for the one shared publication node. */
object HostedNode {
 private def home(s:Settings)=s.state.resolve("hosted-node")
 private def record(s:Settings)=home(s).resolve("process.json")
 private def freePort():Int={val socket=new ServerSocket(0,0,InetAddress.getLoopbackAddress);try socket.getLocalPort finally socket.close()}
 private def command(s:Settings,args:String*)=Support.command(args.toSeq,s.root)
 def status(s:Settings):JsonNode=if(Files.exists(record(s))){val n=Support.copy(Support.read(record(s)));n.put("running",Manager.alive(n.path("pid").asLong));n}else Support.obj.put("running",false)
 private def checkIdle(s:Settings):Unit={val base=Support.localHttp(s.connection.path("web_urls").path("union").asText);val n=Support.get(base+"/api/overview");require(n.path("active_jobs").asInt(-1)==0,"Union 有运行中的任务，请完成后再配置共享托管节点")}
 private[conet] def restartUnion(s:Settings):Unit={
  val path=s.network.resolve("processes.json");val processes=Support.copy(Support.read(path));val pid=processes.path("union").asLong
  if(Manager.alive(pid)){require(Files.readString(Path.of(s"/proc/$pid/cmdline")).contains(s.network.toString),"拒绝停止无关的 Union 进程");Support.run(Seq("sudo","-n","kill","-TERM","--",s"-$pid"),s.root);val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(25);while(Manager.alive(pid)&&System.nanoTime()<until)Thread.sleep(100);require(!Manager.alive(pid),"Union 未停止")}
  val program=s.dacpHome.resolve("dacp-union-server/target/universal/stage/bin/dacp-union-server")
  val process=new ProcessBuilder("setsid","sudo","-n",program.toString,"serve",s.network.resolve("union.yaml").toString).directory(s.root.toFile).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(s.network.resolve("union.log").toFile)).start();processes.put("union",process.pid);Support.atomic(path,processes)
  val base=Support.localHttp(s.connection.path("web_urls").path("union").asText);val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);var ready=false;while(!ready&&Manager.alive(process.pid)&&System.nanoTime()<until){ready=scala.util.Try(Support.get(base+"/api/overview",2)).isSuccess;if(!ready)Thread.sleep(200)};require(ready,"Union 重启失败，请检查 union.log")
 }
 def up(s:Settings):Unit={
  require(!status(s).path("running").asBoolean,"共享托管节点已经运行");checkIdle(s)
  val storage=new HostedStorage(s);val h=home(s);Files.createDirectories(h);val plugins=h.resolve("plugins");Files.createDirectories(plugins)
  val jar=Support.children(s.root.resolve("conet-hosting/target/scala-2.13")).find(p=>p.getFileName.toString.matches("conet-hosting_2.13-[0-9].*\\.jar")&& !p.toString.endsWith("-sources.jar")).getOrElse(throw new IllegalArgumentException("请先运行 sbt hosting/packageBin"))
  Files.copy(jar,plugins.resolve("conet-hosting.jar"),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
  val ca=s.network.resolve("tls");val cert=h.resolve("node.pem");val key=h.resolve("node.key")
  if(!Files.exists(cert)){Support.writeText(h.resolve("extensions.cnf"),s"subjectAltName=DNS:localhost,IP:127.0.0.1,IP:${s.publicHost}\nextendedKeyUsage=serverAuth,clientAuth\n");command(s,"openssl","req","-newkey","rsa:2048","-nodes","-keyout",key.toString,"-out",h.resolve("node.csr").toString,"-subj","/CN=conet-hosted");command(s,"openssl","x509","-req","-in",h.resolve("node.csr").toString,"-CA",ca.resolve("ca.pem").toString,"-CAkey",ca.resolve("ca.key").toString,"-CAcreateserial","-days","365","-extfile",h.resolve("extensions.cnf").toString,"-out",cert.toString);Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"))}
  val configFile=h.resolve("node.yaml");val config=if(Files.exists(configFile))xstream.util.YamlDocument.parse(Files.readString(configFile)).deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]()else{
   val n=Support.obj.put("host","0.0.0.0").put("public_host",s.publicHost).put("port",freePort()).put("tls_cert",cert.toString).put("tls_key",key.toString).put("tls_ca",ca.resolve("ca.pem").toString).put("credential_directory",h.resolve("credentials").toString).put("plugin_directory",plugins.toString)
   n.putObject("web").put("host","127.0.0.1").put("port",freePort());n.putObject("s3").put("enabled",false);n.putObject("data_catalog").put("provider","conet-hosted").putObject("source").put("root",storage.root.toString);n.putObject("union").put("state_directory",h.resolve("union").toString);n
  };config.put("local_operator_library",s.network.resolve("operators").toString).put("operator_cache",h.resolve("operator-cache").toString)
  config.putObject("execution_store").put("provider","file").putObject("settings").put("directory",h.resolve("executions").toString)
  config.putObject("access_log").put("directory",h.resolve("access").toString)
  Support.writeText(configFile,xstream.util.YamlDocument.dump(config))
  val admin=s.dacpHome.resolve("dacp-server/target/universal/stage/bin/dacp-admin");val unionAdmin=s.dacpHome.resolve("dacp-union-server/target/universal/stage/bin/union-admin")
  val who=xstream.util.Json.mapper.readTree(command(s,unionAdmin.toString,"identity",s.network.resolve("union.yaml").toString))
  val grant=xstream.util.Json.mapper.readTree(command(s,admin.toString,"grant-union",h.resolve("credentials").toString,"--subject","conet-union","--union-id",who.path("union_id").asText,"--fingerprint",who.path("scheduler_identity").asText))
  val token=h.resolve("union.token");if(!Files.exists(token))Files.createFile(token,java.nio.file.attribute.PosixFilePermissions.asFileAttribute(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));Files.writeString(token,grant.path("token").asText+"\n")
  val server=s.dacpHome.resolve("dacp-server/target/universal/stage/bin/dacp-server");val process=new ProcessBuilder("setsid","sudo","-n",server.toString,"-J-Xms64m","-J-Xmx512m",configFile.toString).directory(s.root.toFile).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(h.resolve("node.log").toFile)).start()
  val nodeUrl=s"dacp://${s.publicHost}:${config.path("port").asInt}";Support.atomic(record(s),Support.obj.put("pid",process.pid).put("url",nodeUrl).put("config",configFile.toString))
  val nodeWeb=s"http://127.0.0.1:${config.path("web").path("port").asInt}";val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);var ready=false
  while(!ready&&Manager.alive(process.pid)&&System.nanoTime()<until){ready=scala.util.Try(Support.get(nodeWeb+"/api/overview",2)).isSuccess;if(!ready)Thread.sleep(200)};require(ready,"托管节点未启动，请检查 .state/hosted-node/node.log")
  val path=s.network.resolve("union.yaml");val union=xstream.util.YamlDocument.parse(Files.readString(path)).deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]();val members=union.path("members").asInstanceOf[com.fasterxml.jackson.databind.node.ArrayNode];if(!members.elements.asScala.exists(_.path("url").asText==nodeUrl)){members.add(Support.obj.put("url",nodeUrl).put("credential_file",token.toString));checkIdle(s);Support.writeText(path,xstream.util.YamlDocument.dump(union));restartUnion(s)}
  println("Shared hosted node: "+nodeUrl)
 }
 def down(s:Settings):Unit={if(status(s).path("running").asBoolean){checkIdle(s);stop(s)}}
 def stop(s:Settings):Unit={if(!status(s).path("running").asBoolean)return;val n=Support.read(record(s));val pid=n.path("pid").asLong;require(Files.readString(Path.of(s"/proc/$pid/cmdline")).contains(home(s).toString),"拒绝停止无关进程");Support.run(Seq("sudo","-n","kill","-TERM","--",s"-$pid"),s.root);println("Shared hosted node stopped; its Union membership remains configured")}
}
