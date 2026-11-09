package conet
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
class RatingsSpec extends AnyFunSuite {
 test("ratings aggregate, replace a browser vote and survive reload without changing definitions") {
  val dir=Files.createTempDirectory("conet-ratings-");val a="11111111-1111-1111-1111-111111111111";val b="22222222-2222-2222-2222-222222222222"
  val ratings=new Ratings(dir);assert(ratings.list("app",a).path("entries").isEmpty)
  assert(ratings.rate("app","demo",a,5).path("count").asInt==1)
  val two=ratings.rate("app","demo",b,3);assert(two.path("average").asDouble==4d);assert(two.path("count").asInt==2)
  val changed=ratings.rate("app","demo",a,1);assert(changed.path("count").asInt==2);assert(changed.path("average").asDouble==2d)
  ratings.rate("dataset","demo",a,4)
  val reload=new Ratings(dir);val entry=reload.list("app",a).path("entries").get(0);assert(entry.path("mine").asInt==1);assert(entry.path("average").asDouble==2d)
  assert(reload.list("dataset",a).path("entries").get(0).path("average").asDouble==4d)
  assert(!Files.readString(dir.resolve("ratings.json")).contains(a))
  Seq(0,6).foreach(score=>intercept[IllegalArgumentException](ratings.rate("app","demo",a,score)))
  intercept[IllegalArgumentException](ratings.rate("operator","demo",a,5));intercept[IllegalArgumentException](ratings.rate("app","",a,5))
 }
}
