package conet

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** Declarative, version-specific explanations; display metadata never changes the executed YAML. */
object PlanNarrative {
 private val placeholder="\\{\\{([^{}]+)\\}\\}".r
 def enrich(plan:JsonNode,templates:JsonNode,datasets:JsonNode,publicHost:String):JsonNode={
  val out=Support.copy(plan);val steps=out.path("job").path("steps").elements.asScala.toVector
  def source(s:JsonNode):String={
   val raw=s.path("params").path("uri").asText
   val matched=scala.util.Try(URI.create(raw)).toOption.flatMap{uri=>datasets.elements.asScala.find{d=>scala.util.Try{val root=URI.create(d.path("dacp_uri").asText);val local=Set("127.0.0.1","localhost",publicHost);val sameHost=uri.getHost==root.getHost||(local(uri.getHost)&&local(root.getHost));sameHost&&uri.getPort==root.getPort&&(uri.getPath==root.getPath||uri.getPath.startsWith(root.getPath+"/"))}.getOrElse(false)}}
   matched.map{d=>val item=URI.create(raw).getPath.stripPrefix(URI.create(d.path("dacp_uri").asText).getPath);d.path("node_title").asText+"的数据集“"+d.path("title").asText+"”中的 Item“"+item+"”"}.getOrElse("Item“"+raw+"”")
  }
  def sources(s:JsonNode,seen:Set[String]=Set.empty):Vector[String]={val id=s.path("id").asText;if(seen(id))Vector.empty else if(Set("read_item","item_source","csv_items_source","folder_items_source")(s.path("operator").asText.takeWhile(_ != '@')))Vector(source(s))else s.path("inputs").elements.asScala.toVector.flatMap(v=>steps.find(_.path("id").asText==v.asText.split(":")(0)).toVector.flatMap(x=>sources(x,seen+id))).distinct}
  steps.foreach{s=>val step=s.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode];val op=s.path("operator").asText;val context=Support.copy(s);context.put("source_summary",sources(s).mkString("、"))
   val text=if(Set("read_item","item_source","csv_items_source","folder_items_source")(op.takeWhile(_ != '@')))"从"+source(s)+"读取数据，使用“"+s.path("params").path("view").asText("默认")+"”视图，将内容作为数据流传递给下游步骤" else if(op=="client_sink@1.0")"将加工结果流返回调用方，供结果预览、成果下载与人工审批使用" else if(templates.path("operators").path(op).isTextual)placeholder.replaceAllIn(templates.path("operators").path(op).asText,m=>java.util.regex.Matcher.quoteReplacement({val value=context.at(m.group(1));if(value.isMissingNode)"未记录"else if(value.isTextual)value.asText else value.toString})).trim else s.path("description").asText(s.path("title").asText("使用算子“"+op+"”处理上游数据；该版本尚未提供详细用途说明"))
   step.put("explanation",text)
  };out
 }
 def apply(plan:JsonNode,settings:Settings,catalog:CatalogGateway):JsonNode={
  val path=settings.root.resolve(settings.config.path("appbuilder").path("process_descriptions").asText("config/process-descriptions.yaml"))
  val templates=if(Files.isRegularFile(path))xstream.util.YamlDocument.parse(Files.readString(path))else Support.obj
  val datasets=scala.util.Try(catalog.list().path("datasets")).getOrElse(Support.arr(Seq.empty))
  enrich(plan,templates,datasets,settings.publicHost)
 }
}
