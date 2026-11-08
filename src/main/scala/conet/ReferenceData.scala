package conet

import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

object ReferenceData {
 def prepare(s:Settings):Unit={
  val dam=Support.read(s.caseState("check-dams").resolve("data/manifest.json"))
  val astronomy=Support.read(s.caseState("observatory").resolve("data/manifest.json"))
  val tiles=dam.path("tiles").elements.asScala.map{t=>val n=Support.obj.put("synthetic",true);Seq("tile","bbox_wgs84","resolution_m").foreach(k=>n.set[JsonNode](k,t.path(k)));n}.toVector
  val bands=astronomy.path("files").fields.asScala.map{entry=>val n=Support.obj.put("source_file",entry.getKey).put("synthetic",true);Seq("band","rows","bytes").foreach(k=>n.set[JsonNode](k,entry.getValue.path(k)));n}.toVector
  val coverage=Support.obj.put("synthetic",true);Seq("footprint","coordinate_frame","photometric_system","total_rows").foreach(k=>coverage.set[JsonNode](k,astronomy.path(k)))
  val methods=Seq(Support.obj.put("method","channel_susceptibility").put("min_slope_ratio",.08).put("max_slope_ratio",.5).put("max_entropy",.2).put("synthetic",true),Support.obj.put("method","spatial_overlap").put("bias_pixels",3).put("region_encoding","GeoJSON Polygon").put("synthetic",true))
  Seq(("check-dams","d","tile-coverage.jsonl",tiles),("observatory","e","band-catalog.jsonl",bands),("check-dams","f","analysis-methods.jsonl",methods),("observatory","g","survey-coverage.jsonl",Seq(coverage))).foreach{case(id,center,name,rows)=>
   val path=s.caseState(id).resolve("data/references").resolve(center).resolve(name);val content=rows.map(xstream.util.Json.mapper.writeValueAsString).mkString("","\n","\n")
   if(!java.nio.file.Files.exists(path)||java.nio.file.Files.readString(path)!=content)Support.writeText(path,content)
  }
 }
}
