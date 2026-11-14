package conet.appbuilder

import org.scalatest.funsuite.AnyFunSuite
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path}
import java.util.concurrent.{CountDownLatch,TimeUnit}
import xstream.util.{Json,YamlDocument}

class AppBuilderSpec extends AnyFunSuite {
 def json(s:String)=Json.mapper.readTree(s)
 val job="""name: Example
steps:
- id: source
  operator: read_item@1.0
  params: {uri: $SOURCE, view: bytes}
- id: process
  operator: example@1.0
  params: {threshold: $THRESHOLD}
  inputs: {source: 'source:result'}
outputs: {result: 'process:result'}
"""
 val config="""version: 1
id: example
title: 示例
description: 分析示例数据并生成结果。
job: job.yaml
inputs:
- {name: threshold, widget: number, default: 0.5, min: 0, max: 1}
variables:
  THRESHOLD: {input: threshold}
  SOURCE: {dataset: samples, item: input.json}
result:
  decoder: json
  artifacts:
  - {id: csv, file: rows.csv, format: csv, path: /rows, columns: [name, value]}
  summary: {rows: {path: /rows, operation: count}}
  views: [{type: table, path: /rows}]
limits: {max_queued: 2, max_result_bytes: 1024}
"""
 test("bindings follow actual Job scalar references, including lowercase variables, not comments or prose") {
  val lowerJob=job.replace("$THRESHOLD","$threshold")+"# $COMMENT_ONLY is documentation\n"
  val lowerConfig=config.replace("THRESHOLD:","threshold:")
  assert(Definition.parse(lowerConfig,lowerJob).variableBindings.contains("threshold"))
  val prose=lowerJob.replace("name: Example","name: Example mentions $UNBOUND in prose")
  assert(Definition.parse(lowerConfig,prose).id=="example")
  intercept[IllegalArgumentException](Definition.parse(config,lowerJob))
 }

 test("backgrounds accept trusted asset paths, uploads, HTTPS or explicit no-background") {
  for(value<-Seq("/assets/background-indigo.svg","/appbuilder/media/"+("a"*64)+".png","https://example.org/background.jpg","null")){
   val yaml=if(value=="null")"null" else "\""+value+"\""
   val d=Definition.parse(config+"presentation: {theme: indigo, background: "+yaml+"}\n",job)
   assert(d.config.path("presentation").has("background"))
  }
  for(value<-Seq("http://example.org/image.png","javascript:alert(1)","file:///tmp/image.png","https://user:secret@example.org/image.png")){
   intercept[IllegalArgumentException](Definition.parse(config+"presentation: {background: \""+value+"\"}\n",job))
  }
 }

