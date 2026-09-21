package com.pitchcode.nextmove.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * On-device heuristic check for a phone number or a link/URL.
 *
 * This does NOT query any live database. It flags patterns commonly seen in
 * Indian scams (URL shorteners, look-alike domains, risky endings, premium-rate
 * or overly long numbers) so the user gets a fast, private, transparent hint.
 * The result always tells the user to verify through an official source.
 */
public final class LinkNumberCheck {

    public enum Kind { LINK, NUMBER, UNKNOWN }

    private static final String[] SHORTENERS = {
            "bit.ly", "tinyurl", "t.co", "goo.gl", "ow.ly", "is.gd", "rebrand.ly",
            "cutt.ly", "shorturl", "rb.gy", "tiny.cc", "bit.do"
    };

    // Cheap, suspicious top-level domains often used in throwaway scam sites.
    private static final String[] RISKY_TLDS = {
            ".xyz", ".top", ".club", ".online", ".site", ".click", ".link",
            ".work", ".buzz", ".rest", ".cn", ".ru", ".tk", ".ml", ".ga", ".cf"
    };

    // Brand names commonly impersonated; a look-alike domain is a strong signal.
    private static final String[] IMPERSONATED = {
            "sbi", "hdfc", "icici", "axis", "paytm", "phonepe", "gpay", "amazon",
            "flipkart", "irctc", "epfo", "aadhaar", "kyc", "netflix", "whatsapp"
    };

    public final Kind kind;
    public final int score;
    public final boolean highRisk;
    public final List<String> reasons;

    private LinkNumberCheck(Kind kind, int score, List<String> reasons) {
        this.kind = kind;
        this.score = score;
        this.highRisk = score >= 2;
        this.reasons = Collections.unmodifiableList(reasons);
    }

    public static LinkNumberCheck evaluate(String rawInput) {
        String input = rawInput == null ? "" : rawInput.trim();
        String lower = input.toLowerCase(Locale.ROOT);
        List<String> reasons = new ArrayList<>();

        boolean looksLikeLink = lower.contains("http://") || lower.contains("https://")
                || lower.contains("www.") || lower.matches(".*[a-z0-9-]+\\.[a-z]{2,}.*");
        String digits = input.replaceAll("[^0-9]", "");
        boolean looksLikeNumber = !looksLikeLink && digits.length() >= 5;

        Kind kind = looksLikeLink ? Kind.LINK
                : (looksLikeNumber ? Kind.NUMBER : Kind.UNKNOWN);

        if (kind == Kind.LINK) {
            if (lower.contains("http://")) reasons.add("no_https");
            for (String s : SHORTENERS) {
                if (lower.contains(s)) { reasons.add("shortener"); break; }
            }
            for (String t : RISKY_TLDS) {
                if (lower.contains(t)) { reasons.add("risky_tld"); break; }
            }
            for (String brand : IMPERSONATED) {
                // Brand name present but not as the real registered domain (brand.com/.in/.org).
                if (lower.contains(brand)
                        && !lower.contains(brand + ".com")
                        && !lower.contains(brand + ".in")
                        && !lower.contains(brand + ".org")
                        && !lower.contains(brand + ".net")) {
                    reasons.add("lookalike_brand");
                    break;
                }
            }
            if (input.matches(".*\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}.*")) {
                reasons.add("raw_ip");
            }
            if (lower.contains("@")) reasons.add("at_symbol");
            if (input.length() > 60) reasons.add("long_url");
        } else if (kind == Kind.NUMBER) {
            if (digits.length() > 12) reasons.add("too_long");
            if (digits.length() < 10) reasons.add("too_short");
            if (input.contains("+") && !input.trim().startsWith("+91")
                    && input.trim().startsWith("+")) {
                reasons.add("foreign_number");
            }
            // Indian mobile numbers start 6-9; a landline-style or odd prefix is worth noting.
            if (digits.length() == 10 && "012345".indexOf(digits.charAt(0)) >= 0) {
                reasons.add("unusual_prefix");
            }
        }

        // Blend in the shared scam-phrase engine for any surrounding text.
        VoiceRiskAssessment textRisk = VoiceRiskAssessment.evaluate(input);
        int score = reasons.size() + (textRisk.signalCount > 0 ? 1 : 0);
        if (textRisk.highRisk) reasons.add("scam_phrases");
        return new LinkNumberCheck(kind, score, reasons);
    }
}
