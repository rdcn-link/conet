package conet.appbuilder

import java.nio.file.{Files,Path}
import java.util.concurrent.{Executors,TimeUnit}
import scala.collection.mutable
import scala.util.control.NonFatal

/** One bounded admission queue shared by all published applications in a web process. */
final class AnalysisScheduler(val maxRunning:Int=2,val maxQueued:Int=16,val minFreeBytes:Long=1024L*1024*1024,
                             usable:Path=>Long=p=>Files.getFileStore(p).getUsableSpace) extends AutoCloseable {
 require(maxRunning>=1 && maxRunning<=128,"max_running must be between 1 and 128")
 require(maxQueued>=0 && maxQueued<=10000,"max_queued must be between 0 and 10000")
 require(minFreeBytes>=0,"min_free_bytes must be nonnegative")
 private case class Work(owner:String,key:String,run:()=>Unit)
 private val pending=mutable.ArrayBuffer.empty[Work]
 private val running=mutable.Set.empty[String]
 private var closed=false
 private val pool=Executors.newFixedThreadPool(maxRunning,(r:Runnable)=>{val t=new Thread(r,"conet-analysis");t.setDaemon(true);t})
 def checkDisk(path:Path):Unit={
  var existing=path.toAbsolutePath
  while(!Files.exists(existing) && existing.getParent!=null)existing=existing.getParent
  require(usable(existing)>=minFreeBytes,"存储空间不足，暂停接收或启动分析，请清理磁盘后重试")
 }
 /** prepare persists the accepted job before a worker can start it. No work is created on rejection. */
 def submit(owner:String,key:String,path:Path)(prepare: => (()=>Unit)):Option[(Int,String)]=synchronized {
  if(closed)return Some(503->"分析服务正在关闭")
  try checkDisk(path) catch{case NonFatal(_)=>return Some(507->"存储空间不足或无法检查磁盘，暂停接收分析")}
  val immediate=running.size<maxRunning && !running(owner) && !pending.exists(_.owner==owner)
  if(!immediate && pending.size>=maxQueued)return Some(429->"全局分析队列已满，请稍后重试")
  pending+=Work(owner,key,prepare);dispatch();None
 }
 private def dispatch():Unit={
  var index=pending.indexWhere(w=> !running(w.owner))
  while(!closed && running.size<maxRunning && index>=0){
   val work=pending.remove(index);running+=work.owner
   pool.execute(new Runnable{def run():Unit=try work.run() finally AnalysisScheduler.this.synchronized{running-=work.owner;dispatch()}})
   index=pending.indexWhere(w=> !running(w.owner))
  }
 }
 /** Return queued work for cancellation cleanup outside the scheduler lock. */
 def remove(owner:String,key:String):Option[()=>Unit]=synchronized {
  val i=pending.indexWhere(w=>w.owner==owner && w.key==key)
  if(i<0)None else Some(pending.remove(i).run)
 }
 def counts:(Int,Int)=synchronized{running.size->pending.size}
 def close():Unit={synchronized{closed=true;pending.clear()};pool.shutdownNow();pool.awaitTermination(10,TimeUnit.SECONDS)}
}