 val manifest=json("{}")
 val result="""{"rows":[{"name":"safe","value":3},{"name":"=1+1","value":4}]}""".getBytes("UTF-8")
 val definition=Definition.parse(config,job)
 def await(s:ApplicationService,id:String):JsonNode={val limit=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);var n=s.get(id).get;while(Set("queued","running")(n.path("state").asText)&&System.nanoTime()<limit){Thread.sleep(10);n=s.get(id).get};n}
 class Backend extends JobBackend {
  def webUrls=json("{}")
  def datasets(scope:String)=Map("samples"->"dacp://node/samples")
  def execute(job:String,v:JsonNode,limit:Long,c:JobControl,started:String=>Unit,status:JsonNode=>Unit):Array[Byte]={started("execution-1");val s=json("""{"job_id":"execution-1","attempt_id":"attempt-1","steps":[{"id":"source","state":"succeeded"},{"id":"process","state":"running"}],"nodes":{"node-id":{"execution_id":"execution-1","steps":{"source":"succeeded","process":"succeeded"}}}}""");status(s);status(s);result}
 }
 test("configuration binds typed values and validates inputs and unsafe paths"){
  val inputs=definition.validate(manifest,manifest);assert(inputs.path("threshold").asDouble==.5)
  val variables=definition.variables(inputs,manifest,Map("samples"->"dacp://node/samples"));assert(variables.path("SOURCE").asText=="dacp://node/samples/input.json")
  val nested=Definition.parse(config.replace("input: threshold","input: event.threshold").replace("- {name: threshold, widget: number, default: 0.5, min: 0, max: 1}","- name: event\n  widget: object\n  fields: [{name: threshold, widget: number, default: 0.5, min: 0, max: 1}]"),job)
  assert(nested.variables(nested.validate(json("""{"event":{}}"""),manifest),manifest,Map("samples"->"dacp://node/samples")).path("THRESHOLD").asDouble==.5)
  intercept[IllegalArgumentException](definition.validate(json("""{"threshold":2}"""),manifest))
  intercept[IllegalArgumentException](definition.validate(json("""{"extra":1}"""),manifest))
  intercept[IllegalArgumentException](Definition.parse(config.replace("rows.csv","../rows.csv"),job))
  intercept[IllegalArgumentException](Definition.parse(config,job.replace("$THRESHOLD","$UNKNOWN")))
  intercept[IllegalArgumentException](definition.variables(inputs,manifest,Map.empty))
 }
 test("publication rejects cycles, dangling references and unsafe cover URLs"){
  intercept[IllegalArgumentException](Definition.parse(config,job.replace("source:result","missing:result")))
  intercept[IllegalArgumentException](Definition.parse(config,job.replace("inputs: {source: 'source:result'}","inputs: {}")))
  intercept[IllegalArgumentException](Definition.parse(config,job.replace("operator: read_item@1.0","operator: read_item@1.0\n  inputs: {dependency: 'process:result'}")))
  intercept[IllegalArgumentException](Definition.parse(config+"presentation: {cover: 'javascript:alert(1)'}\n",job))
 }
 test("result decoders disable XML entities and produce bounded explicit artifacts"){
  val output=Files.createTempDirectory("builder-results-");val r=Results.write(definition,output,result,json("{}"));assert(Results.summary(definition,r).path("rows").asInt==2)
  assert(Files.readString(output.resolve("rows.csv")).contains("'=1+1"))
  val svg=new SvgMetadataDecoder
  assert(svg.decode("""<svg xmlns="http://www.w3.org/2000/svg"><metadata>{"rows":[]}</metadata></svg>""".getBytes).has("rows"))
  intercept[Exception](svg.decode("""<!DOCTYPE svg [<!ENTITY secret SYSTEM "file:///etc/passwd">]><svg xmlns="http://www.w3.org/2000/svg"><metadata>&secret;</metadata></svg>""".getBytes))
 }
 test("successful jobs persist history and deduplicate application, dataset, operator and node statistics"){
  val state=Files.createTempDirectory("builder-job-");val s=new ApplicationService(definition,state,()=>manifest,new Backend,"/apps/example")
  val id=try{val(code,n)=s.submit(manifest);assert(code==202);val done=await(s,n.path("id").asText);assert(done.path("state").asText=="succeeded",done.toString);assert(done.path("summary").path("rows").asInt==2)
   assert(done.path("resolved_job_yaml").isTextual);assert(!s.plan(done.path("id").asText).path("yaml").asText.contains("$SOURCE"));assert(s.artifact(done.path("id").asText,"rows.csv").exists(Files.isRegularFile(_)));
   val metadata=Json.mapper.readTree(Files.readAllBytes(s.artifact(done.path("id").asText,"metadata.json").get));assert(metadata.path("state").asText=="succeeded");assert(metadata.path("artifacts").size==2);assert(metadata.path("sources").size==1);assert(s.artifact(done.path("id").asText,"report.md").exists(Files.isRegularFile(_)));
   assert(s.artifact(done.path("id").asText,"../variables.yaml").isEmpty)
   val stats=Usage.aggregate(s.records);assert(stats.path("submissions").asInt==1);assert(stats.path("datasets").get(0).path("count").asInt==1);assert(stats.path("operators").size==2);assert(stats.path("nodes").get(0).path("count").asInt==1);done.path("id").asText
  }finally s.close()
  val restored=new ApplicationService(definition,state,()=>manifest,new Backend,"/apps/example");try{assert(restored.get(id).get.path("state").asText=="succeeded");assert(Usage.aggregate(restored.records).path("submissions").asInt==1)}finally restored.close()
 }
 test("rerun uses saved Job, variables and manifest after the application is edited") {
  val state=Files.createTempDirectory("builder-rerun-");val original=new ApplicationService(definition,state,()=>manifest,new Backend,"/apps/example")
  val first=try{val (_,n)=original.submit(manifest);val done=await(original,n.path("id").asText);assert(done.path("state").asText=="succeeded");done}finally original.close()
  val changed=Definition.parse(config.replace("default: 0.5","default: 0.9"),job.replace("name: Example","name: Changed"))
  val backend=new Backend{override def execute(j:String,v:JsonNode,l:Long,c:JobControl,start:String=>Unit,status:JsonNode=>Unit):Array[Byte]={assert(j==first.path("job_yaml").asText);assert(v==first.path("variables"));super.execute(j,v,l,c,start,status)}}
  val service=new ApplicationService(changed,state,()=>json("{}"),backend,"/apps/example")
  try{val (code,n)=service.rerun(first.path("id").asText);assert(code==202);val done=await(service,n.path("id").asText);assert(done.path("state").asText=="succeeded",done.toString);assert(done.path("rerun_of").asText==first.path("id").asText);assert(done.path("id")!=first.path("id"));assert(done.path("job_yaml")==first.path("job_yaml"))}finally service.close()
 }
 test("unavailable inputs reject rerun before creating a new analysis") {
  var available=true
  val files=new InputFiles {
   def upload(d:Definition,k:String,n:String,in:java.io.InputStream):JsonNode=throw new UnsupportedOperationException()
   def resolve(d:Definition,p:JsonNode,r:Option[String])=Values.copy(p)
   override def status(d:Definition,r:JsonNode):JsonNode=Values.obj.put("available",available).put("reason","输入快照已过期，请重新上传文件")
  }
  val service=new ApplicationService(definition,Files.createTempDirectory("builder-rerun-expired-"),()=>manifest,new Backend,"/apps/example",files)
  try {val (_,n)=service.submit(manifest);val done=await(service,n.path("id").asText);assert(done.path("state").asText=="succeeded");available=false
   assert(intercept[IllegalArgumentException](service.rerun(done.path("id").asText)).getMessage.contains("过期"));assert(service.records.size==1)
  }finally service.close()
 }
 test("queued and running cancellation does not execute a queued job or retain partial results"){
  val entered=new CountDownLatch(1);val backend=new Backend{override def execute(j:String,v:JsonNode,l:Long,c:JobControl,start:String=>Unit,status:JsonNode=>Unit):Array[Byte]={start("running");entered.countDown();while(!c.cancelled.get())Thread.sleep(5);c.check();result}}
  val s=new ApplicationService(definition,Files.createTempDirectory("builder-cancel-"),()=>manifest,backend,"/apps/example")
  try{val first=s.submit(manifest)._2.path("id").asText;assert(entered.await(3,TimeUnit.SECONDS));val second=s.submit(manifest)._2.path("id").asText;assert(s.submit(manifest)._1==429);s.cancel(second);s.cancel(first);assert(await(s,first).path("state").asText=="cancelled");assert(await(s,second).path("state").asText=="cancelled");assert(s.artifact(second,"analysis.json").isEmpty)}finally s.close()
 }
 test("shared admission rejects across apps and rechecks disk before queued execution"){
  val free=new java.util.concurrent.atomic.AtomicLong(1000)
  val scheduler=new AnalysisScheduler(1,1,100,_=>free.get())
  val entered=new CountDownLatch(1);val release=new CountDownLatch(1)
  val calls=new java.util.concurrent.atomic.AtomicInteger()
  val backend=new Backend{override def execute(j:String,v:JsonNode,l:Long,c:JobControl,start:String=>Unit,status:JsonNode=>Unit):Array[Byte]={calls.incrementAndGet();start("running");entered.countDown();assert(release.await(5,TimeUnit.SECONDS));c.check();result}}
  def service(name:String)=new ApplicationService(Definition.parse(config.replace("id: example","id: "+name),job),Files.createTempDirectory("shared-app-"),()=>manifest,backend,"/apps/"+name,sharedScheduler=Some(scheduler))
  val a=service("first");val b=service("second");val c=service("third")
  try{
   assert(a.submit(manifest)._1==202);assert(entered.await(3,TimeUnit.SECONDS))
   val queued=b.submit(manifest);assert(queued._1==202)
   assert(c.submit(manifest)._1==429);assert(c.records.isEmpty)
   free.set(0);assert(c.submit(manifest)._1==507);assert(c.records.isEmpty)
   release.countDown()
   val failed=await(b,queued._2.path("id").asText)
   assert(failed.path("state").asText=="failed");assert(failed.path("error").asText.contains("存储空间不足"));assert(calls.get()==1)
  }finally{release.countDown();a.close();b.close();c.close();scheduler.close()}
 }
 test("retry observations count each invocation while node polling remains idempotent"){
  val record=Values.obj.put("id","job").put("app_id","example");record.set[JsonNode]("step_operators",json("""{"source":"read_item@1.0","process":"example@1.0"}"""))
  val first=json("""{"attempt_id":"one","steps":[{"id":"process","state":"running"}],"nodes":{"n":{"execution_id":"one"}}}""")
  Usage.observe(record,first);Usage.observe(record,first)
  Usage.observe(record,json("""{"attempt_id":"two","steps":[{"id":"process","state":"succeeded"}],"nodes":{"n":{"execution_id":"two"},"unreceived":{"state":"pending"}}}"""))
  val counts=Usage.aggregate(Vector(record));assert(counts.path("operators").get(0).path("count").asInt==2);assert(counts.path("nodes").get(0).path("count").asInt==2)
 }
 test("failed output is not published"){
  val backend=new Backend{override def execute(j:String,v:JsonNode,l:Long,c:JobControl,start:String=>Unit,status:JsonNode=>Unit):Array[Byte]={start("bad");"invalid JSON".getBytes}}
  val state=Files.createTempDirectory("builder-failure-");val s=new ApplicationService(definition,state,()=>manifest,backend,"/apps/example")
  try{val id=s.submit(manifest)._2.path("id").asText;assert(await(s,id).path("state").asText=="failed");assert(s.artifact(id,"analysis.json").isEmpty)}finally s.close()
 }
}
