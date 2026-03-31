JT808 Protocol Gateway
====================

# Project Overview
* Built on Netty, implements message processing, encoding, and decoding for JT808, JT1078, Jiangsu (JSATL), and Guangdong (GDRTA) transport protocols;
* Supports both TCP and UDP protocols without any code changes;
* Uses Spring WebFlux to provide high-concurrency Web API services;
* Spring-independent — Spring can be removed for standalone usage (encoding/decoding also supports Android);
* The most concise, clean, and easy-to-use transport protocol development framework.

# Key Features
* Compact codebase, easy to extend for secondary development;
* Inspired by the design philosophy of Spring and Hibernate — developers familiar with web development can get started quickly;
* Annotation-driven protocol definition — no more tedious manual packet packing/unpacking;
* Supports asynchronous batch processing to significantly improve MySQL write performance;
* Provides a message interpreter (packet analysis tool) to diagnose encoding/decoding issues;
* Comprehensive test coverage for stable releases.

# Protocol Support (Transport Layer: TCP & UDP)
| Protocol Name | Version | Supported | Notes |
|---|---|---|---|
| JT/T 808 | 2011 | Yes | |
| JT/T 808 | 2013 | Yes | |
| JT/T 808 | 2019 | Yes | |
| JT/T 1078 | 2016 | Yes | Requires self-hosted media server |
| T/JSATL 12 (Active Safety - Jiangsu) | 2017 | Yes | Based on JT/T808-2013 |
| T/GDRTA 002 (Active Safety - Guangdong) | 2019 | Yes | Based on JT/T808-2019 |

Note: No manual configuration needed — automatically compatible with 2011, 2013, and 2019 protocol versions. Supports fragmented request, fragmented response, and timeout-based fragment retransmission.
JT1078 supports audio/video commands; a media server must be set up separately.

# Demo
 * Device connection: 127.0.0.1:7100
 * Log monitor: http://127.0.0.1:8100/ws.html
 * API docs: http://127.0.0.1:8100/doc.html

# Getting Started

## 1. Verify Message Definitions
Decode analysis tool: `org.zendo.Elucidator` (Packet ⟺ Object)

Use `src/test/java/Elucidator` to analyze each field's position and converted value within a packet, helping diagnose parsing errors.
```java
package org.zendo;

public class Elucidator extends JT808Beans {

    public static final JTMessageAdapter coder = new JTMessageAdapter("org.zendo.protocol");

    public static void main(String[] args) {
        String hex = "020000d40123456789017fff000004000000080006eeb6ad02633df7013800030063200707192359642f000000400101020a0a02010a1e00640001b2070003640e200707192359000100000061646173200827111111010101652f000000410202020a0000000a1e00c8000516150006c81c20070719235900020000000064736d200827111111020202662900000042031e012c00087a23000a2c2a200707192359000300000074706d732008271111110303030067290000004304041e0190000bde31000d90382007071923590004000000006273642008271111110404049d";
        JTMessage msg = H2019(T0200JSATL12());

        msg = decode(hex);
        hex = encode(msg);
    }
}
```
Sample output of Elucidator:
```
0	[001f] Province ID: 31
2	[0073] City/County ID: 115
4	[0000000034] Manufacturer ID: 4
9	[000000000000000000000042534a2d47462d3036] Device Model: BSJ-GF-06
29	[74657374313233] Terminal ID: test123
36	[01] Plate Color: 1
37	[b2e241383838383838] Vehicle ID: 测A888888
0	[0100] Message ID: 256
2	[002e] Message Body Properties: 46
4	[012345678901] Terminal Phone: 12345678901
10	[7fff] Serial No: 32767

7e0100002e0123456789017fff001f00730000000034000000000000000000000042534a2d47462d30367465737431323301b2e241383838383838157e
```
## 2. Simulate Device Requests
Run `\Protocol Docs\packet-tool.exe`
1. Protocol type: **TCP Client**
2. Remote host address: 127.0.0.1
3. Remote host port: 7100
4. Receive settings: ⊙HEX
5. Send settings: ⊙HEX
6. Click **Connect**
7. Paste the packet generated in the previous step into the text box
8. Click **Send**

