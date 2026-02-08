import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class GrepNavigator {

    public static final class Hit {
        public final long absOffset;   // keyword absolute byte offset in file (0-based)
        public final long lineNo;      // 1-based
        public final long colOffset;   // 0-based (byte offset within line)
        public final String lineText;  // line content (no trailing \r)

        public Hit(long absOffset, long lineNo, long colOffset, String lineText) {
            this.absOffset = absOffset;
            this.lineNo = lineNo;
            this.colOffset = colOffset;
            this.lineText = lineText;
        }

        // your required format:
        @Override public String toString() {
            return lineNo + "," + colOffset + "," + absOffset + "," + lineText;
        }
    }

    private final String filePath;
    private final String pattern;      // fixed string
    private boolean wrap;
    private boolean reverse;
    private long cursor;               // absolute byte offset (0-based)

    public GrepNavigator(String filePath, String pattern) {
        this.filePath = Objects.requireNonNull(filePath);
        this.pattern = Objects.requireNonNull(pattern);
    }

    public void setWrap(boolean wrap) { this.wrap = wrap; }
    public void setReverse(boolean reverse) { this.reverse = reverse; }
    public void setCursor(long cursor) { this.cursor = Math.max(0, cursor); }
    public long getCursor() { return cursor; }

    public Hit next() throws IOException, InterruptedException {
        this.reverse = false;
        Hit hit = runSearch("next");
        if (hit != null) cursor = hit.absOffset + 1; // move forward
        return hit;
    }

    public Hit prev() throws IOException, InterruptedException {
        this.reverse = true;
        Hit hit = runSearch("prev");
        if (hit != null) cursor = hit.absOffset;     // stay at match start for prev
        return hit;
    }

    private Hit runSearch(String mode) throws IOException, InterruptedException {
        String sh = buildShell(mode, filePath, pattern, cursor, wrap);

        ProcessBuilder pb = new ProcessBuilder("bash", "-lc", sh);
        pb.redirectErrorStream(true);

        Process p = pb.start();
        String out;
        try (InputStream is = p.getInputStream()) {
            out = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
        int code = p.waitFor();

        if (code != 0) return null;

        // output expected:
        // ABS=<num>
        //
        // lineNo,col,lineText

        String[] lines = out.split("\n", -1);
        long abs = -1;
        String resultLine = null;
        for (String line : lines) {
            if (line.startsWith("ABS=")) {
                abs = Long.parseLong(line.substring(4).trim());
            } else if (!line.isBlank()) {
                // first non-empty non-ABS line is the result
                resultLine = line;
            }
        }
        if (abs < 0 || resultLine == null) return null;

        // parse "lineNo,col,lineText"
        int firstComma = resultLine.indexOf(',');
        int secondComma = resultLine.indexOf(',', firstComma + 1);
        if (firstComma < 0 || secondComma < 0) return null;

        long lineNo = Long.parseLong(resultLine.substring(0, firstComma));
        long col = Long.parseLong(resultLine.substring(firstComma + 1, secondComma));
        String text = resultLine.substring(secondComma + 1);

        return new Hit(abs, lineNo, col, text);
    }

    private static String buildShell(String mode, String filePath, String pattern, long cursor, boolean wrap) {
        String f = sq(filePath);
        String p = sq(pattern);
        String wrapFlag = wrap ? "1" : "0";

        // 说明：
        // - 用 grep -abo -F 输出：offset:match
        // - next：tail 从 cursor+1 开始取子串，grep -m1 找第一个 -> abs = cursor + rel
        // - prev：head 取 [0,cursor) 子串，grep 全部 -> tail -n1 取最后一个 -> abs=offset
        // - 找到 abs 后，用 awk 扫描到该 abs 所在行，输出 NR,col,line
        // - 同时 echo ABS=abs 供 Java 更新 cursor
        return ""
            + "set -euo pipefail; "
            + "file=" + f + "; pat=" + p + "; cursor=" + cursor + "; wrap=" + wrapFlag + "; "
            + "pat_len=$(printf %s \"$pat\" | wc -c); "
            + "abs=''; "
            + "find_next(){ "
            + "  start=$((cursor+1)); "
            + "  if [ \"$cursor\" -gt 0 ]; then start=$((cursor+2)); fi; "
            + "  base=$((start-1)); "
            + "  tail -c +$start \"$file\" "
            + "  | grep -abo -m1 -F -- \"$pat\" "
            + "  | awk -F: -v b=\"$base\" '{print b + $1; exit}' || true; "
            + "}; "
            + "find_prev(){ "
            + "  if [ \"$cursor\" -le 0 ]; then return 0; fi; "
            + "  head -c \"$((cursor + pat_len - 1))\" \"$file\" "
            + "  | grep -abo -F -- \"$pat\" "
            + "  | awk -F: -v c=\"$cursor\" '$1 < c {print $1}' "
            + "  | tail -n 1 || true; "
            + "}; "
            + "find_first(){ "
            + "  grep -abo -m1 -F -- \"$pat\" \"$file\" | awk -F: '{print $1; exit}' || true; "
            + "}; "
            + "find_last(){ "
            + "  grep -abo -F -- \"$pat\" \"$file\" | tail -n 1 | awk -F: '{print $1}' || true; "
            + "}; "
            + (mode.equals("next")
                ? "abs=$(find_next); "
                + "if [ -z \"$abs\" ] && [ \"$wrap\" = \"1\" ]; then abs=$(find_first); fi; "
                : "abs=$(find_prev); "
                + "if [ -z \"$abs\" ] && [ \"$wrap\" = \"1\" ]; then abs=$(find_last); fi; ")
            + "if [ -z \"$abs\" ]; then exit 2; fi; "
            + "echo \"ABS=$abs\"; "
            + "awk -v target=\"$abs\" '"
            + "BEGIN{pos=0}"
            + "{line=$0; sub(/\\r$/, \"\", line); len=length(line)+1; "
            + " if (pos + len > target) {col = target - pos; print NR \",\" col \",\" line; exit} "
            + " pos += len}"
            + "' \"$file\"; ";
    }

    // single-quote escape for bash: ' -> '\'' 
    private static String sq(String s) {
        return "'" + s.replace("'", "'\"'\"'") + "'";
    }

    // ---------------- demo ----------------
    public static void main(String[] args) throws Exception {
        // 假设 test.log 内容如下（你可以自己创建）：
        // this is an ERROR line
        // no error here
        // another ERROR happened

        GrepNavigator nav = new GrepNavigator("/home/qiumc/test.log", "ERROR");

        nav.setWrap(true);
        nav.setCursor(0);

        Hit h1 = nav.next();
        System.out.println(h1); // 期望：1,10,this is an ERROR line

        Hit h2 = nav.next();
        System.out.println(h2); // 期望：3,8,another ERROR happened

        Hit h3 = nav.prev();
        System.out.println(h3); // 期望：1,10,this is an ERROR line
    }
}
