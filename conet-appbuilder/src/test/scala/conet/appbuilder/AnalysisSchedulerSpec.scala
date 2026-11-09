package conet.appbuilder

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch,TimeUnit}

class AnalysisSchedulerSpec extends AnyFunSuite {
 private def await(l:CountDownLatch):Unit=assert(l.await(5,TimeUnit.SECONDS))
 test("global capacity, per-app serialization, cancellation and failure release slots") {
  val scheduler=new AnalysisScheduler(2,1,0,_=>Long.MaxValue)
  val path=Files.createTempDirectory("admission")
  val first=new CountDownLatch(1);val other=new CountDownLatch(1);val release=new CountDownLatch(1);val resumed=new CountDownLatch(1)
  try {
   assert(scheduler.submit("a","1",path){()=>{first.countDown();await(release)}}.isEmpty);await(first)
   assert(scheduler.submit("a","2",path){()=>resumed.countDown()}.isEmpty)
   assert(scheduler.submit("b","1",path){()=>{other.countDown();await(release)}}.isEmpty);await(other)
   assert(scheduler.counts==(2,1))
   var prepared=false
   assert(scheduler.submit("c","1",path){prepared=true;()=>()}.exists(_._1==429));assert(!prepared)
   assert(scheduler.remove("a","2").isDefined);assert(scheduler.counts==(2,0))
   assert(scheduler.submit("c","2",path){()=>resumed.countDown()}.isEmpty)
   release.countDown();await(resumed)
  }finally{release.countDown();scheduler.close()}
 }
 test("disk admission fails closed and rejected jobs are not persisted") {
  val path=Files.createTempDirectory("disk-admission")
  val scheduler=new AnalysisScheduler(1,0,100,_=>99)
  try {
   var prepared=false
   assert(scheduler.submit("a","1",path){prepared=true;()=>()}.exists(_._1==507));assert(!prepared)
   intercept[IllegalArgumentException](scheduler.checkDisk(path))
  }finally scheduler.close()
 }
 test("preparation failure does not consume capacity; closed scheduler rejects") {
  val path=Files.createTempDirectory("admission-failure")
  val scheduler=new AnalysisScheduler(1,0,0,_=>100)
  val done=new CountDownLatch(1)
  try {
   intercept[IllegalStateException](scheduler.submit("a","1",path){throw new IllegalStateException("persist failed")})
   assert(scheduler.counts==(0,0))
   assert(scheduler.submit("b","1",path){()=>done.countDown()}.isEmpty);await(done)
  }finally scheduler.close()
  assert(scheduler.submit("c","1",path){()=>()}.exists(_._1==503))
 }
}
