package com.hb.utils


/**
  * Created by Simon on 2017/2/27.
  */

object Num {

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
}

