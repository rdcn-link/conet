package conet

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path,StandardCopyOption}
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.jdk.CollectionConverters._

/** Anonymous browser ratings are stored separately from application and dataset contracts. */
final class Ratings(directory:Path) {
 private val path=directory.resolve("ratings.json")
 private var votes:Map[(String,String,String),Int]=if(Files.isRegularFile(path)){
  val rows=xstream.util.Json.mapper.readTree(Files.readString(path)).path("votes")
  rows.elements.asScala.map(n=>(n.path("kind").asText,n.path("id").asText,n.path("browser").asText)->n.path("score").asInt).toMap
 }else Map.empty
 private def checked(kind:String,id:String):Unit={require(Set("app","dataset")(kind),"评分类型无效");require(id.nonEmpty&&id.length<=200&& !id.exists(_.isControl),"评分对象无效")}
 private def fingerprint(browser:String):String={require(browser.matches("[A-Za-z0-9-]{36}"),"评分浏览器标识无效");MessageDigest.getInstance("SHA-256").digest(browser.getBytes(UTF_8)).map(b=>f"${b&255}%02x").mkString}
 private def summary(kind:String,id:String,browser:String):JsonNode={
  val values=votes.iterator.collect{case((k,i,_),v)if k==kind&&i==id=>v}.toVector
  val n=Support.obj.put("kind",kind).put("id",id).put("count",values.size).put("average",if(values.isEmpty)0d else values.sum.toDouble/values.size)
  votes.get((kind,id,browser)).foreach(n.put("mine",_));n
 }
 def list(kind:String,browser:String):JsonNode=synchronized{
  require(Set("app","dataset")(kind),"评分类型无效");val key=fingerprint(browser)
  val ids=votes.keysIterator.collect{case(k,id,_)if k==kind=>id}.toSet.toVector.sorted
  val out=Support.obj;out.set[JsonNode]("entries",Support.arr(ids.map(id=>summary(kind,id,key))));out
 }
 def rate(kind:String,id:String,browser:String,score:Int):JsonNode=synchronized{
  checked(kind,id);require(score>=1&&score<=5,"评分须为 1–5 分");val key=fingerprint(browser)
  val next=votes.updated((kind,id,key),score)
  val doc=Support.obj;doc.set[JsonNode]("votes",Support.arr(next.toVector.sortBy(_._1).map{case((k,i,b),s)=>Support.obj.put("kind",k).put("id",i).put("browser",b).put("score",s)}))
  Files.createDirectories(directory);val temp=Files.createTempFile(directory,"ratings-",".tmp")
  try{Files.writeString(temp,doc.toString+"\n");Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally Files.deleteIfExists(temp)
  votes=next;summary(kind,id,key)
 }
}
