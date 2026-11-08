package conet

import java.nio.file.{Files,Path}
import java.util.concurrent.CountDownLatch
import scala.jdk.CollectionConverters._

object Main {
 def main(raw:Array[String]):Unit={
  val (root,args)=if(raw.headOption.contains("--root")){require(raw.length>=3,"--root requires a path and command");(Path.of(raw(1)).toAbsolutePath.normalize,raw.drop(2))}else(Path.of(".").toAbsolutePath.normalize,raw)
  val settings=new Settings(root)
  args.headOption.getOrElse("help") match {
   case "catalog-up"=>Network.catalogUp(settings)
   case "hosting-up"=>HostedNode.up(settings)
   case "hosting-down"=>HostedNode.down(settings)
   case "hosting-status"=>println(HostedNode.status(settings))
   case "serve"=>val web=new Web(settings);Runtime.getRuntime.addShutdownHook(new Thread(()=>web.close()));web.start();new CountDownLatch(1).await()
   case "up"=>require(!Manager.running(settings),"CONET is already running");Network.up(settings);try{if(settings.config.path("hosting").path("enabled").asBoolean)HostedNode.up(settings);Manager.start(settings)}catch{case e:Throwable=>try HostedNode.stop(settings)finally Network.stop(settings);throw e}
   case "down"=>HostedNode.stop(settings);Manager.stop(settings);Network.stop(settings)
   case "restart-web"=>Manager.stop(settings);Manager.start(settings)
   case "status"=>println(xstream.util.Json.mapper.writerWithDefaultPrettyPrinter.writeValueAsString(Manager.status(settings)))
   case "build-operators"=>println(Operators.build(settings))
   case _=>println("Usage: bin/conet up|down|restart-web|status|build-operators|catalog-up|hosting-up|hosting-down|hosting-status|serve")
  }
 }
}
object Manager {
 private def records(s:Settings)=if(Files.exists(s.state.resolve("apps.json")))Support.read(s.state.resolve("apps.json")).elements.asScala.toVector else Vector.empty
 def alive(pid:Long):Boolean=ProcessHandle.of(pid).isPresent&&ProcessHandle.of(pid).get.isAlive&&scala.util.Try(Files.readString(Path.of(s"/proc/$pid/stat")).split(" ")(2)!="Z").getOrElse(false)
 private def startTime(pid:Long):String=Files.readString(Path.of(s"/proc/$pid/stat")).split(" ")(21)
 def running(s:Settings):Boolean=records(s).exists(r=>alive(r.path("pid").asLong))
 def status(s:Settings)=Support.arr(records(s).map{r=>val n=Support.copy(r);n.put("running",alive(r.path("pid").asLong));n})
 def stop(s:Settings):Unit={
  val remaining=records(s).reverse.filter{r=>val pid=r.path("pid").asLong
   if(!alive(pid))false else{
    require(startTime(pid)==r.path("start").asText&&Path.of(s"/proc/$pid/cwd").toRealPath()==Path.of(r.path("cwd").asText),s"Refusing to stop unrelated PID $pid")
    Support.run(Seq("kill","-TERM","--",s"-$pid"),s.root)
    val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(20);while(alive(pid)&&System.nanoTime()<deadline)Thread.sleep(100)
    alive(pid)
   }
  };Support.write(s.state.resolve("apps.json"),Support.arr(remaining));require(remaining.isEmpty,"Application is still stopping")
 }
 def start(s:Settings):Unit={
  require(!running(s),"CONET is already running");require(Files.isRegularFile(s.network.resolve("connection.json")),"Start the network first with bin/conet up")
  s.cases.foreach(c=>{val path=s.caseState(c.path("id").asText);Files.createDirectories(path);Support.write(path.resolve("connection.json"),s.connection)})
  val p=new ProcessBuilder("setsid",s.root.resolve("bin/conet").toString,"serve").directory(s.root.toFile).redirectInput(ProcessBuilder.Redirect.from(Path.of("/dev/null").toFile)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(s.state.resolve("portal.log").toFile)).start()
  val r=Support.obj.put("name","conet").put("pid",p.pid).put("cwd",s.root.toString).put("start",startTime(p.pid));Support.write(s.state.resolve("apps.json"),Support.arr(Seq(r)))
  try{
   val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
   val ports=Seq(s.config.path("port").asInt)
   ports.foreach{port=>var ready=false;while(!ready&&alive(p.pid)&&System.nanoTime()<deadline){ready=scala.util.Try(Support.get(s"http://127.0.0.1:$port/api/config",1)).isSuccess;if(!ready)Thread.sleep(100)};require(ready,s"Web service $port did not become ready; see .state/portal.log")}
   println(s"CONET: http://${s.publicHost}:${s.config.path("port").asInt}/")
  }catch{case e:Throwable=>stop(s);throw e}
 }
}
