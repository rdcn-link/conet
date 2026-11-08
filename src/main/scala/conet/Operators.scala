package conet

import java.nio.file.{Files,Path,StandardCopyOption}
import java.util.zip.{ZipEntry,ZipOutputStream}
import scala.util.Using
import scala.jdk.CollectionConverters._
import xstream.util.YamlDocument

object Operators {
 def build(settings:Settings):Path={
  val library=settings.network.resolve("operators");Files.createDirectories(library)
  settings.cases.foreach{c=>
   val id=c.path("id").asText;val home=settings.caseRoot(id)
   val assets=if(Files.exists(home.resolve("packaging.yaml")))YamlDocument.parse(Files.readString(home.resolve("packaging.yaml"))).path("assets")else Support.obj
   Support.children(home.resolve("manifests")).filter(_.toString.endsWith(".yaml")).foreach{file=>
    val manifest=YamlDocument.parse(Files.readString(file));val name=manifest.path("name").asText;val version=manifest.path("version").asText
    require(name.matches("[a-z][a-z0-9_]*")&&version.matches("[0-9][a-zA-Z0-9.\\-]*"),"Invalid operator identifier")
    val folder=settings.caseState(id).resolve("operators").resolve(name).resolve(version);Files.createDirectories(folder)
    val archive=Files.createTempFile(folder,"analysis-",".zip")
    try Using.resource(new ZipOutputStream(Files.newOutputStream(archive))){zip=>
     def add(source:Path,entry:String):Unit={zip.putNextEntry(new ZipEntry(entry));Files.copy(source,zip);zip.closeEntry()}
     Support.children(home.resolve("operator")).filter(_.toString.endsWith(".py")).sortBy(_.toString).foreach(p=>add(p,p.getFileName.toString))
     assets.path(name).fields.asScala.foreach{entry=>
      val base=settings.caseState(id).toRealPath();val source=base.resolve(entry.getValue.asText).normalize
      require(source.startsWith(base)&&Files.isRegularFile(source),"Package asset must be inside case state")
      require(entry.getKey.matches("[A-Za-z0-9][A-Za-z0-9._-]*"),"Invalid package asset name")
      add(source,entry.getKey)
     }
    }
    catch {case error:Throwable=>Files.deleteIfExists(archive);throw error}
    Files.move(archive,folder.resolve("analysis.zip"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
    Files.copy(file,folder.resolve("operator.yaml"),StandardCopyOption.REPLACE_EXISTING)
    val link=library.resolve(name);if(!Files.exists(link))Files.createSymbolicLink(link,folder.getParent)
   }
  };library
 }
}
