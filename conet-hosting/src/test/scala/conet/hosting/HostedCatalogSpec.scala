package conet.hosting

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import xstream.data._
import com.fasterxml.jackson.databind.ObjectMapper

class HostedCatalogSpec extends AnyFunSuite {
 test("ServiceLoader catalog exposes publications only, honors expiry/revocation and preserves directory views") {
  val mapper=new ObjectMapper;val root=Files.createTempDirectory("hosted-catalog");val directory=root.resolve("spaces/alice/published/test");Files.createDirectories(directory);Files.createDirectories(root.resolve("publications"));Files.writeString(directory.resolve("sample.txt"),"data")
  val plugins=new PluginEnvironment()
  val context=new CatalogContext {
   val batchSize=16;val inferSample=10;val byteChunk=1024;val countFileRecordsOnStartup=false;val sources=Map.empty[String,SourceDefinition];val configuredDatasets=Map.empty[String,DatasetDefinition]
   def plugins=HostedCatalogSpec.this.pluginsReference
   def createDataset(d:DatasetDefinition):PublishedDataset=new xstream.data.files.DirectoryDatasetProvider().create(d,this)
  }
  pluginsReference=plugins
  val catalog=plugins.byId(classOf[DataCatalogProvider],"conet-hosted").create(Map("root"->root.toString),context)
  val descriptor=root.resolve("publications/public.json");val n=mapper.createObjectNode().put("published",false).put("directory","spaces/alice/published/test").put("description","{\"title\":\"样例\",\"description\":\"说明\"}")
  try{
   Files.writeString(root.resolve("private.txt"),"secret");Files.writeString(descriptor,n.toString);assert(catalog.list().isEmpty)
   n.put("published",true);Files.writeString(descriptor,n.toString);assert(catalog.list().map(_.name)==Seq("public"))
   val dataset=catalog.open("public");val access=dataset.open(()=>());assert(access.list().exists(_.path=="/sample.txt"))
   n.put("published",false);Files.writeString(descriptor,n.toString);assert(catalog.list().isEmpty);intercept[java.io.FileNotFoundException](catalog.open("public"));intercept[java.io.FileNotFoundException](access.list())
   access.close();dataset.close()
   val temporary=root.resolve("execution-inputs/input-"+("a"*32)+".json");Files.createDirectories(temporary.getParent)
   val input=n.deepCopy[com.fasterxml.jackson.databind.node.ObjectNode]().put("published",true);Files.writeString(temporary,input.toString)
   assert(catalog.list().isEmpty);val hidden=catalog.open("input-"+("a"*32));val inputAccess=hidden.open(()=>());assert(inputAccess.list().exists(_.path=="/sample.txt"))
   input.put("expires_at","2000-01-01T00:00:00Z");Files.writeString(temporary,input.toString);intercept[java.io.FileNotFoundException](catalog.open("input-"+("a"*32)));intercept[java.io.FileNotFoundException](inputAccess.list());inputAccess.close();hidden.close()
   n.put("published",true).put("expires_at","2000-01-01T00:00:00Z");Files.writeString(descriptor,n.toString);assert(catalog.list().isEmpty)
   n.remove("expires_at");n.put("directory","../escape");Files.writeString(descriptor,n.toString);intercept[IllegalArgumentException](catalog.list())
   n.put("directory","link");Files.createSymbolicLink(root.resolve("link"),directory);Files.writeString(descriptor,n.toString);intercept[IllegalArgumentException](catalog.open("public"))
  }finally{catalog.close();plugins.close()}
 }
 private var pluginsReference:PluginEnvironment=null
}
