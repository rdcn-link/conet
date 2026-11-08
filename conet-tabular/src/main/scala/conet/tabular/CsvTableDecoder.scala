package conet.tabular

import conet.appbuilder.{ResultDecoder,Values}
import com.fasterxml.jackson.databind.JsonNode
import xstream.data.{ByteSource,FileOptions}
import xstream.util.Json
import java.io.ByteArrayInputStream

/** CSV result presentation plugin; parsing uses the XStream format implementation. */
final class CsvTableDecoder extends ResultDecoder {
 val id="csv-table"
 def decode(bytes:Array[Byte]):JsonNode={
  require(bytes.length<=16*1024*1024,"CSV 超过 16 MiB 上限")
  val source=new ByteSource {val name="result.csv";val size=Some(bytes.length.toLong);def open(check:()=>Unit)={check();new ByteArrayInputStream(if(bytes.take(3).sameElements(Array(0xef.toByte,0xbb.toByte,0xbf.toByte)))bytes.drop(3)else bytes)}}
  val reader=new xstream.data.formats.CsvFormat().rows(source,FileOptions())
  try{
   val columns=reader.names.toVector;require(columns.nonEmpty&&columns.size<=256&&columns.distinct.size==columns.size&&columns.forall(n=>n.nonEmpty&&n.length<=256),"CSV 表头须包含 1–256 个非空且唯一的字段名")
   val rows=Json.mapper.createArrayNode();val empty=Array.fill(columns.size)(0);val numeric=Array.fill(columns.size)(0);val minima=Array.fill(columns.size)(Double.PositiveInfinity);val maxima=Array.fill(columns.size)(Double.NegativeInfinity)
   reader.iterator.foreach{values=>
    if(Thread.currentThread.isInterrupted)throw new java.util.concurrent.CancellationException("分析已取消")
    require(rows.size<100000,"CSV 不能超过 100000 条记录")
    val row=Values.obj;columns.zipWithIndex.foreach{case(name,i)=>val v=values(i);if(v==null){row.putNull(name);empty(i)+=1}else{val text=v.toString;row.put(name,text);scala.util.Try(text.toDouble).toOption.filter(java.lang.Double.isFinite).foreach{n=>numeric(i)+=1;minima(i)=math.min(minima(i),n);maxima(i)=math.max(maxima(i),n)}}};rows.add(row)
   }
   val stats=columns.zipWithIndex.map{case(name,i)=>val n=Values.obj.put("name",name).put("empty_count",empty(i)).put("nonempty_count",rows.size-empty(i)).put("numeric_count",numeric(i));if(numeric(i)>0)n.put("min",minima(i)).put("max",maxima(i))else{n.putNull("min");n.putNull("max")};n}
   val result=Values.obj.put("row_count",rows.size).put("column_count",columns.size);result.set[JsonNode]("columns",Values.array(columns.map(Json.mapper.getNodeFactory.textNode)));result.set[JsonNode]("rows",rows);result.set[JsonNode]("column_statistics",Values.array(stats));result
  }finally reader.close()
 }
}
