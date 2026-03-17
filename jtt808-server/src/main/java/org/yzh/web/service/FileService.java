package org.yzh.web.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import com.oracle.bmc.objectstorage.ObjectStorage;
import com.oracle.bmc.objectstorage.requests.PutObjectRequest;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.FileCopyUtils;
import org.yzh.commons.model.Tuple2;
import org.yzh.commons.util.DateUtils;
import org.yzh.commons.util.Exceptions;
import org.yzh.commons.util.IOUtils;
import org.yzh.protocol.jsatl12.DataPacket;
import org.yzh.protocol.jsatl12.T1210;
import org.yzh.protocol.jsatl12.T1211;
import org.yzh.protocol.t808.T0200;
import org.yzh.protocol.t808.T0801;
import org.yzh.web.config.JTProperties;
import org.yzh.web.config.OciProperties;
import org.yzh.web.model.entity.Device;
import org.yzh.web.model.entity.MediaRecord;
import org.yzh.web.model.enums.SessionKey;
import org.yzh.web.repository.MediaRecordRepository;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.nio.file.StandardOpenOption.*;

/**
 * @author yezhihao
 * https://gitee.com/yezhihao/jt808-server
 */
@Slf4j
@Service
public class FileService {

    @Resource
    private JTProperties jtProperties;

    @Resource
    private ObjectStorage objectStorage;

    @Resource
    private OciProperties ociProperties;

    @Resource
    private MediaRecordRepository mediaRecordRepository;

    private static final Comparator<long[]> comparator = Comparator.comparingLong((long[] a) -> a[0]).thenComparingLong(a -> a[1]);

    private String getDir(T1210 alarmId) {
        StringBuilder sb = new StringBuilder(80);
        sb.append(jtProperties.getT9208().getPath()).append('/');
        sb.append(alarmId.getClientId()).append('_');
        DateUtils.yyMMddHHmmss.formatTo(alarmId.getDateTime(), sb);
        sb.append('_').append(alarmId.getSerialNo()).append('/');
        return sb.toString();
    }

    /** 创建报警目录及 附件列表日志 */
    public void createDir(T1210 alarmId) {
        String dirPath = getDir(alarmId);
        new File(dirPath).mkdirs();

        List<T1210.Item> items = alarmId.getItems();
        StringBuilder fileList = new StringBuilder(items.size() * 50);
        fileList.append(dirPath).append('\n');

        for (T1210.Item item : items)
            fileList.append(item.getName()).append('\t').append(item.getSize()).append('\n');

        log.warn("文件列表: {}", fileList);
        Exceptions.ignore(() -> FileCopyUtils.copy(fileList.toString().getBytes(StandardCharsets.UTF_8), new File(dirPath, "fs.txt")));
    }

    private final LoadingCache<String, Tuple2<FileChannel, FileChannel>> channelsCache = Caffeine.newBuilder()
            .expireAfterAccess(10, TimeUnit.SECONDS)
            .evictionListener((RemovalListener<String, Tuple2<FileChannel, FileChannel>>) (key, value, cause) -> IOUtils.close(value.t1(), value.t2()))
            .build(path -> {
                FileChannel file = FileChannel.open(Paths.get(path + ".tmp"), CREATE, WRITE);
                FileChannel filelog = FileChannel.open(Paths.get(path + ".log"), CREATE, WRITE, APPEND);
                return new Tuple2<>(file, filelog);
            });

    /** 将数据块写入到报警文件，并记录日志 */
    public void writeFile(T1210 alarmId, DataPacket fileData, boolean last) {
        String dir = getDir(alarmId);
        String name = fileData.getName().strip();

        int offset = fileData.getOffset();
        int length = fileData.getLength();
        ByteBuf data = fileData.getData();
        try {
            Tuple2<FileChannel, FileChannel> channels = channelsCache.get(dir + name);
            FileChannel file = channels.t1();
            FileChannel filelog = channels.t2();

            data.readBytes(file, offset, data.readableBytes());
            filelog.write(ByteBuffer.allocate(8).putInt(offset).putInt(length).flip());
            if (last) {
                file.force(false);
                filelog.force(false);
                IOUtils.close(file, filelog);
            }
        } catch (IOException e) {
            log.error("写入报警文件", e);
        }
    }

    public void writeFileSingle(T1210 alarmId, DataPacket fileData) {
        String dir = getDir(alarmId);
        String name = fileData.getName().strip();

        int offset = fileData.getOffset();

        FileChannel file = null;
        ByteBuf data = fileData.getData();
        try {
            file = FileChannel.open(Paths.get(dir + name), CREATE, WRITE);
            data.readBytes(file, offset, data.readableBytes());
        } catch (IOException e) {
            log.error("写入报警文件", e);
        } finally {
            IOUtils.close(file);
        }
    }

