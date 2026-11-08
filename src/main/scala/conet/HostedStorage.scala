package conet

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path,LinkOption,StandardCopyOption}
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import scala.jdk.CollectionConverters._

/** Shared file storage and explicit immutable dataset publications. */
final class HostedStorage(settings:Settings) {
 private val config=settings.config.path("hosting")
 val root=settings.root.resolve(config.path("directory").asText(".state/hosting")).toAbsolutePath.normalize
 private val defaultQuotaBytes=config.path("quota_bytes").asLong(1024L*1024*1024)
 val maxUploadBytes=config.path("max_upload_bytes").asLong(16L*1024*1024)
 require(defaultQuotaBytes>0&&maxUploadBytes>0&&maxUploadBytes<=64L*1024*1024,"托管配额或上传上限无效")
 Files.createDirectories(root);Files.setPosixFilePermissions(root,PosixFilePermissions.fromString("rwx------"));Files.createDirectories(root.resolve("publications"))
 private val home=root.resolve("storage");Files.createDirectories(home.resolve("files"))

 private def quotaBytes:Long={val p=home.resolve("quota.json");if(Files.exists(p))Support.read(p).path("bytes").asLong(defaultQuotaBytes)else defaultQuotaBytes}
 private def resolve(raw:String):Path={val base=home.resolve("files");require(raw!=null&&raw.length<=1000&& !raw.contains("\\")&& !raw.exists(_.isControl),"文件路径无效");val relative=Path.of(raw);require(!relative.isAbsolute&&relative.iterator.asScala.forall(p=>p.toString!=".."&&p.toString!="."),"文件路径无效");val p=base.resolve(relative).normalize;require(p.startsWith(base),"文件必须位于共享存储内");var current=base;require(!Files.isSymbolicLink(base)&&base.toRealPath().startsWith(root),"存储路径无效");base.relativize(p).iterator.asScala.foreach{part=>current=current.resolve(part);require(!Files.isSymbolicLink(current),"不允许符号链接")};p}
 private def files(base:Path):Vector[Path]={if(!Files.exists(base))return Vector.empty;val stream=Files.walk(base);try stream.iterator.asScala.filter(p=>Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).toVector finally stream.close()}
 private def usage:Long=files(home).map(Files.size(_)).sum
 def publishedDatasetCount:Int=synchronized{Support.children(root.resolve("publications")).filter(_.toString.endsWith(".json")).map(Support.read).count(n=>n.path("published").asBoolean&&(!n.has("expires_at")||Instant.now.isBefore(Instant.parse(n.path("expires_at").asText))))}
 def totalBytes:Long=synchronized{files(root).map(Files.size(_)).sum}
 private def safeName(raw:String):String={require(raw.matches("[a-zA-Z0-9][a-zA-Z0-9_-]{0,47}"),"数据集标识须为 1–48 位字母、数字、横线或下划线");raw}
 private def descriptor(name:String)=root.resolve("publications").resolve(name+".json")
 private def datasetName(name:String)="u"+"conet"+"-"+safeName(name)
 def summary:JsonNode=synchronized{Support.obj.put("used_bytes",usage).put("quota_bytes",quotaBytes).put("max_upload_bytes",maxUploadBytes).put("shared_node",true).put("node_url",config.path("node_url").asText(HostedNode.status(settings).path("url").asText))}
 def list(raw:String):JsonNode=synchronized{val base=resolve(raw);require(Files.isDirectory(base),"目录不存在");Support.arr(Support.children(base).filterNot(Files.isSymbolicLink(_)).sortBy(p=>(!Files.isDirectory(p),p.getFileName.toString)).map{p=>Support.obj.put("name",p.getFileName.toString).put("path",home.resolve("files").relativize(p).toString).put("folder",Files.isDirectory(p)).put("bytes",if(Files.isRegularFile(p))Files.size(p)else 0L).put("modified_at",Files.getLastModifiedTime(p).toString)})}
 def mkdir(path:String):Unit=synchronized{val target=resolve(path);require(target!=home.resolve("files"),"目录已存在");Files.createDirectories(target);()}
 def upload(n:JsonNode):JsonNode=synchronized{val path=n.path("path").asText;val dest=resolve(path);require(dest!=home.resolve("files")&& !Files.isDirectory(dest),"请选择文件路径");val data=n.path("content_base64").asText;require(data.length<=((maxUploadBytes+2)/3)*4,"文件超过上传上限");val bytes=java.util.Base64.getDecoder.decode(data);require(bytes.length<=maxUploadBytes,"文件超过上传上限");val previous=if(Files.isRegularFile(dest))Files.size(dest)else 0L;require(usage-previous+bytes.length<=quotaBytes,"存储空间不足");Files.createDirectories(dest.getParent);val temp=Files.createTempFile(dest.getParent,".upload-",".tmp");try{Files.write(temp,bytes);Files.move(temp,dest,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}finally Files.deleteIfExists(temp);Support.obj.put("path",path).put("bytes",bytes.length)}
 def download(path:String):Path=synchronized{val p=resolve(path);require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS),"文件不存在");p}
 def delete(path:String):Unit=synchronized{val p=resolve(path);require(p!=home.resolve("files"),"不能删除存储根目录");require(Files.exists(p),"文件不存在");if(Files.isDirectory(p))require(Support.children(p).isEmpty,"请先清空目录");Files.delete(p)}
 def datasets:JsonNode=synchronized{Support.arr(Support.children(home.resolve("datasets")).filter(_.toString.endsWith(".json")).map(Support.read).map{n=>val r=Support.copy(n);val p=descriptor(n.path("id").asText);r.put("published",Files.exists(p)&&Support.read(p).path("published").asBoolean);r})}
 def saveDataset(n:JsonNode):JsonNode=synchronized{val name=safeName(n.path("name").asText);val source=resolve(n.path("path").asText);require(Files.isDirectory(source),"请选择目录");val description=metadata(n.path("title").asText,n.path("description").asText);val id=datasetName(name);val r=Support.obj.put("id",id).put("name",name).put("path",n.path("path").asText).put("title",n.path("title").asText).put("description",n.path("description").asText).put("published",false);Support.atomic(home.resolve("datasets").resolve(id+".json"),r);r}
 def resultDatasets:JsonNode=synchronized{Support.arr(Support.children(home.resolve("results")).filter(p=>Files.isRegularFile(p.resolve("run.json"))).map{p=>val run=Support.read(p.resolve("run.json"));val r=Support.obj.put("id",run.path("id").asText).put("app_id",run.path("app_id").asText).put("title",run.path("definition").path("title").asText("分析结果")).put("created_at",run.path("finished_at").asText).put("readonly",true).put("published",false).put("url","/apps/"+run.path("app_id").asText+"/?run="+run.path("id").asText);val artifacts=Support.obj;run.path("artifacts").fields.asScala.foreach{entry=>val name=entry.getValue.asText.split('/').last;artifacts.put(entry.getKey,"/api/storage/result-files/"+p.getFileName.toString+"/"+name)};r.set[JsonNode]("artifacts",artifacts);r})}
 def resultFile(key:String,name:String):Path=synchronized{require(key.matches("[A-Za-z0-9_-]{1,160}")&&name.nonEmpty&& !name.contains("/")&& !name.contains("\\")&&name!="."&&name!="..","结果文件路径无效");val folder=home.resolve("results").resolve(key);val run=Support.read(folder.resolve("run.json"));require(run.path("artifacts").elements.asScala.exists(_.asText.split('/').last==name),"结果文件未声明");val file=folder.resolve(name);require(Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS),"结果文件不存在");file}
 private def metadata(title:String,description:String):String={require(title.trim.nonEmpty&&title.length<=200&&description.length<=10000,"请填写标题和说明（长度超限）");Support.obj.put("title",title).put("description",description).put("owner","CONET").toString}
 private def snapshot(name:String,sourceFiles:Vector[(Path,String)],description:String,expires:Option[String],kind:String):JsonNode={
  require(!Files.exists(descriptor(name))|| !Support.read(descriptor(name)).path("published").asBoolean,"数据集已经公开，请先撤回后重新发布")
  val size=sourceFiles.map(p=>Files.size(p._1)).sum;require(usage+size<=quotaBytes,"发布快照超过存储配额")
  val target=home.resolve("published").resolve(name+"-"+java.util.UUID.randomUUID.toString);Files.createDirectories(target)
  try{sourceFiles.foreach{case(source,relative)=>val dest=target.resolve(relative).normalize;require(dest.startsWith(target)&& !Path.of(relative).isAbsolute,"成果文件名无效");Files.createDirectories(dest.getParent);try Files.createLink(dest,source)catch{case _:java.io.IOException=>Files.copy(source,dest)}}
   val n=Support.obj.put("id",name).put("description",Support.copy(xstream.util.Json.mapper.readTree(description)).set[JsonNode]("publication",Support.obj.put("policy","public")).toString).put("directory",root.relativize(target).toString).put("published",true).put("kind",kind).put("bytes",size).put("created_at",Support.now);expires.foreach(n.put("expires_at",_));Support.atomic(descriptor(name),n);Support.copy(n)
  }catch{case scala.util.control.NonFatal(e)=>Support.walk(target).sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_));throw e}
 }
 def publish(n:JsonNode):JsonNode=synchronized{require(n.path("confirmed_public").asBoolean,"请确认数据集将对外公开");saveDataset(n);val source=resolve(n.path("path").asText);require(Files.isDirectory(source),"请选择要发布的目录");val all=files(source);require(all.nonEmpty,"不能发布空目录");all.foreach(p=>resolve(home.resolve("files").relativize(p).toString));snapshot(datasetName(n.path("name").asText),all.map(p=>p->source.relativize(p).toString),metadata(n.path("title").asText,n.path("description").asText),None,"dataset")}
 def publishProduct(id:String,products:Products):JsonNode=synchronized{val m=products.document(id).getOrElse(throw new IllegalArgumentException("产品不存在"));val selected=m.path("artifacts").elements.asScala.filter(_.path("download_allowed").asBoolean).map(_.path("file").asText).toVector++Vector("metadata.json","report.md");val sources=selected.distinct.map(name=>products.download(id,name).getOrElse(throw new IllegalArgumentException("成果不可下载"))->name);val description=Support.obj.put("title",m.path("title").asText).put("description",m.path("abstract").asText).put("product_id",id).put("published_at",m.path("published_at").asText).put("dataset_uri",m.path("dataset_uri").asText).put("application_id",m.path("application").path("id").asText).put("run_id",m.path("run_id").asText).put("publisher",m.path("rights").path("publisher_name").asText).put("metadata_uri",m.path("metadata_uri").asText).put("expires_at",m.path("expires_at").asText);snapshot(m.path("dataset_id").asText,sources,description.toString,Some(m.path("expires_at").asText),"product")}
 def captureRun(service:conet.appbuilder.ApplicationService,id:String):Unit=synchronized{
  val target=home.resolve("results").resolve(service.id+"-"+id)
  if(Files.isDirectory(target))return
  val run=service.get(id).getOrElse(throw new IllegalArgumentException("任务不存在"));if(run.path("state").asText!="succeeded")return
  val sourceFiles=run.path("artifacts").elements.asScala.map(_.asText.split('/').last).toVector.distinct.flatMap(name=>service.artifact(id,name).filter(Files.isRegularFile(_)).map(_->name))
  val size=sourceFiles.map(f=>Files.size(f._1)).sum+xstream.util.Json.mapper.writeValueAsBytes(run).length;require(usage+size<=quotaBytes,"结果归档超过存储配额")
  val temp=home.resolve("results").resolve(".pending-"+java.util.UUID.randomUUID);Files.createDirectories(temp)
  try{sourceFiles.foreach{case(source,name)=>try Files.createLink(temp.resolve(name),source)catch{case _:java.io.IOException=>Files.copy(source,temp.resolve(name))}};Support.write(temp.resolve("run.json"),run);Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE)}finally if(Files.exists(temp))Support.walk(temp).sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
 }
 def withdrawProduct(id:String):Unit=synchronized{val name="result-"+id;require(name.matches("[a-zA-Z0-9_-]{1,100}"),"产品标识无效");if(Files.exists(descriptor(name)))unpublish(name)}
 def unpublish(id:String):Unit=synchronized{require(id.matches("[a-zA-Z0-9_-]{1,100}"),"数据集标识无效");val path=descriptor(id);require(Files.isRegularFile(path),"数据集不存在");val n=Support.copy(Support.read(path));n.put("published",false);Support.atomic(path,n)}
}
