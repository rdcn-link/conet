package conet

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path}
import scala.jdk.CollectionConverters._

/** An organizational namespace, freely selectable without authentication. */
final case class Workspace(id:String,name:String) {
 def json:JsonNode=Support.obj.put("id",id).put("name",name)
}
final class Workspaces(path:Path) {
 private val state=if(Files.exists(path))Support.copy(Support.read(path))else{val n=Support.obj;n.set[JsonNode]("spaces",Support.obj);n.set[JsonNode]("resources",Support.obj);n}
 private def spaces=state.path("spaces").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
 private def assignments=state.path("resources").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode]
 val default=Workspace("00000000-0000-0000-0000-000000000001","共享工作空间")
 if(!spaces.has(default.id)){spaces.set[JsonNode](default.id,default.json);Support.atomic(path,state)}
 def get(id:String):Workspace=synchronized{val n=Option(spaces.get(id)).getOrElse(throw new IllegalArgumentException("工作空间不存在"));Workspace(n.path("id").asText,n.path("name").asText)}
 def list:JsonNode=synchronized{Support.arr(spaces.elements.asScala.toVector)}
 def create(raw:String):Workspace=synchronized{val name=raw.trim;require(name.nonEmpty&&name.length<=80,"工作空间名称须为 1–80 字");val w=Workspace(java.util.UUID.randomUUID.toString,name);spaces.set[JsonNode](w.id,w.json);Support.atomic(path,state);w}
 def record(w:Workspace,key:String):Unit=synchronized{if(!assignments.has(key)){assignments.put(key,w.id);Support.atomic(path,state)}}
 def resources(w:Workspace):JsonNode=synchronized{Support.arr(resourceOwners.filter(_._2==w.id).map{case(k,id)=>Support.obj.put("resource",k).put("owner_id",id)})}
 def resourceOwners:Vector[(String,String)]=synchronized{assignments.fields.asScala.map(e=>e.getKey->e.getValue.asText).toVector}
}