Note: Select HEX mode before pasting the packet.
```
7e0100002e0123456789017fff001f00730000000034000000000000000000000042534a2d47462d30367465737431323301b2e241383838383838157e
```
As shown below:
![Simulate request with packet tool](https://images.gitee.com/uploads/images/2020/1231/150635_85de7ac4_670717.jpeg)

## 3. Send Commands to Terminal

OpenAPI documentation is integrated. After startup, access:
* Knife4j UI: [http://127.0.0.1:8000/doc.html](http://127.0.0.1:8000/doc.html)
* Swagger UI: [http://127.0.0.1:8000/swagger-ui/index.html](http://127.0.0.1:8000/swagger-ui/index.html)

Enter parameters and click Send:
![Knife4j UI](https://images.gitee.com/uploads/images/2020/1231/115947_bb39bcd0_670717.jpeg)

* Device message monitor: [http://127.0.0.1:8000/ws.html](http://127.0.0.1:8000/ws.html)

![Console](https://images.gitee.com/uploads/images/2021/0714/171301_9f44b193_670717.jpeg)

# Protocol Extension

## 1. Define a Message
 ```java
package org.zendo.protocol.t808;

@Message(JT808.终端注册)
public class T0100 extends JTMessage {

    @Field(desc = "Province ID")
    private short provinceId;
    @Field(desc = "City/County ID")
    private short cityId;
    @Field(length = 11, desc = "Manufacturer ID")
    private String makerId;
    @Field(length = 30, desc = "Device Model")
    private String deviceModel;
    @Field(length = 30, desc = "Terminal ID")
    private String deviceId;
    @Field(desc = "Plate Color: 0=No Plate 1=Blue 2=Yellow 3=Black 4=White 9=Other")
    private byte plateColor;
    @Field(desc = "Vehicle Identification")
    private String plateNo;
}
```

## 2. Handle Terminal-Reported Messages
```java
package org.zendo.web.endpoint;

@Endpoint
public class JT808Endpoint {

    @Autowired
    private DeviceService deviceService;

    @Mapping(types = 0x0100, desc = "Terminal Registration")
    public T8100 register(T0100 message, Session session) {
        T8100 result = new T8100();
        result.setResponseSerialNo(message.getSerialNo());

        DeviceInfo device = deviceService.register(message);
        if (device != null) {
            session.register(message);

            result.setToken("1234567890A");
            result.setResultCode(T8100.Success);
        } else {
            result.setResultCode(T8100.NotFoundTerminal);
        }
        return result;
    }
}
```

## 3. Push Messages to Terminal (via Web API)
```java
package org.zendo.web.controller;

@RestController
@RequestMapping("device")
public class JT808Controller {

    @Autowired
    private MessageManager messageManager;

    @Operation(summary = "8103 Set Terminal Parameters")
    @PostMapping("8103")
    public Mono<T0001> T8103(@RequestBody T8103 request) {
        return messageManager.request(request, T0001.class);
    }
}
```

Annotation Reference:
* `@Message` — Message type, equivalent to Hibernate's `@Table`
* `@Field` — Message field, equivalent to Hibernate's `@Column`

* `@Endpoint` — Message entry point, equivalent to SpringMVC's `@Controller`
* `@Mapping` — Maps messages to methods, equivalent to SpringMVC's `@RequestMapping`
* `@Async` — Asynchronous message processing, for time-consuming operations (e.g., file writes)
* `@AsyncBatch` — Batch message processing; merges messages of the same type for high-concurrency scenarios (e.g., location reports) to improve database write performance


# Integration

If your business system uses Spring Boot, it is recommended to integrate this project via Maven dependency.

Run Maven's `install` command to install the `jtt808-protocol` module into your local Maven repository, or use `deploy` to publish it to a private Maven repository.

Add the following dependency to your `pom.xml`:
```xml
<dependency>
    <groupId>org.zendo</groupId>
    <artifactId>jtt808-protocol</artifactId>
    <version>1.0.0-SNAPSHOT</version>
</dependency>
```

Add the jtt808-server HTTP base URL to your `application.yml`:
```yml
jt808-service:
  base-url: http://127.0.0.1:8100
```

You can call the 808 service in your code as shown below.

For non-blocking usage, refer to `org.zendo.JT808ServiceTest` under `jtt808-server`:
```java
package com.xxx;

import org.zendo.protocol.service.JT808Service;

@Service
public class YourService {

    @Autowired
    private JT808Service jt808Service;

    public void test() {
        JTMessage message = new JTMessage();
        message.setClientId("12345678901");
        try {
            T0001 result = jt808Service.send("8304", message);
            System.out.println(result);
        } catch (WebClientResponseException e) {
            R error = e.getResponseBodyAs(R.class);
            System.out.println(error);
        }
    }
}
```

Directory Structure
```sh
├── Protocol Docs
│   ├── 808-2011 Protocol Spec
│   ├── 808-2013 Protocol Spec
│   ├── 808-2019 Protocol Spec
│   ├── 1078-2016 Protocol Spec
│   ├── Guangdong Standard-2020 Protocol Spec
│   ├── Jiangsu Standard-2016 Protocol Spec
│   └── packet-tool.exe
│
├──jtt808-protocol
│   │
│   ├──main
│   │   ├── t808  Message definitions
│   │   ├── t1078 Message definitions
│   │   ├── jstal12 Message definitions
│   │   └── codec  Encoding/Decoding
│   └──test
│       ├── codec    Protocol analysis tools
│       └── protocol Protocol unit tests
│
├──jtt808-server
│   ├──main
│   │   └── web  SpringBoot microservice
│   │      ├── config    808 service configuration
│   │      └── endpoint  808 message entry point; Netty-received requests are routed here via @Mapping
│   └──test
│      ├── ClientTest  Client
│      └── StressTest  Stress test
 ```
- JT808 refers to the JT/T 808 protocol, a transport industry communication standard issued by the Ministry of Transport of China. Full name: *Transport Industry Standard — Road Transport Vehicle Satellite Positioning System Terminal Communication Protocol and Data Format*.