    /** 根据日志检查文件完整性，并返回缺少的数据块信息 */
    public int[] checkFile(T1210 alarmId, T1211 fileInfo) {
        String dir = getDir(alarmId);
        String name = fileInfo.getName().strip();
        Path logPath = Paths.get(dir + name + ".log");

        int size;
        ByteBuffer buffer;
        try (FileChannel filelog = FileChannel.open(logPath, READ)) {
            size = (int) (filelog.size() / 8);
            buffer = ByteBuffer.allocate((int) filelog.size());
            filelog.read(buffer);
            buffer.flip();
        } catch (NoSuchFileException e) {
            return null;
        } catch (IOException e) {
            log.error("检查文件完整性", e);
            return null;
        }

        long[][] items = new long[size + 2][2];
        items[size + 1][0] = fileInfo.getSize();
        for (int i = 1; i <= size; i++) {
            items[i][0] = buffer.getInt();
            items[i][1] = buffer.getInt();
        }

        List<Integer> result = new ArrayList<>(items.length);
        int len = items.length - 1;
        Arrays.sort(items, 1, len, comparator);

        for (int i = 0; i < len; ) {
            long a = items[i][0] + items[i][1];
            long b = items[++i][0] - a;
            if (b > 0) {
                result.add((int) a);
                result.add((int) b);
            }
        }

        if (result.isEmpty()) {
            File file = new File(dir + name + ".tmp");
            File dest = new File(dir + name);
            if (file.renameTo(dest)) {
                try {
                    Files.delete(logPath);
                } catch (IOException ignored) {
                }
            }
            return null;
        }
        return result.stream().filter(Objects::nonNull).mapToInt(i -> i).toArray();
    }

    /** 多媒体数据上传 */
    public boolean saveMediaFile(T0801 message) {
        Device device = message.getSession().getAttribute(SessionKey.Device);
        T0200 location = message.getLocation();

        StringBuilder filename = new StringBuilder(32);
        filename.append(type(message.getType())).append('_');
        DateUtils.yyMMddHHmmss.formatTo(location.getDeviceTime(), filename);
        filename.append('_');
        filename.append(message.getChannelId()).append('_');
        filename.append(message.getEvent()).append('_');
        filename.append(message.getId()).append('.');
        filename.append(suffix(message.getFormat()));

        String deviceId;
        if (device == null)
            deviceId = message.getClientId();
        else
            deviceId = device.getDeviceId();

        File dir = new File(jtProperties.getT0801().getPath() + '/' + deviceId);
        dir.mkdirs();

        String objectStorePath = message.getClientId() + "/" + filename;

        ByteBuf packet = message.getPacket();
        FileOutputStream fos = null;
        try {
            File file = new File(dir, filename.toString());
            fos = new FileOutputStream(file);
            packet.readBytes(fos.getChannel(), 0, packet.readableBytes());
            fos.close();
            fos = null;

            try (FileInputStream fis = new FileInputStream(file)) {
                PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                        .bucketName(ociProperties.getBucket())
                        .namespaceName(ociProperties.getNamespace())
                        .objectName(objectStorePath)
                        .putObjectBody(fis)
                        .build();
                objectStorage.putObject(putObjectRequest);
            } catch (Exception e) {
                log.error("OCI Upload Failed", e);
            }

            // Convert deviceTime from CST (UTC+8) to UTC
            LocalDateTime deviceTimeUtc = location.getDeviceTime() == null ? null
                    : location.getDeviceTime().atZone(ZoneId.of("Asia/Shanghai")).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();

            MediaRecord record = new MediaRecord()
                    .setClientId(message.getClientId())
                    .setDeviceId(deviceId)
                    .setMediaId(message.getId())
                    .setChannelId(message.getChannelId())
                    .setMediaType(message.getType())
                    .setFormat(message.getFormat())
                    .setEvent(message.getEvent())
                    .setDeviceTime(deviceTimeUtc)
                    .setReceivedAt(LocalDateTime.now(ZoneOffset.UTC))
                    .setObjectStorePath(objectStorePath);
            mediaRecordRepository.save(record);

            return true;
        } catch (IOException e) {
            log.error("多媒体数据保存失败", e);
            return false;
        } finally {
            IOUtils.close(fos);
            packet.release();
        }
    }

    private static String type(int type) {
        return switch (type) {
            case 0 -> "image";
            case 1 -> "audio";
            case 2 -> "video";
            default -> "unknown";
        };
    }

    private static String suffix(int format) {
        return switch (format) {
            case 0 -> "jpg";
            case 1 -> "tif";
            case 2 -> "mp3";
            case 3 -> "wav";
            case 4 -> "wmv";
            default -> "bin";
        };
    }
}