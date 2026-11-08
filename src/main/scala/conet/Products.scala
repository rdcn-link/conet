package conet

import com.fasterxml.jackson.databind.JsonNode
import conet.appbuilder.{ApplicationService,Definition,ProductMetadata,Values}
import java.nio.file.{Files,Path,StandardCopyOption}
import java.time.Instant
import java.security.{SecureRandom,MessageDigest}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Immutable result publications. Public identifiers never grant management access. */
final class Products(settings:Settings) {
 private val cfg=settings.config.path("appbuilder").path("products")
 val directory=settings.root.resolve(cfg.path("directory").asText(".state/products")).toAbsolutePath.normalize
 private val retention=cfg.path("retention_days").asInt(30)
 require(retention>=1&&retention<=3650,"产品保留期限须为 1 至 3650 天")
 Files.createDirectories(directory)
 private def random():String={val bytes=new Array[Byte](24);new SecureRandom().nextBytes(bytes);java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)}
 private def path(id:String):Path={require(id.matches("[A-Za-z0-9_-]{32}"),"产品编号无效");directory.resolve(id)}
 private def index(id:String):Option[JsonNode]={val file=path(id).resolve("publication.json");Option.when(Files.isRegularFile(file))(Support.read(file))}
 private def live(n:JsonNode):Boolean= !n.path("revoked").asBoolean&&Instant.now.isBefore(Instant.parse(n.path("expires_at").asText))
 def list:JsonNode=synchronized{Support.arr(Support.children(directory).filter(p=>Files.isDirectory(p)&&p.getFileName.toString.matches("[A-Za-z0-9_-]{32}")).flatMap(p=>document(p.getFileName.toString)).sortBy(_.path("published_at").asText).reverse)}
 def defaults(service:ApplicationService,id:String):JsonNode={
  val run=service.get(id).getOrElse(throw new IllegalArgumentException("分析记录不存在"));require(run.path("state").asText=="succeeded","只有成功完成的分析可以发布")
  require(run.path("job_yaml").isTextual,"该历史记录缺少完整 Job 快照，请重新执行后发布")
  val d=Definition.parse(xstream.util.YamlDocument.dump(run.path("definition")),run.path("job_yaml").asText)
  val file=service.artifact(id,"analysis.json").filter(Files.isRegularFile(_)).getOrElse(throw new IllegalArgumentException("分析成果文件不存在"))
  val metadata=ProductMetadata.build(d,run,file.getParent)
  metadata.path("sources").elements.asScala.foreach{s=>val source=s.asInstanceOf[com.fasterxml.jackson.databind.node.ObjectNode];settings.centers.find(_.path("datasets").elements.asScala.exists(ds=>ds.path("case").asText==d.config.path("data").path("scope").asText(d.id)&&ds.path("name").asText==s.path("dataset").asText)).foreach(center=>source.put("provider",center.path("title").asText(center.path("id").asText)))}
  val out=Values.obj.put("retention_days",retention);out.set[JsonNode]("metadata",metadata)
  val rights=Values.copy(metadata.path("publication_defaults"));rights.put("verification","self_declared")
  val app=if(rights.path("application").isObject)Values.copy(rights.path("application"))else Values.obj
  Seq("name","license","attribution").foreach(k=>if(!app.has(k))app.put(k,""));Seq("view","download","commercial").foreach(k=>if(!app.has(k))app.put(k,"unconfirmed"));rights.set[JsonNode]("application",app)
  val configured=rights.path("sources").elements.asScala.toVector
  val sources=metadata.path("sources").elements.asScala.toVector.distinctBy(_.path("id").asText).map{s=>
   val policy=configured.find(c=>c.path("id").asText==s.path("id").asText||c.path("dataset").asText.nonEmpty&&c.path("dataset").asText==s.path("dataset").asText||c.path("uri").asText.nonEmpty&&c.path("uri").asText==s.path("uri").asText).map(Values.copy).getOrElse(Values.obj)
   policy.put("id",s.path("id").asText).put("dataset",s.path("dataset").asText).put("uri",s.path("uri").asText)
   if(!policy.has("provider")&&s.has("provider"))policy.set[JsonNode]("provider",s.get("provider"))
   Seq("provider","rights_holder","license","attribution").foreach(k=>if(!policy.has(k))policy.put(k,""));Seq("view","download","commercial").foreach(k=>if(!policy.has(k))policy.put(k,"unconfirmed"));policy
  };rights.set[JsonNode]("sources",Values.array(sources));rights.set[JsonNode]("outputs",if(rights.path("outputs").isObject)rights.path("outputs")else Values.obj);out.set[JsonNode]("rights",rights);out
 }
 private def grant(n:JsonNode,key:String):Boolean=n.path(key).asText=="allowed"
 def publish(service:ApplicationService,id:String,request:JsonNode,nodeUri:String):JsonNode=synchronized{
  val origin=java.net.URI.create(nodeUri);require(origin.getScheme=="dacp"&&origin.getHost!=null&&origin.getPort>0&&origin.getUserInfo==null&&origin.getQuery==null&&origin.getFragment==null&&Option(origin.getPath).forall(p=>p.isEmpty||p=="/"),"产品发布需要有效的共享 DACP 节点地址")

  val preview=defaults(service,id);val metadata=Values.copy(preview.path("metadata"));val rights=request.path("rights")
  require(rights.isObject&&request.path("confirmed").asBoolean,"请确认来源、署名、许可和公开范围")
  val app=rights.path("application");val expected=preview.path("rights").path("sources").elements.asScala.map(_.path("id").asText).toSet
  val sources=rights.path("sources").elements.asScala.toVector
  require(sources.map(_.path("id").asText).distinct.size==sources.size&&sources.map(_.path("id").asText).toSet==expected,"必须确认全部实际引用的数据来源")
  def validateParty(p:JsonNode,owner:String):Unit={require(p.path(owner).asText.trim.nonEmpty&&p.path(owner).asText.length<=300,"请填写贡献者或数据权利人");require(p.path("license").asText.trim.nonEmpty&&p.path("license").asText.length<=3000,"请填写使用许可");Seq("view","download","commercial").foreach(k=>require(Set("allowed","denied","unconfirmed")(p.path(k).asText),"许可状态无效"));require(grant(p,"view"),"存在未确认或禁止公开的许可，不能发布");require(p.path("attribution").asText.length<=3000,"署名要求过长")}
  validateParty(app,"name");sources.foreach(validateParty(_,"rights_holder"))
  def preserveKnown(before:JsonNode,after:JsonNode):Unit={Seq("view","download","commercial").foreach(k=>require(before.path(k).asText!="denied"||after.path(k).asText=="denied","不能放宽已有的禁止性许可"));Seq("license","rights_holder","name").foreach(k=>if(before.path(k).asText.nonEmpty)require(before.path(k)==after.path(k),"不能改写已配置的权利人或许可证"))}
  preserveKnown(preview.path("rights").path("application"),app)
  sources.foreach(s=>preserveKnown(preview.path("rights").path("sources").elements.asScala.find(_.path("id").asText==s.path("id").asText).get,s))
  val all=metadata.path("artifacts").elements.asScala.toVector
  val selected=request.path("downloads").elements.asScala.map(_.asText).toVector
  require(selected.distinct.size==selected.size&&selected.forall(i=>all.exists(_.path("id").asText==i)),"下载成果选择无效")
  require(selected.isEmpty||grant(app,"download")&&sources.forall(grant(_,"download")),"存在未确认或禁止下载的许可，请取消成果下载或完善授权")
  val outputPolicies=if(rights.path("outputs").isObject)rights.path("outputs")else Values.obj
  val configuredOutputs=preview.path("rights").path("outputs")
  all.foreach{a=>val id=a.path("id").asText;val base=configuredOutputs.path(id);require(base.path("view").asText!="denied","某项成果的既有许可禁止公开");require(!selected.contains(id)||base.path("download").asText!="denied","某项成果的既有许可禁止下载");val p=outputPolicies.path(id);if(p.has("view"))require(grant(p,"view"),"某项成果不允许公开，请核对输出许可");if(selected.contains(a.path("id").asText)&&p.has("download"))require(grant(p,"download"),"某项成果不允许下载")}
  val publisher=request.path("publisher_name").asText.trim;require(publisher.nonEmpty&&publisher.length<=300,"请填写发布者名称，可使用匿名署名")
  val resultId=random();val datasetId="result-"+resultId;val datasetUri=nodeUri.stripSuffix("/")+"/"+datasetId;val temp=directory.resolve(".pending-"+resultId);Files.createDirectory(temp)
  try{
   val artifacts=all.map{a=>val n=Values.copy(a);val source=service.artifact(id,a.path("file").asText).filter(Files.isRegularFile(_)).getOrElse(throw new IllegalArgumentException("成果文件缺失"));val target=temp.resolve(a.path("file").asText)
    // Hard links retain a fixed result without duplicating bytes on the same filesystem.
    try Files.createLink(target,source)catch{case _:java.io.IOException=>Files.copy(source,target);case _:UnsupportedOperationException=>Files.copy(source,target)}
    require(ProductMetadata.fileDigest(target)==a.path("sha256").asText,"成果文件在发布过程中发生变化")
    n.put("download_allowed",selected.contains(a.path("id").asText));if(selected.contains(a.path("id").asText))n.put("item_uri",datasetUri+"/"+a.path("file").asText);n
   }
   val now=Instant.now;val expires=now.plusSeconds(retention.toLong*86400)
   val cleanRights=Values.obj.put("verification","self_declared").put("publisher_name",publisher).put("confirmed_at",now.toString)
   cleanRights.set[JsonNode]("application",app);cleanRights.set[JsonNode]("sources",Values.array(sources.map{s=>val clean=Values.copy(s);val fact=preview.path("rights").path("sources").elements.asScala.find(_.path("id").asText==s.path("id").asText).get;Seq("uri","dataset").foreach(k=>clean.set[JsonNode](k,fact.path(k)));clean}))
   cleanRights.set[JsonNode]("outputs",outputPolicies);cleanRights.put("commercial_use",if(grant(app,"commercial")&&sources.forall(grant(_,"commercial")))"allowed"else"not_granted")
   metadata.remove("publication_defaults");metadata.set[JsonNode]("platform",Values.obj.put("name",settings.config.path("name").asText("CONET")).put("role","analysis coordination and result publication"));metadata.set[JsonNode]("rights",cleanRights);metadata.set[JsonNode]("artifacts",Values.array(artifacts));metadata.put("dataset_id",datasetId).put("dataset_uri",datasetUri).put("metadata_uri",datasetUri+"/metadata.json").put("report_uri",datasetUri+"/report.md").put("product_id",resultId).put("published_at",now.toString).put("expires_at",expires.toString)
   Support.write(temp.resolve("metadata.json"),metadata);Support.writeText(temp.resolve("report.md"),ProductMetadata.report(metadata))
   val internal=Values.obj.put("created_at",now.toString).put("expires_at",expires.toString).put("revoked",false)
   internal.set[JsonNode]("downloads",Values.array(selected.map(JsonNodeFactory.text)))
   Support.write(temp.resolve("publication.json"),internal);Files.move(temp,path(resultId),StandardCopyOption.ATOMIC_MOVE)
   Values.obj.put("id",resultId).put("dataset_id",datasetId).put("dataset_uri",datasetUri).put("preview_url",s"/products/$resultId/").put("expires_at",expires.toString)
  }catch{case NonFatal(e)=>Support.walk(temp).sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_));throw e}
 }
 def document(id:String):Option[JsonNode]=index(id).filter(live).map(_=>Support.read(path(id).resolve("metadata.json")))
 def data(id:String):Option[Path]=index(id).filter(live).map(_=>path(id).resolve("analysis.json"))
 def download(id:String,name:String):Option[Path]=index(id).filter(live).flatMap{n=>
  val allowed=Set("metadata.json","report.md")++document(id).toVector.flatMap(_.path("artifacts").elements.asScala.filter(_.path("download_allowed").asBoolean).map(_.path("file").asText))
  Option.when(allowed(name)&& !name.contains("/")&& !name.contains("\\"))(path(id).resolve(name)).filter(Files.isRegularFile(_))
 }
 def cleanupExpired():Int=synchronized{var removed=0;Support.children(directory).filter(p=>p.getFileName.toString.matches("[A-Za-z0-9_-]{32}")).foreach{folder=>index(folder.getFileName.toString).filterNot(live).foreach{record=>if(!record.path("payload_removed").asBoolean){Support.children(folder).filter(p=>Files.isRegularFile(p)&& !Set("publication.json","metadata.json","report.md")(p.getFileName.toString)).foreach{p=>Files.delete(p);removed+=1};val updated=Values.copy(record).put("payload_removed",true);Support.atomic(folder.resolve("publication.json"),updated)}}};removed}
 def revoke(id:String):Boolean=synchronized{index(id).exists{n=>val updated=Values.copy(n).put("revoked",true);Support.atomic(path(id).resolve("publication.json"),updated);true}}
}
private object JsonNodeFactory {def text(s:String):JsonNode=xstream.util.Json.mapper.getNodeFactory.textNode(s)}
