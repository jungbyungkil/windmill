package com.windmill.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** TourAPI homepage 필드는 HTML 앵커이거나 순수 URL(또는 프로토콜 없는 도메인)일 수 있어 공용으로 정리한다 */
public final class HomepageUrlExtractor {

    private static final Pattern HREF = Pattern.compile(
            "href\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    private HomepageUrlExtractor() {
    }

    public static String extract(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        Matcher href = HREF.matcher(text);
        if (href.find()) {
            return normalizeUrl(href.group(1).trim());
        }
        // HTML 태그 제거 후 http 추출
        String plain = text.replaceAll("<[^>]+>", " ").trim();
        int http = plain.toLowerCase().indexOf("http");
        if (http >= 0) {
            String rest = plain.substring(http).split("[\\s\"'<>]+")[0];
            return normalizeUrl(rest);
        }
        return null;
    }

    private static String normalizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String u = url.trim();
        if (u.startsWith("//")) {
            return "https:" + u;
        }
        if (u.startsWith("http://") || u.startsWith("https://")) {
            return u;
        }
        if (u.contains(".") && !u.contains(" ")) {
            return "https://" + u;
        }
        return null;
    }
}
