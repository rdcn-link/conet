package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dacp.client.{Source,TlsConfig,Union}
import xstream.util.{Json,YamlDocument,WorkflowVariables}
import java.nio.file.{Files,Path,StandardCopyOption}
import java.io.ByteArrayOutputStream
import java.util.concurrent.{Executors,TimeUnit,ConcurrentHashMap}
import java.util.concurrent.atomic.{AtomicBoolean,AtomicReference}
import scala.jdk.CollectionConverters._
import scala.util.Using
import scala.util.control.NonFatal

final class JobControl {
 val cancelled=new AtomicBoolean(false)
 private val action=new AtomicReference[()=>Unit](()=>())
 def onCancel(f:()=>Unit):Unit={action.set(f);if(cancelled.get())f()}
 def cancel():Unit={cancelled.set(true);action.get()()}
 def check():Unit=if(cancelled.get()||Thread.currentThread.isInterrupted)throw new java.util.concurrent.CancellationException("分析已取消")
}
/** Server-owned connection and transport. Browser requests cannot choose credentials or endpoints. */
trait JobBackend {
 def datasets(scope:String):Map[String,String]
 def webUrls:JsonNode
 def details(jobId:String):Option[JsonNode]=None
 def execute(job:String,variables:JsonNode,limit:Long,control:JobControl,started:String=>Unit,status:JsonNode=>Unit):Array[Byte]
}
final class UnionBackend(connection:()=>JsonNode) extends JobBackend {
 private def tls(c:JsonNode)=TlsConfig(ca=Some(Path.of(c.path("ca").asText)),tokenFile=Some(Path.of(c.path("token_file").asText)))
 def webUrls:JsonNode=connection().path("web_urls")
 override def details(jobId:String):Option[JsonNode]=try{
  require(jobId.matches("[a-zA-Z0-9-]{1,100}"),"任务编号无效")
  val base=webUrls.path("union").asText.stripSuffix("/");val uri=java.net.URI.create(base+"/api/jobs/"+jobId)
  require(Set("http","https")(uri.getScheme),"看板地址无效")
  val request=java.net.http.HttpRequest.newBuilder(uri).timeout(java.time.Duration.ofSeconds(4)).GET.build()
  val response=java.net.http.HttpClient.newHttpClient().send(request,java.net.http.HttpResponse.BodyHandlers.ofInputStream())
  val bytes=try response.body.readNBytes(4*1024*1024+1)finally response.body.close()
  if(response.statusCode==200&&bytes.length<=4*1024*1024)Some(Json.mapper.readTree(bytes))else None
 }catch{case NonFatal(_)=>None}
 def datasets(scope:String):Map[String,String]={val c=connection();val catalog=Source.datasets(c.path("union_url").asText,tls(c),15);require(catalog.complete,"联合数据目录尚未完整加载");catalog.flatMap{d=>val m=Json.mapper.readTree(d.description);if(m.path("case").asText==scope)Some(m.path("dataset").asText->d.uri)else None}.toMap}
 def execute(job:String,variables:JsonNode,limit:Long,control:JobControl,started:String=>Unit,status:JsonNode=>Unit):Array[Byte]={
  val c=connection();control.check();val bytes=new ByteArrayOutputStream()
  Using.resource(new Union(c.path("union_url").asText,tls(c),300)){union=>
   Using.resource(union.submit(job,variables=variables)){execution=>
    control.onCancel(()=>execution.cancel());control.check();started(execution.id)
    val monitor=Executors.newSingleThreadScheduledExecutor()
    monitor.scheduleWithFixedDelay(new Runnable{def run():Unit=try status(execution.status())catch{case NonFatal(_)=>()}},0,1,TimeUnit.SECONDS)
    try Using.resource(execution.results()){results=>results.foreach{event=>control.check();if(event.kind=="data"){require(bytes.size.toLong+event.data.length<=limit,"成果超过应用输出上限");bytes.write(event.data)}else if(Set("error","failed")(event.kind))throw new IllegalStateException("任务执行失败")}}
    finally {monitor.shutdownNow();monitor.awaitTermination(3,TimeUnit.SECONDS);control.onCancel(()=>())}
    control.check();try status(execution.status())catch{case NonFatal(_)=>()}
   }
  };bytes.toByteArray
 }
}
final class ApplicationService(val definition:Definition,val state:Path,manifest:()=>JsonNode,backend:JobBackend,val base:String,inputFiles:InputFiles=InputFiles.unavailable,sharedScheduler:Option[AnalysisScheduler]=None) extends AutoCloseable {
 val id=definition.id
 private val jobs=scala.collection.mutable.Map.empty[String,ObjectNode]
 private val admission=sharedScheduler.getOrElse(new AnalysisScheduler())
 private val ownsScheduler=sharedScheduler.isEmpty
 @volatile private var closed=false
 private val controls=new ConcurrentHashMap[String,JobControl]()
 private def now=java.time.Instant.now.toString
 private def save(j:ObjectNode):Unit={val path=state.resolve("web-jobs").resolve(j.path("id").asText+".json");Files.createDirectories(path.getParent);val temp=path.resolveSibling(path.getFileName.toString+".tmp");Files.write(temp,Json.mapper.writeValueAsBytes(j));Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
 if(Files.isDirectory(state.resolve("web-jobs")))Using.resource(Files.list(state.resolve("web-jobs"))){files=>files.iterator.asScala.filter(_.toString.endsWith(".json")).foreach{p=>val j=Values.copy(Json.mapper.readTree(Files.readAllBytes(p)));j.put("app_id",id);require(j.path("id").asText.matches("[a-zA-Z0-9-]{1,100}"),"任务记录编号无效");if(Set("queued","running")(j.path("state").asText)){j.put("state","interrupted").put("error","应用服务已重启，请先在 Union 看板核对原任务再决定是否重新提交");save(j)};jobs(j.path("id").asText)=j}}
 def config:JsonNode={val n=Values.obj.put("job_yaml",definition.jobYaml);n.set[JsonNode]("application",definition.publicConfig);n.set[JsonNode]("manifest",manifest());n.set[JsonNode]("web_urls",backend.webUrls);n}
 def records:Vector[JsonNode]=synchronized{jobs.values.toVector.map(Values.copy)}
 def list:JsonNode=synchronized{Values.array(jobs.values.toVector.sortBy(_.path("created_at").asText).reverse.map{j=>val n=Values.copy(j);n.remove("result");n.remove("execution");n.remove("definition");n.remove("job_yaml");n.remove("resolved_job_yaml");n})}
 def get(identifier:String):Option[JsonNode]=synchronized{jobs.get(identifier).map(Values.copy)}
 def plan(identifier:String):JsonNode={
  val run=get(identifier).getOrElse(throw new IllegalArgumentException("分析记录不存在"))
  val yaml=if(run.path("resolved_job_yaml").isTextual)run.path("resolved_job_yaml").asText else{
   require(run.path("job_yaml").isTextual&&run.path("variables").isObject,"该分析尚无已填充变量的 Job 快照")
   WorkflowVariables.resolve(run.path("job_yaml").asText,run.path("variables"))
  }
  val out=Values.obj.put("yaml",yaml);out.set[JsonNode]("job",YamlDocument.parse(yaml));out
 }
 def active:Boolean=synchronized{jobs.values.exists(j=>Set("queued","running")(j.path("state").asText))}
 private def update(identifier:String)(f:ObjectNode=>Unit):Unit=synchronized{val j=jobs(identifier);f(j);save(j)}
 def upload(field:String,name:String,stream:java.io.InputStream):JsonNode=inputFiles.upload(definition,field,name,stream)
 def preview(payload:JsonNode):JsonNode={val input=inputFiles.resolve(definition,definition.validate(payload,manifest()),None);val bindings=definition.variableBindings.filterNot(_._2.has("dataset"));val v=Values.obj;bindings.foreach{case(k,b)=>if(b.has("input")){val value=input.at("/"+b.path("input").asText.replace(".","/"));v.set[JsonNode](k,if(value.isObject&&value.has("upload_id"))value.path("uri")else value)}};val n=Values.obj;n.set[JsonNode]("inputs",input);n.set[JsonNode]("input_variables",v);n.put("job_yaml",definition.jobYaml);n}
 def submit(payload:JsonNode,submittedBy:Option[JsonNode]=None):(Int,JsonNode)=synchronized{
  if(closed)return(503,Values.obj.put("error","分析服务正在关闭"))
  val validated=definition.validate(payload,manifest());if(jobs.values.count(j=>Set("queued","running")(j.path("state").asText))>=definition.config.path("limits").path("max_queued").asInt(4))return(429,Values.obj.put("error","任务队列已满，请等待当前分析完成"))
  val identifier=java.util.UUID.randomUUID.toString.replace("-","")
  val rejected=admission.submit(id,identifier,state){
  val input=inputFiles.resolve(definition,validated,Some(identifier));input.put("id",identifier).put("state","queued").put("created_at",now).put("synthetic",definition.config.path("synthetic").asBoolean(true));input.set[JsonNode]("definition",definition.publicConfig);input.put("job_yaml",definition.jobYaml);input.put("app_id",id);submittedBy.foreach(a=>input.set[JsonNode]("submitted_by",a))
  val stepOperators=Values.obj;YamlDocument.parse(definition.jobYaml).path("steps").elements.asScala.foreach(s=>stepOperators.put(s.path("id").asText,s.path("operator").asText));input.set[JsonNode]("step_operators",stepOperators)
  save(input);jobs(identifier)=input;val control=new JobControl();controls.put(identifier,control)
  ()=>execute(identifier,control)
  }
  rejected match{case Some((code,message))=>(code,Values.obj.put("error",message));case None=>(202,Values.copy(jobs(identifier)))}
 }
 def cancel(identifier:String):Option[JsonNode]={
  val control=synchronized{jobs.get(identifier).filter(j=>Set("queued","running")(j.path("state").asText)).map{j=>j.put("state","cancelled").put("finished_at",now);save(j);Option(controls.get(identifier))}}
  control.flatten.foreach(c=>try c.cancel()catch{case NonFatal(e)=>System.err.println(s"App $id cancellation failed: ${e.getClass.getSimpleName}")});admission.remove(id,identifier).foreach(_.apply());get(identifier)
 }
 private def execute(identifier:String,control:JobControl):Unit={
  val output=state.resolve("results").resolve(identifier)
  try {
   control.check();admission.checkDisk(state);val job=get(identifier).get;val m=manifest();val scope=definition.config.path("data").path("scope").asText(id)
   val datasets=if(definition.variableBindings.values.exists(_.has("dataset")))backend.datasets(scope)else Map.empty[String,String]
   control.check();val vars=definition.variables(job,m,datasets)
   val resolved=YamlDocument.parse(WorkflowVariables.resolve(definition.jobYaml,vars))
   val bindings=Values.array(datasets.toVector.map{case(name,uri)=>val n=Values.obj.put("name",name).put("uri",uri);val ids=resolved.path("steps").elements.asScala.filter(s=>s.path("operator").asText.startsWith("read_item@")&&s.path("params").path("uri").asText.startsWith(uri.stripSuffix("/")+"/")).map(s=>Json.mapper.getNodeFactory.textNode(s.path("id").asText)).toVector;n.set[JsonNode]("steps",Values.array(ids));n})
   update(identifier){j=>j.set[JsonNode]("dataset_bindings",bindings);j.set[JsonNode]("variables",vars);j.put("resolved_job_yaml",YamlDocument.dump(resolved))}
   val raw=backend.execute(definition.jobYaml,vars,definition.config.path("limits").path("max_result_bytes").asLong(8L*1024*1024),control,
    executionId=>update(identifier){j=>if(!control.cancelled.get())j.put("job_id",executionId).put("state","running").put("started_at",now);j.set[JsonNode]("variables",vars)},
    status=>update(identifier){j=>Usage.observe(j,status);j.set[JsonNode]("execution",status);j.put("updated_at",now)})
   control.check();admission.checkDisk(state);require(raw.length.toLong<=definition.config.path("limits").path("max_result_bytes").asLong(8L*1024*1024),"成果超过应用输出上限");val result=Results.write(definition,output,raw,vars);val summary=Values.copy(Results.summary(definition,result));summary.put("job_id",get(identifier).get.path("job_id").asText)
   val artifacts=Values.obj;definition.artifacts.foreach(a=>artifacts.put(a.path("id").asText,base+"/results/"+identifier+"/"+a.path("file").asText));artifacts.put("metadata",base+"/results/"+identifier+"/metadata.json").put("report",base+"/results/"+identifier+"/report.md");artifacts.put("analysis",base+"/results/"+identifier+"/analysis.json").put("variables",base+"/results/"+identifier+"/variables.yaml")
   val completed=Values.copy(get(identifier).get).put("state","succeeded").put("finished_at",now);completed.set[JsonNode]("summary",summary);completed.set[JsonNode]("result",result);completed.set[JsonNode]("artifacts",artifacts)
   control.check();ProductMetadata.write(definition,completed,output)
   control.check();update(identifier){j=>if(j.path("state").asText!="cancelled"){j.put("state","succeeded").put("finished_at",completed.path("finished_at").asText);j.set[JsonNode]("summary",summary);j.set[JsonNode]("result",result);j.set[JsonNode]("artifacts",artifacts)}}
  }catch{
   case _:java.util.concurrent.CancellationException=>update(identifier)(_.put("state","cancelled").put("finished_at",now))
   case _:InterruptedException=>Thread.currentThread.interrupt();update(identifier)(_.put("state","interrupted").put("finished_at",now))
   case NonFatal(e)=>System.err.println(s"App $id job $identifier failed");e.printStackTrace();update(identifier){j=>if(j.path("state").asText!="cancelled")j.put("state","failed").put("finished_at",now).put("error",Option(e.getMessage).getOrElse(e.getClass.getSimpleName))}
  }finally {
   try inputFiles.finished(id,identifier)catch{case NonFatal(e)=>System.err.println("Input retention update failed: "+e.getClass.getSimpleName)}
   get(identifier).filter(_.has("job_id")).foreach{j=>backend.details(j.path("job_id").asText).foreach(s=>update(identifier){record=>Usage.observe(record,s);record.set[JsonNode]("execution",s)})}
   get(identifier).filter(_.path("state").asText=="succeeded").foreach(record=>try ProductMetadata.write(definition,record,output)catch{case NonFatal(e)=>System.err.println(s"App $id result metadata generation failed");e.printStackTrace()})
   controls.remove(identifier);if(get(identifier).exists(_.path("state").asText!="succeeded")&&Files.isDirectory(output))Using.resource(Files.walk(output)){paths=>paths.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))}}
 }
 def artifact(identifier:String,file:String):Option[Path]=get(identifier).filter(_.path("state").asText=="succeeded").flatMap{j=>val declared=j.path("artifacts").elements.asScala.map(_.asText.split('/').last).toSet;if(declared(file)&& !file.contains("/")&& !file.contains("\\"))Some(Values.relative(state.resolve("results").resolve(identifier),file))else None}
 def close():Unit={closed=true;controls.keySet.asScala.toVector.foreach(cancel);if(ownsScheduler)admission.close()}
}
