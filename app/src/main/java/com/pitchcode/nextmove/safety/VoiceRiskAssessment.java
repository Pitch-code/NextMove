package com.pitchcode.nextmove.safety;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class VoiceRiskAssessment {
    private static final String[] HIGH_RISK_TERMS = {
            "otp", "cvv", "upi pin", "password", "remote access", "screen share",
            "anydesk", "teamviewer", "digital arrest", "police", "customs", "parcel",
            "account blocked", "account closed", "pay now", "send money", "click link",
            "urgent", "immediately", "kyc", "refund", "lottery", "investment return",
            "ओटीपी", "सीवीवी", "यूपीआई पिन", "पासवर्ड", "डिजिटल अरेस्ट", "पुलिस",
            "खाता बंद", "अभी भुगतान", "पैसे भेजें", "लिंक खोलें", "तुरंत", "केवाईसी"
    };

    public final int signalCount;
    public final boolean highRisk;
    public final List<String> matchedTerms;

    private VoiceRiskAssessment(int signalCount, List<String> matchedTerms) {
        this.signalCount = signalCount;
        this.highRisk = signalCount >= 2;
        this.matchedTerms = Collections.unmodifiableList(matchedTerms);
    }

    public static VoiceRiskAssessment evaluate(String description) {
        String normalized = description == null ? "" : description.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String term : HIGH_RISK_TERMS) {
            if (normalized.contains(term)) matched.add(term);
        }
        if (normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("www.")) {
            matched.add("link");
        }
        return new VoiceRiskAssessment(matched.size(), matched);
    }
}
