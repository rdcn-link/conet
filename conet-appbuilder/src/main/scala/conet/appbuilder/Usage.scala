package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import scala.jdk.CollectionConverters._

/** Persist event identities inside the job record: status polling and process restarts cannot double count. */
object Usage {
 private def objectAt(parent:ObjectNode,key:String):ObjectNode=if(parent.path(key).isObject)parent.get(key).asInstanceOf[ObjectNode]else{val n=Values.obj;parent.set[JsonNode](key,n);n}
 def observe(job:ObjectNode,status:JsonNode):Unit={
  val usage=objectAt(job,"usage");val operators=objectAt(usage,"operator_events");val datasets=objectAt(usage,"dataset_events");val nodes=objectAt(usage,"node_events")
  val stepOperators=job.path("step_operators")
  def attempt(s:JsonNode,fallback:String):Unit={
   val attemptId=s.path("attempt_id").asText(fallback)
   val stepNodes=s.path("steps").elements.asScala.filter(_.has("node_identity")).map(n=>n.path("node_identity").asText->n.path("node").asText).toMap
   def step(id:String,state:String):Unit=if(Set("running","succeeded","failed")(state)&&stepOperators.has(id)){
    operators.put(attemptId+"/"+id,stepOperators.path(id).asText)
    job.path("dataset_bindings").elements.asScala.filter(_.path("steps").elements.asScala.exists(_.asText==id)).foreach{d=>datasets.set[JsonNode](d.path("uri").asText,d)}
   }
   val values=s.path("steps");if(values.isArray)values.elements.asScala.foreach(n=>step(n.path("id").asText,n.path("state").asText))
   s.path("nodes").fields.asScala.foreach{e=>val n=e.getValue
    if(n.path("execution_id").asText.nonEmpty){val key=e.getKey+"/"+n.path("execution_id").asText;nodes.set[JsonNode](key,Values.obj.put("identity",e.getKey).put("node",stepNodes.getOrElse(e.getKey,nodes.path(key).path("node").asText(e.getKey))).put("execution_id",n.path("execution_id").asText))}
    n.path("steps").fields.asScala.foreach(e=>step(e.getKey,e.getValue.asText))
   }
  }
  attempt(status,status.path("job_id").asText(job.path("job_id").asText(job.path("id").asText)))
  status.path("attempts").elements.asScala.foreach(a=>attempt(a,a.path("attempt_id").asText))
 }
 def aggregate(jobs:Seq[JsonNode]):JsonNode={
  val apps=scala.collection.mutable.Map.empty[String,Long];val operators=scala.collection.mutable.Map.empty[String,Long];val datasets=scala.collection.mutable.Map.empty[String,Long];val nodes=scala.collection.mutable.Map.empty[String,Long]
  def add(m:scala.collection.mutable.Map[String,Long],key:String):Unit=m(key)=m.getOrElse(key,0L)+1
  val nodeNames=scala.collection.mutable.Map.empty[String,String]
  jobs.foreach{j=>val appId=j.path("app_id").asText(j.path("definition").path("id").asText("unknown"));nodeNames(appId)=j.path("definition").path("title").asText(appId);add(apps,j.path("app_id").asText(j.path("definition").path("id").asText("unknown")))
   j.path("usage").path("operator_events").elements.asScala.foreach(n=>add(operators,n.asText))
   j.path("usage").path("dataset_events").elements.asScala.foreach{n=>val uri=n.path("uri").asText;add(datasets,uri);nodeNames(uri)=n.path("name").asText(uri)+" · "+uri}
   j.path("usage").path("node_events").elements.asScala.foreach{n=>val id=n.path("identity").asText;add(nodes,id);val name=n.path("node").asText(id);if(name!=id|| !nodeNames.contains(id))nodeNames(id)=name}
  }
  def rows(m:scala.collection.mutable.Map[String,Long]):JsonNode=Values.array(m.toVector.sortBy{case(k,v)=>(-v,k)}.map{case(k,v)=>Values.obj.put("id",k).put("name",nodeNames.getOrElse(k,k)).put("count",v)})
  val out=Values.obj.put("submissions",jobs.size).put("recorded_at",java.time.Instant.now.toString)
  out.set[JsonNode]("apps",rows(apps));out.set[JsonNode]("datasets",rows(datasets));out.set[JsonNode]("operators",rows(operators));out.set[JsonNode]("nodes",rows(nodes))
  out.put("scope","CONET App Builder job records; dataset uses once per submitted job, operator calls once per observed step and attempt, node receipts once per execution and node")
  out.put("observed_jobs",jobs.count(_.has("usage")));out
 }
}
