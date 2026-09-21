package com.pitchcode.nextmove.data;

import com.pitchcode.nextmove.R;

import java.util.Calendar;

/**
 * Rotating "scam of the week" education content, stored locally (no backend).
 * The active tip changes each week so the app always has something fresh.
 */
public final class ScamTips {

    public static final class Tip {
        public final int titleRes;
        public final int bodyRes;

        Tip(int titleRes, int bodyRes) {
            this.titleRes = titleRes;
            this.bodyRes = bodyRes;
        }
    }

    private static final Tip[] TIPS = {
            new Tip(R.string.tip_digital_arrest_title, R.string.tip_digital_arrest_body),
            new Tip(R.string.tip_kyc_title, R.string.tip_kyc_body),
            new Tip(R.string.tip_refund_title, R.string.tip_refund_body),
            new Tip(R.string.tip_upi_request_title, R.string.tip_upi_request_body),
            new Tip(R.string.tip_job_title, R.string.tip_job_body),
            new Tip(R.string.tip_otp_title, R.string.tip_otp_body),
            new Tip(R.string.tip_electricity_title, R.string.tip_electricity_body),
            new Tip(R.string.tip_lottery_title, R.string.tip_lottery_body),
    };

    private ScamTips() {}

    public static int count() {
        return TIPS.length;
    }

    public static int currentIndex() {
        Calendar now = Calendar.getInstance();
        int week = now.get(Calendar.YEAR) * 53 + now.get(Calendar.WEEK_OF_YEAR);
        return Math.floorMod(week, TIPS.length);
    }

    public static Tip current() {
        return at(currentIndex());
    }

    public static Tip at(int index) {
        return TIPS[Math.floorMod(index, TIPS.length)];
    }
}
