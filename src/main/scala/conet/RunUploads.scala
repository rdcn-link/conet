package conet

import conet.appbuilder.{Definition,InputFiles,Values}
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.io.InputStream
import java.nio.file.{Files,Path,LinkOption,StandardCopyOption}
import java.time.Instant
import scala.jdk.CollectionConverters._

/** Bounded uploads; immutable run-scoped Items readable on the shared DACP node. */
final class RunUploads(settings:Settings) extends InputFiles {
 private val storage=new HostedStorage(settings)
 private val root=storage.root.resolve("inputs")
 private val index=storage.root.resolve("execution-inputs")
 private val retention=settings.config.path("appbuilder").path("uploads").path("retention_days").asInt(7)
 require(retention>=1&&retention<=365,"上传保留期须为 1–365 天")
 private val quota=settings.config.path("appbuilder").path("uploads").path("quota_bytes").asLong(1024L*1024*1024)
 require(quota>0,"上传总配额必须大于 0")
 Files.createDirectories(root);Files.createDirectories(index)
 private def expires=Instant.now.plusSeconds(retention.toLong*86400).toString
 private def safeId(id:String):String={require(id.matches("[a-f0-9]{32}"),"上传标识无效");id}
 private def regular(p:Path):Unit={require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&& !Files.isSymbolicLink(p),"上传文件不存在")}
 private def field(d:Definition,key:String)=d.fileFields.find(_._1==key).map(_._2).getOrElse(throw new IllegalArgumentException("文件输入字段不存在"))
 private def remove(p:Path):Unit=if(Files.exists(p,LinkOption.NOFOLLOW_LINKS))Support.walk(p).sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
 override def upload(d:Definition,key:String,name:String,in:InputStream):JsonNode=synchronized{
  val f=field(d,key);require(name.nonEmpty&&name.length<=200&& !name.exists(c=>c.isControl||c=='/'||c=='\\')&&name!="."&&name!="..","文件名无效")
  require(f.path("extensions").elements.asScala.exists(e=>name.toLowerCase(java.util.Locale.ROOT).endsWith(e.asText)),"不支持的文件扩展名")
  val limit=math.min(storage.maxUploadBytes,f.path("max_bytes").asLong)
  val used=Support.walk(root).filter(Files.isRegularFile(_,LinkOption.NOFOLLOW_LINKS)).map(Files.size(_)).sum
  val id=java.util.UUID.randomUUID.toString.replace("-","");val home=root.resolve("staged").resolve(id);Files.createDirectories(home)
  try{
   Files.createDirectories(home.resolve("file"));val file=home.resolve("file").resolve(name);val out=Files.newOutputStream(file);val digest=java.security.MessageDigest.getInstance("SHA-256");val buffer=new Array[Byte](65536);var count=0L
   try{var n=in.read(buffer);while(n != -1){if(Thread.currentThread.isInterrupted)throw new java.io.InterruptedIOException("上传已取消");count+=n;require(count<=limit,"文件超过上传大小上限");require(used+count<=quota,"运行输入存储空间不足");out.write(buffer,0,n);digest.update(buffer,0,n);n=in.read(buffer)}}finally out.close()
   require(count>0,"不能上传空文件")
   val meta=Support.obj.put("upload_id",id).put("application",d.id).put("field",key).put("name",name).put("bytes",count).put("sha256",digest.digest().map(b=>f"${b&255}%02x").mkString).put("view",f.path("view").asText).put("created_at",Support.now).put("expires_at",expires)
   Support.atomic(home.resolve("upload.json"),meta);Support.copy(meta)
  }catch{case scala.util.control.NonFatal(e)=>remove(home);throw e}
 }
 override def resolve(d:Definition,payload:JsonNode,run:Option[String]):ObjectNode=synchronized{
  val out=Values.copy(payload);val selected=d.fileFields.flatMap{case(key,f)=>val value=payload.at("/"+key.replace('.', '/'));if(value.isMissingNode)None else if(value.has("uri")){
   val uri=value.path("uri").asText
   val u=java.net.URI.create(uri);val parts=u.getPath.stripPrefix("/").split("/",2)
   // Never forward Union credentials to a caller-supplied remote origin.
   val tls=dacp.client.TlsConfig(ca=Some(Path.of(settings.connection.path("ca").asText)))
   val dataset=dacp.client.Source.open(new java.net.URI("dacp",null,u.getHost,u.getPort,"/"+parts(0),null,null).toASCIIString,tls,15)
   val item=try dataset.describe("/"+parts(1))finally dataset.close()
   require(item.views.contains(f.path("view").asText),"远程 Item 不支持所需视图："+f.path("view").asText)
   require(item.kind=="data" || (item.kind=="folder" && f.path("view").asText=="bytes" && item.views.contains("bytes")),"远程目录不可作为输入")
   item.totalSizeBytes.foreach(size=>require(size<=f.path("max_bytes").asLong,"远程 Item 超过输入大小上限"))
   val meta=Support.obj.put("uri",uri).put("view",f.path("view").asText).put("name",parts(1).split('/').last).put("source","remote")
   val path=key.split("\\.");val parent=path.dropRight(1).foldLeft(out:JsonNode)((n,k)=>n.path(k)).asInstanceOf[ObjectNode];parent.set[JsonNode](path.last,meta)
   None
  }else{
   val id=safeId(value.path("upload_id").asText);val home=root.resolve("staged").resolve(id);regular(home.resolve("upload.json"));val m=Support.read(home.resolve("upload.json"))
   require(m.path("application").asText==d.id&&m.path("field").asText==key,"上传文件不属于该应用输入")
   require(Instant.now.isBefore(Instant.parse(m.path("expires_at").asText)),"上传文件已过期，请重新上传")
   require(m.path("bytes").asLong<=math.min(storage.maxUploadBytes,f.path("max_bytes").asLong)&&f.path("extensions").elements.asScala.exists(e=>m.path("name").asText.toLowerCase(java.util.Locale.ROOT).endsWith(e.asText)),"上传文件不符合当前配置")
   regular(home.resolve("file").resolve(m.path("name").asText));Some((key,f,m,home))
  }}
  val dataset=run.map(r=>"input-"+safeId(r));val target=run.map(r=>root.resolve("runs").resolve(d.id+"-"+r))
  try{
   if(selected.nonEmpty){target.foreach(Files.createDirectories(_));require(HostedNode.status(settings).path("running").asBoolean,"共享托管节点未运行")}
   selected.foreach{case(key,f,m,home)=>val meta=Support.copy(m);meta.put("view",f.path("view").asText)
    val relative=key.replace('.', '-')+"/"+m.path("name").asText
    target.foreach{base=>val dest=base.resolve(relative);Files.createDirectories(dest.getParent);try Files.createLink(dest,home.resolve("file").resolve(m.path("name").asText))catch{case _:java.io.IOException=>Files.copy(home.resolve("file").resolve(m.path("name").asText),dest)}}
    val uri=run.map(_=>HostedNode.status(settings).path("url").asText.stripSuffix("/")+"/"+dataset.get+"/"+relative.split('/').map(s=>new java.net.URI(null,null,"/"+s,null).getRawPath.drop(1)).mkString("/")).getOrElse("")
    meta.put("uri",uri);val parts=key.split("\\.");val parent=parts.dropRight(1).foldLeft(out:JsonNode)((n,k)=>n.path(k)).asInstanceOf[ObjectNode];parent.set[JsonNode](parts.last,meta)
   }
   if(selected.nonEmpty&&run.nonEmpty){val n=Support.obj.put("published",true).put("application",d.id).put("run",run.get).put("directory",storage.root.relativize(target.get).toString).put("description",Support.obj.put("title","运行输入 · "+d.config.path("title").asText).put("description","临时分析输入，不列入公共目录").toString);Support.atomic(index.resolve(dataset.get+".json"),n)}
   out
  }catch{case scala.util.control.NonFatal(e)=>target.foreach(remove);dataset.foreach(id=>Files.deleteIfExists(index.resolve(id+".json")));throw e}
 }
 override def finished(application:String,run:String):Unit=synchronized{val path=index.resolve("input-"+safeId(run)+".json");if(Files.exists(path)){val n=Support.copy(Support.read(path));n.put("expires_at",expires);Support.atomic(path,n)}}
 /** Active runs remain readable; orphaned/restarted runs acquire the normal retention period. */
 def cleanup(active:Set[(String,String)]):Unit=synchronized{
  Support.children(root.resolve("staged")).foreach{p=>val m=p.resolve("upload.json");if(Files.isRegularFile(m)&& !Instant.now.isBefore(Instant.parse(Support.read(m).path("expires_at").asText)))remove(p)}
  Support.children(index).filter(_.toString.endsWith(".json")).foreach{p=>val n=Support.copy(Support.read(p));if(!active(n.path("application").asText->n.path("run").asText)){
   if(!n.has("expires_at")){n.put("expires_at",expires);Support.atomic(p,n)}
   else if(!Instant.now.isBefore(Instant.parse(n.path("expires_at").asText))){Files.delete(p);val directory=storage.root.resolve(n.path("directory").asText).normalize;require(directory.startsWith(root.resolve("runs")),"运行输入路径无效");remove(directory)}
  }}
 }
}
