package org.zendo.protocol.t808;

import io.github.yezhihao.protostar.annotation.Field;
import io.github.yezhihao.protostar.annotation.Message;
import lombok.Data;
import lombok.ToString;
import lombok.experimental.Accessors;
import org.zendo.protocol.basics.JTMessage;
import org.zendo.protocol.commons.JT808;

import java.time.LocalDateTime;

/**
 * @author yezhihao
 * https://gitee.com/yezhihao/jt808-server
 */
@ToString
@Data
@Accessors(chain = true)
@Message(JT808.查询服务器时间应答)
public class T8004 extends JTMessage {

    @Field(length = 6, charset = "BCD", desc = "UTC时间")
    private LocalDateTime dateTime;

}