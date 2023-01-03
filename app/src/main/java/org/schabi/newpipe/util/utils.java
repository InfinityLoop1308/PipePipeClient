package org.schabi.newpipe.util;

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
}
