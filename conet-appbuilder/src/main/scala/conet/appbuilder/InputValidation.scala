package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.locationtech.jts.geom.{Coordinate,GeometryFactory,Polygon}
import scala.jdk.CollectionConverters._

object InputValidation {
 private val factory=new GeometryFactory()
 def number(n:JsonNode):Double={require(n.isNumber && java.lang.Double.isFinite(n.asDouble),"数值必须为有限数字");n.asDouble}
 def polygon(n:JsonNode):Polygon={
  require(n.isObject && n.fieldNames.asScala.toSet==Set("type","coordinates") && n.path("type").asText=="Polygon","region must be a GeoJSON Polygon geometry")
  val rings=n.path("coordinates");require(rings.isArray && rings.size>0 && rings.size<=16,"region requires an exterior ring and at most 15 holes")
  require(rings.elements.asScala.map(_.size).sum<=256,"region exceeds 256 vertices")
  val parsed=rings.elements.asScala.map{ring=>
   require(ring.isArray && ring.size>=4 && ring.get(0)==ring.get(ring.size-1),"polygon rings must be closed")
   factory.createLinearRing(ring.elements.asScala.map{point=>require(point.isArray && point.size==2,"expected longitude/latitude pairs");val x=number(point.get(0));val y=number(point.get(1));require(x>= -180 && x<=180 && y> -85 && y<85,"polygon coordinates outside supported WGS84 range");new Coordinate(x,y)}.toArray)
  }.toVector
  val geometry=factory.createPolygon(parsed.head,parsed.tail.toArray)
  require(geometry.isValid && !geometry.isEmpty && geometry.getArea>0 && geometry.getEnvelopeInternal.getWidth<=180,"polygon must be valid, nonempty and without self-intersections")
  geometry
 }
 def validate(fields:Vector[JsonNode],payload:JsonNode,manifest:JsonNode):ObjectNode={
  require(payload.isObject,"请求必须为 JSON 对象")
  require(payload.fieldNames.asScala.toSet.subsetOf(fields.map(_.path("name").asText).toSet),"请求包含未知输入参数")
  val out=Values.obj
  fields.foreach{f=>
   val name=f.path("name").asText
   val value=if(payload.has(name))payload.get(name)else if(f.has("default"))f.get("default")else if(f.has("default_manifest"))Values.at(manifest,f.path("default_manifest").asText)else com.fasterxml.jackson.databind.node.MissingNode.getInstance()
   if(value.isMissingNode){require(!f.path("required").asBoolean(true),"缺少参数："+name)}else {
    val normalized=f.path("widget").asText match {
     case "file"=>
      require(value.isObject,"请选择上传文件或远程 Item："+name)
      if(value.has("uri")){
       require(value.fieldNames.asScala.toSet==Set("uri") && value.path("uri").isTextual,"远程 Item 参数无效")
       val u=java.net.URI.create(value.path("uri").asText)
       require(u.getScheme=="dacp" && u.getHost!=null && u.getUserInfo==null && u.getRawQuery==null && u.getFragment==null && (u.getPort == -1 || u.getPort>0 && u.getPort<=65535),"请提供不含凭据的 DACP Item URI")
       val parts=Option(u.getPath).getOrElse("").stripPrefix("/").split("/",-1)
       require(parts.length>=2 && parts.forall(p=>p.nonEmpty && p!="." && p!=".." && !p.contains("\\") && !p.exists(_.isControl)),"远程 URI 必须指向 Item")
      }else require(value.fieldNames.asScala.toSet==Set("upload_id") && value.path("upload_id").isTextual && value.path("upload_id").asText.matches("[a-f0-9]{32}"),"请先上传文件："+name)
      value
     case "number"=>val n=number(value);require(!f.has("min")||n>=number(f.get("min")),"参数小于下限："+name);require(!f.has("max")||n<=number(f.get("max")),"参数超过上限："+name);value
     case "select"=>require(value.isTextual&&f.path("options").elements.asScala.exists(o=>(if(o.isTextual)o.asText else o.path("value").asText)==value.asText),"无效选项："+name);value
     case "datetime"=>require(value.isTextual,"时间必须为字符串");try java.time.OffsetDateTime.parse(value.asText)catch{case _:java.time.DateTimeException=>throw new IllegalArgumentException("时间必须为带时区的 ISO 8601 时间："+name)};value
     case "text"=>require(value.isTextual&&value.asText.length<=f.path("max_length").asInt(4096),"文本参数无效："+name);value
     case "polygon"=>val g=polygon(value);if(f.has("coverage_manifest")){val b=Values.at(manifest,f.path("coverage_manifest").asText);require(b.isArray&&b.size==4,"数据覆盖范围无效");val c=factory.toGeometry(new org.locationtech.jts.geom.Envelope(number(b.get(0)),number(b.get(2)),number(b.get(1)),number(b.get(3))));require(g.intersection(c).getArea>0,"所选多边形未与数据覆盖区相交")};value
     case "cone"=>require(value.isArray&&value.size==3,"请提供有效的赤经、赤纬和半径");val a=value.elements.asScala.map(number).toVector;require(a(0)>=0&&a(0)<360&&a(1)>= -90&&a(1)<=90&&a(2)>=f.path("min_radius").asDouble(.001)&&a(2)<=f.path("max_radius").asDouble(1),"赤经、赤纬或半径范围无效");value
     case "object"=>val n=validate(f.path("fields").elements.asScala.toVector,value,manifest)
      if(f.has("point_fields")){val keys=f.path("point_fields");val x=number(n.path(keys.get(0).asText));val y=number(n.path(keys.get(1).asText));val b=Values.at(manifest,f.path("coverage_manifest").asText);require(b.isArray&&b.size==4&&x>=number(b.get(0))&&x<=number(b.get(2))&&y>=number(b.get(1))&&y<=number(b.get(3)),"事件位置须位于数据覆盖区内")};n
     case _=>throw new IllegalArgumentException("不支持的输入控件")
    }
    out.set[JsonNode](name,normalized)
   }
  };out
 }
}
