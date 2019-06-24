package com.dksou.fitting.stream.service.impl;


import com.dksou.fitting.stream.service.JavaKafkaConsumerHighAPIHdfsService;
import com.dksou.fitting.stream.utils.HDFSUtils;
import com.dksou.fitting.stream.utils.PropUtils;
import kafka.consumer.Consumer;
import kafka.consumer.ConsumerConfig;
import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.consumer.ConsumerConnector;
import kafka.message.MessageAndMetadata;
import kafka.serializer.StringDecoder;
import kafka.utils.VerifiableProperties;
import org.apache.log4j.Logger;
import org.apache.thrift.TException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class JavaKafkaConsumerHighAPIHdfsImpl implements JavaKafkaConsumerHighAPIHdfsService.Iface,Runnable{

    static Logger logger = Logger.getLogger(JavaKafkaConsumerHighAPIHdfsImpl.class);
    static Properties providerProp = PropUtils.getProp("consumer-hdfs.properties");
    static String zookeeper = providerProp.getProperty("consumer.hdfs.zookeeper.connect");//"192.168.1.126:2181";
    static String groupId = providerProp.getProperty("consumer.hdfs.group.id");//"group1";
    static int threads = Integer.parseInt(providerProp.getProperty("consumer.hdfs.kafka.topicConsumerNum")); //1
    static String hdfsPath = providerProp.getProperty("consumer.hdfs.hdfsFilePath");

    /**
     * Kafka数据消费对象
     */
    private ConsumerConnector consumer;

    /**
     * Kafka Topic名称
     */
    private String topic;

    /**
     * 线程数量，一般就是Topic的分区数量
     */
    private int numThreads;

    /**
     * 线程池
     */
    private ExecutorService executorPool;

    /**
     * 构造函数
     *
     * @param topic      Kafka消息Topic主题
     * @param numThreads 处理数据的线程数/可以理解为Topic的分区数
     * @param zookeeper  Kafka的Zookeeper连接字符串
     * @param groupId    该消费者所属group ID的值
     */
    public JavaKafkaConsumerHighAPIHdfsImpl(String topic, int numThreads, String zookeeper, String groupId) {
        // 1. 创建Kafka连接器
        this.consumer = Consumer.createJavaConsumerConnector(createConsumerConfig(zookeeper, groupId));
        // 2. 数据赋值
        this.topic = topic;
        this.numThreads = numThreads;
    }

    public JavaKafkaConsumerHighAPIHdfsImpl() {

    }


    public void run() {
        // 1. 指定Topic
        Map<String, Integer> topicCountMap = new HashMap<String, Integer>();
        topicCountMap.put(this.topic, this.numThreads);

        // 2. 指定数据的解码器
        StringDecoder keyDecoder = new StringDecoder(new VerifiableProperties());
        StringDecoder valueDecoder = new StringDecoder(new VerifiableProperties());

        // 3. 获取连接数据的迭代器对象集合
        /**
         * Key: Topic主题
         * Value: 对应Topic的数据流读取器，大小是topicCountMap中指定的topic大小
         */
        Map<String, List<KafkaStream<String, String>>> consumerMap = this.consumer.createMessageStreams(topicCountMap, keyDecoder, valueDecoder);

        // 4. 从返回结果中获取对应topic的数据流处理器
        List<KafkaStream<String, String>> streams = consumerMap.get(this.topic);

        // 5. 创建线程池
        this.executorPool = Executors.newFixedThreadPool(this.numThreads);


        // 6. 构建数据输出对象
        int threadNumber = 0;
        for (final KafkaStream<String, String> stream : streams) {
            this.executorPool.submit(new ConsumerKafkaStreamProcesser(stream, threadNumber));
            threadNumber++;
        }
    }

    public void shutdown() {
        // 1. 关闭和Kafka的连接，这样会导致stream.hashNext返回false
        if (this.consumer != null) {
            this.consumer.shutdown();
        }

        // 2. 关闭线程池，会等待线程的执行完成
        if (this.executorPool != null) {
            // 2.1 关闭线程池
            this.executorPool.shutdown();

            // 2.2. 等待关闭完成, 等待五秒
            try {
                if (!this.executorPool.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.info("Timed out waiting for consumer threads to shut down, exiting uncleanly!!");
                }
            } catch (InterruptedException e) {
                logger.error("Interrupted during shutdown, exiting uncleanly!!");
            }
        }

    }

    /**
     * 根据传入的zk的连接信息和groupID的值创建对应的ConsumerConfig对象
     *
     * @param zookeeper zk的连接信息，类似于：<br/>
     *
     * @param groupId   该kafka consumer所属的group id的值， group id值一样的kafka consumer会进行负载均衡
     * @return Kafka连接信息
     */
    private ConsumerConfig createConsumerConfig(String zookeeper, String groupId) {
        // 1. 构建属性对象
        Properties prop = new Properties();
        // 2. 添加相关属性
        prop.put("group.id", groupId); // 指定分组id
        prop.put("zookeeper.connect", zookeeper); // 指定zk的连接url
        prop.put("zookeeper.session.timeout.ms", providerProp.getProperty("consumer.hdfs.zookeeper.session.timeout.ms")); //
        prop.put("session.timeout.ms",providerProp.getProperty("consumer.hdfs.session.timeout.ms"));
        prop.put("enable.auto.commit",providerProp.getProperty("consumer.hdfs.enable.auto.commit"));
        prop.put("auto.offset.reset",providerProp.getProperty("consumer.hdfs.auto.offset.reset"));
        prop.put("offsets.storage",providerProp.getProperty("consumer.hdfs.offsets.storage"));
        prop.put("dual.commit",providerProp.getProperty("consumer.hdfs.dual.commit"));
        //prop.put("zookeeper.sync.time.ms", providerProp.getProperty("consumer.es.auto.commit.interval.ms"));
        prop.put("auto.commit.interval.ms", providerProp.getProperty("consumer.hdfs.auto.commit.interval.ms"));


        if(!providerProp.getProperty("consumer.hdfs.security.protocol").equals("")&& providerProp.getProperty("consumer.hdfs.security.protocol") != null && !providerProp.getProperty("consumer.hdfs.ssl.truststore.location").equals("null")){

            prop.put("security.protocol", providerProp.getProperty("consumer.hdfs.security.protocol"));
            prop.put("ssl.truststore.location", providerProp.getProperty("consumer.hdfs.ssl.truststore.location"));
            prop.put("ssl.truststore.password", providerProp.getProperty("consumer.hdfs.ssl.truststore.password"));
            prop.put("ssl.keystore.location", providerProp.getProperty("consumer.hdfs.ssl.keystore.location"));
            prop.put("ssl.keystore.password", providerProp.getProperty("consumer.hdfs.ssl.keystore.password"));
            prop.put("ssl.key.password", providerProp.getProperty("consumer.hdfs.ssl.key.password"));

        }

        // 3. 构建ConsumerConfig对象
        return new ConsumerConfig(prop);
    }




    /**
     * Kafka消费者数据处理线程
     */
    public static class ConsumerKafkaStreamProcesser implements Runnable {
        // Kafka数据流
        private KafkaStream<String, String> stream;
        // 线程ID编号
        private int threadNumber;

        public ConsumerKafkaStreamProcesser(KafkaStream<String, String> stream, int threadNumber) {
            this.stream = stream;
            this.threadNumber = threadNumber;
        }

        int count = 0;


        public void run() {
            // 1. 获取数据迭代器
            ConsumerIterator<String, String> iter = this.stream.iterator();
            // 2. 迭代输出数据
            while (iter.hasNext()) {
                // 2.1 获取数据值
                MessageAndMetadata value = iter.next();
                count++;
                // 2.2 输出
//                logger.info(count + ":" + this.threadNumber + ":" + value.offset() +":" + value.key() + ":" + value.message());
//                System.out.println(count + ":" + this.threadNumber + ":" + value.offset() +":"  + value.key() + ":" + value.message());
                try {
                    String hdfs_xml = providerProp.getProperty("consumer.hdfs.hdfs.path");
                    String core_xml = providerProp.getProperty("consumer.hdfs.core.path");
                    String krb5_conf = providerProp.getProperty("consumer.hdfs.krb5.path");
                    String principal = providerProp.getProperty("consumer.hdfs.principal.path");
                    String keytab = providerProp.getProperty("consumer.hdfs.keytab.pat");



                    HDFSUtils.sendToHDFS(hdfs_xml,core_xml,krb5_conf,principal,keytab,
                            hdfsPath + "/" + this.threadNumber,value.message().toString() + "\n");
                    //+ this.threadNumber,value.message().toString() + "\n");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            // 3. 表示当前线程执行完成
            System.out.println("Shutdown Thread:" + this.threadNumber);
        }
    }


    public String StartHdfs() throws TException {
        JavaKafkaConsumerHighAPIHdfsImpl example = null;
        String CG = "成功";
        try {
            String topicName = providerProp.getProperty("consumer.hdfs.kafka.topicNames");
            example = new JavaKafkaConsumerHighAPIHdfsImpl(topicName, threads, zookeeper, groupId);
            new Thread(example).start();
        } catch (Exception e) {
            e.printStackTrace();
            return  e.toString();
        } finally {
            example.shutdown();
        }
        return CG;
    }








}
