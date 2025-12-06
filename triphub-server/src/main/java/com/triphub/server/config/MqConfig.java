package com.triphub.server.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class MqConfig {

    public static final String SECKILL_EXCHANGE = "TRIPHUB_EXCHANGE";
    public static final String SECKILL_DL_EXCHANGE = "TRIPHUB_DL_EXCHANGE";
    public static final String SECKILL_QUEUE = "TRIPHUB_SECKILL_QUEUE";
    public static final String SECKILL_DLQ = "TRIPHUB_SECKILL_DLQ";

    @Bean
    public DirectExchange seckillExchange() {
        return new DirectExchange(SECKILL_EXCHANGE);
    }

    @Bean
    public DirectExchange seckillDlExchange() {
        return new DirectExchange(SECKILL_DL_EXCHANGE);
    }

    @Bean
    public Queue seckillQueue() {
        Map<String, Object> args = new HashMap<>();
        // 如有需要可设置 TTL 和死信转发
        args.put("x-dead-letter-exchange", SECKILL_DL_EXCHANGE);
        args.put("x-dead-letter-routing-key", "seckill.dlq");
        return QueueBuilder.durable(SECKILL_QUEUE)
                .withArguments(args)
                .build();
    }

    @Bean
    public Queue seckillDlq() {
        return QueueBuilder.durable(SECKILL_DLQ).build();
    }

    @Bean
    public Binding bindSeckillQueue(Queue seckillQueue, DirectExchange seckillExchange) {
        return BindingBuilder.bind(seckillQueue).to(seckillExchange).with("seckill");
    }

    @Bean
    public Binding bindSeckillDlq(Queue seckillDlq, DirectExchange seckillDlExchange) {
        return BindingBuilder.bind(seckillDlq).to(seckillDlExchange).with("seckill.dlq");
    }
}


