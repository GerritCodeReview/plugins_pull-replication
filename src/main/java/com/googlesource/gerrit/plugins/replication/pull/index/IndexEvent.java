package com.googlesource.gerrit.plugins.replication.pull.index;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public class IndexEvent {
    public long eventCreatedOn = System.currentTimeMillis() / 1000;
    public String targetSha;

    @Override
    public String toString() {
        return "IndexEvent@" + format(eventCreatedOn) + ((targetSha != null) ? "/" + targetSha : "");
    }

    public static String format(long eventTs) {
        return LocalDateTime.ofEpochSecond(eventTs, 0, ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_DATE_TIME);
    }
}