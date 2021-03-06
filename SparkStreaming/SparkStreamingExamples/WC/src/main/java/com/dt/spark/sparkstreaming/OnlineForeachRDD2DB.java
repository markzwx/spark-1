package com.dt.spark.sparkstreaming;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaPairRDD;
import org.apache.spark.api.java.function.FlatMapFunction;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.PairFunction;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaPairDStream;
import org.apache.spark.streaming.api.java.JavaReceiverInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;

import scala.Tuple2;

/**
 * 背景描述：在广告点击计费系统中，我们在线过滤掉黑名单的点击，进而保护广告商的利益，只进行有效的广告点击计费
 * 或者在防刷评分（或者流量）系统，过滤掉无效的投票或者评分或者流量；
 * 实现技术：使用transform Api直接基于RDD编程，进行join操作
 * @author faith
 *
 */
public class OnlineForeachRDD2DB {

	public static void main(String[] args) {
		JavaStreamingContext jsc = null;
		
		try {
			/**
			* 第1步：创建Spark的配置对象SparkConf，设置Spark程序的运行时的配置信息，
			* 例如说通过setMaster来设置程序要链接的Spark集群的Master的URL,如果设置
			* 为local，则代表Spark程序在本地运行，特别适合于机器配置条件非常差（例如
			* 只有1G的内存）的初学者 *
			*/
			SparkConf sparkConf = new SparkConf();//创建SparkConf对象
			sparkConf.setAppName("OnlineForeachRDD2DB");//设置应用程序的名称，在程序运行的监控界面可以看到名称
			// conf.setMaster("spark://Master:7077") //此时，程序在Spark集群
//			sparkConf.setMaster("local[6]");
			//设置batchDuration时间间隔来控制Job生成的频率并且创建Spark Streaming执行的入口
			jsc = new JavaStreamingContext(sparkConf, Durations.seconds(5));
			JavaReceiverInputDStream<String> lines = jsc.socketTextStream("faith-Fedora", 9999);
			
			JavaDStream<String> words = lines.flatMap(new FlatMapFunction<String, String>() {
				
				private static final long serialVersionUID = 7248584563869145056L;
	
				@Override
				public Iterable<String> call(String line) throws Exception {
					return Arrays.asList(line.split(" "));
				}
			});
			
			JavaPairDStream<String, Integer> word_1 = words.mapToPair(new PairFunction<String, String, Integer>() {
				
				private static final long serialVersionUID = 1L;

				@Override
				public Tuple2<String, Integer> call(String word) throws Exception {
					return new Tuple2<String, Integer>(word, 1);
				}
			});
			
			JavaPairDStream<String, Integer> word_count = word_1.reduceByKey(new Function2<Integer, Integer, Integer>() {
				
				private static final long serialVersionUID = 7052561230835447882L;

				@Override
				public Integer call(Integer v1, Integer v2) throws Exception {
					return v1 + v2;
				}
			});
			
//			word_count.print();
			
			/*
			 * 将word_count的内容写入Mysql
			 */
			word_count.foreachRDD(new VoidFunction<JavaPairRDD<String, Integer>>() {

				private static final long serialVersionUID = 1233407929957594025L;

				@Override
				public void call(JavaPairRDD<String, Integer> pairRdd) throws Exception {
					
					/*
					 * foreahPartition与foreach的不同是：
					 * foreach：每循环一次是一个数据
					 * foreachPartition：每循环一次是一个Partition，处理一个Partition里面的所有数据，
					 * 					注意VoidFunction的参数是Iterator，也就是每隔Partition里面数据的迭代
					 * foreachPartition常常用于向数据库例如数据，当使用foreach时候，每次循环是一个数据，那么每个数据
					 * 就要创建一个数据链连接，foreachPartition是每一个Partition创建一个数据库链接。
					 */
					pairRdd.foreachPartition(new VoidFunction<Iterator<Tuple2<String,Integer>>>() {

						private static final long serialVersionUID = 4506422371753940828L;

						@Override
						public void call(Iterator<Tuple2<String, Integer>> vs) throws Exception {
							JDBCWrapper jdbcWrapper = JDBCWrapper.getJDBCInstance();
							List<Object[]> insertParams = new ArrayList<Object[]>();
							while ( vs.hasNext() ) {
								Tuple2<String, Integer> next = vs.next();
								insertParams.add(new Object[] {next._1, next._2});
							}
							
							if ( !insertParams.isEmpty() ) {
								System.out.println(insertParams);
								jdbcWrapper.doBatch("INSERT INTO wordcount VALUES(?, ?)", insertParams);
							}
						}
					});
				}
			});
			
			jsc.start();
		} finally {
			jsc.awaitTermination();
		}
	}

}
