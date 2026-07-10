package com.techdevgroup.guidechain.web;

/** Minimal HTML escaping helper for hand-rendered fragments. Plain Java. */
final class Html
{
    private Html() {}

    /** Escape text for use in HTML content and attribute values. */
    static String esc(String s)
    {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++)
        {
            char c = s.charAt(i);
            switch (c)
            {
                case '&':  sb.append("&amp;");  break;
                case '<':  sb.append("&lt;");   break;
                case '>':  sb.append("&gt;");   break;
                case '"':  sb.append("&quot;"); break;
                case '\'': sb.append("&#39;");  break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }
}
