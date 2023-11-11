package com.heima.kafka.listener;


import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class HelloListener {

    @KafkaListener(topics = "xh_kafka")
    public void onMessage(String message){
        if(!message.isEmpty()){
            System.out.println(message);
        }
    }

}
