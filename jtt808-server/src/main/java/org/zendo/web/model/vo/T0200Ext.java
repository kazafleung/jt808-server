package org.zendo.web.model.vo;

import io.github.yezhihao.netmc.session.Session;
import org.zendo.protocol.commons.transform.attribute.*;
import org.zendo.protocol.t808.T0200;
import org.zendo.web.model.entity.Device;
import org.zendo.web.model.entity.DeviceStatus;
import org.zendo.web.model.enums.SessionKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author yezhihao
 *         https://gitee.com/yezhihao/jt808-server
 */
public class T0200Ext {

    private boolean updated;
    private List<Alarm> alarms;

    public T0200Ext(T0200 t) {
        Session session = t.getSession();
        this.alarms = getAlarms(t);

        Device device = session.getAttribute(SessionKey.Device);
        if (device != null)
            device.updateStatus(DeviceStatus.from(t));
    }

    private List<Alarm> getAlarms(T0200 t) {
        Map<Integer, Object> attributes = t.getAttributes();
        if (attributes != null) {
            List<Alarm> alarmList = new ArrayList<>();
            Alarm alarm = (Alarm) attributes.get(AlarmADAS.key);
            if (alarm != null && alarm.getState() != 2) {
                alarmList.add(alarm);
            }
            alarm = (Alarm) attributes.get(AlarmBSD.key);
            if (alarm != null && alarm.getState() != 2) {
                alarmList.add(alarm);
            }
            alarm = (Alarm) attributes.get(AlarmDSM.key);
            if (alarm != null && alarm.getState() != 2) {
                alarmList.add(alarm);
            }
            alarm = (Alarm) attributes.get(AlarmTPMS.key);
            if (alarm != null && alarm.getState() != 2) {
                alarmList.add(alarm);
            }
            alarm = (Alarm) attributes.get(InOutAreaAlarm.key);
            if (alarm != null) {
                alarm.setLocation(t);
                alarmList.add(alarm);
            }
            alarm = (Alarm) attributes.get(OverSpeedAlarm.key);
            if (alarm != null) {
                alarm.setLocation(t);
                alarmList.add(alarm);
            }
            alarm = (Alarm) attributes.get(RouteDriveTimeAlarm.key);
            if (alarm != null) {
                alarm.setLocation(t);
                alarmList.add(alarm);
            }
            return alarmList;
        }
        return Collections.emptyList();
    }

    public boolean updated() {
        return updated || !(updated = true);
    }

    public List<Alarm> getAlarms() {
        return alarms;
    }

    public void setAlarms(List<Alarm> alarms) {
        this.alarms = alarms;
    }
}