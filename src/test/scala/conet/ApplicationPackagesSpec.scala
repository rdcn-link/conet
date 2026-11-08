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
   def image(color:Int):String={val picture=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);picture.setRGB(0,0,color);val bytes=new ByteArrayOutputStream;javax.imageio.ImageIO.write(picture,"png",bytes);apps.upload(Support.obj.put("content_base64",java.util.Base64.getEncoder.encodeToString(bytes.toByteArray))).path("url").asText}
   val cover=image(0xff0000);val background=image(0x00ff00)
   val styled=Support.copy(xstream.util.YamlDocument.parse(app));val appearance=styled.withObject("/presentation");appearance.put("cover",cover).put("background",background)
   val packaged=packages.load(packages.export(Support.obj.put("application_yaml",xstream.util.YamlDocument.dump(styled)).put("job_yaml",job)))
   assert(packaged.path("application").path("presentation").path("cover").asText==cover)
   assert(packaged.path("application").path("presentation").path("background").asText==background)
   val saved=apps.save(loaded);assert(saved.path("application").path("id").asText=="uploaded-json");assert(apps.get("uploaded-json").nonEmpty)
   def zip(name:String,data:Array[Byte]):Array[Byte]={val b=new ByteArrayOutputStream;val z=new ZipOutputStream(b);z.putNextEntry(new ZipEntry(name));z.write(data);z.closeEntry();z.close();b.toByteArray}
   intercept[IllegalArgumentException](packages.load(zip("../application.yaml",Array[Byte](1))))
   intercept[IllegalArgumentException](packages.load(zip("job.yaml",new Array[Byte](262145))))
   intercept[IllegalArgumentException](packages.load(zip("job.yaml",Array[Byte](1))))
  }finally apps.close()
 }
}
