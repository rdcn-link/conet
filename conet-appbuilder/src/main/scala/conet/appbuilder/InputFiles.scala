package conet.appbuilder

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream

/** Upload storage and execution-target publication are supplied by the host application. */
trait InputFiles {
 def upload(definition:Definition,field:String,name:String,stream:InputStream):JsonNode
 def resolve(definition:Definition,payload:JsonNode,run:Option[String]):ObjectNode
 def replay(definition:Definition,previous:JsonNode,payload:JsonNode,run:String):ObjectNode=resolve(definition,payload,Some(run))
 def status(definition:Definition,record:JsonNode):JsonNode=Values.obj.put("has_uploads",false).put("available",true)
 def retain(definition:Definition,record:JsonNode,keep:Boolean):JsonNode=throw new IllegalArgumentException("该分析没有可保留的上传输入")
 def finished(application:String,run:String):Unit=()
}
object InputFiles {
 val unavailable:InputFiles=new InputFiles {
  def upload(d:Definition,f:String,n:String,s:InputStream):JsonNode=throw new IllegalArgumentException("该部署未启用文件上传")
  def resolve(d:Definition,p:JsonNode,r:Option[String]):ObjectNode={require(!d.fileFields.exists{case(k,_)=> !p.at("/"+k.replace('.', '/')).isMissingNode},"该部署未启用文件上传");Values.copy(p)}
 }
}
