# spark real-time analysis
Spark version v2.0.0  
Kafka version v0.9.0.1  

# Introduction
flume采集日志传输到kafka，再通过spark-streaming进行实时分析，得到pv，uv，用户分布图等指标，结果保存两份。一份通过http接口发送给open-falcon做监控和告警，一部分存到数据库。
  # Authors
|Email Address        | Name   |  
|simonwindwf@gmail.com| Simon  |

# Contents 

## Initializing StreamingContext

```
ssc = new StreamingContext(conf, Seconds(60))
val messages = ReceiverLauncher.launch(ssc, props, numberOfReceivers, StorageLevel.MEMORY_ONLY)
val partitonOffset_stream = ProcessedOffsetManager.getPartitionOffset(messages, props)
ProcessedOffsetManager.persists(partitonOffset_stream, props)
```  

## Filter and Split

日志分隔符为"^^A"

```
val filterMessages = messages.map { x => new String(x.getPayload) }
    .filter(s => s.contains("GET") || s.contains("POST"))
    .map(line => line.split("\\^\\^A"))
    .map(line => Array(line(column1), line(column2).split(" ")(1), line(column3), line(column4), line(column5)))
```
## Jobs
分多个JOB计算指标，详情见代码注释。结果通过foreachRdd和foreachPartiion算子发送给外部接口和数据库

## Algorithms
### 1:百分位的计算 

目前采用的是快排再求相应百分位的值，数据量很大的时候可能会有瓶颈。可以考虑采用分治法求第K大个数，做粗略估计 

```
  /**
    * @param arr 输入数组
    * @return 快速排序后的数组
    */
  def quickSort(arr: Array[Double]): Array[Double] = {
    if (arr.length <= 1)
      arr
    else {
      val index = arr(arr.length / 2)
      Array.concat(
        quickSort(arr filter (index >)),
        arr filter (_ == index),
        quickSort(arr filter (index <))
      )
    }
  }

  /**
    * @param arr 输入数组
    * @return p 百分位
    */
  def percentile(arr: Array[Double], p: Double) = {
    if (p > 1 || p < 0) throw new IllegalArgumentException("p must be in [0,1]")
    val sorted = quickSort(arr)
    val f = (sorted.length + 1) * p
    val i = f.toInt
    if (i == 0) sorted.head
    else if (i >= sorted.length) sorted.last
    else {
      sorted(i - 1) + (f - i) * (sorted(i) - sorted(i - 1))
    }
  }
```

### 2：每天用户数

采用updateStateByKey算子保存HLL对象，并且在每天0点的时候重新计数

  
```

val updateCardinal = (values: Seq[String], state: Option[HyperLogLogPlus]) => {
    val calendar = Calendar.getInstance()
    val hh = calendar.get(Calendar.HOUR_OF_DAY)
    val mm = calendar.get(Calendar.MINUTE)
    if (hh == 0 && mm == 0 ) {
      val hll = new HyperLogLogPlus(14)
      for (value <- values) { hll.offer(value) }
      Option(hll)
    }
    else {
      if (state.nonEmpty) {
        val hll = state.get
        for (value <- values) { hll.offer(value) }
        Option(hll)
      } else {
        val hll = new HyperLogLogPlus(14)
        for (value <- values) { hll.offer(value) }
        Option(hll)
      }
    }
  }
  //计算结果实时UV只有一条数据可不用批量提交 
 filterMessages.map(x => (null, x(3))).updateStateByKey(updateCardinal)
      .map(x => x._2.cardinality).foreachRDD(rdd => {
    ....(省略)
    }
    )
```

### 3:各省用户数

计算为笛卡尔乘积，待改进


```
 if (aggregateProvinceFlag) {
   ipRecords.map(x => (IpToLong.IPv4ToLong(x._1.trim),x._2))
   .map(x => (LocationInfo.findLocation(ipMapBroadCast.value,x._1),x._2))
   .reduceByKey(_+_) foreachRDD( rdd => {
    rdd.foreachPartition { data =>
       if (data != null) {
          val conn = ConnectionPool.getConnectionPool(propC3p0BroadCast.value).getConnection
          conn.setAutoCommit(false)

          val sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm")
          val currentTimestamp = sdf.format(new Date())

          try {
             val sql = "insert into uv_province(time,province,uv) values (?,?,?)"
             val preparedStatement = conn.prepareStatement(sql)
                data.foreach(r => {
                  preparedStatement.setString(1, currentTimestamp)
                  preparedStatement.setString(2, r._1.toString)
                  preparedStatement.setInt(3, r._2)
                  preparedStatement.addBatch()
                })

             preparedStatement.executeBatch()
             conn.commit()
             preparedStatement.clearBatch()
          } catch {
             case e:SQLException  => conn.rollback()
             case e:Exception => e.printStackTrace()
          } finally {
             conn.close()
            }
          }
          }
        }
          )
      }
```


# Output 

具体指标为： 
    
1：每分钟请求数
        
2：每分钟错误请求数
        
3：每分钟各错误码的数量
        
4：每分钟用户数（精确统计，distinct去重）
        
5：每天实时用户数（HyperLogLog基数估计）
        
6: 99th,95th,75,50th的百分位时延
        
7: 各省用户分布

8：访问频次异常IP

# Defect

1： 多个job每个job每分钟都会跟falcon的接口建立HTTP连接,链接过于频繁。  

2： 每60秒生成一个RDD，计算一次指标。不是按访问时间计算的指标，统计有误差。改进方法：从日志里面获取访问时间作为key再groupByKey进行统计，存储到hbase，延迟日志的补算可采用hbase的counter。如果用mysql做补算，要先查询，再update，性能会很差，不推荐。  

3： 时延相关指标涉及到对整个时延的集合进行排序和求百分位，目前的方法可能统计会有误差。但是一般情况下延迟到达的日志记录很少，误差也可以忽略。
