package conet
import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
class WorkspacesSpec extends AnyFunSuite {
 test("workspaces persist organizational namespaces without accounts or credentials") {
  val path=Files.createTempDirectory("conet-workspaces").resolve("spaces.json");val w=new Workspaces(path)
  val a=w.create("研究组");w.record(a,"run:demo:one");assert(w.resources(a).size==1);assert(w.resources(w.default).isEmpty)
  val again=new Workspaces(path);assert(again.get(a.id)==a);assert(again.resourceOwners.contains("run:demo:one"->a.id))
  intercept[IllegalArgumentException](w.create(" "));intercept[IllegalArgumentException](w.get("../../outside"))
 }
}
