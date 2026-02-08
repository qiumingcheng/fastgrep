import java.nio.file.*;
import java.util.*;

public class GrepNavigatorTest {
    private static void assertEquals(String label, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + " expected=" + expected + " actual=" + actual);
        }
    }

    private static void assertNull(String label, Object actual) {
        if (actual != null) {
            throw new AssertionError(label + " expected null but got=" + actual);
        }
    }

    public static void main(String[] args) throws Exception {
        Path temp = Files.createTempFile("grepnav", ".log");
        Files.writeString(temp, String.join("\n",
            "alpha",
            "beta ERROR gamma",
            "delta ERROR",
            "epsilon",
            "ERROR at start",
            "zeta"
        ));

        GrepNavigator nav = new GrepNavigator(temp.toString(), "ERROR");
        nav.setWrap(false);
        nav.setCursor(0);

        GrepNavigator.Hit h1 = nav.next();
        assertEquals("h1", "2,5,beta ERROR gamma", h1.toString());
        GrepNavigator.Hit h2 = nav.next();
        assertEquals("h2", "3,6,delta ERROR", h2.toString());
        GrepNavigator.Hit h3 = nav.next();
        assertEquals("h3", "5,0,ERROR at start", h3.toString());
        GrepNavigator.Hit h4 = nav.next();
        assertNull("h4", h4);

        GrepNavigator.Hit p1 = nav.prev();
        assertEquals("p1", "5,0,ERROR at start", p1.toString());
        GrepNavigator.Hit p2 = nav.prev();
        assertEquals("p2", "3,6,delta ERROR", p2.toString());
        GrepNavigator.Hit p3 = nav.prev();
        assertEquals("p3", "2,5,beta ERROR gamma", p3.toString());
        GrepNavigator.Hit p4 = nav.prev();
        assertNull("p4", p4);

        nav.setWrap(true);
        nav.setCursor(0);
        GrepNavigator.Hit w1 = nav.prev();
        assertEquals("w1", "5,0,ERROR at start", w1.toString());
        GrepNavigator.Hit w2 = nav.next();
        assertEquals("w2", "2,5,beta ERROR gamma", w2.toString());

        GrepNavigator nav2 = new GrepNavigator(temp.toString(), "MISSING");
        nav2.setWrap(true);
        nav2.setCursor(0);
        assertNull("missing next", nav2.next());
        assertNull("missing prev", nav2.prev());

        System.out.println("OK");
    }
}
