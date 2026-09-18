import com.pitchcode.nextmove.safety.VoiceRiskAssessment;

public final class VoiceRiskAssessmentTest {
    public static void main(String[] args) {
        assertHighRisk("Pay now and share your OTP through this link");
        assertHighRisk("Police said I am under digital arrest and must send money immediately");
        assertHighRisk("अभी भुगतान करें और अपना ओटीपी बताएं");
        assertNeedsReview("My friend asked whether I am free for coffee tomorrow");
        assertNeedsReview("");
        System.out.println("Voice risk assessment checks passed.");
    }

    private static void assertHighRisk(String description) {
        VoiceRiskAssessment result = VoiceRiskAssessment.evaluate(description);
        if (!result.highRisk || result.signalCount < 2) {
            throw new AssertionError("Expected high risk: " + description);
        }
    }

    private static void assertNeedsReview(String description) {
        VoiceRiskAssessment result = VoiceRiskAssessment.evaluate(description);
        if (result.highRisk) {
            throw new AssertionError("Expected review/uncertain result: " + description);
        }
    }
}
