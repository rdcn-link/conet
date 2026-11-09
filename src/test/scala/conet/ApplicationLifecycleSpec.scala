package conet

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import com.fasterxml.jackson.databind.JsonNode
import xstream.util.YamlDocument

class ApplicationLifecycleSpec extends AnyFunSuite {
 test("publication status and deletion persist without discarding definitions or analysis history") {
  val root=Files.createTempDirectory("conet-lifecycle-")
  Support.writeText(root.resolve("conet.yaml"),"cases: []\ncenters: []\n")
  val settings=new Settings(root)
  val app="""version: 1
id: lifecycle-test
title: Test
description: Lifecycle test
job: job.yaml
inputs: []
variables: {}
result: {decoder: json, views: [], artifacts: []}
"""
  val job="""name: Test
steps:
- id: read
  operator: read_item@1.0
  inputs: {}
  params: {uri: 'context:/test/input', view: bytes}
outputs: {result: read}
"""
  var apps=new Applications(settings,Map.empty)
  try {
   val saved=apps.save(Support.obj.put("application_yaml",app).put("job_yaml",job))
   val revision=saved.path("revision").asText
   assert(saved.path("status").asText=="online")
   intercept[IllegalArgumentException](apps.changeStatus("lifecycle-test","offline","stale"))
   intercept[IllegalArgumentException](apps.changeStatus("lifecycle-test","unknown",revision))
   Support.write(root.resolve(".state/appbuilder/runs/lifecycle-test/web-jobs/old.json"),Support.obj.put("id","old").put("state","succeeded"))
   apps.changeStatus("lifecycle-test","offline",revision)
   assert(!apps.visible("lifecycle-test"))
   intercept[IllegalArgumentException](apps.whileOnline("lifecycle-test"){throw new AssertionError("must not execute")})
   assert(apps.list.path(0).path("status").asText=="offline")
   apps.close();apps=new Applications(settings,Map.empty)
   assert(apps.document("lifecycle-test").get.path("status").asText=="offline")
   assert(apps.get("lifecycle-test").get.list.size==1)
   apps.changeStatus("lifecycle-test","online",revision)
   assert(apps.visible("lifecycle-test"))
   apps.changeStatus("lifecycle-test","deleted",revision)
   assert(apps.list.isEmpty&&apps.document("lifecycle-test").isEmpty)
   assert(Files.isRegularFile(root.resolve(".state/appbuilder/definitions/lifecycle-test/application.yaml")))
   assert(apps.get("lifecycle-test").get.list.size==1)
   apps.close();apps=new Applications(settings,Map.empty)
   assert(!apps.visible("lifecycle-test")&&apps.list.isEmpty)
   intercept[IllegalArgumentException](apps.changeStatus("lifecycle-test","online",revision))
   intercept[IllegalArgumentException](apps.save(Support.obj.put("application_yaml",app).put("job_yaml",job)))
  } finally apps.close()
 }
}
