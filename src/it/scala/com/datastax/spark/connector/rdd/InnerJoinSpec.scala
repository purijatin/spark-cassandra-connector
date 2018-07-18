/*
 * Copyright DataStax, Inc.
 *
 * Please see the included license file for details.
 */

package com.datastax.spark.connector.rdd

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

import com.datastax.bdp.config.YamlClientConfiguration
import com.datastax.driver.core.Session
import com.datastax.spark.connector._
import com.datastax.spark.connector.cql.CassandraConnector
import com.datastax.spark.connector.embedded.YamlTransformations

@RunWith(classOf[JUnitRunner])
class InnerJoinSpec extends DseITFlatSpecBase {

  YamlClientConfiguration.setAsClientConfigurationImpl()

  useCassandraConfig(Seq(YamlTransformations.Default))
  useSparkConf(sparkConf)

  override lazy val conn = CassandraConnector(sparkConf)

  beforeClass {
    conn.withSessionDo { session =>
      session.execute(
        s"""CREATE KEYSPACE IF NOT EXISTS $ks
            |WITH REPLICATION = { 'class': 'SimpleStrategy', 'replication_factor': 1 }"""
          .stripMargin)
      makeSimpleJoinTables(session)
      makeComplexJoinTables(session)
    }
  }

  def makeSimpleJoinTables(session: Session): Unit = {
    session.execute(
      s"""CREATE TABLE IF NOT EXISTS $ks.leftjoin( key INT, foo TEXT, PRIMARY KEY
         |(key))""".stripMargin)
    session.execute(
      s"""CREATE TABLE IF NOT EXISTS $ks.rightjoin( key INT, bar TEXT, PRIMARY KEY
         | (key))""".stripMargin)
    for (i <- 1 to 100) {
      session.execute(s"INSERT INTO $ks.leftjoin (key, foo) values ($i, 'foo$i')")
      session.execute(s"INSERT INTO $ks.rightjoin (key, bar) values ($i, 'bar$i')")
    }
  }

  def makeComplexJoinTables(session: Session): Unit = {
    session.execute(
      s"""CREATE TABLE IF NOT EXISTS $ks.complexleftjoin
         |(key1 INT, key2 INT, c TEXT, foo TEXT, PRIMARY KEY ((key1, key2), c))""".stripMargin)
    session.execute(
      s"""CREATE TABLE IF NOT EXISTS $ks.complexrightjoin
         |(key1 INT, key2 INT, c TEXT, bar TEXT, PRIMARY KEY ((key1, key2), c))""".stripMargin)
    for (key1 <- 1 to 10; key2 <- 1 to 10; c <- 1 to 3) {
      session.execute(
        s"""INSERT INTO $ks.complexleftjoin (key1, key2, c, foo)
          |values ($key1, $key2, '$c', 'foo$c')""".stripMargin)
      if (key2 % 2 == 0)
        session.execute(
          s"""INSERT INTO $ks.complexrightjoin (key1, key2, c, bar)
            |values ($key1,  $key2, '$c', 'foo$c')""".stripMargin)
    }
  }


  "MergeJoinRDD" should "be able to merge two C* tables together that share a partition key" in {
    val leftRDD = sc.cassandraTable(ks, "leftjoin")
    val rightRDD = sc.cassandraTable(ks, "rightjoin")
    val merged = new CassandraMergeJoinRDD(sc, leftRDD, rightRDD)
    val results = merged.collect()
    results should have size (100)
    for (i <- 0 until 100) {
      val key = results(i)._1(0).getInt("key")
      val left = results(i)._1
      val right = results(i)._2
      left(0).getString("foo") should be(s"foo$key")
      right(0).getString("bar") should be(s"bar$key")
    }
  }

  it should "be able to merge two C* tables together with multiple spark partitions" in {
    implicit val readConf = ReadConf(splitCount = Some(10))
    val leftRDD = sc.cassandraTable(ks, "leftjoin")
    val rightRDD = sc.cassandraTable(ks, "rightjoin")
    val merged = new CassandraMergeJoinRDD(sc, leftRDD, rightRDD)
    val results = merged.collect()
    results should have size (100)
    for (i <- 0 until 100) {
      val key = results(i)._1(0).getInt("key")
      val left = results(i)._1
      val right = results(i)._2
      left(0).getString("foo") should be(s"foo$key")
      right(0).getString("bar") should be(s"bar$key")
    }
  }

  it should " be able to merge two C* tables together with specific types" in {
    val leftRDD = sc.cassandraTable[(Int, String)](ks, "leftjoin")
    val rightRDD = sc.cassandraTable[(Int, String)](ks, "rightjoin")
    val merged = new CassandraMergeJoinRDD(sc, leftRDD, rightRDD)
    val results = merged.collect()
    results should have size (100)
    for (i <- 0 until 100) {
      val key = results(i)._1(0)._1
      val left = results(i)._1
      val right = results(i)._2
      left(0)._2 should be(s"foo$key")
      right(0)._2 should be(s"bar$key")
    }
  }

  it should " be able to merge two C* tables with composite keys" in {
    val leftRDD = sc.cassandraTable(ks, "complexleftjoin")
    val rightRDD = sc.cassandraTable(ks, "complexrightjoin")
    val leftResult = leftRDD.collect()
    val rightResult = rightRDD.collect()
    val merged = new CassandraMergeJoinRDD(sc, leftRDD, rightRDD)
    val results = merged.collect()
    results should have size (100)
    for (i <- 0 until 100) {
      val key1 = results(i)._1(0).getInt("key1")
      val key2 = results(i)._1(0).getInt("key2")
      results(i)._1 should have length 3
      results(i)._1.map(_.getString("c")) should contain allOf("1", "2", "3")
      if (key2 % 2 == 0) {
        results(i)._2 should have length 3
        results(i)._2.map(_.getString("c")) should contain allOf("1", "2", "3")
      }
      else results(i)._2 should be('empty)
    }
  }
}
