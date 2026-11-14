package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import xstream.util.{Json,YamlDocument,WorkflowVariables}
import java.nio.file.{Files,Path}
import scala.jdk.CollectionConverters._

object Values {
 def obj:ObjectNode=Json.obj()
 def array(values:Iterable[JsonNode]):JsonNode={val a=Json.mapper.createArrayNode();values.foreach(a.add);a}
 def copy(n:JsonNode):ObjectNode=n.deepCopy[ObjectNode]()
 def at(n:JsonNode,p:String):JsonNode={require(p.isEmpty||p.startsWith("/"),"数据字段路径必须为 JSON Pointer");n.at(p)}
 def relative(root:Path,name:String):Path={val base=root.toAbsolutePath.normalize;val p=base.resolve(name).normalize;require(name.nonEmpty && !Path.of(name).isAbsolute && p.startsWith(base) && !name.split("/",-1).exists(x=>x==".."||x.isEmpty),"文件路径必须位于应用目录内");p}
 def safeId(id:String):String={require(id.matches("[a-z][a-z0-9-]{0,63}"),"应用编号须为小写字母、数字和连字符，且以字母开头");id}
}
final case class Definition(config:JsonNode,jobYaml:String) {
 val id=Values.safeId(config.path("id").asText)
 val inputs=config.path("inputs").elements.asScala.toVector
 def fileFields:Vector[(String,JsonNode)]={def walk(fields:Vector[JsonNode],prefix:String):Vector[(String,JsonNode)]=fields.flatMap{f=>val key=prefix+f.path("name").asText;if(f.path("widget").asText=="file")Vector(key->f)else if(f.path("widget").asText=="object")walk(f.path("fields").elements.asScala.toVector,key+".")else Vector.empty};walk(inputs,"")}
 val variableBindings=config.path("variables").fields.asScala.map(e=>e.getKey->e.getValue).toMap
 val artifacts=config.path("result").path("artifacts").elements.asScala.toVector
 def publicConfig:JsonNode=config.deepCopy[JsonNode]()
 def validate(payload:JsonNode,manifest:JsonNode):ObjectNode=InputValidation.validate(inputs,payload,manifest)
 def variables(input:JsonNode,manifest:JsonNode,datasets:Map[String,String]):ObjectNode={
  val out=Values.obj
  variableBindings.foreach{case(name,b)=>
   val value=if(b.has("input")){val v=input.at("/"+b.path("input").asText.replace(".","/"));if(v.isObject&&fileFields.exists(_._1==b.path("input").asText))v.path("uri")else v}
   else if(b.has("manifest"))Values.at(manifest,b.path("manifest").asText)
   else if(b.has("bbox")){
    val e=InputValidation.polygon(input.path(b.path("bbox").asText)).getEnvelopeInternal
    val a=Json.mapper.createArrayNode();Seq(e.getMinX,e.getMinY,e.getMaxX,e.getMaxY).foreach(a.add);a
   }else if(b.has("dataset")){
    val dataset=datasets.getOrElse(b.path("dataset").asText,throw new IllegalArgumentException("缺少数据集："+b.path("dataset").asText))
    val item=b.path("item").asText;require(item.nonEmpty && !item.startsWith("/") && !item.split("/",-1).exists(x=>x==".."||x=="."||x.isEmpty) && !item.exists(_.isControl),"无效 Item 路径")
    Json.mapper.getNodeFactory.textNode(dataset.stripSuffix("/")+"/"+item)
   }else b.path("value")
   require(!value.isMissingNode,"缺少变量值："+name);out.set[JsonNode](name,value)
  }
  WorkflowVariables.resolve(jobYaml,out);out
 }
}
object Definition {
 def operatorId(s:JsonNode):String=if(s.path("operator").isTextual)s.path("operator").asText else s.path("operator").path("local").asText

