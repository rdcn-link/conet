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
 test("retained private snapshots survive staging cleanup and rerun verifies original contents") {
  val root=Files.createTempDirectory("conet-retained-inputs")
  Support.writeText(root.resolve("conet.yaml"),"cases: []\ncenters: []\nhosting: {max_upload_bytes: 1024}\n")
  Support.write(root.resolve(".state/hosted-node/process.json"),Support.obj.put("pid",ProcessHandle.current.pid).put("url","dacp://127.0.0.1:1234"))
  val uploads=new RunUploads(new Settings(root));val d=definition;val data=Files.readAllBytes(Path.of("examples/uploaded-json/sample.json"))
  val m=uploads.upload(d,"source","sample.json",new ByteArrayInputStream(data))
  val payload=Support.obj.set[JsonNode]("source",Support.obj.put("upload_id",m.path("upload_id").asText))
  val run="c"*32;val record=uploads.resolve(d,payload,Some(run)).put("id",run).put("state","succeeded")
  uploads.finished(d.id,run)
  assert(uploads.status(d,record).path("available").asBoolean)
  assert(uploads.retain(d,record,true).path("retained").asBoolean)
  val descriptor=root.resolve(".state/hosting/execution-inputs/input-"+run+".json")
  val staged=root.resolve(".state/hosting/inputs/staged/"+m.path("upload_id").asText+"/upload.json")
  Support.atomic(staged,Support.copy(Support.read(staged)).put("expires_at","2000-01-01T00:00:00Z"))
  uploads.cleanup(Set.empty);assert(!Files.exists(staged));assert(Files.exists(descriptor))
  val replay=uploads.replay(d,record,payload,"d"*32)
  assert(replay.path("source").path("uri").asText!=record.path("source").path("uri").asText)
  assert(replay.path("source").path("sha256")==record.path("source").path("sha256"))
  assert(!Support.read(descriptor).has("expires_at"))
  val released=uploads.retain(d,record,false);assert(!released.path("retained").asBoolean);assert(released.has("expires_at"))
  val file=root.resolve(".state/hosting/inputs/runs/uploaded-json-"+run+"/source/sample.json")
  Files.write(file,Array.fill[Byte](data.length)(42))
  assert(intercept[IllegalArgumentException](uploads.replay(d,record,payload,"e"*32)).getMessage.contains("内容已改变"))
  Files.delete(file);Files.createSymbolicLink(file,Path.of("/etc/passwd"))
  assert(!uploads.status(d,record).path("available").asBoolean)
  intercept[IllegalArgumentException](uploads.replay(d,record,payload,"f"*32))
  Files.delete(file);Files.write(file,data)
  Support.atomic(descriptor,Support.copy(Support.read(descriptor)).put("expires_at","2000-01-01T00:00:00Z"))
  assert(!uploads.status(d,record).path("available").asBoolean)
  intercept[IllegalArgumentException](uploads.replay(d,record,payload,"e"*32))
  uploads.cleanup(Set.empty);assert(!Files.exists(descriptor))
 }
 test("run snapshot creation respects total storage quota without leaving partial inputs") {
  val root=Files.createTempDirectory("conet-input-quota");val data=Files.readAllBytes(Path.of("examples/uploaded-json/sample.json"))
  Support.writeText(root.resolve("conet.yaml"),s"cases: []\ncenters: []\nhosting: {max_upload_bytes: 1024}\nappbuilder: {uploads: {quota_bytes: ${data.length+1}}}\n")
  Support.write(root.resolve(".state/hosted-node/process.json"),Support.obj.put("pid",ProcessHandle.current.pid).put("url","dacp://127.0.0.1:1234"))
  val uploads=new RunUploads(new Settings(root));val d=definition;val m=uploads.upload(d,"source","sample.json",new ByteArrayInputStream(data))
  val payload=Support.obj.set[JsonNode]("source",Support.obj.put("upload_id",m.path("upload_id").asText))
  assert(intercept[IllegalArgumentException](uploads.resolve(d,payload,Some("a"*32))).getMessage.contains("空间不足"))
  assert(!Files.exists(root.resolve(".state/hosting/execution-inputs/input-"+("a"*32)+".json")))
 }
 test("file contracts reject raw paths, malformed limits and mismatched read views") {
  val d=definition;intercept[IllegalArgumentException](d.validate(Support.obj.put("source","/tmp/file"),Support.obj))
  val yaml=xstream.util.YamlDocument.dump(d.config)
  intercept[IllegalArgumentException](Definition.parse(yaml.replace("max_bytes: 16777216","max_bytes: 0"),d.jobYaml))
  intercept[IllegalArgumentException](Definition.parse(yaml,d.jobYaml.replace("view: bytes","view: records")))
 }
}
