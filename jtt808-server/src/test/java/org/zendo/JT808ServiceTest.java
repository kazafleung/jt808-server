package org.zendo;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.zendo.commons.model.R;
import org.zendo.protocol.basics.JTMessage;
import org.zendo.protocol.service.JT808Service;
import org.zendo.protocol.service.JT808ServiceConfiguration;
import org.zendo.protocol.service.JT808ServiceProperties;
import org.zendo.protocol.t808.T0001;

import java.util.function.Consumer;


public class JT808ServiceTest {

    private static final JT808ServiceProperties jt808Properties = new JT808ServiceProperties("http://127.0.0.1:8100");
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final JT808Service jt808Service = new JT808ServiceConfiguration(jt808Properties, objectMapper).jt808Api();

    public static void main(String[] args) {
        syncCall();
        asyncCall();
    }

    /** 同步调用 */
    private static void syncCall() {
        JTMessage message = new JTMessage().setClientId("1111111111");
        try {
            T0001 result = jt808Service.sendT0001("8304", message);
            System.out.println(result);
        } catch (WebClientResponseException e) {
            R error = e.getResponseBodyAs(R.class);
            System.out.println(error);
        }
    }

    /** 异步调用 */
    @SneakyThrows
    private static void asyncCall() {
        JTMessage message = new JTMessage().setClientId("1111111111");
        jt808Service.sendT0001Async("8304", message)
                .doOnError(WebClientResponseException.class, e -> {
                    R error = e.getResponseBodyAs(R.class);
                    System.out.println(error);
                }).subscribe(new Consumer<T0001>() {
                    @Override
                    public void accept(T0001 result) {
                        System.out.println(result);
                    }
                });
        Thread.sleep(1000L);
    }
}