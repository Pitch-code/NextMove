package com.pitchcode.nextmove.safety;

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

    private VoiceRiskAssessment(int signalCount) {
        this.signalCount = signalCount;
        this.highRisk = signalCount >= 2;
    }

    public static VoiceRiskAssessment evaluate(String description) {
        String normalized = description.toLowerCase(Locale.ROOT);
        int count = 0;
        for (String term : HIGH_RISK_TERMS) {
            if (normalized.contains(term)) count++;
        }
        if (normalized.contains("http://") || normalized.contains("https://")
                || normalized.contains("www.")) {
            count++;
        }
        return new VoiceRiskAssessment(count);
    }
}
