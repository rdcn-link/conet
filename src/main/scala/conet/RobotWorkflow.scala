package conet

import com.fasterxml.jackson.databind.JsonNode
import conet.appbuilder.{ApplicationService,Definition,InputFiles,JobBackend,JobControl,ProductMetadata}
import java.nio.file.{Files,Path}
import java.util.concurrent.{TimeUnit,Executors}
import scala.jdk.CollectionConverters._
import scala.util.Using
import xstream.util.{Json,YamlDocument,WorkflowVariables}
import xstream.data.{PluginEnvironment,PluginRegistration}
import xstream.engine.LocalEngine
import xstream.execution.{RemoteItemProvider,RemoteItemRequest,StreamCancel,StreamProtocol}

/** The executable bundle is immutable; model output cannot select host commands, images or credentials. */
object RobotWorkflow {
 def executionSnapshot(root:JsonNode,history:Seq[JsonNode]):JsonNode={
  val n=Support.copy(root);val rootId=root.path("record_id").asText
  val steps=history.filter(_.path("parent_execution_id").asText==rootId).filter(_.has("task_id")).map{raw=>val step=Support.copy(raw);step.put("id",raw.path("task_id").asText).put("node_identity","local").put("node","CONET 本地 XStream");step}
  n.set[JsonNode]("steps",Support.arr(steps));n.putObject("nodes").putObject("local").put("execution_id",rootId);n
 }
 def sameMetadata(a:JsonNode,b:JsonNode):Boolean=if(a.isNumber&&b.isNumber)a.decimalValue.compareTo(b.decimalValue)==0 else if(a.isArray&&b.isArray)a.size==b.size&&a.elements.asScala.zip(b.elements.asScala).forall{case(x,y)=>sameMetadata(x,y)} else if(a.isObject&&b.isObject)a.fieldNames.asScala.toSet==b.fieldNames.asScala.toSet&&a.fields.asScala.forall(e=>sameMetadata(e.getValue,b.path(e.getKey))) else a==b

