package conet

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path}
import java.time.Instant
import java.util.concurrent.{Executors,TimeUnit}
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal
import conet.appbuilder.ProductMetadata

/** Durable autonomous research, local workflow execution and human publication approval. */
trait ProductRobotRuntime {
 def context():JsonNode
 def propose(context:JsonNode):JsonNode
 def prepare(proposal:JsonNode,context:JsonNode,candidate:String):JsonNode
 def submit(app:String,inputs:JsonNode):(Int,JsonNode)
 def run(app:String,id:String):JsonNode
 def review(app:String,id:String):JsonNode
 def publish(app:String,id:String,request:JsonNode,automation:JsonNode):JsonNode
 def published(candidate:String):Option[JsonNode]
}
final class ProductRobot(directory:Path,cfg:JsonNode,runtime:ProductRobotRuntime) extends AutoCloseable {
 Files.createDirectories(directory)
 private val interval=cfg.path("interval_seconds").asLong(86400)
 require(interval>=60&&interval<=2592000,"机器人加工周期须为 60 秒至 30 天")
 private val maxRuntime=cfg.path("max_run_seconds").asLong(3600)
 require(maxRuntime>=60&&maxRuntime<=86400,"机器人任务观察期限须为 60 秒至 24 小时")
 private val stateFile=directory.resolve("schedule.json")
 private var schedule=if(Files.isRegularFile(stateFile))Support.copy(Support.read(stateFile))else Support.obj.put("enabled",cfg.path("enabled").asBoolean(false)).put("next_run_at",Instant.now.plusSeconds(60).toString)
 private val busy=new AtomicBoolean(false)
 private val executor=Executors.newSingleThreadScheduledExecutor()
 private var started=false
 private def path(id:String):Path={require(id.matches("[a-f0-9]{32}"),"加工记录编号无效");directory.resolve(id+".json")}
 private def records:Vector[JsonNode]=Support.children(directory).filter(p=>p.getFileName.toString.matches("[a-f0-9]{32}\\.json")).map(Support.read).sortBy(_.path("created_at").asText).reverse
 def get(id:String):JsonNode=synchronized{val p=path(id);require(Files.isRegularFile(p),"加工记录不存在");Support.read(p)}
 private def save(n:JsonNode):Unit=Support.atomic(path(n.path("id").asText),n)
 def list:JsonNode=synchronized{Support.arr(records)}
 def owns(app:String,run:String):Boolean=synchronized{records.exists(n=>n.path("app_id").asText==app&&n.path("run_id").asText==run)}
 def status:JsonNode=synchronized{val n=Support.copy(schedule).put("busy",busy.get).put("interval_seconds",interval);n.set[JsonNode]("records",list);n}
 def enable(value:Boolean):JsonNode=synchronized{schedule.put("enabled",value);Support.atomic(stateFile,schedule);status}
 // Restart never automatically resubmits an ambiguous submission.
 records.filter(n=>Set("planning","submitting")(n.path("state").asText)).foreach { n=>val next=Support.copy(n).put("state","interrupted").put("error","服务重启；请核对分析历史后重新加工，未自动重复提交");save(next) }
 def start():Unit=synchronized{if(!started){started=true;executor.scheduleWithFixedDelay(new Runnable{def run():Unit=try tick()catch{case NonFatal(e)=>System.err.println("Product robot tick failed: "+e.getClass.getSimpleName)}},5,10,TimeUnit.SECONDS)}}
 def trigger():JsonNode=synchronized{require(!records.exists(n=>Set("planning","submitting","executing")(n.path("state").asText)),"已有机器人分析正在执行");require(records.count(_.path("state").asText=="pending_review")<10,"待审批成果已达 10 个，请先审核");require(busy.compareAndSet(false,true),"机器人正在加工，请稍后重试");try executor.execute(new Runnable{def run():Unit=try cycle()catch{case NonFatal(e)=>System.err.println("Product robot run failed: "+e.getClass.getSimpleName)}finally busy.set(false)})catch{case NonFatal(e)=>busy.set(false);throw e};Support.obj.put("accepted",true)}
 def tick():Unit={
  if(!busy.compareAndSet(false,true))return
  try{
   val active=synchronized{records.filter(_.path("state").asText=="executing")}
   active.foreach(observe)
   val due=synchronized{schedule.path("enabled").asBoolean&& !Instant.now.isBefore(Instant.parse(schedule.path("next_run_at").asText))}
   if(due&&active.isEmpty&&synchronized{records.count(_.path("state").asText=="pending_review")<10})cycle()
  }finally busy.set(false)
 }
 private def cycle():Unit={
  val id=java.util.UUID.randomUUID.toString.replace("-","")
  val record=Support.obj.put("id",id).put("state","planning").put("created_at",Support.now)
  synchronized{
   require(!records.exists(n=>Set("planning","submitting","executing")(n.path("state").asText)),"已有机器人分析正在执行")
   save(record);schedule.put("last_run_at",Support.now).put("next_run_at",Instant.now.plusSeconds(interval).toString);Support.atomic(stateFile,schedule)
  }
  try{
   val context=Support.copy(runtime.context());context.set[JsonNode]("recent",Support.arr(synchronized{records.take(20).map{r=>val n=Support.obj;Seq("title","state","fingerprint","purpose","research","error","quality").foreach(k=>if(r.has(k))n.set[JsonNode](k,r.get(k)));n}}));require(context.path("catalog").path("complete").asBoolean,"联合数据目录不完整，本轮不提交加工")
   val proposal=ProductPlanner.validate(runtime.propose(context),context)
   if(proposal.path("skip").asBoolean){record.put("state","skipped").put("reason",proposal.path("reason").asText("没有适合加工的数据"));synchronized{save(record)};return}
   record.set[JsonNode]("proposal",ProductMetadata.sanitize(proposal));record.set[JsonNode]("research",proposal.path("research"));record.put("title",proposal.path("title").asText).put("purpose",proposal.path("purpose").asText).put("rationale",proposal.path("rationale").asText);synchronized{save(record)}
   val workflow=runtime.prepare(proposal,context,id)
   val app=workflow.path("id").asText
   val sourceIds=workflow.path("datasets").elements.asScala.map(_.asText).toSet
   require(sourceIds.nonEmpty,"加工流程未关联当前目录中的数据集")
   val identity=Support.obj;identity.set[JsonNode]("job",proposal.path("job"));identity.set[JsonNode]("new_operators",proposal.path("new_operators"));identity.set[JsonNode]("inputs",proposal.path("inputs"));identity.set[JsonNode]("datasets",Support.arr(context.path("catalog").path("datasets").elements.asScala.filter(d=>sourceIds(d.path("id").asText)).toVector))
   val fingerprint=ProductMetadata.digest(xstream.util.Json.mapper.writeValueAsBytes(identity))
   val duplicate=synchronized{records.exists(n=>n.path("id").asText!=id&&n.path("fingerprint").asText==fingerprint&&Set("executing","pending_review","approved","rejected","quality_failed")(n.path("state").asText))}
   record.put("app_id",app).put("fingerprint",fingerprint).put("title",proposal.path("title").asText).put("purpose",proposal.path("purpose").asText).put("rationale",proposal.path("rationale").asText)
   record.set[JsonNode]("proposal",ProductMetadata.sanitize(proposal));record.set[JsonNode]("workflow",workflow);record.set[JsonNode]("research",proposal.path("research"))
   if(duplicate){record.put("state","skipped").put("reason","相同数据目录、流程和参数已加工或审核，跳过重复任务");synchronized{save(record)};return}
   record.put("state","submitting");synchronized{save(record)}
   val(code,run)=runtime.submit(app,proposal.path("inputs"));require(code==202,run.path("error").asText("任务队列未接受本次加工"))
   record.put("run_id",run.path("id").asText).put("state","executing").put("submitted_at",Support.now);synchronized{save(record)}
  }catch{case NonFatal(e)=>record.put("state","failed").put("error",e match{case v:IllegalArgumentException=>Option(v.getMessage).getOrElse("加工计划无效");case _=>"机器人加工失败，请查看服务端日志"});synchronized{save(record)};System.err.println("Product robot candidate failed: "+id+" "+e.getClass.getSimpleName);e.printStackTrace(System.err)}
 }
 private def observe(record:JsonNode):Unit={
  val n=Support.copy(record)
  try{
   val run=runtime.run(n.path("app_id").asText,n.path("run_id").asText);val state=run.path("state").asText
   if(Set("failed","cancelled","interrupted")(state)){n.put("state","failed").put("error",("分析未完成："+state+"；"+run.path("error").asText).take(4000))}
   else if(state=="succeeded"){
    val preview=runtime.review(n.path("app_id").asText,n.path("run_id").asText)
    val metadata=preview.path("metadata");val diagnostics=metadata.path("diagnostics")
    val artifacts=metadata.path("artifacts").elements.asScala.toVector
    val rows=metadata.path("collections").elements.asScala.toVector.flatMap(c=>Option(c.get("rows")).map(_.asLong))
    val summaryValid= !run.has("definition")||run.path("definition").path("result").path("summary").fields.asScala.forall(e=>e.getValue.isObject&&e.getValue.path("path").isTextual)
    val checks=Vector("摘要配置有效"->summaryValid,"来源可追溯"->(metadata.path("sources").size>0),"加工步骤已记录"->(metadata.path("operators").size>0),"成果文件存在且有校验和"->artifacts.exists(a=>a.path("bytes").asLong>0&&a.path("sha256").asText.matches("[a-f0-9]{64}")),"结果未标记为不完整"->(!diagnostics.has("complete")||diagnostics.path("complete").asBoolean),"表格结果非空"->(rows.isEmpty||rows.exists(_>0)),"无结果警告"->(!diagnostics.has("warnings")||diagnostics.path("warnings").isNull||(diagnostics.path("warnings").isArray&&diagnostics.path("warnings").isEmpty)))
    n.set[JsonNode]("quality",Support.arr(checks.map{case(label,passed)=>Support.obj.put("label",label).put("passed",passed)}))
    if(n.has("replay_of")&&get(n.path("replay_of").asText).path("review").path("metadata").has("artifacts")){
     val previous=get(n.path("replay_of").asText).path("review").path("metadata").path("artifacts").elements.asScala.toVector
     val comparison=Support.obj.put("previous_candidate",n.path("replay_of").asText).put("byte_identical",previous.nonEmpty&&previous.size==artifacts.size&&previous.forall(a=>artifacts.exists(b=>b.path("id")==a.path("id")&&b.path("sha256")==a.path("sha256"))))
     n.set[JsonNode]("replay_comparison",comparison)
    }
    n.set[JsonNode]("review",preview);n.set[JsonNode]("plan",ProductMetadata.sanitize(runtimePlan(run)))
    n.put("state",if(checks.forall(_._2))"pending_review"else"quality_failed").put("finished_at",Support.now)
   }else if(Instant.now.isAfter(Instant.parse(n.path("submitted_at").asText).plusSeconds(maxRuntime))){
    // Do not start additional work while this run is still consuming resources.
    n.put("overdue",true).put("error","分析超过观察期限；请核对执行记录并取消任务，机器人暂停新增加工")
   }
   synchronized{save(n)}
  }catch{case NonFatal(_)=>n.put("observation_error","暂时无法读取分析状态，下次检查会重试");synchronized{save(n)}}
 }
 private def runtimePlan(run:JsonNode):JsonNode={val n=Support.obj;Seq("resolved_job_yaml","job_yaml","variables","dataset_bindings","usage").foreach(k=>if(run.has(k))n.set[JsonNode](k,run.get(k)));n}
 def replay(id:String):JsonNode=synchronized{
  require(!busy.get&& !records.exists(n=>Set("planning","submitting","executing")(n.path("state").asText)),"已有机器人分析正在执行")
  require(records.count(_.path("state").asText=="pending_review")<10,"待审批成果已达 10 个，请先审核")
  val original=get(id);require(original.has("app_id")&&original.has("run_id"),"该记录尚未生成可重放的流程")
  val n=Support.obj.put("id",java.util.UUID.randomUUID.toString.replace("-","")).put("created_at",Support.now).put("state","submitting").put("replay_of",id)
  Seq("app_id","title","purpose","rationale","proposal","workflow","research","fingerprint").foreach(k=>if(original.has(k))n.set[JsonNode](k,original.get(k)))
  save(n)
  try{val(code,run)=runtime.submit(n.path("app_id").asText,n.path("proposal").path("inputs"));require(code==202,run.path("error").asText("任务队列未接受重放"));n.put("state","executing").put("run_id",run.path("id").asText).put("submitted_at",Support.now);save(n);n}
  catch{case NonFatal(e)=>n.put("state","failed").put("error","重放提交失败，请核对队列与运行环境");save(n);throw e}
 }
 def reject(id:String,reason:String):JsonNode=synchronized{require(reason.trim.nonEmpty&&reason.length<=4000,"请填写不超过 4000 字的退回原因");val n=Support.copy(get(id));require(Set("pending_review","quality_failed")(n.path("state").asText),"该加工结果不在待审核状态");n.put("state","rejected").put("reviewed_at",Support.now).put("review_note",reason);save(n);n}
 def approve(id:String,request:JsonNode):JsonNode=synchronized{
  val n=Support.copy(get(id));if(n.path("state").asText=="approved")return n
  require(n.path("state").asText=="pending_review","只有通过质量检查的待审核成果可以批准")
  require(request.path("review_note").isTextual&&request.path("review_note").asText.trim.nonEmpty&&request.path("review_note").asText.length<=4000,"请填写审批意见")
  val automation=Support.obj.put("candidate_id",id).put("workflow_id",n.path("app_id").asText).put("bundle_sha256",n.path("workflow").path("revision").asText).put("title",n.path("title").asText).put("purpose",n.path("purpose").asText).put("rationale",n.path("rationale").asText).put("review_note",request.path("review_note").asText).put("reviewed_at",Support.now).put("reviewer","管理员")
  automation.set[JsonNode]("research",n.path("research"));automation.set[JsonNode]("workflow_snapshot",n.path("proposal"));automation.set[JsonNode]("workflow",n.path("workflow"))
  val published=runtime.published(id).getOrElse(runtime.publish(n.path("app_id").asText,n.path("run_id").asText,request,automation))
  n.put("state","approved").put("reviewed_at",Support.now).put("review_note",request.path("review_note").asText);n.set[JsonNode]("publication",published);save(n);n
 }
 def close():Unit={executor.shutdownNow();executor.awaitTermination(5,TimeUnit.SECONDS)}
}
