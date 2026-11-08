package conet

import com.fasterxml.jackson.databind.JsonNode
import dacp.client.{Source,TlsConfig,DatasetInfo,Item}
import java.net.URI
import java.nio.file.{Files,Path}
import scala.jdk.CollectionConverters._
import scala.util.Using

/** Browsing is restricted to datasets advertised by the configured Union. */
final class CatalogGateway(settings:Settings) {
 private var entries=Vector.empty[DatasetInfo];private var expires=0L;private var complete=true
 private val permits=new java.util.concurrent.Semaphore(4)
 private def bounded[A](f: =>A):A={require(permits.tryAcquire(),"目录正在处理其他请求，请稍后重试");try f finally permits.release()}
 private def tls=TlsConfig(ca=Some(Path.of(settings.connection.path("ca").asText)),tokenFile=Some(Path.of(settings.connection.path("token_file").asText)))
 private def encode(s:String)=new URI(null,null,"/"+s,null).getRawPath.drop(1)
 private def unionConfig=xstream.util.YamlDocument.parse(Files.readString(settings.network.resolve("union.yaml")))
 def s3Endpoint:Option[String]={val n=unionConfig;val s=n.path("s3");Option.when(s.path("enabled").asBoolean&&s.path("port").asInt>0)("https://"+URI.create(n.path("public_url").asText).getHost+":"+s.path("port").asInt)}
 private def directory(refresh:Boolean=false):Vector[DatasetInfo]=synchronized{if(refresh||System.currentTimeMillis>=expires){val d=Source.datasets(settings.connection.path("union_url").asText,tls,15);entries=d.entries;complete=d.complete;expires=System.currentTimeMillis+30000};entries}
 private def dataset(name:String):DatasetInfo={require(name.nonEmpty&&name.length<=200&& !name.exists(c=>c=='/'||c=='\\'||c.isControl),"数据集标识无效");directory().find(_.name==name).getOrElse(throw new IllegalArgumentException("数据集不在当前联合目录中"))}
 def list(refresh:Boolean=false):JsonNode=bounded{
  val rows=directory(refresh).map{d=>val description=xstream.util.Json.mapper.readTree(d.description);val n=Support.obj.put("id",d.name).put("title",description.path("title").asText(d.name)).put("description",description.path("description").asText).put("dacp_uri",d.uri).put("node_identity",d.nodeIdentity).put("product",description.has("product_id"));s3Endpoint.foreach(e=>n.put("s3_uri",e+"/"+encode(d.name)+"/"));n.set[JsonNode]("metadata",description);n}
  val out=Support.obj.put("complete",complete).put("s3_enabled",s3Endpoint.nonEmpty);out.set[JsonNode]("datasets",Support.arr(rows));out
 }
 private def itemJson(d:DatasetInfo,i:Item):JsonNode={
  val n=Support.obj.put("path",i.path).put("name",i.path.stripSuffix("/").split('/').lastOption.filter(_.nonEmpty).getOrElse("/")).put("kind",i.kind).put("format",i.format).put("dacp_uri",d.uri.stripSuffix("/")+i.path.split('/').map(encode).mkString("/"))
  n.set[JsonNode]("views",Support.arr(i.views.map(xstream.util.Json.mapper.getNodeFactory.textNode)));i.totalSizeBytes.foreach(n.put("bytes",_));i.recordCount.foreach(n.put("records",_));i.lastModifiedTimeMs.foreach(n.put("modified_at_ms",_));i.creationTimeMs.foreach(n.put("created_at_ms",_))
  if(i.views.contains("bytes"))s3Endpoint.foreach(e=>n.put("s3_uri",e+"/"+encode(d.name)+i.path.split('/').map(encode).mkString("/")))
  n.set[JsonNode]("schema",Support.arr(i.schema.toVector.flatMap(_.getFields.asScala).map(f=>Support.obj.put("name",f.getName).put("type",f.getType.toString).put("nullable",f.isNullable))));n
 }
 def items(name:String,path:String):JsonNode=bounded{val d=dataset(name);Using.resource(Source.open(d.uri,tls,15)){source=>val rows=if(path=="/")source.list()else source.list(path);val out=Support.obj.put("dataset",name).put("path",path);out.set[JsonNode]("items",Support.arr(rows.take(1000).map(itemJson(d,_))));out.put("truncated",rows.size>1000);out}}
 def preview(name:String,path:String):JsonNode=bounded{val d=dataset(name);Using.resource(Source.open(d.uri,tls,15)){source=>
  val i=source.describe(path);val out=Support.copy(itemJson(d,i))
  if(i.views.contains("records")){
   val rows=xstream.util.Json.mapper.createArrayNode();val columns=scala.collection.mutable.ArrayBuffer.empty[String]
   Using.resource(source.open(path,limit=Some(20L))){item=>Using.resource(item.asRecords()){reader=>columns++=reader.schema.getFields.asScala.take(64).map(_.getName);while(rows.size<20&&reader.next()){val root=reader.root;for(row<-0 until math.min(root.getRowCount,20-rows.size)){val n=Support.obj;columns.foreach{c=>val value=root.getVector(c).getObject(row);if(value==null)n.putNull(c)else n.put(c,value.toString.take(512))};rows.add(n)}}}}
   out.put("preview_kind","records").put("row_limit",20).put("column_limit",64);out.set[JsonNode]("columns",Support.arr(columns.map(xstream.util.Json.mapper.getNodeFactory.textNode)));out.set[JsonNode]("rows",rows)
  }else if(i.views.contains("bytes")){
   val bytes=Using.resource(source.open(path))(item=>Using.resource(item.asBytes())(_.readNBytes(4096)))
   val text=scala.util.Try(java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString).toOption.filter(_.forall(c=> !c.isControl||c=='\n'||c=='\r'||c=='\t'))
   out.put("preview_kind",if(text.nonEmpty)"text"else"hex").put("sample_bytes",bytes.length).put("byte_limit",4096).put("sample",text.getOrElse(bytes.take(256).map(b=>f"${b&255}%02x").grouped(16).map(_.mkString(" ")).mkString("\n")))
  }else out.put("preview_kind","metadata").put("note","该 Item 提供专用视图，当前展示结构与元数据，不展开数组或科学数据内容。")
  out
 }}
 def presign(name:String,path:String):JsonNode=bounded{
  val d=dataset(name);val i=Using.resource(Source.open(d.uri,tls,15))(_.describe(path));require(i.views.contains("bytes"),"该 Item 没有可通过 S3 读取的 bytes 视图")
  val endpoint=s3Endpoint.getOrElse(throw new IllegalArgumentException("当前 Union 未启用 S3 服务"));val seconds=settings.config.path("catalog").path("presign_seconds").asInt(300);require(seconds>=30&&seconds<=900,"临时链接有效期须为 30–900 秒")
  val config=unionConfig;val directory=Path.of(config.path("credential_directory").asText)
  Using.resource(new dacp.union.Credentials(directory)){store=>val id=store.list.find(n=>n.path("kind").asText=="s3"&&n.path("subject").asText=="conet-catalog").map(_.path("id").asText).getOrElse(throw new IllegalArgumentException("尚未配置目录临时链接凭据，请运行 bin/conet catalog-up"));val credential=store.s3(id).getOrElse(throw new IllegalArgumentException("临时链接凭据不可用"));require(store.permissions(credential)("data:read"),"临时链接账号无读取权限")
   val uri=URI.create(endpoint+"/"+encode(name)+path.split('/').map(encode).mkString("/"));Support.obj.put("url",dacp.union.SigV4.presignGet(uri,credential.id,credential.secret.get,seconds).toASCIIString).put("expires_at",java.time.Instant.now.plusSeconds(seconds).toString).put("expires_in",seconds)
  }
 }
}