 def definition(proposal:JsonNode,id:String):Definition={
  val app=Support.copy(proposal.path("application"));app.put("id",id).put("title",proposal.path("title").asText).put("description",proposal.path("purpose").asText).put("job","job.yaml")
  require(!app.has("data")&& !app.has("publication"),"机器人流程不可定义数据连接或发布许可")
  val limits=app.putObject("limits");limits.put("max_queued",1).put("max_result_bytes",8*1024*1024)
  val d=Definition.parse(YamlDocument.dump(app),YamlDocument.dump(proposal.path("job")),allowLocalOperators=true)
  require(d.fileFields.isEmpty&&d.variableBindings.values.forall(v=>v.has("input")||v.has("value")),"机器人流程只允许已声明输入或固定变量")
  d.config.path("result").path("summary").fields.asScala.foreach{e=>require(e.getValue.isObject&&e.getValue.path("path").isTextual,"result.summary 须为名称到 {label,path,operation} 的映射，如 {feature_count:{label:要素数,path:/feature_count}}")}
  d.validate(proposal.path("inputs"),Support.obj);d
 }
 def validate(proposal:JsonNode,context:JsonNode,id:String):Definition={
  val d=definition(proposal,id);val resolved=YamlDocument.parse(WorkflowVariables.resolve(d.jobYaml,d.variables(proposal.path("inputs"),Support.obj,Map.empty)))
  require(resolved.fieldNames.asScala.toSet.subsetOf(Set("name","steps")),"机器人 Job 含不允许的执行设置")
  require(resolved.path("steps").size<=16,"机器人流程最多 16 个步骤")
  val declared=proposal.path("new_operators").elements.asScala.toVector
  require(proposal.path("new_operators").isArray&&declared.size<=4,"每轮最多生成 4 个本地算子")
  val names=declared.map(o=>o.path("name").asText+"@"+o.path("version").asText)
  require(names.distinct.size==names.size,"本地算子身份重复")
  declared.foreach{o=>
   require(o.fieldNames.asScala.toSet==Set("name","version","description","inputs","parameters","source","tests"),"本地算子设计字段不完整或包含不允许的设置")
   require(o.path("name").asText.matches("[a-z][a-z0-9_]{0,63}")&&o.path("version").asText.matches("[0-9][A-Za-z0-9_.-]{0,20}"),"本地算子身份无效")
   require(o.path("description").asText.trim.nonEmpty,"请描述本地算子的用途")
   Seq("source","tests").foreach(k=>require(o.path(k).isTextual&&o.path(k).asText.trim.nonEmpty&&o.path(k).asText.length<=50000,"本地算子源码或测试无效"))
   xstream.operator.ParameterSchema.validate(o.get("parameters"));xstream.execution.OperatorPorts.read(o.path("inputs"))
   require(o.path("inputs").size>0,"生成的本地算子必须消费输入流")
  }
  val published=context.path("operators").elements.asScala.map(o=>o.path("name").asText+"@"+o.path("version").asText).toSet
  val items=context.path("catalog").path("datasets").elements.asScala.flatMap(_.path("items").elements.asScala).map(i=>i.path("dacp_uri").asText->i).toMap
  val sources=resolved.path("steps").elements.asScala.filter(s=>Set("item_source","read_item")(Definition.operatorId(s).takeWhile(_!='@'))).toVector
  require(sources.nonEmpty,"加工流程必须引用目录 Item");val actual=context.path("catalog").path("datasets").elements.asScala.filter(ds=>ds.path("items").elements.asScala.exists(i=>sources.exists(_.path("params").path("uri").asText==i.path("dacp_uri").asText))).map(_.path("id").asText).toSet;require(actual==proposal.path("datasets").elements.asScala.map(_.asText).toSet,"计划的数据集清单必须与实际读取来源一致")
  sources.foreach{s=>val uri=s.path("params").path("uri").asText;val item=items.getOrElse(uri,throw new IllegalArgumentException("流程引用了未研究的 Item："+uri));require(!item.has("read_probe_error"),"来源 Item 的读取探测未通过，请选择可读取的数据");val view=xstream.data.ItemViews.selectForRead(s.path("params").path("view").asText(""),item.path("kind").asText,item.path("views").elements.asScala.map(_.asText).toSeq);require(Set("bytes","records")(view),"机器人首轮支持 bytes、records 视图，科学数组应使用配套格式算子")}
  resolved.path("steps").elements.asScala.foreach{s=>
   require(s.fieldNames.asScala.toSet.subsetOf(Set("id","operator","inputs","params","description","title")),"步骤含不允许的执行设置")
   val op=Definition.operatorId(s)
   if(s.path("operator").isObject)require(names.contains(op),"本地算子未附源代码")else require(published(op),"流程引用的仓库算子不在当前公开目录："+op)
  }
  require(names.toSet==resolved.path("steps").elements.asScala.filter(_.path("operator").isObject).map(Definition.operatorId).toSet,"本地算子必须在流程中使用")
  d
 }
}
final class RobotWorkflows(settings:Settings,catalog:CatalogGateway) extends AutoCloseable {
 private val home=settings.state.resolve("product-robot/workflows")
 private val cfg=settings.config.path("appbuilder").path("product_robot")
 private val services=scala.collection.mutable.Map.empty[String,ApplicationService]
 private def create(directory:Path):ApplicationService={
  val d=Definition.parse(Files.readString(directory.resolve("application.yaml")),Files.readString(directory.resolve("job.yaml")),true)
  new ApplicationService(d,directory.resolve("runs"),()=>Support.obj,new RobotLocalBackend(settings,directory,catalog),s"/apps/${d.id}",InputFiles.unavailable,Some(settings.analysisScheduler),allowLocalOperators=true)
 }
 Support.children(home).filter(p=>Files.isRegularFile(p.resolve("bundle.json"))).foreach(p=>services(p.getFileName.toString)=create(p))
 def get(id:String):Option[ApplicationService]=synchronized{services.get(id)}
 def history:Vector[ApplicationService]=synchronized{services.values.toVector}
 def prepare(proposal:JsonNode,context:JsonNode,candidate:String):JsonNode=synchronized{
  val id="robot-"+candidate;val validated=RobotWorkflow.validate(proposal,context,id);val app=Support.copy(validated.config);app.put("synthetic",context.path("catalog").path("datasets").elements.asScala.exists(d=>proposal.path("datasets").elements.asScala.exists(_.asText==d.path("id").asText)&&d.path("metadata").path("synthetic").asBoolean));val d=Definition(app,validated.jobYaml);val directory=home.resolve(id);require(!Files.exists(directory),"加工流程已存在")
  Files.createDirectories(directory)
  Support.writeText(directory.resolve("application.yaml"),YamlDocument.dump(d.config));Support.writeText(directory.resolve("job.yaml"),d.jobYaml)
  val runtime=cfg.path("local_runtime");require(runtime.path("image").asText.matches("sha256:[a-f0-9]{64}"),"机器人本地运行环境须固定到已安装的 Docker 镜像 ID")
  proposal.path("new_operators").elements.asScala.foreach{o=>
   val name=o.path("name").asText;val base=directory.resolve("library").resolve(name).resolve(o.path("version").asText)
   val manifest=Support.obj.put("name",name).put("version",o.path("version").asText).put("description",o.path("description").asText).put("role","transform").put("type","sdk").put("language","python").put("entrypoint",name).put("runtime",runtime.path("runtime").asText("python-arrow@1.0")).put("source_public",false)
   manifest.set[JsonNode]("icon",Support.obj.put("path","M4 5h16v14H4zM8 9h8M8 12h8M8 15h5").put("color","#168b94"))
   xstream.operator.OperatorPresentation.validate(manifest)
   Seq("inputs","parameters").foreach(k=>manifest.set[JsonNode](k,o.get(k)))
   Support.writeText(base.resolve("operator.yaml"),YamlDocument.dump(manifest));Support.writeText(base.resolve("local.yaml"),s"image: ${runtime.path("image").asText}\nexecutor: python\nbuild: {provider: python}\n")
   Support.writeText(base.resolve("src/"+name+".py"),o.path("source").asText);Support.writeText(base.resolve("tests/test_operator.py"),o.path("tests").asText)
  }
  // Every byte needed for replay is hashed; connection secrets are never part of the bundle.
  val bundle=Support.obj.put("version",1).put("created_at",Support.now).put("execution_mode","local");bundle.set[JsonNode]("proposal",proposal);bundle.set[JsonNode]("context",context);bundle.set[JsonNode]("runtime",runtime)
  val checks=bundle.putArray("files");Support.walk(directory).filter(Files.isRegularFile(_)).sortBy(_.toString).foreach(p=>checks.addObject().put("path",directory.relativize(p).toString).put("sha256",ProductMetadata.fileDigest(p)))
  Support.atomic(directory.resolve("bundle.json"),bundle)
  val service=create(directory);services(id)=service
  val out=Support.obj.put("id",id).put("revision",ProductMetadata.digest(Files.readAllBytes(directory.resolve("bundle.json")))).put("title",proposal.path("title").asText).put("synthetic",d.config.path("synthetic").asBoolean(true))
  out.set[JsonNode]("runtime",runtime);out.set[JsonNode]("source_metadata",Support.arr(context.path("catalog").path("datasets").elements.asScala.filter(ds=>proposal.path("datasets").elements.asScala.exists(_.asText==ds.path("id").asText)).map(ds=>{val n=Support.copy(ds);n.remove("samples");n}).toVector));val usedOperators=proposal.path("job").path("steps").elements.asScala.filter(_.path("operator").isTextual).map(Definition.operatorId).toSet;out.set[JsonNode]("operator_artifacts",Support.arr(context.path("operators").elements.asScala.filter(o=>usedOperators(o.path("name").asText+"@"+o.path("version").asText)).toVector))
  out.set[JsonNode]("datasets",Support.arr(context.path("catalog").path("datasets").elements.asScala.filter(ds=>proposal.path("datasets").elements.asScala.exists(_.asText==ds.path("id").asText)).map(ds=>Json.mapper.getNodeFactory.textNode(ds.path("id").asText)).toVector));out
 }
 def bundle(id:String):JsonNode={require(id.matches("robot-[a-f0-9]{32}"),"流程标识无效");Support.read(home.resolve(id).resolve("bundle.json"))}
 def close():Unit=synchronized{services.values.foreach(_.close())}
}

