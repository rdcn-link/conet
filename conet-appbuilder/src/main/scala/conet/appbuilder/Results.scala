package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import xstream.util.{Json,YamlDocument}
import java.nio.file.{Files,Path}
import java.util.ServiceLoader
import scala.jdk.CollectionConverters._

/** Trusted server plugins. A decoder produces the JSON document used by configured views/artifacts. */
trait ResultDecoder {def id:String;def decode(bytes:Array[Byte]):JsonNode}
final class JsonResultDecoder extends ResultDecoder {
 val id="json"
 def decode(bytes:Array[Byte]):JsonNode={val n=Json.mapper.readTree(bytes);require(n!=null && (n.isObject||n.isArray),"成果须为 JSON 对象或数组");n}
}
final class SvgMetadataDecoder extends ResultDecoder {
 val id="svg-metadata"
 def decode(bytes:Array[Byte]):JsonNode={
  val factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true)
  factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true)
  factory.setFeature("http://xml.org/sax/features/external-general-entities",false)
  factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false)
  factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"")
  val document=factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes))
  require(document.getDocumentElement.getNamespaceURI=="http://www.w3.org/2000/svg" && document.getDocumentElement.getLocalName=="svg","成果须为 SVG")
  val metadata=document.getElementsByTagNameNS("http://www.w3.org/2000/svg","metadata");require(metadata.getLength==1,"SVG 必须包含一个 metadata 成果对象")
  val n=Json.mapper.readTree(metadata.item(0).getTextContent);require(n!=null&&n.isObject,"SVG metadata 必须为 JSON 对象");n
 }
}
object ResultDecoders {
 private lazy val all=ServiceLoader.load(classOf[ResultDecoder]).iterator.asScala.toVector
 def named(id:String):ResultDecoder={require(all.map(_.id).distinct.size==all.size,"成果解码器编号重复");all.find(_.id==id).getOrElse(throw new IllegalArgumentException("不支持的成果解码器："+id))}
}
object Results {
 def rows(document:JsonNode,config:JsonNode):Vector[JsonNode]={
  val selected=Values.at(document,config.path("path").asText);require(selected.isArray,"表格成果路径必须指向数组")
  selected.elements.asScala.map(n=>if(config.has("project"))Values.at(n,config.path("project").asText)else n).filterNot(n=>config.path("exclude").fields.asScala.exists(e=>n.path(e.getKey)==e.getValue)).toVector
 }
 def write(definition:Definition,output:Path,raw:Array[Byte],variables:JsonNode):JsonNode={
  val result=ResultDecoders.named(definition.config.path("result").path("decoder").asText).decode(raw)
  require(Json.mapper.writeValueAsBytes(result).length.toLong<=definition.config.path("limits").path("max_result_bytes").asLong(8L*1024*1024),"解码成果超过应用输出上限")
  Files.createDirectories(output)
  Files.write(output.resolve("analysis.json"),Json.mapper.writeValueAsBytes(result));Files.writeString(output.resolve("variables.yaml"),YamlDocument.dump(variables))
  definition.artifacts.foreach{a=>val file=Values.relative(output,a.path("file").asText)
   a.path("format").asText match {
    case "raw"=>Files.write(file,raw)
    case "json"=>val n=Values.at(result,a.path("path").asText);require(!n.isMissingNode,"成果字段不存在");Files.write(file,Json.mapper.writeValueAsBytes(n))
    case "csv"=>val selected=if(a.has("columns_path"))Values.at(result,a.path("columns_path").asText)else a.path("columns");require(selected.isArray&&selected.elements.asScala.forall(_.isTextual),"CSV columns 必须为字符串数组");val columns=selected.elements.asScala.map(_.asText).toVector
     require(columns.nonEmpty,"CSV 必须声明 columns")
     def cell(s:String):String={val safe=if(s.nonEmpty&&"=+@\t\r".contains(s.head))"'"+s else s;if(safe.exists(c=>",\"\r\n".contains(c)))"\""+safe.replace("\"","\"\"")+"\""else safe}
     val lines=columns.map(cell).mkString(",")+:rows(result,a).map(r=>columns.map(k=>cell(if(r.path(k).isNull||r.path(k).isMissingNode)""else r.path(k).asText)).mkString(","))
     Files.writeString(file,lines.mkString("\n")+"\n")
   }
  };result
 }
 def summary(definition:Definition,result:JsonNode):JsonNode={val out=Values.obj
  definition.config.path("result").path("summary").fields.asScala.foreach{e=>val b=e.getValue;val n=Values.at(result,b.path("path").asText);require(!n.isMissingNode,"汇总字段不存在："+e.getKey);if(b.path("operation").asText=="count"){require(n.isArray||n.isObject,"count 汇总须引用集合");out.put(e.getKey,n.size)}else out.set[JsonNode](e.getKey,n)};out
 }
}
