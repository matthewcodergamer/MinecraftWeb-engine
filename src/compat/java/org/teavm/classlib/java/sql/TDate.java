package org.teavm.classlib.java.sql;

/** java.sql.Date: a java.util.Date holding only a date (used by Gson's SQL type adapters). */
public class TDate extends java.util.Date {
    private static final long serialVersionUID = 1L;

    public TDate(long date) {
        super(date);
    }

    public static TDate valueOf(String s) {
        String[] p = s.split("-");
        if (p.length != 3) {
            throw new IllegalArgumentException(s);
        }
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(Integer.parseInt(p[0]), Integer.parseInt(p[1]) - 1, Integer.parseInt(p[2]));
        return new TDate(c.getTimeInMillis());
    }
}
