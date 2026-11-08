package conet

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.{Files,Path}
import java.net.{ServerSocket,Socket,InetSocketAddress}
import java.nio.file.attribute.PosixFilePermissions
import scala.jdk.CollectionConverters._
import scala.util.Using
import xstream.util.{Json,YamlDocument}
import dacp.client.{Source,TlsConfig}

object Network {
 private def objectOf(fields:(String,Any)*):ObjectNode={val n=Support.obj;fields.foreach{case(k,v)=>n.set[JsonNode](k,v match{case j:JsonNode=>j;case b:Boolean=>Json.mapper.getNodeFactory.booleanNode(b);case i:Int=>Json.mapper.getNodeFactory.numberNode(i);case l:Long=>Json.mapper.getNodeFactory.numberNode(l);case other=>Json.mapper.getNodeFactory.textNode(other.toString)})};n}
 private def freePort():Int=Using.resource(new ServerSocket(0,0,java.net.InetAddress.getLoopbackAddress))(_.getLocalPort)
 private def secret(path:Path,value:String):Unit={Files.createDirectories(path.getParent);if(!Files.exists(path))Files.createFile(path,PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));Files.setPosixFilePermissions(path,PosixFilePermissions.fromString("rw-------"));Files.writeString(path,value+"\n")}
 private def yaml(path:Path,n:JsonNode):Unit=Support.writeText(path,YamlDocument.dump(n))
 def catalogUp(s:Settings):Unit={
  val base=Support.localHttp(s.connection.path("web_urls").path("union").asText);require(Support.get(base+"/api/overview").path("active_jobs").asInt(-1)==0,"请等待 Union 活动任务完成后再启用目录 S3")
  val path=s.network.resolve("union.yaml");val n=Support.copy(YamlDocument.parse(Files.readString(path)));val current=n.path("s3");val changed= !current.path("enabled").asBoolean||current.path("port").asInt<=0||n.path("public_url").asText!=s"dacp://${s.publicHost}:${n.path("port").asInt}"
  if(changed){val port=if(current.path("port").asInt>0)current.path("port").asInt else freePort();n.put("host","0.0.0.0").put("public_url",s"dacp://${s.publicHost}:${n.path("port").asInt}");n.set[JsonNode]("s3",objectOf("enabled"->true,"host"->"0.0.0.0","port"->port));yaml(path,n)}
  Using.resource(new dacp.union.Credentials(Path.of(n.path("credential_directory").asText))){store=>if(!store.list.exists(c=>c.path("kind").asText=="s3"&&c.path("subject").asText=="conet-catalog"))store.createAccount("conet-catalog","reader",0)}
  if(changed)HostedNode.restartUnion(s)
  println("Catalog S3: https://"+s.publicHost+":"+n.path("s3").path("port").asInt)
 }
 def datasets(s:Settings,center:JsonNode):ObjectNode={val out=Support.obj;center.path("datasets").elements.asScala.foreach{entry=>
  val id=entry.path("case").asText;val base=s.caseState(id).resolve("data").toRealPath();val directory=base.resolve(entry.path("directory").asText(center.path("id").asText)).normalize
  require(directory.startsWith(base)&&Files.isDirectory(directory)&&directory.toRealPath().startsWith(base),"Dataset must be inside case data: "+directory)
  val description=objectOf("title"->entry.path("title").asText,"description"->entry.path("description").asText,"case"->id,"center"->center.path("id").asText,"dataset"->entry.path("name").asText,"synthetic"->true,"publication"->objectOf("policy"->"public"))
  out.set[JsonNode](entry.path("name").asText,objectOf("provider"->"directory","description"->Json.mapper.writeValueAsString(description),"source"->objectOf("root"->directory.toString)))
 };out}
 def stop(s:Settings):Unit={val path=s.network.resolve("processes.json");if(!Files.exists(path))return
  val remaining=Support.obj
  Support.read(path).fields.asScala.foreach{record=>val pid=record.getValue.asLong
   if(Manager.alive(pid)){
    val cmd=Files.readString(Path.of(s"/proc/$pid/cmdline"));require(cmd.contains(s.network.toString),s"Refusing to stop unrelated PID $pid")
    Support.run(Seq("sudo","-n","kill","-TERM","--",s"-$pid"),s.root)
    val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(Manager.alive(pid)&&System.nanoTime()<deadline)Thread.sleep(100)
    if(Manager.alive(pid))remaining.set[JsonNode](record.getKey,record.getValue)
   }
  };Support.write(path,remaining);require(remaining.isEmpty,"Some network processes are still stopping")
 }
 def up(s:Settings):Unit={
  val path=s.network.resolve("processes.json");require(!Files.exists(path)||Support.read(path).isEmpty,"Network processes already registered; stop CONET first")
  Files.createDirectories(s.network)
  ReferenceData.prepare(s)
  val library=Operators.build(s)
  Seq("xstream/check-dams-demo:1.0","xstream/observatory-demo:1.0").foreach(image=>Support.command(Seq("sudo","-n","docker","image","inspect",image),s.root))
  val tls=s.network.resolve("tls");Files.createDirectories(tls)
  def openssl(args:String*):Unit={Support.command(Seq("openssl")++args,s.root);()}
  if(!Files.exists(tls.resolve("ca.pem")))openssl("req","-x509","-newkey","rsa:2048","-nodes","-days","365","-keyout",tls.resolve("ca.key").toString,"-out",tls.resolve("ca.pem").toString,"-subj","/CN=CONET synthetic CA")
  Support.writeText(tls.resolve("extensions.cnf"),s"subjectAltName=DNS:localhost,IP:127.0.0.1,IP:${s.publicHost}\nextendedKeyUsage=serverAuth,clientAuth\n")
  val names=s.centers.map(_.path("id").asText);require(names.nonEmpty&&names.distinct.size==names.size&&names.forall(n=>n.matches("[a-z][a-z0-9-]*")&&n!="union"),"Invalid center identifiers")
  (names:+"union").foreach{name=>if(!Files.exists(tls.resolve(name+".pem"))){
   openssl("req","-newkey","rsa:2048","-nodes","-keyout",tls.resolve(name+".key").toString,"-out",tls.resolve(name+".csr").toString,"-subj","/CN=conet-"+name)
   openssl("x509","-req","-in",tls.resolve(name+".csr").toString,"-CA",tls.resolve("ca.pem").toString,"-CAkey",tls.resolve("ca.key").toString,"-CAcreateserial","-days","365","-extfile",tls.resolve("extensions.cnf").toString,"-out",tls.resolve(name+".pem").toString)
  }}
  Support.children(tls).filter(_.toString.endsWith(".key")).foreach(p=>Files.setPosixFilePermissions(p,PosixFilePermissions.fromString("rw-------")))
  val allocated=scala.collection.mutable.Set.empty[Int]
  def port():Int={var p=freePort();while(!allocated.add(p))p=freePort();p}
  val ports=(names:+"union").map(_->port()).toMap;val web=(names:+"union").map(_->port()).toMap
  val node=s.dacpHome.resolve("dacp-server/target/universal/stage/bin/dacp-server");val union=s.dacpHome.resolve("dacp-union-server/target/universal/stage/bin/dacp-union-server")
  def local(program:Path,args:String*):JsonNode=Json.mapper.readTree(Support.command(Seq(program.toString)++args,s.root))
  def base(name:String):ObjectNode=objectOf("host"->"127.0.0.1","port"->ports(name),"tls_cert"->tls.resolve(name+".pem").toString,"tls_key"->tls.resolve(name+".key").toString,"tls_ca"->tls.resolve("ca.pem").toString,"credential_directory"->s.network.resolve(name+"-credentials").toString,"web"->objectOf("host"->"0.0.0.0","port"->web(name)),"s3"->objectOf("enabled"->false))
  val u=base("union");u.put("public_url",s"dacp://127.0.0.1:${ports("union")}").put("state_directory",s.network.resolve("union").toString).put("poll_interval_ms",500).put("execution_timeout_seconds",300).put("retention_seconds",3600)
  val members=u.putArray("members");yaml(s.network.resolve("union.yaml"),u)
  val identity=local(union.resolveSibling("union-admin"),"identity",s.network.resolve("union.yaml").toString)
  s.centers.foreach{center=>val name=center.path("id").asText
   val grant=local(node.resolveSibling("dacp-admin"),"grant-union",s.network.resolve(name+"-credentials").toString,"--subject","conet-union","--union-id",identity.path("union_id").asText,"--fingerprint",identity.path("scheduler_identity").asText)
   val token=s.network.resolve(name+"-union.token");secret(token,grant.path("token").asText)
   members.add(objectOf("url"->s"dacp://127.0.0.1:${ports(name)}","credential_file"->token.toString))
   val n=base(name);n.put("public_host","127.0.0.1").put("local_operator_library",library.toString).put("operator_cache",s.network.resolve(name+"-cache").toString).put("max_concurrent_tasks",16)
   n.set[JsonNode]("datasets",datasets(s,center));n.putArray("remote_operator_library_urls")
   n.set[JsonNode]("execution_store",objectOf("provider"->"file","settings"->objectOf("directory"->s.network.resolve(name+"-executions").toString)))
   n.set[JsonNode]("union",objectOf("state_directory"->s.network.resolve(name+"-union").toString,"execution_timeout_seconds"->300));n.set[JsonNode]("access_log",objectOf("directory"->s.network.resolve(name+"-access").toString))
   yaml(s.network.resolve(name+".yaml"),n)
  };yaml(s.network.resolve("union.yaml"),u)
  if(!Files.exists(s.network.resolve("app.token"))){val account=local(union.resolveSibling("union-admin"),"create-account",s.network.resolve("union-credentials").toString,"--subject","conet-app","--role","runner");secret(s.network.resolve("app.token"),account.path("token").asText)}
  val processes=Support.obj
  def start(program:Path,name:String,extra:Seq[String]=Seq.empty):Unit={
   val command=Seq("setsid","sudo","-n",program.toString)++extra:+s.network.resolve(name+".yaml").toString
   val p=new ProcessBuilder(command:_*).directory(s.root.toFile).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(s.network.resolve(name+".log").toFile)).start()
   processes.put(name,p.pid);Support.write(path,processes)
  }
  try{
   names.foreach(name=>start(node,name))
   names.foreach{name=>var ready=false;val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(40);while(!ready&&System.nanoTime()<deadline){ready=scala.util.Try(Using.resource(new Socket()){socket=>socket.connect(new InetSocketAddress("127.0.0.1",ports(name)),200)}).isSuccess;if(!ready)Thread.sleep(100)};require(ready,"Node startup failed: "+name)}
   start(union,"union",Seq("serve"))
   val connection=objectOf("union_url"->u.path("public_url"),"ca"->tls.resolve("ca.pem").toString,"token_file"->s.network.resolve("app.token").toString,"platform"->"CONET")
   val urls=connection.putObject("node_urls");names.foreach(name=>urls.put(name,s"dacp://127.0.0.1:${ports(name)}"))
   val webs=connection.putObject("web_urls");web.foreach{case(name,p)=>webs.put(name,s"http://${s.publicHost}:$p")};Support.write(s.network.resolve("connection.json"),connection)
   val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(90);var ready=false
   while(!ready&&System.nanoTime()<deadline){ready=scala.util.Try(Source.datasets(u.path("public_url").asText,TlsConfig(ca=Some(tls.resolve("ca.pem")),tokenFile=Some(s.network.resolve("app.token"))),3).size==s.centers.map(_.path("datasets").size).sum).getOrElse(false);if(!ready)Thread.sleep(500)}
   require(ready,"Union members did not become ready; see .state/network logs")
  }catch{case e:Throwable=>stop(s);throw e}
 }
}
