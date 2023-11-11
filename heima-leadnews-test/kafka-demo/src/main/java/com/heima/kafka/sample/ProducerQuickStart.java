package com.heima.kafka.sample;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.Properties;

public class ProducerQuickStart {

    public static void main(String[] args) {

        //1 give kafka connection settings
        Properties prop = new Properties();
        prop.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "192.168.200.130:9092");
        prop.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");
        prop.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer");

        //2 create producer object
        KafkaProducer<String,String> kafkaProducer = new KafkaProducer<String, String>(prop);

        //3 send message
        /*
        there 3 parameters of the packaged message:
        1 topic
        2 message key
        3 message value
         */
        ProducerRecord<String,String> records = new ProducerRecord<String,String>("topic-first","key-001","hallo kafka");
        kafkaProducer.send(records);

        //4 close the kafka channel
        /*
        must be closed, otherwise the message cannot be sent
         */
        kafkaProducer.close();


    }
}
