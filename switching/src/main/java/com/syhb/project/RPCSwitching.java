package com.syhb.project;

import com.rabbitmq.client.*;
import com.syhb.project.models.ISOMessage;
import com.syhb.project.repositories.*;
import com.syhb.project.services.*;
import org.jpos.iso.ISOException;
import org.jpos.iso.ISOMsg;
import org.jpos.iso.packager.GenericPackager;
import org.json.JSONException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Controller
public class RPCSwitching {

    private static final String RPC_QUEUE_NAME = "switching-queue";

    private static AccountRepository accountRepository;

    private static TransactionRepository transactionRepository;

    private static CustomerRepository customerRepository;

    private static BankRepository bankRepository;

    private static ProductRepository productRepository;

    @Autowired
    public RPCSwitching(AccountRepository accountRepository, TransactionRepository transactionRepository,
                        CustomerRepository customerRepository, BankRepository bankRepository, ProductRepository productRepository) {
        RPCSwitching.accountRepository = accountRepository;
        RPCSwitching.transactionRepository = transactionRepository;
        RPCSwitching.customerRepository = customerRepository;
        RPCSwitching.bankRepository = bankRepository;
        RPCSwitching.productRepository = productRepository;
    }

    private static String process(String body) throws JSONException {

        ISOMessage isoMessage = new ISOMessage(unpackFromIso(body));
        Map<Integer, String> message = isoMessage.getMessage();

        TransactionService transaction;

        switch (message.get(3)) {
            case "400010":
                transaction = new TransferService(accountRepository, transactionRepository, customerRepository);
                break;
            case "380010":
                transaction = new PaymentInquiryService(productRepository);
                break;
            case "180010":
                transaction = new PaymentService(productRepository);
                break;
            default:
                transaction = new TransferInquiryService(accountRepository, customerRepository, bankRepository);
                break;
        }

        return transaction.response(message);

    }

    public static void main() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("localhost");
        factory.setPort(5672);
        factory.setUsername("guest");
        factory.setPassword("guest");

        try (Connection connection = factory.newConnection();
             Channel channel = connection.createChannel()) {
            channel.queueDeclare(RPC_QUEUE_NAME, true, false, false, null);
            channel.queuePurge(RPC_QUEUE_NAME);

            channel.basicQos(1);

            System.out.println(" [x] Awaiting RPC requests");

            Object monitor = new Object();
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                AMQP.BasicProperties replyProps = new AMQP.BasicProperties
                        .Builder()
                        .correlationId(delivery.getProperties().getCorrelationId())
                        .build();

                String response = "";

                try {
                    String message = new String(delivery.getBody(), StandardCharsets.UTF_8);

                    System.out.println(" [.] " + message);

                    response += process(message);
                } catch (JSONException e) {
                    e.printStackTrace();
                } finally {
                    channel.basicPublish("", delivery.getProperties().getReplyTo(), replyProps, response.getBytes(StandardCharsets.UTF_8));
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
                    // RabbitMq consumer worker thread notifies the RPC server owner thread
                    synchronized (monitor) {
                        monitor.notify();
                    }
                }
            };

            channel.basicConsume(RPC_QUEUE_NAME, false, deliverCallback, (consumerTag -> { }));
            // Wait and be prepared to consume the message from RPC client.
            while (true) {
                synchronized (monitor) {
                    try {
                        monitor.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private static Map<Integer, String> unpackFromIso(String isomessage){
        Map<Integer, String> map = new HashMap<Integer, String>();

        try{
            ISOMsg isoMsg = new ISOMsg();
            isoMsg.setPackager(new GenericPackager("src/main/resources/fields.xml"));
            isoMsg.unpack(isomessage.getBytes());

            for(int i = 1; i <= isoMsg.getMaxField(); i++){
                if(isoMsg.hasField(i)){
                    map.put(i, isoMsg.getString(i));
                }
            }
        }catch(ISOException e){
            e.printStackTrace();
        }

        return map;
    }

}
