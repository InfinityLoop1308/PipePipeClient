package org.schabi.newpipe.util;

import org.schabi.newpipe.database.playlist.PlaylistStreamEntry;
import org.schabi.newpipe.player.playqueue.PlayQueue;

import java.text.Collator;
import java.text.RuleBasedCollator;
import java.util.Locale;

public class utils {
    public static boolean DetimestampedEqual(String a, String b){
        return Detimestamp(a).equals(Detimestamp(b));
    }
    public static String Detimestamp(String a){
        String formatA = "#timestamp=";
        String formatB = "&timestamp=";
        String formatC = "?timestamp=";
        String format = "timestamp=";
        if(a.contains(format)){
            a = a.replace(formatA, "").replace(formatB, "")
                    .replace(formatC, "")
                    .replace(a.split("timestamp=")[1].split("&")[0], "");
        }
        return a;
    }
    public static SortMode parseSortMode(String sortMode) {
        // from SortMode.name() to SortMode
        switch (sortMode) {
            case "ORIGIN":
                return SortMode.ORIGIN;
            case "ORIGIN_REVERSE":
                return SortMode.ORIGIN_REVERSE;
            case "SORT_NAME":
                return SortMode.SORT_NAME;
            case "SORT_NAME_REVERSE":
                return SortMode.SORT_NAME_REVERSE;
            default:
                return SortMode.ORIGIN;
        }
    }
    public static int getIndexInQueue(PlaylistStreamEntry item, PlayQueue queue, SortMode sortMode){
        switch (sortMode) {
            case ORIGIN:
                return item.getJoinIndex();
            case ORIGIN_REVERSE:
                return queue.size() - item.getJoinIndex() - 1;
            case SORT_NAME:
            case SORT_NAME_REVERSE:
                int result = -1;
                for(int i = 0; i < queue.getStreams().size(); i++){
                    if(queue.getStreams().get(i).getUrl().equals(item.getStreamEntity().getUrl())){
                        result = i;
                    }
                }
                return result;
        }
        throw new RuntimeException("Failed to get index in queue");
    }
    public static int compareJapaneseStrings(String str1, String str2) {
        Collator collator = Collator.getInstance(Locale.JAPANESE);
        collator.setDecomposition(Collator.CANONICAL_DECOMPOSITION);
        collator.setStrength(Collator.IDENTICAL);
        return collator.compare(str1, str2);
    }
    public static int compareChineseStrings(String str1, String str2) {
        Collator collator = Collator.getInstance(Locale.CHINESE);
        return collator.compare(str1, str2);
    }
}
