package org.zendo.web.model.enums;

/**
 * @author yezhihao
 *         https://gitee.com/yezhihao/jt808-server
 */
public enum SessionKey {

    Device,

    /**
     * Timestamp (UTC) when the current TCP session was registered, for
     * online-duration tracking.
     */
    OnlineAt
}