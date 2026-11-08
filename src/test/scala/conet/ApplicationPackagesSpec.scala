package conet
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files,Path}
import java.io.ByteArrayOutputStream
import java.util.zip.{ZipEntry,ZipOutputStream}
class ApplicationPackagesSpec extends AnyFunSuite {
 test("application package round trips without publishing and rejects unsafe or oversized entries") {
  val root=Files.createTempDirectory("conet-packages");Support.writeText(root.resolve("conet.yaml"),"cases: []\ncenters: []\n")
  val apps=new Applications(new Settings(root),Map.empty);val packages=new ApplicationPackages(apps)
  try{
   val app=Files.readString(Path.of("examples/uploaded-json/application.yaml"));val job=Files.readString(Path.of("examples/uploaded-json/job.yaml"));val n=Support.obj.put("application_yaml",app).put("job_yaml",job)
   val loaded=packages.load(packages.export(n));assert(loaded.path("application").path("id").asText=="uploaded-json");assert(!loaded.path("loaded").asBoolean);assert(apps.list.isEmpty)
   val saved=apps.save(loaded);assert(saved.path("application").path("id").asText=="uploaded-json");assert(apps.get("uploaded-json").nonEmpty)
   def zip(name:String,data:Array[Byte]):Array[Byte]={val b=new ByteArrayOutputStream;val z=new ZipOutputStream(b);z.putNextEntry(new ZipEntry(name));z.write(data);z.closeEntry();z.close();b.toByteArray}
   intercept[IllegalArgumentException](packages.load(zip("../application.yaml",Array[Byte](1))))
   intercept[IllegalArgumentException](packages.load(zip("job.yaml",new Array[Byte](262145))))
   intercept[IllegalArgumentException](packages.load(zip("job.yaml",Array[Byte](1))))
  }finally apps.close()
 }
}
