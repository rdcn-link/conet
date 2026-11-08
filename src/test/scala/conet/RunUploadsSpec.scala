package conet
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import java.io.ByteArrayInputStream
import conet.appbuilder.{Definition,ApplicationService,JobBackend,JobControl}
import com.fasterxml.jackson.databind.JsonNode
class RunUploadsSpec extends AnyFunSuite {
 private def definition=Definition.load(Path.of("examples/uploaded-json"))
 test("streamed uploads enforce bounds, application binding, immutable per-run inputs and retention") {
  val root=Files.createTempDirectory("conet-run-inputs");Support.writeText(root.resolve("conet.yaml"),"cases: []\ncenters: []\nhosting: {max_upload_bytes: 1024}\n")
  Support.write(root.resolve(".state/hosted-node/process.json"),Support.obj.put("pid",ProcessHandle.current.pid).put("url","dacp://127.0.0.1:1234"))
  val uploads=new RunUploads(new Settings(root));val d=definition;val data=Files.readAllBytes(Path.of("examples/uploaded-json/sample.json"))
  val m=uploads.upload(d,"source","sample.json",new ByteArrayInputStream(data));assert(m.path("bytes").asInt==data.length);assert(m.path("sha256").asText.length==64)
  def payload(id:String)=Support.obj.set[JsonNode]("source",Support.obj.put("upload_id",id))
  intercept[IllegalArgumentException](d.validate(Support.obj.set[JsonNode]("source",Support.obj.put("upload_id",m.path("upload_id").asText).put("uri","file:///etc/passwd")),Support.obj))
  Seq("file:///etc/passwd","dacp://host/ds/../secret","dacp://host/ds/item?token=secret","dacp://user@host/ds/item","dacp://host/ds").foreach{uri=>intercept[IllegalArgumentException](d.validate(Support.obj.set[JsonNode]("source",Support.obj.put("uri",uri)),Support.obj))}
  val remote=d.validate(Support.obj.set[JsonNode]("source",Support.obj.put("uri","dacp://localhost:19860/ds/item.json")),Support.obj)
  assert(remote.path("source").path("uri").asText.endsWith("/ds/item.json"))
  val run="a"*32;val prepared=uploads.resolve(d,d.validate(payload(m.path("upload_id").asText),Support.obj),Some(run))
  val uri=prepared.path("source").path("uri").asText;assert(uri.startsWith("dacp://127.0.0.1:1234/input-"+run+"/"));assert(d.variables(prepared,Support.obj,Map.empty).path("INPUT_URI").asText==uri)
  assert(Files.readAllBytes(root.resolve(".state/hosting/inputs/runs/uploaded-json-"+run+"/source/sample.json")).sameElements(data))
  val descriptor=root.resolve(".state/hosting/execution-inputs/input-"+run+".json");assert(!Support.read(descriptor).has("expires_at"));uploads.cleanup(Set(d.id->run));assert(!Support.read(descriptor).has("expires_at"))
  uploads.finished(d.id,run);assert(Support.read(descriptor).has("expires_at"));assert(Support.children(root.resolve(".state/hosting/publications")).isEmpty)
  intercept[IllegalArgumentException](uploads.upload(d,"source","../sample.json",new ByteArrayInputStream(data)))
  intercept[IllegalArgumentException](uploads.upload(d,"source","sample.exe",new ByteArrayInputStream(data)))
  intercept[IllegalArgumentException](uploads.upload(d,"source","sample.json",new ByteArrayInputStream(new Array[Byte](1025))))
  intercept[IllegalArgumentException](uploads.upload(d,"source","sample.json",new ByteArrayInputStream(Array.emptyByteArray)))
  val other=Definition.parse(xstream.util.YamlDocument.dump(d.config).replace("id: uploaded-json","id: other-app"),d.jobYaml)
  intercept[IllegalArgumentException](uploads.resolve(other,payload(m.path("upload_id").asText),Some("b"*32)))
  val old=Support.copy(Support.read(descriptor)).put("expires_at","2000-01-01T00:00:00Z");Support.atomic(descriptor,old);uploads.cleanup(Set.empty);assert(!Files.exists(descriptor));assert(!Files.exists(root.resolve(".state/hosting/inputs/runs/uploaded-json-"+run)))
 }
 test("file contracts reject raw paths, malformed limits and mismatched read views") {
  val d=definition;intercept[IllegalArgumentException](d.validate(Support.obj.put("source","/tmp/file"),Support.obj))
  val yaml=xstream.util.YamlDocument.dump(d.config)
  intercept[IllegalArgumentException](Definition.parse(yaml.replace("max_bytes: 16777216","max_bytes: 0"),d.jobYaml))
  intercept[IllegalArgumentException](Definition.parse(yaml,d.jobYaml.replace("view: bytes","view: records")))
 }
}
