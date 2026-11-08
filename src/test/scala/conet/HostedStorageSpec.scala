package conet

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import com.fasterxml.jackson.databind.JsonNode
import scala.jdk.CollectionConverters._

class HostedStorageSpec extends AnyFunSuite {
 test("shared storage validates paths, publishes immutable snapshots and enforces quotas") {
  val root=Files.createTempDirectory("conet-storage");Support.writeText(root.resolve("conet.yaml"),"cases: []\ncenters: []\nhosting: {quota_bytes: 8192, max_upload_bytes: 8192}\n")
  val s=new HostedStorage(new Settings(root))
  def upload(path:String,value:String)={val n=Support.obj.put("path",path).put("content_base64",java.util.Base64.getEncoder.encodeToString(value.getBytes));s.upload(n)}
  upload("science/data.txt","original");assert(s.list("science").size==1)
  intercept[IllegalArgumentException](upload("../../bob.txt","bad"));intercept[IllegalArgumentException](upload("/etc/passwd","bad"))
  val bad=root.resolve(".state/hosting/storage").resolve("files/link");Files.createSymbolicLink(bad,root);intercept[IllegalArgumentException](s.list("link"))
  val d=Support.obj.put("name","science").put("path","science").put("title","科学数据").put("description","我的数据")
  val draft=s.saveDataset(d);assert(!draft.path("published").asBoolean);assert(s.datasets.size==1)
  assert(Support.children(s.root.resolve("publications")).isEmpty)
  intercept[IllegalArgumentException](s.publish(d));d.put("confirmed_public",true);val published=s.publish(d);val id=published.path("id").asText
  assert(s.datasets.get(0).path("published").asBoolean)
  upload("science/data.txt","changed");val snapshot=s.root.resolve(published.path("directory").asText);assert(Files.readString(snapshot.resolve("data.txt"))=="original")
  s.unpublish(id);assert(!s.datasets.get(0).path("published").asBoolean)
  val again=s.publish(d);assert(Files.readString(s.root.resolve(again.path("directory").asText).resolve("data.txt"))=="changed")
  intercept[IllegalArgumentException](upload("large.txt","x"*8190));s.delete("science/data.txt");assert(Files.exists(snapshot.resolve("data.txt")))
  intercept[IllegalArgumentException](s.delete(""));assert(s.summary.path("quota_bytes").asLong==8192L)
 }
}
