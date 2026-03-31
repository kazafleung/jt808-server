package org.zendo.protocol.service;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;
import org.zendo.protocol.basics.JTMessage;
import org.zendo.protocol.t808.T0001;
import reactor.core.publisher.Mono;

@HttpExchange("/device")
public interface JT808Service {

    @PostExchange("/{msgId}")
    T0001 sendT0001(@PathVariable String msgId, @RequestBody JTMessage message);

    @PostExchange("/{msgId}")
    String send(@PathVariable String msgId, @RequestBody JTMessage message);

    /** 异步调用 */
    @PostExchange("/{msgId}")
    Mono<T0001> sendT0001Async(@PathVariable String msgId, @RequestBody JTMessage message);

    /** 异步调用 */
    @PostExchange("/{msgId}")
    Mono<String> sendAsync(@PathVariable String msgId, @RequestBody JTMessage message);

}
