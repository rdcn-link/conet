package conet

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.sun.net.httpserver.{HttpExchange,HttpServer}
import java.net.{InetSocketAddress,URI}
import java.net.http.{HttpRequest,HttpResponse}
import java.nio.file.{Files,Path}
import java.time.Duration
import java.util.concurrent.{Callable,Executors,TimeUnit}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

final class Web(settings:Settings) extends AutoCloseable {
 private val uploads=new RunUploads(settings)
 private val cases=settings.cases.map(c=>c.path("id").asText->new CaseService(settings,c.path("id").asText,uploads)).toMap
 private val applications=new Applications(settings,cases,uploads)
 private val catalog=new CatalogGateway(settings)
 private val packages=new ApplicationPackages(applications)
 private val pageGenerator=new ApplicationPageGenerator(settings,applications)
 private val storage=new HostedStorage(settings)
 private val products=new Products(settings)
 private val ratings=new Ratings(settings.root.resolve(".state"))
 private def submitter(x:HttpExchange):Option[JsonNode]=None
 private def submitted(x:HttpExchange,app:String,result:(Int,JsonNode)):Unit={val(code,value)=result;json(x,code,value)}
 private val workers=Executors.newFixedThreadPool(16)
 private val observations=Executors.newFixedThreadPool(math.max(4,settings.cases.size+2))
 private val scheduler=Executors.newSingleThreadScheduledExecutor()
 @volatile private var cached:JsonNode=Support.obj.put("available",false)
 private val servers=scala.collection.mutable.ArrayBuffer.empty[HttpServer]
 private def mime(path:Path):String={val n=path.toString;if(n.endsWith(".html"))"text/html; charset=utf-8"else if(n.endsWith(".js"))"text/javascript; charset=utf-8"else if(n.endsWith(".css"))"text/css; charset=utf-8"else if(n.endsWith(".svg"))"image/svg+xml"else if(n.endsWith(".jpg"))"image/jpeg"else if(n.endsWith(".png"))"image/png"else if(n.endsWith(".geojson"))"application/geo+json"else if(n.endsWith(".csv"))"text/csv"else if(n.endsWith(".md"))"text/markdown; charset=utf-8"else if(n.endsWith(".yaml"))"application/yaml"else"application/json"}
 private def send(x:HttpExchange,code:Int,body:Array[Byte],kind:String):Unit={x.getResponseHeaders.set("Content-Type",kind);x.getResponseHeaders.set("Cache-Control","no-store");x.getResponseHeaders.set("X-Content-Type-Options","nosniff");x.sendResponseHeaders(code,body.length);try x.getResponseBody.write(body)finally x.close()}
 private def json(x:HttpExchange,code:Int,n:JsonNode):Unit=send(x,code,xstream.util.Json.mapper.writeValueAsBytes(n),"application/json")
 private def file(x:HttpExchange,path:Path):Unit=if(Files.isRegularFile(path))send(x,200,Files.readAllBytes(path),mime(path))else json(x,404,Support.obj.put("error","未找到资源"))
 private def checkOrigin(x:HttpExchange):Unit={Option(x.getRequestHeaders.getFirst("Origin")).foreach(o=>if(URI.create(o).getAuthority!=x.getRequestHeaders.getFirst("Host"))throw new SecurityException("不允许跨站提交"))}
 private def requestBody(x:HttpExchange,maxBytes:Int=16384):JsonNode={
  checkOrigin(x)
  require(Option(x.getRequestHeaders.getFirst("Content-Type")).exists(_.split(';').head=="application/json"),"请求必须采用 JSON 格式")
  val bytes=try x.getRequestBody.readNBytes(maxBytes+1)finally x.getRequestBody.close();require(bytes.nonEmpty&&bytes.length<=maxBytes,"请求大小无效")
  xstream.util.Json.mapper.readTree(bytes)
 }
 private def uploadInput(x:HttpExchange,service:conet.appbuilder.ApplicationService,path:String):Boolean={
  if(!path.startsWith("/api/uploads/"))return false
  if(x.getRequestMethod!="POST"){json(x,405,Support.obj.put("error","请使用 POST 上传"));return true}
  checkOrigin(x);require(Option(x.getRequestHeaders.getFirst("Content-Type")).exists(_.split(';').head=="application/octet-stream"),"文件上传须采用 application/octet-stream")
  val field=path.stripPrefix("/api/uploads/");val name=Option(x.getRequestHeaders.getFirst("X-File-Name")).map(v=>java.net.URLDecoder.decode(v,"UTF-8")).getOrElse(throw new IllegalArgumentException("缺少文件名"))
  val result=try service.upload(field,name,x.getRequestBody)finally x.getRequestBody.close();json(x,201,result);true
 }
 private def builderAsset(x:HttpExchange,name:String):Unit={
  val in=getClass.getResourceAsStream("/appbuilder/"+name)
  if(in==null)json(x,404,Support.obj.put("error","未找到资源"))else{val bytes=try in.readAllBytes()finally in.close();send(x,200,bytes,mime(Path.of(name)))}
 }
 private def handleApplication(x:HttpExchange,service:conet.appbuilder.ApplicationService,path:String):Unit={
  if(!applications.visible(service.id) && (path=="/"||path=="/api/config"||path=="/api/jobs"&&x.getRequestMethod=="POST"||path=="/api/preview"||path.startsWith("/api/uploads/"))){json(x,410,Support.obj.put("error","应用已下线或删除"));return}
  if(uploadInput(x,service,path))return
  val publication="^/api/jobs/([a-zA-Z0-9-]{1,100})/publication$".r
  val plan="^/api/jobs/([a-zA-Z0-9-]{1,100})/plan$".r
  path match {
   case plan(id) if x.getRequestMethod=="GET"=>json(x,200,PlanNarrative(service.plan(id),settings,catalog));return
   case publication(id) if x.getRequestMethod=="GET"=>json(x,200,products.defaults(service,id));return
   case publication(id) if x.getRequestMethod=="POST"=>val body=Support.copy(requestBody(x,262144));body.put("publisher_name","CONET");storage.captureRun(service,id);val node=HostedNode.status(settings);require(node.path("running").asBoolean,"共享 DACP 节点未运行，无法发布数据集");val published=products.publish(service,id,body,node.path("url").asText);val productId=published.path("id").asText;try{storage.publishProduct(productId,products)}catch{case NonFatal(e)=>products.revoke(productId);throw e};json(x,201,published);return
   case _=>()
  }
  val method=x.getRequestMethod
  if(method=="GET")path match {
   case "/"=>builderAsset(x,"application.html")
   case "/api/config"=>json(x,200,service.config)
   case "/api/jobs"=>json(x,200,service.list)
   case p if p.startsWith("/api/jobs/")=>service.get(p.stripPrefix("/api/jobs/")) match{case Some(j)=>json(x,200,j);case None=>json(x,404,Support.obj.put("error","未找到任务"))}
   case p if p.startsWith("/results/")=>val parts=p.split("/");val artifact=if(parts.length==4)service.artifact(parts(2),parts(3))else None;artifact match{case Some(f)=>x.getResponseHeaders.set("Content-Disposition","attachment; filename=\""+parts(3)+"\"");file(x,f);case None=>json(x,404,Support.obj.put("error","未找到成果"))}
   case _=>json(x,404,Support.obj.put("error","未找到资源"))
  }else if(method=="POST"){
   checkOrigin(x)
   if(path=="/api/jobs"){submitted(x,service.id,applications.whileOnline(service.id){service.submit(requestBody(x),submitter(x))})}
   else if(path=="/api/preview")json(x,200,service.preview(requestBody(x)))
   else if(path.startsWith("/api/jobs/")&&path.endsWith("/cancel")){service.cancel(path.stripPrefix("/api/jobs/").stripSuffix("/cancel")) match{case Some(j)=>json(x,200,j);case None=>json(x,404,Support.obj.put("error","未找到任务"))}}
   else json(x,404,Support.obj.put("error","未找到资源"))
  }else json(x,405,Support.obj.put("error","不支持该方法"))
 }
 private val portalFiles=Map("/ratings.js"->"ratings.js","/ratings.css"->"ratings.css","/nodes"->"nodes.html","/nodes/"->"nodes.html","/services"->"services.html","/services/"->"services.html","/assets/background-coast.svg"->"assets/background-coast.svg","/assets/background-forest.svg"->"assets/background-forest.svg","/assets/background-indigo.svg"->"assets/background-indigo.svg","/assets/background-amber.svg"->"assets/background-amber.svg","/assets/background-rose.svg"->"assets/background-rose.svg","/catalog"->"catalog.html","/catalog/"->"catalog.html","/catalog.js"->"catalog.js","/catalog.css"->"catalog.css","/navigation.css"->"navigation.css","/"->"index.html","/screen"->"screen.html","/screen/"->"screen.html","/style.css"->"style.css","/app.js"->"app.js","/screen-panels.js"->"screen-panels.js","/screen.js"->"screen.js","/screen.css"->"screen.css","/job-plan"->"job-plan.html","/job-plan.js"->"job-plan.js","/job-plan.css"->"job-plan.css","/assets/tianditu.js"->"assets/tianditu.js","/assets/earth.jpg"->"assets/earth.jpg","/assets/conet-mark.svg"->"assets/conet-mark.svg","/assets/app-cover.svg"->"assets/app-cover.svg","/assets/check-dams-cover.svg"->"assets/check-dams-cover.svg","/assets/observatory-cover.svg"->"assets/observatory-cover.svg","/assets/emergency-cover.svg"->"assets/emergency-cover.svg")
 private def ratingBrowser(x:HttpExchange):String={
  val existing=Option(x.getRequestHeaders.getFirst("Cookie")).toVector.flatMap(_.split(";")).map(_.trim).find(_.startsWith("conet-rating=")).map(_.stripPrefix("conet-rating="))
  existing.filter(_.matches("[A-Za-z0-9-]{36}")).getOrElse{
   val id=java.util.UUID.randomUUID().toString
   x.getResponseHeaders.add("Set-Cookie","conet-rating="+id+"; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax"+(if(x.isInstanceOf[com.sun.net.httpserver.HttpsExchange])"; Secure"else""));id
  }
 }
 private def handlePortal(x:HttpExchange):Unit={
  val path=x.getRequestURI.getPath
  if(path.startsWith("/api/ratings/")&&x.getRequestMethod=="GET"){json(x,200,ratings.list(path.stripPrefix("/api/ratings/"),ratingBrowser(x)));return}
  if(path=="/api/ratings"&&x.getRequestMethod=="POST"){
   val n=requestBody(x);val kind=n.path("kind").asText;val id=n.path("id").asText
   require(n.path("score").isIntegralNumber&&n.path("score").canConvertToInt,"评分须为整数")
   require(kind=="app"&&applications.get(id).nonEmpty||kind=="dataset"&&catalog.list().path("datasets").elements.asScala.exists(_.path("id").asText==id),"评分对象不存在")
   json(x,200,ratings.rate(kind,id,ratingBrowser(x),n.path("score").asInt));return
  }
  if(path=="/workspace"||path=="/workspace/"){builderAsset(x,"workspace.html");return}
  if(path=="/api/catalog"&&x.getRequestMethod=="GET"){json(x,200,catalog.list());return}
  if(path.startsWith("/api/catalog/")&&x.getRequestMethod=="POST"){
   val b=requestBody(x);val name=b.path("dataset").asText;val item=b.path("path").asText("/")
   path match{case "/api/catalog/refresh"=>json(x,200,catalog.list(true));case "/api/catalog/items"=>json(x,200,catalog.items(name,item));case "/api/catalog/preview"=>json(x,200,catalog.preview(name,item));case "/api/catalog/presign"=>json(x,200,catalog.presign(name,item));case _=>json(x,404,Support.obj.put("error","未找到资源"))};return
  }
  if(path=="/appbuilder"||path=="/appbuilder/"){if(x.getRequestMethod=="GET")builderAsset(x,"builder.html")else json(x,405,Support.obj.put("error","不支持该方法"));return}
  if(path.startsWith("/appbuilder/assets/")){val name=path.stripPrefix("/appbuilder/assets/");if(x.getRequestMethod=="GET"&&Set("application.html","analysis-process.js","workspace.js","workspace.css","builder.js","page-studio.js","configuration-forms.js","presentation.js","product-publishing.js","product.js","product.html","product.css","visual-editor.js","visual-editor.css","runtime.js","application.js","application.css","appbuilder.css","map.html","map.js")(name))builderAsset(x,name)else json(x,404,Support.obj.put("error","未找到资源"));return}
  if(path.startsWith("/appbuilder/media/")&&x.getRequestMethod=="GET"){applications.media(path.stripPrefix("/appbuilder/media/")) match{case Some(p)=>file(x,p);case None=>json(x,404,Support.obj.put("error","未找到展示图片"))};return}
  if(path.startsWith("/api/storage/")){
   val action=path.stripPrefix("/api/storage/")
   if(action=="summary"&&x.getRequestMethod=="GET"){json(x,200,storage.summary);return}
   if(action=="results"&&x.getRequestMethod=="GET"){json(x,200,storage.resultDatasets);return}
   if(action.startsWith("result-files/")&&x.getRequestMethod=="GET"){val parts=action.stripPrefix("result-files/").split("/",-1);require(parts.length==2,"结果路径无效");val p=storage.resultFile(parts(0),parts(1));x.getResponseHeaders.set("Content-Disposition","attachment; filename=\"download\"");file(x,p);return}
   if(action=="datasets"&&x.getRequestMethod=="GET"){json(x,200,storage.datasets);return}
   if(x.getRequestMethod!="POST"){json(x,405,Support.obj.put("error","请使用 POST"));return}
   val b=requestBody(x,if(action=="upload")((storage.maxUploadBytes+2)/3*4+16384).toInt else 16384)
   action match {
    case "list"=>json(x,200,storage.list(b.path("path").asText))
    case "mkdir"=>storage.mkdir(b.path("path").asText);json(x,201,Support.obj.put("created",true))
    case "upload"=>json(x,201,storage.upload(b))
    case "download"=>val p=storage.download(b.path("path").asText);x.getResponseHeaders.set("Content-Disposition","attachment; filename=\"download\"");file(x,p)
    case "delete"=>storage.delete(b.path("path").asText);json(x,200,Support.obj.put("deleted",true))
    case "dataset"=>json(x,201,storage.saveDataset(b))
    case "publish"=>json(x,201,storage.publish(b))
    case "unpublish"=>val id=b.path("id").asText;storage.unpublish(id);if(id.startsWith("result-"))products.revoke(id.stripPrefix("result-"));json(x,200,Support.obj.put("published",false))
    case _=>json(x,404,Support.obj.put("error","未找到资源"))
   };return
  }
  if(path.startsWith("/api/appbuilder/")){


   if(path=="/api/appbuilder/packages/import"&&x.getRequestMethod=="POST"){
    checkOrigin(x);require(Option(x.getRequestHeaders.getFirst("Content-Type")).contains("application/octet-stream"),"请上传 ZIP 应用程序包")
    val bytes=try x.getRequestBody.readNBytes(4*1024*1024+1)finally x.getRequestBody.close();json(x,200,packages.load(bytes));return
   }
   if(path=="/api/appbuilder/packages/export"&&x.getRequestMethod=="POST"){
    val bytes=packages.export(requestBody(x,262144));x.getResponseHeaders.set("Content-Disposition","attachment; filename=application.conet.zip");send(x,200,bytes,"application/zip");return
   }
   if(path.startsWith("/api/appbuilder/packages/")&&x.getRequestMethod=="GET"){
    val id=path.stripPrefix("/api/appbuilder/packages/");val document=applications.document(id).getOrElse(throw new IllegalArgumentException("应用不存在"));val bytes=packages.export(document);x.getResponseHeaders.set("Content-Disposition","attachment; filename="+id+".conet.zip");send(x,200,bytes,"application/zip");return
   }
   if(path=="/api/appbuilder/settings"&&x.getRequestMethod=="GET"){
    val url=settings.config.path("appbuilder").path("designer_url").asText("");if(url.nonEmpty){val uri=URI.create(url);require(Set("http","https")(uri.getScheme)&&uri.getHost!=null&&uri.getUserInfo==null,"Job Designer 地址无效")};json(x,200,Support.obj.put("designer_url",url).put("page_generation_enabled",pageGenerator.enabled))
   }else if(path=="/api/appbuilder/statistics"&&x.getRequestMethod=="GET"){val stats=applications.statistics;stats.path("nodes").elements.asScala.foreach{row=>cached.path("overview").path("members").elements.asScala.find(_.path("identity").asText==row.path("id").asText).foreach{member=>settings.centers.find(_.path("id").asText==member.path("center_id").asText).foreach(center=>row.asInstanceOf[ObjectNode].put("name",center.path("title").asText))}};json(x,200,stats)}
   else if(path=="/api/appbuilder/apps"&&x.getRequestMethod=="GET")json(x,200,applications.list)
   else if(path=="/api/appbuilder/apps"&&x.getRequestMethod=="POST"){val body=requestBody(x,262144);val id=xstream.util.YamlDocument.parse(body.path("application_yaml").asText).path("id").asText;json(x,201,{val saved=applications.save(body);saved})}
   else if(path=="/api/appbuilder/media"&&x.getRequestMethod=="POST")json(x,201,applications.upload(requestBody(x,3000000)))
   else if(path=="/api/appbuilder/convert"&&x.getRequestMethod=="POST")json(x,200,applications.convert(requestBody(x,262144)))
   else if(path=="/api/appbuilder/pages/generate"&&x.getRequestMethod=="POST")json(x,200,pageGenerator.generate(requestBody(x,524288)))
   else if(path=="/api/appbuilder/validate"&&x.getRequestMethod=="POST")json(x,200,applications.validate(requestBody(x,262144)))
   else if(path.startsWith("/api/appbuilder/apps/")&&x.getRequestMethod=="POST"&&(path.endsWith("/status")||path.endsWith("/delete"))){
    val parts=path.stripPrefix("/api/appbuilder/apps/").split("/",-1);require(parts.length==2,"应用操作路径无效");val body=requestBody(x)
    json(x,200,applications.changeStatus(parts(0),if(parts(1)=="delete")"deleted"else body.path("status").asText,body.path("revision").asText))
   }
   else if(path.startsWith("/api/appbuilder/apps/")&&x.getRequestMethod=="GET")applications.document(path.stripPrefix("/api/appbuilder/apps/")) match{case Some(n)=>val doc=Support.copy(n).put("can_edit",true);json(x,200,doc);case None=>json(x,404,Support.obj.put("error","未找到应用"))}
   else json(x,404,Support.obj.put("error","未找到资源"));return
  }
  if(path=="/api/analyses"&&x.getRequestMethod=="GET"){json(x,200,applications.history(products.list));return}
  if(path=="/api/products"&&x.getRequestMethod=="GET"){json(x,200,products.list);return}
  if(path.startsWith("/products/")){
   x.getResponseHeaders.set("X-Robots-Tag","noindex")
   val parts=path.stripPrefix("/products/").split("/",-1);val id=parts(0);val suffix=parts.drop(1).mkString("/")
   if(suffix=="permissions"&&x.getRequestMethod=="GET"){json(x,200,Support.obj.put("manage",true));return}
   if(suffix=="revoke"&&x.getRequestMethod=="POST"){val body=requestBody(x);storage.withdrawProduct(id);if(products.revoke(id))json(x,200,Support.obj.put("revoked",true))else json(x,404,Support.obj.put("error","产品不存在"));return}
   if(x.getRequestMethod!="GET"){json(x,405,Support.obj.put("error","不支持该方法"));return}
   if(products.document(id).isEmpty){json(x,410,Support.obj.put("error","产品不存在、已过期或已撤回"));return}
   suffix match {
    case ""=>builderAsset(x,"product.html")
    case "api"=>json(x,200,products.document(id).get)
    case "data"=>products.data(id) match{case Some(p)=>file(x,p);case None=>json(x,410,Support.obj.put("error","产品已过期或已撤回"))}
    case s if s.startsWith("files/")=>products.download(id,s.stripPrefix("files/")) match{case Some(f)=>x.getResponseHeaders.set("Content-Disposition","attachment; filename=\""+f.getFileName+"\"");file(x,f);case None=>json(x,403,Support.obj.put("error","该文件不允许下载"))}
    case _=>json(x,404,Support.obj.put("error","未找到资源"))
   };return
  }
  if(path.startsWith("/apps/")){val parts=path.stripPrefix("/apps/").split("/",2);applications.get(parts(0)) match{case Some(service)=>if(parts.length==1){x.getResponseHeaders.set("Location",path+"/");x.sendResponseHeaders(302,-1);x.close()}else handleApplication(x,service,"/"+parts(1));case None=>json(x,404,Support.obj.put("error","未找到应用"))};return}

  if(x.getRequestMethod!="GET"){json(x,404,Support.obj.put("error","未找到资源"));return}
  path match {
   case "/api/config"=>val out=Support.obj.put("name",settings.config.path("name").asText);out.set[JsonNode]("centers",settings.config.path("centers"));if(settings.config.path("hosting").path("enabled").asBoolean){out.set[JsonNode]("shared_center",Support.obj.put("id","shared").put("title","共享数据中心").put("url",HostedNode.status(settings).path("url").asText).set[JsonNode]("location",Support.obj.put("longitude",108.94).put("latitude",34.34).put("label","共享服务（示意位置）")))};out.set[JsonNode]("cases",Support.arr(settings.cases.map(c=>{val n=Support.copy(c);n.remove("port");n.remove("directory");n})));out.set[JsonNode]("applications",Support.arr(applications.list.elements.asScala.filter(a=>a.path("status").asText=="online").toVector));json(x,200,out)
   case "/api/snapshot"=>json(x,200,cached)
   case p if p.startsWith("/api/union-jobs/") || Set("/union-assets/dag.js","/union-assets/g6.js")(p)=>
    val suffix=if(p=="/union-assets/dag.js")"/dag.js"else if(p=="/union-assets/g6.js")"/vendor/g6-5.1.1.min.js"else{val id=p.stripPrefix("/api/union-jobs/");require(id.matches("[a-zA-Z0-9-]{1,100}"),"作业编号无效");"/api/jobs/"+id}
    try{
     val base=Support.localHttp(settings.connection.path("web_urls").path("union").asText)
     val response=Support.http.send(HttpRequest.newBuilder(URI.create(base+suffix)).timeout(Duration.ofSeconds(10)).GET.build,HttpResponse.BodyHandlers.ofInputStream())
     val bytes=try response.body.readNBytes(16*1024*1024+1)finally response.body.close();require(bytes.length<=16*1024*1024,"资源超过大小上限")
     if(response.statusCode==200)send(x,200,bytes,response.headers.firstValue("Content-Type").orElse("application/json"))else json(x,response.statusCode,Support.obj.put("error","未找到作业物理计划"))
    }catch{case NonFatal(_)=>json(x,503,Support.obj.put("error","Union 服务暂不可用"))}
   case p if portalFiles.contains(p)=>file(x,settings.root.resolve("web").resolve(portalFiles(p)))
   case _=>json(x,404,Support.obj.put("error","未找到资源"))
  }
 }
 def snapshot():JsonNode={
  val result=Support.obj.put("at_ms",System.currentTimeMillis).put("synthetic",true).put("available",false)
  result.put("shared_dataset_count",storage.publishedDatasetCount)
  val volumes=Support.obj
  settings.centers.foreach{center=>val seen=scala.collection.mutable.Set.empty[Path];var bytes=0L;var complete=true
   center.path("datasets").elements.asScala.foreach{dataset=>val base=settings.caseState(dataset.path("case").asText).resolve("data").resolve(dataset.path("directory").asText(center.path("id").asText));if(!Files.isDirectory(base))complete=false else try{Support.walk(base).filter(Files.isRegularFile(_)).foreach{f=>val real=f.toRealPath();if(seen.add(real))bytes+=Files.size(f)}}catch{case NonFatal(_)=>complete=false}}
   val value=Support.obj.put("complete",complete);if(complete)value.put("data_bytes",bytes)else value.putNull("data_bytes");volumes.set[JsonNode](center.path("id").asText,value)
  };if(settings.config.path("hosting").path("enabled").asBoolean)volumes.set[JsonNode]("shared",Support.obj.put("complete",true).put("data_bytes",storage.totalBytes));result.set[JsonNode]("center_volumes",volumes)
  val manifests=settings.cases.flatMap(c=>try Some(Support.read(settings.caseState(c.path("id").asText).resolve("data/manifest.json")))catch{case NonFatal(_)=>None})
  result.put("data_bytes",manifests.map(_.path("total_data_bytes").asLong).sum).put("catalog_rows",manifests.map(_.path("total_rows").asLong).sum)
  val operators=scala.collection.mutable.Map.empty[String,Int]
  settings.cases.foreach(c=>xstream.util.YamlDocument.parse(Files.readString(settings.caseRoot(c.path("id").asText).resolve("job.yaml"))).path("steps").elements.asScala.foreach{step=>val key=step.path("operator").asText;operators(key)=operators.getOrElse(key,0)+1})
  result.set[JsonNode]("operators",Support.arr(operators.toVector.sorted.map{case(name,count)=>Support.obj.put("name",name).put("references",count)}))
  result.set[JsonNode]("cases",Support.arr(settings.cases.map(c=>{val out=Support.obj.put("id",c.path("id").asText).put("title",c.path("title").asText).put("available",true);out.set[JsonNode]("jobs",Support.arr(cases(c.path("id").asText).list.elements.asScala.map{j=>val n=Support.obj;Seq("id","app_id","state","created_at","started_at","finished_at","job_id").foreach(k=>if(j.has(k))n.set[JsonNode](k,j.get(k)));n}.toVector));out})))
  try{
   val connection=settings.connection;val base=Support.localHttp(connection.path("web_urls").path("union").asText)
   val reads=Seq("overview","topology").map(key=>new Callable[(String,JsonNode)]{def call()=key->Support.get(base+"/api/"+key,3)})
   observations.invokeAll(reads.asJava,4,TimeUnit.SECONDS).asScala.foreach{future=>try{val(key,rawValue)=future.get();val value=Support.copy(rawValue);if(key=="overview"){val jobs=Support.arr(value.path("jobs").elements.asScala.map{j=>val n=Support.obj;Seq("job_id","name","state","created_at_ms","finished_at_ms").foreach(k=>if(j.has(k))n.set[JsonNode](k,j.get(k)));n}.toVector);value.set[JsonNode]("jobs",jobs)};value.path("members").elements.asScala.foreach{m=>val center=connection.path("node_urls").fields.asScala.find(_.getValue.asText==m.path("url").asText).map(_.getKey);center.foreach(m.asInstanceOf[ObjectNode].put("center_id",_))};result.set[JsonNode](key,value)}catch{case NonFatal(_)=>()}}
   result.put("available",result.has("overview"))
  }catch{case NonFatal(_)=>()};result
 }
 private def startOne(host:String,port:Int,handler:HttpExchange=>Unit):Unit={val server=HttpServer.create(new InetSocketAddress(host,port),32);server.setExecutor(workers);server.createContext("/",(x:HttpExchange)=>try handler(x)catch{
  case e:SecurityException=>json(x,403,Support.obj.put("error",e.getMessage))
  case e:IllegalArgumentException=>json(x,400,Support.obj.put("error",Option(e.getMessage).getOrElse("请求格式无效")))
  case _:com.fasterxml.jackson.core.JsonProcessingException=>json(x,400,Support.obj.put("error","请求格式无效"))
  case NonFatal(error)=>System.err.println("CONET request failed: "+error.getClass.getSimpleName);error.printStackTrace(System.err);json(x,500,Support.obj.put("error","服务暂不可用"))
 });server.start();servers+=server}
 def start():Unit={
  startOne(settings.config.path("host").asText,settings.config.path("port").asInt,handlePortal)
  scheduler.scheduleWithFixedDelay(new Runnable{def run():Unit=try{cached=snapshot()}catch{case NonFatal(_)=>cached=Support.obj.put("available",false)}},0,1,TimeUnit.SECONDS)
  scheduler.scheduleWithFixedDelay(new Runnable{def run():Unit=try{products.cleanupExpired()}catch{case NonFatal(e)=>System.err.println("Product retention cleanup failed: "+e.getClass.getSimpleName)}},0,1,TimeUnit.HOURS)
  scheduler.scheduleWithFixedDelay(new Runnable{def run():Unit=applications.captureResults(storage)},5,15,TimeUnit.SECONDS)
  scheduler.scheduleWithFixedDelay(new Runnable{def run():Unit=try uploads.cleanup(applications.activeRunKeys)catch{case NonFatal(e)=>System.err.println("Input cleanup failed: "+e.getClass.getSimpleName)}},30,60,TimeUnit.SECONDS)
  println(s"CONET: http://${settings.publicHost}:${settings.config.path("port").asInt}/")
 }
 def close():Unit={servers.foreach(_.stop(0));scheduler.shutdownNow();observations.shutdownNow();applications.close();cases.values.foreach(_.close());settings.analysisScheduler.close();workers.shutdownNow()}
}
