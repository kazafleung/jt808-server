package org.zendo.web.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceServiceTest {

    @Test
    void sessionDurationIsSplitAcrossEveryCalendarDate() {
        Map<LocalDate, Long> result = DailyDiagnosticsService.splitDurationByDate(
                LocalDateTime.of(2026, 7, 12, 15, 30),
                LocalDateTime.of(2026, 7, 14, 0, 30),
                ZoneId.of("Asia/Hong_Kong"));

        assertEquals(Map.of(
                LocalDate.of(2026, 7, 12), 1_800L,
                LocalDate.of(2026, 7, 13), 86_400L,
                LocalDate.of(2026, 7, 14), 30_600L), result);
    }

    @Test
    void sessionDurationUsesActualDstDayLength() {
        Map<LocalDate, Long> result = DailyDiagnosticsService.splitDurationByDate(
                LocalDateTime.of(2026, 3, 8, 5, 0),
                LocalDateTime.of(2026, 3, 9, 4, 0),
                ZoneId.of("America/New_York"));

        assertEquals(Map.of(LocalDate.of(2026, 3, 8), 82_800L), result);
    }

    @Test
    void sessionDurationRejectsInvertedRange() {
        assertThrows(IllegalArgumentException.class, () ->
                DailyDiagnosticsService.splitDurationByDate(
                        LocalDateTime.of(2026, 7, 13, 1, 0),
                        LocalDateTime.of(2026, 7, 13, 0, 0),
                        ZoneId.of("Asia/Hong_Kong")));
    }

    @Test
    void dailyCounterAddsToExistingDailyDocument() {
        Date dayStart = new Date(0);
        Document expr = DailyDiagnosticsService.buildDailyCounterExpr("df", 1, 1, dayStart, null);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("df", new Document("ws", dayStart)
                .append("tot", 2)
                .append("bad", 1)
                .append("ratio", 0.5));

        Document result = (Document) eval(expr, root);

        assertEquals(3L, result.get("tot"));
        assertEquals(2L, result.get("bad"));
        assertEquals(2.0 / 3.0, (double) result.get("ratio"), 0.0001);
    }

    @Test
    void dailyDurationIgnoresAnAlreadyAppliedSession() {
        Date dayStart = new Date(0);
        Document alreadyApplied = new Document("$in", List.of(
                "session-1",
                new Document("$ifNull", List.of("$se", List.of()))));
        Document expr = DailyDiagnosticsService.buildDailyDurationExpr(
                "ol", 3_600L, 86_400L, dayStart, alreadyApplied);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("se", List.of("session-1"));
        root.put("ol", new Document("ws", dayStart)
                .append("base", 1_000L)
                .append("sec", 1_000L));

        Document result = (Document) eval(expr, root);

        assertEquals(1_000L, result.get("base"));
        assertEquals(1_000L, result.get("sec"));
    }

    @Test
    void dailyLocationCounterAccumulatesSupplementAndEventCounts() {
        Date dayStart = new Date(0);
        Document expr = DailyDiagnosticsService.buildDailyLocationExpr(3, 2, 600L, 1L, dayStart);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("dl", new Document("ws", dayStart)
                .append("tot", 5)
                .append("bad", 1)
                .append("ratio", 0.2)
                .append("sd", 300L)
                .append("ec", 2L));

        Document result = (Document) eval(expr, root);

        assertEquals(8L, result.get("tot"));
        assertEquals(3L, result.get("bad"));
        assertEquals(0.375, (double) result.get("ratio"), 0.0001);
        assertEquals(900L, result.get("sd"));
        assertEquals(3L, result.get("ec"));
    }

    @Test
    void statusUpdatePreservesPreviousLocationWhenIncomingLocationIsInvalid() {
        Date previousTime = new Date(1_000);
        Date incomingTime = new Date(2_000);
        Document previousLoc = new Document("type", "Point")
                .append("coordinates", List.of(114.1, 22.3));
        Document incomingLoc = new Document("type", "Point")
                .append("coordinates", List.of(0.0, 0.0));
        Document incomingStatus = new Document("dt", incomingTime)
                .append("loc", incomingLoc)
                .append("spd", 88);
        Document expr = DeviceService.buildStatusUpdateExpr(incomingStatus, incomingTime, false);

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("st", new Document("dt", previousTime)
                .append("loc", previousLoc)
                .append("spd", 12));

        Document result = (Document) eval(expr, device);

        assertEquals(incomingTime, result.get("dt"));
        assertEquals(previousLoc, result.get("loc"));
        assertEquals(88, result.get("spd"));
    }

    @Test
    void statusUpdateUsesIncomingLocationWhenLocationIsValid() {
        Date previousTime = new Date(1_000);
        Date incomingTime = new Date(2_000);
        Document previousLoc = new Document("type", "Point")
                .append("coordinates", List.of(114.1, 22.3));
        Document incomingLoc = new Document("type", "Point")
                .append("coordinates", List.of(114.2, 22.4));
        Document incomingStatus = new Document("dt", incomingTime)
                .append("loc", incomingLoc)
                .append("spd", 88);
        Document expr = DeviceService.buildStatusUpdateExpr(incomingStatus, incomingTime, true);

        Map<String, Object> device = new LinkedHashMap<>();
        device.put("st", new Document("dt", previousTime)
                .append("loc", previousLoc)
                .append("spd", 12));

        Document result = (Document) eval(expr, device);

        assertEquals(incomingTime, result.get("dt"));
        assertEquals(incomingLoc, result.get("loc"));
        assertEquals(88, result.get("spd"));
    }

    private static Object eval(Object expr, Map<String, Object> root) {
        if (expr instanceof String s && s.startsWith("$"))
            return resolvePath(root, s.substring(1));
        if (!(expr instanceof Document doc))
            return expr;
        if (doc.size() == 1 && doc.keySet().iterator().next().startsWith("$"))
            return evalOperator(doc, root);

        Document out = new Document();
        for (Map.Entry<String, Object> entry : doc.entrySet()) {
            out.append(entry.getKey(), eval(entry.getValue(), root));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object evalOperator(Document doc, Map<String, Object> root) {
        String op = doc.keySet().iterator().next();
        Object arg = doc.get(op);

        return switch (op) {
            case "$ifNull" -> {
                List<Object> args = (List<Object>) arg;
                Object value = eval(args.get(0), root);
                yield value == null ? eval(args.get(1), root) : value;
            }
            case "$lt" -> compare((List<Object>) arg, root) < 0;
            case "$eq" -> {
                List<Object> args = (List<Object>) arg;
                yield eval(args.get(0), root).equals(eval(args.get(1), root));
            }
            case "$in" -> {
                List<Object> args = (List<Object>) arg;
                Object value = eval(args.get(0), root);
                List<Object> values = (List<Object>) eval(args.get(1), root);
                yield values.contains(value);
            }
            case "$cond" -> {
                List<Object> args = (List<Object>) arg;
                yield Boolean.TRUE.equals(eval(args.get(0), root))
                        ? eval(args.get(1), root)
                        : eval(args.get(2), root);
            }
            case "$max" -> extremum((List<Object>) arg, root, true);
            case "$min" -> extremum((List<Object>) arg, root, false);
            case "$subtract" -> subtract((List<Object>) arg, root);
            case "$divide" -> {
                List<Object> args = (List<Object>) arg;
                yield toDouble(eval(args.get(0), root)) / toDouble(eval(args.get(1), root));
            }
            case "$toLong" -> (long) toDouble(eval(arg, root));
            case "$add" -> ((List<Object>) arg).stream()
                    .map(item -> eval(item, root))
                    .mapToLong(DeviceServiceTest::toLong)
                    .sum();
            default -> throw new IllegalArgumentException("Unsupported operator: " + op);
        };
    }

    private static int compare(List<Object> args, Map<String, Object> root) {
        Object left = eval(args.get(0), root);
        Object right = eval(args.get(1), root);
        if (left instanceof Date l && right instanceof Date r)
            return l.compareTo(r);
        return Long.compare(toLong(left), toLong(right));
    }

    private static Object extremum(List<Object> args, Map<String, Object> root, boolean max) {
        Object result = null;
        for (Object arg : args) {
            Object value = eval(arg, root);
            if (result == null || (max ? compareValues(value, result) > 0 : compareValues(value, result) < 0))
                result = value;
        }
        return result;
    }

    private static int compareValues(Object left, Object right) {
        if (left instanceof Date l && right instanceof Date r)
            return l.compareTo(r);
        return Long.compare(toLong(left), toLong(right));
    }

    private static Object subtract(List<Object> args, Map<String, Object> root) {
        Object left = eval(args.get(0), root);
        Object right = eval(args.get(1), root);
        if (left instanceof Date l && right instanceof Date r)
            return l.getTime() - r.getTime();
        return toLong(left) - toLong(right);
    }

    @SuppressWarnings("unchecked")
    private static Object resolvePath(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> currentMap))
                return null;
            current = ((Map<String, Object>) currentMap).get(part);
        }
        return current;
    }

    private static long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : (long) toDouble(value);
    }

    private static double toDouble(Object value) {
        return value instanceof Number number
                ? number.doubleValue()
                : Double.parseDouble(value.toString());
    }
}
