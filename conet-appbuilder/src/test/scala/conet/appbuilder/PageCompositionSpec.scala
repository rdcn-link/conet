package conet.appbuilder

import org.scalatest.funsuite.AnyFunSuite
import com.fasterxml.jackson.databind.node.ObjectNode
import xstream.util.Json

class PageCompositionSpec extends AnyFunSuite {
 private def page=Json.mapper.readTree("""{"composition":{"type":"grid","columns":12,"children":[{"type":"slot","name":"results","span":8},{"type":"panel","span":4,"children":[{"type":"slot","name":"intro"},{"type":"slot","name":"inputs","columns":2},{"type":"slot","name":"progress"}]}]},"max_width":1520,"palette":{"background":"#101c30","text":"#f0f7ff"}}""").asInstanceOf[ObjectNode]
 test("content composition permits free ordering, nested proportions and independent colors"){
  PageComposition.validate(page)
 }
 test("every functional component is required exactly once"){
  val missing=page;missing.path("composition").path("children").asInstanceOf[com.fasterxml.jackson.databind.node.ArrayNode].remove(0)
  intercept[IllegalArgumentException](PageComposition.validate(missing))
  val duplicate=page;duplicate.path("composition").path("children").path(0).asInstanceOf[ObjectNode].put("name","intro")
  intercept[IllegalArgumentException](PageComposition.validate(duplicate))
 }
 test("layout bounds prevent overflow and unbounded nesting"){
  val overflow=page;overflow.path("composition").path("children").path(0).asInstanceOf[ObjectNode].put("span",13)
  intercept[IllegalArgumentException](PageComposition.validate(overflow))
  val deep=page;var node=deep.path("composition").asInstanceOf[ObjectNode]
  for(_<-0 to 7){val child=Json.mapper.createObjectNode().put("type","stack");node.set[com.fasterxml.jackson.databind.JsonNode]("children",Json.mapper.createArrayNode().add(child));node=child}
  node.set[com.fasterxml.jackson.databind.JsonNode]("children",Json.mapper.createArrayNode().add(Json.mapper.createObjectNode().put("type","text").put("text","deep")))
  intercept[IllegalArgumentException](PageComposition.validate(deep))
 }
 test("presentation cannot introduce executable content or arbitrary resources"){
  for((key,value)<-Seq("script"->"alert(1)","background"->"url(https://outside.invalid)")){
   val p=page;p.path("composition").asInstanceOf[ObjectNode].put(key,value)
   intercept[IllegalArgumentException](PageComposition.validate(p))
  }
  val image=Json.mapper.createObjectNode().put("type","image").put("src","https://outside.invalid/image.png").put("alt","外部图片")
  val p=page;p.path("composition").path("children").asInstanceOf[com.fasterxml.jackson.databind.node.ArrayNode].add(image)
  intercept[IllegalArgumentException](PageComposition.validate(p))
  val mixed=page.put("layout","split")
  intercept[IllegalArgumentException](PageComposition.validate(mixed))
 }
}
