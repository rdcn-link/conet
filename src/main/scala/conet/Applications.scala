package conet

import conet.appbuilder.{ApplicationService,Definition,InputValidation,UnionBackend,Values}
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path,StandardCopyOption}
import scala.jdk.CollectionConverters._

/** Published application definitions and revision-checked editing. */
final class Applications(settings:Settings,cases:Map[String,CaseService],inputFiles:conet.appbuilder.InputFiles=conet.appbuilder.InputFiles.unavailable) extends AutoCloseable {
 private val directory=settings.state.resolve("appbuilder/definitions")
 private val services=scala.collection.mutable.Map.empty[String,ApplicationService] ++ cases.map{case(id,c)=>id->c.application}
 Support.children(directory).filter(Files.isDirectory(_)).filterNot(p=>cases.contains(p.getFileName.toString)).foreach{home=>val d=Definition.load(home);checkScope(d);services(d.id)=create(d)}
 private def checkScope(d:Definition):Unit={val scope=d.config.path("data").path("scope").asText("");if(scope.nonEmpty)require(settings.cases.exists(_.path("id").asText==scope),"数据上下文必须引用已配置的应用数据集")else{
  def usesManifest(n:JsonNode):Boolean=if(n.isObject)n.fields.asScala.exists(e=>Set("default_manifest","initial_manifest","coverage_manifest")(e.getKey)||usesManifest(e.getValue))else if(n.isArray)n.elements.asScala.exists(usesManifest)else false
  require(!usesManifest(d.config)&& !d.variableBindings.values.exists(b=>b.has("dataset")||b.has("manifest")),"该应用需要配置 data.scope 数据上下文")
 }}
 private def manifest(d:Definition):JsonNode={val scope=d.config.path("data").path("scope").asText("");if(scope.isEmpty)Support.obj else Support.read(settings.caseState(scope).resolve("data/manifest.json"))}
 private def create(d:Definition)=new ApplicationService(d,settings.state.resolve("appbuilder/runs").resolve(d.id),()=>manifest(d),new UnionBackend(()=>settings.connection),s"/apps/${d.id}",inputFiles)
 private def revision(d:Definition):String=java.security.MessageDigest.getInstance("SHA-256").digest((xstream.util.YamlDocument.dump(Values.copy(d.config).put("job","job.yaml"))+d.jobYaml).getBytes(java.nio.charset.StandardCharsets.UTF_8)).map(b=>f"${b&255}%02x").mkString
 def activeRunKeys:Set[(String,String)]=synchronized{services.values.flatMap(s=>s.records.filter(r=>Set("queued","running")(r.path("state").asText)).map(r=>s.id->r.path("id").asText)).toSet}
 def statistics:JsonNode=synchronized{conet.appbuilder.Usage.aggregate(services.values.toVector.flatMap(_.records))}
 def statisticsFor(ids:Set[String]):JsonNode=synchronized{conet.appbuilder.Usage.aggregate(services.values.filter(s=>ids(s.id)).toVector.flatMap(_.records))}
 def list:JsonNode=synchronized{Values.array(services.values.toVector.sortBy(_.id).map{s=>
  val p=s.definition.config.path("presentation");val n=Values.obj.put("id",s.id).put("title",s.definition.config.path("title").asText).put("description",s.definition.config.path("description").asText).put("url",s"/apps/${s.id}/").put("cover_url",p.path("cover").asText("/assets/app-cover.svg")).put("submissions",s.records.size).put("revision",revision(s.definition))
  n.set[JsonNode]("tags",if(p.path("tags").isArray)p.path("tags")else Values.array(Seq.empty));n
 })}
 def upload(n:JsonNode):JsonNode={
  val text=n.path("content_base64");require(text.isTextual&&text.asText.length<=2800000,"图片大小不能超过 2 MB")
  val bytes=try java.util.Base64.getDecoder.decode(text.asText)catch{case _:IllegalArgumentException=>throw new IllegalArgumentException("图片编码无效")}
  require(bytes.nonEmpty&&bytes.length<=2*1024*1024,"图片大小不能超过 2 MB")
  val stream=javax.imageio.ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(bytes));require(stream!=null,"图片无效")
  val extension=try{val readers=javax.imageio.ImageIO.getImageReaders(stream);require(readers.hasNext,"仅支持 PNG、JPEG 图片");val reader=readers.next();try{reader.setInput(stream);val format=reader.getFormatName.toLowerCase(java.util.Locale.ROOT);require(Set("png","jpeg","jpg")(format),"仅支持 PNG、JPEG 图片");require(reader.getWidth(0)>0&&reader.getHeight(0)>0&&reader.getWidth(0).toLong*reader.getHeight(0)<=16000000,"图片尺寸不能超过 1600 万像素");if(format=="png")"png"else"jpg"}finally reader.dispose()}finally stream.close()
  val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).map(b=>f"${b&255}%02x").mkString
  val home=settings.state.resolve("appbuilder/media");Files.createDirectories(home);Files.write(home.resolve(digest+"."+extension),bytes)
  Values.obj.put("url",s"/appbuilder/media/$digest.$extension")
 }
 def media(name:String):Option[Path]={require(name.matches("[a-f0-9]{64}\\.(png|jpg)"),"图片路径无效");val path=settings.state.resolve("appbuilder/media").resolve(name);Option.when(Files.isRegularFile(path))(path)}
 def get(id:String):Option[ApplicationService]=synchronized{services.get(id)}
 def document(id:String):Option[JsonNode]=get(id).map{s=>val n=Values.obj.put("url",s"/apps/${s.id}/").put("application_yaml",xstream.util.YamlDocument.dump(s.definition.config)).put("job_yaml",s.definition.jobYaml).put("revision",revision(s.definition));n.set[JsonNode]("application",s.definition.publicConfig);n}
 def convert(n:JsonNode):JsonNode={
  def node(key:String):JsonNode={val value=n.path(key);val parsed=if(value.isTextual)xstream.util.YamlDocument.parse(value.asText)else value;require(parsed.isObject,"配置必须为映射："+key);parsed}
  val app=node("application");val job=node("job");val out=Values.obj
  out.set[JsonNode]("application",app);out.set[JsonNode]("job",job);out.put("application_yaml",xstream.util.YamlDocument.dump(app)).put("job_yaml",xstream.util.YamlDocument.dump(job));out
 }
 def validate(n:JsonNode):JsonNode={require(n.path("application_yaml").isTextual&&n.path("job_yaml").isTextual,"请提供应用 YAML 与 Job YAML");val d=Definition.parse(n.path("application_yaml").asText,n.path("job_yaml").asText);checkScope(d);val out=Values.obj.put("valid",true).put("id",d.id);out.set[JsonNode]("application",d.publicConfig);if(n.has("inputs"))out.set[JsonNode]("inputs",d.validate(n.path("inputs"),manifest(d)));out}
 def save(n:JsonNode):JsonNode=synchronized{
  validate(n);val d=Definition.parse(n.path("application_yaml").asText,n.path("job_yaml").asText)
  services.get(d.id).foreach{s=>require(!s.active,"应用有正在执行或排队的分析，请完成或取消后再保存");require(n.path("revision").asText==revision(s.definition),"应用已存在或已被修改，请先加载当前版本")}
  val home=directory.resolve(d.id);Files.createDirectories(home)
  // Each revision is written completely before making it the active definition.
  val jobName="job-"+revision(d)+".yaml";Support.writeText(home.resolve(jobName),d.jobYaml)
  val cfg=Values.copy(d.config);cfg.put("job",jobName)
  val temp=home.resolve("application.yaml.tmp");Files.writeString(temp,xstream.util.YamlDocument.dump(cfg));Files.move(temp,home.resolve("application.yaml"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
  val loaded=Definition.load(home)
  val service=cases.get(d.id) match{case Some(c)=>c.reload(loaded);c.application;case None=>services.get(d.id).foreach(_.close());create(loaded)}
  services(d.id)=service
  document(d.id).get
 }
 def close():Unit=synchronized{services.filterNot{case(id,_)=>cases.contains(id)}.values.foreach(_.close())}
}