/** Local operator code runs only in XStream's isolated, network-disabled containers. */
final class RobotLocalBackend(settings:Settings,directory:Path,catalog:CatalogGateway) extends JobBackend {
 private val cfg=settings.config.path("appbuilder").path("product_robot")
 private def bundle=Support.read(directory.resolve("bundle.json"))
 def webUrls:JsonNode=settings.connection.path("web_urls")
 def datasets(scope:String):Map[String,String]=Map.empty
 override def sourceBindings(job:JsonNode):Vector[JsonNode]=bundle.path("context").path("catalog").path("datasets").elements.asScala.flatMap{d=>
  val ids=job.path("steps").elements.asScala.filter(s=>Set("item_source","read_item")(Definition.operatorId(s).takeWhile(_!='@'))&&d.path("items").elements.asScala.exists(_.path("dacp_uri").asText==s.path("params").path("uri").asText)).map(s=>Json.mapper.getNodeFactory.textNode(s.path("id").asText)).toVector
  if(ids.isEmpty)None else {val n=Support.obj.put("name",d.path("title").asText).put("uri",d.path("dacp_uri").asText);n.set[JsonNode]("steps",Support.arr(ids));Some(n)}
 }.toVector
 override def details(id:String):Option[JsonNode]={require(id.matches("[a-f0-9-]{36}"),"执行记录编号无效");val file=directory.resolve("execution-snapshots").resolve(id+".json");Option.when(Files.isRegularFile(file))(Support.read(file))}
 def execute(job:String,variables:JsonNode,limit:Long,control:JobControl,started:String=>Unit,status:JsonNode=>Unit):Array[Byte]={
  val snapshot=bundle
  snapshot.path("files").elements.asScala.foreach{f=>val p=conet.appbuilder.Values.relative(directory,f.path("path").asText);require(Files.isRegularFile(p)&& !Files.isSymbolicLink(p)&&ProductMetadata.fileDigest(p)==f.path("sha256").asText,"流程或算子源码已变化，不能重放")}
  val datasets=snapshot.path("context").path("catalog").path("datasets").elements.asScala.toVector

  val now=catalog.list(true);require(now.path("complete").asBoolean,"重放前无法确认完整数据目录")
  val resolved=YamlDocument.parse(WorkflowVariables.resolve(job,variables))
  val used=resolved.path("steps").elements.asScala.filter(s=>Set("item_source","read_item")(Definition.operatorId(s).takeWhile(_!='@'))).map(_.path("params").path("uri").asText).toSet
  datasets.filter(ds=>ds.path("items").elements.asScala.exists(i=>used(i.path("dacp_uri").asText))).foreach{ds=>
   val current=catalog.items(ds.path("id").asText,"/").path("items").elements.asScala.map(i=>i.path("dacp_uri").asText->i).toMap
   ds.path("items").elements.asScala.filter(i=>used(i.path("dacp_uri").asText)).foreach{before=>val after=current.getOrElse(before.path("dacp_uri").asText,throw new IllegalArgumentException("来源 Item 已移除，无法重放"));Seq("bytes","modified_at_ms","views","schema").foreach(k=>require(RobotWorkflow.sameMetadata(before.path(k),after.path(k)),"来源 Item 元数据已变化（"+k+"），请重新研究而非重放"))}
  }
  val image=snapshot.path("runtime").path("image").asText
  snapshot.path("proposal").path("new_operators").elements.asScala.foreach(o=>testOperator(o,image,control))
  val delegate=new xstream.connector.dacp.DacpRemoteItemProvider()
  val restricted=new RemoteItemProvider {
   val id="dacp"
   override def initialize(p:PluginEnvironment):Unit=delegate.initialize(p)
   def supports(uri:String):Boolean=delegate.supports(uri)
   def open(config:JsonNode,request:RemoteItemRequest,allocator:org.apache.arrow.memory.BufferAllocator,cancel:StreamCancel):xstream.execution.FrameSource={require(used(request.uri),"机器人只能读取已研究并记录的来源 Item");delegate.open(config,request,allocator,cancel)}
  }
  val config=Support.obj.put("job_working_directory",directory.toString).put("local_operator_library",directory.resolve("library").toString).put("operator_cache",settings.state.resolve("product-robot/operator-cache").toString).put("execution_directory",directory.resolve("executions").toString).put("max_concurrent_tasks",2).put("execution_timeout_seconds",cfg.path("max_run_seconds").asLong(3600))
  config.set[JsonNode]("remote_operator_library_urls",cfg.path("repositories"));config.set[JsonNode]("datasets",Support.obj)
  val c=settings.connection;val nodes=config.putArray("nodes");val publicOrigin=YamlDocument.parse(Files.readString(settings.network.resolve("union.yaml"))).path("public_url").asText
  Seq(c.path("union_url").asText,publicOrigin).distinct.foreach(origin=>nodes.addObject().put("url",origin).put("ca",c.path("ca").asText).put("token_file",c.path("token_file").asText))
  config.putObject("execution_resources").put("max_tmp_size_mb",512).put("max_memory_mb",1024)
  val output=new java.io.ByteArrayOutputStream
  Using.resource(new PluginEnvironment(registrations=Seq(PluginRegistration(restricted)))){plugins=>Using.resource(new LocalEngine(config,plugins)){host=>Using.resource(new StreamCancel(()=>control.cancelled.get(),cfg.path("max_run_seconds").asLong(3600))){cancel=>
   control.onCancel(()=>cancel.close());val localId=java.util.UUID.randomUUID().toString;started(localId)
   val policy=Support.obj;policy.putObject("resources").put("tmp_size_mb",256).put("memory_mb",1024)
   val initial=host.executionHistory.list(limit=1000).filter(_.path("kind").asText=="local_job").map(_.path("record_id").asText).toSet
   def observe():Unit={val history=host.executionHistory.list(limit=1000);history.find(n=>n.path("kind").asText=="local_job"&& !initial(n.path("record_id").asText)).foreach{raw=>val n=RobotWorkflow.executionSnapshot(raw,history);Support.atomic(directory.resolve("execution-snapshots").resolve(localId+".json"),n);status(n)}}
   val monitor=Executors.newSingleThreadScheduledExecutor();monitor.scheduleWithFixedDelay(new Runnable{def run():Unit=try observe()catch{case scala.util.control.NonFatal(_)=>()}},0,1,TimeUnit.SECONDS)
   try{
    val id=host.engine.run(WorkflowVariables.resolve(job,variables),frame=>{control.check();val h=StreamProtocol.header(frame);if(h.path("type").asText=="data"){require(output.size.toLong+frame.data.length<=limit,"成果超过应用输出上限");output.write(frame.data)}},cancel,policy)
    observe()
   }finally{monitor.shutdownNow();monitor.awaitTermination(2,TimeUnit.SECONDS);try observe()finally control.onCancel(()=>())}
  }}}
  output.toByteArray
 }
 private def testOperator(o:JsonNode,image:String,control:JobControl):Unit={
  val base=directory.resolve("library").resolve(o.path("name").asText).resolve(o.path("version").asText)
  val name="conet-robot-test-"+java.util.UUID.randomUUID.toString
  val harness="import unittest,sys; sys.path.insert(0,'/code'); s=unittest.defaultTestLoader.discover('/tests'); n=s.countTestCases(); print('tests:',n); r=unittest.TextTestRunner().run(s); sys.exit(0 if n>0 and r.wasSuccessful() else 1)"
  val process=new ProcessBuilder("docker","run","--rm","--name",name,"--network","none","--read-only","--memory","512m","--cpus","1","--pids-limit","64","--cap-drop","ALL","--security-opt","no-new-privileges","--tmpfs","/tmp:rw,noexec,nosuid,size=64m","--mount",s"type=bind,src=${base.resolve("src")},dst=/code,readonly","--mount",s"type=bind,src=${base.resolve("tests")},dst=/tests,readonly","--entrypoint","/usr/local/bin/python",image,"-c",harness).redirectErrorStream(true).start()
  val log=new java.io.ByteArrayOutputStream
  val reader=new Thread(()=>{val in=process.getInputStream;try{val b=new Array[Byte](4096);var n=in.read(b);while(n != -1){if(log.size<65536)log.write(b,0,math.min(n,65536-log.size));n=in.read(b)}}catch{case _:java.io.IOException=>()}finally in.close()},"robot-test-log");reader.setDaemon(true);reader.start()
  val report=directory.resolve("test-reports").resolve(name);Files.createDirectories(report)
  val until=System.nanoTime()+TimeUnit.SECONDS.toNanos(60)
  try{while(!process.waitFor(100,TimeUnit.MILLISECONDS)){control.check();require(System.nanoTime()<until,"本地算子测试超时")};reader.join(2000);Files.write(report.resolve("test.log"),log.toByteArray);require(process.exitValue()==0,"本地算子测试未通过："+new String(log.toByteArray,java.nio.charset.StandardCharsets.UTF_8).takeRight(1500));Support.atomic(report.resolve("test-result.json"),Support.obj.put("operator",o.path("name").asText+"@"+o.path("version").asText).put("passed",true).put("image",image).put("at",Support.now))}
  finally{if(process.isAlive)process.destroyForcibly();val clean=new ProcessBuilder("docker","rm","-f",name).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();clean.waitFor(10,TimeUnit.SECONDS)}
 }
}
