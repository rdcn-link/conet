package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters._
import xstream.util.{Json,YamlDocument,WorkflowVariables}
import scala.util.Using

/** Deterministic provenance. Unknown facts and self-declared rights remain explicit. */
object ProductMetadata {
 def digest(bytes:Array[Byte]):String=MessageDigest.getInstance("SHA-256").digest(bytes).map(b=>f"${b&255}%02x").mkString
 def fileDigest(path:Path):String={val digest=MessageDigest.getInstance("SHA-256");Using.resource(Files.newInputStream(path)){in=>val buffer=new Array[Byte](65536);var count=in.read(buffer);while(count>=0){if(count>0)digest.update(buffer,0,count);count=in.read(buffer)}};digest.digest().map(b=>f"${b&255}%02x").mkString}
 private val secret="(?i).*(token|password|secret|credential|authorization|private.?key|access.?key).*".r
 def sanitize(n:JsonNode):JsonNode=if(n.isObject){val out=Values.obj;n.fields.asScala.foreach(e=>out.set[JsonNode](e.getKey,if(secret.pattern.matcher(e.getKey).matches)Json.mapper.getNodeFactory.textNode("[redacted]")else sanitize(e.getValue)));out}else if(n.isArray)Values.array(n.elements.asScala.map(sanitize).toVector)else if(n.isTextual){val s=n.asText;val clean=try{val uri=java.net.URI.create(s);if(uri.getScheme!=null&&uri.getHost!=null)new java.net.URI(uri.getScheme,null,uri.getHost,uri.getPort,uri.getPath,null,null).toString else s}catch{case _:IllegalArgumentException=>s};Json.mapper.getNodeFactory.textNode(clean)}else n.deepCopy[JsonNode]()
 def build(definition:Definition,run:JsonNode,output:Path):JsonNode={
  val out=Values.obj.put("version",1).put("run_id",run.path("id").asText).put("title",definition.config.path("title").asText).put("description",definition.config.path("description").asText).put("state",run.path("state").asText).put("synthetic",run.path("synthetic").asBoolean).put("generated_at",java.time.Instant.now.toString)
  out.set[JsonNode]("application",Values.obj.put("id",definition.id).put("definition_sha256",digest(YamlDocument.dump(definition.config).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
  val job=YamlDocument.parse(definition.jobYaml);out.set[JsonNode]("job",Values.obj.put("name",job.path("name").asText).put("id",run.path("job_id").asText).put("definition_sha256",digest(definition.jobYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
  val timing=Values.obj;Seq("created_at","started_at","finished_at").foreach(k=>if(run.has(k))timing.set[JsonNode](k,run.get(k)))
  for(start<-scala.util.Try(java.time.Instant.parse(run.path("started_at").asText)).toOption;end<-scala.util.Try(java.time.Instant.parse(run.path("finished_at").asText)).toOption)timing.put("duration_ms",java.time.Duration.between(start,end).toMillis)
  out.set[JsonNode]("timing",timing)
  val fields=definition.inputs.map(_.path("name").asText).filter(run.has);val inputs=Values.obj;fields.foreach(k=>inputs.set[JsonNode](k,sanitize(run.get(k))));out.set[JsonNode]("inputs",inputs);out.set[JsonNode]("variables",sanitize(run.path("variables")));out.set[JsonNode]("summary",sanitize(run.path("summary")));out.set[JsonNode]("initiator",if(run.path("submitted_by").isObject)run.path("submitted_by")else Values.obj.put("name","未记录账号"))
  val bindings=run.path("dataset_bindings").elements.asScala.toVector
  val resolved=YamlDocument.parse(WorkflowVariables.resolve(definition.jobYaml,run.path("variables")))
  val reads=resolved.path("steps").elements.asScala.filter(s=>Set("read_item","item_source","csv_items_source","folder_items_source")(s.path("operator").asText.takeWhile(_ != '@'))).map{s=>
   val uri=s.path("params").path("uri").asText;val dataset=bindings.find(b=>uri.startsWith(b.path("uri").asText.stripSuffix("/")+"/"));val source=Values.obj.put("id",digest(uri.getBytes(java.nio.charset.StandardCharsets.UTF_8)).take(24)).put("step",s.path("id").asText).put("uri",sanitize(Json.mapper.getNodeFactory.textNode(uri)).asText).put("view",s.path("params").path("view").asText("default"))
   dataset.foreach(b=>source.put("dataset",b.path("name").asText).put("dataset_uri",b.path("uri").asText));source.put("observation",if(dataset.exists(b=>run.path("usage").path("dataset_events").has(b.path("uri").asText)))"observed"else"referenced");source
  }.toVector;out.set[JsonNode]("sources",Values.array(reads))
  out.set[JsonNode]("operators",Values.array(job.path("steps").elements.asScala.map{s=>val n=Values.obj.put("step",s.path("id").asText).put("operator",Definition.operatorId(s));definition.config.path("publication").path("operator_contributors").elements.asScala.find(_.path("operator").asText==s.path("operator").asText).foreach(c=>Seq("name","license","attribution").foreach(k=>if(c.has(k))n.set[JsonNode](k,c.get(k))));n}.toVector))
  out.set[JsonNode]("observed_usage",sanitize(run.path("usage")))
  out.set[JsonNode]("execution_nodes",Values.array(run.path("usage").path("node_events").elements.asScala.toVector));def descriptors(fields:JsonNode):JsonNode=Values.array(fields.elements.asScala.map{f=>val n=Values.obj;Seq("name","label","widget","unit").foreach(k=>if(f.has(k))n.set[JsonNode](k,f.get(k)));if(f.has("fields"))n.set[JsonNode]("fields",descriptors(f.get("fields")));n}.toVector)
  out.set[JsonNode]("input_definitions",descriptors(definition.config.path("inputs")));out.set[JsonNode]("views",definition.config.path("result").path("views"))
  val collections=definition.config.path("result").path("views").elements.asScala.filter(v=>Set("table","scatter")(v.path("type").asText)).map{v=>val n=Values.obj.put("title",v.path("title").asText).put("path",v.path("path").asText);val selected=run.path("result").at(v.path("path").asText);if(selected.isArray)n.put("rows",selected.size);n}.toVector
  out.set[JsonNode]("collections",Values.array(collections))
  val extents=definition.config.path("result").path("views").elements.asScala.filter(_.path("type").asText=="geojson").flatMap{view=>
   val points=scala.collection.mutable.ArrayBuffer.empty[(Double,Double)]
   def coordinates(n:JsonNode):Unit=if(n.isArray){if(n.size>=2&&n.get(0).isNumber&&n.get(1).isNumber){val x=n.get(0).asDouble;val y=n.get(1).asDouble;if(java.lang.Double.isFinite(x)&&java.lang.Double.isFinite(y))points+=((x,y))}else n.elements.asScala.foreach(coordinates)}
   def geometry(n:JsonNode):Unit={if(n.has("coordinates"))coordinates(n.get("coordinates"));Seq("geometry","geometries","features").foreach(k=>if(n.path(k).isArray)n.path(k).elements.asScala.foreach(geometry)else if(n.path(k).isObject)geometry(n.get(k)))}
   geometry(run.path("result").at(view.path("path").asText));if(points.isEmpty)None else{val bbox=Json.mapper.createArrayNode();Seq(points.map(_._1).min,points.map(_._2).min,points.map(_._1).max,points.map(_._2).max).foreach(bbox.add);val n=Values.obj.put("title",view.path("title").asText).put("path",view.path("path").asText).put("coordinates","GeoJSON coordinates as supplied");n.set[JsonNode]("bbox",bbox);Some(n)}
  }.toVector;out.set[JsonNode]("spatial_extents",Values.array(extents))
  val diagnostics=Values.obj;Seq("warnings","complete","missing_sources","stale_sources").foreach(k=>if(run.path("result").has(k))diagnostics.set[JsonNode](k,sanitize(run.path("result").get(k))));out.set[JsonNode]("diagnostics",diagnostics)
  val artifactDefs=definition.artifacts:+Values.obj.put("id","analysis").put("label","分析结果 JSON").put("file","analysis.json").put("format","json")
  out.set[JsonNode]("artifacts",Values.array(artifactDefs.map{a=>val file=Values.relative(output,a.path("file").asText);val n=Values.copy(a);n.put("bytes",Files.size(file)).put("sha256",fileDigest(file));if(a.path("format").asText=="csv")n.put("rows",Results.rows(run.path("result"),a).size);n}))
  out.set[JsonNode]("publication_defaults",if(definition.config.path("publication").isObject)definition.config.path("publication")else Values.obj)
  out.set[JsonNode]("limitations",Values.array((Seq("未提供的数据版本、单位、许可证和作者信息不作推断。","来源标为 referenced 时，表示执行观察未确认实际读取。","署名和许可声明需要发布者确认；平台账号不表示数据权利或现实身份已经核验。")++(if(run.path("synthetic").asBoolean)Seq("本次结果基于合成演示数据，不作为实际业务或科研结论。")else Seq.empty)).map(Json.mapper.getNodeFactory.textNode)))
  val summaryLabels=Values.obj;definition.config.path("result").path("summary").fields.asScala.foreach(e=>summaryLabels.put(e.getKey,e.getValue.path("label").asText(e.getKey)));out.set[JsonNode]("summary_labels",summaryLabels)
  val summaryText=run.path("summary").fields.asScala.filterNot(_.getKey=="job_id").take(8).map(e=>summaryLabels.path(e.getKey).asText(e.getKey)+"："+e.getValue.asText).mkString("；")
  val narrative=s"本次分析的流程引用了 ${reads.map(_.path("id").asText).distinct.size} 项数据来源，包含 ${job.path("steps").size} 个处理步骤，生成 ${artifactDefs.size} 项成果。"+(if(summaryText.nonEmpty)"结果摘要："+summaryText+"。"else "未配置数值摘要。")+(if(run.path("result").path("complete").isBoolean&& !run.path("result").path("complete").asBoolean)"结果标记为不完整，请核对缺测和警告信息。"else "")+(if(run.path("synthetic").asBoolean)"这是合成数据演示结果。"else "")
  out.put("abstract",narrative)
  out
 }
 def report(n:JsonNode):String={
  val b=new StringBuilder("# "+n.path("title").asText+"\n\n"+n.path("description").asText+"\n\n"+n.path("abstract").asText+"\n\n")
  b.append("## 分析记录\n\n");Seq("run_id","state","generated_at","synthetic").foreach(k=>b.append(s"- $k: ${n.path(k).asText}\n"))
  for((key,title)<-Seq("timing"->"执行时间","inputs"->"分析条件","summary"->"结果摘要","collections"->"数据记录数","spatial_extents"->"空间范围","diagnostics"->"数据完整性与警告","sources"->"数据来源","operators"->"算子与版本","execution_nodes"->"执行节点","artifacts"->"成果清单","rights"->"权益与使用许可","automation"->"自动加工与人工审批"))if(n.has(key))b.append(s"\n## $title\n\n```json\n${Json.mapper.writerWithDefaultPrettyPrinter.writeValueAsString(n.get(key))}\n```\n")
  b.append("\n## 适用限制\n\n");n.path("limitations").elements.asScala.foreach(v=>b.append("- "+v.asText+"\n"));b.toString
 }
 def write(definition:Definition,run:JsonNode,output:Path):Unit={val metadata=build(definition,run,output);def atomic(name:String,bytes:Array[Byte]):Unit={val target=output.resolve(name);val temp=output.resolve(name+".tmp");Files.write(temp,bytes);Files.move(temp,target,java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING)};atomic("metadata.json",Json.mapper.writerWithDefaultPrettyPrinter.writeValueAsBytes(metadata));atomic("report.md",report(metadata).getBytes(java.nio.charset.StandardCharsets.UTF_8))}
}
