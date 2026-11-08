package conet

import com.fasterxml.jackson.databind.JsonNode
import conet.appbuilder.{Definition,Values}
import java.io.{ByteArrayInputStream,ByteArrayOutputStream}
import java.util.zip.{ZipEntry,ZipInputStream,ZipOutputStream}
import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** Declarative application delivery; packages never contain credentials, code or datasets. */
final class ApplicationPackages(applications:Applications) {
 private val limit=4*1024*1024
 def export(document:JsonNode):Array[Byte]={
  val d=Definition.parse(document.path("application_yaml").asText,document.path("job_yaml").asText)
  val app=Values.copy(d.config).put("job","job.yaml");val manifest=Support.obj.put("format","conet-application").put("version",1).put("created_at",Support.now)
  manifest.set[JsonNode]("operators",Support.arr(xstream.util.YamlDocument.parse(d.jobYaml).path("steps").elements.asScala.map(_.path("operator")).toVector.distinct));manifest.put("data_scope",app.path("data").path("scope").asText)
  val out=new ByteArrayOutputStream();val zip=new ZipOutputStream(out)
  def add(name:String,bytes:Array[Byte]):Unit={zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry()}
  try{
   Seq("cover","background").foreach { key=>
    val url=app.path("presentation").path(key).asText
    if(url.startsWith("/appbuilder/media/")){
      val path=applications.media(url.stripPrefix("/appbuilder/media/")).getOrElse(throw new IllegalArgumentException("图片文件不存在："+key))
      val name=key+"."+path.toString.split('.').last;val bytes=Files.readAllBytes(path)
      require(bytes.length<=2*1024*1024,"图片超过大小上限");add(name,bytes);manifest.put(key+"_file",name)
      app.path("presentation").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put(key,"/assets/app-cover.svg")
    }
   }
   add("manifest.json",xstream.util.Json.mapper.writeValueAsBytes(manifest));add("application.yaml",xstream.util.YamlDocument.dump(app).getBytes(java.nio.charset.StandardCharsets.UTF_8));add("job.yaml",d.jobYaml.getBytes(java.nio.charset.StandardCharsets.UTF_8))
  }finally zip.close()
  require(out.size<=limit,"应用程序包过大");out.toByteArray
 }
 def load(bytes:Array[Byte]):JsonNode={
  require(bytes.nonEmpty&&bytes.length<=limit,"应用程序包超过 4 MiB 上限")
  val zip=new ZipInputStream(new ByteArrayInputStream(bytes));val files=scala.collection.mutable.Map.empty[String,Array[Byte]];var total=0
  try{var entry=zip.getNextEntry;while(entry!=null){val name=entry.getName;require(Set("manifest.json","application.yaml","job.yaml","cover.png","cover.jpg","background.png","background.jpg")(name)&& !entry.isDirectory&& !files.contains(name),"包中存在不允许或重复的文件")
   val max=if(name.startsWith("cover.")||name.startsWith("background."))2*1024*1024 else 262144;val data=zip.readNBytes(max+1);total+=data.length;require(data.length<=max&&total<=limit,"应用程序包解压内容超过上限");files(name)=data;zip.closeEntry();entry=zip.getNextEntry}
  }catch{case _:java.util.zip.ZipException=>throw new IllegalArgumentException("无效的 ZIP 应用程序包")}finally zip.close()
  require(Set("manifest.json","application.yaml","job.yaml").subsetOf(files.keySet.toSet),"应用程序包缺少清单、应用配置或流程文件")
  val manifest=xstream.util.Json.mapper.readTree(files("manifest.json"));require(manifest.path("format").asText=="conet-application"&&manifest.path("version").asInt==1,"不支持的应用程序包格式")
  val app=Values.copy(xstream.util.YamlDocument.parse(new String(files("application.yaml"),java.nio.charset.StandardCharsets.UTF_8)));require(app.path("job").asText=="job.yaml","程序包中的 job 必须为 job.yaml")
  val job=new String(files("job.yaml"),java.nio.charset.StandardCharsets.UTF_8)
  // Validate definitions before persisting any optional media.
  Definition.parse(xstream.util.YamlDocument.dump(app),job)
  Seq("cover","background").foreach { key=>
    if(manifest.has(key+"_file")){
      val name=manifest.path(key+"_file").asText
      require(Set(key+".png",key+".jpg")(name)&&files.contains(name),"图片资源缺失："+key)
      val media=applications.upload(Support.obj.put("content_base64",java.util.Base64.getEncoder.encodeToString(files(name))))
      if(!app.path("presentation").isObject)app.set[JsonNode]("presentation",Support.obj)
      app.path("presentation").asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode].put(key,media.path("url").asText)
    }
  }
  val out=Support.copy(applications.convert(Support.obj.put("application",xstream.util.YamlDocument.dump(app)).put("job",job)));out.set[JsonNode]("requirements",manifest);out.put("loaded",false);out
 }
}