 private val widgets=Set("number","select","text","datetime","polygon","cone","object","file")
 def parse(configYaml:String,jobYaml:String,allowLocalOperators:Boolean=false):Definition={
  val n=YamlDocument.parse(configYaml);require(n.isObject,"应用配置必须是映射")
  val allowed=Set("version","id","title","description","job","inputs","variables","result","presentation","limits","synthetic","data","publication")
  require(n.fieldNames.asScala.toSet.subsetOf(allowed),"存在未知应用配置项")
  require(n.path("version").asInt==1,"不支持的应用配置版本")
  require(n.path("title").isTextual && n.path("title").asText.trim.nonEmpty,"请填写应用名称")
  require(n.path("description").isTextual&&n.path("description").asText.trim.nonEmpty,"请填写应用描述")
  require(n.path("inputs").isArray && n.path("variables").isObject && n.path("result").isObject,"inputs、variables 和 result 配置不完整")
  val definition=Definition(n,jobYaml)
  def fields(inputs:Vector[JsonNode]):Unit={
   require(inputs.map(_.path("name").asText).distinct.size==inputs.size,"输入字段名重复")
   inputs.foreach{f=>require(f.path("name").asText.matches("[a-z][a-z0-9_]*"),"无效输入字段名");require(widgets(f.path("widget").asText),"不支持的输入控件："+f.path("widget").asText)
    if(f.path("widget").asText=="file"){
     require(!f.has("default")&& !f.has("default_manifest"),"文件输入不能设置默认值")
     require(f.path("max_bytes").isIntegralNumber&&f.path("max_bytes").asLong>0&&f.path("max_bytes").asLong<=64L*1024*1024,"文件 max_bytes 须为 1–64 MiB")
     require(f.path("extensions").isArray&&f.path("extensions").size>0&&f.path("extensions").elements.asScala.forall(e=>e.isTextual&&e.asText.matches("\\.[a-z0-9]+(?:\\.[a-z0-9]+)*")),"文件 extensions 必须为小写扩展名列表，如 [.csv, .zip]")
     require(f.path("view").isTextual&&f.path("view").asText.matches("[a-z][a-z0-9_-]*"),"请指定文件读取 view")
    }
    if(f.has("default")&&f.has("default_manifest"))throw new IllegalArgumentException("输入默认值不能重复声明")
    if(f.path("widget").asText=="object")fields(f.path("fields").elements.asScala.toVector)
    if(f.path("widget").asText=="select")require(f.path("options").isArray&&f.path("options").size>0,"选择控件缺少 options")
   }
  };fields(definition.inputs)
  definition.variableBindings.foreach{case(name,b)=>require(name.matches("[A-Za-z_][A-Za-z0-9_]*") && b.isObject,"无效变量绑定");require(Seq("input","manifest","bbox","dataset","value").count(b.has)==1,"变量须声明且仅声明一种绑定来源")}
  def lookup(path:String):Option[JsonNode]=path.split("\\.").foldLeft(Option(n)){case(Some(parent),name)=>parent.path(if(parent==n)"inputs"else"fields").elements.asScala.find(_.path("name").asText==name);case _=>None}
  definition.variableBindings.values.foreach{b=>if(b.has("input"))require(lookup(b.path("input").asText).nonEmpty,"变量引用了未声明的输入字段");if(b.has("bbox"))require(lookup(b.path("bbox").asText).exists(_.path("widget").asText=="polygon"),"bbox 绑定必须引用多边形输入")}
  require(definition.artifacts.map(_.path("id").asText).distinct.size==definition.artifacts.size,"成果编号重复")
  require(!definition.artifacts.exists(a=>Set("analysis","variables","metadata","report")(a.path("id").asText)),"成果编号与系统成果冲突")
  definition.artifacts.foreach{a=>require(a.path("id").asText.matches("[a-z][a-z0-9_]*"),"无效成果编号");Values.relative(Path.of("/application"),a.path("file").asText);require(!a.path("file").asText.contains("/"),"成果文件必须位于结果根目录");require(Set("raw","json","csv")(a.path("format").asText),"不支持的成果格式")}
  ResultDecoders.named(n.path("result").path("decoder").asText)
  val rawJob=YamlDocument.parse(jobYaml)
  val sinks=rawJob.path("steps").elements.asScala.filter(s=>operatorId(s)=="client_sink@1.0").toVector
  val job=if(allowLocalOperators&&sinks.nonEmpty){
   require(sinks.size==1&& !rawJob.has("outputs"),"本地应用须使用唯一 client_sink，不能同时定义 outputs")
   val normalized=Values.copy(rawJob);normalized.set[JsonNode]("outputs",sinks.head.path("inputs"));normalized.set[JsonNode]("steps",Values.array(rawJob.path("steps").elements.asScala.filterNot(s=>operatorId(s)=="client_sink@1.0").toVector));normalized
  }else rawJob
  require(job.path("steps").isArray && job.path("steps").size>0 && job.path("outputs").isObject && job.path("outputs").size==1,"应用 Job 必须包含步骤和一个输出")
  require(job.path("steps").elements.asScala.forall(s=>(s.path("operator").isTextual && s.path("operator").asText.contains("@")) || (allowLocalOperators && s.path("operator").isObject && s.path("operator").fieldNames.asScala.toSet==Set("local") && s.path("operator").path("local").asText.matches("[a-z][a-z0-9_]*@[0-9][A-Za-z0-9_.-]*"))),"应用 Job 使用具名仓库算子")
  val steps=job.path("steps").elements.asScala.toVector;val identifiers=steps.map(_.path("id").asText)
  require(identifiers.forall(_.matches("[A-Za-z_][A-Za-z0-9_-]*"))&&identifiers.distinct.size==identifiers.size,"步骤标识无效或重复")
  def producer(value:JsonNode):String={val id=if(value.isTextual)value.asText.takeWhile(_!=':')else value.path("task").asText;require(identifiers.contains(id),"连接引用了不存在的步骤："+id);id}
  val dependencies=steps.map(s=>s.path("id").asText->s.path("inputs").elements.asScala.map(producer).toSet).toMap
  job.path("outputs").elements.asScala.foreach(producer)
  val done=scala.collection.mutable.Set.empty[String];val visiting=scala.collection.mutable.Set.empty[String]
  def visit(id:String):Unit=if(!done(id)){require(!visiting(id),"Job 数据流存在循环");visiting+=id;dependencies(id).foreach(visit);visiting-=id;done+=id}
  identifiers.foreach(visit)
  val reachable=scala.collection.mutable.Set.empty[String]
  def mark(id:String):Unit=if(reachable.add(id))dependencies(id).foreach(mark)
  job.path("outputs").elements.asScala.map(producer).foreach(mark)
  require(identifiers.forall(reachable),"存在未连接到应用结果的步骤")
  def referencesIn(n:JsonNode):Set[String]=if(n.isTextual&&n.asText.matches("\\$[A-Za-z_][A-Za-z0-9_]*"))Set(n.asText.substring(1))else if(n.isContainerNode)n.elements.asScala.flatMap(referencesIn).toSet else Set.empty
  definition.fileFields.foreach{case(key,f)=>
   definition.variableBindings.filter{case(_,b)=>b.path("input").asText==key}.keys.foreach{variable=>
    steps.filter(s=>s.path("operator").asText.startsWith("read_item@")&&s.path("params").path("uri").asText=="$"+variable).foreach(s=>require(s.path("params").path("view").asText==f.path("view").asText,"文件输入 view 与 read_item 不一致："+key))
   }
  }
  val references=referencesIn(job)
  require(references.subsetOf(definition.variableBindings.keySet),"Job 存在未绑定变量："+(references--definition.variableBindings.keySet).toVector.sorted.mkString(", "))
  val page=n.path("presentation").path("page")
  if(!page.isMissingNode){
   require(page.isObject,"页面设计必须为对象")
   val enums=Map("layout"->Set("split","stacked","wide"),"hero"->Set("banner","compact","centered"),"surface"->Set("soft","outlined","elevated"),"density"->Set("comfortable","compact"))
   require(page.fieldNames.asScala.forall(k=>enums.contains(k)||Set("accent","heading","intro","input_labels","input_hints","input_order","theme","background","cover")(k)),"未知页面设计字段")
   enums.foreach{case(k,values)=>require(page.path(k).isTextual&&values(page.path(k).asText),"页面设计字段无效："+k)}
   if(page.has("theme"))require(page.path("theme").isTextual&&Set("coast","forest","indigo","amber","rose")(page.path("theme").asText),"未知页面主题")
   Seq("background","cover").foreach{k=>val image=page.path(k);if(!image.isMissingNode && !image.isNull)require(image.isTextual&&image.asText.startsWith("/assets/") && !image.asText.contains("..") && !image.asText.exists(c=>c=='?'||c=='#'||c=='\\'),"页面图片须为本地资源："+k)}
   require(page.path("accent").asText.matches("#[0-9a-fA-F]{6}"),"页面强调色无效")
   Seq("heading"->80,"intro"->400).foreach{case(k,max)=>require(page.path(k).isTextual&&page.path(k).asText.length<=max,"页面文字过长或无效："+k)}
  }
  def inputPaths(fields:Vector[JsonNode],prefix:String=""):Set[String]=fields.flatMap{f=>val key=prefix+f.path("name").asText;Set(key)++inputPaths(f.path("fields").elements.asScala.toVector,key+".")}.toSet
  val paths=inputPaths(definition.inputs)
  Seq("input_labels"->80,"input_hints"->240).foreach{case(key,max)=>
   val map=page.path(key)
   if(!map.isMissingNode)require(map.isObject&&map.fields.asScala.forall(e=>paths(e.getKey)&&e.getValue.isTextual&&e.getValue.asText.length<=max),"页面输入说明无效："+key)
  }
  val order=page.path("input_order")
  if(!order.isMissingNode)require(order.isArray&&order.size==definition.inputs.size&&order.elements.asScala.forall(_.isTextual)&&order.elements.asScala.map(_.asText).toSet==definition.inputs.map(_.path("name").asText).toSet,"页面输入顺序必须包含全部顶层输入且不重复")
  val design=n.path("presentation").path("design")
  if(!design.isMissingNode){
   val enums=Map("layout"->Set("split","stacked","wide"),"hero"->Set("banner","compact","centered"),"surface"->Set("soft","outlined","elevated"),"density"->Set("comfortable","compact"))
   require(design.isObject&&design.fields.asScala.forall(e=>enums.get(e.getKey).exists(v=>e.getValue.isTextual&&v(e.getValue.asText))),"结构化页面设计配置无效")
  }
  val theme=n.path("presentation").path("theme")
  if(!theme.isMissingNode)require(theme.isTextual && Set("coast","forest","indigo","amber","rose")(theme.asText),"未知应用主题")
  Seq("cover","background").foreach { key=>
    val image=n.path("presentation").path(key)
    if(!image.isMissingNode && !(key=="background" && image.isNull)){
      require(image.isTextual,"图片必须是地址："+key)
      val url=image.asText
      require(url.startsWith("/assets/")||url.matches("/appbuilder/media/[a-f0-9]{64}\\.(png|jpg)")||scala.util.Try{val u=java.net.URI.create(url);u.getScheme=="https"&&u.getHost!=null&&u.getUserInfo==null}.getOrElse(false),"图片须为本地资源或 HTTPS 图片地址："+key)
    }
  }
  val tags=n.path("presentation").path("tags");if(!tags.isMissingNode)require(tags.isArray&&tags.size<=8&&tags.elements.asScala.forall(t=>t.isTextual&&t.asText.length<=32),"标签最多 8 项，每项最多 32 字符")
  val max=n.path("limits").path("max_result_bytes").asLong(8L*1024*1024);require(max>0&&max<=64L*1024*1024,"结果上限须介于 1 字节和 64 MiB")
  val queued=n.path("limits").path("max_queued").asInt(4);require(queued>=1&&queued<=100,"队列上限须介于 1 和 100")
  val knownViews=Set("metrics","table","scatter","geojson")
  n.path("result").path("views").elements.asScala.foreach(v=>require(knownViews(v.path("type").asText),"不支持的结果视图"))
  require(definition.artifacts.map(_.path("file").asText).distinct.size==definition.artifacts.size,"成果文件名重复")
  require(!definition.artifacts.exists(a=>Set("analysis.json","variables.yaml","metadata.json","report.md")(a.path("file").asText)),"成果文件名与系统成果冲突")
  definition
 }
 def load(home:Path):Definition={val raw=Files.readString(home.resolve("application.yaml"));val n=YamlDocument.parse(raw);val job=Values.relative(home,n.path("job").asText);parse(raw,Files.readString(job))}
}
