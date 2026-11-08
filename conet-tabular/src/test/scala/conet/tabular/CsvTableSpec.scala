package conet.tabular
import org.scalatest.funsuite.AnyFunSuite
class CsvTableSpec extends AnyFunSuite {
 test("CSV presentation preserves quoted cells, multiline text, missing values and bounded numeric statistics") {
  val d=new CsvTableDecoder;val n=d.decode("\ufeffname,value,note\n黄河,42,\"line 1\nline 2\"\n星表,,\"a,b\"\n".getBytes("UTF-8"))
  assert(n.path("row_count").asInt==2);assert(n.path("column_count").asInt==3)
  assert(n.path("rows").get(0).path("note").asText=="line 1\nline 2");assert(n.path("column_statistics").get(1).path("empty_count").asInt==1);assert(n.path("column_statistics").get(1).path("min").asInt==42)
  intercept[IllegalArgumentException](d.decode("a,b\n1,2,3\n".getBytes("UTF-8")))
  intercept[IllegalArgumentException](d.decode("a,a\n1,2\n".getBytes("UTF-8")))
 }
}
