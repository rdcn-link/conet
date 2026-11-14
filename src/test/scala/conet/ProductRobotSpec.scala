package conet

import org.scalatest.funsuite.AnyFunSuite
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import conet.appbuilder.ProductMetadata

class ProductRobotSpec extends AnyFunSuite {
 private def node(s:String)=xstream.util.Json.mapper.readTree(s)
 private def context=node("""{"catalog":{"complete":true,"datasets":[{"id":"d","items":[{"kind":"data","dacp_uri":"dacp://node:1/d/a.csv","views":["bytes"]}]}]},"operators":[{"name":"item_source","version":"1.0"},{"name":"csv_profile","version":"1.0"},{"name":"client_sink","version":"1.0"}]}""")
 private def proposal=node("""{"title":"列质量报告","purpose":"分析缺测与范围","rationale":"目录中存在观测表","research":{"question":"哪些列存在缺测","method":"逐批统计"},"datasets":["d"],"inputs":{},"new_operators":[],"application":{"version":1,"id":"placeholder","title":"分析","description":"分析质量","inputs":[],"variables":{},"result":{"decoder":"json","artifacts":[],"views":[{"type":"table","path":"/rows","columns":["column"]}]}},"job":{"name":"质量分析","steps":[{"id":"source","operator":"item_source@1.0","inputs":{},"params":{"uri":"dacp://node:1/d/a.csv","view":"bytes"}},{"id":"profile","operator":"csv_profile@1.0","inputs":{"source":"source"},"params":{}},{"id":"receive","operator":"client_sink@1.0","inputs":{"result":"profile"},"params":{}}]}}""")
 private class Runtime extends ProductRobotRuntime {
  var complete=true;var submits=0;var state="succeeded";var quality=true;var publishes=0;var permitted=false;var existing:Option[JsonNode]=None
  def context():JsonNode={val n=Support.copy(ProductRobotSpec.this.context);n.path("catalog").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("complete",complete);n}
  def propose(c:JsonNode):JsonNode=proposal
  def prepare(p:JsonNode,c:JsonNode,id:String):JsonNode=Support.obj.put("id","robot-"+id).put("revision","version-1").set[JsonNode]("datasets",node("[\"d\"]"))
  def submit(a:String,i:JsonNode):(Int,JsonNode)={submits+=1;(202,Support.obj.put("id","run-"+submits))}
  def run(a:String,id:String):JsonNode=Support.obj.put("state",state).put("resolved_job_yaml","steps: []")
  def review(a:String,id:String):JsonNode={val m=node("""{"sources":[{"uri":"dacp://node:1/d/a.csv"}],"operators":[{"operator":"csv_profile@1.0"}],"artifacts":[{"bytes":10,"sha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}],"collections":[{"rows":1}],"diagnostics":{"complete":true,"warnings":[]}}""");if(!quality)m.path("collections").get(0).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("rows",0);Support.obj.set[JsonNode]("metadata",m)}
  def publish(a:String,id:String,r:JsonNode,automation:JsonNode):JsonNode={require(permitted,"来源许可尚未确认");assert(automation.has("workflow_snapshot"));publishes+=1;val p=Support.obj.put("id","published").put("dataset_uri","dacp://node:1/result");existing=Some(p);p}
  def published(id:String):Option[JsonNode]=existing
 }
 private def idle(r:ProductRobot):Unit={val until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(4);while(r.status.path("busy").asBoolean&&System.nanoTime()<until)Thread.sleep(10);assert(!r.status.path("busy").asBoolean)}
 test("autonomous workflow executes, requires quality and human rights approval, and replays without planning"){
  val rt=new Runtime;val r=new ProductRobot(Files.createTempDirectory("robot-test"),Support.obj.put("enabled",false),rt)
  try{r.trigger();idle(r);assert(rt.submits==1);r.tick();val n=r.list.get(0);assert(n.path("state").asText=="pending_review");assert(rt.publishes==0);assert(r.owns(n.path("app_id").asText,"run-1"))
   intercept[IllegalArgumentException](r.approve(n.path("id").asText,Support.obj))
   val request=Support.obj.put("review_note","已复核方法和来源许可");intercept[IllegalArgumentException](r.approve(n.path("id").asText,request));assert(r.get(n.path("id").asText).path("state").asText=="pending_review")
   rt.permitted=true;r.approve(n.path("id").asText,request);r.approve(n.path("id").asText,request);assert(rt.publishes==1)
   val replay=r.replay(n.path("id").asText);assert(replay.path("replay_of").asText==n.path("id").asText);assert(rt.submits==2);r.tick();assert(r.get(replay.path("id").asText).path("state").asText=="pending_review")
  }finally r.close()
 }
 test("incomplete catalog blocks submission, empty tables cannot be approved and active runs prevent overlap"){
  val rt=new Runtime;val r=new ProductRobot(Files.createTempDirectory("robot-quality"),Support.obj,rt)
  try{rt.complete=false;r.trigger();idle(r);assert(rt.submits==0);rt.complete=true;rt.quality=false;r.trigger();idle(r);r.tick();val n=r.list.elements();var failed:JsonNode=null;while(n.hasNext){val v=n.next();if(v.path("state").asText=="quality_failed")failed=v};assert(failed!=null);intercept[IllegalArgumentException](r.approve(failed.path("id").asText,Support.obj.put("review_note","批准")));r.reject(failed.path("id").asText,"无有效结果")
   rt.state="running";val replay=r.replay(failed.path("id").asText);intercept[IllegalArgumentException](r.trigger());r.tick();assert(r.get(replay.path("id").asText).path("state").asText=="executing")
  }finally r.close()
 }
 test("restart does not blindly repeat an ambiguous submission"){
  val dir=Files.createTempDirectory("robot-restart");val id="a"*32;Support.write(dir.resolve(id+".json"),Support.obj.put("id",id).put("state","submitting").put("created_at",Support.now));val rt=new Runtime;val r=new ProductRobot(dir,Support.obj,rt)
  try{assert(r.get(id).path("state").asText=="interrupted");r.tick();assert(rt.submits==0)}finally r.close()
 }
 test("workflow validates actual sources and restricts local source packages and model execution settings"){
  val p=proposal;ProductPlanner.validate(p,context);val d=RobotWorkflow.validate(p,context,"robot-test");assert(d.id=="robot-test")
  val bad=Support.copy(p);bad.path("job").path("steps").get(0).path("params").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("uri","file:///etc/passwd");intercept[IllegalArgumentException](RobotWorkflow.validate(bad,context,"robot-test"))
  val remote=Support.copy(p);remote.path("job").path("steps").get(1).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("node","remote");intercept[IllegalArgumentException](RobotWorkflow.validate(remote,context,"robot-test"))
  val inline=Support.copy(p);inline.path("job").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].putObject("operators");intercept[IllegalArgumentException](RobotWorkflow.validate(inline,context,"robot-test"))
  val unreadable=Support.copy(context);unreadable.path("catalog").path("datasets").get(0).path("items").get(0).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put("read_probe_error","metadata is unavailable");intercept[IllegalArgumentException](RobotWorkflow.validate(p,unreadable,"robot-test"))
  val other=Support.copy(p);other.set[JsonNode]("datasets",node("[\"other\"]"));intercept[IllegalArgumentException](ProductPlanner.validate(other,context))
 }
 test("persisted source metadata compares numeric values independently of Jackson integer width"){
  val a=Support.obj.put("bytes",42L);val b=xstream.util.Json.mapper.readTree(a.toString)
  assert(RobotWorkflow.sameMetadata(a,b));assert(!RobotWorkflow.sameMetadata(a,Support.obj.put("bytes",43)))
 }

 test("generated local packages include required presentation, fixed runtime and immutable source checks"){
  val root=Files.createTempDirectory("robot-package");Support.writeText(root.resolve("conet.yaml"),"cases: []\ncenters: []\nappbuilder:\n  product_robot:\n    local_runtime:\n      image: sha256:"+("a"*64)+"\n")
  val settings=new Settings(root);val workflows=new RobotWorkflows(settings,new CatalogGateway(settings))
  try{
   val p=Support.copy(proposal);val step=p.path("job").path("steps").get(1).asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode];step.set[JsonNode]("operator",Support.obj.put("local","csv_profile@1.0"))
   val op=Support.obj.put("name","csv_profile").put("version","1.0").put("description","统计字段质量").put("source","def analyze(x): return len(x)\n").put("tests","import unittest\n")
   op.set[JsonNode]("inputs",node("{\"source\":{\"representations\":[\"bytes\"],\"formats\":[]}}"));op.set[JsonNode]("parameters",node("{\"type\":\"object\",\"properties\":{},\"required\":[],\"additionalProperties\":false}"));p.set[JsonNode]("new_operators",Support.arr(Seq(op)))
   val saved=workflows.prepare(p,context,"b"*32);val directory=root.resolve(".state/product-robot/workflows").resolve(saved.path("id").asText);val manifest=xstream.util.YamlDocument.parse(Files.readString(directory.resolve("library/csv_profile/1.0/operator.yaml")))
   xstream.operator.OperatorPresentation.validate(manifest);assert(manifest.path("role").asText=="transform");assert(!manifest.path("source_public").asBoolean)
   val bundle=workflows.bundle(saved.path("id").asText);assert(bundle.path("files").size>=6);assert(bundle.path("runtime").path("image").asText=="sha256:"+("a"*64));assert(workflows.get(saved.path("id").asText).get.allowLocalOperators)
   Files.writeString(directory.resolve("library/csv_profile/1.0/src/csv_profile.py"),"changed")
   intercept[IllegalArgumentException]{new RobotLocalBackend(settings,directory,new CatalogGateway(settings)).execute("",Support.obj,1024,new conet.appbuilder.JobControl,_=>(),_=>())}
  }finally{workflows.close();settings.analysisScheduler.close()}
 }

 test("local observation uses the root Job and its steps rather than the newest child record"){
  val root=node("""{"record_id":"root","kind":"local_job","state":"succeeded","attempt_id":"attempt"}""")
  val children=node("""[{"record_id":"child","kind":"job_task","task_id":"profile","parent_execution_id":"root","state":"succeeded"},{"record_id":"other","kind":"job_task","task_id":"ignored","parent_execution_id":"previous","state":"succeeded"}]""")
  val history=Vector(children.get(0),children.get(1),root);val snapshot=RobotWorkflow.executionSnapshot(root,history)
  assert(snapshot.path("record_id").asText=="root");assert(snapshot.path("steps").size==1);assert(snapshot.path("steps").get(0).path("id").asText=="profile")
  val run=Support.obj.put("id","run").put("app_id","app");run.set[JsonNode]("step_operators",Support.obj.put("profile","profile@1.0"));conet.appbuilder.Usage.observe(run,snapshot)
  assert(run.path("usage").path("operator_events").size==1);assert(run.path("usage").path("node_events").elements.next.path("node").asText=="CONET 本地 XStream")
 }

}
