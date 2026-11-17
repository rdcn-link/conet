package conet.appbuilder

import org.scalatest.funsuite.AnyFunSuite
import xstream.util.Json

class UnionFailureSpec extends AnyFunSuite {
 test("public failure diagnostics include phase, step, operator and correlation IDs") {
  val status=Json.mapper.readTree("""{"failure":{"phase":"preparation","message":"Operator is unavailable","step_id":"terrain","operator":"emergency_terrain@1.0","node":"node-b","request_id":"r1","execution_id":"e1"}}""")
  val reason=UnionBackend.failureReason(status).get
  assert(reason.contains("环境准备失败") && reason.contains("步骤：terrain"))
  assert(reason.contains("emergency_terrain@1.0") && reason.contains("节点：node-b"))
  assert(reason.contains("r1") && reason.contains("e1"))
  assert(UnionBackend.failureReason(Json.mapper.readTree("{}" )).isEmpty)
 }
}
