package conet.hosting

import xstream.data._
import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.{Files,Path,LinkOption}
import java.time.Instant
import scala.jdk.CollectionConverters._

/** Live, publication-only discovery. Private user spaces are never directory roots of this catalog. */
class HostedCatalogProvider extends DataCatalogProvider {
 val id="conet-hosted"
 def create(settings:Map[String,Object],context:CatalogContext):DataCatalog={
  require(settings.keySet==Set("root"),"conet-hosted requires source.root")
  val root=Path.of(settings("root").toString).toRealPath();val index=root.resolve("publications")
  def entry(path:Path):JsonNode={require(!Files.isSymbolicLink(path),"publication descriptor cannot be a symlink");new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(path))}
  def active(n:JsonNode):Boolean=n.path("published").asBoolean&&(!n.has("expires_at")||Instant.now.isBefore(Instant.parse(n.path("expires_at").asText)))
  def publication(name:String):JsonNode={require(name.matches("[a-zA-Z0-9_-]{1,100}"),"invalid hosted dataset name");val publicPath=index.resolve(name+".json");val p=if(name.matches("input-[a-f0-9]{32}"))root.resolve("execution-inputs").resolve(name+".json")else publicPath;if(!Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS))throw new java.io.FileNotFoundException("dataset unavailable");val n=entry(p);if(!active(n))throw new java.io.FileNotFoundException("dataset unavailable");n}
  def publicationDirectory(n:JsonNode):Path={val relative=n.path("directory").asText;val p=root.resolve(relative).normalize;require(relative.nonEmpty&& !Path.of(relative).isAbsolute&&p.startsWith(root),"invalid publication directory");var current=root;root.relativize(p).iterator.asScala.foreach{part=>current=current.resolve(part);require(!Files.isSymbolicLink(current),"publication path cannot contain symlinks")};require(Files.isDirectory(p,LinkOption.NOFOLLOW_LINKS)&&p.toRealPath().startsWith(root),"publication directory unavailable");p}
  new DataCatalog {
   def list():Seq[DatasetInfo]=list(()=>())
   override def list(check:()=>Unit):Seq[DatasetInfo]={check();if(!Files.isDirectory(index))return Seq.empty;val in=Files.list(index);try in.iterator.asScala.filter(p=>p.getFileName.toString.matches("[a-zA-Z0-9_-]{1,100}\\.json")&&Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)).flatMap{p=>check();val n=entry(p);if(active(n)){publicationDirectory(n);Some(DatasetInfo(p.getFileName.toString.stripSuffix(".json"),n.path("description").asText))}else None}.toVector.sortBy(_.name)finally in.close()}
   def open(name:String):PublishedDataset={val n=publication(name);val definition=DatasetDefinition("directory",Map("root"->publicationDirectory(n).toString),description=n.path("description").asText);val delegate=context.createDataset(definition)
    new PublishedDataset {
     def open(check:()=>Unit):DatasetAccess={publication(name);val access=delegate.open(()=>{check();val current=publication(name);if(current.path("directory")!=n.path("directory"))throw new java.io.FileNotFoundException("publication changed");()});access}
     override def close():Unit=delegate.close()
    }
   }
  }
 }
}
