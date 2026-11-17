package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

/** Presentation-only trees: existing runtime components own all execution behavior. */
object PageComposition {
 private val slots=Set("intro","inputs","progress","results")
 private val containers=Set("grid","stack","panel")
 private val common=Set("type","span","gap","padding","radius","background","color","align")
 private def color(n:JsonNode):Boolean=n.isTextual&&n.asText.matches("#[0-9a-fA-F]{6}")
 private def number(n:JsonNode,min:Int,max:Int):Boolean=n.isIntegralNumber&&n.canConvertToInt&&n.asInt>=min&&n.asInt<=max
 private def text(n:JsonNode,max:Int):Boolean=n.isTextual&&n.asText.length<=max
 def localImage(n:JsonNode):Boolean=n.isTextual&&n.asText.matches("/assets/[A-Za-z0-9_/-]+\\.(svg|png|jpg|webp)") && !n.asText.contains("..")
 def validate(page:JsonNode):Unit={
  require(page.path("composition").isObject,"自由页面须提供区域结构")
  require(!Seq("layout","hero","surface","density").exists(page.has),"自由页面不使用模板布局字段")
  if(page.has("max_width"))require(number(page.path("max_width"),640,1800),"页面宽度须为 640–1800")
  val palette=page.path("palette")
  if(!palette.isMissingNode)require(palette.isObject&&palette.size>0&&palette.fields.asScala.forall(e=>Set("background","surface","text","muted","border","accent")(e.getKey)&&color(e.getValue)),"页面色板无效")
  var count=0;val found=scala.collection.mutable.Set.empty[String]
  def visit(n:JsonNode,depth:Int,parentColumns:Int):Unit={
   count+=1;require(depth<=6&&count<=80&&n.isObject,"页面区域过多或嵌套过深")
   val kind=n.path("type").asText
   val specific=if(containers(kind))Set("children","columns","title","description") else kind match {
    case "slot"=>Set("name","title","description","columns","map_height")
    case "text"=>Set("text","size","weight")
    case "image"=>Set("src","alt","ratio")
    case _=>throw new IllegalArgumentException("未知页面区域类型")
   }
   require(n.fieldNames.asScala.forall(k=>common(k)||specific(k)),"未知页面区域字段")
   Seq("gap"->64,"padding"->64,"radius"->40).foreach{case(k,max)=>if(n.has(k))require(number(n.path(k),0,max),"页面区域尺寸无效："+k)}
   if(n.has("span"))require(number(n.path("span"),1,parentColumns),"页面区域跨度超出网格")
   Seq("background","color").foreach(k=>if(n.has(k))require(color(n.path(k)),"页面区域颜色无效"))
   if(n.has("align"))require(n.path("align").isTextual&&Set("start","center","end")(n.path("align").asText),"页面对齐方式无效")
   Seq("title"->80,"description"->400).foreach{case(k,max)=>if(n.has(k))require(text(n.path(k),max),"页面区域文字无效")}
   if(n.has("columns"))require((kind=="grid"||kind=="slot")&&number(n.path("columns"),1,if(kind=="slot")3 else 12),"页面区域列数无效")
   if(containers(kind)){
    val children=n.path("children");require(children.isArray&&children.size>0&&children.size<=20,"页面容器须包含 1–20 个区域")
    val columns=if(kind=="grid")n.path("columns").asInt(12)else 1
    children.elements.asScala.foreach(c=>visit(c,depth+1,columns))
   }else if(kind=="slot"){
    val name=n.path("name").asText;require(slots(name)&&found.add(name),"功能区域缺失、重复或未知")
    if(n.has("map_height"))require(name=="inputs"&&number(n.path("map_height"),240,640),"地图高度无效")
    if(n.has("columns"))require(Set("inputs","results")(name),"此功能区域不支持列数设置")
   }else if(kind=="text"){
    require(text(n.path("text"),600),"页面文字无效")
    if(n.has("size"))require(number(n.path("size"),10,60),"文字尺寸无效")
    if(n.has("weight"))require(number(n.path("weight"),400,800),"文字粗细无效")
   }else{
    require(localImage(n.path("src"))&&text(n.path("alt"),160),"页面图片须为本地资源并包含说明")
    if(n.has("ratio"))require(n.path("ratio").isNumber&&n.path("ratio").asDouble>=.5&&n.path("ratio").asDouble<=4,"图片比例无效")
   }
  }
  visit(page.path("composition"),0,12)
  require(found.toSet==slots,"自由页面必须包含介绍、输入、进度和结果区域")
 }
}
