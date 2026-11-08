package conet

import com.fasterxml.jackson.databind.JsonNode
import conet.appbuilder.{ApplicationService,Definition,UnionBackend}

/** The portal supplies deployment paths; analysis behavior is configured by application.yaml. */
final class CaseService(settings:Settings,val id:String,inputFiles:conet.appbuilder.InputFiles=conet.appbuilder.InputFiles.unavailable) extends AutoCloseable {
 val state=settings.caseState(id);val home=settings.caseRoot(id)
 private def create(d:Definition)=new ApplicationService(d,state,()=>Support.read(settings.caseState(d.config.path("data").path("scope").asText(id)).resolve("data/manifest.json")),new UnionBackend(()=>settings.connection),s"/apps/$id",inputFiles)
 private val overrideHome=settings.state.resolve("appbuilder/definitions").resolve(id)
 @volatile var application=create(Definition.load(if(java.nio.file.Files.isRegularFile(overrideHome.resolve("application.yaml")))overrideHome else home))
 def reload(d:Definition):Unit=synchronized{application.close();application=create(d)}
 def manifest:JsonNode=Support.read(state.resolve("data/manifest.json"))
 def config:JsonNode=application.config
 def list:JsonNode=application.list
 def get(identifier:String):Option[JsonNode]=application.get(identifier)
 def submit(payload:JsonNode,submittedBy:Option[JsonNode]=None):(Int,JsonNode)=application.submit(payload,submittedBy)
 def close():Unit=application.close()
}
